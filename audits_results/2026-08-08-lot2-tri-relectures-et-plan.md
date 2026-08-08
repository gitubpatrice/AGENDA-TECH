# Agenda Tech — Lot 2 : tri des relectures externes + plan de correction

**Date** : 2026-08-08 · **Commit** : `893b683` (`main` = `v0.5.4`)
**Relecteurs** : GPT-5.2 · Gemini 3.1 Pro (`gemini-3.1-pro-preview`)
**Périmètre envoyé** : 30 fichiers **entiers** (185 647 caractères), identiques pour les deux.
Rapports bruts : [rapport-gpt](../audits/ia-externe/rapport-gpt-lot1-2026-08-08.md) ·
[rapport-gemini](../audits/ia-externe/rapport-gemini-lot1-2026-08-08.md)

Gemini a rendu `503` à la 1ʳᵉ tentative et abouti à la 2ᵉ, comme attendu. Les deux rapports sont
non vides et exploitables.

---

## Tableau de recoupement

| # | Constat | Moi | GPT-5.2 | Gemini | Verdict après vérification |
|---|---|---|---|---|---|
| **F1** | `DatabaseFactory` efface l'agenda sur échec transitoire | ✅ CRITIQUE | ❌ **manqué** | ✅ CRITIQUE | **CONFIRMÉ — CRITIQUE** |
| **F2** | Base construite sur le thread principal dans les 2 receivers | ✅ MAJEUR | ❌ **« ⇒ OK », faux** | ✅ MAJEUR | **CONFIRMÉ — MAJEUR** |
| **F3** | `timeZoneId` non validé à l'import `.ics` et au `.atbak` | ✅ MAJEUR | ✅ MAJEUR | ❌ manqué | **CONFIRMÉ — MAJEUR** |
| **F4** | Lecture `.ics` non bornée — **3 déclencheurs distincts** | ✅ (1/3) | ✅ (2/3) | ❌ manqué | **CONFIRMÉ — MAJEUR** |
| **F5** | `ExpansionBudget` sur 1 des 3 chemins | ✅ MAJEUR | ❌ manqué | ✅ mais **sévérité fausse** | **CONFIRMÉ — MAJEUR** (pas CRITIQUE) |
| **F6** | `setPin` échoue en silence | ❌ **manqué** | ❌ manqué | ✅ CRITIQUE | **CONFIRMÉ — MAJEUR** (pas CRITIQUE) |
| **F7** | Restauration : alarmes orphelines sur repli `emptyList()` | ❌ **manqué** | ✅ MAJEUR | ❌ manqué | **CONFIRMÉ — MAJEUR**, *pire* que décrit |
| **F8** | Lignes `EXDATE` répétées : seule la dernière survit | ❌ **omis du rapport** | ✅ MAJEUR | ✅ MAJEUR | **CONFIRMÉ — MAJEUR** |
| **F9** | Asymétrie widget / notification sur le verrou | ✅ | ❌ | ❌ | CONFIRMÉ — **arbitrage produit** |
| **F10** | Zéro test instrumenté | ✅ | — (pas de tests fournis) | — | CONFIRMÉ — MAJEUR |
| **F11** | Zéro CI | ✅ | — | — | CONFIRMÉ — MAJEUR |
| **F12** | Révocation `SCHEDULE_EXACT_ALARM` sur API 31-32 | ⚠️ PROBABLE | « je ne sais pas » | ✅ « oui » sûr | **RESTE PROBABLE** — voir ci-dessous |
| **F13** | `FLAG_SECURE` / instantané Recents | ✅ MINEUR | ❌ | ❌ | CONFIRMÉ — MINEUR |
| **F14** | Le pliage `.ics` casse les paires de substitution | ✅ MINEUR | ❌ | ❌ | CONFIRMÉ — MINEUR |
| **F15** | Gate detekt rouge + 10 imports morts | ✅ | — | — | CONFIRMÉ — MINEUR |

**Lecture du tableau** : aucun constat n'a été trouvé par les trois. Les deux relecteurs externes ont
apporté **3 constats réels que j'avais manqués ou omis** (F6, F7, F8) et **2 déclencheurs
supplémentaires** sur F4. Réciproquement, chacun a manqué le CRITIQUE ou s'est trompé dessus. La
relecture croisée a payé dans les deux sens.

---

## Corrections que j'apporte aux relecteurs

Je ne reprends aucun constat externe sans l'avoir vérifié dans le code. Trois ont dû être corrigés.

### ❌ Gemini : « recherche = CRITIQUE, ANR, gel total de l'application » — **sévérité rejetée**

Gemini écrit : « *10 millions d'itérations exécutées de manière synchrone à chaque frappe clavier,
figeant totalement l'application (ANR)* ».
**Faux.** [SearchEventsUseCase.kt:59](../app/src/main/java/com/filestech/agenda_tech/domain/usecase/SearchEventsUseCase.kt#L59)
termine par `.flowOn(defaultDispatcher)`, et `defaultDispatcher` est `Dispatchers.Default`
([CoroutineModule.kt](../app/src/main/java/com/filestech/agenda_tech/di/CoroutineModule.kt)).
Le calcul ne touche jamais le thread principal : **pas d'ANR**. Ramené à **MAJEUR**.

**Mais son scénario est meilleur que le mien et je l'adopte.** J'avais construit F5 autour d'un
import hostile ; Gemini montre que la frappe d'une lettre courante suffit. Et cela révèle un
sous-constat que ni lui ni moi n'avions formulé :

> [SearchViewModel.kt:23-30](../app/src/main/java/com/filestech/agenda_tech/ui/screens/search/SearchViewModel.kt#L23-L30)
> justifie l'**absence de debounce** par : « *the corpus is folded once when the data changes, so
> typing only runs a `contains` over pre-folded strings — **there is no expensive work to throttle**.* »
> **Cette justification est fausse**, et c'est précisément F5 qui la falsifie : `dateHit`
> ([SearchEventsUseCase.kt:139-149](../app/src/main/java/com/filestech/agenda_tech/domain/usecase/SearchEventsUseCase.kt#L139-L149))
> s'exécute pour **chaque** résultat et peut coûter jusqu'à 100 000 itérations `java.time`. Il y a
> bien du travail coûteux à étrangler.
> Aggravant : `search()` n'est pas `suspend` et `occurrenceStarts` est une `Sequence` **sans point
> de suspension** — l'annulation coopérative ne peut donc **pas** l'interrompre. Chaque frappe
> empile un recalcul complet et non annulable.

### ❌ Gemini : « `setPin` = CRITIQUE, l'app reste déverrouillée à l'insu de l'utilisateur » — **ramené à MAJEUR**

Le défaut est **réel** et je l'avais manqué :
[LockRepositoryImpl.kt:55](../app/src/main/java/com/filestech/agenda_tech/data/local/settings/LockRepositoryImpl.kt#L55)
fait `if (wrapped == null) return@withContext` — si le Keystore échoue à envelopper le blob,
`LOCK_ENABLED` n'est jamais écrit, et `setPin` retourne `Unit` : **la signature rend l'échec
physiquement irrapportable.**

Mais l'affirmation « *l'UI considère que le PIN a été configuré avec succès* » est **inexacte**.
Vérifié : [SettingsScreen.kt:237](../app/src/main/java/com/filestech/agenda_tech/ui/screens/settings/SettingsScreen.kt#L237)
pose `checked = lockState.lockEnabled`, et `lockState` dérive de `lockRepository.lockEnabled`, donc de
DataStore ([SettingsViewModel.kt:46-51](../app/src/main/java/com/filestech/agenda_tech/ui/screens/settings/SettingsViewModel.kt#L46-L51)).
**L'interrupteur reste donc sur « off »** : l'état affiché ne ment pas, il se corrige tout seul.

Ce qui reste, et qui suffit à en faire un MAJEUR : la boîte de dialogue se ferme quand même
([SettingsScreen.kt:261-267](../app/src/main/java/com/filestech/agenda_tech/ui/screens/settings/SettingsScreen.kt#L261-L267)),
**aucun message n'est affiché**, et un utilisateur qui ne regarde pas l'interrupteur croit avoir
posé un code. Et c'est **motif #3** : `893b683` a corrigé exactement ce défaut sur le bouton
biométrique — « *plutôt qu'un bouton qui échoue en silence* » — et n'a pas touché à `setPin`.

### ❌ GPT : « `BootReceiver.onReceive`: check action puis `goAsync()` immédiatement ⇒ OK »

**Faux, et c'est un angle mort instructif.** GPT avait le fichier et a lu le corps de `onReceive`
sans voir que `@Inject lateinit var scheduler: ReminderScheduler` est résolu par Hilt **avant** que
ce corps ne s'exécute. Gemini l'a vu. C'est le CRITIQUE indirect (F1) qui se joue là.

En revanche **GPT a posé la bonne question** sur `@ApplicationScope`, et je l'ai vérifiée : le scope
est bâti sur `Dispatchers.Default`, donc le travail **après** `goAsync()` est bien hors du thread
principal. Aucun défaut supplémentaire de ce côté.

### ⚠️ F12 — je refuse de trancher sur la seule assurance de Gemini

Gemini répond « **Oui** » avec assurance ; GPT répond « **je ne sais pas** ». Une réponse assurée
contre une abstention n'est **pas** une vérification — c'est exactement le schéma qui a produit des
conclusions fausses sur ce dépôt.

Et ce n'est **pas testable sur le matériel disponible** : le S9 est en **API 29**
(`ro.build.version.sdk = 29`, Android 10) — `canScheduleExactAlarms()` n'existe qu'à partir de
l'API 31, et `SCHEDULE_EXACT_ALARM` n'y est pas révocable. Le S24 FE est au-delà de l'API 33, où
`USE_EXACT_ALARM` est auto-accordée et non révocable. **La fenêtre API 31-32 n'existe sur aucun de
tes deux appareils.**

Conséquence pratique : le correctif (écouter `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`
et replanifier) **est sûr et peu coûteux même si le défaut n'existe pas** — un receiver qui ne
reçoit jamais rien ne coûte rien. Je le proposerai comme **défense sans regret**, pas comme la
correction d'un défaut établi. C'est la formulation honnête.

---

## Constats retenus, par sévérité

### CRITIQUE

**F1 — `DatabaseFactory.kt:39-47` — un échec transitoire du Keystore efface tout l'agenda.**
Moi + Gemini, indépendamment, tous deux CERTAIN. `catch (e: Exception)` → `destroyKeyFile()` +
`deleteDatabase()`. Le fournisseur promet le contraire par écrit et expose une hiérarchie typée
`KeystoreInvalidated`/`WrapCorrupted`/`Io` **qu'aucun appelant n'exploite** (code mort). Le `catch`
attrape en plus les exceptions non mappées de keystore2. `allowBackup=false` : perte définitive.
Pire chemin : `BootReceiver`, sans écran ni question.

### MAJEUR

| # | Constat | Fichier |
|---|---|---|
| F2 | Base construite sur le thread principal dans `BootReceiver` **et** `ReminderReceiver`, avant `goAsync()`. Les jumeaux `MainActivity` (`3b4da6f`) et `MainApplication` (`6aafe38`) ont été corrigés, pas eux. Le KDoc de `ReminderReceiver` affirme le contraire. | `BootReceiver.kt:21-23`, `ReminderReceiver.kt:31-33` |
| F3 | `timeZoneId` validé par 2 des 4 points d'ingestion. **F3a** corruption de l'instant à chaque aller-retour `.ics` (un rendez-vous Outlook recule de 2 h) · **F3b** dérive DST de toute série importée, contre la promesse du KDoc · **F3c** injection de ligne iCalendar via `TZID` non échappé, atteignable par un `.atbak` fabriqué · **F3d** les 9 tests d'aller-retour utilisent tous un fuseau valide, donc aucun ne peut le voir | `IcsCodec.kt:170-174, 92, 310-311` · `RestoreBackupUseCase.kt:95-125` |
| F4 | Lecture `.ics` non bornée — **3 déclencheurs, 1 correctif** : (a) `openFileDescriptor` rend `null` → contrôle sauté *[moi]* · (b) un provider **ment** sur `statSize` → contrôle passé, lecture énorme *[GPT]* · (c) `statSize == -1` → **refus d'un fichier légitime** de 10 Ko *[GPT]*. Le jumeau `BackupViewModel.readFile` borne la lecture elle-même (correctif H2), `IcsViewModel` non | `IcsViewModel.kt:63-70` |
| F5 | `ExpansionBudget` sur 1 des 3 chemins d'expansion. Déclencheur réel : **taper une lettre courante**. Le KDoc de `SearchViewModel` justifie l'absence de debounce par une affirmation que ce constat falsifie, et le calcul n'est pas annulable | `SearchEventsUseCase.kt:140,143` · `ReminderScheduling.kt:45` |
| F6 | `setPin` échoue en silence quand le Keystore refuse : signature `Unit`, aucun message. Motif #3 vs le bouton biométrique corrigé en `893b683` | `LockRepositoryImpl.kt:47-61` |
| F7 | Restauration : `runCatching { … }.getOrDefault(emptyList())` défait le but documenté du code auquel il est attaché. **Vérifié pire que ce que GPT décrit** : les ids d'événements sont réinsérés **verbatim** depuis la sauvegarde, donc une alarme orpheline retrouve un id valide et `ReminderActionHandler:39-41` poste **une notification pour le mauvais événement, à la mauvaise heure**, puis la replanifie | `BackupViewModel.kt:134` · `ReminderActionHandler.kt:39-46` |
| F8 | Lignes `EXDATE` répétées : `current[property.name] = property` écrase, seule la dernière survit → les occurrences annulées ressuscitent. Trouvé par **les deux** relecteurs. Même symptôme que le bug corrigé en v0.5.3, mécanisme différent — et invisible pour la suite de tests, qui n'exerce que notre propre export (une seule ligne `EXDATE`) | `IcsCodec.kt:152-155` |
| F10 | Zéro test instrumenté : `DatabaseFactory`, `DatabaseKeyManager`, `ReminderScheduler`, `Migrations`, `LockRepositoryImpl` n'ont aucune couverture | `app/src/androidTest/` |
| F11 | Zéro CI : rien ne vérifie la promesse « zéro réseau », déjà cassée une fois dans un APK publié | `.github/` absent |

### Arbitrage produit, je ne tranche pas

**F9** — le widget masque les titres quand le verrou est actif, la notification les affiche.
Asymétrie confirmée ; le caractère « défaut » t'appartient. Minimum : **écrire la décision** dans
`SECURITY.md`, pour qu'elle cesse d'être un oubli.

### MINEUR

**F13** `FLAG_SECURE` / instantané Recents (re-verrouillage asynchrone à `onStop`) ·
**F14** le pliage `.ics` coupe les paires de substitution → un emoji devient `??` à l'export ·
**F15** gate detekt rouge (320/520 = le nom de package compté deux fois) + 10 imports morts.

### PROBABLE — à ne pas corriger comme un défaut

**F12** révocation `SCHEDULE_EXACT_ALARM` sur API 31-32. Non testable ici. Traité en défense sans
regret, pas en correctif.

---

## Plan de correction proposé — à valider avant toute ligne de code

Six lots, ordonnés par **risque décroissant** et par **indépendance**. Chaque lot est autonome, gate
vérifiée avant de passer au suivant, et suivi d'une relecture de mes propres correctifs (le dépôt a
produit 8 défauts en 2 passes sur les correctifs de la v0.5.4 : je ne prendrai pas le raccourci).

### Lot A — Débloquer le gate (préalable technique, aucun changement de comportement)

Sans ça, aucun lot suivant ne peut être « vert », et je refuse la baseline.

1. `config/detekt/detekt.yml` : autoriser le nom de package existant sur `PackageNaming`
   (`packagePattern`) et neutraliser la règle ktlint `PackageName` redondante. **-320 issues**, aucun
   fichier de code touché, `applicationId` intact.
2. Aligner sur la convention du portefeuille (SMS Tech) : mise en forme **non bloquante**, règles
   sémantiques **bloquantes**. **-160 issues**.
3. Supprimer les **10 imports réellement inutilisés** (F15). **-20 issues**.
4. Traiter au cas par cas les ~20 signaux sémantiques restants, ou les justifier explicitement.

> **Ce que ça risque de casser** : rien de fonctionnel. Le risque est de desserrer une règle utile —
> d'où le choix de ne desserrer que le **nommage de package** et la **mise en forme**, jamais une
> règle de comportement.

### Lot B — F1, le CRITIQUE (isolé, rien d'autre dans ce lot)

Ne détruire que dans le seul cas réellement irrécupérable :
- `Failure.KeystoreInvalidated` → la base est cryptographiquement inaccessible : réinitialisation,
  comme aujourd'hui.
- `Failure.Io` / `Failure.WrapCorrupted` / **toute exception non mappée** → **ne rien effacer**.
  Réessayer une fois, puis échouer en laissant les données en place.
- Enveloppe `KeystoreManager.getOrCreateKey` pour que `KeyStoreException`,
  `UnrecoverableKeyException` et `ProviderException` deviennent des `Failure` typées au lieu de
  remonter nues.
- La hiérarchie typée cesse d'être du code mort : elle décide.

> **Ce que ça risque de casser** — le point délicat, et Gemini l'a signalé aussi : si la clé est
> *réellement* perdue et qu'on n'efface plus, l'app peut boucler sur un échec de démarrage. C'est
> pour ça que `KeystoreInvalidated` garde le comportement destructif : c'est le seul cas où
> l'effacement est la bonne réponse. Pour les autres, un démarrage qui refuse d'ouvrir est
> **récupérable** ; un agenda effacé ne l'est pas. Le repli doit fermer.
> **Ce lot n'est pas vérifiable en JVM** — il exige le lot F (tests instrumentés) ou une validation
> manuelle sur le S9. Je préconise de faire **F avant B**, ou au minimum d'écrire le test
> instrumenté de B dans le même lot.

### Lot C — F2, les deux receivers (petit, mécanique, à faible risque)

`Provider<ReminderScheduler>` / `Provider<ReminderActionHandler>`, `.get()` **dans** la coroutine,
après `goAsync()`. Exactement le patron déjà appliqué à `MainActivity` et `MainApplication` — donc
zéro invention. Et le KDoc de `ReminderReceiver` redevient vrai.

> **Risque** : faible. Le travail total après `goAsync()` doit rester dans le budget de broadcast —
> ce qui est justement l'objet du lot D.

### Lot D — F5 puis F7 (les chemins qui perdent des rappels en silence)

- F5 : donner un `budget: ExpansionBudget?` à `nextOccurrenceStart` et `lastOccurrenceStartBefore`,
  un budget partagé par passe de recherche et un par passe de replanification. Corriger le KDoc de
  `SearchViewModel`, qui affirme aujourd'hui l'inverse de la réalité.
- F7 : sur échec d'énumération des reminders avant le wipe, **échouer la restauration** au lieu de
  continuer avec une liste vide. Fail-closed, comme le reste du chemin de restauration, qui refuse
  déjà le fichier **en bloc** plutôt que d'en perdre la moitié.

> **Risque F5** : un budget épuisé laisserait des résultats de recherche non datés. Je préfère un
> plafond **beaucoup plus haut** pour la recherche que pour le rendu, plus une troncature explicite
> et journalisée, à un gel silencieux.
> **Risque F7** : bloquer une restauration qui « marchait » avant en laissant des alarmes orphelines.
> Arbitrage assumé, cohérent avec la doctrine du fichier refusé en bloc.

### Lot E — F3, F4, F8 : durcir l'unique entrée de données externes

- **F3** : normaliser `timeZoneId` **à l'ingestion**, exactement comme `MAX_INTERVAL` a été porté
  dans `RecurrenceRule.init` — l'import `.ics` stocke le fuseau **réellement utilisé** pour calculer
  l'instant, et la restauration `.atbak` valide le fuseau dans `validate()`. Les trois symptômes
  (F3a, F3b, F3c) tombent avec la cause. **Et** échapper `TZID` à l'export, ceinture et bretelles.
- **F4** : borner la lecture elle-même dans `IcsViewModel`, en réutilisant la technique de
  `BackupViewModel.readFile`. Les trois déclencheurs tombent, y compris le refus injustifié (c).
- **F8** : accumuler les propriétés multi-valuées (`EXDATE` au minimum) au lieu de les écraser.
- **F14** : plier sur les points de code, pas sur les `char`.
- Tests : les premiers cas `.ics` du dépôt construits à partir de **fichiers réels** (TZID Windows,
  `EXDATE` multiples), et non de notre propre export. C'est ce qui manque structurellement (F3d).

> **Risque F3** : les fichiers déjà importés avec un fuseau invalide sont en base. Faut-il les
> migrer ? **Question ouverte pour toi** — je penche pour une migration Room additive qui remplace
> un `time_zone` non résolvable par le fuseau de l'appareil, parce que laisser la base dans deux
> états rendrait le comportement dépendant de la date d'import.
> **Risque F8** : la structure de parsing passe à `Map<String, List<IcsProperty>>` — c'est le seul
> changement structurel du plan, et il touche `parseVEvent`/`parseRRule`.

### Lot F — Filet de sécurité : tests instrumentés + CI

- **F10** : premiers tests instrumentés sur le S9 (**API 29**, ciblé par série, jamais
  `connectedAndroidTest`) : ouverture SQLCipher avec la vraie clé, non-régression de la clé nulle,
  `MigrationTestHelper`, et **le lot B** (échec transitoire → données préservées).
- **F11** : workflow GitHub Actions — build, tests, lint, detekt, **et un job qui régénère le
  manifeste fusionné release et échoue si `INTERNET` ou `ACCESS_NETWORK_STATE` y apparaît**. La
  promesse publique devient une assertion de build. Déclencheurs incluant `fix/**` et `feat/**`
  (trou §9.4 du `CLAUDE.md`).
- **F12** : le receiver `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` — défense sans regret.
- **C0-1** (lot 0) : documenter les 4 permissions manquantes dans `PRIVACY.md` / `PRIVACY.fr.md`.
- **F9** : écrire la décision dans `SECURITY.md`, quelle qu'elle soit.
- **F13** : rendre le re-verrouillage à `onStop` synchrone.

---

## Trois décisions qui t'appartiennent

1. **F9** — la notification de rappel doit-elle masquer le titre quand le verrou d'app est actif ?
   (a) laisser et documenter · (b) masquer · (c) rendre réglable. Je recommande **(a)** pour ce
   cycle : un rappel muet dégrade la fonction principale du produit, et l'écran de verrouillage de
   l'appareil est déjà couvert.
2. **F3, migration** — faut-il réparer en base les fuseaux invalides déjà importés, ou seulement
   arrêter d'en créer ? Je recommande **de réparer**, par une migration additive.
3. **Ordre B / F** — le CRITIQUE (B) n'est pas vérifiable en JVM. Je recommande **F avant B**, contre
   l'intuition qui voudrait traiter le critique d'abord : corriger sans filet le code qui a déjà
   effacé des données une fois est le scénario le plus risqué du plan.

**Et une remarque de méthode** : la consigne « gate verte entre chaque lot » supposait un gate vert
au départ. Il ne l'est pas et ne l'a jamais été. Le lot A existe pour rendre la consigne applicable
— sans baseline, et sans toucher au code de production.

**Aucune ligne de code ne sera écrite avant ta validation de ce plan.**
