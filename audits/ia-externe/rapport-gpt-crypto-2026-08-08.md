## Partie 1 — crypto `.atbak`

### 1) `BackupEnvelope` — format / AAD / PBKDF2 / bornes / oracle

**Rien à signaler (CONFIRMÉ)** sur l’idée centrale : *le header complet est authentifié en AAD*, donc `iterations/salt/kdfId/version/magic` ne sont pas malléables sans casser le tag.

- **`BackupEnvelope.kt:44-52`** — *sel aléatoire + PBKDF2 + AES‑GCM avec AAD=header*  
  **Scénario** : attaquant modifie `iterations` à 1 pour rendre une attaque offline “gratuite”.  
  **Résultat** : échec GCM (tag) car le header est en AAD → l’attaque ne marche pas.  
  **Sévérité** : (bon point)  
  **Statut** : **CONFIRMÉ**

- **`BackupEnvelope.kt:120-127`** — *bornes sur `iterations` et `saltLen`*  
  **Scénario** : fichier hostile demande `iterations = 2_000_000_000` pour geler l’app.  
  **Résultat** : refusé par `if (iterations !in MIN..MAX) return null`.  
  **Sévérité** : (bon point)  
  **Statut** : **CONFIRMÉ**

- **`BackupEnvelope.kt:122` + `BackupEnvelope.kt:67-72`** — **KDF-bomb (DoS) dans les limites acceptées**  
  Tu bornes, mais tu **exécutes PBKDF2 avant de savoir si le tag GCM est valide** (normal) et l’itération vient du fichier (non fiable).  
  **Scénario concret** : un fichier “reconnu” (`magic` ok, version ok, etc.) avec `iterations=10_000_000` (dans la borne) est fourni à l’utilisateur ; à l’ouverture, l’app passe (selon device) de “~1 s” à “dizaines de secondes / minutes” de CPU, avant de finir en échec de tag. Répétable pour user‑fatigue / batterie.  
  **Sévérité** : **Faible → Moyenne (disponibilité / UX), pas de divulgation**  
  **Statut** : **CONFIRMÉ**

- **`BackupEnvelope.kt:70-71` + `AeadCipher.kt:51-54`** — **multiples copies mémoire du fichier (risque OOM)**
  `open()` fait au moins :
  1) `body = file.copyOfRange(...)` (copie ~taille fichier)  
  2) `aad = file.copyOfRange(...)` (petite copie)  
  3) puis `AeadCipher.decrypt()` recopie encore `ct = ByteArray(ctSize)` (copie ~taille ciphertext)  
  + `Cipher.doFinal()` alloue le plaintext.
  **Scénario concret** : agenda volumineux → `.atbak` de (ex.) 80–200 MB. À la restauration, ces copies doublent/triplent le pic RAM → crash/OOM → **restauration impossible** (et vu ton contexte “backup unique”, c’est le vrai risque).  
  **Sévérité** : **Haute (disponibilité / perte de récupérabilité)**  
  **Statut** : **CONFIRMÉ**

- **`BackupEnvelope.kt:64-66` + `BackupEnvelope.kt:118-119`** — **refus “pas un backup” pour fichier plus récent**
  `parseHeader()` retourne `null` si `envVersion` ou `kdfId` diffèrent, et `open()` transforme ça en `"not an Agenda Tech backup"`.  
  **Scénario concret** : dans 2 ans tu passes `ENVELOPE_VERSION=0x02` ou `kdfId=Argon2id`. Un utilisateur tente d’ouvrir un backup récent avec une vieille version : message “pas un backup Agenda Tech” (faux), donc support et UX dégradés.  
  **Sévérité** : **Faible (robustesse/diagnostic), pas crypto**  
  **Statut** : **CONFIRMÉ**

- **Oracle “mauvais mot de passe” vs “fichier corrompu” (CONFIRMÉ : globalement sain)**  
  Pour un header reconnu, **mauvais mot de passe** et **corruption du body** donnent tous deux un échec GCM (tag).  
  **Nuance** : certaines corruptions “structurelles” (header invalide, version AEAD invalide) prennent un chemin différent (`parseHeader==null`, ou `require(...)` dans `AeadCipher.decrypt`). Ça **ne donne pas** un oracle sur le mot de passe (le branchement ne dépend pas du secret), mais ça contredit un peu la formule “indistinguishable” au sens strict.  
  **Sévérité** : **Faible**  
  **Statut** : **CONFIRMÉ**

### 2) `AeadCipher` / `KeystoreManager` — AES‑GCM, IV, réutilisation

- **`AeadCipher.kt:32-35`** — IV 12 bytes aléatoire à chaque chiffrement  
  **Scénario** : réutilisation IV sous la même clé (catastrophique pour GCM).  
  **Ici** : IV tiré via `SecureRandom` à chaque appel, taille recommandée (12). Pour les volumes attendus (wrapping + quelques backups), le risque de collision accidentelle est négligeable.  
  **Sévérité** : (bon point)  
  **Statut** : **CONFIRMÉ : rien à signaler sur l’IV**

- **`KeystoreManager.kt` (KDoc + `generateKey(...).setRandomizedEncryptionRequired(!allowUserIv)`)** — `allowUserIv=true` = garde-fou OS désactivé  
  **Scénario concret** : à l’avenir, une autre portion de code réutilise **le même alias** et fournit un IV non aléatoire / répétitif (ou réutilise par bug un ancien IV) → GCM cassé.  
  **Dans l’extrait** : `AeadCipher` ne permet pas d’injecter un IV, donc *aujourd’hui* ça ne crée pas une réutilisation à elle seule ; c’est surtout un **“footgun” d’évolution**.  
  **Sévérité** : **Faible** (tant que l’alias n’est pas réutilisé ailleurs avec IV contrôlé)  
  **Statut** : **PROBABLE** (risque de maintenance, pas une faille démontrée ici)

- **`AeadCipher.kt:49-50`** — byte `VERSION` non authentifié (car hors AAD/ciphertext)  
  **Scénario concret** : corruption/bitflip du premier octet → échec “Unsupported AEAD version” (via `require`) au lieu d’un simple mismatch GCM.  
  **Impact sécurité** : pas de divulgation ; mais **si** du code appelant traite différemment “version invalide” vs “tag mismatch” (ex. tentative de “réparer”/réinitialiser), ça peut devenir une voie de sabotage de données. Je ne peux pas confirmer sans voir l’appelant (wrapping DB master key).  
  **Sévérité** : **Faible → Moyenne** (selon stratégie de récupération côté appelant)  
  **Statut** : **PROBABLE**

### 3) `PinHasher` — sel / PBKDF2 / comparaison temps constant

- **`PinHasher.kt:33-41`** — `MessageDigest.isEqual(actual, expectedHash)` + sel 16 bytes + PBKDF2-HMAC‑SHA256  
  **Scénario** : attaque timing sur comparaison ; rainbow tables ; PIN en clair.  
  **Ici** : comparaison constant‑time OK, sel OK, rien en clair.  
  **Sévérité** : (bon point)  
  **Statut** : **CONFIRMÉ : rien à signaler sur la comparaison / sel**

### 4) Zéroïsation (`wipe`) — chemins d’erreur / copies implicites

- **`BackupEnvelope.kt:91-95`** — le `CharArray` est bien wipe même en cas d’exception (via `finally`)  
  **Scénario** : dérivation échoue → password reste en mémoire.  
  **Ici** : non, `password.wipe()` est inconditionnel dans `deriveKey.finally`.  
  **Sévérité** : (bon point)  
  **Statut** : **CONFIRMÉ**

- **`BackupEnvelope.kt:80-88`** — le buffer `bits` (clé dérivée en bytes) est wipe  
  **Scénario** : fuite de clé PBKDF2 via heap dump.  
  **Ici** : la copie `bits` est effacée, mais **la clé dans `SecretKeySpec` ne l’est pas** (pas de handle sur l’array interne).  
  **Sévérité** : **Faible** (sur Android non-root, ce n’est généralement pas le premier risque ; mais c’est réel en forensic / dumps)  
  **Statut** : **CONFIRMÉ**

## Partie 2 — tes 3 points

### (a) Receiver exporté / process-start DoS (API 26–30)

- **`ExactAlarmPermissionReceiver.kt:96-103`** (et surtout *le fait qu’il soit exporté dans le manifest*)  
  **Scénario concret** : une app hostile envoie un intent explicite vers ce receiver en boucle sur API 26–30. Résultat : démarrages de process + `MainApplication.onCreate` (DB open + keystore IPC + SQLCipher) + parfois reschedule, donc batterie/CPU. Pas de lecture de données, mais nuisance.  
  **Sévérité** : **Faible → Moyenne (disponibilité/batterie)**  
  **Statut** : **CONFIRMÉ** (tu l’as mesuré, et la garde dans `onReceive` ne peut pas empêcher le process-start)

**Ton arbitrage (“ne pas faire `enabled=false` + PM write à chaque cold start”) : partiellement basé sur une hypothèse trop pessimiste.**  
Ce que tu rates potentiellement : tu n’es pas obligé de faire **une écriture à chaque démarrage**. Le pattern habituel est :
- lire l’état (`getComponentEnabledSetting`) ;
- n’appeler `setComponentEnabledSetting` **que si changement nécessaire** (ex. première exécution, ou après upgrade OS 30→31).

**Coûts/effets secondaires à connaître :**
- l’état “enabled/disabled” est **persisté par PackageManager** et en pratique **survit aux updates** (F‑Droid compris) ; il saute surtout en cas de réinstallation / clear data.
- `setComponentEnabledSetting` peut tuer le process **si** tu n’utilises pas `DONT_KILL_APP` (dépend de l’appel exact).
- si tu déclares le receiver `enabled=false` par défaut, tu peux **rater** un broadcast reçu avant premier lancement. Ici, vu le type d’événement (permission exacte alarm) c’est peu probable, mais c’est un vrai trade‑off.

### (b) `MigrationTrace` écrit en release mais illisible

- **`MigrationTrace.kt:32-39`**  
  **Scénario concret** : en prod, une migration destructive se produit ; tu enregistres une trace en clair dans `shared_prefs`, mais tu ne peux pas la lire sur l’appareil de l’utilisateur (pas `run-as`, pas UI). Donc : **coût + surface de données en clair**, pour un bénéfice quasi nul en support réel.  
  **Sévérité** : **Faible** (confidentialité limitée, mais incohérence fonctionnelle réelle)  
  **Statut** : **CONFIRMÉ**

**Choix** (en restant dans tes contraintes) :
- si le contrat réel est “outil de repro sur device de test avec `.atbak`” → **conditionner l’écriture au debug** (ou supprimer).  
- si tu veux une utilité en release → **l’exposer volontairement** (About) *avec action explicite de l’utilisateur pour copier/partager*. Ton argument “donnée dérivée” est valide : ce n’est pas du contenu d’événement, mais ça reste un signal (voyages/imports). À toi de décider si ce niveau de méta-donnée est acceptable hors conteneur chiffré.

### (c) Toggle “journée entière” destructif (fuseau non restauré)

Ce que je peux conclure à partir de **ce fichier seul** :

- **`EventEditorViewModel.kt:~190-207`** (dans `persist`, affectation de `timeZoneId`)  
  Quand `current.allDay == true`, tu forces `timeZoneId = zone.id`. Donc **si l’utilisateur enregistre en “journée entière”**, il a effectivement jeté (a) les heures et (b) le fuseau auteur.  
  **Scénario concret** : événement importé “09:00 Asia/Tokyo”, l’utilisateur coche “journée entière” et enregistre → l’instant devient minuit device-zone, et `timeZoneId` devient device-zone. Revenir en arrière plus tard ne peut pas recréer “Asia/Tokyo 09:00”.  
  **Sévérité** : **Faible** (c’est cohérent avec une conversion de type d’événement)  
  **Statut** : **CONFIRMÉ**

**Ton raisonnement (“on ne peut pas restaurer une info que l’utilisateur a détruite”) tient** *si* l’action est bien perçue comme une conversion irréversible au moment du save.

La seule zone grise (où l’utilisateur peut perdre “sans l’avoir consciemment jeté”) n’est pas le fuseau en soi, c’est le cas “j’ai coché par erreur puis sauvegardé en pensant juste changer un autre champ”. Là, il perd aussi les heures, donc le fuseau n’est pas la seule perte.

Dernière remarque factuelle : dans ce ViewModel, **le simple fait de cocher/décocher** ne modifie pas `startDateTime/endDateTime` (pas de logique dans `onAllDayChange`), donc **si tu constates une perte de fuseau après un aller-retour sans enregistrer en all-day**, la cause est probablement **ailleurs** (UI qui recale les heures à minuit, mapper, ou logique non incluse ici).  
**Statut de cette hypothèse** : **PROBABLE** (je ne peux pas confirmer sans le code UI/mapper).