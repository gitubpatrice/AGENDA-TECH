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
repose sur le verrou de l'appareil + l'AndroidKeyStore. Un **verrou biométrique optionnel** du
coffre pourra être ajouté en phase 2 pour un modèle de menace plus strict (appareil déverrouillé
laissé sans surveillance).

## Exposition à l'écran

- `FLAG_SECURE` est posé par défaut sur l'`Activity` : pas d'aperçu dans les Récents, capture
  d'écran bloquée. Un réglage pour l'assouplir viendra avec la phase Réglages.

### Widget écran d'accueil (limitation connue)

Le widget optionnel affiche la date du jour et les **titres** des prochains événements
directement sur l'écran d'accueil. C'est le comportement attendu d'un widget d'agenda (identique
à Google Agenda), mais `FLAG_SECURE` **ne s'applique pas** aux widgets — leur contenu est rendu
par le launcher, hors du contrôle de l'app. Un titre potentiellement sensible peut donc être
visible par quiconque a un accès visuel à l'écran d'accueil.

Choix assumé : le widget est **opt-in** (l'utilisateur choisit de le poser). Un réglage
« confidentialité du widget » (masquer les titres → n'afficher que l'heure ou un compteur)
sera proposé avec la phase Réglages pour qui souhaite un compromis plus strict.

## Sauvegardes

- `allowBackup="false"` + règles d'exclusion (`data_extraction_rules.xml`, `backup_rules.xml`) :
  ni backup cloud ni transfert d'appareil. La clé enveloppée étant liée à l'AndroidKeyStore local,
  l'exporter serait inutile et inutilement exposant.

## Signalement

Vulnérabilité ? Contact : voir les mentions du dépôt Files Tech. Merci de ne pas divulguer
publiquement avant correctif.
