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
  à 60 s) est imposé avant chaque nouvelle tentative. L'état est **en mémoire process** (jamais
  persisté : un lock-out oublié ne peut pas bloquer l'app) et basé sur l'horloge **monotone**
  (`SystemClock.elapsedRealtime`, insensible au changement d'heure).
- **Ré-authentification (LOCK-6).** Désactiver le verrou ou changer le PIN exige d'abord la saisie
  du PIN actuel.
- **Biométrie.** `BiometricPrompt` accepte `BIOMETRIC_STRONG` **et** `BIOMETRIC_WEAK` (LOCK-9) —
  compromis UX assumé pour des données personnelles locales (ni paiement ni secret à haute valeur) ;
  repli explicite vers le PIN via le bouton négatif.
- **Résiduel accepté (LOCK-8).** Le PIN transite en `String` immuable dans l'état Compose avant
  conversion en `CharArray` (wipé après hachage). Le scrubbing complet du chemin Compose serait
  disproportionné (`OutlinedTextField` est nativement `String`-backed) ; l'exploitation exigerait un
  dump mémoire d'un build release non-debuggable.

## Exposition à l'écran

- `FLAG_SECURE` est posé par défaut sur l'`Activity` : pas d'aperçu dans les Récents, capture
  d'écran bloquée. Un réglage « confidentialité » permet de l'assouplir, **mais** il est
  **forcé actif** tant que le verrou est activé ou non résolu (LOCK-2) : l'écran de saisie du PIN et
  l'aperçu Récents capturé lors du passage en arrière-plan ne peuvent jamais fuiter, quel que soit
  le réglage utilisateur.

### Widget écran d'accueil (limitation connue)

Le widget optionnel affiche la date du jour et les **titres** des prochains événements
directement sur l'écran d'accueil. C'est le comportement attendu d'un widget d'agenda (identique
à Google Agenda), mais `FLAG_SECURE` **ne s'applique pas** aux widgets — leur contenu est rendu
par le launcher, hors du contrôle de l'app.

Deux garde-fous : le widget est **opt-in** (l'utilisateur choisit de le poser) ; un réglage
« masquer les titres » n'affiche alors que l'heure. **Quand le verrou d'application est activé, les
titres sont masqués d'office dans le widget (LOCK-3)** — activer le verrou ne laisse donc jamais de
titre lisible sur l'écran d'accueil.

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

## Sauvegardes

- `allowBackup="false"` + règles d'exclusion (`data_extraction_rules.xml`, `backup_rules.xml`) :
  ni backup cloud ni transfert d'appareil. La clé enveloppée étant liée à l'AndroidKeyStore local,
  l'exporter serait inutile et inutilement exposant.

## Signalement

Vulnérabilité ? Contact : voir les mentions du dépôt Files Tech. Merci de ne pas divulguer
publiquement avant correctif.
