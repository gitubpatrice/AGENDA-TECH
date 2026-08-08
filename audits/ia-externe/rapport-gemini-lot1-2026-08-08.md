Voici la relecture de sécurité et de robustesse pour le lot 1 d'Agenda Tech.

### Réponse à la question précise (Android 12 et `SCHEDULE_EXACT_ALARM`)
**Oui.** Sur Android 12, lorsque l'utilisateur révoque la permission `SCHEDULE_EXACT_ALARM`, le système annule effectivement toutes les alarmes exactes déjà posées par l'application et émet le broadcast `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`. Puisque l'application ne l'écoute pas pour basculer sur des alarmes inexactes ou les replanifier, tous les rappels sont perdus jusqu'au prochain déclencheur (redémarrage ou édition).

---

### 1. La garde est posée sur l'AFFICHAGE, pas sur l'ACCÈS
**Aucun défaut sur ce motif.** L'accès aux données via l'UI est correctement bloqué par le `LockScreen` dans la `MainActivity` qui englobe tout le graphe de navigation. Le widget accède aux données en contournant le verrou, mais c'est un choix de conception assumé et documenté (le masquage des titres est appliqué).

---

### 2. Le repli échoue du mauvais côté

**Défaut 1 : Effacement destructif de la base de données sur erreur transitoire**
- **Fichier :** `app/src/main/java/com/filestech/agenda_tech/data/local/db/DatabaseFactory.kt:41`
- **Motif :** Le repli échoue du mauvais côté (Destruction au lieu de préservation).
- **Sévérité :** CRITIQUE
- **Scénario :** Lors de l'ouverture de la base, une erreur d'I/O transitoire survient en lisant `master.key`, ou le Keystore échoue temporairement à déchiffrer (fréquent sur certains appareils). `DatabaseKeyManager` lève une exception typée (`Failure.Io` ou `Failure.WrapCorrupted`) prévue pour déclencher un flux de récupération sans perte. Cependant, `DatabaseFactory.build` attrape l'exception générique `catch (e: Exception)` et exécute immédiatement `context.deleteDatabase(...)`. L'agenda entier de l'utilisateur est silencieusement et irréversiblement détruit à cause d'un simple faux contact cryptographique.
- **Confiance :** CERTAIN
- **Risque du correctif :** Retirer la suppression automatique obligera à remonter l'erreur jusqu'à l'UI pour guider l'utilisateur. Il faut s'assurer que l'application ne crashe pas en boucle au démarrage si la clé est *réellement* corrompue.

**Défaut 2 : Échec silencieux de l'activation du verrouillage PIN**
- **Fichier :** `app/src/main/java/com/filestech/agenda_tech/data/local/settings/LockRepositoryImpl.kt:47`
- **Motif :** Le repli échoue du mauvais côté (Ouverture au lieu de fermeture).
- **Sévérité :** CRITIQUE
- **Scénario :** L'utilisateur configure un code PIN. Lors de l'enregistrement, le Keystore échoue à chiffrer le blob (ex: erreur interne du TEE), et `wrap()` retourne `null`. La fonction `setPin` exécute `if (wrapped == null) return@withContext` et se termine silencieusement en retournant `Unit`. L'UI considère que le PIN a été configuré avec succès et passe à l'écran suivant, mais `LOCK_ENABLED` n'a jamais été passé à `true`. L'application reste totalement déverrouillée à l'insu de l'utilisateur.
- **Confiance :** CERTAIN
- **Risque du correctif :** Faire remonter une erreur (ex: `Outcome.Failure` ou exception) nécessitera d'adapter le ViewModel appelant pour afficher un message d'erreur, sinon l'UI plantera.

---

### 3. Le jumeau asymétrique

**Défaut 3 : Blocage du Thread Principal dans les BroadcastReceivers**
- **Fichier :** `app/src/main/java/com/filestech/agenda_tech/system/alarm/BootReceiver.kt:23` (et `ReminderReceiver.kt:34`)
- **Motif :** Le jumeau asymétrique (vs `MainActivity`).
- **Sévérité :** MAJEUR
- **Scénario :** Dans `MainActivity`, l'auteur a correctement utilisé un `Provider<AppDatabase>` et `Dispatchers.IO` pour éviter de bloquer le thread principal lors de l'initialisation de SQLCipher et du Keystore. En revanche, `BootReceiver` et `ReminderReceiver` injectent directement `ReminderScheduler`, qui requiert transitivement `AppDatabase`. Lors du réveil par le système, Hilt instancie l'arbre de dépendances *sur le thread principal* avant même d'atteindre `goAsync()`. Les appels I/O et Keystore bloquent le thread principal, ce qui provoque un ANR ou un timeout du broadcast, entraînant la perte silencieuse des alarmes.
- **Confiance :** CERTAIN
- **Risque du correctif :** Injecter un `Provider<ReminderScheduler>` et appeler `.get()` dans la coroutine (après `goAsync()`) décalera l'initialisation. Il faut s'assurer que le temps d'exécution total dans la coroutine ne dépasse pas le budget du broadcast (~10s).

**Défaut 4 : Recherche sans budget d'expansion (Gel de l'application)**
- **Fichier :** `app/src/main/java/com/filestech/agenda_tech/domain/usecase/SearchEventsUseCase.kt:119` (et `RecurrenceExpander.kt:76`)
- **Motif :** Le jumeau asymétrique (vs `ObserveOccurrencesInRangeUseCase`).
- **Sévérité :** CRITIQUE
- **Scénario :** `ObserveOccurrencesInRangeUseCase` utilise correctement un `ExpansionBudget` pour plafonner le coût total d'un rendu. À l'inverse, `SearchEventsUseCase` appelle `expander.nextOccurrenceStart` pour dater chaque résultat de recherche, mais ne lui passe *aucun budget*. Si l'utilisateur tape une lettre fréquente (ex: "a"), la recherche trouve des centaines d'événements récurrents anciens. Pour chacun, l'expander itère depuis la date de création jusqu'à aujourd'hui (jusqu'à 100 000 itérations par événement). 100 événements trouvés = 10 millions d'itérations exécutées de manière synchrone à chaque frappe clavier, figeant totalement l'application (ANR).
- **Confiance :** CERTAIN
- **Risque du correctif :** Passer un budget partagé à `nextOccurrenceStart` lors de la recherche risque de tronquer les dates des derniers résultats si le budget est épuisé. Il faudra peut-être dater les résultats de manière paresseuse ou augmenter le budget pour la recherche.

---

### 4. Le chemin mort, et le test vert qui le couvre
**Aucun défaut sur ce motif.** Les chemins conditionnels et les hiérarchies de types examinés sont tous atteignables et exploités par le produit.

---

### 5. Points durs propres au produit

**Défaut 5 : Perte de données sur les propriétés répétées à l'import ICS**
- **Fichier :** `app/src/main/java/com/filestech/agenda_tech/domain/ics/IcsCodec.kt:133`
- **Motif :** Points durs propres au produit (Propriétés répétées).
- **Sévérité :** MAJEUR
- **Scénario :** Un fichier `.ics` valide contient un événement récurrent avec plusieurs lignes `EXDATE` (pour annuler plusieurs occurrences), ce qui est standard dans la RFC 5545. La boucle de décodage stocke les propriétés dans une `MutableMap<String, IcsProperty>`. L'instruction `current[property.name] = property` écrase systématiquement les valeurs précédentes ayant le même nom. Seule la toute dernière ligne `EXDATE` est conservée. L'événement est importé, mais toutes les autres occurrences annulées ressuscitent silencieusement dans l'agenda de l'utilisateur.
- **Confiance :** CERTAIN
- **Risque du correctif :** Changer la structure en `Map<String, List<IcsProperty>>` impactera toute la logique de parsing en aval (`parseVEvent`, `parseRRule`) qui s'attend actuellement à une valeur unique.