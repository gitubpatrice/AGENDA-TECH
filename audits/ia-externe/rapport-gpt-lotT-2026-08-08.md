## 1) Budgets — arithmétique (pas de confiance aux commentaires)

### A. Le total est-il réellement borné ? (et dépassement possible)
**Constat 1 — dépassement du plafond de passe via le plancher `maxOf(1, …)`**  
- **Lieu**: `ReminderScheduler.kt:~120-145` (`rescheduleAll`, ligne `ExpansionBudget(maxOf(1, minOf(...)))` + `pass.charge(allowance.spent)`)  
- **Scénario chiffré** (pire cas réaliste côté CPU):
  - `pass.max = 1_000_000`.
  - 100 rappels récurrents “lourds” consomment chacun `10_000` itérations (cap), donc `100 * 10_000 = 1_000_000` → `pass.remaining = 0`.
  - Il reste ensuite **R** rappels récurrents dans la boucle.
  - Comme `pass.remaining = 0`, chaque rappel reçoit **quand même** un `allowance` de **1** (plancher), et `RecurrenceExpander` va typiquement consommer **1** itération avant de s’arrêter → `pass.charge(1)` répété.
  - **Coût total** = `1_000_000 + R` itérations (pas “strictement 1_000_000”).
- **Bornage exact**:  
  - Avant épuisement: la dépense totale est ≤ `pass.max` (car chaque sous-budget a `maxIterations ≤ pass.remaining` au moment de sa création).  
  - Après épuisement: + **1 itération par rappel récurrent encore traité** (ceux pour lesquels `computeNextFire` consulte réellement le budget).
- **Sévérité**: **MOYENNE** (le plafond n’est plus un plafond strict ; en présence d’un N massif, ça peut re-déborder le budget “broadcast en secondes”).  
- **Statut**: **CONFIRMÉ**.

> Remarque: ce dépassement est *délibérément* introduit pour ne jamais construire `ExpansionBudget(0)`. Son coût réel n’est pas “1”, c’est “1 × (nombre de rappels récurrents parcourus après épuisement)”.

---

### B. Existe-t-il une population où ce schéma arme moins que l’allocation partagée “avant F5-ter” ?
Oui : dès qu’un rappel **a besoin de > `PER_REMINDER_ITERATIONS`** pour trouver la prochaine occurrence.

**Constat 2 — régression fonctionnelle pour séries longues (> 27 ans quotidiennes)**  
- **Lieu**: `ReminderScheduler.kt:~120-145` (`perReminderIterations = 10_000`) + `RecurrenceExpander.kt:~95-140` (`occurrenceStarts` consomme 1 unité par occurrence scannée depuis `event.startUtcMillis`)  
- **Scénario chiffré (homogène)**:
  - 1 000 rappels sur séries **quotidiennes** dont le début est il y a **30 ans**.
  - Besoin par rappel ≈ `30 * 365 = 10_950` itérations (ordre de grandeur, hors années bissextiles).
  - **Avant (budget partagé sans cap par rappel)**: avec `1_000_000` itérations, on pouvait en armer `floor(1_000_000 / 10_950) = 91` (les 91 premiers).  
  - **Maintenant (cap 10_000)**: chaque rappel est limité à `10_000`, donc **aucun** ne peut atteindre `afterUtcMillis` → **0 armés** (tous échouent par cap).
- **Scénario chiffré (mixte)**:
  - 999 rappels “normaux” (3 ans quotidiens, besoin ~1095) + 1 rappel “long” (30 ans quotidiens, besoin ~10_950).
  - **Avant**: les 1000 peuvent être armés (coût total ≈ `999*1095 + 10_950 ≈ 1_105_?` → en fait ça dépasse 1M, donc ~913 des “normaux” + le long selon ordre; mais **le long** peut être armé si placé tôt et budget suffisant).
  - **Maintenant**: le “long” est **impossible** à armer (cap 10k), même si le pass a encore du budget.
- **Sévérité**: **ÉLEVÉE** (après reboot: ces rappels ne sonnent plus “jusqu’à édition”, ce qui est pratiquement “perdu” pour l’utilisateur).  
- **Statut**: **CONFIRMÉ**.

---

### C. Le plafond par rappel (10 000) est-il “au bon endroit” ?
- **Lieu**: `ReminderScheduler.kt:~215-240` (`PER_REMINDER_ITERATIONS = 10_000`) + `RecurrenceExpander.kt:~105` (scan depuis `event.startUtcMillis`)  
- **Calcul**: 10 000 itérations ≈ 27,4 ans **en quotidien**.  
- **Risque agenda réel**:
  - Pour des habitudes/traitements “quotidiens depuis longtemps” importés (ou un agenda tenu depuis la fin des années 90), **27 ans n’est pas inconcevable**.
  - Pour du hebdo / mensuel / annuel, 10k est gigantesque, donc la vraie zone de risque est surtout le **quotidien** (et éventuellement des règles quotidiennes “importées très anciennes”).
- **Sévérité**: **MOYENNE à ÉLEVÉE** (selon votre public/imports; c’est directement un risque de “rappels qui meurent au reboot”).  
- **Statut**: **CONFIRMÉ** (sur le mécanisme), **PROBABLE** (sur la fréquence en prod).

---

### D. `pass.charge(allowance.spent)` charge-t-il bien ce qui a été consommé ?
**Constat 3 — l’accounting “spent” est cohérent, mais attention à une hypothèse externe**  
- **Lieu**: `ExpansionBudget.kt:~10-55` (`spent == used`, incrémenté seulement par `tryConsume` / `charge`) + `ReminderScheduler.kt:~140` (`pass.charge(allowance.spent)`) + `RecurrenceExpander.kt:~120` (`budget.tryConsume()` à chaque scan)  
- **Ce qui est correct (arithmétique)**:
  - Si le sous-budget n’est pas épuisé (occurrence trouvée tôt), `spent < maxIterations` et on ne charge que le réellement consommé.
  - Pas de double-charge dans ce code: 1 `charge` par rappel, et `spent` est monotone.
- **Point à vérifier (hypothèse)**:
  - Tout ceci suppose que `ReminderScheduling.computeNextFire(...)` **passe bien `budget`** à `RecurrenceExpander.nextOccurrenceStart(...)` (sinon `allowance.spent` resterait 0 et `pass` ne bornerait rien). Je ne peux pas le confirmer sans le fichier `ReminderScheduling`.
- **Sévérité**:  
  - Sur l’accounting montré ici: **RAS**.  
  - Sur l’hypothèse “budget effectivement consommé”: **ÉLEVÉE si faux**, mais **PROBABLE** seulement (car le code appelle explicitement `budget?.isExhausted` en aval, ce qui serait inutile si jamais consommé).
- **Statut**: **CONFIRMÉ** (accounting interne), **PROBABLE** (propagation correcte via `ReminderScheduling`).

---

## 2) Ce qui reste non armé, et si on le sait

### A. Arbitrage actuel
- **Lieu**: `ReminderScheduler.kt:~155-205` (`schedule`, branche `fire == null` + `if (budget?.isExhausted == true) return`)  
- **Comportement**:
  - Sur reboot, si budget épuisé (cap rappel ou pass épuisé → allowance=1), le rappel récurrent **n’est pas armé**.
  - Il n’existe pas d’alarme “à laisser en place” après reboot (AlarmManager exact ne survit pas), donc ce “ne pas annuler” ne préserve rien: c’est un **trou** jusqu’à prochaine sauvegarde/édition.
- **Sévérité**: **ÉLEVÉE** (fonctionnel: “ne sonnera plus”).  
- **Statut**: **CONFIRMÉ**.

### B. Témoin: seulement `Timber.w`, supprimé en release
**Constat 4 — absence totale de signal utilisateur en release pour rappels perdus**  
- **Lieu**: `ReminderScheduler.kt:~175-195` (`Timber.w(...)` quand budget épuisé) + description `NoOpReleaseTree`  
- **Scénario**: reboot → budget épuisé sur certains rappels → **aucun log en release**, aucun UI → l’utilisateur ne saura jamais pourquoi “ça ne sonne plus”.  
- **Sévérité**: **CRITIQUE** (c’est exactement la classe de défaut “silencieux” qui détruit la confiance, et ici ça peut être permanent).  
- **Statut**: **CONFIRMÉ**.

**Repli “sans nouvelle passe” (minimum viable)**:
- À la fin de `rescheduleAll`, **compter** combien de rappels récurrents n’ont pas été armés pour cause de budget (cap ou pass épuisé) et **persister un drapeau** (settings/DB).  
- Au prochain lancement UI: bannière/écran “Certains rappels récurrents n’ont pas pu être réarmés après redémarrage, ouvrez/éditez les événements concernés”.  
Ça ne coûte pas une seconde passe, juste un reporting fiable.

### C. Garantie “non-récurrents armés quoi qu’il arrive”
- **Lieu**: `RecurrenceExpander.kt:~60-85` (`nextOccurrenceStart`: si `event.recurrence == null`, retourne avant de consulter `budget`)  
- **Conclusion**: oui, **dans `RecurrenceExpander`**, un événement non récurrent ne consomme pas de budget et ne peut pas être bloqué par épuisement.  
- **Limite**: la garantie dépend encore de `ReminderScheduling.computeNextFire` (non fourni). Si ce code appelle bien `expander.nextOccurrenceStart` pour les non-récurrents, alors la promesse tient.
- **Sévérité**: **INFO**  
- **Statut**: **CONFIRMÉ** côté `RecurrenceExpander`, **PROBABLE** bout-en-bout.

---

## 3) Autres correctifs du lot

### A. `BackupEnvelope` (UnsupportedVersion sur `>` au lieu de `!=`)
**Constat 5 — verdicts exhaustifs/mutuellement exclusifs : OK ; wipe du mot de passe : OK**  
- **Lieu**: `BackupEnvelope.kt:~55-115` (`open`) + `parseHeader`: `~125-185` + `deriveKey`: `~90-120`  
- **Verdicts**:
  - `NotABackup`: magic absente/fausse.
  - `Malformed`: format ours mais incohérent (tailles/ranges, version/kdf < connu, etc.).
  - `UnsupportedVersion`: version/kdf **> connu**.
  - `Openable`: header cohérent.
  → ces branches sont **exhaustives** et **exclusives** dans ce parseur (1 seul retour).
- **Fichier hostile peut-il choisir son verdict ?**
  - Il peut toujours forger `UnsupportedVersion` en mettant un octet de version > 1 avec le bon magic. C’est inévitable à ce stade (avant auth) si on veut distinguer “trop récent” de “pas à nous”. Je ne vois pas de chemin pour obtenir `Openable` sans satisfaire toutes les contraintes de header.
- **Wipe password**:
  - Refus précoce: `password.wipe()` explicitement.
  - Sinon: `deriveKey` wipe toujours en `finally` (et wipe aussi le clone interne via `spec.clearPassword()`).
- **Sévérité**: **RAS**  
- **Statut**: **CONFIRMÉ** (“rien à signaler”).

### B. `SettingsViewModel.refreshWidget` (passage à `appScope`)
**Constat 6 — pas de fuite de contexte ; concurrence possible**  
- **Lieu**: `SettingsViewModel.kt:~150-195` (`refreshWidget`)  
- **Contexte**: `@ApplicationContext` → pas de fuite d’Activity. **OK**.  
- **Coroutine survivante**: oui, et c’est le but (ne pas être annulée quand on quitte l’écran).  
- **Deux refresh concurrents**: **oui possible** (aucun job de dédoublonnage / coalescing). Effet probable: travail redondant `updateAll`.  
- **Sévérité**: **FAIBLE**  
- **Statut**: **CONFIRMÉ**.

### C. `BackupViewModel.onExportTargetPicked` — wipe sur les 3 branches
**Constat 7 — wipe non garanti sur la branche “export” (dépend du UseCase)**  
- **Lieu**: `BackupViewModel.kt:~70-110` (`onExportTargetPicked`) + `export(...)`  
- **Branches**:
  1) `uri == null` → `password?.wipe()` : **OK**.  
  2) `password == null` → rien à wiper : **OK**.  
  3) `else -> export(uri, password)` → **ici le ViewModel ne wipe pas**.
- **Risque concret**:
  - Si `ExportBackupUseCase` refuse sur validation (ex: longueur < min) **avant** d’appeler `BackupEnvelope.seal` (qui wipe), alors le `CharArray` peut rester en mémoire.
- **Sévérité**: **MOYENNE** (secret en mémoire; et vous avez explicitement une discipline de wipe partout ailleurs)  
- **Statut**: **PROBABLE** (je ne peux pas confirmer sans `ExportBackupUseCase`).

### D. `ImportDeviceEventsUseCase` — `remaining` / `truncated`
**Constat 8 — `remaining` peut devenir négatif si `readEvents` dépasse la limite demandée**  
- **Lieu**: `ImportDeviceEventsUseCase.kt:~70-140` (`remaining -= deviceEvents.size`)  
- **Scénario**:
  - `remaining = 10`, `readEvents(deviceId, remaining)` renvoie 12 événements (bug provider / impl repo) → `remaining = -2`.
  - Le code s’en sort (les calendriers suivants sont sautés par `remaining <= 0`), mais l’invariant “remaining ≥ 0” saute.
- **Sévérité**: **FAIBLE**  
- **Statut**: **PROBABLE** (car `readEvents` est censé respecter la limite, mais ce n’est pas prouvé ici).

**Drapeau `truncated` dans les 4 combinaisons (tronqué/pas × write ok/ko)**  
- **Lieu**: même bloc  
- **Conclusion**: le drapeau dit la vérité **au sens “lecture tronquée / import arrêté court côté source”**, y compris quand l’écriture échoue (puisque vous le fixez au moment de la lecture).  
- **Sévérité**: **RAS**  
- **Statut**: **CONFIRMÉ**.

---

## 4) Commentaires/KDoc du lot qui ne collent pas au code

**Constat 9 — commentaire obsolète “allowance … divided”**
- **Lieu**: `ReminderScheduler.kt:~175-190` (commentaire dans `schedule`, phrase “see rescheduleAll, the allowance is now divided”)  
- **Pourquoi c’est faux**: `rescheduleAll` ne fait plus `total / n`. Il applique un **cap par rappel** et sert en ordre avec `min(perReminderIterations, pass.remaining)` (+ plancher 1).  
- **Sévérité**: **FAIBLE** (mais c’est exactement le type de KDoc faux qui a déjà coûté cher ici)  
- **Statut**: **CONFIRMÉ**.

**Constat 10 — “pass ceiling bounds the total” n’est pas strictement vrai**
- **Lieu**: `ReminderScheduler.kt:KDoc de rescheduleAll ~60-115` + ligne du plancher `maxOf(1, …)`  
- **Pourquoi**: dépassement possible de `+R` (voir Constat 1).  
- **Sévérité**: **FAIBLE à MOYENNE** (selon l’importance que vous donnez au plafond strict)  
- **Statut**: **CONFIRMÉ**.

---

# À corriger avant release (à mon avis)
1) **Signal utilisateur persistant** quand des rappels récurrents ne sont pas réarmés (Constat 4 — **CRITIQUE**). Le log supprimé en release rend le défaut silencieux.  
2) **Décision produit sur `PER_REMINDER_ITERATIONS`**: à 10k vous cassez les quotidiennes > 27 ans (Constat 2 — **ÉLEVÉ**). Soit vous assumez et vous l’annoncez à l’utilisateur (via 1), soit vous montez le cap.

# Peut attendre
- Dépassement du plafond strict via plancher 1 (Constat 1 — **MOYENNE**).
- Concurrence de refresh widget (Constat 6 — **FAIBLE**).
- `remaining` négatif si repo ne respecte pas sa limite (Constat 8 — **FAIBLE**).
- Commentaires obsolètes/inexacts (Constats 9-10 — **FAIBLE** mais rentables à nettoyer).

# Zones saines (rien à signaler)
- **`BackupEnvelope`**: verdicts bien séparés, parse défensif, wipe du mot de passe sur toutes les sorties observables (Constat 5 — OK).