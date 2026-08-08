# Agenda Tech — Lot 0 : état des lieux + manifeste fusionné release

**Date** : 2026-08-08
**Commit audité** : `893b683` (= `main`, = tag `v0.5.4`), arbre propre
**Version** : `versionCode=51`, `versionName=0.5.4`
**Build** : `./gradlew :app:assembleRelease` → BUILD SUCCESSFUL, 4 APK splits signés produits

---

## Point 1 — Ce qui a déjà été audité

### Les répertoires d'audit sont VIDES

| Répertoire | Contenu |
|---|---|
| `audit/ia-externe/` | vide |
| `audit/ia-interne/` | vide |
| `audits/ia-externe/` | vide (créé le 2026-08-08) |
| `audits/ia-interne/` | vide (créé le 2026-08-08) |
| `audits_results/` | vide (créé le 2026-08-08) |

Aucun rapport n'a jamais été déposé. Git ne suit pas les répertoires vides — ils n'apparaissent
donc même pas dans `git status`. **Il n'y a rien à relire.**

⚠️ `audit/` (singulier) et `audits/` (pluriel) coexistent avec les mêmes sous-dossiers. C'est un
doublon (doctrine §1 reco 2). Convention retenue pour la suite, à confirmer :
`audits/ia-externe/` = rapports bruts GPT/Gemini · `audits/ia-interne/` = mes constats bruts ·
`audits_results/` = le tri consolidé. `audit/` (singulier) à supprimer.

### La vraie base de connaissance est l'historique Git

Les audits passés n'ont pas produit de rapports : ils ont produit des **corps de commit détaillés**.
9 commits portent une trace d'audit exploitable. Synthèse de ce qui est **déjà traité** — donc à ne
pas resignaler :

| Commit | Version | Défauts fermés |
|---|---|---|
| `6aafe38` | v0.5.2 | **SEC-1 CRITICAL : base chiffrée avec une clé NULLE** (`raw.wipe()` avant que Room n'ouvre le fichier) + migration auto-diagnostiquante `rekeyLegacyZeroKeyDatabase()` · SEC-2 anti-force-brute PIN en mémoire de process (`force-stop` = compteur remis à zéro) → `LockThrottleStore` · SEC-3 PBKDF2 600k–10M sur le thread Main à l'export/restauration `.atbak` · `MainApplication` construisait le graphe Hilt (donc la base) sur le Main |
| `ea52760` | v0.5.3 | **F1/F5/F7 : `INTERVAL` de RRULE non borné → `DateTimeException` non rattrapée, crash en boucle au lancement, seule issue = effacer les données.** Bornage aux **5** points d'ingestion (ics, device, lecture DB, `.atbak`, éditeur) · F2/F4 injection de ligne iCalendar via l'UID à l'export · F9/F10/F11 `escapeText` ne neutralisait pas le CR · F3 biométrie Classe 2 acceptée → Classe 3 via `StrongBiometrics` · F6 course read-modify-write sur le back-off PIN → `Mutex` · **bug préexistant : `parseRRule` parsait les EXDATE et ne les passait jamais au constructeur** (occurrences annulées ressuscitées à l'import) |
| `893b683` | v0.5.4 | F3 (reste) biométrie adossée à une clé AndroidKeyStore `setUserAuthenticationRequired(true)` sans fenêtre de validité (le TEE arbitre, plus le callback) · **F8 : `ExpansionBudget` global partagé par passe de rendu** (le plafond par événement ne disait rien du nombre d'événements) · bornes RRULE tolérantes (`COUNT<1`, `COUNT`+`UNTIL` simultanés — lever faisait perdre TOUTE la récurrence via le `runCatching`) · `DeviceEventMapper` : `COUNT=0` perdait les DEUX bornes → série infinie · 8 défauts trouvés en 2 revues sur ses propres correctifs (catch trop étroit, Keystore sur le Main, prompts croisés, `lifecycleScope` survivant à `onStop`, clé orpheline après `disableLock()`) |
| `ed19126` | v0.4.0 | H1 `PRIVACY.md` omettait le `.atbak` · H2 `readBytes()` lisait tout AVANT le plafond (OOM) → plafond 16 Mo appliqué à la lecture · H3 `.atbak` vérifié pour de vrai sur APK release R8 (déchiffré par une implé Python indépendante) · L1 `validate()` acceptait une moitié d'override |
| `ab05feb` | — | H1 `lastOccurrenceStartBefore` sans test direct (les 2 tests indirects passaient par des règles **bornées**, jamais le chemin infini que son KDoc justifie) · **M1 asymétrie réelle : les vues calculaient les exclusions dynamiquement, la recherche lisait les EXDATE persistées** · **`FakeEventRepository.observeOverrides()` renvoyait toujours une liste vide → tout test s'y appuyant était VACANT** · M2 `SearchRow`/`AgendaRow` dupliqués → `ui/util/EventRow.kt` · L4 ligatures ﬁ/ﬂ non normalisées par NFD |
| `3b4da6f` | — | **CRITICAL : « Effacer les calendriers importés » supprimait aussi les calendriers faits main** (`WHERE is_default = 0`, filtre `source_id IS NOT NULL` manquant) alors que le dialogue promettait le contraire · `BrandDanger` sur les 2 derniers boutons destructifs · `DeviceEventMapper` copie de `MAX_FIELD_CHARS` (KDoc « can never drift » faux) · `MainActivity` injectait `AppDatabase` en champ (Keystore + AES-GCM sur le Main au démarrage) · `reminder_days` pluriel cassé |
| `d015632` | — | `sourceUid` perdu à l'édition → ré-import en doublon · **SEC1 HIGH : `@Upsert` réécrivait la ligne entière → adresse/GPS écrasés au ré-import** (corriger le 1er seul aurait transformé un doublon en perte de données) · SEC2 `GeoLink` notation scientifique sous 0,001° |
| `16f455a` | — | **`ACCESS_NETWORK_STATE` retirée via `tools:node="remove"`** — elle était dans l'APK v0.3.0 publié · M4 magic bytes vérifiés APRÈS la saisie du mot de passe · M2/M3 `validate()` sur `.atbak` (override orphelin, `id=0` renuméroté par Room) · lint 12 → 0 · `rememberAppLocale()` mutualisé |
| `2dfafa9` | — | L2 grant URI persistable de l'ancienne sonnerie non libéré · C5 `ImportEventsUseCase` sans test d'orchestration · fakes factorisés dans `FakeRepositories.kt` |

### Faux positifs connus — ne pas resignaler

- **C1** (`ed19126`) : l'auditeur avait lu l'arbre de travail pendant un commit en cours.
- **`MissingPermission`** sur les alarmes (`16f455a`) : la garde existe, lint ne la suit pas dans un
  helper. `@SuppressLint` ciblé et justifié.
- **L1 course d'~1 s** entre capture des ids et annulation des alarmes (`16f455a`) : arbitrage
  explicite, le remède coûte plus que le mal.
- **Absence de `MigrationTestHelper`** (`d015632`) : noté non bloquant, aucune migration n'en a.

### Ce que cet historique m'apprend sur la méthode

Trois signaux mesurés sur **ce** dépôt, à intégrer d'emblée :

1. **Les correctifs de ce dépôt introduisent des défauts.** 8 défauts trouvés en 2 passes de revue
   sur les seuls correctifs de la v0.5.4. Sortir le Keystore du thread Main a *ouvert* une classe de
   bug (prompts croisés, `authenticate()` après sauvegarde d'état).
2. **Les fakes de test ont menti au moins une fois** (`observeOverrides()` → liste vide constante).
   Tout test vert passant par un fake doit être vérifié par sabotage.
3. **Les KDoc de ce dépôt ont menti au moins quatre fois** (`readBytes` borné, `MAX_FIELD_CHARS`
   « can never drift », `PasswordDialog` sans `String`, magic bytes avant mot de passe). Un
   commentaire n'est jamais une preuve ici.

---

## Point 2 — Manifeste FUSIONNÉ release : la liste réelle

Régénéré : `app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml`
**Contre-vérifié sur l'APK signé** (preuve la plus forte, R8 + shrink appliqués) :
`aapt2 dump badging app/build/outputs/apk/release/app-universal-release.apk`

Les deux sources concordent exactement. `minSdk=26`, `targetSdk=35`, `versionCode=51`.

### VERDICT « ZÉRO RÉSEAU » : ✅ LA PROMESSE TIENT — CONFIRMÉ

- `android.permission.INTERNET` → **ABSENTE** de l'APK
- `android.permission.ACCESS_NETWORK_STATE` → **ABSENTE** de l'APK

Le `tools:node="remove"` fonctionne réellement. Le piège de méthode signalé est bien réel : le
`grep` sur la source fait apparaître `ACCESS_NETWORK_STATE`, l'APK ne la contient pas.

Vérifié aussi : `androidx.work.impl.background.systemalarm.ConstraintProxy$NetworkStateProxy` est
bien présent comme composant (il écoute `CONNECTIVITY_CHANGE`) mais porte
`android:enabled="false"` — ligne 241 du manifeste fusionné. Il est inerte, et sans
`ACCESS_NETWORK_STATE` il ne pourrait rien lire de toute façon.

Le fondement du retrait est confirmé factuellement : **aucune référence à WorkManager /
Constraints / NetworkType / *WorkRequest / CoroutineWorker dans `app/src/main`.** L'app n'enfile
zéro travail. Le commentaire du manifeste dit vrai sur ce point précis.

### Les 11 permissions réellement dans l'APK

| # | Permission | Origine | Documentée dans `PRIVACY.md` ? |
|---|---|---|---|
| 1 | `READ_CALENDAR` | manifeste source | ✅ oui |
| 2 | `POST_NOTIFICATIONS` | manifeste source | ✅ oui |
| 3 | `VIBRATE` | manifeste source | ✅ oui |
| 4 | `SCHEDULE_EXACT_ALARM` (`maxSdkVersion=32`) | manifeste source | ✅ oui |
| 5 | `USE_EXACT_ALARM` | manifeste source | ✅ oui |
| 6 | `RECEIVE_BOOT_COMPLETED` | manifeste source | ✅ oui |
| 7 | `USE_BIOMETRIC` | `androidx.biometric:1.1.0` | ❌ **NON** |
| 8 | `USE_FINGERPRINT` | `androidx.biometric:1.1.0` | ❌ **NON** |
| 9 | `WAKE_LOCK` | `androidx.work:work-runtime:2.7.1` | ❌ **NON** |
| 10 | `FOREGROUND_SERVICE` | `androidx.work:work-runtime:2.7.1` | ❌ **NON** |
| 11 | `…DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | `androidx.core` (auto, `signature`) | ❌ non (sans objet : niveau `signature`, auto-déclarée) |

Chaîne de dépendance de `androidx.work`, vérifiée par `dependencyInsight` :

```
androidx.work:work-runtime:2.7.1
+--- androidx.glance:glance:1.1.1  ->  glance-appwidget:1.1.1  ->  releaseRuntimeClasspath
\--- androidx.work:work-runtime-ktx:2.7.1  ->  androidx.glance:glance:1.1.1
```

Le commentaire du manifeste attribue correctement l'origine à Glance.

---

## Constats issus du point 2

### C0-1 — MAJEUR — La table de permissions de `PRIVACY.md` est incomplète (4 permissions sur 11 manquantes)

- **Fichiers** : [PRIVACY.md:29-35](PRIVACY.md#L29-L35) et [PRIVACY.fr.md:29-35](PRIVACY.fr.md#L29-L35)
- **Motif** : #3, jumeau asymétrique — *ce que le code fait* vs *ce que l'utilisateur voit*. C'est
  exactement la classe de défaut qui a motivé le retrait d'`ACCESS_NETWORK_STATE` en `16f455a`.
- **Statut** : **CONFIRMÉ** — les deux tables listent 6 permissions ; l'APK signé en contient 11.
- **Scénario d'échec concret** : un utilisateur installe Agenda Tech depuis F-Droid. F-Droid affiche
  la liste des permissions **lue dans l'APK** — il y verra `WAKE_LOCK` et `FOREGROUND_SERVICE`
  (« exécuter un service au premier plan », « empêcher le téléphone de se mettre en veille »). Il
  ouvre `PRIVACY.md`, qui affirme une table exhaustive de 6 permissions et la phrase
  « L'application ne demande **jamais** l'accès à … ». Deux des permissions qu'il voit n'y figurent
  pas. Pour une app dont l'argument de vente **est** la vérifiabilité, l'écart se retourne contre
  elle : c'est précisément le raisonnement écrit dans `16f455a`.
- **Aggravant** : `WAKE_LOCK` et `FOREGROUND_SERVICE` sont là pour servir un WorkManager que l'app
  **n'utilise pas du tout** (0 référence dans `app/src/main`). Elles ne couvrent aucune
  fonctionnalité réelle du produit.
- **Deux voies, à trancher** (je ne tranche pas seul, voir plus bas) :
  (a) documenter les 4 permissions dans les deux tables — sûr, immédiat ;
  (b) faire disparaître `WAKE_LOCK` + `FOREGROUND_SERVICE` de l'APK, comme `ACCESS_NETWORK_STATE`.
  ⚠️ **(b) demande une instruction, pas une déduction.** Retirer les permissions sans neutraliser
  `WorkManagerInitializer` laisserait les services `androidx.work` déclarés sans leur permission ;
  et retirer `WorkManagerInitializer` ferait lever `IllegalStateException` à tout appel de
  `WorkManager.getInstance()` — un « repli du mauvais côté » (motif #2) si Glance en fait un en
  interne sur un chemin que je n'ai pas prouvé mort. **À instruire au lot suivant, preuve exigée
  avant tout retrait.** L'option (a) est sans risque et devrait être faite quoi qu'il arrive.

### C0-2 — MAJEUR — Zéro CI : rien ne vérifie ce dépôt hors de la machine de Patrice

- **Fait** : `.github/` **n'existe pas**. Aucun workflow — ni build, ni test, ni detekt, ni CodeQL,
  ni release, ni Dependabot.
- **Statut** : **CONFIRMÉ**.
- **Contexte portefeuille** : les 8 autres apps ont au minimum un workflow. Agenda Tech est la
  seule à n'en avoir aucun. Dépôt **public**.
- **Scénario d'échec concret** : une PR (ou un push direct) réintroduit une dépendance tirant
  `INTERNET`. Le manifeste fusionné n'est régénéré que par un `assembleRelease` local. Personne ne
  le refait avant la release suivante. La promesse publique « zéro réseau » — badge en tête du
  README, argument sur files-tech.com — est cassée dans l'APK publié, et **rien n'échoue**.
  C'est déjà arrivé une fois sur ce dépôt : `ACCESS_NETWORK_STATE` a été livrée dans l'APK v0.3.0
  publié (`16f455a`).
- **Garde-fou à écrire, spécifique à ce projet** : un job qui régénère le manifeste fusionné
  release et **échoue** si `INTERNET` ou `ACCESS_NETWORK_STATE` y apparaît. La promesse devient
  une assertion de build au lieu d'une intention documentée.

### C0-3 — MAJEUR — `androidTest` contient ZÉRO test : seul le runner existe

- **Fichier** : [app/src/androidTest/java/com/filestech/agenda_tech/HiltTestRunner.kt](app/src/androidTest/java/com/filestech/agenda_tech/HiltTestRunner.kt)
- **Motif** : #4, chemin mort / harnais orphelin.
- **Statut** : **CONFIRMÉ** — l'unique fichier `androidTest` est le `AndroidJUnitRunner`, pas un
  test. Il n'y a **aucun** `@Test` instrumenté.
- **Aggravant** : 7 dépendances `androidTestImplementation` sont déclarées et **téléchargées**
  ([app/build.gradle.kts:238-244](app/build.gradle.kts#L238-L244)) — Espresso, `compose-ui-test-junit4`,
  `mockk-android`, `hilt-android-testing` — et `testInstrumentationRunner` est câblé
  ([app/build.gradle.kts:53](app/build.gradle.kts#L53)). Le harnais complet est monté ; il n'exécute rien.
- **Scénario d'échec concret** : la partie de l'app qui **ne peut pas** être couverte en JVM est
  précisément celle qui a produit les deux défauts les plus graves de l'historique — l'ouverture
  réelle de la base SQLCipher (`6aafe38` : la clé nulle n'a été confirmée que **sur émulateur**) et
  la biométrie adossée au TEE (`893b683` : validée **à la main sur S24 FE**). Ces deux vérifications
  existent aujourd'hui uniquement dans la mémoire des corps de commit. Une régression sur l'une des
  deux repasse les 241 tests JVM au vert.
- **Conséquence directe** : aucune migration Room n'est testée sur un vrai SQLite/SQLCipher
  (`MigrationTestHelper` absent — noté « non bloquant » en `d015632`, ce qui était vrai quand il n'y
  avait aucune migration ; à réexaminer maintenant).
- **Le S9 `22dbb7390a057ece` est branché et prévu pour ça.** Cible ciblée par série uniquement,
  jamais `connectedAndroidTest`.

---

## Prochaine étape

Point 3 : audit par les 4 motifs + les points durs, sur les 132 fichiers de production. Priorités
d'attaque déduites de ce lot :

1. Import `.ics` (surface d'attaque n°1, a déjà crashé l'app en boucle)
2. Chemin de la clé SQLCipher (a déjà été nulle en production)
3. `BootReceiver` + `ReminderScheduler` (motif #2 : `stateIn` non hydraté sur chemin réveillé par
   le système — `RECEIVE_BOOT_COMPLETED` est exactement ce chemin)
4. Widgets Glance vs verrou d'application (autre processus, motif #1)
5. Cohérence structurelle et maintenabilité (demande explicite de Patrice du 2026-08-08)
