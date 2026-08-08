## Lot E — `.ics`, `TimeZones`, `BoundedRead`, migration v5→v6 (priorité)

### 1) Export `.ics` invalide dès qu’un `ZoneId` contient `:` (offsets `+02:00`)
- **Fichier:ligne** : `domain/ics/IcsCodec.kt:109`
- **Code** : `"$name;TZID=${zone.id}:${local.format(LOCAL_STAMP)}"`
- **Scénario concret** :
  1. L’utilisateur importe un `.ics` dont `TZID` est un **offset** (ex. `TZID=+02:00`) — ou a déjà en base un `event.timeZoneId` égal à `+02:00` (puisque `TimeZones` annonce accepter les offsets).
  2. Il exporte ensuite son agenda en `.ics`.
  3. Le fichier produit contient une ligne du type : `DTSTART;TZID=+02:00:20260808T100000`.
  4. À la ré-importation, **votre propre parseur** (`parsePropertyLine`, qui fait `indexOf(':')`) coupe au **premier** `:` (celui de `+02:00`) et la ligne devient illisible ⇒ `DTSTART` mal parsé ⇒ évènement **drop** (car `DTSTART` devient invalide).
- **Impact** : round-trip `.ics` non fiable (perte d’évènements) + interop cassée avec d’autres lecteurs.
- **Statut** : **CONFIRMÉ** (le séparateur `:` est utilisé à la fois dans l’offset et comme séparateur iCalendar, et votre parseur ne gère pas ce cas).

---

### 2) Résolution Windows TZID cassée selon la locale (Turc “I/ı”)
- **Fichier:ligne** : `core/time/TimeZones.kt:39`
- **Code** : `WINDOWS_TO_IANA[candidate.lowercase()]`
- **Scénario concret** :
  1. Appareil en locale **tr-TR** (ou toute locale à règles spéciales de casse).
  2. Import d’un `.ics` Outlook avec `TZID=Israel Standard Time` / `India Standard Time` / `Iran Standard Time`, etc.
  3. `candidate.lowercase()` en turc transforme `I` → `ı` (i sans point) ⇒ la clé ne matche pas `"israel standard time"` (ASCII).
  4. Résolution Windows échoue ⇒ fallback (souvent zone device) ⇒ instants calculés/étiquetés incohérents.
- **Statut** : **CONFIRMÉ**.

---

### 3) Table Windows→IANA : plusieurs valeurs “backward links” (risque de non-résolution silencieuse)
- **Fichier:ligne** : `core/time/TimeZones.kt:~70+` (table `WINDOWS_TO_IANA`)
- **Exemples précis** :
  - `"india standard time" to "Asia/Calcutta"`
  - `"nepal standard time" to "Asia/Katmandu"`
  - `"myanmar standard time" to "Asia/Rangoon"`
  - `"greenland standard time" to "America/Godthab"`
  - `"fle standard time" to "Europe/Kiev"`
- **Scénario concret** :
  1. Appareil Android avec tzdb/ICU où certains alias “backward” sont absents (ou modifiés selon constructeur/MAJ).
  2. Import Outlook `TZID=India Standard Time`.
  3. Windows→IANA donne `Asia/Calcutta`, mais `ZoneId.of("Asia/Calcutta")` peut échouer ⇒ `resolveOrNull` rend `null` ⇒ fallback device.
- **Statut** : **PROBABLE** (dépend du tzdb présent sur l’appareil ; ce sont des IDs notoirement “legacy/alias”).

---

### 4) `BoundedRead`: boucle infinie possible si `read()` renvoie 0 (provider buggé/malveillant)
- **Fichier:ligne** : `core/io/BoundedRead.kt:41-46` (boucle `while (true)` / `val read = input.read(chunk)`)
- **Scénario concret** :
  1. L’utilisateur choisit un document via un `DocumentProvider` (Drive-like, OEM, ou provider tiers) dont le flux renvoie **0** sans EOF (bug ou implémentation exotique).
  2. `read == 0` ⇒ `total` n’augmente pas, `read < 0` faux ⇒ boucle **sans fin**.
  3. Import `.ics` reste bloqué (coroutine IO occupée), écran “import…” qui ne finit jamais.
- **Statut** : **PROBABLE** (InputStream “correct” ne devrait pas, mais ça arrive en pratique avec des wrappers/providers).

---

### 5) `BoundedRead`: le commentaire “hard ceiling mémoire” est faux (BAOS + copie finale)
- **Fichier:ligne** : `core/io/BoundedRead.kt:6-7` (KDoc “hard ceiling… held in memory”), + `:38` et `:48`
- **Pourquoi** :
  - `ByteArrayOutputStream` **sur-alloue** (croissance par doublement) ⇒ buffer interne > données réelles.
  - `out.toByteArray()` **copie** le buffer ⇒ pic mémoire = (buffer interne) + (copie).
- **Scénario concret** :
  1. Appareil low-end / heap serré.
  2. Import d’un `.ics` proche de `MAX_ICS_BYTES` (5 MiB).
  3. Pic mémoire bien au-delà de 5 MiB (ex. ~8 MiB buffer + 5 MiB copie) ⇒ OOM possible.
- **Statut** : **CONFIRMÉ** (propriété connue de `ByteArrayOutputStream` + `toByteArray()`).

---

## Migration v5→v6 (réécriture de lignes existantes)

### 6) MIGRATION_5_6 : coût potentiellement énorme (N scans de table) → blocage à l’ouverture / au boot
- **Fichier:ligne** : `data/local/db/Migrations.kt:115` (DISTINCT) + `:122-127` (UPDATE par valeur distincte)
- **Cause** :
  - `SELECT DISTINCT time_zone FROM events` scanne la table (et peut construire un temp B-tree).
  - Puis **un UPDATE par valeur distincte** : `UPDATE events SET ... WHERE time_zone = ?`
  - S’il n’y a **pas d’index** sur `events.time_zone` (rien ne le montre ici), chaque UPDATE peut rescanner la table.
- **Scénario concret** :
  1. L’utilisateur a une base très grosse (import massif / usage long).
  2. Il met à jour vers v6 puis **redémarre** l’appareil : `BootReceiver` ouvre la DB (donc migration) pendant un broadcast.
  3. Migration + reschedule peuvent dépasser budget temps/IO ⇒ process tué ⇒ rappels non réarmés jusqu’à prochaine ouverture.
- **Statut** : **PROBABLE** (dépend taille DB + index ; le pattern “UPDATE WHERE col = ?” sans index est classiquement catastrophique).

---

### 7) MIGRATION_5_6 : fallback `ZoneId.systemDefault()` peut “réparer” vers la *mauvaise* zone si l’utilisateur a voyagé
- **Fichier:ligne** : `data/local/db/Migrations.kt:113` (`val fallback = ZoneId.systemDefault()`)
- **Scénario concret** :
  1. En v5, l’utilisateur importe un `.ics` Outlook en **Europe/Paris** (device zone Paris), TZID Windows non résolu stocké brut.
  2. Plus tard il vit/voyage et son appareil est en **America/New_York** au moment de la MAJ v6.
  3. Pour tout TZID non-réparable (non-Windows, faute de table/alias), la migration fixe `time_zone = America/New_York`.
  4. Les instants UTC restent ceux calculés à l’import (Paris), mais l’étiquette zone devient New York ⇒ affichage/export/expansion potentiellement décalés.
- **Statut** : **PROBABLE** (c’est explicitement dans le design, mais c’est un comportement utilisateur-visible et parfois pire que “laisser brut + fallback constant”).

---

### 8) MIGRATION_5_6 : KDoc “seulement où TimeZones ne peut pas résoudre” n’est pas strictement vrai
- **Fichier:ligne** : `data/local/db/Migrations.kt:96-97` (KDoc) vs `:122-123`
- **Pourquoi** :
  - Le filtre est `stored.filterNot(TimeZones::isCanonical)` (`:122`), et `isCanonical` (`TimeZones.kt:55`) **ne trim/unquote pas**.
  - Donc une valeur stockée `" Europe/Paris "` (espaces) est “non canonique” selon `isCanonical` mais **résoluble** selon `TimeZones.normalize` (qui trim).
- **Scénario concret** : pas besoin d’utilisateur ; c’est une divergence doc/code qui peut faire accepter une future réécriture plus large “par accident”.
- **Statut** : **CONFIRMÉ** (doc affirme une propriété que le code ne garantit pas).

---

## Décodage `.ics` (multimap / first-wins / distinct)

### 9) `decode`: “first wins” peut faire DROP un évènement importable si `SUMMARY` est répété (1er vide, 2e bon)
- **Fichier:ligne** : `domain/ics/IcsCodec.kt:~168-205` (`first("SUMMARY")` + `takeIf { it.isNotBlank() } ?: return null`)
- **Scénario concret** :
  1. `.ics` malformé mais rencontré “en vrai” : deux lignes `SUMMARY`, la première vide/placeholder, la seconde correcte (certains exports bricolés/concaténés font ça).
  2. Avec “first wins”, `summary` devient vide ⇒ évènement **jeté** (`return null`).
  3. Avant (last-wins), il aurait pu être importé.
- **Statut** : **PROBABLE** (dépend producteurs ; le comportement est une régression de tolérance).

### 10) `EXDATE.distinct()` : perte d’info de forme (pas d’instant), mais possible perte sémantique si le producteur encode volontairement des doublons
- **Fichier:ligne** : `domain/ics/IcsCodec.kt:~260-270` (`.distinct()`)
- **Scénario concret** :
  1. Fichier `.ics` contenant volontairement des doublons `EXDATE` (rare, mais possible dans des merges).
  2. Après import/export, les doublons disparaissent.
- **Statut** : **PROBABLE** (ça ne change pas l’ensemble d’instants exclus, mais ça change le fichier — si vous promettez “lossless” au sens strict du texte).

---

## Lot C — Receivers / Provider / `goAsync()`

### 11) “off the main thread” n’est pas garanti : `scope.launch {}` dépend du dispatcher réel de `@ApplicationScope`
- **Fichier:ligne** :
  - `system/alarm/BootReceiver.kt:47`
  - `system/alarm/ReminderReceiver.kt:~40+` (même pattern)
- **Scénario concret** :
  1. Si `@ApplicationScope` est construit avec `Dispatchers.Main` (ou un dispatcher confiné main) dans le module DI (non visible ici),
  2. alors `scheduler.get().rescheduleAll()` / `handler.get().handle()` s’exécute encore sur le main thread, malgré `Provider`.
- **Statut** : **PROBABLE** (on ne voit pas la définition de `@ApplicationScope`; le correctif enlève l’injection eager coûteuse, mais ne prouve pas le thread d’exécution).

### 12) `goAsync()` est bien relâché sur les chemins couverts ici
- **Fichier:ligne** : `BootReceiver.kt:46-55`, `ReminderReceiver.kt:~33-55`
- **Constat** : `finish()` est en `finally`.
- **Statut** : **rien à signaler** sur “finish oublié” *dans ces fichiers*.

---

## Lot D — budget partagé / rappels

### 13) Budget partagé + `fire == null ⇒ cancel()` peut supprimer des rappels quand le budget s’épuise (au reboot)
- **Fichier:ligne** :
  - `system/alarm/ReminderScheduler.kt:70` (budget partagé)
  - `system/alarm/ReminderScheduler.kt:85-86` (budget passé à `schedule`)
  - `system/alarm/ReminderScheduler.kt:179-181` (`if (fire == null) { cancel(...) }`)
- **Scénario concret** :
  1. Utilisateur “normal+” (ou import massif) : beaucoup de rappels sur des séries récurrentes difficiles à expanser.
  2. Après reboot, `rescheduleAll()` consomme le budget sur les premiers rappels.
  3. Pour les suivants, `computeNextFire()` peut renvoyer `null` **par épuisement de budget** (selon implémentation de `RecurrenceExpander`), pas parce que la série est finie.
  4. Le code interprète `null` comme “série terminée” et **annule** l’alarme de ce reminder.
- **Visibilité** : pas forcément visible immédiatement (juste “plus de notif”), log seulement.
- **Statut** : **PROBABLE** (dépend du contrat exact `RecurrenceExpander` quand budget épuisé ; ici la sémantique “null == plus d’occurrence” est dangereuse avec un budget partagé).

---

## Tests
- **Constat** : aucun fichier de test n’est fourni dans l’énoncé.
- **Statut** : **rien à signaler (impossible à évaluer)** sur le point 8 sans les tests.

Si tu me donnes aussi `RecurrenceExpander` + `ExpansionBudget` (et éventuellement la définition DI de `@ApplicationScope`), je peux confirmer/infirmer précisément les points **11** et **13** (qui dépendent d’implémentations non visibles ici).