# Privacy Policy — Agenda Tech

_Last updated: 26 August 2026 — version 1.0.3_ · 🇫🇷 [Version française](PRIVACY.fr.md)

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

This list is **exhaustive**: it is the eleven permissions the published APK carries, as read from its
**merged** manifest. That includes the ones no line of our own code asks for, which a library brought
along with it — leaving those out would have been more flattering and less true. An automated check
fails any build whose merged manifest departs from this list
(`tools/check-manifest-permissions.py`, run on every continuous-integration build).

### Declared by the app

| Permission | Purpose | Network? |
|---|---|---|
| `READ_CALENDAR` | Import, at your request, the events **already present** on the device (Google/Exchange/local calendar synced by the system). **Read-only.** | No |
| `POST_NOTIFICATIONS` | Show event reminders. | No |
| `USE_EXACT_ALARM` / `SCHEDULE_EXACT_ALARM` | Fire reminders at the exact time. | No |
| `RECEIVE_BOOT_COMPLETED` | Re-arm reminders after a reboot. | No |
| `VIBRATE` | Vibration for reminders. | No |

### Brought in by the libraries used

| Permission | Comes from | What it actually does **here** | Network? |
|---|---|---|---|
| `USE_BIOMETRIC` | `androidx.biometric` | Unlock the app with a fingerprint or face, if you enable the app lock. | No |
| `USE_FINGERPRINT` | `androidx.biometric` | The same, on Android 9 and earlier, where the modern biometric API does not exist. | No |
| `WAKE_LOCK` | `androidx.work`, pulled in by Glance (the widgets) | Held for a moment while a home-screen widget is redrawn. Glance runs that redraw as a WorkManager job, and WorkManager takes a partial wake lock to run any job. The app's own code schedules nothing. | No |
| `FOREGROUND_SERVICE` | `androidx.work`, pulled in by Glance (the widgets) | **Nothing.** Declared by `androidx.work`, which would need it only to run an expedited job — none is ever scheduled. | No |
| `com.filestech.agenda_tech.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | `androidx.core` | A **self-granted**, signature-level permission: only an app signed with our key can hold it. It stops other apps from reaching our internal receivers. | No |

Corrected on 26 August 2026. This paragraph used to say that both permissions covered nothing the app
does. That was true of the app's own code and **false of the app you install**: Glance redraws a widget
by scheduling a WorkManager job, so `WAKE_LOCK` is genuinely taken, briefly, every time a widget
refreshes. Removing it would make widget updates fail. `FOREGROUND_SERVICE` really is unused, but it
comes from the same library and is kept with it rather than removed on its own.

Neither grants network access, and neither can be used to read anything. The wake lock only keeps the
processor from sleeping for the fraction of a second a widget takes to redraw.

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
  nor whichever service might host that file can read it. For a backup you export by hand that
  password is stored nowhere — if you forget it, the file is permanently unreadable, **including to
  us**. What becomes of the file once it leaves the app is entirely up to you.
- **Automatic backup** (optional, off by default): once you turn it on, the app writes the same
  encrypted `.atbak` once a week into a folder you pick, keeping the four most recent. It still
  sends nothing anywhere — it writes to that folder and has no network access. Because it runs
  without you, it is the one place where **your password is kept on the phone**: encrypted by a key
  held in the device's secure hardware, which never leaves it. Turning the option off erases the
  password and that key. The trade is deliberate — a backup only you can open, kept usable on the
  day the phone is gone. `SECURITY.md` states it in full.
- **Opening a place on a map**: if you enter GPS coordinates on an event and tap the marker, the app
  hands **those coordinates and the event's label** to your phone's maps app. Nothing else is sent,
  and nothing leaves if you don't tap the marker. What that maps app then does with the information
  is governed by its own privacy policy.
- **Reminder sound**: you choose a ringtone from those already registered on the device, through the
  system ringtone picker. The app stores the identifier of the chosen ringtone and takes **no
  persistent access permission** on your files — it declares neither `READ_MEDIA_AUDIO` nor
  `READ_EXTERNAL_STORAGE`, so it could not hold one. If the chosen sound ever becomes unreadable, the
  system default sound is used rather than silence.

  *(This paragraph previously said the app "keeps permission to read" a picked audio file. That was
  wrong in both halves — the picker is the system ringtone picker, not a file picker, and
  `takePersistableUriPermission` appears nowhere in the app; the only related code releases a grant an
  older build may have taken. A privacy policy that claims to be exhaustive has to be corrected out
  loud when it is not.)*

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
