# Agenda Tech — audit global de l'application (2026-09-11)

> Grille : `J:\applications\.claude\skills\developpeur-apps\agents\audit_global_apps.md`, transposée.
> Elle vise SMS Tech (SMS/MMS, coffre, mode panique) ; ici : événements + récurrences ↔ conversations,
> rappels/alarmes ↔ envoi, base SQLCipher + `.atbak` ↔ coffre-fort, widget + notifications ↔ surfaces
> d'exposition.
>
> Périmètre : branche `feat/sauvegarde-auto-duplication-anniversaires`, commit `8bbc641`,
> v1.0.3 (versionCode 55). **Audit en lecture seule** — aucun fichier modifié pendant la phase d'audit.
>
> Méthode : quatre agents en parallèle (motifs de défaut, sécurité, fiabilité/concurrence,
> qualité/tests/dépendances) **plus** une lecture personnelle du noyau. **Chaque constat a été
> revérifié dans le code avant d'être retenu** — trois ont été reclassés en gravité, un que j'avais
> moi-même sur-généralisé a été corrigé, aucun n'a été retenu sur la seule foi d'un agent.

---

## Ce qui a été MESURÉ (pas rappelé de mémoire)

| Mesure | Résultat |
|---|---|
| `./gradlew testDebugUnitTest detekt lintDebug` | **BUILD SUCCESSFUL** |
| Tests unitaires JVM | **364 passés, 0 échec, 0 ignoré** (agrégé depuis `test-results/*.xml`) |
| detekt | **0 finding**, et **aucun fichier baseline n'existe** sur le disque |
| Secrets versionnés | **aucun** — `git ls-files` ne matche que `KeystoreManager.kt` (du code) |
| `TODO` / `FIXME` / `HACK` | **0** dans 195 fichiers `.kt` |
| `fallbackToDestructiveMigration*` | **absent**, et l'absence est argumentée jusqu'au bytecode |
| `AutoBackupWorker` sous R8 | **survit** — `AutoBackupWorker -> AutoBackupWorker`, constructeur `(Context, WorkerParameters)` préservé (mapping release du 2026-09-11) |
| `ktlint` | **1 956 violations** masquées par `ignoreFailures = true` |
| Règle R8 `net.sqlcipher.**` | **0 entrée retenue** (l'artefact est `net.zetetic.database` : 1 074) |

Le `0 ignoré` est le contrôle qui compte : c'est exactement le piège « test vert qui n'exécute rien »
consigné pour SMS Tech le 2026-09-07.

---

## A. Résumé exécutif

Code d'une maturité inhabituelle. Une vingtaine de passes d'audit ont laissé leurs marqueurs en ligne
(`F1`–`F14`, `S1`–`S16`, `SEC-1`–`SEC-6`, `LOCK-1`–`LOCK-9`, `DR-*`, `ROB-*`) : chaque décision porte
son scénario d'attaque et sa justification.

**Sur l'axe sécurité, il n'y a rien** — au-delà d'une dérive documentaire. Le risque s'est déplacé
vers **la fiabilité fonctionnelle et la cohérence entre chemins jumeaux** : chaque fois qu'une
fonction existe en deux exemplaires, une seule a reçu le durcissement. C'est le motif dominant de cet
audit, et il se répète **sept fois** (AG-1, AG-2, AG-3, AG-6/AG-7, AG-8, AG-10, AG-17).

| Domaine | État |
|---|---|
| Sécurité | Excellente — crypto, clés, verrou, exposition : rien à corriger. |
| Fiabilité | **Le maillon faible.** Un événement importable et invisible, un widget jamais rafraîchi, des rappels morts après « forcer l'arrêt ». |
| Qualité | Très bonne. |
| Performance | Bonne — rien sur le thread principal, index en place, expansions bornées deux fois. |
| Architecture | Bonne, deux cycles de packages mineurs. |
| UX | Correcte, avec des angles morts. |
| Tests | 364 verts — mais les branches qui comptent ne sont pas celles qui sont exercées. |

## B. Score global

| Domaine | Note |
|---|---|
| Sécurité | **9,5 / 10** |
| Qualité | 8,5 / 10 |
| Architecture | 8 / 10 |
| Performance | 8 / 10 |
| Tests | 7 / 10 |
| UX | 7 / 10 |
| **Fiabilité** | **6,5 / 10** |
| **Global** | **8 / 10** |

## C. Vulnérabilités critiques

**Aucune.** Dit sans réserve : le périmètre sécurité a été couvert fichier par fichier, et
l'historique git complet est propre. Un attaquant disposant des fichiers de l'application n'obtient
qu'une base SQLCipher et une clé enveloppée par une clé AndroidKeyStore non exportable.

---

## D. Problèmes importants

### 🔴 ÉLEVÉE

#### AG-1 — Un `.ics` journée entière sans `DTEND` s'importe et devient invisible partout

- **Domaine** : import / affichage
- **Fichier** : `domain/ics/IcsCodec.kt:192` — `DURATION` : **0 occurrence** dans le fichier
- **Constat** : `end = dtEnd?.let { … } ?: start`. Pour un `DTSTART;VALUE=DATE`, `start = end = minuit`.
  Les filtres de recouvrement sont stricts des deux côtés (`start < dayEnd && end > dayStart` —
  `RecurrenceExpander.kt:131`, `MonthViewModel.kt:220`) et les fenêtres commencent toutes à
  `atStartOfDay` **sur le même fuseau** que le parseur (`IcsCodec.kt:233`) : `end > dayStart` est donc
  faux à l'égalité exacte.
- **Reproduction** : importer `BEGIN:VEVENT / DTSTART;VALUE=DATE:20260714 / SUMMARY:Fête nationale /
  END:VEVENT`. L'app annonce « 1 événement importé ». Rien ne s'affiche : ni mois, ni jour, ni
  semaine, ni agenda, ni widget.
- **Impact** : RFC 5545 §3.6.1 rend `DTEND` optionnel. Un fichier de jours fériés, de calendrier
  scolaire ou un export Outlook s'importe et disparaît. Une fonction annoncée qui ne marche pas sur
  une entrée légitime.
- **Cause probable** : le jumeau `DeviceEventMapper.kt:57-64` force 1 jour et gère `DURATION` ; le
  codec `.ics` n'a jamais reçu ce traitement.
- **Correction** : aligner `IcsCodec` sur son jumeau + implémenter la branche `DURATION`.
- **Risque de régression** : nul, chemin isolé, couvert par tests.

> ⚠️ **Correction en cours d'audit.** J'avais d'abord écrit qu'un utilisateur pouvait créer un tel
> événement à la main. **Faux** : un événement de durée nulle à 14 h reste visible (`14:00 > 00:00`).
> Seules les durées nulles tombant **exactement sur une frontière de journée** disparaissent. Le
> `.ics` est le chemin réel.

#### AG-2 — Le widget n'est jamais rafraîchi après une écriture d'événement

- **Fichiers** : deux appelants d'`updateAll` dans tout le dépôt — `BackupViewModel.kt:390`
  (restauration) et `SettingsViewModel.kt:199` (verrou / réglage). Aucun depuis `persist`,
  `deleteDirect`, `deleteSeries`, `deleteThisOccurrence`, ni depuis les deux imports.
  `agenda_widget_info.xml` : `updatePeriodMillis="1800000"`.
- **Impact** : un événement créé n'apparaît pas avant 30 min ; un événement **supprimé reste
  affiché**.
- **Cause** : le commentaire `AgendaWidget.kt:102-108` documente précisément ce piège — **pour le
  verrou uniquement** — et le corrige là seulement.

#### AG-3 — Double-tap sur « Enregistrer » crée deux événements

- **Fichiers** : `EventEditorViewModel.kt:331` (aucun garde), `EventEditorUiState` (ni `busy` ni
  `isSaving`), `EventEditorScreen.kt:284` (`Button(onClick = onSave)` sans `enabled`).
- **Constat** : un nouvel événement est construit avec `id = 0L` ⇒ INSERT. Sur un événement existant,
  deux séquences `cancelEvent / deleteForEvent / upsert×N / rescheduleEvent` s'entrelacent ⇒ rappels
  dupliqués.
- **Jumeau qui fait bien** : `BackupScreen` garde chaque bouton par `enabled = state.busy == null`
  **et** `BackupViewModel.kt:349-352` ajoute un second garde côté ViewModel, avec le commentaire qui
  explique ce risque exact.

#### AG-4 — Après un « forcer l'arrêt », les rappels sont morts jusqu'au prochain redémarrage

- **Fichier** : `MainApplication.kt:38-74`.
- **Constat** : `rescheduleAll()` n'est appelé que depuis `BootReceiver` (`BOOT_COMPLETED` seul),
  `ExactAlarmPermissionReceiver` (API 31-32) et la restauration. **Rien au démarrage de l'app.**
- **L'asymétrie est frappante** : la sauvegarde automatique, elle, **est** ré-armée au lancement
  (lignes 65-73), avec un commentaire qui explique pourquoi c'est nécessaire. Le raisonnement
  s'applique mot pour mot aux alarmes, qui sont plus fragiles.

#### AG-5 — Deux documents publics et le manifeste sont devenus factuellement faux

- **Fichiers** : `PRIVACY.fr.md:51-52`, `PRIVACY.md:51-52`, commentaire `ACCESS_NETWORK_STATE` du
  manifeste.
- **Constat** : « Le code de l'application, lui, n'en planifie aucune » / « The app's own code
  schedules nothing » / `FOREGROUND_SERVICE` → « **Rien.** … il n'en est jamais planifié » / « aucune
  référence à WorkManager/Constraints/NetworkType dans `app/src/main` ».
  Or `AutoBackupScheduler.kt:34` enfile un `PeriodicWorkRequest`.
- **Nuance qui compte** : la **conclusion** tient — aucune contrainte n'est posée, donc
  `NetworkStateTracker` n'est jamais sollicité et le retrait d'`ACCESS_NETWORK_STATE` reste sûr.
  C'est **la preuve invoquée** qui est fausse, sur trois surfaces publiques.
- **C'est exactement la consigne du `CLAUDE.md`** : *avant toute fonction touchant stockage,
  permissions ou réseau, relire ces quatre surfaces*. Elles l'ont été pour le mot de passe de
  sauvegarde, pas pour WorkManager.

### 🟡 MOYENNE

| ID | Constat | Fichier |
|---|---|---|
| **AG-6** | Export `.atbak` manuel : `"wt"` tronque **avant** d'écrire ; un échec en cours perd l'ancienne sauvegarde *et* la nouvelle. Le jumeau automatique écrit dans un `tmp-` puis bascule. L'échec est **signalé** — d'où Moyenne et non Critique. | `BackupViewModel.kt:413` |
| **AG-7** | Miroir exact : l'export `.ics` n'a **pas** de `"wt"` — réécrire par-dessus un fichier plus gros laisse l'ancienne queue après `END:VCALENDAR`. | `IcsViewModel.kt:57` |
| **AG-8** | Les deux imports ne ré-arment aucun rappel : un événement déplacé puis ré-importé garde son alarme à l'ancienne heure. | `ImportEventsUseCase.kt`, `ImportDeviceEventsUseCase.kt` |
| **AG-9** | Alarmes orphelines : `deleteSeries` n'annule que celles du **maître** alors que `deleteSeriesAtomic` supprime aussi les dérogations ; la suppression de calendrier n'annule rien (cascade FK). Bénin isolément (ids `AUTOINCREMENT`) — **dangereux en chaîne avec une restauration**, qui réinsère les ids verbatim. | `EventEditorViewModel.kt:575`, `CalendarsViewModel.kt` |
| **AG-10** | `setPin` rend `Unit` et sort en silence si le Keystore refuse. Le jumeau `AutoBackupSecretStore.store()` rend `Boolean` et l'UI affiche l'échec. **Reclassé Haute → Moyenne** : l'interrupteur reflète `lockEnabled` lu du flux, donc l'échec est *visible* — mal expliqué, pas silencieux. | `LockRepositoryImpl.kt:55` |
| **AG-11** | `isReturnDefaultValues = true` ⇒ `SDK_INT = 0` en test JVM ⇒ `0 < 31` court-circuite le `||` et **`canScheduleExactAlarms()` n'est jamais appelé**. La branche *inexacte* — celle de tout utilisateur API 31+ sans permission — n'est testée par personne. | `ReminderScheduler.kt:338` |
| **AG-12** | 5 sites font `_state.value = BackupUiState(...)` là où 8 autres font `.update { copy }` ⇒ la section « sauvegarde automatique » disparaît après tout export/restauration, **emportant son unique avertissement d'échec**. Sur le chemin de succès, c'est une course avec le collecteur. | `BackupViewModel.kt:251,295,357,379,404` |
| **AG-13** | La CI ne construit **jamais** la release : R8 n'est exercé dans aucun job. *Vérifié localement que le Worker survit* — le risque est futur, pas actuel. | `.github/workflows/ci.yml:44` |
| **AG-14** | Ré-import `.ics` : N `VEVENT` partageant un UID se rabattent sur un seul `id` (« la dernière ligne gagne »), aucun index UNIQUE sur `source_uid`, `RECURRENCE-ID` jamais lu — alors que la RFC fait partager l'UID au maître et à ses occurrences modifiées. La KDoc annonce un ré-import idempotent et n'énumère pas cette limite. | `ImportEventsUseCase.kt:45-51` |
| **AG-15** | `SECURITY.md` périmé sur **deux paragraphes** : SEC-6 présenté comme « travail restant » (or l'écran l'affiche, FR+EN), et un plafond « par calendrier source » (or c'est une allocation unique depuis DR-9). | `SECURITY.md:221-228` |
| **AG-16** | Le chemin automatique ne fait tourner **aucune vraie crypto** en test (exporteur mocké, 64 octets nuls). Or son mot de passe vient d'une **autre source** que le manuel. Une régression du round-trip `AutoBackupSecret` laisserait 364 tests verts et tous les `.atbak` hebdomadaires illisibles. | `RunAutoBackupUseCaseTest.kt:33` |
| **AG-17** | `nextEarliestStart = fired + 1`, **sans plancher sur « maintenant »**, alors que son jumeau `initialEarliestStart` en a un quatre lignes plus haut. Atteignabilité faible — c'est l'asymétrie qui est le constat. | `ReminderScheduling.kt:28-33` |
| **AG-18** | L'âge d'anniversaire est câblé sur 5 surfaces et absent de 2 : la recherche et la notification de rappel. | `SearchScreen.kt:176`, `ReminderNotifier.kt:192` |
| **AG-19** | Ni Dependabot, ni CodeQL, ni `security.yml` — `ci.yml` seul. C'est le point 3 du §9.4 du `CLAUDE.md`, toujours ouvert. | `.github/` |
| **AG-20** | La distribution Gradle n'est pas épinglée par empreinte (`distributionSha256Sum` absent) — le maillon non vérifié d'une chaîne dont la reproductibilité est par ailleurs longuement argumentée. | `gradle-wrapper.properties:3` |

### 🟢 FAIBLE

`ktlint` désactivé derrière un commentaire « à remettre à `false` », masquant **1 956 violations** —
dominées par des règles que le projet contredit délibérément (`package-name` 153, nommage des
Composables 81) · seuils detekt à 5× le défaut (`LongMethod` 300 pour une fonction de 337 lignes) :
« 0 sans baseline » est **littéralement vrai** mais ne veut pas dire ce qu'un lecteur extérieur
comprendra · règle R8 morte `net.sqlcipher.**` · `MigrationsTest` n'asserte pas la colonne `kind` de
la migration 6→7 et sa KDoc dit encore « les cinq migrations (v1 → v6) » · `%1$d events` sans
`<plurals>` alors que le projet en a un · `about_privacy_policy_url` en `translatable="false"` mais
surchargé en FR · un `?: return@forEach` silencieux survit dans le jumeau du test qui dit avoir
éliminé ce motif · assertion tautologique dans `ExpansionBudgetWiringTest` · bascule
`delete`-puis-`rename` non atomique côté SAF · plafond `MAX_ITERATIONS` à 10 M (≈ 17 s de CPU sur
fichier hostile) · `AgendaViewModel.startDate` figé à la construction (agenda ancré sur la veille
après minuit).

---

## E. Points à vérifier (non tranchés — ne pas deviner)

1. **`EntityMappers.toDomain()` ne répare qu'un des trois invariants** de `RecurrenceRule`
   (`interval` oui ; `count XOR until` et `count ≥ 1` non), et **aucun `Flow` du dépôt n'a de
   `.catch`** (grep : 0). Une ligne violant un invariant ferait crasher au lancement — le scénario
   que la KDoc de `MAX_INTERVAL` décrit. **Inatteignable par une ligne écrite par cette version.**
   Une requête tranche :
   `SELECT COUNT(*) FROM events WHERE (rrule_count IS NOT NULL AND rrule_until IS NOT NULL) OR rrule_count < 1 OR end_utc_millis < start_utc_millis;`
2. **`detekt` n'analyse pas `androidTest`** (sources par défaut = `main` + `test`) — les tests de
   crypto et de migrations échappent donc à l'analyse statique. À confirmer par
   `./gradlew :app:detekt --dry-run`.
3. Le commentaire `detekt.yml:28-30` justifie ses seuils par un alignement sur « Now-In-Android, Tivi
   et Material 3 ». À ma connaissance `nowinandroid` n'utilise pas detekt. **Justification non
   étayée** — à vérifier ou à retirer.
4. Un receiver `MY_PACKAGE_REPLACED` coûterait une ligne de manifeste et couvrirait la mise à jour
   d'app ; le comportement d'`AlarmManagerService` sur `EXTRA_REPLACING` varie selon les versions —
   **à mesurer sur appareil, pas à déduire**.

---

## F. Architecture

Séparation domain / data / ui / system réellement tenue : **aucun import Android dans `domain/`**.

Deux cycles mineurs :
- `domain → di` (six fichiers, pour des qualifiers Hilt) — à déplacer dans `core/di/` ;
- `ui ↔ widget` (`AgendaWidget` importe `ui.util.birthdayDisplayTitle` pendant que deux ViewModels
  importent `AgendaWidget`).

Sur les « God classes », le verdict est nuancé : `EventEditorScreen.kt` (1 034 l.) est l'idiome
Compose et se justifie **comme fichier** ; ce qui ne se justifie pas est `EventEditorContent`
**seule** — 337 lignes, 26 paramètres — alors que trois sections voisines ont déjà été extraites.
`EventEditorViewModel` (37 fonctions dont ~24 setters) est la forme plate attendue d'un VM de
formulaire : **rien à faire**. `MainActivity` mélange cycle de vie et orchestration du verrou.

**Faiblesse structurelle réelle** : « écrire un fichier » existe en deux implémentations
(`BackupViewModel.writeFile` et `SafAutoBackupTarget.write`) et « importer » en deux autres, ce qui
produit mécaniquement AG-6, AG-7, AG-8 et AG-14.

---

## G. Matrice des risques (impact × probabilité)

| | **Probabilité forte** | **Probabilité moyenne** | **Probabilité faible** |
|---|---|---|---|
| **Impact fort** | AG-1 | AG-4, AG-6 | AG-9 |
| **Impact moyen** | AG-2, AG-5 | AG-3, AG-8, AG-12 | AG-10, AG-17 |
| **Impact faible** | AG-18, faibles | AG-7, AG-14, AG-15 | AG-20 |

---

## H. Plan d'action

### URGENT — avant tout tag
1. **AG-5** — réécrire les deux `PRIVACY` et le bloc du manifeste.
2. **AG-1** — aligner `IcsCodec` sur `DeviceEventMapper` + branche `DURATION`, avec un test par cas.
3. **AG-4** — un `rescheduleAll()` sur `appScope` au démarrage.
4. **AG-3** — un `busy` dans `EventEditorUiState` + `enabled` sur le bouton.

### PRIORITAIRE
5. **AG-2** — `updateAll` après chaque écriture d'événement.
6. **AG-6 / AG-7** — unifier les deux écrivains de fichiers.
7. **AG-8 / AG-9** — planificateur appelé depuis les imports et les suppressions en cascade.
8. **AG-11 / AG-16** — les deux tests dont l'absence rend verte une suite de 364.
9. **AG-13** — `:app:assembleRelease` dans le job `gate`.
10. **AG-10, AG-12, AG-14, AG-15**.

### AMÉLIORATION
AG-18, AG-19, AG-20, les faibles, les deux cycles de packages, l'extraction des sections restantes
d'`EventEditorContent`.

### ⛔ Ce qu'il ne faut PAS faire
**Ne pas remonter `androidx.work 2.7.1` ni `documentfile 1.0.0`.** Un agent l'a recommandé (lint
signale 2.11.2 disponible) ; c'est un **gel délibéré et documenté** — point de reprise du 2026-08-31
§3 : ces versions sont celles que Glance résout déjà, pour qu'**aucun artefact résolu ne change**
avant la fusion de `!42991`. Les remonter casserait la vérification de build reproductible en cours.

---

## Les 5 problèmes que je refuserais de laisser passer

1. **AG-1** — un `.ics` de jours fériés s'importe, l'app annonce « N événements importés », et rien
   n'apparaît nulle part.
2. **AG-5** — deux documents publics et le manifeste affirment ce que le code contredit depuis le
   31 août. Sur une app dont l'argument est la vérifiabilité, c'est le défaut le plus coûteux.
3. **AG-4** — un « forcer l'arrêt » tue les rappels jusqu'au prochain redémarrage, sans un mot, alors
   que la sauvegarde automatique est ré-armée au lancement.
4. **AG-3** — un double-tap crée deux événements, et le garde existe déjà dans l'écran d'à côté.
5. **AG-2** — un événement supprimé reste affiché sur l'écran d'accueil jusqu'à une demi-heure.

---

## Suivi des corrections

Voir `2026-09-11-audit-global-corrections.md` pour le journal d'application des correctifs.
