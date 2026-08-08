# Relecture contradictoire — Agenda Tech, lots C, D et E

Tu relis **trois lots de correctifs écrits par un autre assistant**. Sur ce dépôt précisément, les
correctifs ont produit **8 nouveaux défauts en 2 passes de revue** lors de la v0.5.4, et sur une
application sœur un correctif a introduit un nouveau défaut **9 fois d'affilée**. Sur cette branche,
le correctif du défaut CRITIQUE ne couvrait que **la moitié du trajet cryptographique** — trouvé par
deux relecteurs indépendants, pas par son auteur. Ton rôle est de trouver ce que CES correctifs
cassent, manquent, ou prétendent faire sans le faire.

**Agenda Tech** : agenda Android natif (Kotlin, Compose, Hilt, Room + SQLCipher), 100 % local,
chiffré, **zéro permission réseau**. `allowBackup=false` : la base est le **SEUL exemplaire** de
l'agenda de l'utilisateur sur l'appareil. Fichiers entiers ci-dessous ; tu n'as pas accès au dépôt.

---

## Ce qui a été corrigé

### Lot C — deux `BroadcastReceiver` construisaient la base sur le thread principal

`BootReceiver` et `ReminderReceiver` injectaient `ReminderScheduler` par champ Hilt
(`@Inject lateinit var`). L'injection Hilt d'un receiver s'exécute **avant** le corps de `onReceive`,
donc la construction du graphe — et avec elle l'ouverture de la base SQLCipher, dérivation de clé
comprise — se produisait sur le thread principal, avant tout `goAsync()`. Corrigé en injectant un
`javax.inject.Provider<ReminderScheduler>` et en appelant `.get()` dans la coroutine.
`ReminderReceiver` filtre désormais l'action via `ReminderActionHandler.handles(action)`, déplacé en
`companion`, pour qu'un filtrage ne construise pas le graphe.

### Lot D — le plafond d'expansion n'était branché que sur 1 des 3 chemins

`ExpansionBudget` bornait le rendu des vues mais ni la recherche ni la planification des rappels.
Ajouté aux deux entrées manquantes (`nextOccurrenceStart`, `lastOccurrenceStartBefore`), avec un
budget **partagé** par passe pour `rescheduleAll()` (le plafond par événement ne dit rien du nombre
d'événements). Le planificateur lit désormais aussi les overrides vivants. Et
`BackupViewModel.restore` échoue fermé au lieu de continuer avec une liste de rappels vide.

### Lot E — l'import `.ics` (à relire en priorité, c'est le plus gros)

- **F3** : l'importateur stockait la chaîne `TZID` du fichier telle quelle tout en calculant l'instant
  avec le fuseau de l'**appareil** quand il ne savait pas la résoudre. Tout `.ics` d'Outlook nomme ses
  fuseaux à la manière de Windows (`Romance Standard Time`). Conséquences : décalage d'un offset
  entier à chaque aller-retour export/import, dérive DST de toute série importée, et injection de
  ligne iCalendar par un `TZID` non échappé à l'export. Corrigé par un résolveur unique
  (`core/time/TimeZones`) branché sur les **quatre** points d'ingestion, plus une **migration Room
  v5 → v6** qui répare les lignes déjà en base.
- **F4** : la lecture `.ics` était bornée par `ParcelFileDescriptor.statSize`, une taille déclarée par
  le fournisseur de documents. Remplacé par une lecture bornée (`core/io/BoundedRead`), partagée avec
  le jumeau `BackupViewModel.readFile`.
- **F8** : les lignes `EXDATE` répétées s'écrasaient, seule la dernière survivait.
- **F14** : le pliage comptait des `Char` UTF-16, donc coupait les paires de substitution (emoji).

---

## Ce que je te demande

Cherche **des défauts précis**, pas des généralités. Pour chacun : `fichier:ligne`, le scénario
concret d'échec (quel geste de l'utilisateur, quel fichier, quel appareil), et **CONFIRMÉ** ou
**PROBABLE**.

Interroge en particulier :

1. **La migration v5 → v6.** C'est la seule qui **réécrit des lignes existantes**, sur la base qui est
   le seul exemplaire de l'agenda. Que se passe-t-il si elle échoue à mi-parcours ? Si la base est
   énorme ? Si le fuseau de l'appareil a changé depuis l'import ? Le repli sur
   `ZoneId.systemDefault()` est-il défendable, ou aggrave-t-il quelque chose ? Est-elle idempotente ?
   Le fait qu'elle ne change pas le schéma (même `identityHash` que v5) pose-t-il un problème à Room ?
2. **La table CLDR Windows → IANA** dans `TimeZones`. Un mauvais appariement est un bug de données
   silencieux. Y a-t-il des entrées fausses ? L'ordre de résolution (tzdb → Windows → SHORT_IDS)
   peut-il faire résoudre un nom vers autre chose que ce qu'il désigne ?
3. **`BoundedRead`.** La borne est-elle réellement bornée ? Que fait-elle sur un flux qui ne se
   termine jamais, qui rend 0 sans fin, qui lève en cours de route ?
4. **Le changement de `IcsCodec.decode` en multimap.** Le passage de « la dernière gagne » à « la
   première gagne » casse-t-il un cas légitime ? Les `EXDATE` `distinct()` perdent-elles une
   information ?
5. **Lot C.** Le `Provider` suffit-il réellement à sortir la construction du graphe du thread
   principal, ou reste-t-il un chemin par lequel Hilt construit quelque chose de coûteux avant
   `goAsync()` ? `goAsync()` est-il correctement relâché sur tous les chemins d'erreur ?
6. **Lot D.** Un budget partagé peut-il tronquer les rappels d'un utilisateur normal ? La troncature
   est-elle visible ? `computeNextFire` peut-il rendre un instant faux quand le budget s'épuise ?
7. **Les commentaires qui mentent.** Ce dépôt en a déjà produit six. Un commentaire ou un KDoc qui
   affirme quelque chose que le code ne fait pas est un défaut à part entière : signale-le.
8. **Les tests.** Lesquels passeraient encore si on retirait le correctif qu'ils sont censés
   protéger ? Deux de ces tests étaient déjà faux et l'auteur les a corrigés après sabotage — y en
   a-t-il d'autres ?

Ne signale pas de préférences de style. Ne propose pas de renommer le keystore, la signature ou le
package. Si tu ne trouves rien sur un point, dis-le explicitement — « rien à signaler » sur un point
précis est une information utile ; un rapport vide ne l'est pas.
