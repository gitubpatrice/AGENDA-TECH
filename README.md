# Agenda Tech

A **fully local, encrypted, zero-network** Android calendar — the calendar piece of the **Files
Tech** suite, built to leave Google Calendar without handing anything to a cloud.

[![Licence](https://img.shields.io/badge/licence-Apache%202.0-blue.svg)](LICENSE)
[![Zero network](https://img.shields.io/badge/network-zero-success.svg)](#privacy)
[![Platform](https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-brightgreen.svg)](#installation)

🇫🇷 [Version française](README.fr.md)

> **Test release** `v0.4.1`. Works end to end; being polished ahead of a first stable release. Bug
> reports are welcome in the [issues](https://github.com/gitubpatrice/AGENDA-TECH/issues).

## Installation

1. Download the **universal** APK (`agenda-tech-…-universal.apk`) from the
   [latest release](https://github.com/gitubpatrice/AGENDA-TECH/releases/latest).
2. Open it on your Android phone (8.0 or later). Allow "install unknown apps" if prompted.
3. That's it — no sign-up, no account, no connection.

The APK is **universal** (works on every device, no variant to pick) and **signed**.

## Features

- **Views**: Month (smooth swipe between months), Week, Day and Agenda (list).
- **Events**: create/edit, all-day, description, per-event colour.
- **Place**: label, postal address (street, postcode, city) and GPS coordinates — one tap opens the
  point in your maps app (with no location permission at all).
- **Recurrence** (RFC 5545): daily / weekly (chosen days) / monthly / yearly, with interval, ending
  after N occurrences or on a date, and **editing/deleting a single occurrence** (the iCalendar
  `RECURRENCE-ID` model).
- **Reminders** via exact alarms, re-armed after a reboot: preset delays or a free value, and your
  choice of sound (a system ringtone or your own audio file).
- **Encrypted `.atbak` backup**: export/restore **everything** (calendars, events, recurrences,
  places, reminders) in a password-protected file (PBKDF2 600,000 iterations + AES-256-GCM) — keep
  it wherever you like, cloud included.
- **`.ics` import / export** (RFC 5545) through the system file picker — an interchange format,
  which does not replace the backup (it loses reminders, colours and the calendar structure).
- **Import from the device calendar** (Google, Exchange, local calendars), **read-only** — the app
  copies what is already synced onto the phone, **with no network**.
- **Search** across the whole calendar (title, description, place, address, city) — accent- and
  case-insensitive: "reunion" finds "Réunion".
- **Optional lock** with a PIN and/or biometrics.
- **Dark theme** (GitHub-styled), home-screen **widgets**, French / English.

## Privacy

- **No internet permission.** No data leaves the device: no cloud, no analytics, no ads, no
  third-party tracking SDK.
- **Encrypted at rest.** A Room database backed by **SQLCipher** (AES-256); the master key is
  wrapped by the **Android KeyStore** (hardware/TEE on supported devices).
- **End-to-end encrypted backups.** The `.atbak` file is encrypted by your password alone: it stays
  unreadable even sitting on a cloud. Format documented in [SECURITY.md](SECURITY.md).
- **Privacy on screen.** `FLAG_SECURE` (no preview in Recents, screenshots blocked), cloud backups
  excluded (`allowBackup=false`).

Details: [SECURITY.md](SECURITY.md) · [PRIVACY.md](PRIVACY.md).

## Tech stack

Kotlin · Jetpack Compose (Material 3) · Hilt · Room + SQLCipher · Coroutines/Flow · typed
`Outcome`/`AppError` · Timber · JUnit5 + Truth + MockK tests.

`applicationId`: `com.filestech.agenda_tech` — `minSdk 26` (native java.time) / `compileSdk 36`.

### Architecture (layers)

```
core/      Outcome, AppError, crypto (AeadCipher, KeystoreManager, PinHasher), text, logging
domain/    pure models (Calendar, Event, RecurrenceRule, Reminder, DeviceCalendar/DeviceEvent
           + enums), repository interfaces, use cases, recurrence engine (DST-correct),
           device/ pure mapper from a calendar row to an Event      ← no Android dependency
data/      Room entities, DAOs, AppDatabase (SQLCipher), mappers, repository impls — including
           Calendar Provider access (device import), hidden behind a domain interface
di/        Hilt modules
ui/        theme, navigation, screens (Month/Week/Day/Agenda, editor, settings, lock…)
system/    exact alarms for reminders · notifications
widget/    Glance widgets (agenda + date icon)
```

The domain is **100% pure**; the only domain↔Room crossing point is
`data/repository/EntityMappers.kt`. Recurrence is stored in structured columns (`rrule_*`) on the
event — the iCalendar model (RRULE is a property of the VEVENT).

## Building from source

```bash
git clone https://github.com/gitubpatrice/AGENDA-TECH.git
cd AGENDA-TECH
./gradlew :app:assembleDebug          # debug APK (splits + universal)
./gradlew :app:testDebugUnitTest      # unit tests
```

Release signing expects a `keystore.properties` file (not versioned); the debug build does not need
one.

### Versions

`version.properties`, at the root, is the **single source** of `versionCode` / `versionName`:

```properties
versionCode=46
versionName=0.4.1
```

The `versionCode` must **always increase**: Android refuses to install an APK whose `versionCode` is
lower than the installed one, and the user only sees an unexplained failure. It is bumped **by
hand**, before building a release APK — never after.

The build **fails** if the file is missing or unreadable: producing nothing beats producing an APK
carrying a silently wrong version.

## Security & privacy

- Threat model, encryption, lock: [SECURITY.md](SECURITY.md).
- Privacy policy (permissions, data, contact): [PRIVACY.md](PRIVACY.md).
- Reporting a vulnerability: see [SECURITY.md](SECURITY.md#signalement).

## Licence

[Apache License 2.0](LICENSE) — © 2026 Patrice Haltaya / Files Tech.
