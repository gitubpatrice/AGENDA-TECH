# Audit et durcissement d'Agenda Tech — avec relectures externes GPT + Gemini Pro

> Prompt à coller au début d'un nouveau chat. Écrit le 2026-08-06. **Tous les chiffres ci-dessous
> ont été mesurés dans le dépôt**, pas rappelés de mémoire.

---

Tu vas auditer puis durcir **Agenda Tech**, un agenda Android natif, chiffré et **100 % local**.
Objectif : le rendre plus solide, plus fiable, plus sûr, plus rapide — et faire **valider chaque
constat par deux relecteurs externes** avant de me proposer un plan.

## Faits vérifiés sur le dépôt

| | |
|---|---|
| Chemin local | `j:\applications\agenda_tech` |
| GitHub | `gitubpatrice/AGENDA-TECH` — dépôt **public** |
| Stack | **Kotlin natif**, Jetpack Compose, Hilt, Room + **SQLCipher**, Glance (widgets), androidx.biometric |
| Modules Gradle | **un seul** : `:app` |
| État | `main` = `893b683`, dernier tag **`v0.5.4`**, arbre **propre**, rien à pousser |
| Version | `version.properties` → `versionCode=51`, `versionName=0.5.4` |
| Volumétrie | **132** fichiers Kotlin de production · **28** fichiers de test JVM (**241** `@Test`) · **1 seul** fichier `androidTest` |
| Qualité | detekt configuré (`config/detekt/detekt.yml`) + detekt-formatting |
| Rapports d'audit | `audit/ia-externe/` et `audit/ia-interne/` existent déjà — **les lire avant de recommencer** |

### ⚠️ Deux écarts à savoir d'emblée

**1. Agenda Tech n'a AUCUN workflow GitHub Actions.** `.github/workflows/` n'existe pas. Ni build,
ni test, ni analyse, ni release. C'est le seul projet du portefeuille dans ce cas, et cela signifie
que **rien ne vérifie jamais ce dépôt en dehors de ta machine**. À traiter comme un constat en soi.

**2. Agenda Tech n'est pas dans le tableau de mapping du `CLAUDE.md` global**, qui liste 8 apps
(SMS, Pass, Notes, AI, PDF, Read Files, Health, App Manager). C'est donc une **9ᵉ** application, et
l'entrée « Health Tech » du tableau désigne un **autre** projet — `j:\applications\health_tech`, en
Flutter. Ne pas confondre les deux. Si tu constates une divergence de nom sur `files-tech.com`, c'est
le point ouvert §9.1 du `CLAUDE.md` : **ne pas le trancher par déduction**, me le signaler.

### ⚠️ Le versionnement ne marche PAS comme sur SMS Tech

Ici `versionCode` est **figé à la main** dans `version.properties`, délibérément — le fichier
explique pourquoi : un `git rev-list --count` **baisse** après un rebase/squash et retombe à 1 hors
dépôt git. **Ne jamais « corriger » ça vers un compte de commits.** Un bump = éditer
`version.properties`, et seulement au moment de la release.

## La doctrine du produit, qui est aussi un critère bloquant

Le manifeste porte cette phrase :

> **Agenda 100 % LOCAL, chiffré, ZÉRO réseau.** Aucune permission `INTERNET` /
> `ACCESS_NETWORK_STATE` n'est déclarée — et ne doit jamais l'être. L'interopérabilité se fait par
> import/export de fichiers `.ics` (RFC 5545) via le sélecteur système, pas par synchronisation.

Permissions **réellement déclarées** (vérifié) : `READ_CALENDAR`, `POST_NOTIFICATIONS`, `VIBRATE`,
`SCHEDULE_EXACT_ALARM`, `USE_EXACT_ALARM`, `RECEIVE_BOOT_COMPLETED`.
Permission **retirée** par `tools:node="remove"` : `ACCESS_NETWORK_STATE` — tirée transitivement par
`androidx.work` (via Glance). Le commentaire du manifeste documente ce retrait.

🔴 **PIÈGE DE MÉTHODE, il m'a fait conclure faux deux fois aujourd'hui.** Un `grep` sur
`uses-permission` fait apparaître `ACCESS_NETWORK_STATE` comme **déclarée**, parce qu'elle figure
dans le fichier *pour être retirée*. **Le seul contrôle qui fait autorité est le manifeste
FUSIONNÉ** :

```
build/app/intermediates/merged_manifest/release/**/AndroidManifest.xml
```

Il est **absent en local** : il faut un `assembleRelease` pour le régénérer. **Fais-le, et relève la
liste réelle** — c'est le premier livrable de l'audit, parce que toute dépendance ajoutée peut
réintroduire une permission réseau et casser la promesse publique sans que rien n'échoue.

⚠️ `USE_EXACT_ALARM` est légitime pour un agenda (fonction principale = rappels datés) et
auto-accordée. **Ne pas la retirer** — mais vérifie qu'elle est justifiée et documentée, et que le
repli quand les alarmes exactes sont refusées est correct.

## 🎯 Auditer PAR MOTIF, jamais linéairement

Un balayage générique de ce portefeuille rend surtout des faux positifs. **Tous** les vrais défauts
trouvés ces derniers mois relèvent de quatre motifs. Cherche-les nommément, et dis explicitement
quand tu n'en trouves pas.

**1. La garde est posée sur l'AFFICHAGE, pas sur l'ACCÈS.**
Un écran masqué alors que la donnée reste atteignable par un autre chemin. Pour chaque lecture
d'événement, demande : *« le déverrouillage est-il vérifié ICI, ou seulement à l'écran d'avant ? »*
Précédent réel : sur une app sœur, **le coffre s'ouvrait AVANT son second facteur**, et un test
passait sur un chemin qu'aucun appelant n'emprunte.

**2. Le repli échoue du mauvais côté.**
Pour chaque `catch`, `?: valeur`, `getOrDefault`, `runCatching`, timeout : *« si ça rate, est-ce que
ça verrouille ou est-ce que ça ouvre ? »* Sur une fonction de sécurité, un repli doit **fermer**.
⚠️ Cas particulier déjà rencontré : **un repli `stateIn` non hydraté rend les valeurs par DÉFAUT**,
donc « protection désactivée », en silence, sur un chemin réveillé par le système (worker, alarme,
récepteur de boot). `RECEIVE_BOOT_COMPLETED` est déclaré ici : **c'est exactement ce chemin.**

**3. Le jumeau asymétrique — et il a DEUX faces.**
Deux chemins censés faire la même chose, un seul corrigé. ⚠️ Vérifie **ce que le code fait** *et*
**ce que l'utilisateur voit** : un geste silencieux et un geste confirmé ne sont pas le même geste.
Candidats ici : rappel d'un événement simple vs récurrent · import `.ics` vs export `.ics` ·
verrouillage biométrique vs code de secours · widget Glance vs écran principal · notification
d'alarme vs affichage in-app.

**4. Le chemin mort, et le test vert qui le couvre.**
Chaîne de traduction orpheline, branche qu'aucun appelant n'emprunte, test qui passe sur un chemin
que le produit n'exécute pas. **241 tests JVM pour 132 fichiers de production, et UN SEUL fichier
instrumenté** : suppose la couverture **trompeuse** plutôt que faible, et vérifie que les tests
exercent les chemins que les appelants réels empruntent.
⚠️ Piège mesuré sur ce dépôt : `stateIn(WhileSubscribed)` + lecture de `.value` **sans collecteur**
rend un test **vacant** — vert, sur rien.

### En plus des quatre motifs, points durs propres à Agenda Tech

- 🔴 **Import `.ics` = surface d'attaque n°1.** C'est la seule entrée de données externes. Un `.ics`
  piégé a déjà **crashé l'app en boucle** sur ce projet. Cherche : récurrence pathologique
  (`RRULE` sans `UNTIL`/`COUNT`, `FREQ=SECONDLY`), dates hors bornes, fuseaux inconnus, champs
  géants, `VALARM` en cascade, fichier tronqué, encodage invalide, `DTEND` avant `DTSTART`. Un
  import doit **échouer proprement**, jamais tuer le processus ni empoisonner la base.
- 🔴 **Clé SQLCipher** : vérifier qu'aucun chemin ne peut ouvrir la base avec une **clé nulle**.
  Ce défaut a existé en production sur deux apps de ce portefeuille, dont celle-ci.
- **Alarmes et redémarrage** : après un reboot, les rappels sont-ils tous reprogrammés ? Que se
  passe-t-il si `canScheduleExactAlarms()` est faux ? Une alarme perdue en silence est le pire
  défaut d'un agenda.
- **Récurrences et heure d'été** : un événement à 2 h 30 la nuit du changement d'heure, un événement
  « tous les 31 » en février, un événement sur année bissextile.
- **Widgets Glance** : ils lisent la base depuis un autre processus. Le déverrouillage est-il
  respecté ? Le widget peut-il afficher un titre d'événement alors que l'app est verrouillée ?
- **Zéroïsation** : passphrase ou clé qui survit en mémoire après usage.
- **Effets qui s'annulent eux-mêmes** : un `LaunchedEffect` dont la clé change **par son propre
  effet** s'annule ; s'il porte un affichage, rien ne paraît. Règle : **afficher PUIS consommer**,
  et ne pas recopier dans un `remember` (perdu à la rotation).
- **Gardes échantillonnées** : une valeur lue une fois comme condition, sur un chemin qui ne repasse
  jamais. Demande toujours : *« si la condition devient vraie une seconde plus tard, qui rappelle ce
  code ? »*
- **Performance** : requêtes N+1 sur la vue mois, recompositions Compose inutiles, déchiffrement
  refait à chaque frame, expansion de récurrence recalculée à chaque scroll, coût du démarrage à
  froid.

## Les relectures externes — comment les mener

Utilise **`~/.claude/tools/audit-ia.py`**, qui parle aux deux fournisseurs :

```bash
cd /j/applications/agenda_tech
python ~/.claude/tools/audit-ia.py --provider gpt --model gpt-5.2 \
  --prompt prompts/PROMPT-<sujet>.md --out audit/ia-externe/rapport-gpt-<sujet>-<date>.md \
  <fichiers entiers, pas des extraits>

python ~/.claude/tools/audit-ia.py --provider gemini \
  --prompt prompts/PROMPT-<sujet>.md --out audit/ia-externe/rapport-gemini-<sujet>-<date>.md \
  <mêmes fichiers>
```

**Règles apprises, à respecter :**

- ⚠️ **`gpt-5.1-codex-max` est listé par l'API mais rend 404** (joignable seulement par l'API
  Responses) ⇒ passer **`--model gpt-5.2`**.
- ⚠️ **Gemini rend parfois `503`** (forte demande) ⇒ **boucler jusqu'à 3 tentatives**.
- ⚠️ **Un rapport vide ou absent est un ÉCHEC, jamais un « rien à signaler ».** Le script sort en
  erreur exprès : un jour un prompt promettant « le diff ci-dessous » a été envoyé **sans** le diff,
  et la relecture a conclu « aucun défaut ».
- ⚠️ **Ne jamais lire les clés d'API dans la conversation** — c'est le script qui les lit.
- **Envoyer les fichiers ENTIERS**, pas des extraits choisis par toi : un relecteur sans accès au
  dépôt ne voit que ce qu'on lui colle, et un vrai défaut s'est déjà trouvé **2 000 lignes plus
  loin**, chez un appelant non modifié.

**Ce qui fait la qualité d'une relecture externe** (mesuré : de 7/20 à 4/4 avec des directives
serrées) — ton prompt de relecture doit :

1. donner le **contexte indispensable pour juger** (invariants, ce qui est déjà garanti ailleurs) ;
2. **lister nommément les choix délibérés** avec la mention « ne pas signaler ceci » ;
3. **exiger un scénario d'échec concret** pour chaque constat, et dire qu'un constat sans scénario
   reproductible n'est pas recevable ;
4. **interdire** le style, le nommage, les renommages, le découpage de fichiers ;
5. dire que « **aucun défaut sur ce motif** » est une réponse attendue et utile ;
6. annoncer que la précision de chaque relecteur est mesurée au fil des lots, et qu'un faux positif
   coûte plus qu'un silence.

**Puis TU tries.** Ne me présente jamais un rapport brut : donne le tri
`CONFIRMÉ` / `PROBABLE` / `FAUX POSITIF`, avec la raison. ⚠️ **Un constat externe se vérifie avant
d'être appliqué** — deux audits ont déjà recommandé de « révoquer le keystore », ce qui aurait cassé
la chaîne de mise à jour de toutes les installations. Et **le correctif proposé peut être moins bon
que le tien** : dis-le et justifie quand tu prends une autre voie.

## Non négociable — règles de travail

1. **NE JAMAIS toucher au keystore ni à la signature.** `keystore.properties` et `local.properties`
   sont présents en local : ne pas les lire, ne pas les commiter, ne pas les modifier. Un constat
   qui demande de changer la clé de signature est **faux** — cela rompt la mise à jour des
   installations existantes.
2. **NE JAMAIS régénérer une baseline detekt** pour faire passer le contrôle. Corriger la cause.
3. **Git** : stager **explicitement, fichier par fichier** — jamais `git add -A` (incident réel :
   du travail écrasé). Messages via **`-F fichier`**, jamais `-m` (les accents dans `-m` effacent
   des fragments).
4. **Un audit ne doit JAMAIS effacer le travail existant.** Ajouter plutôt que modifier. Ne pas
   toucher au code existant sans raison forte. **Jamais `git checkout --`** sur un fichier portant
   du travail non commité.
5. **Améliorations UNIQUEMENT si vraiment utiles** : pas de renommage, pas de reformatage, aucun
   changement cosmétique non demandé.
6. **Toujours relancer une revue SUR tes propres correctifs.** Sur ce dépôt précisément :
   **8 défauts trouvés en 2 passes sur mes propres corrections** de la v0.5.4, et la revue externe
   de la v0.5.3 a rattrapé **3 défauts que 226 tests ne voyaient pas**. Sur une app sœur, mes
   correctifs ont introduit un nouveau défaut **9 fois d'affilée**.
7. **Vérifier avant d'affirmer.** Les commentaires du code mentent parfois. Un `grep` tronqué, un
   `head -20`, une sortie vide prise pour preuve : chacun a produit une conclusion fausse ici —
   y compris **deux fois aujourd'hui sur des permissions**, à cause de `tools:node="remove"`.
8. **Contrôle vert entre chaque lot** :
   `./gradlew :app:assembleDebug testDebugUnitTest :app:lintDebug detekt`

## Appareils — l'un est un vrai téléphone

- **S24 FE `RZCY41EGKYL`** = **le téléphone RÉEL de Patrice**. **Annoncer avant toute
  installation.** `adb install -r` uniquement.
- **Galaxy S9 `22dbb7390a057ece`** = appareil de test. Tests instrumentés autorisés ici, ciblés par
  série : `adb -s 22dbb7390a057ece shell am instrument -w -r <pkg>.test/<runner>`.
- 🔴 **NE JAMAIS lancer `connectedAndroidTest` / `connectedDebugAndroidTest`** : la tâche vise
  **tous** les appareils connectés et **efface les données** de l'application, S24 FE compris.
- Si `adb devices` est vide : c'est **Samsung Auto Blocker**, pas adb. Ne pas faire
  `adb kill-server`.

## Ce que je veux, dans cet ordre

1. **Lire `audit/ia-externe/` et `audit/ia-interne/`** — dis-moi ce qui y a déjà été traité, pour ne
   pas refaire le même travail ni resignaler des faux positifs connus.
2. **Régénérer et relever le manifeste FUSIONNÉ release** : la liste réelle des permissions, et si
   la promesse « zéro réseau » tient.
3. **Auditer par les quatre motifs + les points durs**, et produire des constats priorisés
   `CRITIQUE` / `MAJEUR` / `MINEUR`. Pour **chacun** :
   - `fichier:ligne`
   - le **motif** (ou « autre », justifié)
   - un **scénario d'échec concret** — sans lui, le constat n'est pas recevable
   - le statut **CONFIRMÉ** (vérifié dans le code) ou **PROBABLE** (à instruire)
4. **Faire relire par GPT-5.2 ET Gemini 3.1 Pro**, avec des prompts respectant les 6 règles
   ci-dessus, puis me présenter **ton tri**, pas les rapports.
5. **Me proposer un plan de correction à valider AVANT d'écrire du code.**
6. Après validation : corriger par lots, gate verte entre chaque, puis **relancer une relecture sur
   tes propres correctifs**.
7. Me dire aussi **ce que tu as cherché et NON trouvé**, motif par motif.

**Style attendu** : court, dense, factuel. Pas de complaisance. Cite `fichier:ligne`. Distingue
toujours confirmé / probable / à vérifier. Si tu ne sais pas, dis-le. Livre le lot, puis rends la
main.

Commence par les points 1 et 2.
