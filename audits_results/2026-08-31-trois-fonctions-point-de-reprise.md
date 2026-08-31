# Agenda Tech — trois fonctions ajoutées (2026-08-31)

> Écrit pour survivre à une compaction de conversation. Tout ce qui suit a été **mesuré** — build,
> tests, appareil, CI — pas rappelé de mémoire.

---

## 1. Où en est le dépôt

**Branche : `feat/sauvegarde-auto-duplication-anniversaires`**, partant de `main` = `152c8fd`.
Arbre propre, poussée, **CI verte sur les trois jobs**, **364 tests** unitaires (318 au départ).

| Commit | Contenu |
|---|---|
| `acdd6fc` | Duplication d'événement · correctif EXDATE · garde `isLoaded` |
| `63fcbd0` | Anniversaires : `EventKind`, migration Room **6→7**, âge dérivé |
| `9a25c7b` | Sauvegarde automatique hebdomadaire chiffrée |
| `1776dd0` | Retrait d'une string orpheline (`backup_auto_run_now_sub`) |

CI `33428794060` : Gate (build · tests · lint · detekt) ✅ · Tests instrumentés API 29 ✅ ·
Promesse « zéro réseau » ✅.

---

## 2. Les deux décisions qui restent

1. **Fusionner dans `main`** — aucun risque pour F-Droid : la recette construit depuis le **tag
   `v1.0.3` / commit `89e3435a`**, donc `main` peut bouger sans toucher à la MR `!42991`.
2. **Taguer `v1.1.0`** — **à retenir jusqu'à la fusion de `!42991`**. C'est le seul mécanisme par
   lequel ce travail peut retarder l'inclusion : un tag plus récent ferait demander un bump de la
   recette, et la **vérification de build reproductible repartirait de zéro** — or c'est le seul
   point encore ouvert de la revue du 26/08.

---

## 3. Ce qui a été fait pour ne pas perturber la build reproductible

`androidx.work:work-runtime-ktx` et `androidx.documentfile` sont déclarés **aux versions que Glance
1.1.1 résolvait déjà** — `2.7.1` et `1.0.0`, vérifié par
`./gradlew :app:dependencies --configuration releaseRuntimeClasspath`. **Aucun artefact résolu ne
change.** Ne pas les remonter avant la fusion de la MR.

Le Worker utilise `CoroutineWorker` + `EntryPointAccessors` — le même motif que `AgendaWidget` — et
**pas `hilt-work`** : aucun processeur d'annotations ajouté, pas de `HiltWorkerFactory`, pas
d'initializer manifeste modifié. La liste de permissions du manifeste fusionné est **inchangée**,
ce que le job « zéro réseau » de la CI confirme.

---

## 4. Deux bugs préexistants, corrigés en chemin

**FIAB-DUP-1 — les EXDATE disparaissaient au premier renommage d'une série.** L'éditeur
reconstruisait la `RecurrenceRule` sans elles (aucun champ du formulaire ne les porte). Renommer une
série ressuscitait donc toutes les occurrences supprimées ; et une occurrence *déplacée*
s'affichait **deux fois**, le maître la régénérant pendant que sa dérogation restait en base.
Présent depuis l'introduction des dérogations, non couvert par les tests.

**Garde `isLoaded` — supprimer une occurrence emportait la série.** `isEditing` vient de l'argument
de navigation, donc il est vrai dès la première frame, avant la lecture de la base chiffrée. La barre
offrait « Dupliquer » et « Supprimer » sur un formulaire encore vide, et dans cette fenêtre
`loadedRecurrence` étant nul, `isMasterOccurrence()` répondait « événement simple » à propos d'une
série : la suppression emportait **toute la série** sans jamais poser la question de portée.

Chaque correctif a son **contrôle négatif** : test vérifié en échec sans le correctif, puis rétabli.

---

## 5. Relectures externes (gpt-5.2, trois passes)

**Neuf constats retenus sur douze.** Les trois écartés, avec la raison :

- *29 février décalé au 28* — ne s'applique pas : `RecurrenceExpander.kt:245` **saute** l'année où le
  jour n'existe pas, il ne décale pas.
- *Dialogue de portée contournable* — inatteignable par l'UI (dialogue modal). Corrigé quand même,
  une ligne, parce que l'API du ViewModel le permettait.
- *Nom de test sur-promettant* — juste, corrigé.

Les six constats du lot 3 méritent d'être retenus, ils portaient tous sur la fiabilité :

1. L'écriture supprimait l'ancien fichier **avant** d'écrire le nouveau ⇒ un échec de la seconde
   moitié laissait l'utilisateur sans rien. Désormais écriture dans un `tmp-…` puis bascule.
2. Un fichier tronqué pouvait rester et passer pour une sauvegarde.
3. La rotation n'exigeait qu'un préfixe : **`agenda-tech-auto-notes.atbak`**, fichier de
   l'utilisateur, correspondait et aurait été supprimé. La forme de date est désormais exigée,
   ancrée aux deux bouts.
4. `record()` pouvait lever et faire mentir le contrat « ne lève jamais ».
5. Worker et « Sauvegarder maintenant » pouvaient tourner en même temps sur le même nom : `Mutex`.
6. Couper l'interrupteur pendant l'exécution n'empêchait pas l'écriture : recontrôle juste avant.

---

## 6. Ce qui a été vérifié sur l'appareil (Galaxy S9, Android 10 / API 29)

- Duplication : deux événements coexistent après enregistrement.
- **Migration 6→7 sur une base v6 peuplée** : données intactes.
- Anniversaire créé au 12 mars 1984, lu à l'écran : **« Paul · 42 ans »**.
- Sauvegarde automatique : parcours complet d'activation (dossier SAF + mot de passe), écriture
  réelle de `agenda-tech-auto-2026-08-31.atbak`.
- **Rotation avec deux pièges** dans le dossier — un export manuel `agenda-tech-2026-08-20.atbak` et
  `agenda-tech-auto-notes.atbak` — **tous deux intacts** après plusieurs exécutions.
- **Le Worker exercé en processus froid** (`am kill` puis `cmd jobscheduler run -f`) :
  `WM-WorkerWrapper: Worker result SUCCESS`, fichier écrit, graphe Hilt et base chiffrée ouverts
  sans interface. Le chemin hebdomadaire n'est donc pas un chemin mort.

⚠️ **Piège de test constaté** : supprimer des fichiers avec `adb shell rm` derrière le
DocumentsProvider laisse des entrées fantômes dans son listing, ce qui a fait supprimer un fichier
de trop lors d'un essai. Ce n'était pas un défaut de la rotation — confirmé par un second passage
sur un état propre (0 supprimé). Un `distinctBy { it.name }` a été ajouté par précaution.

---

## 7. Limite Android documentée, non corrigeable

« Forcer l'arrêt » — ou une veille agressive constructeur — **suspend la planification** jusqu'à la
prochaine ouverture de l'application. Constaté : après `am force-stop`, le job disparaît de
`dumpsys jobscheduler`. `MainApplication` réarme au démarrage (`ExistingPeriodicWorkPolicy.KEEP`,
donc sans repousser la prochaine exécution), mais encore faut-il que l'app soit ouverte. L'app ne
peut pas le détecter de l'intérieur : elle ne tourne pas. Consigné dans `SECURITY.md`.

---

## 8. Documentation mise à jour, et pourquoi c'était obligatoire

`SECURITY.md`, `PRIVACY.md`, `PRIVACY.fr.md` affirmaient tous trois **« le mot de passe n'est stocké
nulle part »** — vrai de l'export manuel, **faux** de la sauvegarde automatique, qui doit conserver
le sien. L'encadré de l'écran Sauvegarde le disait aussi, dans les deux fichiers `strings`.

C'est exactement le motif que la revue F-Droid du 26/08 avait déjà relevé (commit `b5d983a`, « trois
affirmations fausses »). **Avant toute fonction touchant au stockage, aux permissions ou au réseau,
relire ces quatre surfaces.**

Parité i18n vérifiée après coup : **305 strings + 1 plurals des deux côtés**, aucune clé manquante
dans un sens ni dans l'autre, une orpheline trouvée et supprimée.
