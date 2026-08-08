# Agenda Tech — Lot 1 : audit par motifs (constats priorisés)

**Date** : 2026-08-08 · **Commit** : `893b683` (`main`, tag `v0.5.4`) · **Méthode** : lecture directe
des 132 fichiers de production, priorisée par les 4 motifs + les points durs. Aucun code modifié.

> Chaque constat porte `fichier:ligne`, son **motif**, un **scénario d'échec concret**, et le statut
> **CONFIRMÉ** (vérifié dans le code, mécanisme établi) ou **PROBABLE** (à instruire).

---

## État du contrôle vert — à savoir avant tout

```
./gradlew :app:assembleDebug testDebugUnitTest :app:lintDebug detekt
```

| Tâche | Résultat |
|---|---|
| `assembleDebug` | ✅ |
| `testDebugUnitTest` | ✅ (241 tests) |
| `lintDebug` | ✅ 0 |
| `detekt` | ❌ **ÉCHEC — 520 weighted issues** |

**Le gate exigé est ROUGE sur `main`, à la version publiée.** Répartition exacte des 520 :

| Volume | Règles | Nature |
|---|---|---|
| **320** (62 %) | `PackageName` (160) + `PackageNaming` (160) | **Un seul et même fait, compté deux fois** : le package est `com.filestech.agenda_tech` et l'underscore viole les deux jeux de règles. 160 fichiers × 2 règles = 320. Aucun rapport avec la qualité du code. |
| 160 | `ArgumentListWrapping` (98), `Wrapping` (19), `ImportOrdering` (14), `AnnotationOnSeparateLine` (14), `SpacingBetween…` (14), `ParameterListWrapping` (2) | mise en forme pure |
| 20 | `NoUnusedImports` (10) + `UnusedImports` (10) | **10 imports réellement inutilisés**, comptés deux fois |
| 20 | `ReturnCount` (3), `ThrowsCount` (2), `LongParameterList` (2), `MultiLineIfElse` (4), `TooManyFunctions` (1), `SwallowedException` (1), `SpreadOperator` (1), `LoopWithTooManyJumpStatements` (1), `DestructuringDeclaration…` (1) | signaux sémantiques réels, tous mineurs |

Conséquence de méthode : **la consigne « gate verte entre chaque lot » est aujourd'hui
insatisfaisable**, et elle ne l'a jamais été. Il n'y a **pas** de baseline detekt
(`config/detekt/` ne contient que `detekt.yml`) — donc rien n'a été masqué, mais rien n'a jamais été
vert non plus. Traitement proposé au lot suivant, **sans baseline** (doctrine, règle 2) et **sans
renommer le package** (`applicationId` = chemin de mise à jour des installations) : configurer les
deux règles de nommage dans `detekt.yml` pour accepter le nom existant, et aligner Agenda Tech sur la
convention du portefeuille — **SMS Tech fait tourner le formatage en non-bloquant et detekt
sémantique en bloquant**, Agenda Tech fond les deux dans une seule tâche bloquante.

---

## CRITIQUE

### A1 — Un échec **transitoire** du Keystore efface tout l'agenda, sans confirmation, sur un chemin où aucun utilisateur n'est présent

- **Fichier** : [DatabaseFactory.kt:39-47](app/src/main/java/com/filestech/agenda_tech/data/local/db/DatabaseFactory.kt#L39-L47)
- **Motif** : **#2 — le repli échoue du mauvais côté**, aggravé par **#4 — chemin mort**
- **Statut** : **CONFIRMÉ**

```kotlin
val raw = try {
    keyManager.getOrCreatePassphrase()
} catch (e: Exception) {                              // ← tout, sans distinction
    keyManager.destroyKeyFile()
    context.deleteDatabase(AppDatabase.DATABASE_NAME) // ← l'agenda entier
    markResetPending(context)
    keyManager.getOrCreatePassphrase()
}
```

Trois faits vérifiés, qui se combinent :

1. **Le fournisseur promet exactement le contraire.** `DatabaseKeyManager` expose une hiérarchie
   typée `Failure.KeystoreInvalidated` / `WrapCorrupted` / `Io` et son KDoc
   ([DatabaseKeyManager.kt:29-31](app/src/main/java/com/filestech/agenda_tech/data/local/db/DatabaseKeyManager.kt#L29-L31))
   dit : « *Distinguishing genuine Keystore invalidation … from a transient decrypt failure avoids
   silent data loss: the caller receives a typed Failure and surfaces a recovery flow instead of
   auto-wiping the key.* » Et
   [:127-128](app/src/main/java/com/filestech/agenda_tech/data/local/db/DatabaseKeyManager.kt#L127-L128) :
   « *We DO NOT auto-delete the keyFile here: a silent wipe = silent data loss. The caller surfaces a
   recovery UI instead.* »
   **Le seul appelant efface la base et le fichier de clé, et ne distingue rien.** La « recovery UI »
   n'existe pas : le seul aval est un `Toast` *après coup*
   ([MainActivity.kt:84-86](app/src/main/java/com/filestech/agenda_tech/MainActivity.kt#L84-L86)).
   → **La hiérarchie typée est du code mort** : aucun appelant ne l'exploite (motif #4).

2. **Le `catch` attrape plus large que `Failure`.** `KeystoreManager.getOrCreateKey`
   ([KeystoreManager.kt:32-39](app/src/main/java/com/filestech/agenda_tech/core/crypto/KeystoreManager.kt#L32-L39))
   n'enveloppe rien. `DatabaseKeyManager` ne mappe que `KeyPermanentlyInvalidatedException` et
   `UserNotAuthenticatedException`
   ([:113-119](app/src/main/java/com/filestech/agenda_tech/data/local/db/DatabaseKeyManager.kt#L113-L119)).
   Un `KeyStoreException`, un `UnrecoverableKeyException`, un `ProviderException` du démon keystore2
   remontent **tels quels** — donc dans le `catch (e: Exception)`, donc dans l'effacement. Ce sont
   précisément les erreurs *transitoires*.

3. **Le pire chemin n'a pas d'utilisateur.** `BootReceiver` (voir A2) résout `ReminderScheduler`,
   ce qui construit `AppDatabase` via
   [DatabaseModule.kt:21-23](app/src/main/java/com/filestech/agenda_tech/di/DatabaseModule.kt#L21-L23),
   donc `DatabaseFactory.build()`.

**Scénario d'échec concret** — le téléphone applique une mise à jour système. Au premier
`BOOT_COMPLETED`, sous contention d'E/S maximale, le démon keystore2 rend une `KeyStoreException`.
`BootReceiver` se déclenche, l'injection Hilt construit la base, `getOrCreatePassphrase()` lève.
Le `catch` supprime `master.key` **et** `agendatech.db`. `allowBackup=false` : il n'existe aucune
autre copie sur l'appareil. La vraie clé n'avait rien : une seconde tentative aurait réussi.
**L'agenda entier est perdu définitivement, sans qu'un écran ait été affiché, sans qu'une question
ait été posée.** L'utilisateur découvre un `Toast` au lancement suivant.

**Pourquoi ce n'est pas un faux positif** : le commentaire du code invoque « *If the Keystore key is
gone/corrupted the encrypted DB is cryptographically unrecoverable* » — vrai pour
`KeystoreInvalidated`, faux pour `Io` et pour toute exception non mappée. La garde est correcte pour
un cas et appliquée à tous.

**Direction de correction** (à valider) : ne détruire que sur le cas réellement irrécupérable
(`Failure.KeystoreInvalidated`), réessayer les cas transitoires, et pour tout le reste **échouer en
laissant les données en place** — un lancement qui refuse d'ouvrir est récupérable, un agenda effacé
ne l'est pas. Le repli doit **fermer**.

---

## MAJEUR

### A2 — `BootReceiver` et `ReminderReceiver` construisent la base sur le thread principal ; les deux jumeaux `MainActivity` / `MainApplication` ont été corrigés, pas eux

- **Fichiers** : [BootReceiver.kt:21-23](app/src/main/java/com/filestech/agenda_tech/system/alarm/BootReceiver.kt#L21-L23) · [ReminderReceiver.kt:31-33](app/src/main/java/com/filestech/agenda_tech/system/alarm/ReminderReceiver.kt#L31-L33)
- **Motif** : **#3 — jumeau asymétrique**
- **Statut** : **CONFIRMÉ**

`@Inject lateinit var` est une injection de champ **eager** : Hilt la résout dans `onReceive`, sur le
thread principal, **avant** le `goAsync()`. Résoudre `ReminderScheduler` (ou
`ReminderActionHandler`) exige `EventRepository` → `EventDao` → `AppDatabase` → `DatabaseFactory.build()`,
soit : `System.loadLibrary("sqlcipher")`, un IPC Keystore, un déchiffrement AES-GCM, l'ouverture
SQLCipher, **et** potentiellement `rekeyLegacyZeroKeyDatabase` qui **copie tout le fichier de base
de côté**.

Le KDoc de `ReminderReceiver`
([:25-27](app/src/main/java/com/filestech/agenda_tech/system/alarm/ReminderReceiver.kt#L25-L27))
affirme : « *Work runs off the main thread via goAsync() so the ~10 s broadcast budget is respected
without blocking.* » **C'est faux pour la partie la plus coûteuse**, qui se produit avant.

L'asymétrie est documentée dans l'historique, ce qui la rend indiscutable :
- `3b4da6f` : « *MainActivity n'injecte plus AppDatabase en champ : l'injection Hilt construisait la
  base (IPC Keystore, lecture disque, AES-GCM) sur le thread Main au démarrage.* » → `Provider` + IO.
- `6aafe38` : « *MainApplication injectait le use case en champ direct… Passé en `Provider`.* »
- **Les deux `BroadcastReceiver` n'ont jamais reçu ce traitement**, alors qu'ils sont le pire
  contexte pour ce travail.

**Scénario d'échec concret** : au redémarrage, `BOOT_COMPLETED` est délivré à des dizaines d'apps
simultanément. `onReceive` de `BootReceiver` bloque le thread principal le temps d'un IPC keystore2
et d'une ouverture SQLCipher sur un disque saturé. Au-delà du budget de broadcast, le système tue le
processus : `scheduler.rescheduleAll()` n'a jamais tourné, `pendingResult.finish()` non plus. **Les
alarmes exactes ne survivent pas au reboot et ne sont pas reprogrammées : tous les rappels de
l'utilisateur ont disparu, silencieusement.** Et c'est aussi le chemin qui déclenche A1.

### A3 — `timeZoneId` est validé par 2 des 4 points d'ingestion — trois conséquences, dont une corruption de données et une injection

- **Motif** : **#3 — jumeau asymétrique**
- **Statut** : **CONFIRMÉ** (asymétrie et mécanismes lus dans le code)

Les quatre points d'entrée d'un `timeZoneId`, côte à côte :

| Point d'ingestion | Validé ? |
|---|---|
| Import device — [DeviceEventMapper.kt:65-67, 82](app/src/main/java/com/filestech/agenda_tech/domain/device/DeviceEventMapper.kt#L65-L67) | ✅ `runCatching { ZoneId.of(tz) }.getOrNull() ?: defaultZone`, puis stocke **`zoneId.id`** (id IANA normalisé) |
| Éditeur — [EventEditorViewModel.kt:335](app/src/main/java/com/filestech/agenda_tech/ui/screens/editor/EventEditorViewModel.kt#L335) | ✅ `zone.id`, issu d'un vrai `ZoneId` |
| **Import `.ics`** — [IcsCodec.kt:170-174, 182](app/src/main/java/com/filestech/agenda_tech/domain/ics/IcsCodec.kt#L170-L174) | ❌ stocke **`dtStart.params["TZID"]` brut**, jamais confronté à `ZoneId.of` |
| **Restauration `.atbak`** — [RestoreBackupUseCase.kt:95-125](app/src/main/java/com/filestech/agenda_tech/domain/usecase/RestoreBackupUseCase.kt#L95-L125) | ❌ `validate()` vérifie ids, FK, moitiés d'override — **pas le fuseau** ; `Event.init` ([Event.kt:58-62](app/src/main/java/com/filestech/agenda_tech/domain/model/Event.kt#L58-L62)) ne vérifie que `end >= start` |

C'est exactement la forme du défaut F1/F5/F7 (`INTERVAL`) : « *L'éditeur always clamped ; les import
paths did not* ». Le remède retenu alors — **porter la borne dans l'invariant du modèle** — n'a pas
été appliqué au fuseau.

**A3a — corruption silencieuse de l'instant à chaque aller-retour `.ics`. CONFIRMÉ.**
Les fichiers `.ics` produits par Outlook / Exchange portent des TZID Windows
(`Romance Standard Time`, `W. Europe Standard Time`) — cas ordinaire, pas hostile.
- Import : `parseDateTime` ne résout pas le TZID → retombe sur `defaultZone`
  ([IcsCodec.kt:203](app/src/main/java/com/filestech/agenda_tech/domain/ics/IcsCodec.kt#L203)) → l'instant
  est correct. Mais `timeZoneId` stocké = `"Romance Standard Time"`.
- Export : `zoneOf(event)` ne résout pas non plus → `getOrDefault(ZoneOffset.UTC)`
  ([IcsCodec.kt:310-311](app/src/main/java/com/filestech/agenda_tech/domain/ics/IcsCodec.kt#L310-L311)) →
  écrit **l'heure murale UTC** sous l'étiquette `TZID=Romance Standard Time`.
- Ré-import : TZID toujours non résolu → l'heure UTC est relue comme heure de Paris.

**Scénario concret** : un rendez-vous à 10 h 00 (Paris, été) importé d'Outlook est exporté en
`DTSTART;TZID=Romance Standard Time:20260615T080000`, puis ré-importé à **08 h 00**. L'événement a
reculé de deux heures, sans erreur, sans message. Chaque aller-retour le décale encore.

**A3b — dérive à l'heure d'été de toute série importée. CONFIRMÉ.**
`RecurrenceExpander.resolveZone` retombe sur UTC pour un fuseau non résolu
([RecurrenceExpander.kt:236-240](app/src/main/java/com/filestech/agenda_tech/domain/recurrence/RecurrenceExpander.kt#L236-L240)).
Toute la série est donc ancrée à l'heure murale **UTC**. Le KDoc de la classe
([:25-29](app/src/main/java/com/filestech/agenda_tech/domain/recurrence/RecurrenceExpander.kt#L25-L29))
promet l'inverse : « *A daily 09:00 meeting in Europe/Paris stays at 09:00 local across the
spring-forward / fall-back transition.* » **Scénario** : une réunion hebdomadaire de 9 h importée
d'Outlook s'affiche à 9 h en hiver et à 10 h en été — et le rappel sonne à la mauvaise heure.

**A3c — injection de ligne iCalendar par `TZID` à l'export. CONFIRMÉ comme chemin de code.**
[IcsCodec.kt:92](app/src/main/java/com/filestech/agenda_tech/domain/ics/IcsCodec.kt#L92) écrit
`"$name;TZID=${event.timeZoneId}:…"` **sans aucun échappement**. Le corps de commit de `ea52760`
affirme : « *l'UID était la seule valeur TEXT écrite brute à l'export* » — **cette affirmation est
fausse**, `TZID=` l'est aussi.
Le chemin `.ics` ne peut pas y injecter de `\r\n`, de `:` ni de `;` (`parsePropertyLine` les exclut
structurellement — vérifié). **La restauration `.atbak` peut** : `BackupPayload.timeZoneId` est une
simple chaîne JSON, `validate()` ne la regarde pas, `Event.init` non plus. Un `.atbak` fabriqué avec
`timeZoneId = "UTC\r\nSUMMARY:Rendez-vous falsifié"` atteint la base, et le prochain export `.ics` de
l'utilisateur porte des lignes de contenu écrites par un tiers, sous son nom.
**Honnêteté sur la portée** : l'attaquant doit faire restaurer un `.atbak` qu'il a fabriqué, avec le
mot de passe qu'il fournit. C'est le modèle de menace que le code revendique déjà
([RestoreBackupUseCase.kt:92-93](app/src/main/java/com/filestech/agenda_tech/domain/usecase/RestoreBackupUseCase.kt#L92-L93) :
« *guards against a corrupted, truncated or hand-edited one* », et SEC-3 traitait déjà le `.atbak`
comme hostile) — mais il exige une action de l'utilisateur. **MAJEUR, pas CRITIQUE.**

**A3d — les tests ne peuvent pas voir A3a ni A3b. CONFIRMÉ.**
Les **9** cas de `IcsCodecTest` qui font un aller-retour utilisent `timeZoneId = "Europe/Paris"` ou
`"UTC"` — **des fuseaux valides, sans exception**. Le seul test « fichier externe » (`:159`) est en
UTC. La suite ne teste que notre propre sortie contre notre propre entrée : **aucun test ne peut
révéler un fuseau non résolu**, parce qu'aucun n'en fabrique. Motif #4.

### A4 — Le plafond de taille de l'import `.ics` se contourne ; le jumeau `.atbak` a été corrigé, pas lui

- **Fichiers** : [IcsViewModel.kt:63-70](app/src/main/java/com/filestech/agenda_tech/ui/ics/IcsViewModel.kt#L63-L70) vs [BackupViewModel.kt:181-205](app/src/main/java/com/filestech/agenda_tech/ui/screens/backup/BackupViewModel.kt#L181-L205)
- **Motif** : **#3 — jumeau asymétrique**
- **Statut** : **CONFIRMÉ**

```kotlin
context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
    check(descriptor.statSize in 0..MAX_ICS_BYTES) { … }
}                                    // ← ?. : si null, AUCUN contrôle
val text = context.contentResolver.openInputStream(uri)
    ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }   // ← lecture NON bornée
```

Le `?.` fait du contrôle une **option**. Un fournisseur de documents qui ne sait pas ouvrir de
descripteur de fichier (`openFileDescriptor` → `null`) mais sait servir un flux — cas courant pour
les fournisseurs distants/en flux — saute le plafond entièrement, et `readText()` charge tout.

C'est le défaut H2 de `ed19126`, mot pour mot : « *`readBytes()` lisait tout AVANT que le plafond
soit consulté … le sélecteur doit accepter tous les types MIME, donc une vidéo mal sélectionnée est
un cas normal — et c'était l'OOM que le plafond était censé empêcher.* » Le correctif a borné **la
lecture elle-même** dans `BackupViewModel.readFile`. `IcsViewModel` n'a jamais été touché, et le
sélecteur `.ics` accepte lui aussi des fichiers arbitraires.

**Scénario concret** : l'utilisateur choisit par erreur une vidéo de 2 Go depuis un fournisseur en
flux. `openFileDescriptor` rend `null`, le `check` est sauté, `readText()` tente de matérialiser
2 Go en `String` (≈ 4 Go de `char`) → `OutOfMemoryError`.

> **Correction apportée après relecture** : `kotlin.runCatching` attrape `Throwable`, donc **aussi**
> l'`OutOfMemoryError`. L'import est signalé « échoué » au lieu de tuer le processus à coup sûr —
> mais rattraper un OOM n'est pas une récupération fiable (l'allocation a déjà échoué, le tas est
> sous pression, une autre allocation concurrente peut mourir). Sévérité **MAJEUR**, pas CRITIQUE.
> Deux autres déclencheurs du même défaut ont été trouvés en relecture externe — voir le tri du
> lot 2.

### A5 — `ExpansionBudget` (le correctif F8) n'est branché que sur **1 des 3** chemins d'expansion

- **Motif** : **#3 — jumeau asymétrique**
- **Statut** : **CONFIRMÉ**

| Chemin | Budget ? |
|---|---|
| Rendu des vues — [ObserveOccurrencesInRangeUseCase.kt:45, 59](app/src/main/java/com/filestech/agenda_tech/domain/usecase/ObserveOccurrencesInRangeUseCase.kt#L45) | ✅ `ExpansionBudget()` |
| Recherche — [SearchEventsUseCase.kt:140, 143](app/src/main/java/com/filestech/agenda_tech/domain/usecase/SearchEventsUseCase.kt#L140) | ❌ aucun |
| **Planification des rappels** — [ReminderScheduling.kt:45](app/src/main/java/com/filestech/agenda_tech/domain/reminder/ReminderScheduling.kt#L45) | ❌ aucun |

`nextOccurrenceStart` et `lastOccurrenceStartBefore` n'acceptent même pas de paramètre `budget`
([RecurrenceExpander.kt:82-115](app/src/main/java/com/filestech/agenda_tech/domain/recurrence/RecurrenceExpander.kt#L82-L115)) :
seule `expand()` l'a reçu. Chaque appel reste plafonné à `MAX_SCAN_ITERATIONS = 100_000` **par
événement** — c'est-à-dire exactement le raisonnement que le KDoc d'`ExpansionBudget` déclare
insuffisant : « *the per-event bound says nothing about N. An import can raise N at will.* »

**Scénario concret** : un `.ics` importé apporte 2 000 événements récurrents dont le `DTSTART` est en
1970, chacun avec un rappel. Au redémarrage, `rescheduleAll()`
([ReminderScheduler.kt:50-58](app/src/main/java/com/filestech/agenda_tech/system/alarm/ReminderScheduler.kt#L50-L58))
appelle `computeNextFire` pour chacun : jusqu'à 2 000 × 100 000 = **200 millions** d'opérations
`java.time`, dans une fenêtre de broadcast qui se compte en dizaines de secondes. Le processus est
tué avant la fin. **Les rappels ne sont pas reprogrammés — et rien ne le signale.** Le même fichier
gèle la recherche, qui est le chemin le plus facile à déclencher.

### A6 — Verrou d'application : le widget masque les titres, la notification les affiche

- **Fichiers** : [AgendaWidget.kt:71-72](app/src/main/java/com/filestech/agenda_tech/widget/AgendaWidget.kt#L71-L72) vs [ReminderNotifier.kt:192](app/src/main/java/com/filestech/agenda_tech/system/notifications/ReminderNotifier.kt#L192)
- **Motif** : **#3 — jumeau asymétrique** (« notification d'alarme vs affichage in-app »)
- **Statut** : **CONFIRMÉ comme asymétrie** — le caractère « défaut » relève de ton arbitrage

Le widget applique LOCK-3 : `hideTitles = widgetHideTitles || lockRepository().isLockEnabled()`,
avec pour intention écrite « *enabling the lock never leaves event titles readable on the home
screen* ». La notification de rappel, elle, pose `setContentTitle(event.title)` en toutes
circonstances.

Sa protection est réelle mais visée ailleurs : `VISIBILITY_PRIVATE` + `setPublicVersion(générique)`
couvre **l'écran de verrouillage de l'appareil** — correctement, c'est vérifié. Elle ne couvre pas le
**volet de notifications d'un appareil déverrouillé**, qui est précisément la surface que LOCK-3
protège pour le widget.

**Scénario concret** : Patrice a posé un code PIN sur Agenda Tech. Il prête son téléphone
déverrouillé. Le widget affiche des lignes sans titre — la protection joue. Un rappel se déclenche :
le volet affiche « Consultation Dr X · 14:30 ». Deux surfaces, même appareil, même verrou, deux
réponses opposées.

**Je ne tranche pas** : un rappel muet est un rappel inutile, et c'est un choix produit, pas une
règle technique. Trois voies possibles — (a) laisser tel quel et **documenter la décision** dans
`SECURITY.md` pour que l'asymétrie devienne un choix et non un oubli ; (b) masquer le titre dans la
notification quand le verrou est actif ; (c) rendre le comportement réglable. **(a) est le minimum**,
parce qu'aujourd'hui rien n'écrit que c'est voulu.

### A7 — Aucun test instrumenté : le code le plus dangereux de l'app n'a aucune couverture automatisée

- **Motif** : **#4 — chemin mort / test vert**
- **Statut** : **CONFIRMÉ** (déjà relevé en C0-3 au lot 0, précisé ici)

`app/src/androidTest/` ne contient que `HiltTestRunner.kt` — un runner, pas un test. Aucun `@Test`
instrumenté, alors que 7 dépendances `androidTest` sont déclarées et téléchargées.

Croisé avec la liste des 28 fichiers de test JVM, les classes **sans aucun test** sont exactement
celles qui exigent Android :

| Classe non couverte | Ce qu'elle porte |
|---|---|
| `DatabaseFactory` | **A1** — l'effacement de l'agenda, et la bascule de clé nulle de la v0.5.2 |
| `DatabaseKeyManager`, `KeystoreManager` | la clé SQLCipher |
| `LockRepositoryImpl`, `BiometricGate` | le verrou et son second facteur |
| `ReminderScheduler` | **A5** — `rescheduleAll`, le chemin de reboot (seule la partie pure `ReminderScheduling` est testée) |
| `IcsViewModel` | **A4** — le plafond contourné |
| `BackupViewModel` | le correctif H2 lui-même |
| `Migrations` | aucun `MigrationTestHelper` |

**Scénario concret** : les deux défauts les plus graves de l'histoire du projet — la clé nulle
(`6aafe38`, « *Confirmé sur émulateur* ») et la biométrie adossée au TEE (`893b683`, « *validé sur
S24 FE* ») — n'ont été vérifiés qu'à la main, une fois. Une régression sur l'un ou l'autre **repasse
les 241 tests JVM au vert**. Le S9 `22dbb7390a057ece` est branché et prévu pour ça.

### A8 — Zéro CI (rappel du lot 0)

`.github/` n'existe pas. Voir C0-1/C0-2 du lot 0 : la promesse « zéro réseau » n'est vérifiée par
rien, et elle a déjà été cassée une fois dans un APK publié (`ACCESS_NETWORK_STATE` en v0.3.0).

---

## MINEUR

### A9 — La garantie FLAG_SECURE sur l'instantané Recents ne tient pas : le re-verrouillage est asynchrone

- **Fichiers** : [MainActivity.kt:99-109](app/src/main/java/com/filestech/agenda_tech/MainActivity.kt#L99-L109) et [:123-129](app/src/main/java/com/filestech/agenda_tech/MainActivity.kt#L123-L129)
- **Motif** : **#2 — garde échantillonnée**
- **Statut** : **CONFIRMÉ** (mécanisme), portée étroite

`onStop()` lance une coroutine qui lit `lockRepository.isLockEnabled()` — une lecture DataStore
suspendue — **avant** d'appeler `appLock.lock()`. Pendant ce temps, `lockState` vaut encore
`UNLOCKED`, donc `forceSecure = settings.flagSecure || lockState != UNLOCKED` vaut `flagSecure`.

**Scénario concret** : l'utilisateur a désactivé « bloquer les captures d'écran » mais gardé un code
PIN. Il quitte l'app : `FLAG_SECURE` est absent, l'instantané Recents capture le contenu du
calendrier, **puis** le verrou s'applique. Le commentaire LOCK-2 promet exactement le contraire :
« *the Recents snapshot taken as we background must never leak the PIN field or calendar content* ».
Portée étroite (`flagSecure` vaut `true` par défaut), mécanisme réel.

### A10 — Le pliage des lignes `.ics` coupe les paires de substitution : un emoji devient `??` à l'export

- **Fichiers** : [IcsCodec.kt:126-137](app/src/main/java/com/filestech/agenda_tech/domain/ics/IcsCodec.kt#L126-L137) + [IcsViewModel.kt:49](app/src/main/java/com/filestech/agenda_tech/ui/ics/IcsViewModel.kt#L49)
- **Motif** : autre (encodage)
- **Statut** : **CONFIRMÉ** (mécanisme) / **PROBABLE** (fréquence — dépend de l'offset)

`fold()` découpe à 73 **caractères** fixes (`builder.append(line, index, end)`). Un emoji est une
paire de substitution de 2 `char` : s'il chevauche la frontière, chaque moitié devient un substitut
orphelin. `IcsViewModel` écrit ensuite `exported.ics.toByteArray(Charsets.UTF_8)`, et un substitut
orphelin n'est pas encodable en UTF-8 valide — l'encodeur le remplace par `?`. **L'emoji devient
`??` dans le fichier exporté.**
Au passage : la RFC 5545 borne les lignes à 75 **octets**, pas 73 caractères — avec des accents
français, 73 caractères peuvent faire jusqu'à 146 octets.

### A11 — 10 imports réellement inutilisés, et deux fichiers d'écran hypertrophiés

`NoUnusedImports` désigne 10 imports morts (`AgendaScreen.kt` × 4, `BackupScreen.kt` × 2,
`SearchScreen.kt` × 2, `DeviceImportScreen.kt`, `EventEditorScreen.kt`).

Sur la **structure et la maintenabilité** : `EventEditorScreen.kt` fait 40 Ko, `MonthScreen.kt`
27 Ko, `SettingsScreen.kt` 25,6 Ko ; `EventEditorViewModel` expose **39 fonctions publiques**
(`TooManyFunctions`). Je le **signale sans le proposer** : la doctrine interdit le découpage
cosmétique, et un refactor de cette taille sur du code sans test instrumenté ferait courir plus de
risque qu'il n'en retire. À traiter le jour où l'un de ces fichiers doit changer pour une autre
raison.

---

## Ce que j'ai cherché et **NON** trouvé, motif par motif

Réponses attendues et utiles — elles délimitent ce qui est réellement solide.

**Motif #1 — garde sur l'affichage plutôt que sur l'accès : rien trouvé.**
- Le widget garde sur `isLockEnabled()` — « *le verrou est-il configuré* », pas « *l'app est-elle
  verrouillée en ce moment* ». C'est le choix **plus strict**, et c'est le bon.
  **Correction d'une prémisse** : le widget ne tourne **pas** dans un autre processus — `android:process`
  n'apparaît **nulle part** dans le manifeste fusionné (0 occurrence, vérifié). Il partage le processus
  de l'app. Le raisonnement tient quand même : le lanceur peut lier le widget alors que le processus
  démarre à froid, sans Activity, donc avec `AppLockManager.state == UNKNOWN` — un état de session
  serait inutilisable ici. La conclusion est inchangée, la prémisse était fausse.
- `MainActivity` ne rend **rien** tant que `lockState == UNKNOWN`, et le splash reste posé via
  `setKeepOnScreenCondition` ([MainActivity.kt:71](app/src/main/java/com/filestech/agenda_tech/MainActivity.kt#L71)).
  Aucune fenêtre d'affichage avant résolution.
- Aucun contournement par intent : le manifeste fusionné n'expose que `MainActivity`
  (MAIN/LAUNCHER), `BootReceiver` (protégé par permission) et les deux receivers de widget.
  `ReminderReceiver` est `exported=false`. Pas de deep link, pas de `<data>`, pas de `PROVIDER`
  applicatif.

**Motif #2 — le piège `stateIn` non hydraté nommé dans la consigne est ABSENT.**
C'était mon hypothèse la plus probable et elle est fausse — je l'écris explicitement. Les 12
`stateIn` du code sont **tous** dans des ViewModels, sur `viewModelScope`. Les deux chemins réveillés
par le système lisent de vraies valeurs suspendues, pas des `.value` non hydratés :
`SettingsRepositoryImpl.current()` = `settings.first()`
([:29](app/src/main/java/com/filestech/agenda_tech/data/local/settings/SettingsRepositoryImpl.kt#L29))
et `LockRepositoryImpl.isLockEnabled()` = `lockEnabled.first()`
([:45](app/src/main/java/com/filestech/agenda_tech/data/local/settings/LockRepositoryImpl.kt#L45)).
Le widget ne peut donc **pas** afficher un titre par défaut de « protection désactivée ». (Les
replis qui échouent du mauvais côté que j'ai trouvés sont ailleurs : A1, A4.)

**Motif #3 — trouvé quatre fois** (A2, A3, A4, A5) plus une asymétrie de produit (A6). C'est le motif
le plus productif sur ce dépôt, et de loin.

**Motif #4 — trouvé deux fois** (A7 : harnais instrumenté orphelin ; A3d/A10 : suite `.ics` qui ne
teste que son propre aller-retour). Le piège précis annoncé — `stateIn(WhileSubscribed)` + `.value`
sans collecteur dans un test — **je ne l'ai pas retrouvé** dans les 28 fichiers de test.

**Débordement arithmétique des récurrences : fermé, vérifié par le calcul.**
J'ai cherché à rejouer F1/F5/F7 sur les autres branches de `occurrenceLocalStarts`. Avec
`MAX_INTERVAL = 999` et `MAX_SCAN_ITERATIONS = 100_000`, les bornes tiennent partout :
`YEARLY` sur un 29 février (le pire cas, ~1 valeur retenue sur 4) atteint `k ≈ 412 000`, soit une
année ≈ 4,1 × 10⁸ — sous la limite de 10⁹ de `YearMonth.of`. `MONTHLY` sur un 31 plafonne à
≈ 1,4 × 10⁷ années, `DAILY` et `WEEKLY` à ≈ 2,7 × 10⁵. `(k * interval).toInt()` ne débordera pas.
**Le bornage de la v0.5.3 est effectif.**

**Clé SQLCipher nulle : le correctif de la v0.5.2 tient.**
SQLCipher reçoit `raw.copyOf()` ([DatabaseFactory.kt:59](app/src/main/java/com/filestech/agenda_tech/data/local/db/DatabaseFactory.kt#L59)),
notre copie n'est wipée qu'**après** `open()`, et `db.openHelper.writableDatabase`
([:71](app/src/main/java/com/filestech/agenda_tech/data/local/db/DatabaseFactory.kt#L71)) force le
`PRAGMA key` tant que la clé est vivante. **Aucun chemin ne peut ouvrir la base avec une clé nulle.**
`opensWith` et `changePassword` wipent leurs copies en `finally`.

**Changement d'heure / de fuseau système : pas de défaut.**
Les événements sont des instants absolus + un id IANA ([Event.kt:6-13](app/src/main/java/com/filestech/agenda_tech/domain/model/Event.kt#L6-L13)),
pas des heures flottantes. Un changement de fuseau de l'appareil ne modifie donc pas le sens d'un
événement, et les alarmes `RTC_WAKEUP` restent posées sur le bon instant. Aucun récepteur
`TIME_SET`/`TIMEZONE_CHANGED` n'est nécessaire. (La dérive DST que je signale en A3b vient du
**fuseau invalide**, pas du modèle temporel, qui est juste.)

**Heure d'été aux bornes : correct.** L'expansion itère en `LocalDateTime` puis résout par la zone,
donc un événement à 2 h 30 la nuit du passage à l'heure d'été est décalé par `java.time` au lieu
d'être perdu, et un « tous les 31 » en février est **sauté**, pas ramené au 28
([RecurrenceExpander.kt:215-233](app/src/main/java/com/filestech/agenda_tech/domain/recurrence/RecurrenceExpander.kt#L215-L233)) —
conforme à la RFC et au KDoc.

**Anti-force-brute du PIN : solide.** Persisté (`commit()` synchrone), sérialisé par `Mutex`,
compte à rebours sur l'horloge monotone, échéance persistée en horloge murale mais **bornée à un
palier** au rechargement ([AppLockManager.kt:126-137](app/src/main/java/com/filestech/agenda_tech/security/AppLockManager.kt#L126-L137)).
Avancer l'horloge saute une attente, jamais le compteur. Je n'ai pas trouvé de faille.

**Biométrie : solide.** Adossée à un `CryptoObject`, palier Classe 3 épinglé côté OS
(`setUserAuthenticationParameters`), prompts sérialisés par `biometricPromptInFlight`,
`lifecycle.withResumed` au lieu d'un `authenticate()` après sauvegarde d'état, et le champ PIN reste
affiché en toutes circonstances. Je n'ai pas trouvé de chemin qui déverrouille sans opération
cryptographique réelle.

**Notification sur écran de verrouillage : correct.** `VISIBILITY_PRIVATE` +
`setPublicVersion(générique)` ([ReminderNotifier.kt:170-200](app/src/main/java/com/filestech/agenda_tech/system/notifications/ReminderNotifier.kt#L170-L200)).
Le titre ne fuit pas sur l'écran de verrouillage de l'appareil. (A6 porte sur une autre surface.)

**Zéroïsation : pas de fuite trouvée.** Les `ByteArray` de clé sont wipés en `finally` sur tous les
chemins lus (`DatabaseFactory`, `DatabaseKeyManager`, `LockRepositoryImpl`, `RestoreBackupUseCase`).
Le résiduel `String` de la saisie Compose est connu, documenté dans `SECURITY.md`, et inévitable.

**Effets Compose qui s'annulent eux-mêmes : rien trouvé.** Les 18 `LaunchedEffect` ont été
parcourus ; aucun ne porte un affichage dont la clé change par son propre effet.

---

## À instruire — un constat non tranché

### A12 — Révocation de `SCHEDULE_EXACT_ALARM` sur Android 12 : les alarmes exactes sont annulées par le système et rien ne les repose. **PROBABLE.**

`canScheduleExact()` est **échantillonné** au moment de poser l'alarme
([ReminderScheduler.kt:138-139](app/src/main/java/com/filestech/agenda_tech/system/alarm/ReminderScheduler.kt#L138-L139)).
Aucun récepteur n'écoute `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` (vérifié : absent du
manifeste fusionné).

Sur API 33+, `USE_EXACT_ALARM` est auto-accordée et non révocable — le sujet ne se pose pas.
Sur **API 31-32** (`minSdk = 26`, donc dans le périmètre), `SCHEDULE_EXACT_ALARM` est révocable
depuis les Réglages. Android annule alors les alarmes exactes de l'app et émet ce broadcast.

**Ce qui reste à prouver** : que le système annule bien les alarmes déjà posées plutôt que de les
laisser courir. Je ne l'affirme pas de mémoire — c'est exactement le genre de certitude qui a produit
des conclusions fausses sur ce dépôt. **Vérifiable sur le S9** s'il est en API 31-32 ; sinon à poser
aux relecteurs externes. Si confirmé : les rappels disparaissent jusqu'au prochain reboot ou à la
prochaine édition, et « une alarme perdue en silence est le pire défaut d'un agenda ».

---

## Prochaine étape

Relecture externe GPT-5.2 + Gemini 3.1 Pro sur A1, A3, A4, A5 (les quatre constats où un correctif
est le plus risqué), avec un prompt respectant les 6 règles. Puis mon tri, puis le plan de correction
à valider **avant** toute écriture de code.
