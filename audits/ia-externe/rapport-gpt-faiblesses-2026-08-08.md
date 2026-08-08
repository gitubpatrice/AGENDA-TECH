## 1) Windows TZID cassé selon la locale (Turc “i” etc.) → zones non résolues, heures fausses

**Fichier:** `core/time/TimeZones.kt:39` (`WINDOWS_TO_IANA[candidate.lowercase()]`)

**Scénario concret :**
- L’utilisateur est en locale **tr_TR** (ou toute locale à règles spéciales de casse).
- Il importe un `.ics` Outlook avec `TZID=Romance Standard Time` (ou n’importe quel Windows name contenant un `I/i`, typiquement “Time”).
- `candidate.lowercase()` utilise la **locale courante**, donc la clé produite n’est plus la même que celle du tableau (qui est en ASCII “normal”).
- La table Windows→IANA n’est pas consultée, `ZoneId.of(candidate)` échoue, `SHORT_IDS` ne matche pas ⇒ `resolveOrNull` renvoie `null`.
- Selon l’appelant, on retombe sur un fallback (device zone / UTC), donc **instants/expansions/export deviennent faux** (exactement la famille de défauts F3 que ce fichier est censé éliminer).

**Sévérité :** **ÉLEVÉE** (intégrité des dates/heures, récurrences, export/import ; touche une promesse centrale “unifier la résolution”)

**Statut :** **CONFIRMÉ** (c’est un comportement standard de `String.lowercase()` en Kotlin/JDK : locale-dépendant)


## 2) Budget d’expansion : correctif “ne pas annuler” mais, après reboot, ça “ne ré-arme pas” ⇒ rappels perdus silencieusement

**Fichier:** `system/alarm/ReminderScheduler.kt:~106-143` (dans `schedule()`, branche `if (fire == null) { if (budget?.isExhausted == true) return }`)

**Scénario concret :**
- Après reboot, `rescheduleAll()` tourne (commentaire : “Exact alarms do not survive a reboot”).
- Un ou plusieurs événements récurrents pathologiques (import contrôlé, règle coûteuse, fenêtre large…) **épuisent le budget partagé**.
- `computeNextFire(...)` retourne `null` parce qu’on a **arrêté de chercher**, pas parce que la série est finie.
- Le correctif F5-bis a remplacé “CANCEL” par “return (laisser tel quel)”.
- **Sauf qu’après reboot il n’y a rien à “laisser tel quel”** : l’alarme OS n’existe plus. Donc le rappel concerné (et tous ceux après épuisement, si `computeNextFire` retourne `null` dès que le budget est à zéro) **n’est jamais reprogrammé** et ne refire pas “jusqu’à prochaine édition” (voire jamais si l’utilisateur n’édite pas l’événement).

**Sévérité :** **ÉLEVÉE** (perte fonctionnelle durable ; et contredit le KDoc en tête de classe : “so a reminder is never silently dropped” — ici il peut l’être)

**Statut :** **CONFIRMÉ** sur la logique locale de ce fichier.  
Pour trancher l’ampleur exacte, il faut lire : `ReminderScheduling.computeNextFire` + comment `ExpansionBudget.isExhausted` est testé (est-ce que `computeNextFire` renvoie `null` immédiatement quand épuisé, ou seulement pour la série en cours).


## 3) Migration 5→6 : trace bornée, mais la construction de la liste ne l’est pas (et peut refaire une DoS au démarrage)

**Fichier:** `data/local/db/Migrations.kt:~118-151` (dans `migration5to6().migrate`, `val repairs = stored.filterNot(...).map { ... }` puis `repairs.take(MAX_TRACED_REPAIRS)`)

**Scénario concret :**
- DB v5 contient énormément de valeurs distinctes dans `events.time_zone` (typiquement via un import ancien bogué, ou un `.atbak` “hand-edited” comme vous le mentionnez ailleurs).
- La migration fait :
  - `SELECT DISTINCT time_zone FROM events` (déjà un scan complet),
  - puis pour **chaque** valeur non canonique : `UPDATE ... WHERE time_zone = ?` (k scans complets, vous le documentez),
  - **et** elle matérialise `repairs` comme **liste complète** de chaînes `"$raw->$repaired"` avant de ne garder que `take(20)` pour la trace.
- Donc le bornage `MAX_TRACED_REPAIRS = 20` borne uniquement ce qui est *enregistré*, pas ce qui est *alloué/calculé*.
- Résultat possible : ouverture DB au boot très longue / watchdog / process kill, et l’app peut rester en échec d’ouverture (cf. DR-1 : splash + écran d’échec).

**Sévérité :** **MOYENNE à ÉLEVÉE** (DoS au démarrage sur un corpus malformé ; surface “fichier non fiable” explicite dans votre modèle)

**Statut :** **CONFIRMÉ** (le `map { ... }` construit toute la liste ; `take()` est appliqué après)


## 4) Reset “clé irrécupérable” : l’ordre est corrigé, mais `deleteDatabase()` n’est pas vérifié ⇒ re-brick possible si la suppression échoue

**Fichier:** `data/local/db/DatabaseFactory.kt:~109-132` (`resetAfterUnrecoverableKey`, ligne `context.deleteDatabase(...)`)

**Scénario concret :**
- Chemin “clé irrécupérable” (vous assumez : il faut reset pour éviter brique).
- `markResetPending()` est posé (bien).
- `context.deleteDatabase(...)` **peut échouer** (retour `false`) : fichier verrouillé, souci FS, état anormal (ou autre détenteur si multiprocess / autre composant).
- Le code continue quand même : `keyManager.destroyKeyFile()`, puis génère une nouvelle clé.
- Si l’ancien fichier DB est toujours là (non supprimé), il reste chiffré avec une clé désormais détruite ⇒ à l’ouverture suivante SQLCipher ne peut pas l’ouvrir, et vous retombez dans une boucle d’échec (très proche de la famille de brique que vous décrivez dans le commentaire, juste déplacée).

**Sévérité :** **ÉLEVÉE** (risque de brique “sans issue” même dans le chemin censé restaurer)

**Statut :** **PROBABLE**  
Pour confirmer, il faut regarder :
- si l’app peut avoir **plus d’un process** ou plus d’un point d’entrée concurrent qui ouvre la DB,
- et si, dans votre contexte réel, `deleteDatabase` peut échouer (tests sur device low-storage, fichiers sidecars, DB ouverte, etc.). Le fait que le retour ne soit pas checké rend la brique possible dès que ça arrive.


## 5) PendingIntent : `reminderId.toInt()` peut collisionner si IDs grands ⇒ annulations/écrasements d’alarmes

**Fichier:** `system/alarm/ReminderScheduler.kt:~170-198` (`buildPendingIntent`, `PendingIntent.getBroadcast(..., reminderId.toInt(), ...)`)

**Scénario concret :**
- Un utilisateur restaure/import une base (ou un `.atbak`) où les `reminders.id` sont très grands (Long), ou bien base ancienne avec autoincrement élevé.
- Conversion en `Int` tronque/overflow ⇒ deux reminders différents peuvent produire le même `requestCode`.
- Conséquence : un schedule peut écraser l’autre (même action/class), ou un cancel en annuler un autre.

**Sévérité :** **MOYENNE** (perte de rappels, comportement non déterministe)

**Statut :** **PROBABLE**  
Pour trancher : vérifier contraintes réelles sur `reminders.id` (Room schema/autoincrement, import/restore qui peut fixer des IDs arbitraires, etc.).


---

## Rien à signaler (dans ces extraits)
- `AppLockManager.kt` : le Mutex couvre bien le read-modify-write + la vérification (le correctif S16/F6 est appliqué de façon cohérente), et le clamp wall-clock→elapsed est sain.
- `BoundedRead.kt` : la lecture est correctement bornée sur les **octets lus** (le point principal F4), et la protection “empty reads total” est cohérente avec l’objectif anti-spin (même si elle peut refuser un provider bizarre, c’est un choix de sûreté).
- `MainActivity.kt` : la logique FLAG_SECURE onPause/onResume/onStop est globalement symétrique et traite bien les cas “unknown” vs “true” comme décrit.

Si tu me donnes `ReminderScheduling.computeNextFire` / `ExpansionBudget` et le manifest du `ReminderReceiver` (exported? permission?), je peux confirmer/infirmer l’ampleur exacte du point (2) et vérifier qu’aucune app tierce ne peut provoquer des fires/snoozes abusifs.