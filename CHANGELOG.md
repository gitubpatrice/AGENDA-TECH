# Journal des modifications

Toutes les versions notables d'Agenda Tech. Format inspiré de [Keep a Changelog](https://keepachangelog.com/fr/1.1.0/) ;
versions selon [SemVer](https://semver.org/lang/fr/).

## [Unreleased]

### Added

- **Three ways to show the month**, chosen from the top bar or by pinching the month: *dots*, as
  before; *titles* inside the grid, where a lone event uses every free line of its day instead of
  being cut after a few letters; and *one row per day*, with every title whole and the free days kept
  in place. In the titles view a tap opens the day over the month, and a swipe moves to the next day
  without closing it. Asked by a user who could not see what each day held without tapping it.
- **Long-press a day to create an event on it**, in all three views, and **tap the month name to jump
  to any date**.
- **The month speaks to screen readers**: each day announces its full date and how many events it
  holds, instead of a bare number.
- **German, Italian and Spanish**, alongside English and French: every screen, notification and
  widget, the F-Droid listing, the privacy policy and the new terms of use. Machine-assisted and not
  yet reviewed by a native speaker — corrections welcome, see `TRANSLATING.md`.
- **Language entry in Settings** (Android 13 and later): it opens Android's per-app language screen.
  Below Android 13 the app follows the phone's language, as before.
- **Terms of use** (`TERMS.md`), linked from About. For them and for the privacy policy, the French
  version prevails; the policy now names its publisher and a contact address.

### Changed

- **The main action of each screen is a filled button in the app's blue**: the "+" button, Save
  (green until now), the backup banner, "See updates" in About.
- **The selected day is outlined** rather than filled, in the three month views.
- **Swiping to the next month slides in a month already filled in**: the neighbouring months are
  read with the one shown.
- **The splash logo has rounded corners**; the splash's circle clipped them.

### Fixed

- **An all-day event created in another time zone spilled onto two days.** Its days were read on the
  phone's clock: after a journey, or for a calendar file written elsewhere, a holiday showed on two
  days in the month, week and day views, was listed the day before in the agenda and showed a day
  late in the widget. Opening it and saving without a change moved it by a day. Its dates are now read
  in the zone it was created in, everywhere, and its reminders ring at the phone's midnight. Also in
  1.1.1.
- **One day of an all-day series could open ending the day before it began**, when summer time started
  or ended in the series, and saving it stored a day of no length. Its end is now counted the way the
  calendar views count it, and an all-day end before the start is refused. Found by external review.
- **Coming back to the month could show the next one.** After swiping to another month, a visit to
  Settings, the editor or search came back one month further — every time.
- **At the largest text sizes, two-digit day numbers were cut** ("1" for the 12th), and so were
  week numbers.
- **A day with more than four events showed four dots and nothing else**; a "+" now says there are
  more.
- **The first calendar kept the language the app was first opened in.** "Perso" stayed "Perso" on a
  German screen, because the name was written once at first run. It is now shown in the current
  language, on existing installs too, as long as it still has its original name. A calendar you named
  yourself, or one imported from the phone, is never renamed.
- **The French plurals lacked their `many` form** (exact millions: *1 000 000 d'événements*).
- **The F-Droid listing claimed "Argon2-grade PBKDF2".** Backups use PBKDF2-HMAC-SHA256 at 600,000
  iterations, which is not Argon2; the claim is withdrawn.

### Français

- **Trois façons d'afficher le mois**, au choix dans la barre du haut ou en pinçant le mois : les
  *points*, comme avant ; les *titres* dans la grille, où un événement seul occupe toutes les lignes
  libres de sa case au lieu d'être coupé après quelques lettres ; et *une ligne par jour*, titres
  entiers, jours libres conservés. En mode titres, un tap ouvre le jour par-dessus le mois, et un
  glissement passe au jour suivant sans le refermer. Demandé par un utilisateur qui ne voyait pas ce
  que contenait chaque jour sans le toucher.
- **Appui long sur un jour pour y créer un événement**, dans les trois affichages, et **tap sur le nom
  du mois pour aller à n'importe quelle date**.
- **Le mois se lit avec un lecteur d'écran** : chaque jour annonce sa date complète et son nombre
  d'événements, au lieu d'un simple chiffre.
- **L'action principale de chaque écran est un bouton plein, du bleu de l'application** : le « + »,
  Enregistrer (vert jusqu'ici), le bandeau de sauvegarde, « Voir les mises à jour » dans À propos.
- **Le jour sélectionné est entouré** plutôt que surligné, dans les trois affichages.
- **Glisser au mois suivant fait arriver un mois déjà rempli** : les mois voisins sont lus avec celui
  qui est affiché.
- **Le logo de démarrage a ses coins arrondis** ; le cercle de l'écran de démarrage les coupait.
- **Revenir au mois pouvait afficher le suivant.** Après un glissement vers un autre mois, un passage
  par les réglages, l'éditeur ou la recherche ramenait un mois plus loin — à chaque fois.
- **Aux plus grandes tailles de texte, les numéros de jour à deux chiffres étaient coupés** (« 1 » pour
  le 12), les numéros de semaine aussi.
- **Un jour de plus de quatre événements montrait quatre points et rien d'autre** ; un « + » signale
  désormais les suivants.
- **Une journée entière créée dans un autre fuseau horaire débordait sur deux jours.** Ses dates étaient
  lues à l'heure du téléphone : après un voyage, ou pour un fichier d'agenda écrit ailleurs, un jour
  férié s'affichait sur deux jours dans les vues mois, semaine et jour, figurait la veille dans
  l'agenda et un jour trop tard dans le widget. L'ouvrir et l'enregistrer sans rien changer le
  déplaçait d'un jour. Ses dates sont désormais lues partout dans le fuseau où elle a été créée, et ses
  rappels sonnent à minuit, heure du téléphone. Présent aussi dans la 1.1.1.
- **Un jour d'une série « journée entière » pouvait s'ouvrir avec une fin la veille de son début**
  autour d'un changement d'heure, et l'enregistrer créait une journée de durée nulle. Sa fin est
  désormais comptée comme dans les vues du calendrier, et une fin antérieure au début est refusée.
  Trouvé par une relecture externe.
- **Allemand, italien et espagnol**, en plus de l'anglais et du français : tous les écrans, les
  notifications et le widget, la fiche F-Droid, la politique de confidentialité et les nouvelles
  conditions d'utilisation. Traductions assistées par machine, pas encore relues par un locuteur
  natif — corrections bienvenues, voir `TRANSLATING.md`.
- **Entrée « Langue de l'application » dans les réglages** (Android 13 et plus) : elle ouvre l'écran
  Android de langue par application. Sous Android 13, l'application suit la langue du téléphone.
- **Conditions d'utilisation** (`TERMS.fr.md`), accessibles depuis « À propos ». Pour elles comme pour
  la politique de confidentialité, la version française fait foi ; la politique nomme désormais son
  éditeur et une adresse de contact.
- **Le premier calendrier gardait la langue du premier lancement** : « Perso » restait « Perso » sur
  un écran allemand. Il s'affiche désormais dans la langue courante, installations existantes
  comprises, tant qu'il porte son nom d'origine. Un calendrier que vous avez nommé, ou importé du
  téléphone, n'est jamais renommé.
- **Les pluriels français n'avaient pas leur forme `many`** (millions exacts).
- **La fiche F-Droid annonçait « Argon2-grade PBKDF2 ».** Les sauvegardes utilisent
  PBKDF2-HMAC-SHA256 à 600 000 itérations, qui n'est pas Argon2 ; la mention est retirée.

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
