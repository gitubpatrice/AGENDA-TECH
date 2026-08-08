Voici la relecture contradictoire des lots C, D et E. Cet auteur a effectivement laissé passer des défauts majeurs, dont des corruptions de données silencieuses et des commentaires qui mentent effrontément sur ce que fait le code.

Voici les défauts trouvés, classés par thème.

### 1. La migration v5 → v6 (Corruption de données)
**Fichier :** `Migrations.kt`, ligne 125 (`migration5to6`)
**Statut :** **CONFIRMÉ** (Critique)
**Défaut :** La migration répare le nom du fuseau horaire, mais **laisse l'instant UTC intact**, ce qui désynchronise l'événement.
**Scénario :** 
L'ancien importateur (Lot E, F3) ne comprenait pas `Romance Standard Time`. Il a donc calculé l'instant UTC de l'événement en utilisant le fuseau de l'appareil (ex: `America/New_York`). Un événement à 14h00 en France a été stocké comme 14h00 à New York (soit 18h00 UTC). 
Aujourd'hui, la migration v6 tourne. Elle lit `Romance Standard Time`, le résout correctement en `Europe/Paris`, et met à jour la colonne `time_zone`. 
**Résultat :** L'application lit maintenant 18h00 UTC avec le fuseau `Europe/Paris`. L'événement s'affiche à 20h00 pour l'utilisateur. La migration vient de décaler silencieusement l'événement de 6 heures. Pour corriger le fuseau, il fallait *aussi* recalculer l'instant UTC en appliquant le décalage entre l'ancien fallback et le nouveau fuseau.

### 2. Les commentaires qui mentent (et ouvrent une faille)
**Fichier :** `IcsLines.kt`, ligne 94 (`escapeText`)
**Statut :** **CONFIRMÉ**
**Défaut :** Le commentaire affirme fièrement : *"Audit S8 — U+2028, U+2029 and U+0085 are line terminators... Same defect as the bare CR above"*. 
Regarde le code : `.replace("…", "\\n")`. 
Le caractère `…` est U+2026 (Points de suspension), **pas U+0085 (Next Line / NEL)** !
**Scénario :** 
1. Un utilisateur écrit "À suivre…" dans le titre d'un événement. À l'export, le code remplace les points de suspension par un vrai saut de ligne échappé (`\n`), corrompant le texte.
2. Un attaquant insère un vrai caractère U+0085 dans un fichier `.ics`. Le code ne l'échappe pas. Le lecteur distant l'interprète comme un saut de ligne et l'injection de propriétés (la faille que ce correctif prétendait fermer) a lieu.
*(Réponse à ta question 8 sur les tests : le test unitaire de l'auteur pour U+0085 est soit inexistant, soit il teste volontairement le caractère `…` pour faire passer l'intégration continue).*

### 3. Le second mensonge (Export UTC)
**Fichier :** `IcsCodec.kt`, lignes 85 (`dateProperty`) et 188 (`zoneOf`)
**Statut :** **CONFIRMÉ**
**Défaut :** Le commentaire de `zoneOf` affirme que forcer le fallback à `ZoneId.of("UTC")` empêche d'exporter un fuseau illisible `TZID=Z`. C'est faux si l'événement possède *déjà* le fuseau `"Z"`.
**Scénario :** Si un événement a `timeZoneId = "Z"` (ce qui est valide dans tzdb et passe `isCanonical`), `zoneOf` retourne `Z`. Dans `dateProperty`, la condition `zone.id == "UTC"` échoue (car `"Z" != "UTC"`). Le code tombe dans le `else` et exporte `DTSTART;TZID=Z:20260808T100000`. Le bug d'export que l'auteur prétend avoir corrigé est toujours là.

### 4. Lot D — Le budget partagé (Famine d'événements)
**Fichier :** Lot D (Logique de planification, déduit de ta description)
**Statut :** **PROBABLE** (Défaut architectural)
**Défaut :** Un budget d'expansion *partagé* par passe pour `rescheduleAll()` est une erreur de conception majeure.
**Scénario :** L'utilisateur a un événement récurrent complexe (ex: "tous les jours pendant 10 ans"). Lors du `rescheduleAll()`, cet événement est évalué en premier et consomme l'intégralité du budget partagé (ex: 10 000 itérations). L'événement suivant dans la base, qui a lieu demain, se voit allouer un budget de 0. `computeNextFire` s'arrête immédiatement et retourne `null`. Le rappel pour demain est silencieusement tronqué et ne sonnera jamais. Le budget doit impérativement être *par événement*.

### 5. `BoundedRead` — La borne qui crashe
**Fichier :** `BoundedRead.kt`, ligne 53
**Statut :** **CONFIRMÉ**
**Défaut :** La fonction prétend gérer les flux défaillants en retournant `null` (trop gros, trop de lectures vides), mais elle ne capture pas `IOException` sur `input.read(chunk)`.
**Scénario :** L'utilisateur importe un `.ics` depuis un fournisseur de documents cloud (Google Drive, Nextcloud). Le réseau coupe pendant la lecture. `read()` lève une `IOException`. Comme la fonction ne la capture pas pour retourner `null`, l'exception remonte et fait crasher l'application au lieu d'afficher un message d'erreur à l'utilisateur.

### 6. Lot C — La fuite du `goAsync()`
**Fichier :** Lot C (`BootReceiver` / `ReminderReceiver`)
**Statut :** **PROBABLE**
**Défaut :** L'utilisation de `Provider.get()` repousse effectivement la construction du graphe (et l'ouverture de SQLCipher) dans la coroutine. Mais si l'ouverture de la base échoue (mot de passe invalide, base corrompue), `.get()` lève une exception.
**Scénario :** Si le `pendingResult.finish()` du `goAsync()` n'est pas placé dans un bloc `finally` robuste autour du `.get()`, l'exception tue la coroutine, le `finish()` n'est jamais appelé, et le système déclenche un ANR (Application Not Responding) 10 secondes plus tard.

### 7. L'import `.ics` — Le multimap casse les mises à jour
**Fichier :** `IcsCodec.kt`, ligne 121 (`parseVEvent`)
**Statut :** **CONFIRMÉ**
**Défaut :** Le passage de "la dernière gagne" à "la première gagne" (`props.first("DTSTART")`) casse la compatibilité avec les fichiers `.ics` concaténés.
**Scénario :** Certains serveurs d'entreprise (Exchange/Gateways) mettent à jour un événement non pas en remplaçant la ligne, mais en ajoutant un nouveau bloc `VEVENT` à la fin du fichier, ou en ajoutant un nouveau `DTSTART` à la fin du bloc. Avec "la première gagne", l'application ignore la mise à jour et importe la version obsolète de l'événement.

### 8. `AppLockManager` — Contention et saccades UI
**Fichier :** `AppLockManager.kt`, lignes 96 (`attemptPin`) et 117 (`throttleRemainingMs`)
**Statut :** **CONFIRMÉ**
**Défaut :** `attemptPin` maintient le `Mutex` verrouillé *pendant* l'exécution de la lambda `verify()` (qui fait ~120 000 tours de PBKDF2, soit ~100ms). 
**Scénario :** L'utilisateur tape son code. Pendant que le hash se calcule (100ms), l'UI appelle `throttleRemainingMs()` pour rafraîchir l'écran. Cet appel a besoin du même `Mutex`. Le thread UI (ou le thread appelant) est suspendu en attendant la fin du calcul cryptographique, ce qui provoque une saccade visible sur l'écran de verrouillage. La vérification cryptographique doit se faire *hors* du verrouillage de l'état.

---
**En résumé sur tes questions spécifiques :**
* **Idempotence v6 :** Oui, elle l'est (`isCanonical` protège).
* **IdentityHash Room :** Aucun problème, le schéma ne changeant pas, le hash reste valide.
* **Table CLDR :** Pas d'erreurs manifestes dans les clés/valeurs, mais l'ordre de résolution fait que `ZoneId.SHORT_IDS` est partiellement mort (tzdb intercepte "EST" ou "CST" avant lui et renvoie les versions sans heure d'été).
* **EXDATE distinct() :** Ne perd aucune information légitime, car les instants sont convertis en millisecondes UTC avant le `distinct()`. Deux exclusions identiques dans des fuseaux différents seront bien fusionnées.