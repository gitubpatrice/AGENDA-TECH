Voici la relecture du correctif. J'ai refait l'arithmétique et audité les flux de données comme demandé. 

Il y a **deux défauts majeurs** qui doivent bloquer la release ce soir : une nouvelle forme de famine arithmétique pire que la précédente, et une fuite de mot de passe en mémoire.

Voici les constats, classés par ordre de valeur.

---

### 1. L'arithmétique et le budget (La zone la plus critique)

#### 🔴 Famine sur population mixte (Pire que F5-ter)
`app/src/main/java/com/filestech/agenda_tech/system/alarm/ReminderScheduler.kt:125`
* **Sévérité :** CRITIQUE
* **Statut :** CONFIRMÉ
* **Scénario :** Le KDoc affirme que ce schéma corrige la famine. C'est vrai pour une population *homogène*, mais **ce correctif détruit les populations mixtes** là où l'ancien code (F5-ter) les sauvait.
  * **Nombres :** Budget total = 1 000 000. Plafond = 10 000. Population de 1 000 rappels : 112 rappels "lourds" (nécessitant 9 000 itérations, ex: vieilles séries) placés en début de liste, suivis de 888 rappels "légers" (nécessitant 100 itérations).
  * **Avec l'ancien code (F5-ter) :** Part = 1 000. Les 112 lourds échouent (0 armé). Les 888 légers réussissent (888 armés). **Total : 888 armés.**
  * **Avec ce nouveau code :** Les 111 premiers lourds reçoivent 10 000, consomment 9 000 chacun (total : 999 000). Reste au budget : 1 000. Le 112ème lourd reçoit 1 000, échoue. Reste au budget : 0. Les 888 légers reçoivent `maxOf(1, 0) = 1`, ont besoin de 100, et **échouent tous**. **Total : 111 armés.**
  * **Conclusion :** 11% de la base vient de siphonner le budget entier, affamant les 89% restants. Le plafond de 10 000 empêche *une* série de tout manger, mais n'empêche pas *un groupe* de séries de le faire.

#### 🟠 Dépassement systématique du plafond total
`app/src/main/java/com/filestech/agenda_tech/system/alarm/ReminderScheduler.kt:125`
* **Sévérité :** MINEURE
* **Statut :** CONFIRMÉ
* **Scénario :** Le plancher `maxOf(1, ...)` garantit qu'un budget n'est jamais de 0. Cependant, quand `pass.remaining` tombe à 0, chaque rappel suivant reçoit une allocation de 1. `tryConsume()` autorise cette itération, et `pass.charge(1)` l'ajoute au total.
  * **Nombres :** Si le budget s'épuise au 500ème rappel et qu'il en reste 5 000 derrière, le total consommé sera de `1 000 000 + 5 000 = 1 005 000`.
  * **Conclusion :** Le total n'est pas strictement borné à 1 000 000, il est borné à `1 000 000 + (Rappels restants)`. Le coût réel (1 itération par rappel) est négligeable en CPU, donc ce n'est pas dangereux, mais l'affirmation "borné à 1 000 000" est techniquement fausse.

#### 🟢 Le plafond de 10 000 itérations
* **Statut :** SAIN
* 10 000 itérations couvrent effectivement ~27 ans pour une série quotidienne. C'est un bon arbitrage. Le seul cas d'échec serait un import d'un vieil agenda (ex: un événement quotidien créé en 1990), qui taperait le plafond et ne sonnerait jamais. C'est un cas à la marge acceptable.

#### 🟢 Les rappels non récurrents
* **Statut :** SAIN
* Le code garantit bien leur armement. Si le budget est épuisé, `allowance` vaut 1. `nextOccurrenceStart` répond immédiatement sans appeler `tryConsume()`. `allowance.spent` reste à 0, `isExhausted` est évalué à `0 >= 1` (Faux). Le rappel est armé. Le KDoc dit vrai.

---

### 2. Ce qui reste non armé (Visibilité utilisateur)

#### 🟠 Silence radio sur les rappels abandonnés
`app/src/main/java/com/filestech/agenda_tech/system/alarm/ReminderScheduler.kt:240`
* **Sévérité :** MOYENNE
* **Statut :** CONFIRMÉ
* **Scénario :** L'arbitrage de ne pas réarmer (et d'attendre la prochaine sauvegarde de l'événement) est le seul techniquement viable sans relancer une passe complète. **Cependant**, l'utilisateur n'en sait rien car `Timber.w` est supprimé en release.
* **Ce qui devrait se passer :** Le système devrait lever une notification locale (via `ReminderNotifier`) du type *"Certains rappels n'ont pas pu être planifiés"*, pour que l'utilisateur sache que son agenda n'est pas fiable à 100 % ce jour-là. En l'état, il ratera ses réunions sans comprendre pourquoi.

---

### 3. Les autres correctifs du lot

#### 🔴 Fuite du mot de passe en mémoire sur annulation
`app/src/main/java/com/filestech/agenda_tech/ui/screens/backup/BackupViewModel.kt:88`
* **Sévérité :** HAUTE (Critique pour un audit sécu)
* **Statut :** CONFIRMÉ
* **Scénario :** Dans `onExportTargetPicked`, si `uri != null`, vous faites `pendingExportPassword = null` puis appelez `export(uri, password)`. `export` lance une coroutine (`viewModelScope.launch`) qui capture le tableau `password`.
  * Si l'utilisateur quitte l'écran immédiatement (ou si la coroutine est annulée avant la fin de `exportBackup`), le bloc `finally` de `BackupEnvelope` ne sera jamais atteint.
  * Le tableau `CharArray` reste capturé dans la closure de la coroutine morte, en clair en mémoire, et ne sera **jamais effacé** (le Garbage Collector ne nettoie pas les octets). `onCleared` ne peut plus le sauver car `pendingExportPassword` a été mis à null.
  * **Correction requise :** Le `password.wipe()` doit être garanti par un `try/finally` *à l'intérieur* de la coroutine dans `export()`.

#### 🟢 BackupEnvelope (Versions et KDF)
* **Statut :** SAIN
* Le passage à `>` au lieu de `!=` est correct et empêche le rejet d'un fichier valide. Les 4 verdicts sont mutuellement exclusifs et exhaustifs. Un fichier hostile avec un faux header sera rejeté par le tag GCM plus tard. Le mot de passe est bien effacé sur toutes les sorties (garanti par le `finally` de `deriveKey`).

#### 🟢 SettingsViewModel (Widget Refresh)
* **Statut :** SAIN
* Le passage à `appScope.launch` est correct. Il n'y a pas de fuite de contexte car `context` est l'ApplicationContext. La `CancellationException` est bien relancée. Si deux rafraîchissements sont concurrents, l'API `GlanceAppWidget.updateAll` gère sa propre file d'attente interne de manière sûre.

#### 🟢 ImportDeviceEventsUseCase (Truncated & Remaining)
* **Statut :** SAIN
* `remaining` peut effectivement devenir négatif si un calendrier renvoie plus d'événements que demandé, mais la condition de boucle `remaining <= 0` gère cela parfaitement.
* Le drapeau `truncated` dit la vérité : il est évalué *avant* la tentative d'écriture. Si l'écriture échoue, le calendrier est ignoré, mais le fait que la lecture ait été tronquée remonte bien à l'UI. C'est le comportement attendu.

---

### 4. Les commentaires (KDoc vs Réalité)

#### 🟠 Fausse promesse sur la résolution de la famine
`app/src/main/java/com/filestech/agenda_tech/system/alarm/ReminderScheduler.kt:100`
* **Sévérité :** MOYENNE
* **Statut :** CONFIRMÉ
* **Phrase :** *"the rest are served in order until the pass total is gone, exactly as before F5-ter, with the hogging removed."*
* **Réalité :** Comme prouvé dans le point 1, le "hogging" (accaparement) n'est pas supprimé, il est juste plafonné à 10 000 par série. Un groupe de séries lourdes peut toujours accaparer le budget et affamer le reste. Le commentaire affirme une garantie mathématique que le code ne tient pas.

---

### Résumé pour la release de ce soir :
1. **À corriger immédiatement :** La fuite de mot de passe dans `BackupViewModel.export` (ajoutez un `try/finally` dans la coroutine).
2. **À corriger immédiatement :** L'algorithme de budget dans `ReminderScheduler`. Le remède actuel (F5-quater) est pire que la maladie (F5-ter) sur les agendas hétérogènes. Si vous n'avez pas le temps de repenser l'algo d'ici une heure, envisagez de remonter le budget total (`newPassBudget`) à 5 000 000 pour mitiger le risque de famine en production.
3. **Peut attendre la 1.0.1 :** La notification utilisateur pour les rappels non armés, et la correction du KDoc.