# Security — Agenda Tech

English · 🇫🇷 [Version française](SECURITY.fr.md)

## Threat model

Agenda Tech is a **local, offline** calendar. Its data (events, places, notes, reminders) never leaves
the device: no `INTERNET` permission is declared, and none may be. The network attack surface is
therefore zero by construction; protection is about data **at rest** and **exposure on screen**.

## Encryption at rest

- **SQLCipher** encrypts the whole `agendatech.db` file (AES-256).
- The **SQLCipher passphrase** is a random 32-byte key generated on first run, **wrapped
  (AES-256-GCM) by an AndroidKeyStore key** (`agendatech_db_master`) — hardware-backed (TEE/StrongBox)
  on compatible devices, non-exportable. The wrapped blob is stored in `filesDir/db/master.key`. See
  `DatabaseKeyManager` / `KeystoreManager` / `AeadCipher`.
- **No plaintext KEK.** The master key is never persisted in clear: without the device's
  AndroidKeyStore key, `master.key` — and therefore the database — is useless.
- **Residence in memory (accepted).** SQLCipher re-reads the passphrase every time its pool opens a
  connection, so it must stay in memory while the database is open. The copy handed to SQLCipher
  lives for the process lifetime; the one the app holds is wiped as soon as construction is done.
  This is a constraint of the library, not a choice — wiping earlier would break later connections.
- **Robustness**: the database is only reset when the key is **proven lost** — the `master.key` blob
  rejected by a Keystore key that is still present, or the alias absent on Android 12 and later
  (`KEY_NOT_FOUND`). Any other failure (Keystore unreachable, I/O error, unknown exception) leaves the
  database intact: one more attempt, then a refusal to open.
- **A key is never created while reading** (fix of 2026-09-15). On Android 8 to 11,
  `KeyStore.getKey` also returns `null` when the Keystore daemon is momentarily unreachable (read in
  the AOSP source). The old code then generated a new key under the same alias, which destroyed the
  real one: the agenda was reset as "unrecoverable", or the PIN could never be verified again. The
  three reads (database key, PIN, backup password) now go through `KeystoreManager.loadExistingKey`.
  **Accepted trade-off**: on Android 8 to 11, a key that is really gone leaves the app on the startup
  failure screen instead of starting over empty — that data was lost anyway.

### Known posture (accepted, to revisit if needed)

The database key is **not** protected by user authentication (`setUserAuthenticationRequired =
false`), exactly like the SMS Tech foundation: protection at rest relies on the device lock plus the
AndroidKeyStore.

## App lock (PIN / biometrics)

An **optional lock** keeps the UI behind a PIN and/or biometrics (`AppLockManager`, `LockScreen`,
`LockRepository`). It is independent of the database encryption (which stays active whatever
happens): it is a **UI gate**, and does not change the cryptographic posture of the database key.

- **The PIN is never stored.** Only a **PBKDF2-HMAC-SHA256** hash is kept (120,000 iterations, random
  16-byte salt, 256-bit key). Comparison is constant-time (`MessageDigest.isEqual`).
- **Wrapped hash (LOCK-1).** The `salt || hash` blob is **encrypted with AES-256-GCM under an
  AndroidKeyStore key** (`agendatech_pin_wrap`) before it lands in the DataStore file (not encrypted
  by default) — same pattern as the database key. A PIN has a tiny keyspace: without this wrapping, a
  plaintext salt and hash could be cracked offline in seconds. The wrapping stops simple file
  exfiltration (a root extraction with code execution remains out of scope).
- **Anti brute-force (LOCK-4).** After 5 wrong attempts, an increasing back-off (10 s, 20 s… capped
  at 60 s) is enforced before each new attempt. The live countdown uses the **monotonic** clock
  (`SystemClock.elapsedRealtime`), immune to clock changes.
- **The counter survives process death (audit SEC-2, v0.5.2).** The state used to live only in
  memory: a simple *force-stop* — no privilege needed — reset the counter and handed out 5 fresh
  attempts at every relaunch, cancelling the escalation. It is now persisted (`LockThrottleStore`).
  The 60 s cap guarantees a forgotten lock-out cannot block the app. The persisted deadline uses the
  wall clock, which the user can move — so it is **clamped to one step** on reload: moving the clock
  forward skips at most one wait, never the attempt counter.
- **One attempt = one atomic operation (audits S16 + SEC-2, v0.5.5).** The back-off, the PIN check and
  recording the result happen **under a single lock** (`AppLockManager.attemptPin`).

  The decision used to be split in two by the ~100 ms of PBKDF2, with the lock released: any attempt
  started in that window read a zero back-off and got its try. The counter stayed right, but the
  back-off applied to none of them — a burst of taps therefore got several attempts per 60 s window.

  ⚠️ This now holds for **both** screens that ask for a PIN: the lock screen and **Settings**. S16 had
  only fixed the first, while this sentence already claimed parity — and the second is precisely the
  screen from which the lock can be **turned off** (audit SEC-2).
- **Settings reset: said, never silent (audit S15, v0.5.5).** The preferences file holds
  `lock_enabled` and the wrapped PIN. If it becomes unreadable (power cut during a write, corrupt
  sector, or a byte changed by anyone with one-off file access), it is replaced by defaults — and **the
  app lock ends up turned off**. It cannot be restored: the wrapped PIN died with the file, and locking
  with nothing to type would be the only worse outcome. The app **says so explicitly at the next
  start**, so that nobody keeps trusting a protection that is no longer there.
- **Re-authentication (LOCK-6).** Turning the lock off or changing the PIN first requires the current
  PIN.
- **Biometrics: Class 3 only (audit F3, v0.5.3).** `BiometricPrompt` only accepts `BIOMETRIC_STRONG`.
  `BIOMETRIC_WEAK` (Class 2) is precisely the tier the platform forbids binding to a Keystore key,
  because many OEM face unlocks can be fooled by a photo; the earlier UX compromise (LOCK-9) is
  therefore withdrawn. `DEVICE_CREDENTIAL` stays excluded: it would readmit the same weak tier
  indirectly. A device without Class 3 falls back to the PIN, which is throttled, and the PIN field is
  shown in every case — the hardening cannot lock the user out of their data. The policy lives in one
  place, `StrongBiometrics`: availability and the authenticator mask are the same question, asked in
  the same place by the prompt, the lock screen and the setting.
- **Cryptographic binding (audit F3, v0.5.4).** Unlocking no longer depends on the
  `onAuthenticationSucceeded` callback alone — a claim made by the app's own process, and so
  worthless wherever that process can be tampered with. `BiometricPrompt` receives a `CryptoObject`
  backed by a dedicated AndroidKeyStore key (`agendatech_biometric_gate`, see `BiometricGate`),
  created with `setUserAuthenticationRequired(true)` and **no** validity window: the TEE therefore
  requires a Class 3 authentication for **every** use. The UI only opens if the cryptographic
  operation actually succeeds. On API 30+, the accepted tier is pinned to `AUTH_BIOMETRIC_STRONG` —
  the Class 3 policy is then enforced by the OS, not only by our own check.
- **Accepted residual (API 26–29).** `setUserAuthenticationParameters` only exists from API 30: on
  older devices, the key does require an authentication per use, but the Class 3 tier is not pinned in
  the Keystore itself — it then rests on `BiometricPrompt`'s `setAllowedAuthenticators` alone. Not
  verified on a real device below API 30.
- **Re-enrolment.** The key is created with `setInvalidatedByBiometricEnrollment(true)`: adding a
  fingerprint destroys it, which is the intended behaviour (whoever can enrol must not inherit the
  unlock). `KeyPermanentlyInvalidatedException` is caught when the cipher is initialised, the dead key
  is deleted, the biometric preference turned off and the user told — then the PIN takes over, never a
  button that fails silently.
- **Scope.** Biometrics guard the **screen**. They do not derive the database key: that key stays
  wrapped by its own AndroidKeyStore key, independently of the app lock.
- **Accepted residual (LOCK-8).** The PIN passes through an immutable `String` in the Compose state
  before being converted to a `CharArray` (wiped after hashing). Scrubbing the whole Compose path would
  be disproportionate (`OutlinedTextField` is natively `String`-backed); exploiting it would require a
  memory dump of a non-debuggable release build.

## Exposure on screen

- `FLAG_SECURE` is set on the `Activity` by default: no preview in Recents, screenshots blocked. A
  privacy setting relaxes it, **but** it is **forced on** in two cases (LOCK-2): while the lock
  **actually guards the screen** (app locked), and while its state is **not yet resolved** at
  startup.

  ⚠️ Once the PIN is entered, your "allow screenshots" setting **takes over again**: that is the
  intended behaviour, and it is not what this paragraph used to say. It announced "forced on while the
  lock is enabled", which naturally reads as "while the lock feature is enabled in Settings" — a user
  could therefore believe their screenshots blocked when they were not (audit S14).

- **Going to the background.** `FLAG_SECURE` is raised as early as `onPause`, one lifecycle step
  **before** the re-lock, and again at `onStop`. The reason is a race that had never been measured:
  `Window.addFlags` only reaches the window manager at the next traversal, while the system captures
  the task snapshot around that very transition. Raising the flag earlier gives the traversal time to
  arrive.

- **Pickers opened by the app: no new PIN (2026-09-15).** The maintainer's product rule: as long as
  the user stays in the app, the PIN is not asked again. A file picker or the ringtone picker opened
  **by the app** therefore no longer triggers the `onStop` re-lock (`PickerRelockPolicy`, through the
  mandatory `rememberAppResultLauncher`, enforced by a test on the source). Three bounds: the exemption
  only applies if the stop follows the launch within **3 s**; **turning the screen off** locks at once,
  whether it goes off during the picker or is already off at the stop; coming back **more than
  3 minutes** later locks anyway. `FLAG_SECURE` is raised as before.

  **Permission requests** get no exemption: their translucent dialog pauses the activity without
  stopping it, so they already did not lock. Exempting them opened a hole found by an external review
  (Gemini Pro): a permission request, Home within 3 s, and the app reopened unlocked from Recents.

  Coming back into the app **through a notification, the widget or the icon** while a picker is open
  locks as well: that is entering from outside (`onNewIntent`, found by the pre-tag audit of v1.1.1 —
  the `singleTask` activity resumed without a new `onStop`).

  ⚠️ **Known limit, accepted by the maintainer.** Once the picker is on screen, the app sees nothing
  more: coming back **through Recents** carries no intent, and cannot be told apart from the picker
  returning its result. Someone who leaves the picker and comes back through Recents **with the screen
  on** finds the agenda unlocked if they do so within 3 minutes. Going to another app (browser, system
  settings, map), pressing Home or opening a notification **always locks**. So does leaving for
  Android's per-app language screen from Settings: it is not a picker the app waits on.
- **Unlocking returns the screen you left.** The lock screen **replaces** the interface — nothing of it
  is drawn, touchable or read by accessibility services, its dialogs included — but the navigation and
  the screens' state are kept above the lock (`LockedAppHost`). Unlocking used to return to the month
  view: a restore waiting for its password vanished, an entry interrupted by a phone call was lost.
  The same holds when a language change recreates the activity while the app is locked (measured on
  API 34).

  ⚠️ This paragraph used to claim that the Recents preview "can never leak". An absolute guarantee
  resting on an unmeasured race is not one (audit S5). What is true and checkable today: the flag is
  raised at the **first** lifecycle point the app controls after leaving the foreground, and locking
  has been **synchronous** since F13 — it no longer depends on a disk read that finished after the
  capture.

### Home-screen widget (known limitation)

The optional widget shows today's date and the **titles** of the next events directly on the home
screen. That is what a calendar widget is expected to do (same as Google Calendar), but `FLAG_SECURE`
**does not apply** to widgets — their content is rendered by the launcher, outside the app's control.

Two safeguards: the widget is **opt-in** (the user chooses to place it); a "hide titles" setting then
shows only the time. **When the app lock is on, titles are hidden in the widget whatever that setting
says (LOCK-3)** — turning the lock on therefore never leaves a readable title on the home screen.

### Reminder notification and app lock — accepted asymmetry (F9)

The app lock hides titles in the **widget**, but **not** in the **reminder notification**, which keeps
showing the event title. The two surfaces therefore respond differently to the same setting. This is
a **product trade-off**, not an oversight: it is written here so that it stops being one.

**Decision: keep the current behaviour.**

- A reminder whose title is hidden no longer says what it reminds you of. That is the product's main
  function, and degrading it for everyone would protect a scenario the two safeguards below already
  cover.
- The **device's** lock screen is handled separately, and correctly: notification visibility is
  restricted there, so a locked phone lying on a table does not expose the title.
- The widget and the notification are not comparable surfaces. The widget is **permanent** on the
  home screen, visible to anyone, without anyone doing anything; the notification is **transient**,
  triggered by a reminder the user set themselves.

**What this implies, and what you should know**: on an **unlocked** phone handed to someone, a reminder
that fires shows the event title, even with the app lock on. Anyone who needs stronger
confidentiality than that must rely on the device lock, not on the app's.

To revisit if a third use case comes along — the right answer would then probably be an explicit
setting, not an imposed masking.

## Device calendar import (READ_CALENDAR)

The app can copy, on demand, the events **already synced locally** onto the phone (Google Calendar,
Exchange, local calendars) through the Android Calendar Provider (`CalendarContract`). It is the app's
**only** dangerous permission, and it **does not break** the zero-network doctrine:

- **Read-only, 100 % local.** `READ_CALENDAR` only — never `WRITE_CALENDAR` nor `GET_ACCOUNTS`. No
  network connection, no access to the Google account: Agenda Tech only reads what the system has
  already stored on the device. Remote sync stays with the system apps.
- **Requested at runtime, scoped to the import screen** (`DeviceImportScreen`), never at startup. No
  `CalendarContract` query before it is granted; a refusal is handled cleanly (no crash, no loop).
- **Defences on third-party content** (a shared calendar can be booby-trapped): **parameterised**
  ContentResolver queries (no SQL concatenation), cursors closed (`use{}`), a `MAX_EVENTS` cap
  (20,000) and a field-length cap, **anti-Bidi** cleaning shared with the `.ics` import
  (`BidiSanitizer`), **tolerant** RRULE/EXDATE/duration parsing (returns null rather than crashing,
  duration bounded against overflow). A resilient import: an error on one calendar is logged and
  skipped, never propagated. Database writes are **atomic per batch** (`upsertAll` in a transaction).
- **Idempotent import (safe refresh).** Each device calendar is linked through a stable `source_id`
  (reused on re-import, no duplicated calendar) and each event through a `source_uid` (`_sync_id`,
  falling back to `rowid`): re-importing updates rows in place and adds new events instead of
  duplicating everything. This is NOT a two-way sync: an event deleted at the source is not removed
  (additive import). Caveat: an event created offline and not yet synced server-side may be inserted
  again once, on the first `rowid → _sync_id` pass. VALARM is out of scope (as for the `.ics` import).

## Imported content and recurrence expansion

An `.ics`, a system calendar **and an `.atbak` backup** are all **third-party content**: they are
treated as hostile, including once they are in the database.

⚠️ The `.atbak` was missing from this list, and the code followed the list (audit S7, v0.5.5). Yet it
is the format that carries the **whole** agenda, and a backup readily comes from elsewhere — "here is
my shared agenda, password xxx". Its time zone was normalised, but its six free-text fields went
through no cleaning: a `U+202E` in a title reversed its reading in the month view, the timeline, **the
home-screen widget** and the reminder notification. The anti-Bidi cleaning and the length cap now
apply to it as to the other two.

- **A cap on the NUMBER of events (audit S12, v0.5.5).** The import caps were all in **bytes** —
  5 MiB for an `.ics`, 16 MiB for an `.atbak` — which bounds memory but not the number of rows written:
  5 MiB of minimal `VEVENT` blocks make about 87,000 events. `ImportLimits.MAX_EVENTS` gives one
  shared answer. A **file** chosen by the user is refused **as a whole** with a message — importing
  part of it looks exactly like importing all of it — while the **device** calendar, a live provider
  with no file to hand back, is truncated.

  ✅ That truncation **is reported to the user** (audit SEC-6, shipped). The fact is *returned* by
  `DeviceCalendarRepositoryImpl.readEvents()` (`DeviceRead(rows, truncated)`), not logged — the log
  would be inert on a published version (`NoOpReleaseTree`). `ImportDeviceEventsUseCase` carries it up
  to `DeviceImportScreen`, which shows `device_import_truncated` (present in all five languages),
  distinct from the success message and from the partial-failure one: a truncation is neither, and
  conflating them would be hiding it.

  The cap applies per **import**, not per calendar (audit DR-9): `ImportDeviceEventsUseCase` holds
  **a single allowance** (`var remaining = ImportLimits.MAX_EVENTS`) spent across the selected
  calendars. Selecting ten calendars at once can therefore no longer exceed the announced number —
  calendars left out for lack of allowance are counted as *truncated*, never as *failed*, because
  nothing went wrong.

  > These two paragraphs said the opposite until 2026-09-11: they described a silent truncation and a
  > per-calendar cap, both fixed since. It is the same pattern the F-Droid review of 26/08 had found
  > for the backup password — a documentation surface that goes stale silently — but the other way
  > round: the document described the app as **less** safe than it is. Re-read these surfaces (this
  > file, `PRIVACY` and `TERMS` in all their languages, the manifest) with every feature that touches
  > storage, permissions or the network.

- **Bounded `INTERVAL` (audit F1/F5/F7, v0.5.3).** An absurd interval raised an uncaught
  `DateTimeException` in `RecurrenceExpander`: every view and the widget crashed on every render, in a
  loop from launch, and the faulty event became unreachable — the only way out was to erase all data.
  `RecurrenceRule.MAX_INTERVAL` is enforced in `init` and bounded at the **five** ingestion points:
  `.ics` import, device import, **reading from the database**, **`.atbak` restore** and the editor.
  The last two are essential: without bounding on read, a row already written by an affected version
  would move the crash instead of fixing it.
- **Tolerant RRULE bounds (v0.5.4).** A `COUNT` below 1, or `COUNT` and `UNTIL` together, break the
  model's invariants. Rather than make the event lose its whole recurrence, the faulty bound is
  dropped and `COUNT` wins — the same reading in the `.ics` import and in the device import.
- **Global expansion budget (audit F8, v0.5.4).** The per-event cap says nothing about the number of
  events, and that is exactly what an import controls. A single `ExpansionBudget` is shared by a whole
  rendering pass: a pathological agenda is **truncated** (and logged), never turned into a frozen UI.

## Backups

- `allowBackup="false"` plus exclusion rules (`data_extraction_rules.xml`, `backup_rules.xml`): no
  cloud backup and no device-to-device transfer. The wrapped key being bound to the local
  AndroidKeyStore, exporting it would be useless and needlessly exposing.

### Encrypted `.atbak` backup (manual export)

Since the database key is bound to the device, a copy usable elsewhere must be encrypted by a secret
the user knows — hence a password, not the KeyStore key.

```
[magic:5 "ATBAK"][envVersion:1][kdfId:1][iterations:4 BE][saltLen:1][salt:16]   ← header (28 B)
[AeadCipher: version:1 | iv:12 | ciphertext+tag]                                ← body
```

- **KDF**: PBKDF2-HMAC-SHA256, **600,000 iterations** (the OWASP floor), random 16-byte salt, 256-bit
  key. Password **12 characters minimum**. For a **manual** export it is stored nowhere: a forgotten
  password makes the file permanently unreadable — the price of offline encryption. The **automatic
  backup** is the only exception, documented below: it cannot ask for the password every week, so it
  keeps it.
- **Encryption**: AES-256-GCM through `AeadCipher` (the same primitive as the database key).
- **Authenticated header (AAD)**: the header is not secret but it is **authenticated**. Rewriting the
  salt, or lowering `iterations` to a cheap value to make an offline attack trivial, breaks the GCM tag
  and the file refuses to open. On read, `iterations` outside `[100,000, 10,000,000]` is refused before
  any computation (denial of service by a hostile file).
- **Upgradability**: `kdfId` and the cost are written explicitly, not implied — moving to Argon2id
  later will not invalidate files already written. A backup must still open years from now; that is
  its only reason to exist.
- **Atomic restore**: the file is fully decrypted and validated **before** any write, then the agenda
  is replaced in one transaction. A truncated, tampered-with, or differently-passworded file leaves
  the existing agenda intact.
- **Accepted residual (same as LOCK-8).** The backup password also passes through an immutable
  `String` in the Compose state before being converted to a `CharArray` (wiped after derivation, on
  every path — cancellation and failure included). Same reason, same trade-off: `OutlinedTextField` is
  natively `String`-backed, and exploiting it would require a memory dump of a non-debuggable release
  build.
- **Validation before writing.** The file is refused as a whole (never partly applied) if it carries
  an id ≤ 0 (Room silently renumbers an id 0 on an `autoGenerate` key, which would leave every
  reference dangling), a duplicated id, an event attached to a missing calendar, or an override
  pointing at a missing parent (no FK covers `recurrence_parent_id`).
- A wrong password and a corrupted file are **indistinguishable** (both = invalid GCM tag): the UI
  says "wrong password **or** damaged file", without confirming which.

### Automatic backup (weekly) — the password is kept

This is the **only** place in the app where a user secret is stored reversibly. The trade-off is
explicit, and the user chooses it by turning the option on.

- **What it costs.** The password now exists on the device, where it used to exist only in the user's
  head. Anyone who fully compromises the device **while it runs** can make the app decrypt that
  password.
- **What it buys.** The file stays openable by hand, on any machine, with that password. A key that
  never left this phone would produce backups that die with it — and a backup exists precisely for
  the day the phone is gone.
- **What the wrapping still protects.** The password is encrypted with AES-256-GCM by a **dedicated
  AndroidKeyStore key** (`agendatech_autobackup_pw`), non-exportable, TEE-backed on compatible
  devices, before it lands in Base64 in the DataStore. **Stealing the file** — a copy of the
  DataStore, a file-read exploit, an adb backup — is therefore not enough: the key does not leave the
  device.
- **An alias distinct from the PIN's**, on purpose: turning the app lock off deletes
  `agendatech_pin_wrap`, and that must not take the backup password with it. They are two independent
  user decisions.
- **Turning the option off erases the password** and deletes the Keystore key that wrapped it.
  Without that deletion, the blob would stay decryptable by anyone who later restored the DataStore
  file on this same device.
- **Never a `String`.** The password goes from the input field to the Keystore as a `CharArray` then a
  `ByteArray`, both wiped; a `String` would stay on the heap at the GC's discretion, with no way to
  erase it. (The LOCK-8 residual of the Compose field is the same as for the export.)
- **No storage permission.** Writing goes through the Storage Access Framework: the app's access is
  limited to the folder the user picked in the system picker, for as long as they keep the grant.
  Every run re-checks that grant instead of assuming it, and reports its absence on the Backup
  screen.
- **Rotation can only reach files written by this feature.** It only deletes names of the form
  `agenda-tech-auto-<date>.atbak`, never a manual export `agenda-tech-<date>.atbak` nor anything else
  in the folder — the user is expected to point at a folder they already use. It is the feature's most
  dangerous rule, so it lives in the domain layer and is tested on its own.
- **Known limit — "Force stop" suspends the schedule.** Android blocks the pending jobs of an app the
  user force-stopped (or that the manufacturer put into aggressive standby), until the app is next
  opened. The setting stays "on", the screen shows the last known run, and **nothing runs any more**.
  The app cannot detect it from inside: it is not running. The "your agenda has changed since your
  last backup" warning remains the safety net, since the last-backup date no longer moves.
- **A failure cannot pass for a success.** The result of every run is recorded and shown; only a run
  that actually produced a file moves the "last backup" date, so the "your agenda has changed since
  your last backup" warning stays armed as long as nothing was written.

## Supported versions

Only the **latest published release** receives security fixes: an app without network access does
not update itself, and a fix only ships in a new version.

## Reporting a vulnerability

Think you have found a vulnerability? **Please do not open a public issue.** Two channels, either
one:

- **GitHub private reporting**: the repository's *Security* tab, then *Report a vulnerability*
  (https://github.com/gitubpatrice/AGENDA-TECH/security/advisories/new);
- **email**: **contact@files-tech.com**, subject `[SECURITY] Agenda Tech — <summary>`.

Please allow 90 days before any public disclosure of an unfixed issue.
