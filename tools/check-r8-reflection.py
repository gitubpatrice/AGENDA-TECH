#!/usr/bin/env python3
"""What the libraries instantiate BY REFLECTION must survive R8. It FAILS, it does not warn.

Why this exists
---------------
The CI built the release "to exercise R8", and nothing looked at what R8 had done. WorkManager 2.7.1
creates the InputMerger of every one-time work with Class.newInstance(); its own rule keeps the class
but not its constructor, and in R8's full mode (AGP 8+) the constructor went. Every one-time work then
failed before it started — Glance's SessionWorker among them, so the home-screen widget never drew
and stayed a blue box (issue #37). Debug builds have no R8, and the instrumented tests run in debug:
nothing could see it.

R8 writes `seeds.txt`, the classes and members a keep rule held. Each constructor below must be there.
The list is what WorkManager and Glance build by reflection for this app — its two workers and the
input mergers WorkManager falls back on.

A self-test runs first, on doctored seeds: the check must go red when a constructor is missing, and
must not take a method that shares a name for the constructor.

Usage (after :app:assembleRelease):  python3 tools/check-r8-reflection.py
"""
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SEEDS = os.path.join(ROOT, "app", "build", "outputs", "mapping", "release", "seeds.txt")

REQUIRED = [
    "androidx.work.OverwritingInputMerger: OverwritingInputMerger()",
    "androidx.work.ArrayCreatingInputMerger: ArrayCreatingInputMerger()",
    "androidx.glance.session.SessionWorker: SessionWorker(android.content.Context,androidx.work.WorkerParameters)",
    "com.filestech.agenda_tech.system.backup.AutoBackupWorker: "
    "AutoBackupWorker(android.content.Context,androidx.work.WorkerParameters)",
]


def missing(seed_lines):
    kept = {line.strip() for line in seed_lines}
    return [entry for entry in REQUIRED if entry not in kept]


def self_test():
    healthy = REQUIRED + ["androidx.work.Data", "androidx.work.OverwritingInputMerger: androidx.work.Data merge(java.util.List)"]
    assert missing(healthy) == [], "self-test: a healthy seeds.txt must pass"
    without_merger = [line for line in healthy if not line.endswith("OverwritingInputMerger()")]
    assert missing(without_merger) == [REQUIRED[0]], "self-test: a removed constructor must be reported"
    # The class kept and one of its methods kept is NOT its constructor kept: that was the defect.
    only_class = [line for line in healthy if "InputMerger()" not in line]
    only_class += ["androidx.work.OverwritingInputMerger", "androidx.work.ArrayCreatingInputMerger"]
    assert missing(only_class) == REQUIRED[:2], "self-test: a kept class is not a kept constructor"


def main():
    self_test()
    if not os.path.isfile(SEEDS):
        print("ERROR: %s not found - build the release first (:app:assembleRelease)." % SEEDS, file=sys.stderr)
        return 2
    with open(SEEDS, encoding="utf-8") as f:
        absent = missing(f)
    if absent:
        print("R8 REFLECTION: %d constructor(s) built by reflection were not kept:" % len(absent))
        for entry in absent:
            print("  - " + entry)
        print("Add a keep rule for each to app/proguard-rules.pro, with the reason.")
        return 1
    print("R8 REFLECTION: the %d constructors built by reflection are kept." % len(REQUIRED))
    return 0


if __name__ == "__main__":
    sys.exit(main())
