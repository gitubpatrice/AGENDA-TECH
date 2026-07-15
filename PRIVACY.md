# Politique de confidentialité — Agenda Tech

_Dernière mise à jour : 15 juillet 2026 — version 0.3.0_

Agenda Tech (`com.filestech.agenda_tech`) est une application d'agenda **entièrement locale**.
Elle est conçue autour d'un principe simple : **vos données ne quittent jamais votre appareil.**

## En résumé

- **Aucune donnée collectée, aucune donnée transmise.** L'application ne déclare **aucune
  permission Internet** (`INTERNET`, `ACCESS_NETWORK_STATE`) — elle est techniquement incapable
  d'envoyer quoi que ce soit sur un réseau.
- **Aucun compte, aucune inscription, aucun identifiant.**
- **Aucune publicité, aucun traceur, aucun outil d'analyse** (pas de Firebase Analytics, pas de
  Crashlytics, aucun SDK tiers de collecte).
- **Aucune sauvegarde cloud** : `allowBackup=false`, les données sont exclues des sauvegardes
  automatiques Android et des transferts d'appareil.

## Quelles données, et où

Tout ce que vous saisissez (événements, titres, lieux, notes, rappels, calendriers) est stocké
**uniquement sur votre appareil**, dans une base de données **chiffrée** (SQLCipher, AES-256 ;
la clé est protégée par l'AndroidKeyStore, matériel/TEE sur les appareils compatibles).

Le développeur n'a **aucun accès** à ces données et n'en reçoit **aucune copie**.

## Permissions demandées et pourquoi

| Permission | Usage | Réseau ? |
|---|---|---|
| `READ_CALENDAR` | Importer, à votre demande, les événements **déjà présents** sur l'appareil (agenda Google/Exchange/local synchronisé par le système). **Lecture seule.** | Non |
| `POST_NOTIFICATIONS` | Afficher les rappels d'événements. | Non |
| `USE_EXACT_ALARM` / `SCHEDULE_EXACT_ALARM` | Déclencher les rappels à l'heure exacte. | Non |
| `RECEIVE_BOOT_COMPLETED` | Reprogrammer les rappels après un redémarrage. | Non |
| `VIBRATE` | Vibration des rappels. | Non |

L'application ne demande **jamais** l'accès à la localisation, aux contacts, au micro, à la caméra,
ni à Internet.

L'import du calendrier de l'appareil (`READ_CALENDAR`) lit seulement ce qui est **déjà synchronisé
localement** par les applications système ; Agenda Tech ne se connecte pas à votre compte Google ni
à aucun service distant. La permission est demandée **à l'exécution**, uniquement au moment où vous
ouvrez l'écran d'import, et peut être refusée.

## Partage avec des tiers

**Aucun.** Aucune donnée n'est partagée, vendue ou transmise à qui que ce soit — l'application n'a
aucun moyen technique de le faire (pas de permission Internet).

Les seuls échanges possibles sont ceux que **vous** déclenchez explicitement, et qui restent sur
votre appareil, d'une application à l'autre :

- **Export `.ics`** : vers l'emplacement de votre choix, via le sélecteur de fichiers système.
- **Ouvrir un lieu sur la carte** : si vous saisissez des coordonnées GPS sur un événement et que
  vous touchez le repère, l'application transmet **ces coordonnées et le libellé de l'événement** à
  l'application de cartes de votre téléphone. Rien d'autre n'est transmis, et rien ne part si vous
  ne touchez pas le repère. Ce que cette application de cartes fait ensuite de ces informations
  relève de sa propre politique de confidentialité.
- **Son de rappel personnalisé** : si vous choisissez un fichier audio, l'application conserve
  l'autorisation de le lire pour pouvoir le jouer au moment du rappel.

## Vos droits (RGPD)

L'application ne traitant aucune donnée personnelle en dehors de votre appareil, il n'existe aucun
traitement distant à consulter, rectifier ou supprimer. Vous gardez le contrôle total : supprimer
un événement, un calendrier, ou désinstaller l'application efface les données correspondantes de
l'appareil. La désinstallation supprime la base chiffrée.

## Enfants

L'application ne collecte aucune donnée et convient à tous les publics.

## Modifications

Cette politique pourra évoluer avec l'application ; la date en tête de document indique la dernière
révision, et l'historique est public dans ce dépôt.

## Contact

Question ou signalement : ouvrez une [issue](https://github.com/gitubpatrice/AGENDA-TECH/issues)
sur le dépôt, ou via [files-tech.com](https://files-tech.com). Pour la sécurité, voir
[SECURITY.md](SECURITY.md).
