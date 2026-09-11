# Agenda Tech — cohérence des patterns + POINT DE REPRISE (2026-09-11)

> **Écrit pour survivre à une compaction de conversation.** Tout ce qui suit est soit mesuré, soit
> explicitement marqué « à vérifier ». Il remplace le contexte perdu.
>
> Compagnons : `2026-09-11-audit-global-application.md` (le rapport d'audit) et
> `2026-09-11-audit-global-corrections.md` (le journal d'application des correctifs).

---

## 1. OÙ ON EN EST — état du dépôt

**Branche `feat/sauvegarde-auto-duplication-anniversaires`, POUSSÉE**, 8 commits au-dessus de
`8bbc641`, jusqu'à `d6c1357`. Arbre propre (seul `rapport relecture f-droid/` reste non suivi,
c'était déjà le cas avant la séance — **ne pas le committer**, c'est un document externe).

| Commit | Contenu |
|---|---|
| `a9c7e5b` | Rapport d'audit + les trois surfaces publiques devenues fausses |
| `d492c19` | `.ics` : `DTEND` facultatif, `DURATION`, ré-import idempotent |
| `b45cae2` | Alarmes : ré-armement au lancement, `AgendaChangeNotifier`, widget |
| `69053ef` | UI : double-tap, PIN muet, état de l'écran Sauvegarde, âge d'anniversaire |
| `b3b23e8` | CI (R8, Dependabot, CodeQL), 2 cycles de packages rompus, tests |
| `6969660` | **Désarmer AVANT la cascade** — mon correctif AG-9 était incomplet |
| `1f7a458` | Consignation du HIGH de la plongée sécurité |
| `d6c1357` | Parcours d'import vérifié sur appareil + rectification de portée |

**`version.properties` INTACT** (`versionCode=55`, `versionName=1.0.3`). La décision du 2026-08-31
tient : **on attend `!42991` avant de taguer**. Ne rien bumper.

### Mesuré à la fin de la séance
- **387 tests JVM, 0 échec, 0 ignoré** · detekt **0** (et `androidTest` désormais analysé) · lint 0
- **15/15 tests instrumentés** sur Galaxy S9 (API 29, SQLCipher + Keystore réels)
- **Release minifiée installée et lancée sur le S9**, aucun crash, aucun `ClassNotFound`
- Manifeste fusionné : **11 permissions, aucune réseau**
- Parité i18n : **304 chaînes + 3 plurals des deux côtés**, aucune orpheline
- CI relancée après le push : **CI + CodeQL en cours au moment d'écrire** — à consulter.

---

## 2. CE QUI RESTE À FAIRE — audit de cohérence, 6 déviations

Agent `android-architecture-coherence-checker`, lancé après les 8 commits. **Rien n'a encore été
corrigé** : Patrice a demandé de consigner avant de toucher au code.

### ⚠️ Statut de vérification — à lire avant d'agir

- **C1 : VÉRIFIÉ PAR MOI.** `BackupViewModel.kt:400-405` refait le geste à la main, et le fichier
  contient **0 occurrence** d'`AgendaChangeNotifier` (mesuré par `grep -c`).
- **C2 à C6 : RAPPORTÉS PAR L'AGENT, PAS ENCORE REVÉRIFIÉS PAR MOI.** À contrôler un par un avant
  de corriger — sur cette séance, **1 constat externe sur 7 était faux**, et deux de mes propres
  correctifs étaient incomplets. Ne pas corriger sur la foi du tableau seul.

### C1 — [HIGH, VÉRIFIÉ] `BackupViewModel.restore()` n'utilise pas `AgendaChangeNotifier`

`ui/screens/backup/BackupViewModel.kt:400-405` fait à la main ce que le notifier centralise :
`cancelReminders` + `rescheduleAll()` + `AgendaWidget().updateAll(context)`.

**Pourquoi ça compte, concrètement** : ce bloc tourne sur `viewModelScope`, pas `ApplicationScope`.
`rescheduleAll()` est lente (elle relit tous les rappels et déplie les récurrences) et le message de
succès n'apparaît qu'**après**. Si l'utilisateur quitte l'écran Sauvegarde avant la fin, la
coroutine est annulée en vol : **alarmes non ré-armées, widget non redessiné, aucune trace**. Il
manque aussi le `Mutex` du notifier, donc une restauration concurrente à un import peut armer
d'après un état périmé.

L'ironie : la KDoc d'`AgendaChangeNotifier` cite **la restauration** comme l'un des deux seuls
points qui redessinaient le widget avant la consolidation. Le notifier a été créé en citant ce site,
et ce site n'a pas été migré.

**Correctif** : injecter `AgendaChangeNotifier`, remplacer les lignes 400-405 par
`agendaChanged.onAgendaChanged()`. **Garder `cancelReminders(staleReminderIds)` avant** — c'est le
F7 spécifique à la restauration, le notifier ne le couvre pas.

### C2 — [HIGH, à revérifier] `SettingsViewModel.refreshWidget()` réimplémente la moitié « widget »

`ui/screens/settings/SettingsViewModel.kt:208-228`, appelée par `setPin`, `disableLock`,
`setWidgetHideTitles`. Sa KDoc décrit **mot pour mot** le même problème et la même solution que le
notifier (il faut `ApplicationScope`, pas `viewModelScope`).

**Correctif proposé** : `agendaChanged.onAgendaChanged(rearmReminders = false)` — aucun de ces
changements ne déplace un rappel — puis supprimer `refreshWidget()`.

⚠️ **Attention en corrigeant** : c'est `refreshWidget()` qui rend vraie la garantie LOCK-3
(« activer le verrou ne laisse jamais les titres lisibles sur l'écran d'accueil »). Le commentaire
d'`AgendaWidget.kt:102-108` le dit explicitement. Vérifier que le remplacement redessine bien
**immédiatement**, sinon la garantie retombe à 30 minutes.

### C3 — [HIGH, à revérifier] Aucun garde anti-double-tap sur l'écran Calendriers

`CalendarsScreen.kt:103-107` et `:122-124` ferment le dialogue de façon synchrone dans le callback
qui déclenche l'écriture ; `CalendarsViewModel` n'a **aucun** état `busy`.

**Le risque est une duplication de données, pas seulement de l'UX** : `Calendar.id` vaut `0L` pour un
calendrier neuf et `CalendarDao.upsert` est un `@Upsert` — sur `id = 0` il **insère**. Deux tapes
rapides sur « Enregistrer » créent donc potentiellement **deux calendriers identiques**. C'est la
forme exacte du bug corrigé le jour même sur l'éditeur (AG-3).

**C'est le plus important des six.**

### C4 — [MEDIUM, à revérifier] `IcsViewModel` sans garde côté UI

`ui/ics/IcsViewModel.kt:55,88` : ni `busy` ni `_importing`, et `MonthScreen.kt:551-558` ne désactive
rien. **Atténué** : le `Mutex` de `ImportEventsUseCase` protège les *données*. Le reste est de l'UX
(pas de spinner, bouton actif pendant l'opération). La KDoc d'`ImportEventsUseCase.kt:40-45` signale
déjà ce trou sans le fermer.

### C5 — [MEDIUM, à revérifier] `CalendarsViewModel.save()` avale l'échec

`ui/screens/calendars/CalendarsViewModel.kt:52` journalise le `Outcome.Failure` et n'expose rien.
`UpsertCalendarUseCase` documente pourtant rendre « a typed `AppError.Validation` **the UI can
surface directly** » — promesse non tenue. Risque faible en pratique (le dialogue vide déjà le nom),
mais le dialogue se ferme comme si tout allait bien alors que rien n'est écrit.

### C6 — [LOW, à revérifier] `24L * 60 * 60 * 1000` redéfini 4 fois

`DeviceEventMapper.kt:29`, `IcsCodec.kt:51`, `RfcDuration.kt:23`, `MonthViewModel.kt:288`. Contrevient
à « zéro doublon ». Non prioritaire.

### Ce que l'agent a vérifié SAIN (ne pas ré-auditer)

Aucun ViewModel n'injecte de DAO · la migration des qualificateurs Hilt vers `core/di` est complète
(plus aucun import de l'ancien package, tests compris) · les `Provider<T>` anti-base-sur-Main sont
posés partout où il faut · les `sealed` sont consommés exhaustivement · le `rearmReminders = false`
est utilisé de façon cohérente · `BirthdayLabel` dans `domain/` importe Compose, mais c'est
**documenté et délibéré** (rupture du cycle `ui ↔ widget`) · **`CalendarsViewModel.save()` n'appelle
pas le notifier et c'est CORRECT** : le widget n'affiche ni le nom ni la couleur d'un calendrier,
seule la visibilité le concerne et elle, elle l'appelle.

---

## 3. LE MOTIF DE LA JOURNÉE — à lire avant de corriger quoi que ce soit

**Le chemin jumeau non corrigé.** Il s'est manifesté **quatre fois**, dont **deux fois sur mes
propres correctifs** :

1. Sept jumeaux trouvés par l'audit initial (export manuel/auto, import .ics/système, widget/verrou,
   `initialEarliestStart`/`nextEarliestStart`, `setPin`/`AutoBackupSecret.store`…).
2. **Ma régression d'import** : un `HashSet` posé sur une `Map<String, Long>` empêchait l'écrasement
   et faisait grossir l'agenda d'une copie à chaque ré-import. La pluralité manquait **côté base**.
   Trouvée par gpt-5.2.
3. **Mon correctif AG-9 incomplet** : j'avais ajouté `onAgendaChanged()` après une suppression de
   calendrier en écrivant un commentaire qui décrivait le scénario F7. Or `rescheduleAll()` n'itère
   que les rappels **encore en base**. Trouvé par la plongée sécurité.
4. **C1/C2/C3 ci-dessus** : la consolidation par `AgendaChangeNotifier` n'a pas été appliquée aux
   deux sites que sa propre KDoc cite, et le garde `busy` n'a pas été posé sur l'écran Calendriers.

> **Décrire un défaut dans un commentaire et poser à côté un appel qui ne le traite pas est pire que
> de ne rien écrire : ça ferme la question pour le prochain lecteur.**

Et dans **tous** les cas : **aucun test ne couvrait le chemin**. C'est l'absence de test qui laisse
passer le correctif incomplet, pas l'inattention.

---

## 4. VÉRIFICATIONS SUR APPAREIL — ce qui est fait, ce qui ne l'est pas

### Fait (Galaxy S9, API 29, serial `22dbb7390a057ece`)
Parcours `.ics` complet par l'interface, **avec contrôle négatif** (correctif retiré, même fichier
réimporté) :

| Cas | Avec le correctif | Sans |
|---|---|---|
| `DTSTART;VALUE=DATE` sans `DTEND` | visible, « Toute la journée » | **« Aucun événement ce jour »** |
| `DURATION:PT1H30M` | **09:00 – 10:30** | **09:00 – 09:00** |
| `DTEND` normal (témoin) | 14:00 – 15:00 | 14:00 – 15:00 |

Deux imports du même fichier ⇒ **un seul exemplaire de chaque** (idempotence mesurée).

⚠️ **Ce contrôle a rectifié le rapport d'audit** : j'avais écrit que l'événement n'apparaissait
« ni mois, ni jour, ni semaine, **ni agenda**, ni widget ». Faux — la **vue Agenda l'affiche**, elle
groupe par date de début sur une fenêtre de ±1 an. Ce sont les filtres **par jour** qui le cachent.

### PAS fait
**AG-2 — le rafraîchissement du widget après une écriture n'a pas été mesuré sur appareil.**
Au moment d'écrire, `dumpsys appwidget` ne montre que les **providers déclarés**, aucune **instance
posée** pour `com.filestech.agenda_tech.debug`. Patrice a indiqué « les widgets fonctionnent » — à
reprendre : soit le widget a été posé sur la variante **release** (`com.filestech.agenda_tech`) et
non debug, soit la pose n'a pas abouti. **Impossible à automatiser** : aucune commande `adb` ne
dépose un widget sur l'écran d'accueil, il faut le lanceur.

**Protocole quand un widget sera posé** :
1. `adb shell dumpsys appwidget | grep -A3 AgendaWidgetReceiver` → confirmer un `appWidgetId`.
2. Créer un événement du jour dans l'app, revenir à l'écran d'accueil.
3. `uiautomator dump` → le titre doit apparaître **immédiatement**, pas après 30 min.
4. Supprimer l'événement → il doit **disparaître immédiatement** (c'était le défaut : il restait).

⚠️ **`FLAG_SECURE` bloque `screencap`** (fichier à 0 octet) — c'est la protection de l'app qui
fonctionne. Passer par `uiautomator dump`, qui lit l'arbre d'interface.

---

## 5. PIÈGES DE MESURE CONSIGNÉS (réutilisables)

- **`isReturnDefaultValues = true`** fait rendre **0** à `Build.VERSION.SDK_INT` en test JVM. Donc
  `SDK_INT < S` est **toujours vrai** et court-circuite tout `||` qui suit. C'est ainsi que la branche
  « alarme inexacte » — celle de tout utilisateur d'Android 12+ sans permission — n'était testée par
  personne, alors que trois assertions passaient.
- **detekt n'analyse pas `src/androidTest`** par défaut (sources = `main` + `test`). L'y ajouter a
  trouvé un défaut dès la première passe.
- **Le sélecteur de fichiers système s'ouvre en UNE tape** sur « Fichiers » dans le résolveur ; une
  seconde tape sur « Une seule fois » annule l'import **sans que rien ne le signale**.
- **`runCatching` est `inline`** : les appels `suspend` à l'intérieur sont légaux. Un relecteur
  externe a affirmé le contraire — l'APK release était construit, ce qui l'a réfuté en une commande.

---

## 6. À NE PAS FAIRE

⛔ **Ne pas remonter `androidx.work` (2.7.1) ni `documentfile` (1.0.0)**, malgré le signalement de
lint (2.11.2 disponible). Gel délibéré du 2026-08-31 §3 : ce sont les versions que Glance résout
déjà, pour qu'**aucun artefact résolu ne change** avant la fusion de `!42991`. `dependabot.yml` les
ignore nommément, avec la raison en tête de fichier.

⛔ **Ne pas bumper `version.properties`.** On attend `!42991`.

⛔ **Ne pas committer `rapport relecture f-droid/`** — document externe, non suivi avant la séance.

⛔ **Ne pas extraire `EventEditorContent`** (337 lignes) sans raison forte : refactor de code qui
fonctionne, sans filet de test d'interface. Le seuil detekt de 300 est documenté pour ce qu'il est —
une dette assumée.

---

## 7. AU MOMENT DU TAG (rappel, pas maintenant)

1. Bumper `version.properties` à la main (`versionCode` + `versionName`).
2. Changelogs fastlane **FR + EN** pour le nouveau `versionCode`, **cap 500 caractères**.
3. Bumper les **cinq** surfaces de files-tech.com : carte d'accueil, page dédiée FR et EN, tableau de
   téléchargement FR et EN.
