# Journal des modifications

Toutes les versions notables d'Agenda Tech. Format inspiré de [Keep a Changelog](https://keepachangelog.com/fr/1.1.0/) ;
versions selon [SemVer](https://semver.org/lang/fr/).

## [1.1.1] — 2026-09-15

Two fixes that matter to anyone on 1.1.0. One was found by testing on a phone, the other by an
external code review; both were checked against the code and on a device before being fixed.

### Fixed

- **With the app lock on, restoring a backup stopped after the PIN.** Opening the file picker
  locked the app, and unlocking rebuilt the navigation on the month view, so the password dialog
  never appeared. The app no longer asks for the PIN after a file picker it opened itself, and
  unlocking always returns to the screen you left — an event being typed survives a phone call.
  The lock still applies when you leave the app, when the screen turns off while a picker is open,
  when you come back through a notification, the widget or the app icon, and when you come back more
  than 3 minutes later.
- **Android 8 to 11: a key store that briefly stopped responding could erase the agenda.** On those
  versions the system reports a key it cannot reach as missing. The app then created a new key,
  which destroyed the real one: the agenda was reset, or the PIN could never be verified again. A
  key is now never created while reading. The app refuses to open, erases nothing, and says to
  restart the phone.

### Changed

- Build toolchain: Android Gradle Plugin 9.4, Gradle 9.7.1, Kotlin 2.4.10, compileSdk 37 (target
  unchanged, 35). SQLCipher 4.19, checked by opening a 1.1.0 database in place.

### Français

- **Avec le verrouillage par code PIN, la restauration s'arrêtait après le code.** Ouvrir le
  sélecteur de fichier verrouillait l'application, et le déverrouillage ramenait sur le mois : la
  fenêtre du mot de passe ne s'ouvrait jamais. L'application ne redemande plus le code après un
  sélecteur qu'elle a ouvert, et le déverrouillage ramène toujours à l'écran quitté. Le verrou
  s'applique toujours en quittant l'application, si l'écran s'éteint pendant un sélecteur, au retour
  par une notification, le widget ou l'icône, et au retour après plus de 3 minutes.
- **Android 8 à 11 : un stockage de clés muet un instant pouvait effacer l'agenda.** La clé est
  désormais toujours relue, jamais recréée : l'application refuse d'ouvrir sans rien effacer et
  invite à redémarrer le téléphone.
- Chaîne de build : AGP 9.4, Gradle 9.7.1, Kotlin 2.4.10, compileSdk 37 ; SQLCipher 4.19.

## [1.1.0] — 2026-09-11

Trois fonctionnalités nouvelles, et l'audit global le plus profond que l'application ait reçu :
20 constats corrigés, puis 6 déviations de cohérence, chacune revérifiée dans le code avant
correction.

### Ajouté

- **Sauvegarde chiffrée automatique.** Une fois par semaine, dans le dossier de votre choix, sans
  rien faire. Le mot de passe est conservé dans le magasin sécurisé du téléphone.
- **Événements d'anniversaire.** L'âge se déduit de l'année de naissance et se met à jour tout
  seul : rien à ressaisir chaque année.
- **Dupliquer un événement** en une tape, depuis l'éditeur.

### Corrigé

- **Un `.ics` « toute la journée » sans `DTEND` devenait invisible.** La RFC 5545 rend `DTEND`
  facultatif et admet `DURATION` à sa place ; l'import donnait à ces événements une durée nulle,
  et tous les filtres par jour les écartaient. L'événement était bien en base, mais introuvable
  dans les vues mois, jour et semaine. Mesuré sur appareil, avec contrôle négatif.
- **Les rappels ne survivaient pas à un « forcer l'arrêt »** jusqu'au redémarrage du téléphone,
  alors que la sauvegarde automatique, elle, était bien ré-armée au lancement.
- **Le widget n'était jamais redessiné après une écriture.** Un événement supprimé y restait
  affiché jusqu'à une demi-heure. Tous les points d'écriture passent désormais par une couture
  unique, et un test refuse à quiconque de redessiner le widget à la main.
- **Un double-tap créait deux événements**, et sur l'écran Calendriers, deux calendriers.
- **Un échec d'enregistrement pouvait passer inaperçu** : le PIN, le calendrier et la suppression
  se refermaient comme si l'écriture avait eu lieu.
- **Un ré-import `.ics` d'un fichier déjà importé** pouvait écraser des lignes lorsque plusieurs
  événements partageaient un `UID`, ce que la RFC 5545 autorise.
- **Supprimer un calendrier laissait ses alarmes armées** : la cascade effaçait les rappels sans
  que rien ne désarme les alarmes déjà posées auprès du système.

### Modifié

- **Les widgets prennent les couleurs du logo** — le bleu et le rouge de son damier, prélevés au
  pixel — et des angles arrondis qui fonctionnent dès Android 8, non plus seulement à partir
  d'Android 12. La date sur le bleu, les événements sur le rouge.
- **L'import `.ics` affiche enfin qu'il travaille**, au lieu de laisser l'écran immobile.

### Sécurité

- **Aucune vulnérabilité trouvée.** La promesse « aucune permission Internet » est désormais
  vérifiée **au niveau du noyau** sur appareil : le processus n'appartient pas au groupe
  `AID_INET` (gid 3003), sans lequel l'ouverture d'un socket réseau échoue quoi que le code
  tente. Mesuré avec un témoin positif dans la même session.
- La CI construit maintenant la variante **release minifiée** : R8 n'était exercé par aucun job,
  donc aucune règle `keep` manquante n'aurait été vue avant la publication. CodeQL a été ajouté.

## [1.0.3] — 2026-08-14

Aucun changement fonctionnel. **La release qui rend le binaire analysable sans réserve.**

### Corrigé

- **L'APK transportait un bloc destiné à la console Google Play.** AGP glissait dans le bloc de
  signature une liste chiffrée de nos dépendances (« Dependency metadata »), à destination d'un
  magasin sur lequel Agenda Tech n'est pas publiée. Le scanner F-Droid le refuse, et un blob
  illisible n'a rien à faire dans un binaire dont l'intérêt est précisément d'être vérifiable.
  `dependenciesInfo { includeInApk = false }` le supprime. Le défaut ne pouvait pas apparaître
  plus tôt : tant que `Binaries:` n'était pas déclaré, F-Droid n'analysait que l'APK **non signé**
  qu'il reconstruisait lui-même, où le bloc de signature n'existe pas.

## [1.0.2] — 2026-08-14

Aucun changement fonctionnel. **La release qui rend la build reproductible.**

### Corrigé

- **Deux builds de la même source ne donnaient pas le même fichier.** Le plugin `foojay-resolver`
  de `settings.gradle.kts` changeait `classes.dex` — R8 renommait autrement, +7 `field_ids` — ainsi
  que le profil ART. Comme la recette F-Droid le retirait par `sed` alors que la release publiée
  était construite avec, les deux binaires ne pouvaient structurellement pas coïncider. Le plugin
  est retiré à la source. C'est ce qui permet à F-Droid de distribuer l'APK que **nous** avons
  signé plutôt que de le resigner avec sa propre clé : l'application installée depuis l'un ou
  l'autre canal reste la même, et continue de se mettre à jour.

## [1.0.1] — 2026-08-14

Aucun changement de code. **Uniquement la description affichée par le magasin.**

### Corrigé

- **La description du magasin s'affichait en Markdown brut.** F-Droid ne met pas en forme le
  Markdown : les titres apparaissaient tels quels, soulignements « === » compris. La description
  est réécrite dans le HTML restreint que F-Droid rend réellement (`<b>`, `<ul>`, `<li>`).
- Plus aucune mention de Google dans la description.
- Une release à part entière est nécessaire, malgré l'absence de changement de code : F-Droid lit
  les métadonnées fastlane dans l'arbre du commit référencé par l'entrée de build, pas au `HEAD`.

## [1.0.0] — 2026-08-08

**Première version stable**, au terme de cinq passes d'audit (base et migrations, import `.ics`,
crypto de sauvegarde, alarmes, widget et notifications, interactions), de quatre relectures externes
croisées, de `detekt` ramené de 520 constats à 0 **sans baseline**, et de la première exécution de
la CI de l'histoire du dépôt.

### Sécurité

- **Le widget continuait d'afficher les titres après l'activation du verrou.** Il les masque
  désormais à l'instant où le verrou est activé, sans attendre le rafraîchissement suivant.
- L'import `.ics` appliquait son plafond **par agenda** et non globalement, et tronquait sans le
  dire.

### Corrigé

- **Les rappels n'étaient pas réarmés de façon fiable après un redémarrage du téléphone.** Le
  correctif a demandé plusieurs passes : une version intermédiaire, écrite le même soir, effaçait
  des événements qu'elle était censée protéger. Les deux défauts sont corrigés et couverts par des
  tests.
- **Une sauvegarde écrite par une version plus récente était reniée comme « pas une sauvegarde ».**
  Le message poussait à supprimer un fichier parfaitement valide. Il indique maintenant qu'il faut
  mettre l'application à jour — et surtout ne pas effacer le fichier.
- L'export d'une sauvegarde pouvait échouer **en silence**.
- La restauration annonçait un échec alors qu'elle était encore en train de remplacer l'agenda.
- L'icône de l'écran de démarrage était découpée en cercle ; elle retrouve ses coins arrondis.

## [0.5.4] — 2026-08-01

Release de **sécurité**.

### Sécurité

- **Le déverrouillage biométrique n'était pas adossé au coffre matériel.** L'agenda s'ouvrait sur
  la seule parole de l'application, sans que l'AndroidKeyStore ait à constater que l'appareil avait
  réellement authentifié l'empreinte. La clé de déverrouillage est désormais liée à
  l'authentification : sans elle, rien ne s'ouvre.
- **Si la biométrie enregistrée sur l'appareil change, la clé est perdue pour de bon.** Le
  déverrouillage biométrique se désactive alors de lui-même et le dit, au lieu de laisser un bouton
  qui échoue à chaque fois. Le code PIN reste disponible.

### Corrigé

- Un agenda importé de très grande taille pouvait figer l'affichage.
- Import `.ics` : une règle de répétition malformée faisait perdre sa récurrence à l'événement.

## [0.5.3] — 2026-08-01

Release de **sécurité**. Dix correctifs, dont un qui rendait l'application inutilisable sans recours.

### Sécurité

- **Un fichier `.ics` piégé pouvait faire planter l'agenda en boucle dès le lancement**, sans aucun
  moyen de supprimer l'événement fautif — l'application se fermait avant d'avoir affiché de quoi
  l'atteindre. Corrigé, y compris pour les événements **déjà enregistrés** avant la mise à jour.
- **Un événement importé pouvait glisser de fausses lignes dans les `.ics` que vous exportez**
  (injection de champs). Les valeurs sont désormais échappées à l'écriture.
- **Biométrie : seuls les capteurs de Classe 3 sont acceptés.** Une photo ne suffit plus à
  déverrouiller. Le code PIN reste disponible en toutes circonstances.

### Corrigé

- Un rendez-vous annulé réapparaissait après un import `.ics`.

## [0.5.2] — 2026-07-31

Release de **sécurité**. Trois défauts trouvés par audit, dont un critique présent depuis le tout
premier commit.

### Sécurité

- **La base n'était pas réellement protégée : elle était chiffrée avec une clé nulle.** La vraie
  passphrase — 32 octets aléatoires, scellés sous l'AndroidKeyStore — était bien générée et bien
  stockée, mais jamais utilisée. Le tableau qui la portait était effacé de la mémoire juste après
  la construction de la base, or Room n'ouvre réellement le fichier qu'à la première requête :
  quand SQLCipher réclamait enfin la clé, il ne recevait que des zéros. Le fichier `agendatech.db`
  était donc déchiffrable par quiconque parvenait à l'extraire de l'appareil, sans avoir à toucher
  au coffre matériel. Corrigé, et **les bases existantes sont rechiffrées automatiquement au
  premier lancement**, sans perte : l'agenda est copié de côté avant l'opération et remis en place
  si quoi que ce soit échoue. Le rechiffrement s'exécute une seule fois, en arrière-plan.
- **Le compteur anti-force-brute du code PIN se réinitialisait trop facilement.** Il ne vivait
  qu'en mémoire : fermer l'application de force — quelques appuis dans les réglages, aucun outil,
  aucun privilège — remettait le compteur à zéro et redonnait cinq essais. L'escalade des délais ne
  servait donc à rien face à quelqu'un de patient. Le compteur est désormais conservé d'un
  lancement à l'autre. Le plafond d'une minute est inchangé, un blocage ne peut pas s'éterniser.
- **L'application se figeait pendant la sauvegarde et la restauration.** Le calcul cryptographique
  qui protège un fichier `.atbak` (volontairement lent, c'est ce qui le rend résistant) tournait
  sur le fil d'exécution de l'interface. D'où un gel d'environ une seconde à chaque export — et
  bien plus long face à un fichier hostile, au point de pouvoir faire tuer l'application par
  Android. Ces opérations sont passées en arrière-plan.

### Corrigé

- **Démarrage plus fluide.** La base de données était ouverte sur le fil de l'interface au
  lancement de l'application, ce qui pouvait la faire saccader — d'autant plus avec le
  rechiffrement ci-dessus. L'ouverture se fait désormais en arrière-plan.

## [0.5.1] — 2026-07-17

### Corrigé

- **La sonnerie choisie pour les rappels est enfin prise en compte.** Choisir une sonnerie ne
  changeait rien : le rappel continuait de jouer le son par défaut. En cause, une subtilité
  Android — la configuration son/vibration/écran-verrouillé d'un canal de notification est figée à
  sa création, et supprimer puis recréer le canal sous le même identifiant restaure en réalité
  l'ancienne configuration. Chaque réglage vit désormais dans un canal dédié, si bien qu'un
  changement crée un nouveau canal réellement appliqué. Le vibreur et l'affichage sur écran
  verrouillé, touchés par le même défaut, sont corrigés du même coup.

### Modifié

- **Retrait du choix d'un fichier audio (musique) comme sonnerie de rappel.** Le sélecteur de
  sonnerie du système reste — il propose les sons de notification de l'appareil.

## [0.5.0] — 2026-07-15

### Ajouté

- **Bouton « Répéter 10 min » sur les rappels.** Le moment où un rappel sonne est précisément
  celui où vous êtes occupé : sans ce bouton, le choix était d'agir tout de suite ou de perdre le
  rappel. Un appui ferme la notification et la représente dix minutes plus tard — sans toucher aux
  rappels suivants de la série.
- **Proposition de restauration sur un agenda vide.** La sauvegarde chiffrée n'aidait que ceux qui
  savaient déjà qu'elle existe : après un changement de téléphone, il fallait la deviner et aller la
  chercher dans les réglages. Elle est désormais proposée là où vous atterrissez — et une seule
  fois : répondre non est une réponse.
- **Rappel de sauvegarde.** Vos événements ne vivent que sur ce téléphone : c'est le principe de
  l'app, et cela veut dire qu'un téléphone perdu sans sauvegarde perd tout. L'app vous le rappelle
  désormais — mais rarement, et seulement quand c'est mérité : jamais avant que vous ayez quelque
  chose à perdre, jamais si votre agenda n'a pas changé depuis votre dernière sauvegarde, et
  « Plus tard » vaut deux semaines de silence.
  L'app ne prétend jamais savoir si vous *avez* une sauvegarde : elle sait seulement quand vous avez
  utilisé l'export. Le fichier a pu être supprimé, ou vos données protégées autrement.

### Technique

- 211 tests unitaires (0 échec), lint sans erreur, parité français/anglais complète.
- `EventEditorViewModel` — le chemin de sauvegarde le plus emprunté de l'app — est enfin couvert par
  des tests. C'est le fichier où vivait le plantage corrigé en v0.4.1.

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

[0.5.0]: https://github.com/gitubpatrice/AGENDA-TECH/releases/tag/v0.5.0
[0.4.1]: https://github.com/gitubpatrice/AGENDA-TECH/releases/tag/v0.4.1
[0.4.0]: https://github.com/gitubpatrice/AGENDA-TECH/releases/tag/v0.4.0
[0.3.0]: https://github.com/gitubpatrice/AGENDA-TECH/releases/tag/v0.3.0
