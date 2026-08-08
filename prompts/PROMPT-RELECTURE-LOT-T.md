# Relecture — le dernier lot, écrit il y a une heure, juste avant une release 1.0.0

Tu relis du code Android/Kotlin d'**Agenda Tech** : agenda 100 % local, base SQLCipher,
`allowBackup=false` — **la base est le seul exemplaire de l'agenda de l'utilisateur**, et un `.atbak`
son unique moyen de sauvegarde.

## Pourquoi cette relecture existe, et ce qu'elle doit chercher

Ce dépôt sort de **cinq passes d'audit**. Le fait qui compte : **six fois sur six, une passe a trouvé
des défauts introduits par les correctifs de la précédente.** Le dernier en date, il y a une heure :

> Un correctif divisait une allocation de calcul `total / n` pour empêcher une série récurrente
> pathologique d'affamer les autres. **Deux relectures externes indépendantes l'avaient validé.**
> Aucune n'avait fait l'arithmétique : la division n'est clémente qu'avec une population
> *hétérogène*. Sur une population *homogène* — mille rappels sur des séries quotidiennes de trois
> ans, le cas ordinaire — la part tombe sous le besoin de chacun et **aucun** rappel n'est réarmé,
> là où l'allocation partagée en armait 90 %. Le correctif était plus destructeur que le défaut.

**Le code que tu relis est le correctif de ce correctif.** Il n'a été relu par personne. Il part en
release publique 1.0.0 juste après toi. Traite-le comme la zone la plus suspecte du dépôt.

## Ce que je te demande, par ordre de valeur

### 1. Refais l'arithmétique. Ne fais confiance à aucun commentaire.

Le nouveau schéma est **deux plafonds** : un plafond de passe (`newPassBudget()`, 1 000 000
itérations) qui borne le total, et un plafond par rappel (`PER_REMINDER_ITERATIONS = 10 000`) qui
empêche une série d'accaparer le total. Chaque rappel reçoit
`ExpansionBudget(maxOf(1, minOf(perReminderIterations, pass.remaining)))`, et ce qu'il a dépensé est
reporté sur la passe par `pass.charge(allowance.spent)`.

Vérifie **par le calcul**, pas par la lecture :

- Le total est-il réellement borné ? Par quoi exactement, et avec quel dépassement possible ?
  (Le plancher `maxOf(1, …)` est délibéré — un `ExpansionBudget(0)` lève. Quel est son coût réel ?)
- Existe-t-il une population — homogène, hétérogène, ou mixte — où ce schéma arme **moins** de
  rappels que l'allocation simplement partagée d'avant ? Donne les nombres.
- `RecurrenceExpander.occurrenceStarts` balaie depuis `event.startUtcMillis` et consomme une unité
  par occurrence. Une série quotidienne de 27 ans coûte donc ~10 000. Le plafond par rappel est-il
  au bon endroit pour un agenda réel ? Trop haut, trop bas ?
- `pass.charge(allowance.spent)` : `spent` compte-t-il bien ce qui a été consommé, y compris quand
  la sous-allocation n'a pas été épuisée ? Peut-on charger deux fois, ou pas du tout ?

### 2. Ce qui reste non armé, et si on le sait

Un rappel récurrent qui déborde son plafond, ou qui arrive après épuisement du total, **n'est pas
réarmé** par cette passe. Les alarmes exactes ne survivent pas à un redémarrage, donc « non réarmé »
veut dire « ne sonnera plus jusqu'à ce que l'utilisateur ré-enregistre l'événement ».

- Est-ce le bon arbitrage, ou existe-t-il un repli meilleur qui ne coûte pas une nouvelle passe ?
- Le seul témoin est un `Timber.w`, que `NoOpReleaseTree` supprime en release. L'utilisateur n'a donc
  aucun moyen de savoir. Que devrait-il se passer à la place ?
- Les rappels **non récurrents** sont censés être armés quoi qu'il arrive, parce que
  `RecurrenceExpander.nextOccurrenceStart` répond avant de regarder un budget. Vérifie-le sur le
  code, c'est une garantie sur laquelle le KDoc s'appuie.

### 3. Les autres correctifs du même lot

- **`BackupEnvelope`** — `UnsupportedVersion` est maintenant décidé sur `>` et non sur `!=` (un octet
  corrompu disait « mettez l'application à jour » à quelqu'un déjà à jour). Les quatre verdicts
  sont-ils toujours exhaustifs et mutuellement exclusifs ? Un fichier hostile peut-il obtenir le
  verdict qui l'arrange ? Le mot de passe est-il effacé sur **toutes** les sorties ?
- **`SettingsViewModel.refreshWidget`** — passé de `viewModelScope` au scope applicatif, avec la
  `CancellationException` relancée. Fuite de contexte ? Coroutine qui survit à ce qu'elle devrait ?
  Deux rafraîchissements concurrents ?
- **`BackupViewModel.onExportTargetPicked`** — le `CharArray` du mot de passe est-il effacé sur les
  **trois** branches ? `onCleared` couvre-t-il ce qui reste ?
- **`ImportDeviceEventsUseCase`** — l'allocation et le drapeau `truncated` sont désormais enregistrés
  à la lecture. `remaining` peut-il devenir négatif ? Le drapeau dit-il la vérité dans les quatre
  combinaisons (tronqué/pas tronqué × écriture réussie/échouée) ?

### 4. Les commentaires

Ce dépôt a produit une douzaine de KDoc affirmant ce que le code ne garantit pas, et **deux d'entre
eux ont été écrits dans ce lot même**. Signale chaque phrase du lot que le code voisin ne tient pas.
C'est la famille la plus rentable ici.

---

## Ce que j'attends

Pour chaque constat : `fichier:ligne`, **scénario concret avec des nombres quand il s'agit de
budget**, sévérité, et **CONFIRMÉ ou PROBABLE**.

Je préfère **deux constats solides à dix plausibles**. Ce code part en production ce soir : dis-moi
ce qui doit être corrigé avant, et ce qui peut attendre.

Si une zone est saine, dis-le explicitement — « rien à signaler sur X » est ce qui me permet de
publier.

Ne propose jamais de changer la clé de signature, de toucher au keystore, ni de renommer le package.
Ne signale pas de préférences de style.
