# Agenda Tech — application des correctifs de l'audit global (2026-09-11)

> Compagnon de `2026-09-11-audit-global-application.md`. Tout ce qui suit a été **mesuré** — build,
> tests, appareil — pas rappelé de mémoire.

---

## 1. État final, mesuré

| Mesure | Avant | Après |
|---|---|---|
| Tests unitaires JVM | 364 | **385** — 0 échec, **0 ignoré** |
| Tests instrumentés (Galaxy S9, API 29, SQLCipher + Keystore réels) | 15 | **15 verts** |
| detekt | 0 (sur `main` + `test`) | **0**, et désormais **`androidTest` inclus** |
| lint | 0 erreur | 0 erreur |
| `assembleRelease` (R8) | jamais en CI | **vert**, et **dans la CI** |
| Release minifiée lancée sur le S9 | — | **démarre, processus vivant, aucun `ClassNotFound`** |
| Manifeste fusionné | 11 permissions, aucune réseau | **inchangé** — promesse tenue |

Gate local complet (`assembleDebug testDebugUnitTest lintDebug detekt`) : **BUILD SUCCESSFUL**.

---

## 2. Ce qui a été corrigé

### URGENT
- **AG-1** — `.ics` : `DTEND` facultatif et `DURATION` implémenté ; une journée entière sans fin dure
  un jour **calendaire** (pas 24 h, pour survivre aux changements d'heure). Parseur de durée extrait
  en `domain/ics/RfcDuration.kt`, **partagé** avec `DeviceEventMapper` au lieu d'y être recopié — ce
  qui a aussi corrigé le jumeau : une `DURATION` illisible côté fournisseur produisait la même durée
  nulle. **7 tests, contrôle négatif : 6 tombent sans le correctif.**
- **AG-5** — les deux `PRIVACY` et le manifeste ne prétendent plus que l'application ne planifie
  aucun travail WorkManager. La justification porte désormais sur la **forme** du travail (aucune
  contrainte posée), qui est vraie et vérifiable.
- **AG-4** — `rescheduleAll()` au lancement de l'application, une fois par processus, sur
  `ApplicationScope`. Placé dans `MainActivity` et **non** dans `MainApplication` : ce dernier
  s'exécute à tout démarrage de processus, widget compris, soit toutes les demi-heures.
  Complété par **`MY_PACKAGE_REPLACED`** sur `BootReceiver` (mise à jour de l'app).
- **AG-3** — `busy` dans `EventEditorUiState`, garde côté ViewModel **et** `enabled` sur les boutons,
  comme le jumeau `BackupScreen`. **3 tests, contrôle négatif : 2 tombent sans le garde.**

### PRIORITAIRE
- **AG-2 / AG-8 / AG-9** — une couture unique, `system/AgendaChangeNotifier`, appelée après **chaque**
  écriture d'agenda (éditeur ×5, les deux imports, suppression de calendrier). Elle ré-arme les
  alarmes et redessine le widget, sur `ApplicationScope`, sérialisée par un `Mutex`.
- **AG-6** — l'export manuel relit l'ancien fichier avant de tronquer et le remet en place si
  l'écriture échoue. Limite dite franchement : ça ne couvre pas un disque plein.
- **AG-7** — l'export `.ics` tronque enfin (`"wt"`), comme son jumeau.
- **AG-10** — `setPin` rend un `Boolean` ; l'échec est affiché (string FR + EN).
- **AG-11** — couture `canScheduleExactAlarms` : **les deux branches** de l'alarme exacte sont
  désormais testées. L'inexacte — celle de tout utilisateur d'Android 12+ sans permission — ne
  l'était par personne.
- **AG-12** — les 5 remplacements intégraux d'état deviennent des `update { copy }`.
- **AG-13** — `:app:assembleRelease` ajouté au gate CI : R8 n'était exercé dans aucun job.
- **AG-14** — ré-import `.ics` idempotent malgré des `UID` partagés (cf. §3).
- **AG-15** — `SECURITY.md` : les deux paragraphes périmés (SEC-6, DR-9) rectifiés.
- **AG-16** — le chemin automatique fait tourner la **vraie** crypto en test : scellé avec le mot de
  passe rendu par le coffre, puis rouvert. Plus un contrôle négatif au mauvais mot de passe.
- **AG-17** — plancher sur « maintenant » dans `nextEarliestStart`, comme son jumeau.

### AMÉLIORATION
- **AG-18** — l'âge d'anniversaire sur les 2 surfaces manquantes (recherche, notification) : 7 sur 7.
- **AG-19** — `dependabot.yml` (avec le **gel explicite** de `androidx.work` et `documentfile`) et
  `codeql.yml`.
- **AG-20** — `distributionSha256Sum` sur le wrapper Gradle.
- Règle R8 morte `net.sqlcipher.**` retirée (**0 entrée retenue**, mesuré) · posture `ktlint`
  rectifiée (le commentaire décrivait un provisoire devenu permanent) · justification detekt non
  étayée retirée et les seuils à 5× le défaut dits franchement · `MigrationsTest` asserte la colonne
  `kind` de la migration 6→7 · `<plurals>` pour « 1 événement » · `translatable="false"` mensonger
  retiré · deux pièges armés dans les tests désamorcés · coût PBKDF2 épinglé.
- **Points E.1, E.2, E.4 du rapport, fermés** : les trois invariants de `RecurrenceRule` sont
  réparés à la lecture (plus seulement `interval`) ; detekt analyse `androidTest` — **et y a trouvé
  un défaut dès la première passe** ; `MY_PACKAGE_REPLACED` ajouté.
- **Deux cycles de packages rompus** : les qualificateurs Hilt descendent dans `core/di/`
  (`domain` ne dépend plus de `di`), `BirthdayLabel` descend dans `domain/birthday/`
  (`ui` et `widget` ne se tiennent plus mutuellement).

---

## 3. La relecture externe, et ce qu'elle a rattrapé

Un lot restreint (4 diffs, 616 lignes) relu par **gpt-5.2**. **Sept constats, six valides, un faux.**

**Le plus important : une régression que j'avais introduite.** Mon premier correctif AG-14 posait un
`HashSet` d'ids « déjà revendiqués » par-dessus une `Map<String, Long>`. Il empêchait bien
l'écrasement — et faisait **pire** : la carte ne pouvant porter qu'un id par UID, le deuxième
`VEVENT` ne retrouvait jamais sa ligne et s'insérait à neuf **à chaque ré-import**. L'agenda
grossissait d'une copie par passage, là où le défaut d'origine stagnait.

Le vrai correctif était côté **base**, pas côté fichier : `sourceUidMap` → `sourceUidGroups`, qui
rend la liste des ids d'un UID, et le n-ième `VEVENT` retrouve la n-ième ligne. **Deux tests**
épinglent maintenant l'idempotence — ils n'existaient pas, et c'est leur absence qui a laissé passer
la régression.

Les cinq autres constats retenus : `DTEND` antérieur à `DTSTART` non rattrapé pour un événement
horodaté · une `DURATION` sur journée entière qui retombait à 24 h fixes (le piège DST, sur la
branche voisine de celle que j'avais traitée) · le filet de sauvegarde qui pouvait **détruire un
nouveau fichier déjà écrit** si le `close()` levait après des `write()` réussis · `busy` qui restait
bloqué à `true` sur les quatre chemins de suppression si la base levait · deux passes de
ré-armement concurrentes sans sérialisation.

**Le constat réfuté** : « `AgendaChangeNotifier` ne compile pas, `runCatching` ne prend pas de bloc
`suspend` ». Faux — `runCatching` est `inline`, et l'APK release contenant ce fichier avait été
construit trois minutes plus tôt. Un constat sur sept, ce qui est cohérent avec le taux consigné en
mémoire pour ces relectures : **vérifier chacun, toujours**.

---

## 4. Délibérément NON fait

- **Remonter `androidx.work` (2.7.1) et `documentfile` (1.0.0).** Gel documenté du 2026-08-31 §3 :
  ce sont les versions que Glance résout déjà, pour qu'aucun artefact résolu ne change avant la
  fusion de `!42991`. Dependabot les ignore nommément, avec la raison en tête de fichier.
- **Extraire les sections restantes d'`EventEditorContent`** (337 lignes, 26 paramètres). C'est un
  refactor de code qui fonctionne, sans filet de test d'interface, pour un gain de lisibilité. La
  doctrine dit de ne pas modifier l'existant sans raison forte ; le seuil detekt de 300 est
  maintenant documenté pour ce qu'il est — une dette assumée, pas un certificat.
- **Abaisser `MAX_ITERATIONS` (10 M)** dans l'enveloppe de sauvegarde. Ce plafond sert la
  compatibilité **ascendante** : un fichier écrit par une version future avec un KDF plus cher doit
  encore s'ouvrir. L'abaisser échangerait un déni de service de 17 secondes — que l'utilisateur peut
  interrompre — contre un refus définitif de fichiers légitimes.
- **La bascule `delete`-puis-`rename` de `SafAutoBackupTarget`.** Non atomique par nature côté SAF ;
  l'échec est signalé, et seule la sauvegarde du **même jour** est concernée.

---

## 5. Vérifié sur appareil (Galaxy S9, Android 10 / API 29)

- 15 tests instrumentés verts, y compris la nouvelle assertion sur la colonne `kind`.
- Build **debug** : démarre, aucun crash dans logcat.
- Build **release minifiée** : installée, démarre, processus vivant, aucun `ClassNotFound` ni
  `NoSuchMethod` — la règle R8 retirée était bien morte, et le code neuf survit à R8.
- Contrôle « zéro réseau » : 11 permissions, ni `INTERNET` ni `ACCESS_NETWORK_STATE`.

⚠️ **Non vérifié sur appareil** : le parcours d'import `.ics` de bout en bout par l'interface. Le
codec est une fonction pure — le test unitaire l'exerce au bon niveau, avec un contrôle négatif qui
rejoue le prédicat exact des vues. Un passage par l'écran d'import reste souhaitable avant le tag.

---

## 6. Aucune version n'a été bumpée

`version.properties` est **intact** (`versionCode=55`, `versionName=1.0.3`). La décision du
2026-08-31 tient : on attend `!42991` avant de taguer. Au moment du tag, les trois étapes du point de
reprise s'appliquent (bump à la main, changelogs fastlane FR+EN, **cinq** surfaces de
files-tech.com).
