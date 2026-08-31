# Politique de confidentialité — Agenda Tech

_Dernière mise à jour : 26 août 2026 — version 1.0.3_ · 🇬🇧 [English version](PRIVACY.md)

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

Cette liste est **exhaustive** : ce sont les onze permissions que porte l'APK publié, telles qu'on
les lit dans son manifeste **fusionné**. Elle inclut donc celles qu'aucune ligne de notre code ne
demande, mais qu'une bibliothèque a apportées avec elle — les taire aurait été plus flatteur et moins
vrai. Un contrôle automatique refuse toute construction dont le manifeste fusionné s'écarterait de
cette liste (`tools/check-manifest-permissions.py`, exécuté à chaque intégration continue).

### Déclarées par l'application

| Permission | Usage | Réseau ? |
|---|---|---|
| `READ_CALENDAR` | Importer, à votre demande, les événements **déjà présents** sur l'appareil (agenda Google/Exchange/local synchronisé par le système). **Lecture seule.** | Non |
| `POST_NOTIFICATIONS` | Afficher les rappels d'événements. | Non |
| `USE_EXACT_ALARM` / `SCHEDULE_EXACT_ALARM` | Déclencher les rappels à l'heure exacte. | Non |
| `RECEIVE_BOOT_COMPLETED` | Reprogrammer les rappels après un redémarrage. | Non |
| `VIBRATE` | Vibration des rappels. | Non |

### Apportées par les bibliothèques utilisées

| Permission | Origine | Ce qu'elle sert **réellement** ici | Réseau ? |
|---|---|---|---|
| `USE_BIOMETRIC` | `androidx.biometric` | Déverrouiller l'application par empreinte ou visage, si vous activez le verrou. | Non |
| `USE_FINGERPRINT` | `androidx.biometric` | Même chose sur Android 9 et antérieurs, où l'API biométrique moderne n'existe pas. | Non |
| `WAKE_LOCK` | `androidx.work`, tirée par Glance (les widgets) | Prise un instant pendant qu'un widget de l'écran d'accueil se redessine. Glance confie ce redessin à WorkManager, qui prend un verrou de réveil partiel pour exécuter n'importe quelle tâche. Le code de l'application, lui, n'en planifie aucune. | Non |
| `FOREGROUND_SERVICE` | `androidx.work`, tirée par Glance (les widgets) | **Rien.** Déclarée par `androidx.work`, qui n'en aurait besoin que pour une tâche « expedited » — il n'en est jamais planifié. | Non |
| `com.filestech.agenda_tech.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | `androidx.core` | Permission **auto-attribuée**, de niveau « signature » : seule une application signée avec notre clé peut l'obtenir. Elle empêche les autres applications d'atteindre nos récepteurs internes. | Non |

Corrigé le 26 août 2026. Ce paragraphe affirmait que ces deux permissions ne couvraient aucun usage
réel. C'était vrai du code de l'application et **faux de l'application que vous installez** : Glance
redessine un widget en confiant le travail à WorkManager, si bien que `WAKE_LOCK` est réellement prise,
brièvement, à chaque rafraîchissement de widget. La retirer ferait échouer ces mises à jour.
`FOREGROUND_SERVICE`, elle, est bien inutilisée, mais elle vient de la même bibliothèque et reste avec
elle plutôt que d'être retirée seule.

Ni l'une ni l'autre ne donne d'accès réseau, ni ne permet de lire quoi que ce soit. Le verrou de réveil
empêche seulement le processeur de se rendormir pendant la fraction de seconde que dure un redessin.

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
- **Sauvegarde chiffrée `.atbak`** : c'est l'échange le plus large, il mérite d'être décrit
  précisément. Le fichier contient **tout votre agenda** (calendriers, événements, descriptions,
  lieux, adresses, coordonnées GPS, rappels), et vous choisissez où il est écrit — y compris un
  dossier synchronisé vers un cloud, si c'est votre choix. **L'application ne l'envoie nulle part
  elle-même** : elle écrit à l'emplacement que vous désignez dans le sélecteur de fichiers système,
  et n'a de toute façon aucun moyen d'accéder au réseau. Le contenu est chiffré (AES-256) par une
  clé dérivée de **votre mot de passe seul** : ni nous, ni le service qui hébergerait ce fichier ne
  peuvent le lire. Pour une sauvegarde que vous exportez à la main, ce mot de passe n'est stocké
  nulle part — si vous l'oubliez, le fichier est définitivement illisible, y compris pour nous. Ce
  que devient ensuite le fichier, une fois hors de l'application, ne dépend plus que de vous.
- **Sauvegarde automatique** (facultative, désactivée par défaut) : une fois activée, l'application
  écrit le même `.atbak` chiffré une fois par semaine dans un dossier que vous choisissez, en
  conservant les quatre plus récents. Elle n'envoie toujours rien nulle part — elle écrit dans ce
  dossier, et n'a aucun accès réseau. Parce qu'elle s'exécute sans vous, c'est le seul endroit où
  **votre mot de passe est conservé sur le téléphone** : chiffré par une clé détenue par le
  matériel sécurisé de l'appareil, qui ne le quitte jamais. Désactiver l'option efface le mot de
  passe et cette clé. L'arbitrage est assumé — une sauvegarde que vous seul pouvez ouvrir, et qui
  reste utilisable le jour où le téléphone n'est plus là. `SECURITY.md` l'énonce en entier.
- **Ouvrir un lieu sur la carte** : si vous saisissez des coordonnées GPS sur un événement et que
  vous touchez le repère, l'application transmet **ces coordonnées et le libellé de l'événement** à
  l'application de cartes de votre téléphone. Rien d'autre n'est transmis, et rien ne part si vous
  ne touchez pas le repère. Ce que cette application de cartes fait ensuite de ces informations
  relève de sa propre politique de confidentialité.
- **Son de rappel** : vous choisissez une sonnerie parmi celles **déjà enregistrées sur l'appareil**,
  via le sélecteur système. L'application mémorise l'identifiant de la sonnerie choisie et ne prend
  **aucune autorisation d'accès persistante** sur vos fichiers — elle ne déclare ni
  `READ_MEDIA_AUDIO` ni `READ_EXTERNAL_STORAGE`, elle ne pourrait donc pas en détenir. Si la sonnerie
  devient illisible, le son par défaut du système est utilisé plutôt que le silence.

  *(Ce paragraphe affirmait auparavant que l'application « conserve l'autorisation de lire » un
  fichier audio choisi. C'était faux des deux côtés : le sélecteur est celui des sonneries système et
  non un sélecteur de fichiers, et `takePersistableUriPermission` n'apparaît nulle part dans
  l'application — le seul code voisin **relâche** une autorisation qu'une version antérieure aurait
  pu prendre. Une politique qui se réclame d'être exhaustive doit se corriger à voix haute quand elle
  ne l'est pas.)*

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
