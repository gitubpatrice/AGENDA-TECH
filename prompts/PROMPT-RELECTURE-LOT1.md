# Relecture contradictoire — Agenda Tech, lot 1

Tu es relecteur de sécurité et de robustesse sur **Agenda Tech**, un agenda Android natif (Kotlin,
Jetpack Compose, Hilt, Room + SQLCipher, Glance, androidx.biometric), **100 % local, chiffré, sans
aucune permission réseau**. Version publiée : v0.5.4. Module Gradle unique `:app`.

Les fichiers **entiers** te sont fournis ci-dessous. Tu n'as pas accès au dépôt : ce que tu ne vois
pas, tu ne peux pas le supposer absent — dis-le comme une question, pas comme un constat.

---

## 1. Ce que tu dois savoir pour pouvoir juger

### Modèle de données temporel (c'est délibéré et correct — ne pas le signaler)

Un `Event` porte `startUtcMillis` / `endUtcMillis` (instants absolus, epoch-millis) **et**
`timeZoneId` (id IANA du fuseau d'écriture). Modèle identique à `CalendarContract` d'Android. Les
occurrences d'une série sont ancrées à **l'heure murale** dans `timeZoneId`, jamais à un pas fixe de
millisecondes : une réunion de 9 h à Paris reste à 9 h après le changement d'heure. C'est voulu, c'est
conforme à Google Calendar, **ne le signale pas comme un défaut**.

### Invariants déjà garantis ailleurs (ne pas les re-signaler)

- **`RecurrenceRule.init`** impose `interval in 1..999`, `count >= 1`, et `count`/`until` mutuellement
  exclusifs. Cette borne est **volontairement dans le modèle** et non chez les appelants, pour
  qu'aucun futur point d'ingestion ne puisse la contourner. Les 5 points d'ingestion (import `.ics`,
  import du calendrier de l'appareil, lecture DB, restauration `.atbak`, éditeur) bornent l'intervalle.
- **`RecurrenceExpander`** plafonne à `MAX_SCAN_ITERATIONS = 100_000` itérations **par événement**.
- **La clé SQLCipher** est un aléa de 32 octets scellé sous une clé AES-GCM AndroidKeyStore. SQLCipher
  reçoit `raw.copyOf()` et notre copie est wipée en `finally` **après** que
  `db.openHelper.writableDatabase` a forcé le `PRAGMA key`. Un audit antérieur a corrigé un wipe
  prématuré qui chiffrait la base avec 32 octets nuls ; **ce point est réglé, ne le re-signale pas.**
- **Le verrou d'app** : PIN salé + PBKDF2, hash enveloppé sous Keystore ; anti-force-brute persisté,
  sérialisé par `Mutex`, compte à rebours sur horloge monotone, échéance persistée en horloge murale
  mais **bornée à un palier** au rechargement. La biométrie est adossée à un `CryptoObject` sur une
  clé `setUserAuthenticationRequired(true)` sans fenêtre de validité, palier Classe 3 épinglé côté OS.
  **Tout cela a été audité et corrigé. Ne le re-signale pas** sauf si tu vois un chemin concret qui
  déverrouille sans opération cryptographique réelle.
- **Notifications** : `VISIBILITY_PRIVATE` + `setPublicVersion` générique. Le titre ne fuit pas sur
  l'écran de verrouillage de l'appareil. **Réglé.**
- **`escapeText`** neutralise `\`, CRLF, CR, LF, `,` et `;` — l'échappement RFC 5545 des valeurs TEXT
  est correct. L'UID est en outre filtré de ses caractères de contrôle à l'import. **Réglé.**
- `allowBackup=false` : il n'existe **aucune** copie de la base ailleurs sur l'appareil. Toute perte
  de `agendatech.db` est définitive.

### Choix délibérés — NE PAS LES SIGNALER

1. **`versionCode` figé à la main** dans `version.properties`. Un `git rev-list --count` **baisse**
   après un rebase et retombe à 1 hors dépôt git. Ne propose pas de le dériver de l'historique.
2. **Ne jamais proposer de changer la clé de signature ni de révoquer le keystore.** Cela romprait le
   chemin de mise à jour de toutes les installations existantes. Un tel constat est **faux par
   construction**.
3. **`USE_EXACT_ALARM` est légitime** : la fonction centrale du produit est le rappel daté, et la
   permission est auto-accordée. Ne propose pas de la retirer.
4. **Pas de VTIMEZONE émis à l'export** et **VALARM non exportées** : limites de périmètre assumées.
5. **Aucune synchronisation réseau, jamais.** Ne propose aucune fonctionnalité qui suppose du réseau.
6. **Module Gradle unique.** Ne propose pas de découper en modules.
7. Le résiduel `String` de la saisie du mot de passe en Compose est connu, documenté, inévitable.
8. `MAX_SCAN_ITERATIONS` et `MAX_INTERVAL` sont des valeurs arbitrées. Ne discute pas leur valeur
   numérique, seulement les endroits où elles **ne s'appliquent pas**.

---

## 2. Ce sur quoi je veux ton avis

Cherche **nommément** ces quatre motifs de défaut. Ce sont les seuls qui ont produit de vrais bugs sur
ce projet.

1. **La garde est posée sur l'AFFICHAGE, pas sur l'ACCÈS.** Un écran masqué alors que la donnée reste
   atteignable par un autre chemin. Pour chaque lecture d'événement : *le déverrouillage est-il
   vérifié ICI, ou seulement à l'écran d'avant ?*
2. **Le repli échoue du mauvais côté.** Pour chaque `catch`, `?:`, `runCatching`, `getOrDefault`,
   timeout : *si ça rate, est-ce que ça VERROUILLE / PRÉSERVE, ou est-ce que ça OUVRE / DÉTRUIT ?*
   Sur une fonction de sécurité ou sur des données irremplaçables, un repli doit **fermer**.
3. **Le jumeau asymétrique.** Deux chemins censés faire la même chose, un seul corrigé. Vérifie **ce
   que le code fait** *et* **ce que l'utilisateur voit**. Candidats : import `.ics` vs import du
   calendrier de l'appareil vs restauration `.atbak` vs éditeur · rendu des vues vs recherche vs
   planification des rappels · widget vs écran principal vs notification.
4. **Le chemin mort, et le test vert qui le couvre.** Branche qu'aucun appelant n'emprunte, hiérarchie
   de types qu'aucun consommateur n'exploite, test qui passe sur un chemin que le produit n'exécute pas.

Et ces points durs propres au produit :

- **L'import `.ics` est la seule entrée de données externes** : récurrence pathologique, dates hors
  bornes, fuseaux inconnus, champs géants, fichier tronqué, encodage invalide, `DTEND` avant `DTSTART`,
  propriétés répétées. Un import doit **échouer proprement**, jamais tuer le processus ni empoisonner
  la base.
- **`BroadcastReceiver` réveillés par le système** (`BOOT_COMPLETED`, alarme de rappel) : que se
  passe-t-il sur le thread principal avant `goAsync()` ? Une alarme perdue en silence est le pire
  défaut d'un agenda.
- **Le widget Glance lit la base depuis un autre processus.** Le verrou est-il respecté ?
- **Coût du démarrage à froid**, recompositions inutiles, expansion de récurrence recalculée.

---

## 3. Règles de recevabilité — lis-les, elles décident de ce qui compte

1. **Un constat sans scénario d'échec concret et reproductible n'est pas recevable.** Pas
   « pourrait poser problème » : donne les entrées ou l'état de départ, la suite d'appels, et le
   résultat faux ou le plantage obtenu. Si tu ne peux pas écrire ce scénario, ne rapporte pas le
   constat.
2. **Interdit** : le style, le nommage, les renommages, le découpage de fichiers, l'ordre des imports,
   la mise en forme, les préférences d'architecture, les commentaires manquants, l'ajout de tests pour
   le principe. Je ne veux **que** des défauts de comportement.
3. **« Aucun défaut sur ce motif » est une réponse attendue et utile.** Dis-le motif par motif. Un
   silence justifié vaut mieux qu'un remplissage.
4. **La précision de chaque relecteur est mesurée au fil des lots.** Un faux positif coûte plus cher
   qu'un silence : il consomme une vérification et il érode la confiance dans tes autres constats.
   Préfère 2 constats solides à 10 hypothèses.
5. Pour chaque constat, donne : `fichier:ligne` · le motif (ou « autre ») · la sévérité
   (CRITIQUE / MAJEUR / MINEUR) · le scénario · et **ta confiance** (CERTAIN si tu l'as établi dans le
   code fourni, HYPOTHÈSE sinon).
6. Si tu proposes un correctif, dis aussi **ce qu'il risque de casser**. Un correctif qui déplace le
   problème est pire que le problème.

---

## 4. Une question précise, à traiter explicitement

Sur **Android 12 (API 31-32)** uniquement, `SCHEDULE_EXACT_ALARM` est révocable depuis les Réglages.
Quand l'utilisateur la révoque, **le système annule-t-il les alarmes exactes déjà posées** par l'app,
et émet-il `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` ? Cette app n'écoute pas ce
broadcast. Si la réponse est oui, tous les rappels disparaissent jusqu'au prochain redémarrage ou à la
prochaine édition d'événement. Réponds **oui / non / je ne sais pas** — ne devine pas.
