## A) Tests instrumentés — conclusifs ou pas ?

### A1. `DatabaseEncryptionTest.realKeyIsActuallyUsed`
**Conclusion : plutôt OUI pour l’objectif annoncé (régression SEC‑1 “clé zéro”).**

- Les deux assertions ensemble (“la vraie clé ouvre” **et** “la clé zéro n’ouvre pas”) couvrent bien le cas historique où SQLCipher finissait par ouvrir la base avec 32 octets nuls : si ce défaut revient tel quel, le test doit échouer.
- **État “chiffrement faux” où les deux passeraient** : je n’en vois pas, *au sens* “la base est en réalité chiffrée avec la clé zéro / pas chiffrée” — une base non chiffrée ne devrait pas être lisible via `net.zetetic.database.sqlcipher.SQLiteDatabase` avec une clé arbitraire, et une base chiffrée “zéro” devrait redevenir lisible avec la clé zéro.

**Point de vigilance (limite de preuve, pas un défaut actuel)** : ce test prouve “la base est lisible avec la passphrase renvoyée par `DatabaseKeyManager`”, pas “cette passphrase est matériellement protégée par le Keystore”. Si demain `DatabaseKeyManager` était modifié pour stocker la clé en clair sur disque (tout en renvoyant une clé stable), ce test resterait vert. Ce n’est pas “SQLCipher faux”, mais c’est bien “Keystore non utilisé”.

### A2. `aLegacyZeroKeyDatabaseIsRekeyedAndKeepsItsRows`
**Conclusion : OUI, le montage est valide.**

- Le fait de **laisser `user_version = 0`** est cohérent avec le comportement de `SQLiteOpenHelper` : `oldVersion == 0` déclenche un chemin “création” (création des tables Room) plutôt qu’un chemin “migrations”. Dans ce chemin, Room crée son schéma **sans effacer** les tables préexistantes qu’elle ne connaît pas, donc `legacy_probe` peut survivre.
- Le test prouve bien ce qu’il dit : (1) une table inconnue et sa ligne survivent à l’ouverture Room, et (2) après passage de `DatabaseFactory`, la base n’est plus ouvrable avec la clé zéro.

### A3. `MigrationsTest` (seed v1 + `PRAGMA user_version = 1`)
**Conclusion : OUI, Room doit lancer une chaîne 1→5 *dans ce montage*, et la validation Room est bien exercée.**

Scénario couvert :
- départ : fichier existant + `user_version = 1` ;
- appel : `DatabaseFactory.build()` → `Room.databaseBuilder(...).addMigrations(*Migrations.ALL)...` + **open forcé** via `db.openHelper.writableDatabase` ;
- résultat : Room doit exécuter les migrations nécessaires pour atteindre `SCHEMA_VERSION = 5`, puis valider le schéma final (sinon ouverture → exception et test rouge).

**Limite (future, pas un défaut actuel)** : ce test exerce “le chemin d’upgrade 1→5 que Room choisit”. Si un jour une migration directe (ex. 1→5) est ajoutée et que Room la préfère, le test resterait vert tout en n’exerçant plus MIGRATION_1_2/2_3/3_4/4_5 individuellement. Aujourd’hui, avec `ALL = [1_2,2_3,3_4,4_5]`, il couvre bien les 4.

### A4. Fichier DB partagé + nettoyage `@Before/@After`
**Conclusion : pas de fuite inter-tests évidente dans ce qui est fourni ; risque sur données “réelles” = NON pour l’app release, OUI pour une install debug.**

- **Données utilisateur release** : non concernées, car les tests visent le package debug (`applicationIdSuffix = ".debug"`). Ils ne toucheront pas `com.filestech.agenda_tech` release.
- **Risque concret** : si quelqu’un utilise la **build debug** comme agenda “réel” sur un téléphone, puis lance les tests instrumentés contre ce même téléphone, `clearDatabaseFiles()` supprimera `agendatech.db` et donc les données debug.

### A5. Double appel `SqlCipherNative.load()`
**Conclusion : pas de défaut démontrable avec les fichiers fournis.**

- Dans les deux classes fournies, `SqlCipherNative.load()` est appelé en `@Before` avant tout usage direct de `SQLiteDatabase.*`.
- S’il existe ailleurs une `@Rule` qui touche SQLCipher *dans son initialisation*, alors `@Before` serait trop tard ; mais ce cas n’apparaît pas dans les fichiers fournis → je ne peux pas l’affirmer. (Question ouverte : quelle est cette `@Rule` mentionnée ?)

---

## B) Script `check-manifest-permissions.py` — solide ?

### B1. Permission dans l’APK sans `<uses-permission>` dans le manifeste fusionné ?
**Conclusion : NON (pour les permissions “demandées” au sens Android).**  
Les permissions visibles/accordables à l’installation viennent du manifeste packagé ; elles ne peuvent pas “apparaître” via R8/splits sans être dans le manifeste final.

### B2. Étape ultérieure ajoutant des permissions après `processReleaseMainManifest` ?
**Conclusion : je n’ai pas de scénario concret où packaging/splits/R8 ajouteraient une permission absente du manifeste fusionné.**  
Donc sur ce point : **aucun défaut établi**.

### B3. Chemins d’échec couverts ?
**Conclusion : OUI dans le script lui-même** (absent, XML illisible, liste vide, forbidden, unexpected).  
Je ne peux pas vérifier ici les tests “contre manifestes trafiqués” (ils ne sont pas fournis), donc je ne conclus pas dessus.

---

## C) CI — correcte ?

### Constat C1 — permissions trop restrictives pour `upload-artifact` (et possiblement le cache Gradle)
- **Fichier:ligne** : `.github/workflows/ci.yml:21-22`
- **Sévérité** : MINEUR
- **Scénario reproductible** :
  1. Un run CI démarre avec le bloc :
     ```yaml
     permissions:
       contents: read
     ```
  2. Les steps `actions/upload-artifact@v4` (gate + instrumented) tentent de créer/téléverser un artefact.
  3. Sur GitHub Actions, ces actions peuvent échouer en **403** si le `GITHUB_TOKEN` n’a pas les droits requis (typiquement `actions: write`) → les jobs restent utiles mais vous perdez les rapports (ou le step devient rouge selon config).
- **Confiance** : HYPOTHÈSE (je ne peux pas exécuter le workflow ici ; c’est un comportement connu des permissions fines du `GITHUB_TOKEN`, mais dépend de l’implémentation exacte côté GitHub au moment T).

**Correctif possible** : ajouter explicitement les permissions nécessaires (ex. `actions: write`), ou déplacer `permissions:` au niveau job en ne donnant plus que ce qui est nécessaire.  
**Risque du correctif** : élargit les droits du token (surface CI), donc à faire minimalement.

### C2. `zero-network` sans keystore / `local.properties`
**Conclusion : aucun défaut concret visible** : `:app:processReleaseMainManifest` ne devrait pas dépendre de la signature release (et votre `build.gradle.kts` ne force pas le signingConfig sans `keystore.properties`).

### C3. `instrumented` + émulateur API 29 + AndroidKeyStore
**Conclusion : rien de cassé démontrable dans les fichiers fournis.**  
Si l’AndroidKeyStore émulé ne supportait pas un point précis (AES-GCM + `setRandomizedEncryptionRequired(false)`), les tests échoueraient bruyamment (donc pas de faux vert).

### C4. Exécution de `./gradlew` sur Linux
**Conclusion : rien d’autre d’évident** au-delà du bit exécutable (déjà traité).

---

## D) Modifs prod — cassé quelque chose ?

### Constat D1 — log potentiellement “trop bavard” : fuite de contenu de backup dans les logs via `Throwable.message`
- **Fichier:ligne** : `app/src/main/java/.../RestoreBackupUseCase.kt:82-91` (catch de `decode()`, ligne `Timber.w(t, ...)`)
- **Sévérité** : MAJEUR (confidentialité locale)
- **Scénario reproductible** :
  1. L’utilisateur tente de restaurer un `.atbak` **correctement déchiffré** (donc on a du plaintext en mémoire), mais **corrompu/tronqué** juste après avoir inclus des champs sensibles (titres, descriptions…).
  2. `BackupCodec.decodeFromJson(...)` lève une exception de parsing (p.ex. `JsonDecodingException`) dont le message peut inclure un **extrait de l’input JSON** (selon la lib/forme exacte de l’erreur).
  3. `Timber.w(t, ...)` journalise l’exception complète → le message (et parfois un extrait du JSON) peut finir en logcat.
  4. Toute personne avec accès logcat (debug/USB, device compromis/root, etc.) peut récupérer cet extrait.
- **Confiance** : HYPOTHÈSE (dépend du contenu exact des messages d’exception de la version de kotlinx-serialization/JSON utilisée et de la politique de logs en release).

**Correctif possible** : ne pas logger le `Throwable` (ou ne logger que `t::class.java.name`), ou logger le throwable uniquement en debug si vous avez un switch central (`LOG_ENABLED`).  
**Risque du correctif** : vous perdez la cause détaillée utile pour diagnostiquer des backups refusés “en vrai”.

### Accolades (`ImportDeviceEventsUseCase`, `EventEditorViewModel.onAddReminder`)
**Conclusion : sémantique préservée dans l’état fourni** (aucun scénario de comportement changé détectable).

### `@Suppress` (`DatabaseFactory`, `RecurrenceExpander`)
**Conclusion : aucun `@Suppress` ne masque un comportement ; ils ciblent bien ce qu’ils annoncent.**

---

## E) Seuils detekt relevés / règles désactivées
**Conclusion : aucun défaut de comportement détecté dans `detekt.yml` fourni.**  
Les règles désactivées sous `formatting:` sont bien des règles de mise en page ; je ne vois pas ici de règle “comportementale” coupée par erreur.

---

### Questions pour lever les seules hypothèses (si tu veux les convertir en CERTAIN)
1. En release, as-tu bien une configuration Timber qui **ne loggue rien** quand `BuildConfig.LOG_ENABLED=false` ?
2. Sur un run CI réel, les steps `upload-artifact` passent-ils avec `permissions: contents: read` uniquement, ou faut-il ajouter `actions: write` ?