# Relecture — les interactions, les dialogues et les branchements

Tu relis du code Android/Kotlin/Compose d'**Agenda Tech** : agenda 100 % local, base SQLCipher,
verrou applicatif (PIN ou biométrie), `allowBackup=false` — **la base est le seul exemplaire de
l'agenda de l'utilisateur**.

Trois passes d'audit ont couvert la base, l'import `.ics`, la crypto, les alarmes et le widget.
**L'axe interaction n'a jamais été audité** : ce que fait un bouton, ce que fait un dialogue, ce qui
survit à une rotation ou à une mort de processus, et ce qui est branché à quoi.

Contexte de fiabilité, à prendre au sérieux : **chaque passe de cet audit a trouvé des défauts
introduits par les correctifs de la précédente.** Deux familles reviennent sans cesse :

1. **Un réglage annoncé qui n'agit pas, ou pas au moment où on le change.** Exemple trouvé il y a une
   heure : « masquer les titres dans le widget » ne prenait effet qu'au tic suivant de la plateforme,
   soit jusqu'à 30 minutes plus tard — et activer le verrou laissait donc les titres lisibles sur
   l'écran d'accueil pendant tout ce temps, sous un commentaire affirmant le contraire.
2. **Un commentaire ou un KDoc qui affirme ce que le code ne garantit pas.**

Cherche ces deux familles en priorité. Elles sont productives ici.

## Ce que je te demande de chercher

### 1. Les réglages qui ne s'appliquent pas au moment où on les change

`SettingsScreen` / `SettingsViewModel` : pour **chaque** réglage, demande-toi *qui le relit, et
quand*. Thème, début de semaine, numéros de semaine, couleur par défaut, durée par défaut, rappel par
défaut, `FLAG_SECURE`, son et vibration des notifications, écran de verrouillage. Lequel exige un
redémarrage d'écran, une recréation d'objet système ou un rafraîchissement explicite pour être visible
— et ne l'obtient pas ?

### 2. Les dialogues

- Un dialogue peut-il rester ouvert sur un état qui n'existe plus (événement supprimé pendant qu'il
  est affiché, agenda remplacé par une restauration) ?
- Le bouton de confirmation peut-il être actionné **deux fois** — double tap, ou tap pendant que
  l'opération asynchrone tourne — et produire deux écritures, deux suppressions, deux exports ?
- Le retour matériel / le geste retour ferme-t-il proprement, ou laisse-t-il un état pendant
  (`pendingRestoreFile`, `awaitingRestorePassword`, un mot de passe non effacé) ?
- Un dialogue portant un **mot de passe ou un PIN** : que devient la saisie sur rotation, sur mise en
  arrière-plan, sur mort de processus ? Reste-t-elle dans un `String` immuable non effaçable ?

### 3. Rotation, mort de processus, retour arrière

- Quel état est tenu dans le ViewModel, quel état est `remember` sans `rememberSaveable`, et lequel
  des deux aurait dû être l'autre ?
- Un `LaunchedEffect` est-il mal clé — relancé à chaque recomposition, ou au contraire jamais relancé
  quand sa donnée change ?
- Après une mort de processus pendant un dialogue de restauration ou de suppression, l'écran revient-il
  dans un état cohérent, ou dans un état qui promet une opération qui ne peut plus aboutir ?

### 4. Les opérations destructives

Suppression d'un événement, d'un calendrier, restauration qui **remplace tout l'agenda**. Sont-elles
confirmées ? La confirmation dit-elle ce qui va disparaître, et combien ? Peut-on les déclencher
depuis un état périmé (on a listé, l'utilisateur a supprimé ailleurs, on agit sur l'ancien index) ?

### 5. Les branchements

Un écran, une action ou une option atteignable par aucun chemin ; un bouton câblé sur la mauvaise
fonction ; deux entrées de menu vers la même destination ; une navigation qui empile sans fin ; un
`popUpTo` manquant ou de trop.

### 6. L'accessibilité, seulement là où elle change le résultat

`contentDescription` absent sur une icône qui est la seule affordance d'une action, cible tactile
sous 48 dp, texte qui casse à 200 % de taille. Ne fais pas la liste RGAA complète — signale ce qui
rend une action **inatteignable**.

---

## Ce que j'attends

Pour chaque constat : `fichier:ligne`, **scénario concret** (ce que l'utilisateur fait, dans quel
ordre, et ce qu'il obtient), sévérité, et **CONFIRMÉ ou PROBABLE**.

Si un point est sain, dis-le explicitement — « rien à signaler sur X » est une information que
j'utilise.

Ne signale pas de préférences de style ni de renommages. Ne propose pas de réécrire l'architecture.
