# Audit de sécurité — Agenda Tech, delta `feat/maps-share-undo-holidays` (2026-10-13)

## Résumé exécutif

**Aucune faille critique, élevée ni moyenne.** Trois constats **faibles** confirmés, tous propres aux
nouveautés de la branche (partage `.ics`, lieu dans la vue Mois), un réfuté. Les trois se corrigent en
moins d'une heure chacun et doivent l'être **avant la release** : deux d'entre eux contredisent une
phrase publique de `PRIVACY` ou `SECURITY`.

1. Le `.ics` partagé survit à la suppression de l'événement (et la doc promet le contraire).
2. L'adresse `content://` d'un partage est réutilisée à titre égal, et le droit de lecture jamais retiré.
3. La vue Mois copie le lieu (et l'encode en lien de carte) pour chaque occurrence : un `.ics` piégé
   fait planter l'app à chaque lancement.

## Révision auditée

| | |
|---|---|
| Commit | `1327be0f62205a1c09f21e145811a789d4ea882a` |
| Branche | `feat/maps-share-undo-holidays` — périmètre : `git diff main...HEAD` (88 fichiers) |
| Arbre | propre (seul non suivi : `rapport relecture f-droid/`, antérieur, écarté) |
| Palier | **standard** pour la recherche (3 chercheurs), **rapide** pour la vérification : 4 des 8 vérificateurs arrêtés en cours de route (erreur de l'orchestrateur, voir « Limites ») — 1 vérificateur par constat |
| Méthode | skill `audit-securite-mobile` : lecture pure, constats = chemin complet, panel chargé de réfuter |

## Modèle de menace

Agenda local. Promesses : aucune permission Internet ; base chiffrée SQLCipher (clé AndroidKeyStore,
sans authentification) ; `FLAG_SECURE` ; verrou PIN/biométrie optionnel (`LockedAppHost`) ; pas de
sauvegarde cloud. Nouvelles promesses de la branche : le `.ics` partagé est la seule copie en clair,
lisible par la seule appli choisie, effacée au partage suivant et au démarrage ; un événement supprimé ne
reste qu'en mémoire, le temps d'« Annuler ». Pas de mode leurre. Attaquants : une autre appli installée ;
l'appli destinataire d'un partage ; le fournisseur d'un `.ics` importé ou d'un agenda partagé synchronisé ;
une personne qui a le téléphone en main, app verrouillée.

Manifeste fusionné (debug, à jour) : aucune permission réseau ; nouveau `FileProvider`
`exported="false"`, `grantUriPermissions="true"`, limité à `cache-path shared/`. Le manifeste release
n'a pas été regénéré (lecture pure) ; la CI « Zero-network promise » est verte sur `d1a617c`.

## Couverture

| Élément | Traitement |
|---|---|
| `app/src/main` (delta) | **inspecté** — 3 composants × 3 angles (ci-dessous) |
| `app/src/test`, `androidTest` (delta) | lus par les chercheurs comme preuve, pas comme surface |
| `app/src/main/res` (delta : chaînes, `share_paths.xml`) | inspecté (`share_paths.xml`) ; chaînes écartées (texte) |
| `PRIVACY*`, `SECURITY*`, `TERMS*`, `README*`, `CHANGELOG` | inspectés comme **promesses publiques** |
| `fastlane/` | écarté : texte de fiche de store |
| `gradle/`, `*.kts`, `config/`, `tools/`, `.github/` | écartés : **inchangés** dans le delta (aucune dépendance ni règle de build modifiée) |
| Reste de l'app (hors delta) | écarté : audité en entier le 2026-07-31 (`CLAUDE-SECURITY-20260731-184516/`) et le 2026-09-11 |
| `build/`, `audits*/`, `prompts/`, `CLAUDE-SECURITY-*/`, `rapport relecture f-droid/` | écartés : généré ou rapports |

Cellules de recherche : **partage `.ics` / FileProvider × composants exposés et IPC** ; **données
sortantes (geo:, texte, .ics, nom de fichier) × entrée fournie par un tiers** ; **suppression et
annulation × ce qui reste après une purge**.

## Findings

| # | Sévérité | Statut | Item | Fichier:ligne | Action recommandée |
|---|---|---|---|---|---|
| 1 | 🟢 Basse | CONFIRMÉ | `.ics` en clair qui survit à la suppression de l'événement | `system/SharedFiles.kt:34` | Effacer le partage à la suppression ; corriger 2 phrases publiques |
| 2 | 🟢 Basse | CONFIRMÉ | Adresse de partage réutilisée, droit de lecture jamais retiré | `system/SharedFiles.kt:33-35` | Adresse neuve par partage ; `revokeUriPermission` à l'effacement |
| 3 | 🟢 Basse | CONFIRMÉ | Lieu copié et encodé pour chaque occurrence → plantage au lancement | `ui/screens/month/MonthViewModel.kt:313-315` | Calculer lieu, lien et description une fois par événement |

## Détail par finding

### Finding #1 — Le `.ics` partagé survit, en clair, à la suppression de l'événement
- **Sévérité** : 🟢 Basse — **Statut** : CONFIRMÉ (chercheur + vérificateur IMPACT ; relu)
- **CWE-459** (nettoyage incomplet).
- **Chemin** : « Partager .ics… » → `EventEditorViewModel.kt:279-280` → `SharedFiles.calendarFile`
  écrit `cache/shared/<titre>.ics` (`SharedFiles.kt:33-34`) avec l'événement complet (`EventShare.kt:59-60`) ;
  aucun des quatre chemins de suppression (`EventEditorViewModel.kt:442-465`, `:668-677`, `:679-705`,
  `:708-727`) n'appelle `SharedFiles` ; effacement seulement au partage suivant (`SharedFiles.kt:32`) ou
  au **démarrage du processus** (`MainApplication.kt:81-82`), pas à la réouverture de l'app.
- **Attaquant / gain** : accès au stockage privé (root, extraction après premier déverrouillage) avant
  le prochain démarrage du processus → le contenu d'un événement que l'utilisateur croit effacé. La base
  ne le contient plus ; l'attaquant y lit le reste de l'agenda (clé sans authentification, assumé
  `SECURITY.fr.md:41-43`).
- **Promesses en défaut** : `PRIVACY.fr.md:141-144` (« supprimer un événement efface les données
  correspondantes… jamais sur le stockage ») et `SECURITY.fr.md:307` / `SECURITY.md:285` (« chaque
  démarrage efface le dernier » — faux quand le processus est resté en cache).
- **Action** : à la suppression, effacer les fichiers partagés de l'événement (lier le dossier de
  partage à l'id de l'événement) ; corriger les phrases dans les 5 `PRIVACY` et les 2 `SECURITY`.
- **Ce que la correction fait perdre** : un envoi encore en attente d'un événement qu'on vient de
  supprimer échoue — acceptable.
- **Effort** : Rapide.

### Finding #2 — L'adresse d'un partage est réutilisée et son droit de lecture jamais retiré
- **Sévérité** : 🟢 Basse — **Statut** : CONFIRMÉ (chercheur + vérificateur ACCESSIBILITÉ ; relu)
- **CWE-732**.
- **Chemin** : le nom du fichier ne dépend que du titre (`EventShare.kt:67-74`, `event` si rien ne
  reste) → `SharedFiles.kt:33-35` rend toujours la même `content://…/shared/<titre>.ics` →
  `ShareSheet.kt:32-34,40` accorde la lecture sur cette adresse à l'appli X choisie ; aucun
  `revokeUriPermission` dans `app/src` ; `FileProvider.openFile` ne contrôle que l'adresse
  (androidx core 1.19.1) ; un droit obtenu peut être prolongé par X (`Context.grantUriPermission`,
  `Context.java:7400-7429`). Partage ultérieur d'un événement B de même titre vers Y → X lit B.
- **Attaquant / gain** : une appli choisie une fois comme destinataire, devenue malveillante → le
  contenu complet d'un partage ultérieur adressé à une autre appli, si collision de titre (titres
  génériques, même préfixe de 40 caractères, titres sans lettre ni chiffre) et avant le partage suivant
  ou le redémarrage.
- **Promesse en défaut** : `SECURITY.md` « read grant given to the picked app only, and temporary ».
- **Action** : chaque partage sous une adresse neuve (sous-dossier aléatoire, le nom affiché reste le
  titre) ; retirer le droit (`revokeUriPermission`) quand le fichier est effacé ; test : deux partages
  du même titre donnent deux adresses différentes.
- **Ce que la correction fait perdre** : rien.
- **Effort** : Rapide.

### Finding #3 — La vue Mois copie et encode le lieu pour chaque occurrence
- **Sévérité** : 🟢 Basse — **Statut** : CONFIRMÉ (chercheur + vérificateur DÉFENSES ; relu)
- **CWE-770**.
- **Chemin** : `LOCATION` d'un `.ics` importé, plafonné à 4 000 caractères seulement
  (`BidiSanitizer.kt:15,20`, `IcsCodec.kt:285`) ; séries sans fin relues à chaque fenêtre
  (`EventDao.kt:38-40`) ; `MonthViewModel.buildState` crée pour **chaque occurrence**
  `PlaceLabel.of` (copie) et `GeoLink.forEvent` (encodage URL ×3 à ×9) (`MonthViewModel.kt:313-315`),
  sur le thread principal (`MonthViewModel.kt:139-159`). Ordre de grandeur (calculé, non mesuré) :
  ~160 séries quotidiennes à lieu de 4 000 caractères saturent un tas de 256 Mo ; plafonds existants
  (5 Mo, 20 000 événements, budget d'expansion 1 M d'itérations) jamais atteints. Le Mois est l'écran de
  départ (`AppRoot.kt:41`) : plantage à chaque lancement, sortie = effacer les données.
- **Attaquant / gain** : le fournisseur d'un `.ics` que la victime importe elle-même → indisponibilité
  de l'agenda, perte des données locales sans sauvegarde.
- **Origine** : la copie par occurrence vient de la PR #38 (fusionnée, non publiée) ; la branche
  l'aggrave avec `mapUri`. La v1.2.0 publiée n'est pas touchée.
- **Action** : calculer lieu, lien de carte et description une fois par événement (les occurrences
  d'une série partagent leur `Event`), ou le lien au moment du toucher.
- **Effort** : Rapide.

## Candidats réfutés

- **Séparateurs C0 (VT, FF, FS, GS, RS) dans le `.ics` réémis** (CWE-144) — **FAUX POSITIF** (vérificateur
  IMPACT). `IcsLines.escapeText` (`IcsLines.kt:98-119`) ne les neutralise pas, c'est exact, mais une ligne
  iCalendar se termine par CRLF (RFC 5545 §3.1) : un lecteur conforme ne découpe pas dessus ; le seul
  lecteur cité (`str.splitlines` de Python, « historiquement ») n'a pu être confirmé ; l'attaquant fait
  déjà passer le texte qu'il veut dans SUMMARY/DESCRIPTION ; la victime voit le texte dans l'éditeur
  avant de partager. **Reste un défaut de conformité** (la grammaire TEXT exclut ces caractères), qui
  touche aussi l'export complet : durcissement recommandé, pas une faille.

## Ce qui a été cherché sans rien trouver

- **FileProvider** : aucune autre adresse lisible (pas de préfixe, `exported=false`, seul `cache/shared/`) ;
  droit en lecture seule, ni persistable ni d'écriture ; nom de fichier sans `/`, `.`, NUL, surrogates ni
  RTLO, ≤ 40 caractères ; `ACTION_SEND` toujours via sélecteur ; aucun filtre `SEND` entrant ; partage
  impossible app verrouillée.
- **Lien `geo:`** : schéma fixe, requête `URLEncoder`, coordonnées `BigDecimal` bornées (NaN/Infinity
  rejetés) : ni autre schéma, ni paramètre ajouté ; `ActivityNotFound` géré. Adresse et GPS ne viennent
  que de l'utilisateur ou d'un `.atbak`.
- **Injection CR/LF/U+2028/U+2029/U+0085** dans le `.ics` : neutralisée ; Bidi retiré aux quatre entrées.
- **Annulation** : barre absente sous le verrou (hôtes hors composition, texte générique) ; double
  restauration impossible (`compareAndSet`) ; aucun écrasement (`insertNew` ABORT, transaction unique) ;
  exclusion de série retirée seulement si cette suppression l'avait ajoutée, sur la série actuelle ;
  rien de l'événement supprimé n'atteint le stockage (`PendingDeletion` non sauvegardé) ; journaux Timber
  neutralisés en release et sans contenu ; alarmes annulées avant suppression, ré-armées après.
- **Promesse « aucune permission Internet »** : tenue (manifeste fusionné debug ; CI).

## Notes hors périmètre ou sans portée de sécurité

- **N1 (hors delta)** : une notification de rappel déjà affichée reste dans le volet après la suppression
  de l'événement (`ReminderNotifier.cancelReminder` jamais appelé à la suppression). `VISIBILITY_PRIVATE`.
- **N2 (hors delta)** : un rappel reporté (« Répéter ») d'une occurrence ensuite supprimée seule est
  reposté (`ReminderActionHandler.kt:33-34` ne vérifie pas l'exclusion).
- **Affichage** : une offre « Annuler » qui ne porte qu'une exclusion répond « rétabli » même si la série
  a disparu entre-temps (`DeletionRepositoryImpl.kt:40`).
- **Doc** : `PRIVACY.md` dit « the event's label » envoyé à l'appli de cartes ; c'est le titre quand il y a
  des coordonnées sans nom de lieu (`GeoLink.kt:54`) — à préciser.
- **À mesurer** : l'aperçu des Récents sous le sélecteur de partage (motif préexistant, confiance faible).
- Rappels de l'audit 3 axes du même jour : M1 (`.ics` effacé trop tôt au démarrage), M2 (cibles 24 dp en
  vue Semaine), L2 (phrase `SECURITY` sur la purge mémoire) — à traiter avec les findings 1 et 2.

## Plan d'action ordonné

1. **Avant release** : findings 1, 2, 3 (et M1/M2/L2 de l'audit 3 axes) ; corriger les phrases de
   `PRIVACY` ×5 et `SECURITY` ×2 ; tests de non-régression (deux partages → deux adresses ; suppression →
   fichier effacé ; vue Mois : une seule chaîne par série).
2. **Court terme** : durcissement C0 dans `escapeText` (et à l'import), avec un test par caractère.
3. **Backlog** : N1, N2, l'affichage « rétabli », la phrase sur le libellé envoyé aux cartes.

## Limites de cet audit

- **Vérification au palier rapide** : un seul vérificateur par constat. Quatre vérificateurs ont été
  arrêtés en cours par l'orchestrateur, sans demande de Patrice ; il a choisi ensuite de ne pas les
  relancer, aucun second avis ne pouvant changer les correctifs.
- Lecture pure : rien n'a été construit ni exécuté. Le finding 3 est un **calcul**, pas une mesure ; le
  cycle de vie serveur des droits d'URI (`UriGrantsManagerService`) n'est pas dans les sources locales.
- Manifeste release non regénéré ; pas d'analyse de l'APK ; pas de scan de dépendances ni de secrets.
- Audit non déterministe : une autre passe peut trouver autre chose.

## Garde-fous appliqués

- Lecture pure pendant l'audit ; seul ce rapport a été écrit dans le dépôt.
- Chaque finding cite un `fichier:ligne` relu par l'orchestrateur.
- Consommation : 3 chercheurs et 4 vérificateurs menés à terme (≈ 1,06 M jetons), plus 4 vérificateurs
  arrêtés en cours.
