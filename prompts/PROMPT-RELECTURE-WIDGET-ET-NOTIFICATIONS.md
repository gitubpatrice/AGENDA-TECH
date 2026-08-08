# Relecture — les deux surfaces qui montrent l'agenda EN DEHORS de l'application

Tu relis du code Android/Kotlin d'**Agenda Tech** : agenda 100 % local, base SQLCipher,
`allowBackup=false`, aucune permission Internet. L'application est protégée par un verrou (PIN ou
biométrie Classe 3) et par `FLAG_SECURE`.

**Ces deux surfaces contournent tout cela par construction** : le widget Glance dessine sur l'écran
d'accueil et le notifieur affiche sur l'écran de verrouillage. Elles n'ont **jamais reçu d'audit** —
trois passes se sont concentrées sur la base, l'import `.ics` et la crypto.

Contexte de fiabilité : ce dépôt a subi trois passes successives, et **chaque passe a trouvé des
défauts introduits par les correctifs de la précédente**. Le motif dominant : *un correctif traite sa
moitié constatée et laisse la moitié conséquente*. Le second : des **commentaires qui affirment ce que
le code ne garantit pas**. Traite ce code comme suspect, pas comme acquis.

## Ce que je te demande de chercher, par ordre d'importance

### 1. Fuite de contenu d'agenda hors de la coquille protégée — le point central

- **Le widget** : que dessine-t-il exactement ? Titres d'événements ? Lieux ? Notes ? L'écran
  d'accueil est visible par-dessus l'épaule, et une capture d'écran du lanceur l'emporte.
  `FLAG_SECURE` ne s'applique pas à lui. **Le widget respecte-t-il le verrou de l'application ?** Si
  l'agenda est verrouillé par PIN, le widget devrait-il continuer d'afficher les titres ? C'est la
  question que je n'ai pas tranchée.
- **La notification de rappel** : que met-elle dans le titre et le texte ? Que voit-on **sur l'écran
  de verrouillage**, avant déverrouillage ? Regarde `setVisibility`, `setPublicVersion`, le canal et
  son importance. Une notification `VISIBILITY_PRIVATE` sans `publicVersion` explicite : que montre
  réellement Android ?
- Les deux lisent la base **chiffrée** : d'où viennent leurs données, et cette lecture peut-elle
  survenir alors que l'application est censée être verrouillée ?

### 2. Les `PendingIntent`

- Drapeaux : `FLAG_IMMUTABLE` partout ? Un `PendingIntent` mutable laisse un tiers réécrire l'intent.
- `requestCode` : deux rappels différents peuvent-ils entrer en collision et écraser le
  `PendingIntent` de l'autre (donc : le mauvais événement s'ouvre, ou un rappel disparaît) ?
- Les intents véhiculent-ils des identifiants d'événement ou du contenu ? Un tiers peut-il fabriquer
  un intent vers ces composants et faire afficher, annuler ou reprogrammer quelque chose ?

### 3. Les receivers exportés du widget

`AgendaWidgetReceiver` et `AgendaIconWidgetReceiver` sont `exported="true"` — le lanceur en a besoin.
Quelles actions acceptent-ils ? Une application tierce peut-elle leur envoyer un intent explicite et
déclencher un travail (ouverture de base SQLCipher, IPC Keystore, passe complète) en boucle ?

### 4. Robustesse — le widget est un processus qui peut démarrer sans l'application

- Que se passe-t-il si la base **refuse de s'ouvrir** (Keystore transitoirement indisponible) pendant
  que le widget se rafraîchit ? Une exception dans un `GlanceAppWidget` peut-elle boucler ?
- Le widget peut-il rester affiché avec des données d'un agenda **remplacé** par une restauration, ou
  d'événements supprimés ?
- Y a-t-il du travail long, de l'I/O ou une ouverture de base sur le **thread principal** ?

### 5. Ce que le code affirme

Signale chaque commentaire ou KDoc de ces fichiers qui promet une propriété que le code ne garantit
pas. C'est la famille de défauts la plus productive sur ce dépôt.

---

## Ce que j'attends

Pour chaque constat : `fichier:ligne`, **scénario concret** (qui fait quoi, et que voit ou obtient
l'attaquant / l'utilisateur), sévérité, et **CONFIRMÉ ou PROBABLE**.

Si un point est sain, dis-le explicitement — « rien à signaler sur X » est une information que
j'utilise.

Ne propose jamais de changer la clé de signature, de toucher au keystore, ni de renommer le package.
Ne signale pas de préférences de style. Ne propose pas d'ajouter une permission réseau : l'absence de
permission Internet est une promesse publique de l'application.
