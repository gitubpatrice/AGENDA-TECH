# Translating Agenda Tech

Agenda Tech ships in **English** (the source), **French**, **German**, **Italian** and **Spanish**.
This page is for anyone adding or correcting a language — no Android knowledge required beyond
editing an XML file.

> **Honesty first.** The German, Italian and Spanish translations were produced by the maintainer
> with machine assistance and **have not been reviewed by a native speaker**. They are offered
> because a rough translation in your language beats no translation at all — not because they are
> professional work. If something reads wrong, **please open an issue or a pull request**. You do
> not need to justify a correction: a native ear is the authority here, not the person who wrote it.

## Where the strings live

| Path | Role |
|---|---|
| `app/src/main/res/values/strings.xml` | **English — the source of truth.** Every key is defined here first. |
| `app/src/main/res/values-fr/strings.xml` | French |
| `app/src/main/res/values-<lang>/strings.xml` | your language |
| `fastlane/metadata/android/<locale>/` | the F-Droid store listing |

There are **310 keys**: 307 `<string>` and 3 `<plurals>`. The app has no hardcoded text:
everything you see on screen, in a notification or on the home-screen widget comes from these
files.

## Adding a language: the four gestures

They go together — doing three of the four ships a language that does not work. The CI check
described below fails the build when one is missing.

1. **Translate** `app/src/main/res/values-<lang>/strings.xml`, starting from the English file.
2. **Declare the locale** in `app/build.gradle.kts` → `androidResources { localeFilters }`.
   Without this line the Android Gradle Plugin **strips your resources out of the APK**, with no
   error, and the app silently stays in English.
3. **Add the locale** to `app/src/main/res/xml/locales_config.xml`. This is what puts your
   language in *Settings → Apps → Agenda Tech → Language* on Android 13 and later.
4. **Keep `android:localeConfig="@xml/locales_config"`** on `<application>` in
   `AndroidManifest.xml`. It is already there; it is listed because without it step 3 does
   nothing, and nothing else would say so.

There is no per-language debug file to create: the debug build is named by a manifest
placeholder (`appLabel` in `app/build.gradle.kts`), which no language can override.

## Rules that are not style preferences

**Format placeholders — about forty strings carry them.** `%1$s`, `%2$d` and friends must all
survive, with their numbers intact. You may **reorder** them to fit your grammar — that is what the
`1$` and `2$` are for — but dropping one, or inventing one, **crashes the app at runtime**. It is
not a cosmetic defect.

```xml
<!-- English -->  <string name="x">Moved to %1$s at %2$s</string>
<!-- valid    -->  <string name="x">Am %2$s nach %1$s verschoben</string>
<!-- CRASH    -->  <string name="x">Nach %1$s verschoben</string>
```

**Apostrophes must be escaped** as `\'` in `strings.xml`, always. An unescaped `'` is an Android
build error. Same for a leading or trailing space and for `"`, `\` and `@`. This rule is for
`strings.xml` only: the store listing files are plain text, where `\'` would show the backslash.

**Plurals are not a free choice.** Each language has a fixed set of CLDR quantity categories.
English and German use `one` / `other`; French, Italian and Spanish also use `many` (exact
millions: *1 000 000 d'événements*). The CI check names any category that is missing or useless.

**Do not translate these**, they are names or protocol words: `Agenda Tech`, `PIN`, `GPS`, `.ics`,
`.atbak`, `SQLCipher`, `AES-256`, `Android Keystore`, `Apache License 2.0`, `INTERNET` (the name of
the permission).

## Glossary — terms that must not drift

Pick **one** word per concept in your language and use it everywhere: a synonym used halfway
through makes users think there are two features.

| English | French | German | Italian | Spanish |
|---|---|---|---|---|
| event | événement | Termin | evento | evento |
| calendar (one of several) | calendrier | Kalender | calendario | calendario |
| agenda (all of your data) | agenda | Agenda | agenda | agenda |
| reminder | rappel | Erinnerung | promemoria | recordatorio |
| recurring (event) | récurrent | Serientermin / wiederkehrend | ricorrente | periódico |
| occurrence | occurrence | Termin | occorrenza | repetición |
| backup | sauvegarde | Sicherung | backup | copia de seguridad |
| app lock | verrouillage | App-Sperre | blocco dell'app | bloqueo de la app |
| biometrics | biométrie | Biometrie | biometria | biometría |

**Register: formal.** French uses *vous*, German *Sie*, Italian *Lei*, Spanish *usted*, as in the
other Files Tech apps.

**Strings that do not forgive an approximation.** Translate these slowly, and prefer a plain,
unambiguous wording over an elegant one: the **backup** and **restore** screens (a restore deletes
the current agenda), the **password** warnings (a lost backup password is unrecoverable), the
**app lock**, and `startup_failure_body` (read by someone who thinks their agenda is gone).

## Screen space

German runs about 30 % longer than English, and tabs, buttons and chips have no margin for it.
Where a short label will not fit, a shorter accurate synonym is better than a truncated correct
one. Please check your language on a device or emulator before opening the pull request; the
screens most at risk are the view switcher (Month / Week / Day / Agenda), the event editor and the
settings.

## Store metadata

`fastlane/metadata/android/<locale>/` holds the F-Droid listing (`de-DE`, `it-IT`, `es-ES`, …).
The caps below are counted in **bytes**, not characters — an accented letter costs two. The CI
check measures them on every build.

| File | Cap |
|---|---|
| `title.txt` | 50 |
| `short_description.txt` | **80** |
| `full_description.txt` | 4000 |

A language that has a `full_description.txt` must also have the changelog of the current
`versionCode` (`changelogs/<versionCode>.txt`), or its listing shows a "What's new" in another
language. Changelogs have no cap: the 500 characters sometimes quoted are a Google Play rule, and
F-Droid displays the whole text.

Please end `full_description.txt` with the short paragraph the German, Italian and Spanish
listings carry: who wrote the translation, that no native speaker reviewed it, and where to report
what reads wrong.

## Continuous integration

Two scripts run on every build, before compilation:

```sh
python3 tools/check-translations-negative-control.py   # proves the check can go red
python3 tools/check-translations.py                     # the check itself
```

The check **fails** — it does not warn — when a language drifts from the English source: a missing
or extra key, a format placeholder that changed, a missing plural category, one of the four
gestures above left undone, a store file missing or over its cap, or a link to a repository
document that does not exist. The negative control runs first and reproduces each of those defects
in a throwaway tree: a check that could only ever say "green" would be worse than none.

## Thank you

Translating 310 strings is real work, and it is the difference between an app someone can use and
one they close. It is appreciated.
