#!/usr/bin/env python3
"""Translation parity check. It FAILS, it does not warn.

Why this exists
---------------
A language shipped once and then forgotten does not become incomplete in the open: it does so
SILENTLY. A key added in English and nowhere else makes the screen fall back to English word by
word, and nobody sees it before the user does. A language that drifts must break the build.

Ported from SMS Tech (proven there with its own negative control), adapted here: no SMS bodies to
check, and no per-language debug twin, because this app names its debug build through a manifest
placeholder (`appLabel`, app/build.gradle.kts) that no language can override.

What it checks, and why each one is needed
------------------------------------------
1. KEYS        - a missing key is an English label in the middle of a German screen; an EXTRA key
                 is translation spent on a string nothing displays any more.
2. FORMAT ARGS - %1$s, %2$d... lost or invented CRASH the app at runtime (IllegalFormatException).
                 The ORDER may change - that is what 1$ / 2$ are for - the set may not.
3. PLURALS     - every quantity the LANGUAGE requires (CLDR), not an English floor: English only
                 has `one`/`other`, and French, Italian and Spanish also need `many`.
4. STORE LISTING - fastlane caps, counted in CHARACTERS as fdroidserver cuts them, every file
                 present, and the changelog of the current versionCode: present, and within the
                 500 characters past which F-Droid cuts its "What's new" without a word.
5. THE GESTURES THAT SHIP A LANGUAGE - all of them, or the language does not work:
                 a. values-XX/strings.xml       the translation
                 b. localeFilters               otherwise AGP STRIPS the resources from the APK,
                                                with no error: the app stays in English
                 c. res/xml/locales_config.xml  otherwise no per-app language picker (Android 13+)
                 d. android:localeConfig        the manifest must point at (c), or (c) is dead
                 And the CONVERSE: a language announced in (b) or (c) with no translation behind
                 it offers the user a language the app does not speak.
6. LINKS TO REPOSITORY DOCUMENTS - a string pointing at .../blob/main/PRIVACY.de.md must name a
                 file that exists, or a user of that language gets a 404 instead of the policy.

Usage:  python3 tools/check-translations.py
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
GRADLE = os.path.join(ROOT, "app", "build.gradle.kts")
MANIFEST = os.path.join(ROOT, "app", "src", "main", "AndroidManifest.xml")
LOCALES_CONFIG = os.path.join(RES, "xml", "locales_config.xml")
ANDROID_NAME = "{http://schemas.android.com/apk/res/android}name"

# values-de, values-pt-rBR, values-b+sr+Latn - and NOT values-night, values-v31, values-land...,
# which are configuration qualifiers, not languages.
LANGUAGE_DIR = re.compile(r"^values-(?:(b\+[A-Za-z+]+)|([a-z]{2,3})(?:-r([A-Z]{2}))?)$")
# Real Java conversions only: "100% private" is not a %p argument, and the SPACE flag is left out
# on purpose - "100 % des" is not one either.
FORMAT_ARG = re.compile(r"%(\d+\$)?[-#+0,(]*\d*(?:\.\d+)?([bBhHsScCdoxXeEfgGaAtTn])")
# A link to a document of THIS repository, as the privacy-policy strings carry.
REPO_DOC = re.compile(r"https://github\.com/gitubpatrice/AGENDA-TECH/blob/main/([A-Za-z0-9._/-]+)")

# The CLDR plural categories each shipped language requires. A missing one renders the wrong text
# for those quantities; an extra one is dead text, never rendered.
CLDR_QUANTITIES = {
    "en": {"one", "other"},
    "de": {"one", "other"},
    "fr": {"one", "many", "other"},
    "it": {"one", "many", "other"},
    "es": {"one", "many", "other"},
}

FASTLANE_CAPS = {
    "title.txt": 50,
    "short_description.txt": 80,
    "full_description.txt": 4000,
}

# fdroidserver, `common.py`: char_limits['whatsNew'] = 500.
CHANGELOG_CAP = 500

errors = []


def fail(where, message):
    errors.append((where, message))


def raw_text(element):
    """The content of a string, style tags included - that is where format args live."""
    return "".join(element.itertext())


def read(path):
    """Return ({key: text}, {key: {quantity: text}})."""
    root = ET.parse(path).getroot()
    label = os.path.basename(os.path.dirname(path))
    strings, plurals = {}, {}
    for e in root.findall("string"):
        name = e.get("name")
        if name in strings:
            fail(label, "DUPLICATE key: %s" % name)
        strings[name] = raw_text(e)
    for e in root.findall("plurals"):
        plurals[e.get("name")] = {i.get("quantity"): raw_text(i) for i in e.findall("item")}
    return strings, plurals


def format_args(text):
    """The set of format arguments, whatever their order. %% is a literal percent sign."""
    return frozenset((p or "", t) for p, t in FORMAT_ARG.findall(text.replace("%%", "\x00")))


def pretty(args):
    return "{" + ", ".join(sorted("%%%s%s" % (p, t) for p, t in args)) + "}"


def tag(directory):
    """values-pt-rBR -> pt-rBR, the form localeFilters and locales_config expect."""
    return directory[len("values-"):]


def gradle_languages():
    with open(GRADLE, encoding="utf-8") as f:
        content = f.read()
    m = re.search(r"localeFilters\s*\+?=\s*listOf\(([^)]*)\)", content)
    if not m:
        fail("build.gradle.kts", "localeFilters block not found - this check would be blind")
        return set()
    return set(re.findall(r'"([^"]+)"', m.group(1)))


def locales_config_languages():
    if not os.path.exists(LOCALES_CONFIG):
        fail("locales_config.xml", "file missing - there is no Android 13+ language picker")
        return set()
    return {e.get(ANDROID_NAME) for e in ET.parse(LOCALES_CONFIG).getroot().findall("locale")}


def check_manifest_points_at_locales_config():
    """(d) The file alone does nothing: the <application> element must reference it."""
    with open(MANIFEST, encoding="utf-8") as f:
        content = f.read()
    if 'android:localeConfig="@xml/locales_config"' not in content:
        fail("AndroidManifest.xml",
             'android:localeConfig="@xml/locales_config" missing from <application>: '
             "locales_config.xml exists but no language picker is offered")


def check_repository_links(directory, strings):
    """6. Every link to a document of this repository must name a file that exists."""
    label = "en" if directory == "values" else tag(directory)
    for key, text in sorted(strings.items()):
        for relative in REPO_DOC.findall(text):
            if not os.path.isfile(os.path.join(ROOT, *relative.split("/"))):
                fail(label, "%s links to %s, which does not exist in the repository (404)"
                            % (key, relative))


def check_line_endings(directories):
    """No strings.xml may carry \\r\\r\\n, nor mix CRLF and LF.

    Not a demand for LF: on Windows `core.autocrlf` gives a CRLF working copy while the repository
    stores LF, so requiring either one would fail the other. What is refused is wrong in BOTH:
    the doubled carriage return (seen on SMS Tech, commit 2e8991b), and two conventions in a file.
    """
    for directory in ["values"] + list(directories):
        path = os.path.join(RES, directory, "strings.xml")
        if not os.path.exists(path):
            continue
        label = "en" if directory == "values" else tag(directory)
        data = open(path, "rb").read()
        if b"\r\r\n" in data:
            fail(label, "strings.xml carries \\r\\r\\n line endings (%d): a tool added one "
                        "carriage return too many" % data.count(b"\r\r\n"))
            continue
        crlf, lf = data.count(b"\r\n"), data.count(b"\n")
        if crlf and crlf != lf:
            fail(label, "strings.xml mixes CRLF (%d) and LF (%d): one convention per file"
                        % (crlf, lf - crlf))


def current_version_code():
    """versionCode from version.properties, or None if it cannot be read."""
    path = os.path.join(ROOT, "version.properties")
    if not os.path.isfile(path):
        return None
    for line in open(path, encoding="utf-8"):
        key, _, value = line.partition("=")
        if key.strip() == "versionCode":
            try:
                return int(value.strip())
            except ValueError:
                return None
    return None


def characters(path):
    """The length fdroidserver measures: CHARACTERS, once decoded from UTF-8.

    Trailing white space does not count: past the cap, `text[:limit]` would only lose a line break.
    None when the file is not valid UTF-8 - fdroidserver would put replacement characters in it
    (`errors='replace'`), and the published listing would be unreadable.
    """
    try:
        with open(path, encoding="utf-8") as f:
            return len(f.read().rstrip())
    except UnicodeDecodeError:
        return None


def check_length(locale, name, path, cap, consequence):
    length = characters(path)
    if length is None:
        fail(locale, "fastlane/%s is not valid UTF-8" % name)
    elif length > cap:
        fail(locale, "fastlane/%s: %d characters for a cap of %d (over by %d) - %s"
                     % (name, length, cap, length - cap, consequence))


def check_fastlane():
    """Store-listing caps and the current changelog's, in CHARACTERS; files present.

    What fdroidserver does with these files (read in its source, 2.4.2, by SMS Tech on 2026-10-04):
    `common.py` sets char_limits - title 50, summary 80, description 4000, whatsNew 500 - and
    `update.py` CUTS each text to `text[:limit]`. No error, no warning: the published text stops in
    the middle of a word. Python characters, never bytes; `lint.py` measures the same way, by len().

    This check said otherwise until 1.3.2, as SMS Tech's did until 2026-10-04 - it counted BYTES, and
    called the changelogs uncapped ("a Google Play rule, F-Droid displays the whole text"). The
    fdroidserver source settles it; it is the authority here, not a review nor a history.

    Only the changelog of the CURRENT versionCode is measured: F-Droid reads it in the source at the
    tag it builds, and the older ones are frozen in their tags.
    """
    root = os.path.join(ROOT, "fastlane", "metadata", "android")
    if not os.path.isdir(root):
        return
    version = current_version_code()
    for locale in sorted(os.listdir(root)):
        directory = os.path.join(root, locale)
        if not os.path.isdir(directory):
            continue
        for name, cap in sorted(FASTLANE_CAPS.items()):
            path = os.path.join(directory, name)
            # A MISSING file must fail: skipping it would be the easiest hole not to see, since
            # it prints nothing.
            if not os.path.isfile(path):
                fail(locale, "fastlane/%s MISSING" % name)
                continue
            check_length(locale, name, path, cap, "F-Droid cuts this text")
        if version is None:
            continue
        changelog_name = "changelogs/%d.txt" % version
        changelog = os.path.join(directory, "changelogs", "%d.txt" % version)
        if os.path.isfile(changelog):
            check_length(locale, changelog_name, changelog, CHANGELOG_CAP, "F-Droid cuts this changelog")
        # A language with a store description but without the current changelog shows a
        # "What's new" in another language: started, not complete.
        elif os.path.isfile(os.path.join(directory, "full_description.txt")):
            fail(locale, "fastlane/%s MISSING (current versionCode)" % changelog_name)


def check_language(directory, ref_strings, ref_plurals, gradle, config):
    lang = tag(directory)
    before = len(errors)
    strings, plurals = read(os.path.join(RES, directory, "strings.xml"))

    all_ref = set(ref_strings) | set(ref_plurals)
    all_here = set(strings) | set(plurals)

    # 1. KEYS
    for k in sorted(all_ref - all_here):
        fail(lang, "MISSING key: %s" % k)
    for k in sorted(all_here - all_ref):
        fail(lang, "EXTRA key (absent from English): %s" % k)

    # 2. FORMAT ARGUMENTS - the one defect that crashes the app
    for k, expected in ref_strings.items():
        if k in strings:
            a, b = format_args(expected), format_args(strings[k])
            if a != b:
                fail(lang, "format arguments differ on %s: English=%s translation=%s"
                           % (k, pretty(a), pretty(b)))
    for k, items in ref_plurals.items():
        if k not in plurals:
            continue
        expected = format_args(" ".join(items.values()))
        for q, t in sorted(plurals[k].items()):
            invented = format_args(t) - expected
            if invented:
                fail(lang, "INVENTED argument in plural %s[%s]: %s" % (k, q, pretty(invented)))

    # 3. PLURALS - the categories of THE LANGUAGE
    for k, items in ref_plurals.items():
        if k in plurals:
            for q in sorted(set(items) - set(plurals[k])):
                fail(lang, "MISSING quantity in plural %s: %s" % (k, q))
    required = CLDR_QUANTITIES.get(lang)
    if required is not None:
        for k in sorted(plurals):
            missing = required - set(plurals[k])
            if missing:
                fail(lang, "plural %s: CLDR category MISSING for this language: %s"
                           % (k, ", ".join(sorted(missing))))
            useless = set(plurals[k]) - required
            if useless:
                fail(lang, "plural %s: CLDR category USELESS in %s (never rendered): %s"
                           % (k, lang, ", ".join(sorted(useless))))

    # 5. THE GESTURES
    if lang not in gradle:
        fail(lang, "absent from localeFilters (app/build.gradle.kts): AGP would STRIP these "
                   "resources from the APK and the app would stay in English")
    if lang not in config:
        fail(lang, "absent from res/xml/locales_config.xml: no per-app language picker on "
                   "Android 13+")

    # 6. LINKS
    check_repository_links(directory, strings)

    state = "complete" if len(errors) == before else "%d issue(s)" % (len(errors) - before)
    print("  %-8s %4d keys - %s" % (lang, len(all_here), state))


def main():
    source = os.path.join(RES, "values", "strings.xml")
    if not os.path.exists(source):
        print("ERROR: English source not found (%s)" % source, file=sys.stderr)
        return 2

    ref_strings, ref_plurals = read(source)
    total = len(ref_strings) + len(ref_plurals)
    print("English source: %d keys (%d strings + %d plurals)"
          % (total, len(ref_strings), len(ref_plurals)))

    directories = sorted(d for d in os.listdir(RES)
                         if LANGUAGE_DIR.match(d)
                         and os.path.exists(os.path.join(RES, d, "strings.xml")))
    gradle = gradle_languages()
    config = locales_config_languages()
    check_manifest_points_at_locales_config()
    check_repository_links("values", ref_strings)

    for directory in directories:
        check_language(directory, ref_strings, ref_plurals, gradle, config)

    # THE CONVERSE: a language announced with no translation behind it.
    translated = {tag(d) for d in directories} | {"en"}
    for lang in sorted(gradle - translated):
        fail(lang, "announced in localeFilters but no values-%s/strings.xml: the app would "
                   "offer an empty language" % lang)
    for lang in sorted(config - translated):
        fail(lang, "announced in locales_config.xml but no values-%s/strings.xml: the picker "
                   "would offer an empty language" % lang)

    check_line_endings(directories)
    check_fastlane()

    print()
    if errors:
        print("TRANSLATIONS: %d ISSUE(S)" % len(errors))
        current = None
        for where, message in errors:
            if where != current:
                print("\n  [%s]" % where)
                current = where
            print("    - %s" % message)
        print("\nThe build stops here. See TRANSLATING.md.")
        return 1

    print("TRANSLATIONS: %d language(s) aligned on the %d English keys."
          % (len(directories), total))
    return 0


if __name__ == "__main__":
    sys.exit(main())
