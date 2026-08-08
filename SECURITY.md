# Sécurité — Agenda Tech

## Modèle de menace

Agenda Tech est un agenda **local, hors-ligne**. Les données (événements, lieux, notes, rappels)
ne quittent jamais l'appareil : aucune permission `INTERNET` n'est déclarée et aucune ne doit
l'être. La surface d'attaque réseau est donc nulle par construction ; la protection porte sur les
données **au repos** et sur l'**exposition à l'écran**.

## Chiffrement au repos

- **SQLCipher** chiffre l'intégralité du fichier `agendatech.db` (AES-256).
- La **passphrase SQLCipher** est une clé aléatoire de 32 octets, générée au premier lancement,
  **enveloppée (AES-256-GCM) par une clé de l'AndroidKeyStore** (`agendatech_db_master`) —
  matérielle (TEE/StrongBox) sur les appareils compatibles, non exportable. Le blob enveloppé est
  stocké dans `filesDir/db/master.key`. Cf. `DatabaseKeyManager` / `KeystoreManager` / `AeadCipher`.
- **Pas de KEK en clair.** La clé maître n'est jamais persistée en clair : sans la clé
  AndroidKeyStore de l'appareil, le fichier `master.key` — et donc la base — est inexploitable.
- **Résidence en mémoire (assumée).** SQLCipher relit la passphrase à **chaque** ouverture de
  connexion de son pool : elle doit donc rester en mémoire tant que la base est ouverte. La copie
  transmise à SQLCipher vit pour la durée de vie du processus ; celle détenue par l'app est
  effacée (`wipe`) dès la construction terminée. C'est une contrainte de la bibliothèque, pas un
  choix — un effacement plus précoce casserait les connexions suivantes.
- **Robustesse** : l'invalidation réelle du Keystore (changement du verrou d'écran, reset Knox)
  est distinguée d'une corruption transitoire ; aucun effacement silencieux (pas de perte de
  données furtive) — l'échec est typé et remonté pour un futur flux de récupération.

### Posture connue (assumée, à revisiter selon le besoin)

La clé de base **n'est pas** protégée par authentification utilisateur
(`setUserAuthenticationRequired = false`), à l'identique du socle SMS Tech : la protection au repos
repose sur le verrou de l'appareil + l'AndroidKeyStore.

## Verrou d'application (PIN / biométrie)

Un **verrou optionnel** garde l'UI derrière un code PIN et/ou la biométrie (`AppLockManager`,
`LockScreen`, `LockRepository`). Il est indépendant du chiffrement de la base (qui reste actif
quoi qu'il arrive) : c'est un **gate UI**, il ne modifie pas la posture crypto de la clé DB.

- **PIN jamais stocké.** Seul un hash **PBKDF2-HMAC-SHA256** (120 000 itérations, sel aléatoire de
  16 o, clé de 256 bits) est conservé. La comparaison est à temps constant (`MessageDigest.isEqual`).
- **Hash enveloppé (LOCK-1).** Le blob `sel || hash` est **chiffré AES-256-GCM sous une clé
  AndroidKeyStore** (`agendatech_pin_wrap`) avant d'atterrir dans le fichier DataStore (non chiffré
  par défaut) — même pattern que la clé DB. Un PIN a un keyspace minuscule : sans cet enveloppement,
  un sel+hash en clair serait cassable hors-ligne en secondes. L'enveloppement bloque l'exfiltration
  simple de fichier (une extraction root avec exécution de code reste hors périmètre).
- **Anti-force-brute (LOCK-4).** Après 5 essais erronés, un back-off croissant (10 s, 20 s… plafonné
  à 60 s) est imposé avant chaque nouvelle tentative. Le décompte vivant utilise l'horloge
  **monotone** (`SystemClock.elapsedRealtime`), insensible au changement d'heure.
- **Le compteur survit à la mort du processus (audit SEC-2, v0.5.2).** L'état était auparavant
  purement en mémoire : un simple *force-stop* — aucun privilège requis — remettait le compteur à
  zéro et rendait 5 tentatives fraîches à chaque relance, annulant l'escalade. Il est désormais
  persisté (`LockThrottleStore`). Le plafond de 60 s garantit qu'un lock-out oublié ne peut pas
  bloquer l'app. L'échéance persistée est en horloge murale, donc déplaçable par l'utilisateur —
  elle est **bornée à un palier** au rechargement : avancer l'heure fait sauter au plus une
  attente, jamais le compteur de tentatives.
- **Une tentative = une opération atomique (audit S16 + SEC-2, v0.5.5).** Le back-off, la vérification
  du PIN et l'enregistrement du résultat se déroulent **sous un même verrou**
  (`AppLockManager.attemptPin`).

  Auparavant la décision était coupée en deux par les ~100 ms de PBKDF2, verrou relâché : toute
  tentative lancée dans cet intervalle lisait un back-off nul et obtenait son essai. Le compteur
  restait juste, mais le back-off n'était appliqué à aucune d'elles — une rafale d'appuis obtenait
  donc plusieurs essais par fenêtre de 60 s.

  ⚠️ Cela vaut désormais pour **les deux** écrans qui demandent un PIN : celui de verrouillage et
  celui des **Réglages**. S16 n'avait corrigé que le premier, alors que cette phrase affirmait déjà la
  parité — et le second est précisément l'écran depuis lequel le verrou peut être **désactivé**
  (audit SEC-2).
- **Réinitialisation des réglages : dite, jamais silencieuse (audit S15, v0.5.5).** Le fichier de
  préférences porte `lock_enabled` et l'enveloppe du PIN. S'il devient illisible (coupure pendant une
  écriture, secteur corrompu, ou un octet modifié par quiconque a un accès fichier ponctuel), il est
  remplacé par des valeurs par défaut — et **le verrou d'application se retrouve désactivé**. Il n'est
  pas restaurable : l'enveloppe du PIN est morte avec le fichier, et verrouiller sans rien à saisir
  serait la seule issue pire. L'application **le signale explicitement au démarrage suivant**, pour
  que personne ne continue à faire confiance à une protection qui n'est plus là.
- **Ré-authentification (LOCK-6).** Désactiver le verrou ou changer le PIN exige d'abord la saisie
  du PIN actuel.
- **Biométrie : Classe 3 uniquement (audit F3, v0.5.3).** `BiometricPrompt` n'accepte plus que
  `BIOMETRIC_STRONG`. `BIOMETRIC_WEAK` (Classe 2) est précisément le palier que la plateforme
  interdit d'adosser à une clé Keystore, parce qu'il est usurpable par une photo sur beaucoup de
  déverrouillages faciaux OEM ; le compromis UX antérieur (LOCK-9) est donc annulé. `DEVICE_CREDENTIAL`
  reste exclu : il réadmettrait le même palier faible indirectement. Un appareil sans Classe 3
  retombe sur le PIN, qui est throttlé, et le champ PIN est affiché en toutes circonstances — le
  durcissement ne peut pas enfermer l'utilisateur hors de ses données. La politique est centralisée
  dans `StrongBiometrics` : disponibilité et masque d'authentifieurs sont la même question, posée au
  même endroit par le prompt, l'écran de verrouillage et le réglage.
- **Liaison cryptographique (audit F3, v0.5.4).** Le déverrouillage n'est plus conditionné au seul
  rappel `onAuthenticationSucceeded` — une affirmation faite par le processus de l'app, donc sans
  valeur là où ce processus peut être manipulé. `BiometricPrompt` reçoit un `CryptoObject` adossé à
  une clé AndroidKeyStore dédiée (`agendatech_biometric_gate`, cf. `BiometricGate`), créée avec
  `setUserAuthenticationRequired(true)` **sans** fenêtre de validité : le TEE exige donc une
  authentification Classe 3 pour **chaque** usage. L'UI ne s'ouvre que si l'opération cryptographique
  aboutit réellement. Sur API 30+, le palier accepté est épinglé à `AUTH_BIOMETRIC_STRONG` — la
  politique Classe 3 est alors imposée par l'OS, pas seulement par notre propre contrôle.
- **Résiduel accepté (API 26-29).** `setUserAuthenticationParameters` n'existe qu'à partir d'API 30 :
  sur les appareils antérieurs, la clé exige bien une authentification par usage, mais le palier
  Classe 3 n'est pas épinglé dans le Keystore lui-même — il repose alors sur le seul
  `setAllowedAuthenticators` de `BiometricPrompt`. Non vérifié sur un appareil réel API < 30.
- **Ré-enrôlement.** La clé est créée avec `setInvalidatedByBiometricEnrollment(true)` : ajouter une
  empreinte la détruit, ce qui est le comportement voulu (qui peut enrôler ne doit pas hériter du
  déverrouillage). `KeyPermanentlyInvalidatedException` est rattrapée à l'initialisation du cipher,
  la clé morte est supprimée, la préférence biométrique désactivée et l'utilisateur informé — puis
  repli sur le PIN, jamais un bouton qui échoue en silence.
- **Portée.** La biométrie garde l'**écran**. Elle ne dérive pas la clé de la base : celle-ci reste
  enveloppée par sa propre clé AndroidKeyStore, indépendamment du verrou d'application.
- **Résiduel accepté (LOCK-8).** Le PIN transite en `String` immuable dans l'état Compose avant
  conversion en `CharArray` (wipé après hachage). Le scrubbing complet du chemin Compose serait
  disproportionné (`OutlinedTextField` est nativement `String`-backed) ; l'exploitation exigerait un
  dump mémoire d'un build release non-debuggable.

## Exposition à l'écran

- `FLAG_SECURE` est posé par défaut sur l'`Activity` : pas d'aperçu dans les Récents, capture
  d'écran bloquée. Un réglage « confidentialité » permet de l'assouplir, **mais** il est
  **forcé actif** dans deux cas (LOCK-2) : tant que le verrou **garde effectivement l'écran**
  (application verrouillée) et tant que son état n'est **pas encore résolu** au démarrage.

  ⚠️ Une fois le PIN saisi, votre réglage « autoriser les captures » **reprend la main** : c'est le
  comportement voulu, et ce n'était pas ce que ce paragraphe disait. Il annonçait « forcé actif tant
  que le verrou est activé », ce qui se lit naturellement comme « tant que la fonction verrou est
  activée dans les réglages » — un utilisateur pouvait donc croire ses captures bloquées alors
  qu'elles ne l'étaient pas (audit S14).

- **Passage en arrière-plan.** `FLAG_SECURE` est levé dès `onPause`, un cycle de vie **avant** le
  re-verrouillage, et de nouveau à `onStop`. La raison est une course qui n'avait jamais été
  mesurée : `Window.addFlags` n'atteint le gestionnaire de fenêtres qu'au traversal suivant, tandis
  que l'instantané de tâche est capturé par le système autour de cette même transition. Lever le
  drapeau plus tôt donne au traversal le temps d'arriver.

  ⚠️ Ce paragraphe affirmait auparavant que l'aperçu Récents « ne peut jamais fuiter ». Une garantie
  absolue posée sur une course non mesurée n'en est pas une (audit S5). Ce qui est vrai et vérifiable
  aujourd'hui : le drapeau est levé au **premier** point du cycle de vie que l'application contrôle
  après avoir quitté le premier plan, et le verrouillage, lui, est **synchrone** depuis F13 — il ne
  dépend plus d'une lecture disque qui se terminait après la capture.

### Widget écran d'accueil (limitation connue)

Le widget optionnel affiche la date du jour et les **titres** des prochains événements
directement sur l'écran d'accueil. C'est le comportement attendu d'un widget d'agenda (identique
à Google Agenda), mais `FLAG_SECURE` **ne s'applique pas** aux widgets — leur contenu est rendu
par le launcher, hors du contrôle de l'app.

Deux garde-fous : le widget est **opt-in** (l'utilisateur choisit de le poser) ; un réglage
« masquer les titres » n'affiche alors que l'heure. **Quand le verrou d'application est activé, les
titres sont masqués d'office dans le widget (LOCK-3)** — activer le verrou ne laisse donc jamais de
titre lisible sur l'écran d'accueil.

### Notification de rappel et verrou d'application — asymétrie assumée (F9)

Le verrou d'application masque les titres dans le **widget**, mais **pas** dans la **notification de
rappel**, qui continue d'afficher le titre de l'événement. Les deux surfaces répondent donc
différemment à un même réglage. C'est un **arbitrage produit**, pas un oubli : il est écrit ici pour
qu'il cesse d'en être un.

**Décision retenue : garder le comportement actuel.**

- Un rappel dont le titre est masqué ne dit plus ce qu'il rappelle. C'est la fonction principale du
  produit, et la dégrader pour tous protégerait un scénario que les deux garde-fous ci-dessous
  couvrent déjà.
- L'écran de verrouillage de **l'appareil** est traité séparément et l'est correctement : la
  visibilité des notifications y est restreinte, de sorte qu'un téléphone verrouillé posé sur une
  table n'expose pas le titre.
- Le widget et la notification ne sont pas des surfaces comparables. Le widget est **permanent** sur
  l'écran d'accueil, visible de tous, sans action de personne ; la notification est **transitoire**,
  déclenchée par un rappel que l'utilisateur a lui-même posé.

**Ce que cela implique, et qu'il faut savoir** : sur un téléphone **déverrouillé** confié à
quelqu'un, un rappel qui survient affiche le titre de l'événement, même si le verrou d'application
est activé. Quiconque a besoin d'une confidentialité plus forte que cela doit s'appuyer sur le verrou
de l'appareil, pas sur celui de l'application.

À revisiter si un troisième cas d'usage vient s'ajouter — la bonne réponse serait alors probablement
un réglage explicite, pas un masquage imposé.

## Import du calendrier de l'appareil (READ_CALENDAR)

L'app peut copier ponctuellement les événements **déjà synchronisés localement** sur le téléphone
(agenda Google, Exchange, calendriers locaux) via le Calendar Provider Android (`CalendarContract`).
C'est la **seule** permission dangereuse de l'app, et elle **ne trahit pas** la doctrine zéro-réseau :

- **Lecture seule, 100 % locale.** `READ_CALENDAR` uniquement — jamais `WRITE_CALENDAR` ni
  `GET_ACCOUNTS`. Aucune connexion réseau, aucun accès au compte Google : Agenda Tech ne lit que ce
  que le système a déjà stocké sur l'appareil. La synchro distante reste gérée par les apps système.
- **Demandée à l'exécution, scoped à l'écran d'import** (`DeviceImportScreen`), jamais au démarrage.
  Aucune requête `CalendarContract` avant octroi ; refus géré proprement (pas de crash, pas de boucle).
- **Défenses sur le contenu tiers** (un calendrier partagé peut être piégé) : requêtes
  ContentResolver **paramétrées** (pas de concat SQL), cursors fermés (`use{}`), plafond
  `MAX_EVENTS` (20 000) et cap de longueur des champs, nettoyage **anti-Bidi** partagé avec l'import
  `.ics` (`BidiSanitizer`), parsing RRULE/EXDATE/durée **tolérant** (retourne null plutôt que de
  crasher, durée bornée anti-overflow). Import résilient : une erreur sur un calendrier est journalisée
  et ignorée, jamais propagée. Écriture DB **atomique par lot** (`upsertAll` en transaction).
- **Import idempotent (refresh sûr).** Chaque calendrier device est rattaché via un `source_id`
  stable (réutilisé au ré-import, plus de calendrier dupliqué) et chaque événement via un `source_uid`
  (`_sync_id`, repli `rowid`) : ré-importer met à jour les lignes en place et ajoute les nouveaux
  événements au lieu de tout dupliquer. Ce n'est PAS une synchro bidirectionnelle : un événement
  supprimé à la source n'est pas retiré (import additif). Réserve : un événement créé hors-ligne et
  pas encore synchronisé côté serveur peut être ré-inséré une fois au premier passage `rowid → _sync_id`.
  VALARM hors périmètre (comme l'import `.ics`).

## Contenu importé et expansion des récurrences

Un `.ics`, un calendrier système **et une sauvegarde `.atbak`** sont tous du **contenu tiers** : ils
sont traités comme hostiles, y compris une fois en base.

⚠️ Le `.atbak` manquait à cette liste, et le code suivait la liste (audit S7, v0.5.5). C'est pourtant
le format qui porte **tout** l'agenda, et une sauvegarde arrive volontiers d'ailleurs — « voilà mon
agenda partagé, mot de passe xxx ». Son fuseau était normalisé mais ses six champs de texte libre ne
passaient par aucun nettoyage : un `U+202E` dans un titre inversait sa lecture dans la vue mois, la
timeline, **le widget de l'écran d'accueil** et la notification de rappel. Le nettoyage anti-Bidi et
le plafond de longueur s'y appliquent désormais comme aux deux autres.

- **Plafond sur le NOMBRE d'événements (audit S12, v0.5.5).** Les plafonds d'import étaient tous en
  **octets** — 5 Mio pour un `.ics`, 16 Mio pour un `.atbak` — ce qui borne la mémoire mais pas le
  nombre de lignes écrites : 5 Mio de blocs `VEVENT` minimaux font environ 87 000 événements.
  `ImportLimits.MAX_EVENTS` donne une réponse unique et partagée. Un **fichier** choisi par
  l'utilisateur est refusé **en bloc** avec un message — en importer une partie ressemble exactement
  à l'importer en entier — tandis que l'agenda **de l'appareil**, qui est un fournisseur vivant sans
  fichier à rendre, reste tronqué.

  ⚠️ Cette troncature n'est **pas encore signalée à l'utilisateur** (audit SEC-6). Elle est journalisée,
  mais le journal est inerte sur une version publiée (`NoOpReleaseTree`), exactement la conclusion que
  l'audit D3 avait tirée pour la migration sans la reporter ici. Le dire correctement suppose de
  remonter le fait jusqu'à l'écran d'import plutôt que de le journaliser ; c'est consigné comme
  travail restant, pas présenté comme fait.

  Le plafond s'applique par **fichier** et, pour l'agenda de l'appareil, par **calendrier source** :
  sélectionner dix calendriers en une fois peut donc dépasser le nombre annoncé. Là encore, dit plutôt
  que sous-entendu.

- **`INTERVAL` borné (audit F1/F5/F7, v0.5.3).** Un intervalle absurde faisait lever une
  `DateTimeException` non rattrapée dans `RecurrenceExpander` : toutes les vues et le widget
  plantaient à chaque rendu, en boucle dès le lancement, et l'événement fautif devenait inatteignable
  — seule issue, effacer toutes les données. `RecurrenceRule.MAX_INTERVAL` est imposé dans `init` et
  borné aux **cinq** points d'ingestion : import `.ics`, import device, **lecture depuis la base**,
  **restauration `.atbak`** et éditeur. Les deux derniers sont indispensables : sans le bornage à la
  lecture, une ligne déjà écrite par une version affectée déplacerait le crash au lieu de le corriger.
- **Bornes de RRULE tolérantes (v0.5.4).** Un `COUNT` inférieur à 1, ou un `COUNT` et un `UNTIL`
  simultanés, violent les invariants du modèle. Plutôt que de faire perdre à l'événement toute sa
  récurrence, la borne fautive est écartée et `COUNT` l'emporte — même lecture par l'import `.ics` et
  par l'import device.
- **Budget d'expansion global (audit F8, v0.5.4).** Le plafond par événement ne dit rien du nombre
  d'événements, et c'est exactement ce qu'un import contrôle. Un `ExpansionBudget` unique est partagé
  par toute une passe de rendu : un agenda pathologique est **tronqué** (et journalisé), jamais
  transformé en gel de l'interface.

## Sauvegardes

- `allowBackup="false"` + règles d'exclusion (`data_extraction_rules.xml`, `backup_rules.xml`) :
  ni backup cloud ni transfert d'appareil. La clé enveloppée étant liée à l'AndroidKeyStore local,
  l'exporter serait inutile et inutilement exposant.

### Sauvegarde chiffrée `.atbak` (export manuel)

Comme la clé de la base est liée à l'appareil, une copie exploitable ailleurs doit être chiffrée
par un secret que l'utilisateur connaît — d'où un mot de passe, et non la clé du KeyStore.

```
[magic:5 "ATBAK"][envVersion:1][kdfId:1][iterations:4 BE][saltLen:1][salt:16]   ← en-tête (28 o)
[AeadCipher : version:1 | iv:12 | ciphertext+tag]                              ← corps
```

- **KDF** : PBKDF2-HMAC-SHA256, **600 000 itérations** (plancher OWASP), sel aléatoire de 16 octets,
  clé de 256 bits. Mot de passe **12 caractères minimum**, jamais stocké : un mot de passe oublié
  rend le fichier définitivement illisible — c'est le prix du chiffrement hors ligne.
- **Chiffrement** : AES-256-GCM via `AeadCipher` (le même primitif que la clé de base).
- **En-tête authentifié (AAD)** : l'en-tête n'est pas secret mais il est **authentifié**. Réécrire le
  sel, ou abaisser `iterations` à une valeur bon marché pour rendre une attaque hors ligne triviale,
  casse le tag GCM et le fichier refuse de s'ouvrir. À la lecture, `iterations` hors de
  `[100 000, 10 000 000]` est refusé avant tout calcul (déni de service par fichier hostile).
- **Évolutivité** : `kdfId` et le coût sont écrits explicitement, pas implicites — passer à Argon2id
  plus tard n'invalidera pas les fichiers déjà écrits. Une sauvegarde doit encore s'ouvrir dans des
  années, c'est sa seule raison d'être.
- **Restauration atomique** : le fichier est intégralement déchiffré et validé **avant** toute
  écriture, puis l'agenda est remplacé en une transaction. Un fichier tronqué, falsifié ou protégé
  par un autre mot de passe laisse l'agenda existant intact.
- **Résiduel accepté (identique à LOCK-8).** Le mot de passe de sauvegarde transite lui aussi en
  `String` immuable dans l'état Compose avant conversion en `CharArray` (wipé après dérivation, sur
  tous les chemins — y compris annulation et échec). Même raison, même arbitrage :
  `OutlinedTextField` est nativement `String`-backed, et l'exploitation exigerait un dump mémoire
  d'un build release non-debuggable.
- **Validation avant écriture.** Le fichier est refusé en bloc (jamais partiellement appliqué) s'il
  porte un id ≤ 0 (Room renumérote silencieusement un id 0 sur une clé `autoGenerate`, ce qui ferait
  pendre toutes les références), un id dupliqué, un événement rattaché à un calendrier absent, ou un
  override pointant vers un parent absent (aucune FK ne couvre `recurrence_parent_id`).
- Un mauvais mot de passe et un fichier corrompu sont **indistinguables** (tous deux = tag GCM
  invalide) : l'UI dit « mot de passe incorrect **ou** fichier endommagé », sans confirmer lequel.

## Signalement

Vulnérabilité ? Contact : voir les mentions du dépôt Files Tech. Merci de ne pas divulguer
publiquement avant correctif.
