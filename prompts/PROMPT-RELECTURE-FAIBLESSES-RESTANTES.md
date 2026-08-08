# Relecture — cherche la faiblesse que quatre passes n'ont pas vue

Tu relis du code Android/Kotlin d'**Agenda Tech** : agenda 100 % local, base SQLCipher, verrou
applicatif, `allowBackup=false`, **aucune permission Internet** (c'est une promesse publique).

Ce dépôt sort de **quatre passes d'audit** : base de données et migrations, import `.ics`, crypto de
sauvegarde, alarmes et rappels, widget et notifications. Les défauts faciles sont partis. Ce que je te
demande est différent d'une relecture de plus.

## Le fait le plus important à connaître avant de lire

**Chaque passe a trouvé des défauts introduits par les correctifs de la passe précédente**, et deux
d'entre eux étaient plus graves que ce qu'ils corrigeaient :

- un budget d'expansion partagé, ajouté pour borner un calcul, **annulait des alarmes vivantes** ;
- le retrait d'une ligne jugée dangereuse laissait l'application **briquée sans issue** quand la base
  refusait de s'ouvrir.

Et **un de mes propres tests épinglait un défaut** au lieu de le détecter : il affirmait que le
comportement fautif était le comportement attendu.

Les fichiers ci-dessous sont donc majoritairement du **code de correctif récent**. Traite-les comme la
zone la plus suspecte du dépôt, pas comme la plus sûre.

## Ce que je cherche, par ordre de valeur

### 1. Le correctif qui traite sa moitié constatée et laisse la moitié conséquente

C'est le motif numéro un ici. Un garde posé sur le symptôme rapporté, alors que la cause a deux ou
trois sorties. Pour chaque garde, chaque `if` de refus, chaque plafond : demande-toi **par où on
arrive au même état sans passer par là**.

### 2. Le repli qui échoue du mauvais côté

Un `catch` qui laisse passer, un `?: emptyList()`, un `getOrDefault`, un `runCatching` qui avale, un
défaut de `when` — là où l'échec devrait fermer et non ouvrir. Et son inverse, tout aussi réel : un
refus si strict qu'il rend inaccessible une donnée légitime de l'utilisateur.

### 3. Les jumeaux traités asymétriquement

Deux chemins qui font la même chose, dont un seul a reçu le correctif. Cherche les paires : les deux
écrans qui vérifient le PIN, les points d'ingestion de données, les receivers, les écritures de
préférences, les lectures de fichier bornées.

### 4. Ce qui casse la promesse « tout reste sur l'appareil »

Journalisation qui pourrait porter du contenu d'agenda, `toString()` d'un modèle dans un log, une
`Exception` dont le message contient une donnée utilisateur, un `Intent` sortant, une écriture hors du
conteneur chiffré, un fichier temporaire, un cache.

### 5. La concurrence

Deux corrections récentes portent sur des sections critiques (un `Mutex` autour de la vérification du
PIN, un budget d'expansion). Cherche les états partagés modifiés depuis plusieurs coroutines, les
`var` non protégés dans un objet à durée de vie longue, les lectures-puis-écritures non atomiques.

### 6. Les commentaires qui mentent

Ce dépôt en a produit une douzaine, et j'en ai écrit beaucoup en corrigeant. Signale chaque KDoc ou
commentaire qui promet une propriété que le code voisin ne garantit pas. C'est la famille la plus
rentable ici.

---

## Ce que j'attends

Pour chaque constat : `fichier:ligne`, **scénario concret**, sévérité, **CONFIRMÉ ou PROBABLE**.

Je préfère **trois constats solides à quinze plausibles**. Si tu n'es pas sûr, dis PROBABLE et dis ce
qu'il faudrait lire pour trancher.

Si une zone est saine, dis-le — « rien à signaler sur X » m'évite de la relire.

Ne propose jamais de changer la clé de signature, de toucher au keystore, de renommer le package, ni
d'ajouter une permission réseau. Ne signale pas de préférences de style.
