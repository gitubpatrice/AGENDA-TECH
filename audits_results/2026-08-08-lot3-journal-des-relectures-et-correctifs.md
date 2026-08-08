# Agenda Tech — Lot 3 : journal des relectures sur mes propres correctifs

**Date** : 2026-08-08 · **Branche** : `fix/audit-2026-08-08` (4 commits sur `main` = `893b683` = `v0.5.4`)

> Règle de travail n°6 : relancer une revue **sur ses propres correctifs**. Justification mesurée sur ce
> dépôt : 8 défauts trouvés en 2 passes sur les correctifs de la v0.5.4, et sur une app sœur un correctif
> a introduit un nouveau défaut 9 fois d'affilée.

---

## Ce qui a été livré

| Commit | Lot | Contenu |
|---|---|---|
| `211adbc` | — | Rapports d'audit (lots 0, 1, 2) + prompts de relecture |
| `b378e66` | **A** | Gate detekt 520 → 0, sans baseline |
| `52a2075` | **F** | 5 tests instrumentés (premiers du projet) + CI + assertion « zéro réseau » |
| `de52745` | **B** | Le CRITIQUE F1 : un échec transitoire du Keystore n'efface plus l'agenda |

Gate à chaque lot : `assembleDebug` OK · **247** tests JVM (+6), 0 échec · lint 0 · **detekt 0**.
Appareil : **7** tests instrumentés verts sur Galaxy S9 (API 29), ciblés par série.

---

## Relecture externe des lots A et F

### Relecteurs joignables : 1 sur 2

**GPT-5.2** : rapport rendu ([rapport-gpt-lotAF](../audits/ia-externe/rapport-gpt-lotAF-2026-08-08.md)).

**Gemini Pro : INDISPONIBLE. C'est un échec consigné, pas un « rien à signaler ».**
20 tentatives, 3 modèles, charge utile réduite de moitié. Diagnostic exact, relevé sur des sondes à
charge minimale :

| Modèle | Réponse |
|---|---|
| `gemini-3.1-pro-preview` | **HTTP 503** — « currently experiencing high demand » |
| `gemini-3-pro-preview` | **HTTP 404** — « no longer available » |
| `gemini-2.5-pro` | **HTTP 404** — « no longer available to new users » |

Conséquence opérationnelle à retenir pour l'outillage d'audit : **`gemini-3.1-pro-preview` est le seul
Pro encore joignable, et il n'existe aucun repli.** Les lots A, F et B n'ont donc à ce stade qu'un seul
relecteur externe. À relancer.

### Tri des constats de GPT-5.2

| # | Constat | Verdict |
|---|---|---|
| **D1** | `Timber.w(t, …)` ajouté dans `RestoreBackupUseCase.decode` peut faire fuir du contenu d'agenda déchiffré dans logcat | **CONFIRMÉ — corrigé**, sévérité ramenée de MAJEUR à MINEUR |
| **C1** | `permissions: contents: read` empêcherait `upload-artifact` de fonctionner | **FAUX POSITIF — rejeté** |
| A1–A5, B1–B3, C2–C4, D-reste, E | « aucun défaut » sur les tests, le script de permissions, la CI, les accolades, les `@Suppress`, les seuils detekt | pas de constat |

#### D1 — CONFIRMÉ, et c'est un défaut que J'AI introduit au lot A

Le correctif `SwallowedException` du lot A journalisait le `Throwable` attrapé. GPT relève qu'à cet
endroit le plaintext est **déjà déchiffré** — c'est l'agenda réel de l'utilisateur — et que les erreurs
de `kotlinx.serialization` citent l'entrée fautive dans leur message.

**Vérifié avant d'accepter** : `NoOpReleaseTree.log` est un vrai no-op, donc **rien n'est journalisé en
release**. GPT annonçait MAJEUR et posait honnêtement la question en fin de rapport ; la réponse est
**MINEUR**. Mais le constat reste juste : un build debug est ce qui tourne sur un téléphone de test, et
le KDoc de `NoOpReleaseTree` pose lui-même la règle — « never log event titles, locations or notes ».

Corrigé dans `de52745` : seul le **nom de classe** de l'exception est journalisé, ce qui conserve
exactement ce pour quoi le log avait été ajouté (distinguer une erreur de parsing JSON d'un invariant
refusé dans `Event.init`) sans la charge utile.

#### C1 — FAUX POSITIF, rejeté avec sa raison

`actions/upload-artifact@v4` s'authentifie par le **token de runtime** (`ACTIONS_RUNTIME_TOKEN`), pas par
`GITHUB_TOKEN` : le bloc `permissions:` ne le concerne pas. Ajouter `actions: write` aurait élargi la
surface du token **sans nécessité** — une régression de sécurité contre un problème inexistant. GPT
l'avait honnêtement marqué HYPOTHÈSE et signalait lui-même que son correctif « élargit les droits ».

#### A1 — pas un défaut, mais une limite de preuve à garder en tête

GPT note justement que `realKeyIsActuallyUsed` prouve « la base est lisible avec la passphrase rendue
par `DatabaseKeyManager` », pas « cette passphrase est matériellement protégée par le Keystore ». Si
`DatabaseKeyManager` était un jour modifié pour stocker la clé en clair tout en rendant une clé stable,
le test resterait vert. Ce n'est pas un défaut actuel ; c'est noté comme frontière du filet.

---

## Vérifications que j'ai faites moi-même sur mon propre travail

Avant d'affirmer que la CI est correcte, deux points ont été mesurés plutôt que supposés.

**Le manifeste que la CI contrôle est-il bien celui qui décide des permissions de l'APK ?**
Comparaison à trois voies, sur l'APK signé réellement produit :

| Source | Permissions |
|---|---|
| `processReleaseMainManifest` (contrôlé par la CI) | 11 |
| `packaged_manifests` (étape ultérieure, par ABI) | 11 — **identiques** |
| APK signé, via `aapt2 dump badging` | 11 — **identiques** |

Aucune étape postérieure n'ajoute de permission. Le contrôle est autoritaire.

**Les chemins d'échec du script d'assertion sont-ils réels ?** Exercés contre des manifestes trafiqués :
`INTERNET` injectée → échec · permission hors liste en forme **multi-ligne** → échec (le cas qu'un
`grep` ratait) · manifeste absent → échec · manifeste sans aucune permission → échec, parce qu'un
résultat vide est un échec du contrôle et jamais un feu vert.

**Les tests instrumentés ont-ils des dents ?** Vérifiés par sabotage sur l'appareil, deux fois :
- clé nulle injectée dans `SupportOpenHelperFactory` + `MIGRATION_4_5` retirée → **5 échecs sur 5** ;
- ancien comportement fourre-tout de `DatabaseFactory` remis → `aTransientKeyFailureLeavesTheDatabaseIntactAndRecoverable`
  échoue (« expected instance of `DatabaseKeyManager$Failure` » : `build()` réussit parce qu'il vient
  d'effacer).

Sabotages retirés à chaque fois, `git diff` vide sur les fichiers de production concernés.

---

## Constat NOUVEAU, trouvé en préparant le lot D

### F16 — MAJEUR — Le planificateur de rappels ignore les overrides vivants : **troisième jumeau** de l'asymétrie M1

- **Fichier** : [ReminderScheduling.kt:45](../app/src/main/java/com/filestech/agenda_tech/domain/reminder/ReminderScheduling.kt#L45)
- **Motif** : **#3 — jumeau asymétrique**
- **Statut** : **CONFIRMÉ** (lu dans le code)

`computeNextFire` appelle `expander.nextOccurrenceStart(event, earliestOccurrenceStartUtcMillis)` — donc
avec le `extraExcludedStartsUtcMillis` par défaut, c'est-à-dire **vide**. Les deux autres lecteurs
passent bien les overrides vivants :

| Chemin | Overrides vivants passés à l'expandeur ? |
|---|---|
| Vues — `ObserveOccurrencesInRangeUseCase:54-59` | ✅ `extraExcluded` |
| Recherche — `SearchEventsUseCase:140,143` | ✅ `entry.excludedStarts` (corrigé par `ab05feb`) |
| **Rappels — `ReminderScheduling:45`** | ❌ **rien** |

C'est exactement le défaut M1 de `ab05feb` — « les vues calculent les exclusions DYNAMIQUEMENT depuis
les overrides vivants, la recherche faisait confiance aux EXDATE persistées sur le maître » — dont le
correctif a été appliqué à la recherche et **jamais au planificateur**.

**Scénario d'échec concret** : un agenda est restauré depuis un `.atbak` contenant un override (une
occurrence déplacée) dont le maître ne porte pas l'`EXDATE` correspondante. `validate()` vérifie bien que
les deux moitiés du modèle `RECURRENCE-ID` s'accordent, mais **n'exige pas** que le maître porte
l'exclusion. Résultat : les vues et la recherche sautent correctement l'occurrence déplacée, tandis que
le rappel du maître **sonne à l'heure d'origine** — et l'override, qui peut porter ses propres rappels
(`remindersByEventId`), sonne aussi à la nouvelle heure. Deux rappels, dont un à une heure où
l'événement n'existe plus, sans rien à l'écran pour l'expliquer.

À traiter avec F5 dans le lot D : les deux constats portent sur les mêmes deux fonctions de l'expandeur,
qui n'acceptent aujourd'hui ni budget ni exclusions.

---

## Reste du plan

| Lot | Contenu | État |
|---|---|---|
| **C** | F2 — `BootReceiver` / `ReminderReceiver` : base construite sur le thread principal | à faire |
| **D** | F5 (budget d'expansion sur 3 chemins) · **F16** (overrides vivants) · F7 (alarmes orphelines) | à faire |
| **E** | F3 (`timeZoneId` non validé + migration) · F4 (lecture `.ics` bornée) · F8 (EXDATE multiples) · F14 (pliage) | à faire |
| **G** | Docs : les 4 permissions dans `PRIVACY.md` · décision F9 dans `SECURITY.md` · F13 · F12 | à faire |
| — | Relance Gemini Pro sur les lots A, F et B | **en attente de disponibilité** |
