# Journal des modifications

Toutes les versions notables d'Agenda Tech. Format inspiré de [Keep a Changelog](https://keepachangelog.com/fr/1.1.0/) ;
versions selon [SemVer](https://semver.org/lang/fr/).

## [0.4.1] — 2026-07-15

### Corrigé

- **L'application ne plante plus quand vous enregistrez un événement modifié qui a un rappel.**
  C'était un plantage systématique, présent depuis les toutes premières versions et jusqu'à la
  v0.4.0 incluse. Il ne se déclenchait que si l'événement portait au moins un rappel — d'où le fait
  qu'il ait pu passer inaperçu si longtemps.
  En interne : Room signale une mise à jour par `-1` au lieu de l'identifiant de la ligne, et cette
  valeur servait ensuite à rattacher les rappels — aucun événement ne porte l'identifiant `-1`, donc
  la base refusait l'écriture. Corrigé à la source : les repositories tiennent désormais la promesse
  de leur contrat (« renvoie l'identifiant de l'événement »), et cette valeur ne peut plus s'échapper.

## [0.4.0] — 2026-07-15

### Ajouté

- **Sauvegarde chiffrée `.atbak`** — export et restauration de **tout** l'agenda (calendriers,
  événements, récurrences, lieux, rappels) dans un fichier protégé par mot de passe, à ranger où
  vous voulez, y compris un cloud : il reste illisible sans le mot de passe.
  PBKDF2-HMAC-SHA256 600 000 itérations + AES-256-GCM, en-tête authentifié (une tentative
  d'abaisser le coût de dérivation rend le fichier invalide). Format documenté dans
  [SECURITY.md](SECURITY.md). Le mot de passe n'est stocké nulle part : oublié, le fichier est
  définitivement illisible.
  Réglages → Confidentialité → « Sauvegarde chiffrée ».
- **Recherche** dans tout l'agenda — titre, description, lieu, adresse, ville. Insensible aux
  accents et à la casse : « reunion » trouve « Réunion », et la correspondance fonctionne au milieu
  d'un mot. Résultats à venir en premier, puis passés. Loupe dans la barre du mois.

### Corrigé

- **La permission `ACCESS_NETWORK_STATE` a disparu de l'APK.** Elle y était en v0.3.0 — non pas
  déclarée par l'app, mais injectée par une bibliothèque (`androidx.work`, tirée par les widgets).
  Elle ne donnait pas l'accès à Internet, mais elle apparaissait dans la liste des permissions et
  contredisait la promesse « zéro réseau ». L'app ne demande désormais plus **aucune** permission
  réseau, ce qui est vérifiable dans les réglages Android.
- **L'import du calendrier de l'appareil dit maintenant clairement que c'est une copie, pas une
  synchronisation** — un événement créé dans Agenda Tech ne remonte pas vers la source, et cela
  s'affiche au moment du choix plutôt que se découvrir des mois plus tard.
- **Déplacer une seule occurrence d'un événement récurrent** écrit l'occurrence déplacée et
  l'exclusion de sa date d'origine **en une seule opération**. Interrompue entre les deux, l'ancienne
  version pouvait laisser un rappel sonner à la date abandonnée et exporter un `.ics` déclarant une
  occurrence déjà déplacée.

### Modifié

- Le `versionCode` est figé dans `version.properties` au lieu d'être dérivé du nombre de commits
  git : un rebase le faisait baisser, et Android refuse alors l'installation sans explication.
- Le build de debug s'appelle « Agenda Tech (debug) » — il n'est plus confondable avec la version
  publiée, notamment dans le sélecteur de widgets.

### Technique

- 182 tests unitaires (0 échec), lint sans erreur, parité français/anglais complète.

## [0.3.0] — 2026-07-15

Première version publique. Agenda local chiffré : vues Mois/Semaine/Jour/Agenda, récurrences
RFC 5545 avec modification par occurrence, rappels par alarmes exactes, import/export `.ics`,
import du calendrier de l'appareil (lecture seule), verrou PIN/biométrie, widgets, thème sombre.

[0.4.1]: https://github.com/gitubpatrice/AGENDA-TECH/releases/tag/v0.4.1
[0.4.0]: https://github.com/gitubpatrice/AGENDA-TECH/releases/tag/v0.4.0
[0.3.0]: https://github.com/gitubpatrice/AGENDA-TECH/releases/tag/v0.3.0
