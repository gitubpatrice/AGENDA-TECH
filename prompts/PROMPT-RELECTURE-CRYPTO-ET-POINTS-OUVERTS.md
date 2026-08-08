# Relecture — la crypto de sauvegarde, et trois points que je n'ai PAS su fermer

Tu relis du code Android/Kotlin d'**Agenda Tech** : agenda 100 % local, base SQLCipher,
`allowBackup=false` — **la base est le SEUL exemplaire de l'agenda de l'utilisateur**. Un `.atbak` est
son unique moyen de sauvegarde, et son mot de passe n'est stocké nulle part : s'il est perdu, le
fichier est définitivement illisible, y compris pour nous.

Ce dépôt a subi trois passes d'audit successives. **Chaque passe a trouvé des défauts introduits par
les correctifs de la précédente**, dont deux plus graves que ceux qu'ils corrigeaient. Le motif
dominant : *un correctif traite sa moitié constatée et laisse la moitié conséquente*. Le second : des
**commentaires qui affirment ce que le code ne garantit pas** — ce dépôt en a produit une douzaine.

## Partie 1 — la crypto de sauvegarde `.atbak` (JAMAIS auditée en profondeur)

C'est le morceau le plus sensible du dépôt et le seul qui n'a reçu aucune plongée dédiée. Fichiers
entiers ci-dessous.

Cherche, précisément :

1. **`BackupEnvelope`** — format, en-tête, AAD, dérivation PBKDF2, bornes sur le nombre d'itérations
   annoncé par le fichier, nonce/IV, distinction « mauvais mot de passe » / « fichier corrompu ».
   Une confusion des deux est un oracle. Le nonce peut-il se répéter ? L'en-tête est-il intégralement
   authentifié, ou seulement en partie ? Un attaquant peut-il tronquer, rejouer, ou faire dériver une
   clé coûteuse à volonté ?
2. **`AeadCipher` / `KeystoreManager`** — usage d'AES-GCM, gestion de l'IV, réutilisation de clé,
   `allowUserIv`. Un IV réutilisé sous la même clé casse GCM ; est-ce possible ici ?
3. **`PinHasher`** — comparaison à temps constant, sel, paramètres.
4. **La zéroïsation** (`wipe`) — les tableaux sensibles sont-ils tous effacés, sur **tous** les chemins
   d'erreur ? Un `CharArray` de mot de passe survit-il quelque part (copie implicite, `String`
   intermédiaire, log) ?
5. **Le format lui-même** : une version future peut-elle lire un fichier ancien, et refuser proprement
   un fichier plus récent ?

## Partie 2 — trois points que je n'ai PAS su fermer

Dis-moi si mon raisonnement tient, ou ce que je rate. **Ne me ménage pas** : je préfère apprendre
maintenant qu'après publication.

### (a) Un receiver exporté que je n'arrive pas à rendre inatteignable

`ExactAlarmPermissionReceiver` écoute `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`. J'ai
**mesuré** que cette diffusion n'est PAS protégée sur API 26-30 (`am broadcast` depuis uid 2000
réussit, alors que `BOOT_COMPLETED` échoue avec `SecurityException` — même canal, même émetteur).

J'ai mis une garde dans `onReceive`. Un relecteur m'a montré qu'elle ne ferme presque rien :
l'injection Hilt s'exécute avant, et surtout **délivrer la diffusion démarre le processus**, où
`MainApplication.onCreate` ouvre la base sans condition (SQLCipher + Keystore + AES-GCM). Le vrai
correctif serait `android:enabled="false"` + `setComponentEnabledSetting` au démarrage.

**Je ne l'ai pas fait** : cela ajoute une écriture au gestionnaire de paquets à chaque démarrage à
froid, pour défendre contre une nuisance (CPU/batterie, aucune divulgation) sur API 26-30 seulement.
**Est-ce le bon arbitrage ?** Et y a-t-il un coût que je ne vois pas à `setComponentEnabledSetting`
(réinitialisation à la mise à jour, interaction avec les widgets, F-Droid) ?

### (b) Un diagnostic écrit mais illisible là où il sert

`MigrationTrace` note ce qu'une migration destructive a écrasé. Elle écrit en release, mais ne peut
être relue qu'en debug (`run-as`). Autrement dit : elle sert exactement là où elle ne peut pas être
lue. J'ai corrigé le commentaire pour le dire, sans corriger la chose.

**Faut-il l'exposer dans l'écran « À propos », la conditionner au build de debug, ou la supprimer ?**
Argument contre l'exposer : c'est du stockage en clair hors du conteneur chiffré, et un nom de fuseau
est une donnée dérivée de l'agenda.

### (c) Un aller-retour destructif que j'ai jugé acceptable

Dans l'éditeur, cocher « journée entière » remplace le fuseau de l'événement par celui de l'appareil
(les instants d'un événement « journée entière » SONT minuit-à-minuit dans ce fuseau). Décocher ne
restaure pas le fuseau d'origine.

**J'ai jugé que ce n'est pas un défaut** : cocher « journée entière » détruit déjà l'heure, donc
perdre le fuseau avec elle est cohérent, et revenir en arrière ne peut pas restaurer une information
que l'utilisateur a lui-même effacée. Une colonne dédiée serait la seule façon de la garder.

**Ce raisonnement tient-il ?** Ou y a-t-il un scénario où l'utilisateur perd quelque chose qu'il n'a
pas consciemment jeté ?

---

## Ce que j'attends

Pour chaque constat : `fichier:ligne`, **scénario concret**, sévérité, et **CONFIRMÉ ou PROBABLE**.
Si un point est sain, dis-le explicitement — « rien à signaler sur X » est une information.

Ne propose jamais de changer la clé de signature, de toucher au keystore, ni de renommer le package.
Ne signale pas de préférences de style.
