# Agenda Tech — point de reprise (audit + durcissement du 2026-08-08)

> Document écrit pour survivre à une compaction de conversation. Tout ce qui suit a été **mesuré**, pas
> rappelé de mémoire. Il remplace le contexte perdu.

---

## 1. Où en est le dépôt

**Branche : `fix/audit-2026-08-08`**, partant de `main` = `893b683` = tag `v0.5.4`.
Arbre **propre**, rien à pousser, **29 commits**, gate **verte**.

| Commit | Lot | Contenu |
|---|---|---|
| `211adbc` | — | Rapports d'audit (lots 0, 1, 2) + prompts de relecture versionnés |
| `b378e66` | **A** | detekt **520 → 0**, sans baseline, sans renommer le package |
| `52a2075` | **F** | 5 premiers tests instrumentés + CI + assertion « zéro réseau » |
| `de52745` | **B** | Le **CRITIQUE F1** : un échec transitoire du Keystore n'efface plus l'agenda |
| `0d90527` | **B'** | Correctifs issus des relectures externes **sur mon propre lot B** |
| `c0e4603` | **C** | F2 — les deux receivers construisaient la base sur le thread principal |
| `a181882` | **D** | F5 budget · F16 overrides vivants · F7 alarmes orphelines |
| `dbde5c7` | — | Ce point de reprise |
| `dd84ad7` | **E** | F3 fuseaux Outlook + migration v6 · F4 lecture bornée · F8 EXDATE · F14 pliage |
| `7612866` | **G** | C0-1 les 11 permissions dans `PRIVACY` · F9 dans `SECURITY.md` · F13 · F12 |
| `cfe9985` | **H** | Relecture GPT des lots C/D/E : mon lot D **annulait des alarmes vivantes** |
| `ea51ba6` | — | Point de reprise à jour |
| `1fc1edc` | **I** | Plongées data-room + sécurité : **un CRITIQUE** — le downgrade effaçait tout |
| `c38a13b` | — | Point de reprise : les restes des deux plongées |
| `f44558f` | **J** | Trois contrôles échouaient **en ouvert** · l'éditeur annulait la migration v6 · plafonds de nombre |
| `d083781` | **K** | Les neuf derniers écarts entre ce que le code affirme et ce qu'il fait |
| `846401b` | — | Point de reprise |
| `1570ac0` | **L** | 3ᵉ passe d'audit, **sur mes propres correctifs** : trois régressions à moi, dont un test qui épinglait un défaut |
| `29e41a9` | **M** | Relecture **Gemini** enfin obtenue : `TZID=Z` corrigé, six constats réfutés |
| `d0282d7` | — | Point de reprise |
| `1f0729f` | **N** | SEC-6 + DR-9 : le plafond d'import était **par agenda** et la troncature muette |
| `3ca7f54` | **O** | Le logo du splash était **découpé en cercle** — coins arrondis rétablis |
| `c739a04` | — | Point de reprise |
| `f106393` | **P** | Une sauvegarde d'une version **plus récente** était reniée comme « pas une sauvegarde » |
| `8a4474c` | **Q** | Trois arbitrages que j'avais mal tranchés (receiver, trace de migration, widget) |
| `205f0c3` | **R** | L'écran de sauvegarde **annonçait un échec pendant qu'il remplaçait l'agenda** |
| `a81454e` | **S** | F5-ter : une série pathologique désarmait les rappels derrière elle |
| `3689b33` | — | Point de reprise |
| `18fd03d` | **T** | **F5-quater** : F5-ter, écrit une heure plus tôt, décimait les agendas homogènes |

**État vérifié** : `assembleDebug` OK · **323 tests JVM**, 0 échec · **lint 0** · **detekt 0** ·
**15 tests instrumentés verts** sur Galaxy S9 · manifeste fusionné à 11 permissions, contrôle OK ·
lancement réel sur appareil sans crash · splash vérifié **par capture d'écran avant/après**.

**Tous les lots du plan sont faits.** Ce qui reste est listé au §2.

⚠️ **Aucune release en cours.** `version.properties` est **intact** (`versionCode=51`,
`versionName=0.5.4`). Ne rien bumper. Le hook de fin de turn a signalé un « contexte version-bump » :
c'est un **faux positif**, déclenché par `build.gradle.kts` modifié pour des dépendances de test.

---

## 2. Ce qui reste à faire

> Les lots A, B, C, D, E, F, G et H sont **faits**. Ce qui suit est ce qui reste après eux — le détail
> historique des lots E et G est conservé plus bas, en archive, parce qu'il documente le raisonnement.

### R1 — la branche n'a jamais été poussée, donc la CI n'a jamais tourné

Le lot F a écrit `.github/workflows/ci.yml` (gate + assertion « zéro réseau » + tests instrumentés sur
émulateur). **Rien de tout cela n'a jamais été exécuté par GitHub**, puisque la branche est restée
locale. Ce qui est vérifié, c'est la porte **locale** et le script de permissions lancé à la main.

Un `git push` sur `fix/audit-2026-08-08` déclencherait la CI et rendrait ces trois assertions réelles.
C'est une action **sortante** : demander à Patrice avant.

### R2 — la relecture Gemini : **obtenue** (lot M)

`gemini-3.1-pro-preview` a fini par répondre après une trentaine de tentatives réparties sur la
journée. Les lots C, D et E ont donc eu **deux relecteurs externes**, comme le plan du lot 2 le
prévoyait. Tri dans [lot 5](2026-08-08-lot5-tri-relecture-gemini.md) : **1 constat retenu sur 8**
(`TZID=Z`), six réfutés avec leur raison — et le plus spectaculairement faux a produit deux
améliorations réelles.

Les lots **A, F et B** n'ont toujours qu'un relecteur externe. À relancer si l'occasion se présente ;
ce sont aussi les lots les plus anciens et les plus relus par ailleurs.

### R2 bis — les deux plongées d'audit sont **entièrement traitées**

Vingt-cinq constats rendus par les axes **data-room** et **sécurité**. Tous traités, en trois commits
(`1fc1edc`, `f44558f`, `d083781`) : corrigés, ou **réfutés par la mesure** avec le test qui épingle la
réfutation. Rien n'est resté en suspens.

Les trois plus lourds, tous vérifiés par moi avant correction :

| # | Constat | Comment il a été établi |
|---|---|---|
| **D1** | `fallbackToDestructiveMigrationOnDowngrade(false)` **armait** l'effacement qu'il prétendait interdire — le booléen est `dropAllTables`, pas un interrupteur | Désassemblage de room-runtime 2.8.4 (`putfield allowDestructiveMigrationOnDowngrade ← iconst_1`, inconditionnel), puis test de non-régression sur appareil, vérifié par sabotage |
| **S1** | Mon propre KDoc affirmait que la diffusion exact-alarm est protégée. **Faux sur API 26-30** | `am broadcast` sur le S9, avec témoin : `BOOT_COMPLETED` → SecurityException, la nôtre → `result=0` |
| **S16** | Le mutex F6 protégeait le compteur, pas la décision : une rafale d'appuis obtenait plusieurs essais PIN par fenêtre de back-off | Test de concurrence reconstruit pour mordre, vérifié par sabotage |

**Deux réfutations, épinglées par un test** : la locale turque ne casse pas `uppercase()`/`lowercase()`
(formes sans argument = `Locale.ROOT`), et `@ApplicationScope` est bien sur `Dispatchers.Default`.

**Un point mesuré plutôt qu'argumenté** : les alias tzdb « backward » (`Asia/Calcutta`, `Europe/Kiev`…)
résolvent tous sur la tzdb **réelle** du S9 — `TimeZonesDeviceTest`. Les garder est plus sûr que de les
moderniser : `Europe/Kyiv` n'existe qu'à partir de tzdb 2022b, donc absent des Android que l'app vise.

### R2 ter — la 3ᵉ passe d'audit : traitée, sauf **deux** points assumés

Les deux plongées relancées **sur mes propres correctifs** ont rendu 23 constats. Trois régressions
étaient de moi (`1570ac0`), les deux derniers points de code sont faits (`1f0729f`). Le motif dominant
est écrit dans le corps de `1570ac0` : *chaque correctif traitait sa moitié constatée et laissait la
moitié conséquente*.

**Deux points restent, tous deux assumés et écrits là où ils se lisent :**

| # | Ce que c'est | Pourquoi |
|---|---|---|
| **SEC-1** | Sur API 26-30, n'importe quelle app peut atteindre `ExactAlarmPermissionReceiver` par intent explicite et **démarrer notre processus**, ce qui ouvre la base (`MainApplication.onCreate`). Ma garde dans `onReceive` n'économise que la passe de replanification | Fermer suppose `android:enabled="false"` + `setComponentEnabledSetting` à chaque démarrage à froid, pour une nuisance (CPU/batterie, aucune divulgation) sur API 26-30 seulement. **Soumis à relecture externe** — voir R4 |
| **SEC-3** | `MigrationTrace` écrit en release mais ne se relit qu'en debug : elle sert là où on ne peut pas la lire | L'exposer dans « À propos » signifie du stockage en clair hors du conteneur chiffré. **Soumis à relecture externe** — voir R4 |

**DR-7** (l'aller-retour « journée entière » perd le fuseau d'auteur) a été **examiné et écarté** :
cocher « journée entière » détruit déjà l'heure de l'événement, donc perdre le fuseau avec elle est
cohérent, et revenir en arrière ne peut pas restaurer une information que l'utilisateur a lui-même
effacée. Soumis quand même à relecture, au cas où le raisonnement aurait un trou.

### R4 — relecture lancée sur la crypto de sauvegarde et les points ouverts

`prompts/PROMPT-RELECTURE-CRYPTO-ET-POINTS-OUVERTS.md` est prêt et versionné. Il couvre :

1. **`BackupEnvelope` / `AeadCipher` / `KeystoreManager` / `PinHasher`** — le morceau le plus sensible
   du dépôt et **le seul qui n'a jamais reçu de plongée dédiée**. Le `.atbak` est l'unique moyen de
   sauvegarde, son mot de passe n'est stocké nulle part.
2. Les trois points ci-dessus (SEC-1, SEC-3, DR-7), soumis comme des **questions**, pas comme des
   constats.

À lancer avec les deux fournisseurs :

```bash
python ~/.claude/tools/audit-ia.py --provider gpt --model gpt-5.2   --prompt prompts/PROMPT-RELECTURE-CRYPTO-ET-POINTS-OUVERTS.md   --out audits/ia-externe/rapport-gpt-crypto-<date>.md   app/src/main/java/com/filestech/agenda_tech/core/crypto/*.kt   app/src/main/java/com/filestech/agenda_tech/system/alarm/ExactAlarmPermissionReceiver.kt   app/src/main/java/com/filestech/agenda_tech/core/prefs/MigrationTrace.kt   app/src/main/java/com/filestech/agenda_tech/ui/screens/editor/EventEditorViewModel.kt
```

Idem `--provider gemini`. **Jamais audité non plus** : le widget Glance et le chemin de notification,
qui rendent du contenu d'agenda **hors de l'application**.

### R5 — quatre relectures externes de plus, et ce qu'elles ont donné (2026-08-08, soir)

Lancées avec `prompts/PROMPT-RELECTURE-{CRYPTO-ET-POINTS-OUVERTS,WIDGET-ET-NOTIFICATIONS,
INTERACTIONS-ET-BRANCHEMENTS,FAIBLESSES-RESTANTES}.md`. Rapports dans `audits/ia-externe/`.

**Zones jamais auditées, désormais couvertes** : la crypto du `.atbak`, le widget Glance, le chemin
de notification, l'axe interaction / dialogues / branchements.

**Neuf défauts corrigés** (lots P à S). Les deux qui comptent le plus :
- l'écran de sauvegarde annonçait « échec » **pendant** qu'il remplaçait l'agenda (double tap) ;
- une série récurrente pathologique laissait **tous les rappels derrière elle désarmés** après un
  redémarrage — trouvé par les deux relecteurs indépendamment, dans mon propre correctif F5-bis.

**Six constats réfutés, dont trois donnés « CONFIRMÉ » par les relecteurs** :
- le « bug du I turc » sur la table Windows→IANA (GPT **et** Gemini, ÉLEVÉ) : le `lowercase()` sans
  argument de Kotlin est `Locale.ROOT`. Réfuté **par un test**, pas par une affirmation ;
- le verrou qui s'ouvre après corruption des préférences (Gemini, CRITIQUE) : arbitrage S15 déjà
  documenté sur place, l'enveloppe du PIN meurt avec le fichier ;
- un identifiant de rappel débordant l'Int depuis un `.atbak` : les rappels sont reconstruits avec
  des identifiants neufs ;
- `flagSecure` non appliqué à chaud : il l'est, `MainActivity:211` ;
- le KDoc « every consumer wipes » de `PasswordDialog` : vrai, vérifié sur les deux use cases ;
- OOM à la restauration sur un `.atbak` de 80–200 Mo : la lecture est plafonnée à 16 Mio.

**Résidus assumés, écrits ici plutôt que corrigés** : une alarme repoussée survit à la suppression
de son événement (coût réel : un réveil unique sans effet) ; une série qui déborde sa propre part
d'expansion n'est pas réarmée par la passe de redémarrage.

**Vérifié mécaniquement au passage** : 278 clés de chaînes, **zéro orpheline**, zéro callback vide
réel — aucune fonctionnalité non câblée.

### R6 — la plongée interne qui a rattrapé les deux relectures externes

Lot T. Une plongée `android-code-quality-deep-dive` sur les **quatre commits de la soirée** a trouvé
**1 HAUT + 5 MOYENS**, dont une régression dans un correctif vieux d'une heure.

**Le HAUT est le plus instructif de tout l'audit** : F5-ter divisait l'allocation d'expansion
`total / n`. Les deux relecteurs externes avaient validé ce correctif. Aucun n'a fait l'arithmétique :
la division n'est clémente qu'avec une population **hétérogène**. Sur une population homogène — mille
rappels sur des séries quotidiennes de trois ans — la part tombe sous le besoin de chacun et **aucun**
rappel n'est armé, là où le budget partagé en armait 90 %. Corrigé par deux plafonds (passe +
par rappel), avec un test qui tombe si l'on remet la division.

**À retenir pour la suite** : deux relectures externes concordantes ne remplacent pas une vérification
arithmétique. Elles ont trouvé le défaut de F5-bis ; elles ont approuvé son correctif défectueux.

### R3 — ce que l'audit a laissé de côté, sciemment

- **`WAKE_LOCK` / `FOREGROUND_SERVICE`** sont dans l'APK et ne couvrent aucun usage. Les retirer
  suppose de neutraliser l'initialiseur de `androidx.work` et de **prouver** que les widgets Glance
  n'en souffrent pas. Documentées en attendant (`PRIVACY.md`).
- **F12** (révocation `SCHEDULE_EXACT_ALARM`) est traité en défense sans regret, **jamais reproduit** :
  aucun appareil API 31-32 disponible. Si un tel appareil apparaît, c'est le moment de trancher.
- **La table CLDR Windows → IANA** est vérifiée mécaniquement (chaque valeur résout, sur la JVM ET sur
  la tzdb réelle du S9), mais **l'exactitude sémantique d'un appariement** ne l'est que par lecture.
  Les zones européennes et les plus courantes ont été relues à l'œil ; les exotiques non.

---

## 2 bis. Archive — le détail des lots E et G, tels qu'ils ont été planifiés

### Lot E — durcir l'import `.ics` (la seule entrée de données externes)

| # | Constat | Fichier |
|---|---|---|
| **F3** | `timeZoneId` validé par 2 des 4 points d'ingestion. **F3a** corruption de l'instant à chaque aller-retour `.ics` (un rendez-vous Outlook recule de 2 h) · **F3b** dérive DST de toute série importée, contre la promesse du KDoc de `RecurrenceExpander` · **F3c** injection de ligne iCalendar par `TZID` non échappé, atteignable par un `.atbak` fabriqué · **F3d** les 9 tests d'aller-retour utilisent tous un fuseau **valide**, donc aucun ne peut le voir | `IcsCodec.kt:170-174` (stocke le TZID brut) · `IcsCodec.kt:92` (`TZID=` écrit sans échappement) · `IcsCodec.kt:310-311` (`zoneOf` → UTC) · `RestoreBackupUseCase.validate` (ne regarde pas le fuseau) — à comparer à `DeviceEventMapper.kt:65-67,82` qui, lui, **valide et normalise** |
| **F4** | Lecture `.ics` non bornée — **3 déclencheurs, 1 correctif** : (a) `openFileDescriptor` rend `null` → contrôle sauté · (b) un provider **ment** sur `statSize` → contrôle passé, lecture énorme · (c) `statSize == -1` → **refus d'un fichier légitime**. Le jumeau `BackupViewModel.readFile:181-205` borne la lecture elle-même (correctif H2 de `ed19126`) | `IcsViewModel.kt:63-70` |
| **F8** | Lignes `EXDATE` **répétées** : `current[property.name] = property` écrase, seule la dernière survit → les occurrences annulées ressuscitent. Trouvé par **les deux** relecteurs externes | `IcsCodec.kt:152-155` |
| **F14** | Le pliage coupe les paires de substitution : un emoji au mauvais offset devient `??` à l'export (`toByteArray(UTF_8)` remplace un substitut orphelin). RFC 5545 borne à 75 **octets**, pas 73 caractères | `IcsCodec.kt:126-137` + `IcsViewModel.kt:49` |

**Décision déjà validée par Patrice** : réparer en base les fuseaux invalides déjà importés, par une
**migration Room additive** (v5 → v6), et non seulement arrêter d'en créer.

**Patron de correction imposé par le dépôt** : normaliser **à l'ingestion**, exactement comme
`MAX_INTERVAL` a été porté dans `RecurrenceRule.init`. Les trois symptômes F3a/F3b/F3c tombent avec la
cause. Ajouter aussi l'échappement de `TZID` à l'export (ceinture et bretelles).

**Ce qui manque structurellement** : les tests `.ics` n'exercent que **notre propre sortie**. Le lot E
doit apporter les premiers cas construits à partir de **fichiers réels** (TZID Windows type
`Romance Standard Time`, plusieurs lignes `EXDATE`).

### Lot G — documentation et finitions

- **C0-1** — `PRIVACY.md` et `PRIVACY.fr.md` documentent **6** permissions, l'APK en porte **11**.
  Manquent : `USE_BIOMETRIC`, `USE_FINGERPRINT` (androidx.biometric), `WAKE_LOCK`, `FOREGROUND_SERVICE`
  (androidx.work via Glance). ⚠️ La CI échouera si on les retire de l'allowlist sans les retirer de
  l'APK — l'allowlist de `tools/check-manifest-permissions.py` et les deux `PRIVACY` doivent bouger
  ensemble, c'est le couplage voulu.
- **F9** — asymétrie widget/notification sur le verrou d'app. **Décision de Patrice : option (a)** —
  laisser le comportement et **écrire la décision dans `SECURITY.md`**, pour qu'elle cesse d'être un
  oubli.
- **F13** — MINEUR : le re-verrouillage à `onStop` est asynchrone, donc la garantie `FLAG_SECURE` sur
  l'instantané Recents ne tient pas si `flagSecure` est désactivé alors qu'un PIN est actif.
  `MainActivity.kt:99-109` et `:123-129`.
- **F12** — PROBABLE, **non testable sur le matériel disponible** : révocation de
  `SCHEDULE_EXACT_ALARM` sur API 31-32. À traiter en **défense sans regret** (un receiver sur
  `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`), pas comme la correction d'un défaut établi.

### Relectures externes restant à faire

*(Fait pour GPT au lot H, `cfe9985`. Voir R2 pour Gemini.)*

---

## 3. Faits vérifiés à ne pas re-découvrir

### Gemini Pro est indisponible — c'est un ÉCHEC consigné, pas « rien à signaler »

Environ **28 tentatives**, 3 modèles, charge utile réduite de moitié. Sondé à charge minimale :

| Modèle | Réponse |
|---|---|
| `gemini-3.1-pro-preview` | **HTTP 503** — « currently experiencing high demand » |
| `gemini-3-pro-preview` | **HTTP 404 — retiré** |
| `gemini-2.5-pro` | **HTTP 404 — retiré pour les nouveaux usagers** |

`gemini-3.1-pro-preview` est le **seul Pro joignable** et il n'existe **aucun repli**. Conséquence :
les lots A, F, B, C et D n'ont **qu'un relecteur externe sur deux**. GPT-5.2 fonctionne
(`--model gpt-5.2`).

### Le manifeste que la CI contrôle est bien celui de l'APK — mesuré

| Source | Permissions |
|---|---|
| `processReleaseMainManifest` (contrôlé par la CI) | 11 |
| `packaged_manifests` (étape ultérieure, par ABI) | 11 — identiques |
| APK signé, `aapt2 dump badging` | 11 — identiques |

**Promesse « zéro réseau » : TIENT.** Ni `INTERNET` ni `ACCESS_NETWORK_STATE` dans l'APK.
⚠️ Un `grep` sur le manifeste **source** fait apparaître `ACCESS_NETWORK_STATE` parce qu'elle y figure
**pour être retirée**. Seul le manifeste **fusionné** fait autorité.
⚠️ Un `grep` ligne-à-ligne sur le XML **rate `SCHEDULE_EXACT_ALARM`**, écrite sur trois lignes — d'où le
parsing XML dans `tools/check-manifest-permissions.py`.

### Le widget ne tourne PAS dans un autre processus

`android:process` est absent du manifeste fusionné (0 occurrence). Le widget partage le processus de
l'app. La garde sur `isLockEnabled()` reste néanmoins le bon choix : le lanceur peut lier le widget sur
un processus démarré à froid, sans Activity.

### Le piège `stateIn` non hydraté est ABSENT de ce dépôt

Les 12 `stateIn` sont tous dans des ViewModels sur `viewModelScope`. Les chemins réveillés par le
système lisent de vraies valeurs suspendues (`dataStore.data.first()`). Cherché, non trouvé.

### Autres points vérifiés solides — ne pas re-auditer

Clé SQLCipher (le correctif v0.5.2 tient, prouvé par test instrumenté) · débordement arithmétique des
récurrences (fermé par `MAX_INTERVAL=999` + cap 100 000, bornes recalculées) · modèle temporel face aux
changements d'heure et de fuseau · anti-force-brute du PIN · biométrie · visibilité des notifications
sur l'écran de verrouillage de l'appareil · zéroïsation · les 18 `LaunchedEffect`.

### `room-testing` est inutilisable ici

`room-testing` 2.8.4 exige kotlinx-serialization ≥ 1.8 ; l'app épingle 1.7.3 →
`AbstractMethodError`. Un bump test-only est **impossible** (AGP impose `{strictly 1.7.3}`). Bumper la
sérialisation embarquée est **exclu** : c'est elle qui écrit le `.atbak`, vérifié contre une implé Python
indépendante sur APK signé (H3, `ed19126`). `MigrationsTest` passe donc par le **vrai chemin de
production**, ce qui exerce plus, pas moins.

---

## 4. Règles de travail à ne pas perdre

1. **Vérifier avant d'affirmer.** Mes noms de colonnes v1 étaient faux, mes rulesets detekt étaient
   faux, ma prémisse sur le processus du widget était fausse, mon `runCatching` n'attrapait pas ce que
   je croyais. Chaque fois, la vérification a corrigé.
2. **Sabotage systématique.** Un test vert ne prouve rien tant qu'on ne l'a pas vu échouer. Fait
   **quinze fois** sur cette branche.
3. **Relire ses propres correctifs.** Mon correctif du CRITIQUE ne couvrait que **la moitié du trajet
   cryptographique** — trouvé par deux relecteurs indépendants. Et mon lot D, qui corrigeait un
   défaut, en a introduit un plus grave : le budget partagé faisait **annuler** les rappels suivants
   au lieu de les laisser tranquilles. Chaque lot de correctifs est un lot de code neuf.
4. **Le sabotage teste les tests, la relecture teste le code.** Trois de mes tests étaient verts avec
   le correctif retiré (emoji sur la `String` au lieu des octets, un seul offset au lieu d'un
   balayage, un `;` entre guillemets qui n'influençait rien). **Aucun relecteur externe ne l'a vu.**
   Inversement, aucun sabotage n'aurait trouvé que `+02:00` casse l'analyseur. Les deux, toujours.
5. **Git** : stager **fichier par fichier**, jamais `git add -A`. Messages via **`-F fichier`**, jamais
   `-m` (les accents y sont mangés).
6. **Ne pas modifier l'arbre de travail pendant qu'un auditeur le lit** — c'est ce qui a produit le faux
   positif « C1 » de `ed19126`.
7. **Jamais de baseline detekt.** La politique du lot A nomme des **règles** avec leur motif, pas des
   instances : c'est auditable et le compte ne peut pas dériver.

### Appareils

- **Galaxy S9 `22dbb7390a057ece`** — appareil de test, **API 29** (Android 10). Branché.
- **S24 FE `RZCY41EGKYL`** — **téléphone RÉEL de Patrice**. Annoncer avant toute installation.
- 🔴 **JAMAIS `connectedAndroidTest`** en local : la tâche vise **tous** les appareils connectés et
  **efface les données** de l'app. Cibler par série :

```bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb -s 22dbb7390a057ece install -r app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
adb -s 22dbb7390a057ece install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s 22dbb7390a057ece shell am instrument -w -r \
  com.filestech.agenda_tech.debug.test/com.filestech.agenda_tech.HiltTestRunner
```

⚠️ `connectedDebugAndroidTest` n'est utilisé QUE dans le job CI, où l'émulateur est le seul appareil.

### Gate

```bash
./gradlew :app:assembleDebug testDebugUnitTest :app:lintDebug detekt
```

### Relecture externe

```bash
python ~/.claude/tools/audit-ia.py --provider gpt --model gpt-5.2 \
  --prompt prompts/PROMPT-<sujet>.md --out audits/ia-externe/rapport-gpt-<sujet>-<date>.md <fichiers entiers>
```
Gemini : même commande, `--provider gemini` (modèle par défaut `gemini-3.1-pro-preview`), **boucler**.
Un rapport vide ou absent est un **ÉCHEC**, jamais un « rien à signaler ».

---

## 5. Où lire le détail

| Document | Contenu |
|---|---|
| [lot 0](2026-08-08-lot0-etat-des-lieux-et-manifeste-fusionne.md) | État des lieux + manifeste fusionné + les 3 constats C0 |
| [lot 1](2026-08-08-lot1-audit-par-motifs.md) | L'audit par les 4 motifs : 1 CRITIQUE, 7 MAJEURS, 3 MINEURS, **et ce qui a été cherché et NON trouvé** |
| [lot 2](2026-08-08-lot2-tri-relectures-et-plan.md) | Tri des relectures + le plan en 6 lots + les 3 décisions de Patrice |
| [lot 3](2026-08-08-lot3-journal-des-relectures-et-correctifs.md) | Journal des relectures sur mes propres correctifs |
| [lot 4](2026-08-08-lot4-tri-relecture-lots-CDE.md) | Tri de la relecture GPT des lots C/D/E : **6 constats retenus, 6 réfutés ou assumés avec leurs raisons** |
| `audits/ia-externe/` | Les 3 rapports bruts (GPT lot 1, GPT lots A+F, GPT lot B) + Gemini lot 1 |
| `prompts/` | Les prompts de relecture, versionnés parce que leur qualité décide de celle des rapports |

**Les corps de commit sont la documentation principale** : chacun explique le défaut, pourquoi il
existait, ce que le correctif change et ce qu'il risque de casser.
