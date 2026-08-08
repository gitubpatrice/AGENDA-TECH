Voici les faiblesses identifiées, en me concentrant sur les angles morts des correctifs récents et les comportements de repli. 

### 1. La restauration de la base de données brique l'application (Asymétrie / Mauvais repli)
`app/src/main/java/com/filestech/agenda_tech/data/local/db/DatabaseFactory.kt:231`

**Scénario concret :** Lors de la réparation d'une base "zéro-clé" (SEC-1), si le changement de mot de passe échoue, l'application tente de restaurer les fichiers `.prerekey`. Dans `restore()`, la boucle `forEach` itère sur `[db, db-wal, db-shm]`. Si la suppression de la base principale échoue (ex: verrouillage de fichier par l'OS), le code fait un `return@forEach`. 
**Le défaut :** `return@forEach` ne quitte pas la fonction, il passe au fichier suivant. L'application laisse donc la base principale corrompue/nouvelle en place, mais **restaure avec succès l'ancien fichier `-wal` par-dessus**. Au prochain lancement, SQLite trouvera un fichier WAL qui ne correspond pas à la base principale et lancera une `SQLITE_CORRUPT` ou `SQLITE_NOTADB` irrécupérable.
**Sévérité :** CRITIQUE. Brique définitivement l'agenda sans déclencher le flag de reset (puisque ce n'est pas une erreur Keystore).
**Statut :** CONFIRMÉ.

### 2. La corruption des préférences ouvre grand les portes (Repli du mauvais côté)
`app/src/main/java/com/filestech/agenda_tech/MainActivity.kt:135`

**Scénario concret :** Le fichier des préférences (DataStore/SharedPreferences) est corrompu (coupure de batterie pendant une écriture, ou action malveillante via un accès physique). Le système le remplace par les valeurs par défaut. La valeur par défaut de `lock_enabled` est `false`.
**Le défaut :** L'application détecte cette corruption (`OneShotFlag.SETTINGS_RESET.consume()`), affiche un Toast "Paramètres réinitialisés", puis lit `lockRepository.isLockEnabled()` qui renvoie `false`. Elle appelle donc `appLock.unlock()`. **L'agenda est désormais totalement accessible sans code PIN ni biométrie.** Pour une application qui promet un verrouillage local strict, échouer "ouvert" sur une corruption d'état est une faille majeure.
**Sévérité :** CRITIQUE. Contournement complet du verrou applicatif.
**Statut :** CONFIRMÉ.

### 3. Le budget d'expansion tue toujours les alarmes au redémarrage (Moitié de correctif / Commentaire menteur)
`app/src/main/java/com/filestech/agenda_tech/system/alarm/ReminderScheduler.kt:159`

**Scénario concret :** Le téléphone redémarre. Android efface toutes les alarmes exactes. Le `BOOT_COMPLETED` lance `rescheduleAll()`. L'utilisateur a un import pathologique qui épuise le `ExpansionBudget` au milieu de la passe.
**Le défaut :** Le correctif F5-bis a modifié `schedule()` pour ne plus appeler `cancel()` quand le budget est épuisé, avec le commentaire : *"leaving those alarms alone, not destroying them"*. C'est vrai en cours d'exécution. Mais **au redémarrage, l'alarme est déjà détruite par l'OS**. En faisant un `return` précoce sans réarmer l'alarme, la queue pathologique des rappels reste silencieusement et définitivement désarmée. Le correctif a traité le symptôme (l'appel explicite à `cancel`) mais ignoré le contexte d'appel principal.
**Sévérité :** HAUTE. Perte silencieuse des rappels de l'utilisateur.
**Statut :** CONFIRMÉ.

### 4. Fuite du budget mémoire sur échec d'écriture (Moitié de correctif)
`app/src/main/java/com/filestech/agenda_tech/domain/usecase/ImportDeviceEventsUseCase.kt:119`

**Scénario concret :** L'utilisateur importe 5 calendriers. Le premier contient 10 000 événements. Ils sont lus depuis le provider (coût en mémoire et temps), mais l'insertion `eventRepository.upsertAll` échoue (ex: transaction trop volumineuse ou contrainte SQL). Le bloc `runCatching` passe dans `onFailure`.
**Le défaut :** Le budget global `remaining` n'est décrémenté **que dans le bloc `onSuccess`**. En cas d'échec d'écriture, les 10 000 événements lus ne sont pas déduits du budget. La boucle passe au calendrier suivant avec le budget intact. Si plusieurs calendriers échouent à l'écriture, l'application lira des dizaines de milliers d'événements en mémoire, contournant totalement le plafond `ImportLimits.MAX_EVENTS` censé empêcher les OOM.
**Sévérité :** HAUTE. Annule la protection mémoire introduite par DR-9.
**Statut :** CONFIRMÉ.

### 5. Les alarmes repoussées (snooze) survivent à la suppression (Jumeaux asymétriques)
`app/src/main/java/com/filestech/agenda_tech/system/alarm/ReminderScheduler.kt:101`

**Scénario concret :** Une alarme sonne, l'utilisateur clique sur "Rappeler dans 10 minutes" (`snooze()`). Une minute plus tard, il ouvre l'application et supprime l'événement. `cancelEvent()` est appelé.
**Le défaut :** `cancelEvent()` itère sur les rappels et appelle `cancel(it.id)`. Cette fonction reconstruit le `PendingIntent` avec l'action `ACTION_FIRE` et le request code `reminderId`. Mais l'alarme repoussée a été créée avec l'action `ACTION_SNOOZE_FIRE` et un request code basé sur un hash `(eventId, occurrenceStart)`. L'alarme repoussée n'est **jamais annulée**. Elle réveillera l'application pour un événement qui n'existe plus.
**Sévérité :** MODÉRÉE. Comportement fantôme.
**Statut :** CONFIRMÉ.

### 6. Le bug du "I" turc casse la résolution des fuseaux horaires (Mauvais repli)
`app/src/main/java/com/filestech/agenda_tech/core/time/TimeZones.kt:32`

**Scénario concret :** Un utilisateur en Turquie (locale `tr-TR`) ou en Azerbaïdjan importe un `.ics` Outlook contenant le fuseau "India Standard Time" ou "Pacific Standard Time".
**Le défaut :** La ligne `WINDOWS_TO_IANA[candidate.lowercase()]` utilise la locale par défaut de l'appareil. En turc, le "I" majuscule mis en minuscules devient "ı" (i sans point), et non "i". La chaîne devient `"ındia standard time"`, ce qui ne correspondra jamais à la clé de la map. Un fuseau parfaitement valide sera rejeté et retombera sur l'heure UTC ou l'heure de l'appareil, décalant les rendez-vous.
**Sévérité :** MODÉRÉE. Altération silencieuse des données pour une population spécifique. (Il faut utiliser `lowercase(Locale.ROOT)` ou `Locale.US` pour des clés de protocole).
**Statut :** CONFIRMÉ.

---
*Note sur les zones saines :* La logique de lecture bornée (`BoundedRead.kt`) est très solide, le refus de réinitialiser le compteur de lectures vides est la bonne décision face aux wrappers de documents malveillants. Le verrouillage par Mutex dans `AppLockManager.kt` englobe correctement le hachage PBKDF2, fermant bien la faille S16.