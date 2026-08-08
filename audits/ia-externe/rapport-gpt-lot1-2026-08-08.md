## Question Android 12 — révocation “exact alarms”
**Je ne sais pas.** (Je n’ai pas une certitude vérifiable hors dépôt/doc officielle ici sur “les alarmes déjà posées sont-elles annulées” lors de la révocation, ni sur l’émission systématique de `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` dans ce cas précis.)

---

## Motif 1 — *Garde sur l’AFFICHAGE, pas sur l’ACCÈS*
**Aucun défaut certain dans les fichiers fournis** concernant un contournement “UI masquée mais donnée accessible” *au sein de l’app* : `MainActivity` ne rend `AppRoot()` que si `LockState.UNLOCKED`.

Points à clarifier (questions, pas constats) :
- **Manifest** : `ReminderReceiver`, `BootReceiver`, et le receiver/widget Glance sont-ils `exported` ? Si oui, quel est le modèle de protection (permission, `exported=false`, intent-filters limités) pour éviter des broadcasts tiers (DoS/réveils intempestifs) ?
- **Process widget** : avez-vous déclaré un `android:process` spécifique pour la partie widget/receiver Glance ? (vous mentionnez “autre processus” ; je ne peux pas le confirmer ici).

---

## Motif 2 — *Le repli échoue du mauvais côté* (fail-open / perte silencieuse / contournement)
### 1) `.ics` import : la limite de taille est vérifiée mais **pas appliquée** au read réel (DoS/OOM)
- **Fichier:ligne** : `IcsViewModel.kt:63-69`
- **Motif** : 2
- **Sévérité** : **CRITIQUE**
- **Scénario reproductible**
  1. L’utilisateur choisit un URI SAF fourni par un `DocumentProvider` (ou content provider malicieux) dont `openFileDescriptor(...).statSize` renvoie une petite taille (ex: 1 Ko).
  2. `check(size in 0..MAX_ICS_BYTES)` passe.
  3. `openInputStream(uri).bufferedReader().readText()` lit en réalité **bien plus** (ex: dizaines/centaines de MB).
  4. Résultat : **OOM / crash processus** pendant l’import, alors que l’import est l’unique entrée externe et doit échouer proprement.
- **Confiance** : **CERTAIN** (le code lit sans borne ; la borne ne protège que si `statSize` est fiable)
- **Correctif proposé**
  - Remplacer `readText()` par une lecture **streamée bornée** (même technique que `BackupViewModel.readFile`), et **arrêter** au-delà de `MAX_ICS_BYTES`.
  - Option : conserver le check `statSize` quand il est fiable, mais ne pas lui faire confiance comme unique garde.
- **Risque de régression**
  - L’import de fichiers réellement > 5 MB échouera plus tôt (déjà le cas), mais surtout : certains providers qui “trichent” sur la taille ne pourront plus faire planter l’app (amélioration attendue).

### 2) `.ics` import : `statSize == -1` (taille inconnue) fait échouer des imports légitimes
- **Fichier:ligne** : `IcsViewModel.kt:63-66`
- **Motif** : 2 (repli “refus” — ici c’est plutôt une **robustesse** ; mais c’est un comportement faux côté UX)
- **Sévérité** : **MAJEUR**
- **Scénario reproductible**
  1. L’utilisateur choisit un `.ics` via un provider qui ne connaît pas la taille (cas courant : certains stockages cloud / providers), `statSize = -1`.
  2. `check(size in 0..MAX_ICS_BYTES)` échoue.
  3. Import impossible même si le fichier fait 10 Ko.
- **Confiance** : **CERTAIN**
- **Correctif proposé**
  - Si `statSize < 0`, basculer sur une lecture streamée bornée (cf. point précédent) au lieu de refuser.
- **Risque de régression**
  - Très faible : vous remplacez un refus systématique par un comportement borné et sûr.

### 3) Restauration backup : si l’énumération des reminders “avant wipe” échoue, vous **laissez des alarmes orphelines**
- **Fichier:ligne** : `BackupViewModel.kt:~120` (ligne du `staleReminderIds = runCatching { reminderRepository.getAll()... }.getOrDefault(emptyList())`)
- **Motif** : 2
- **Sévérité** : **MAJEUR**
- **Scénario reproductible**
  1. L’utilisateur lance une restauration.
  2. `reminderRepository.getAll()` lève une exception (ex : DB temporairement indisponible/locked, problème d’I/O transitoire).
  3. `getOrDefault(emptyList())` ⇒ `staleReminderIds = []`.
  4. La restauration réussit ; `cancelReminders([])` ne désarme rien.
  5. Les **anciennes alarmes** (de l’agenda remplacé) continuent à réveiller `ReminderReceiver` jusqu’à leur tir (elles ne postent probablement pas de notif si l’event/reminder n’existe plus, mais réveillent quand même le process/CPU).
- **Confiance** : **CERTAIN** sur le comportement “fallback => ne cancel rien”, **HYPOTHÈSE** sur la facilité de provoquer l’exception sans rendre la restauration elle-même impossible (implémentations repo non fournies).
- **Correctif proposé**
  - Sur échec d’énumération des reminders : **échouer la restauration** (fail-closed) avec un message “impossible de sécuriser l’annulation des anciennes alarmes”.
  - Variante : retenter une fois après un petit délai si vous suspectez un `SQLiteDatabaseLockedException`.
- **Risque de régression**
  - Vous pouvez bloquer une restauration dans des situations où elle “marchait” avant (mais laissait des alarmes orphelines). C’est un arbitrage UX/robustesse.

---

## Motif 3 — *Le jumeau asymétrique* (deux chemins supposés équivalents, un seul correct)
### 1) Import `.ics` vs import calendrier appareil : gestion des TZID invalides **diverge** et peut décaler les occurrences
- **Fichier:ligne** : `IcsCodec.kt:162-166` (calcul `zoneId`), et `IcsCodec.kt:174-184` (dans `parseDateTime`, fallback ZoneId)
- **Motif** : 3
- **Sévérité** : **MAJEUR**
- **Scénario reproductible**
  1. Importer un `.ics` contenant par ex. `DTSTART;TZID=Bad/Zone:20260101T090000` (TZID inconnu).
  2. `parseDateTime()` (ligne ~174-184) fait bien un fallback : `ZoneId.of("Bad/Zone")` échoue ⇒ utilise `defaultZone` pour calculer l’instant.
  3. MAIS `parseVEvent()` stocke `timeZoneId = dtStart.params["TZID"]` (ici `"Bad/Zone"`) (ligne ~162-166).
  4. Ensuite, ailleurs :
     - `RecurrenceExpander.resolveZone("Bad/Zone")` retombe sur **UTC** (pas `defaultZone`) ⇒ les occurrences d’une série et/ou l’affichage peuvent être décalés.
     - `IcsCodec.encode` ré-exporte avec `TZID=Bad/Zone` mais calcule le local via `zoneOf(event)` qui fallback aussi sur UTC ⇒ heure exportée incohérente avec l’instant importé.
  5. Résultat : un événement importé peut “changer d’heure”/se décaler au rendu, surtout en cas de récurrence (ancrage murale dans une zone qui devient UTC par défaut).
- **Confiance** : **CERTAIN**
- **Correctif proposé**
  - À l’import, ne jamais persister un `timeZoneId` invalide :
    - soit valider `TZID` (tentative `ZoneId.of`) et sinon stocker `defaultZone.id`,
    - soit changer la politique globale “unknown tz fallback” pour matcher le fallback utilisé au parsing (mais ça impacte d’autres lecteurs).
- **Risque de régression**
  - Changement de comportement sur des fichiers `.ics` “déjà mauvais” (TZID invalide). Mais aujourd’hui ces fichiers produisent des résultats instables/incohérents ; les rendre cohérents est généralement souhaitable.

### 2) Lecture fichier externe : `.ics` (bornage par `statSize`) vs `.atbak` (bornage streamé)
- **Fichier:ligne** : `IcsViewModel.kt:63-69` vs `BackupViewModel.kt:readFile()`
- **Motif** : 3
- **Sévérité** : **MAJEUR**
- **Scénario**
  - Un provider à `statSize` inconnu/mensonger casse l’import `.ics` (refus ou OOM), alors que la restauration `.atbak` est robuste grâce au bornage streamé.
- **Confiance** : **CERTAIN**

---

## Motif 4 — *Chemin mort + test vert*
**Aucun défaut identifié** sur ce motif dans les fichiers fournis (je n’ai pas de tests ici, et je ne vois pas de branche manifestement non atteignable couverte par des tests “verts”).

---

## Import `.ics` (externe) — autres défauts concrets
### EXDATE répétés : seule la dernière ligne EXDATE est conservée
- **Fichier:ligne** : `IcsCodec.kt:133-151` + `IcsCodec.kt:146`
- **Motif** : autre (ou 3 si vous le rattachez à “tolérance import” vs attentes RFC)
- **Sévérité** : **MAJEUR**
- **Scénario reproductible**
  1. `.ics` avec un `RRULE` et **deux** lignes `EXDATE`, par ex. :
     - `EXDATE:20260108T090000Z`
     - `EXDATE:20260115T090000Z`
  2. Dans `decode()`, vous faites `current[property.name] = property` ⇒ la seconde **écrase** la première.
  3. `parseRRule(..., props["EXDATE"], ...)` ne voit qu’une EXDATE ⇒ une occurrence annulée “revient”.
- **Confiance** : **CERTAIN**
- **Correctif proposé**
  - Stocker les propriétés multi-valuées (au moins `EXDATE`) comme liste, ou concaténer les valeurs EXDATE au fur et à mesure.
- **Risque de régression**
  - Faible : correction d’un cas RFC courant ; impact limité aux fichiers contenant plusieurs EXDATE.

---

## BroadcastReceiver / thread principal avant `goAsync()`
- `BootReceiver.onReceive`: check action puis `goAsync()` immédiatement ⇒ OK.
- `ReminderReceiver.onReceive`: fait `handler.handles(action)` + lecture extras avant `goAsync()` ⇒ a priori léger.

Question (HYPOTHÈSE à confirmer) :
- Votre `@ApplicationScope CoroutineScope` est-il bien basé sur un dispatcher non-main (ex. `Dispatchers.Default`/`IO`) ? Sinon, même après `goAsync()`, vous pourriez exécuter trop de travail sur le Main.

---

## Widget Glance / lock
Dans le code fourni, le widget **force-hide les titres** si lock activé (`lockRepository.isLockEnabled()`), ce qui répond à l’objectif commenté. Je ne vois pas de contournement “affiché vs accessible” dans ce périmètre.

---

Si tu me donnes le `AndroidManifest.xml` (ou au moins les entrées receivers/widget + exported/permissions) et l’implémentation des repositories (`EventRepository`, `ReminderRepository`, `BackupRepository`), je peux verrouiller (CERTAIN vs HYPOTHÈSE) les scénarios autour :
- broadcasts externes possibles,
- exceptions DB plausibles pendant restore,
- transactions atomiques sur `upsertAll` / `replaceAll`.