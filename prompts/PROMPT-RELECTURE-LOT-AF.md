# Relecture contradictoire — Agenda Tech, mes propres correctifs (lots A et F)

Tu relis **le travail d'un autre assistant**, pas le code d'origine de l'application. Ce point est
important : sur ce dépôt précisément, les correctifs ont produit **8 nouveaux défauts en 2 passes de
revue** lors de la v0.5.4, et sur une application sœur un correctif a introduit un nouveau défaut
**9 fois d'affilée**. Ton rôle est de trouver ce qui a été cassé ou mal fait ICI.

Contexte : **Agenda Tech**, agenda Android natif (Kotlin, Compose, Hilt, Room + SQLCipher, Glance,
androidx.biometric), **100 % local, chiffré, sans aucune permission réseau**. Version publiée v0.5.4,
module Gradle unique `:app`, dépôt **public**. `allowBackup=false` : la base est le SEUL exemplaire de
l'agenda de l'utilisateur sur l'appareil.

Les fichiers **entiers** sont fournis ci-dessous. Tu n'as pas accès au dépôt : ce que tu ne vois pas, tu
ne peux pas le supposer absent — pose-le comme question, pas comme constat.

---

## 1. Ce qui a été fait, et pourquoi

### Lot A — le contrôle qualité passait de 520 défauts detekt à 0

Le gate documenté (`assembleDebug testDebugUnitTest lintDebug detekt`) échouait sur `main`, à la version
publiée, et n'avait **jamais** été vert. Il n'y avait aucune baseline. Dépouillement du rapport XML :

- **320 sur 520** = un seul fait compté deux fois. `PackageName` (ktlint) et `PackageNaming` (detekt)
  rejettent tous deux l'underscore de `com.filestech.agenda_tech`, sur 160 fichiers.
- **160** = mise en page pure (98 `ArgumentListWrapping` à eux seuls).
- **20** = 10 imports réellement morts, comptés deux fois.
- **20** = signaux sémantiques réels, tous mineurs.

Traitement : les 10 imports morts supprimés ; 2 `if/else` multi-lignes sans accolades corrigés ; 1
exception avalée journalisée ; les seuils sémantiques relevés **avec la raison écrite dans le fichier**
après vérification site par site ; 2 `@Suppress` ciblés ; et les règles de mise en page du préréglage
`android: true` de ktlint désactivées nommément, avec justification.

### Lot F — premiers tests instrumentés + CI

`app/src/androidTest/` ne contenait qu'un runner et **aucun test**, alors que 7 dépendances androidTest
étaient déclarées. `.github/` n'existait pas du tout.

Deux classes de tests instrumentés ont été ajoutées, exécutées sur un **Galaxy S9 réel (API 29)** :
5 tests verts, puis **vérifiés par sabotage** (clé nulle injectée dans `SupportOpenHelperFactory`, et
`MIGRATION_4_5` retirée de `Migrations.ALL`) → **5 échecs sur 5**, sabotages ensuite retirés.

Une CI a été créée : un job `gate`, un job `zero-network` qui fusionne le manifeste release et lance
`tools/check-manifest-permissions.py`, un job `instrumented` sur émulateur API 29.

---

## 2. Contexte indispensable pour juger — ne PAS signaler ceci

- **Le modèle temporel est délibéré et correct.** Un `Event` porte des instants absolus
  (`startUtcMillis`) **et** un `timeZoneId` IANA ; les occurrences sont ancrées à l'heure murale dans ce
  fuseau. C'est le modèle de `CalendarContract`, conforme à Google Calendar.
- **`versionCode` figé à la main** dans `version.properties` : un `git rev-list --count` **baisse** après
  un rebase et retombe à 1 hors dépôt git. Ne propose pas de le dériver de l'historique.
- **Ne jamais proposer de changer la clé de signature ni de révoquer le keystore.** Cela romprait le
  chemin de mise à jour de toutes les installations existantes. Un tel constat est **faux par
  construction**.
- **`USE_EXACT_ALARM` est légitime** (fonction centrale = rappel daté, permission auto-accordée) et
  **`READ_CALENDAR` aussi** (import ponctuel, lecture seule, 100 % local).
- **`WAKE_LOCK` et `FOREGROUND_SERVICE` sont dans l'APK** via `androidx.work`, tirée par Glance. L'app
  n'enfile AUCUN travail WorkManager. Elles sont sur l'allowlist parce qu'elles y sont réellement ;
  savoir s'il faut neutraliser `WorkManagerInitializer` est un point ouvert **hors périmètre de cette
  relecture**.
- **`MAX_INTERVAL = 999` et `MAX_SCAN_ITERATIONS = 100_000`** sont des valeurs arbitrées. Ne discute pas
  leur valeur numérique.
- **La désactivation des règles de mise en page ktlint est une décision assumée**, motivée dans
  `detekt.yml` : ne la conteste pas comme préférence. **En revanche, dis-le si j'ai désactivé une règle
  qui décrit un COMPORTEMENT et non une mise en page** — ce serait une vraie erreur.
- Le job `instrumented` de la CI n'a pas pu être exécuté localement (il faut un runner GitHub). C'est
  déclaré. Signale ses erreurs, mais ne signale pas « non testé » comme un constat.

---

## 3. Ce sur quoi je veux ton avis — par ordre de valeur

### A. Les tests instrumentés sont-ils VRAIMENT concluants ?

C'est ma première question. Un test vert qui ne prouve rien est pire que pas de test, et ce dépôt en a
déjà eu un (`FakeEventRepository.observeOverrides()` renvoyait une liste vide constante, rendant vacant
tout test qui s'y appuyait).

- `DatabaseEncryptionTest.realKeyIsActuallyUsed` affirme que la vraie clé ouvre la base ET que la clé
  nulle ne l'ouvre pas. **Ces deux assertions suffisent-elles** à prouver que SQLCipher chiffre bien
  avec la clé du Keystore ? Y a-t-il un état où elles passeraient toutes les deux alors que le chiffrement
  serait faux ?
- `aLegacyZeroKeyDatabaseIsRekeyedAndKeepsItsRows` crée un fichier avec une table (`legacy_probe`) que
  Room ne connaît pas, puis laisse Room créer son schéma à côté. **Ce montage est-il valide ?** Room voit
  `user_version = 0` : est-ce bien ce qui se produit, et le test prouve-t-il ce qu'il prétend prouver ?
- `MigrationsTest` fabrique une base v1 à la main depuis le DDL exporté, pose `PRAGMA user_version = 1`,
  puis laisse `DatabaseFactory` appliquer les migrations. **Room lance-t-il réellement la chaîne 1→5 dans
  ce montage, et valide-t-il réellement le schéma final ?** Si Room ne valide pas, le test surestime ce
  qu'il couvre et je veux le savoir.
- Les tests partagent le **même fichier de base** que l'application (`agendatech.db`) et le nettoient
  dans `@Before`/`@After`. Y a-t-il une fuite entre tests, ou un risque pour des données réelles ?
- `SqlCipherNative.load()` est appelé dans `@Before` et dans l'initialiseur de la `@Rule`. **L'ordre est-il
  garanti** dans tous les cas ?

### B. Le script d'assertion des permissions est-il solide ?

`tools/check-manifest-permissions.py` est ce qui transforme une promesse publique en contrainte de build.
S'il peut rendre « OK » à tort, il est pire qu'absent.

- Existe-t-il une façon pour une permission d'atteindre l'APK **sans** apparaître comme
  `<uses-permission>` ou `<uses-permission-sdk-23>` dans ce manifeste fusionné ?
- Le manifeste fusionné de `processReleaseMainManifest` est-il bien celui qui décide des permissions de
  l'APK, ou existe-t-il une étape ULTÉRIEURE (packaging, splits par ABI, R8) qui pourrait en ajouter ?
  C'est le point qui m'inquiète le plus.
- Les chemins d'échec sont-ils tous couverts ? (fichier absent, XML illisible, zéro permission,
  permission interdite, permission hors liste — tous exercés contre des manifestes trafiqués.)

### C. La CI est-elle correcte ?

- Erreurs de syntaxe, d'action, de version d'action, de permissions de token.
- Le job `zero-network` lance `:app:processReleaseMainManifest` **sans keystore**. Est-ce que cette tâche
  peut échouer en CI pour une raison que je n'aurais pas vue (signature, `local.properties` absent) ?
- Le job `instrumented` : l'émulateur API 29 x86_64 va-t-il réellement démarrer sur un runner
  `ubuntu-latest` ? Les tests ont besoin d'un **vrai AndroidKeyStore** — un émulateur en fournit-il un
  utilisable pour `setUserAuthenticationRequired(false)` + AES-GCM ?
- `gradlew` a été passé en `100755` via `git update-index --chmod=+x`. Manque-t-il quelque chose d'autre
  pour que `./gradlew` fonctionne sur Linux ?

### D. Les modifications du code de production ont-elles cassé quoi que ce soit ?

Cinq fichiers de production ont été touchés, tous marginalement. Regarde surtout :
- `RestoreBackupUseCase` : ajout d'un `Timber.w(t, …)` dans un `catch`. Le message rendu à l'utilisateur
  reste volontairement vague. **Est-ce qu'on journalise quelque chose qui ne devrait pas l'être**, sachant
  que le fichier est un `.atbak` non fiable et que la trace peut contenir son contenu ?
- `ImportDeviceEventsUseCase` et `EventEditorViewModel.onAddReminder` : ajout d'accolades autour d'un
  `if/else`. La sémantique est-elle **exactement** préservée ?
- `DatabaseFactory` et `RecurrenceExpander` : uniquement des `@Suppress` + commentaires. Vérifie qu'aucun
  `@Suppress` ne masque autre chose que ce qu'il annonce.

### E. Les seuils detekt relevés le sont-ils à tort ?

Chaque écart est justifié dans `detekt.yml`. Dis-moi si l'une de ces justifications est **fausse** — par
exemple si un seuil relevé masque un vrai problème de conception plutôt qu'un idiome.

---

## 4. Règles de recevabilité — elles décident de ce qui compte

1. **Un constat sans scénario d'échec concret et reproductible n'est pas recevable.** Pas « pourrait
   poser problème » : donne l'état de départ, la suite d'appels, et le résultat faux obtenu. Si tu ne
   peux pas écrire ce scénario, ne rapporte pas le constat.
2. **Interdit** : le style, le nommage, les renommages, le découpage de fichiers, l'ordre des imports, la
   mise en forme, les préférences d'architecture, « il faudrait plus de tests » en général. Je ne veux
   **que** des défauts de comportement et des tests qui ne prouvent pas ce qu'ils prétendent.
3. **« Aucun défaut sur ce point » est une réponse attendue et utile.** Dis-le point par point (A à E).
   Un silence justifié vaut mieux qu'un remplissage.
4. **La précision de chaque relecteur est mesurée au fil des lots.** Un faux positif coûte plus cher
   qu'un silence : il consomme une vérification et il érode la confiance dans tes autres constats.
   Au lot précédent, sur ce même dépôt : GPT a déclaré un `BroadcastReceiver` « OK » sans voir que
   l'injection Hilt précède le corps de `onReceive` ; Gemini a annoncé un ANR sur un chemin qui termine
   par `flowOn(Dispatchers.Default)`. Les deux ont par ailleurs trouvé des défauts réels que j'avais
   manqués. Préfère 2 constats solides à 10 hypothèses.
5. Pour chaque constat : `fichier:ligne` · la sévérité (CRITIQUE / MAJEUR / MINEUR) · le scénario · et
   **ta confiance** (CERTAIN si établi dans le code fourni, HYPOTHÈSE sinon).
6. Si tu proposes un correctif, dis **ce qu'il risque de casser**. Un correctif qui déplace le problème
   est pire que le problème.
