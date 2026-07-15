# Privacy Policy — Agenda Tech

_Last updated: 15 July 2026 — version 0.4.1_ · 🇫🇷 [Version française](PRIVACY.fr.md)

Agenda Tech (`com.filestech.agenda_tech`) is a **fully local** calendar app, built on one principle:
**your data never leaves your device.**

## In short

- **No data collected, no data transmitted.** The app declares **no internet permission**
  (`INTERNET`, `ACCESS_NETWORK_STATE`) — it is technically incapable of sending anything over a
  network.
- **No account, no sign-up, no identifier.**
- **No ads, no trackers, no analytics** (no Firebase Analytics, no Crashlytics, no third-party
  collection SDK).
- **No cloud backup**: `allowBackup=false` — your data is excluded from Android's automatic backups
  and from device-to-device transfers.

## What data, and where

Everything you enter (events, titles, places, notes, reminders, calendars) is stored **only on your
device**, in an **encrypted** database (SQLCipher, AES-256; the key is protected by the Android
KeyStore — hardware/TEE on supported devices).

The developer has **no access** to this data and receives **no copy** of it.

## Permissions requested, and why

| Permission | Purpose | Network? |
|---|---|---|
| `READ_CALENDAR` | Import, at your request, the events **already present** on the device (Google/Exchange/local calendar synced by the system). **Read-only.** | No |
| `POST_NOTIFICATIONS` | Show event reminders. | No |
| `USE_EXACT_ALARM` / `SCHEDULE_EXACT_ALARM` | Fire reminders at the exact time. | No |
| `RECEIVE_BOOT_COMPLETED` | Re-arm reminders after a reboot. | No |
| `VIBRATE` | Vibration for reminders. | No |

The app **never** requests access to your location, contacts, microphone, camera, or the internet.

The device calendar import (`READ_CALENDAR`) only reads what the system apps have **already synced
locally**; Agenda Tech does not connect to your Google account or to any remote service. The
permission is requested **at runtime**, only when you open the import screen, and can be denied.

## Sharing with third parties

**None.** No data is shared, sold or transmitted to anyone — the app has no technical means of doing
so (no internet permission).

The only exchanges possible are the ones **you** trigger explicitly, and they stay on your device,
from one app to another:

- **`.ics` export**: to the location of your choice, through the system file picker.
- **Encrypted `.atbak` backup**: this is the broadest exchange, so it deserves a precise
  description. The file holds **your entire calendar** (calendars, events, descriptions, places,
  addresses, GPS coordinates, reminders), and you choose where it is written — including a folder
  synced to a cloud, if that is your choice. **The app never sends it anywhere itself**: it writes
  to the location you point at in the system file picker, and has no way to reach a network anyway.
  The contents are encrypted (AES-256) with a key derived from **your password alone**: neither we
  nor whichever service might host that file can read it. That password is stored nowhere — if you
  forget it, the file is permanently unreadable, **including to us**. What becomes of the file once
  it leaves the app is entirely up to you.
- **Opening a place on a map**: if you enter GPS coordinates on an event and tap the marker, the app
  hands **those coordinates and the event's label** to your phone's maps app. Nothing else is sent,
  and nothing leaves if you don't tap the marker. What that maps app then does with the information
  is governed by its own privacy policy.
- **Custom reminder sound**: if you pick an audio file, the app keeps permission to read it so it
  can play it when the reminder fires.

## Your rights (GDPR)

Since the app processes no personal data outside your device, there is no remote processing to
access, rectify or erase. You keep full control: deleting an event, a calendar, or uninstalling the
app removes the corresponding data from the device. Uninstalling deletes the encrypted database.

## Children

The app collects no data and is suitable for all audiences.

## Changes

This policy may evolve alongside the app; the date at the top indicates the latest revision, and the
history is public in this repository.

## Contact

Question or report: open an [issue](https://github.com/gitubpatrice/AGENDA-TECH/issues) on the
repository, or reach us via [files-tech.com](https://files-tech.com). For security, see
[SECURITY.md](SECURITY.md).
