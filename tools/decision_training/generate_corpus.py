"""Corpus for Aive's orchestration decision model (OrchestrationQuestion in OrchestrationDecisions.kt).

Usage: python3 tools/decision_training/generate_corpus.py [out_dir] [--rows-per-class N]

Each row is one typed question about one text: {"question", "text", "label", "split", "tags"}.
Labels come from construction: every template belongs to one answer. Two things keep the test split
honest about generalisation rather than recall:
- templates are split by index: test rows use templates no train row used;
- slot vocabulary (subjects, technologies, platforms) is split too: test rows name things train never saw.
The adversarial split is hand-written near misses (a concrete "improve", an architecture-doc typo, a
date picker that is not history, compatible criteria that look opposed, lexicon traps).

The texts are what the runtime sends: the task objective as written, criteria joined with " ; ",
and one part of a criterion as split by CRITERION_PARTS.
"""
import json
import random
import re
import sys
from pathlib import Path

SEED = 8
SEPARATOR = " ; "  # CRITERIA_SEPARATOR in OrchestrationDecisions.kt
QUESTIONS = {
    "ambiguous-objective": ["no", "yes"],
    "architectural-decision": ["no", "yes"],
    "multi-step-reasoning": ["no", "yes"],
    "chronological-context": ["no", "yes"],
    "contradictory-criteria": ["no", "yes"],
    "verification-operation": ["evidence-check", "lint", "test", "build", "health-check", "source-verification"],
}

# Slot pools: [train-and-validation vocabulary, test vocabulary].
SUBJECTS = [
    ["settings screen", "login flow", "sync engine", "payment module", "notification service", "onboarding wizard",
     "search bar", "profile page", "image cache", "export dialog", "chat view", "billing API", "offline queue",
     "crash reporter", "home widget", "build script", "analytics events", "theme system", "database layer",
     "network client", "permission prompt", "file picker", "audio recorder", "share sheet", "map view"],
    ["checkout page", "media player", "backup service", "calendar view", "upload manager", "comment thread",
     "deep link handler", "feature flags", "password reset", "tab bar", "invoice generator", "location tracker"],
]
PLATFORMS = [["Android", "iOS", "desktop", "web"], ["Wear OS", "tvOS", "the browser extension", "Linux"]]
TECH = [
    [("Room", "SQLDelight"), ("REST", "GraphQL"), ("Redux", "MVI"), ("OkHttp", "Ktor"),
     ("SharedPreferences", "DataStore"), ("polling", "websockets"), ("Firebase", "Supabase"),
     ("Gson", "kotlinx.serialization"), ("RxJava", "coroutines"), ("XML layouts", "Compose")],
    [("Realm", "SQLite"), ("gRPC", "REST"), ("MVP", "MVVM"), ("Dagger", "Koin"), ("Retrofit", "Ktor"),
     ("LiveData", "StateFlow")],
]
TIMES = [["last week", "since Monday", "over the last release", "in March", "since the 2.0 release",
          "during the past sprint", "yesterday"],
         ["since the beta", "over the summer", "in the last three releases", "since we moved to Kotlin"]]
PREFIXES = ["", "", "", "Please ", "We need to ", "TODO: ", "Task: ", "Could you "]

TEMPLATES = {
    "ambiguous-objective": {
        "yes": [
            "Make the {s} better", "Fix the thing", "Improve stuff", "Clean things up", "Do what we discussed",
            "Sort out the {s}", "Make it work", "Look into the issues", "Tidy up whatever needs it",
            "Handle the usual", "Make the app nicer", "Deal with the {s} problem", "Polish things",
            "Fix whatever is broken", "Update it", "Get the {s} into shape", "Do the needful",
            "Make it faster somehow", "Clean up the mess", "Improve the {s} in general",
        ],
        "no": [
            "Add a dark mode toggle to the {s}", "Fix the crash when the {s} opens on {p}",
            "Rename the {s} title to 'Preferences'", "Reduce the {s} load time to under 2 seconds on {p}",
            "Add a retry button to the {s} error state", "Show a spinner while the {s} loads",
            "Validate the email field in the {s}", "Cache the {s} responses for 10 minutes",
            "Log an analytics event when the {s} is opened", "Make the {s} support right-to-left text",
            "Remove the deprecated {s} endpoint", "Bump the {s} timeout from 10 to 30 seconds",
            "Add unit tests for the {s} date parser", "Translate the {s} strings into Spanish",
            "Disable the {s} on {p} until the fix ships", "Improve the {s} startup time to under 300 ms on {p}",
            "Fix the null pointer in the {s} when the list is empty", "Add an empty state to the {s}",
            "Clean up unused imports in the {s}", "Update the copyright year in the {s} footer",
        ],
    },
    "architectural-decision": {
        "yes": [
            "Decide whether to use {a} or {b} for the {s}", "Migrate the {s} from {a} to {b}",
            "Split the {s} into its own module", "Design the data model for the {s}",
            "Choose a state management approach for the {s}", "Replace {a} with {b} across the app",
            "Restructure the {s} into layers", "Move the {s} business logic into a shared domain module",
            "Define the sync protocol between the {s} and the server", "Pick between {a} and {b} for persistence",
            "Introduce dependency injection for the {s}", "Redesign how the {s} talks to the backend",
            "Decouple the {s} from the UI layer", "Evaluate {a} versus {b} and pick one for the {s}",
            "Plan the module boundaries for the {s} and the {s2}", "Rearchitect the {s} for offline-first use",
        ],
        "no": [
            "Fix a typo in the {s} architecture doc", "Rename a variable in the {s}",
            "Add a log line to the {s} repository class", "Change the {s} button colour",
            "Fix the crash in the {s} on {p}", "Bump the {a} version", "Add a test for the {s} parser",
            "Update the {s} copy", "Increase the {s} timeout", "Remove a dead branch in the {s}",
            "Fix the padding in the {s}", "Add an icon to the {s}", "Correct the {s} date format",
            "Add a null check to the {s}", "Update the {s} screenshot in the README",
            "Fix the {a} deprecation warning in the {s}",
        ],
    },
    "multi-step-reasoning": {
        "yes": [
            "Reproduce the {s} crash, fix it and add a regression test",
            "Add an endpoint for the {s}, wire it into the client and update the docs",
            "Investigate why the {s} is slow on {p}, then optimise it and measure the result",
            "Migrate every screen from {a} to {b} and remove the old code",
            "Design, implement and test the {s} export", "First audit the {s} permissions, then tighten them",
            "Find all callers of the {s} API, update them, then delete the old API",
            "Add the {s} to {p}, then roll it out behind a flag and monitor crashes",
            "Write a migration for the {s} schema, backfill the data and verify it",
            "Profile the {s}, identify the hot path, fix it and add a benchmark",
            "Trace the {s} bug across the client and server and fix both sides",
            "Port the {s} to {p} and make sure the tests pass on both platforms",
        ],
        "no": [
            "Fix the typo in the {s} title", "Change the {s} button colour to blue",
            "Bump the {s} timeout to 30 seconds", "Add an icon to the {s}", "Remove the unused {s} import",
            "Rename the {s} label", "Update the {s} link in the README", "Hide the {s} on {p}",
            "Add a log line when the {s} opens", "Set the {s} default to off", "Correct the {s} help text",
            "Increase the {s} font size",
        ],
    },
    "chronological-context": {
        "yes": [
            "What changed in the {s} {t}?", "Why was the {s} cache removed {t}?",
            "When did the {s} start failing on {p}?", "Summarise the history of the {s}",
            "Which commits touched the {s} {t}?", "Explain how the {s} evolved {t}",
            "Find when the {s} regression was introduced", "List the {s} decisions we made {t}",
            "What did we decide about the {s} {t}?", "Reconstruct the order of events in the {s} outage",
            "Who changed the {s} {t} and why?", "Compare the {s} before and after the 2.0 release",
        ],
        "no": [
            "Add a date picker to the {s}", "Show the latest messages first in the {s}",
            "Fix the {s} crash on {p}", "Format timestamps in the {s} as relative times",
            "Add a 'last updated' label to the {s}", "Sort the {s} by newest", "Rename the {s}",
            "Add a calendar export to the {s}", "Make the {s} faster on {p}", "Update the {s} copy",
            "Schedule the {s} sync every hour", "Add a history tab to the {s}",
        ],
    },
}

CONFLICTS = [
    ("The app works fully offline", "The app always shows live data from the server"),
    ("The public API stays unchanged", "The {s} endpoint is renamed"),
    ("No new dependencies are added", "The {s} uses {b} as a new dependency"),
    ("The {s} is removed", "The {s} keeps working as before"),
    ("Data never leaves the device", "Data is synced to the cloud"),
    ("The {s} loads without network access", "The {s} fetches fresh data on every open"),
    ("The {s} is visible to every user", "The {s} is hidden behind a feature flag for everyone"),
    ("Users are never asked for permissions", "The {s} requests location permission on start"),
    ("The binary size does not grow", "The {s} bundles a 40 MB model"),
    ("Only {p} is supported", "The {s} ships on every platform"),
]
COMPATIBLE = [
    "The {s} supports dark mode", "The {s} supports light mode", "Unit tests pass", "Lint passes",
    "The {s} loads in under 2 seconds", "The {s} is accessible with a screen reader", "The {s} works on {p}",
    "The {s} strings are translated", "Crash rate stays below 1%", "The {s} has an empty state",
    "Response time is under 100 ms", "Response time is under 200 ms", "The {s} logs errors",
    "Existing users keep their settings", "The {s} is documented in the README",
]

VERIFICATION = {
    "lint": ["lint passes on the {s}", "no warnings from the style checker", "ktlint is clean",
             "the {s} code is formatted", "detekt reports no issues", "code style matches the guide",
             "eslint shows no errors in the {s}", "the formatter leaves the {s} unchanged",
             "static analysis is clean for the {s}", "there are no lint errors"],
    "test": ["unit tests pass", "the regression suite stays green", "the {s} tests are green",
             "coverage of the {s} is above 80%", "all assertions in the {s} spec hold",
             "instrumented tests succeed on {p}", "the new test reproduces the bug and now passes",
             "the {s} suite runs without failures",
             "end-to-end tests cover the {s}", "the failing case is now covered by a test"],
    "build": ["the app compiles", "the {s} builds on {p}", "assembles a release APK", "the project builds on CI",
              "gradle build succeeds", "the {s} module compiles without errors", "a signed bundle is produced",
              "the {p} target links",
              "the release build completes", "compilation finishes with no errors"],
    "health-check": ["the service answers at /status", "the {s} endpoint responds with 200",
                     "the deployed {s} is reachable", "health checks are green after deploy",
                     "the {s} server stays up for an hour", "the API serves requests in production",
                     "uptime monitoring reports the {s} as healthy", "the staging endpoint replies",
                     "the {s} service is live after rollout", "the load balancer sees the {s} as up"],
    "source-verification": ["every claim links a primary source", "the {s} report cites its references",
                            "figures are backed by dated publications", "each statistic has a citation",
                            "quotes are traced to their original source", "the research notes list their sources",
                            "references are checked against the publications", "claims cite peer-reviewed work",
                            "the {s} summary credits its sources", "numbers trace back to the original dataset"],
    "evidence-check": ["screenshots of the {s} are attached", "the {s} matches the mockup",
                       "product signs off on the {s}", "the latest version is shown in the {s}",
                       "the date format is updated in the {s}", "copy is reviewed by marketing",
                       "the {s} looks right on {p}", "a demo video is recorded", "the changelog mentions the {s}",
                       "the {s} behaves as described in the ticket"],
}

ADVERSARIAL = {
    "ambiguous-objective": [
        ("Improve the startup time of the Android app to under 2 seconds", "no"), ("Fix it", "yes"),
        ("Make the settings screen better", "yes"), ("Clean up", "yes"), ("Do the usual", "yes"),
        ("Update the copyright year in the footer", "no"), ("Fix the bug", "yes"),
        ("Fix the crash when tapping Save with an empty title", "no"),
    ],
    "architectural-decision": [
        ("Fix a typo in the architecture doc", "no"), ("Choose between Room and SQLDelight for local storage", "yes"),
        ("Move business logic out of the views into a domain module", "yes"), ("Add a log line to the repository", "no"),
        ("Bump Room to the latest version", "no"), ("Decide how the app and server sync conflicting edits", "yes"),
    ],
    "multi-step-reasoning": [
        ("Fix the typo in the title, that's all", "no"), ("First reproduce the crash, then fix it, then add a test", "yes"),
        ("Add a button", "no"), ("Migrate all screens to Compose and remove the XML layouts", "yes"),
        ("Rename the app", "no"), ("Audit, fix and document every permission the app requests", "yes"),
    ],
    "chronological-context": [
        ("What changed in the sync engine since Monday?", "yes"), ("When did login start failing?", "yes"),
        ("Add a date picker to the export dialog", "no"), ("Show the latest messages first", "no"),
        ("Why was the cache removed in the last release?", "yes"), ("Add a 'last seen' timestamp to profiles", "no"),
    ],
    "contradictory-criteria": [
        ("App works fully offline ; App always shows live server data", "yes"),
        ("Keep the public API unchanged ; Rename the public endpoint", "yes"),
        ("Tests pass ; Lint passes", "no"), ("Supports dark mode ; Supports light mode", "no"),
        ("Response under 100 ms ; Response under 50 ms", "no"),
        ("Data never leaves the device ; Back up data to the cloud", "yes"),
    ],
    "verification-operation": [
        ("no warnings from the style checker", "lint"), ("the app compiles on CI", "build"),
        ("the service answers at /status", "health-check"), ("every claim links a primary source", "source-verification"),
        ("screenshots are attached", "evidence-check"), ("the latest version is shown", "evidence-check"),
        ("update the date format", "evidence-check"), ("unit tests are green", "test"),
        ("the regression suite stays green", "test"), ("assembles a release APK", "build"),
    ],
}


def slots(rng, vocab):
    a, b = rng.choice(TECH[vocab])
    subjects = rng.sample(SUBJECTS[vocab], 2)
    return {"s": subjects[0], "s2": subjects[1], "p": rng.choice(PLATFORMS[vocab]),
            "t": rng.choice(TIMES[vocab]), "a": a, "b": b}


def fill(template, rng, vocab, values=None):
    return template.format(**(values or slots(rng, vocab)))


def vary(text, rng):
    prefix = rng.choice(PREFIXES)
    if prefix and prefix != "TODO: " and prefix != "Task: ":
        text = text[0].lower() + text[1:]
    text = prefix + text
    if rng.random() < 0.15:
        text = text.lower()
    if rng.random() < 0.3:
        text = text.rstrip(".?") + rng.choice([".", "", "!"]) if not text.endswith("?") else text
    return text


def template_split(index):
    """Templates by index: 0-2 of every 5 train, 3 validation, 4 test."""
    return ("train", "train", "train", "validation", "test")[index % 5]


def rows_for(question, label, templates, rng, per_split, make):
    out = {"train": set(), "validation": set(), "test": set()}
    by_split = {split: [t for i, t in enumerate(templates) if template_split(i) == split] for split in out}
    for split, chosen in by_split.items():
        if not chosen:
            continue
        vocab = 1 if split == "test" else 0
        for _ in range(per_split[split] * 20):
            if len(out[split]) >= per_split[split]:
                break
            out[split].add(make(rng.choice(chosen), rng, vocab))
    return [{"question": question, "text": text, "label": label, "split": split, "tags": [f"class:{label}", "regular"]}
            for split, texts in out.items() for text in sorted(texts)]


def criteria_text(rng, vocab, conflict):
    pool = [fill(c, rng, vocab) for c in rng.sample(COMPATIBLE, rng.randint(1, 3))]
    if conflict is not None:
        shared = slots(rng, vocab)  # both sides of a conflict are about the same thing
        pool += [fill(side, rng, vocab, shared) for side in conflict]
    rng.shuffle(pool)
    return SEPARATOR.join(dict.fromkeys(pool))


CRITERION_PREFIXES = ["", "", "Ensure ", "Verify that ", "Make sure ", "Check that ", "- "]
CRITERION_SUFFIXES = ["", "", "", " before merge", " on {p}", " for the {s}", " after the change"]


def criterion_part(template, rng, vocab):
    values = slots(rng, vocab)
    return (rng.choice(CRITERION_PREFIXES) + fill(template, rng, vocab, values)
            + fill(rng.choice(CRITERION_SUFFIXES), rng, vocab, values))


def generate(rows_per_class):
    rng = random.Random(SEED)
    per_split = {"train": rows_per_class, "validation": max(20, rows_per_class // 5), "test": max(20, rows_per_class // 5)}
    rows = []
    for question, answers in TEMPLATES.items():
        for label, templates in answers.items():
            rows += rows_for(question, label, templates, rng, per_split, lambda t, r, v: vary(fill(t, r, v), r))
    for label, conflicts in (("yes", CONFLICTS), ("no", [None] * len(CONFLICTS))):
        rows += rows_for("contradictory-criteria", label, conflicts, rng, per_split,
                         lambda c, r, v: criteria_text(r, v, c))
    for label, templates in VERIFICATION.items():
        rows += rows_for("verification-operation", label, templates, rng, per_split, criterion_part)
    for question, cases in ADVERSARIAL.items():
        rows += [{"question": question, "text": text, "label": label, "split": "adversarial",
                  "tags": [f"class:{label}", "adversarial"]} for text, label in cases]
    # A text in a later split never also appears in an earlier one for the same question.
    seen, kept = set(), []
    for split in ("train", "validation", "test", "adversarial"):
        for row in rows:
            key = (row["question"], row["text"].lower())
            if row["split"] == split and (split == "adversarial" or key not in seen):
                seen.add(key)
                kept.append(row)
    for row in kept:
        assert row["label"] in QUESTIONS[row["question"]], row
    return kept


def main():
    args = sys.argv[1:]
    rows_per_class = 600
    if "--rows-per-class" in args:
        index = args.index("--rows-per-class")
        rows_per_class = int(args[index + 1])
        del args[index:index + 2]
    out = Path(args[0] if args else "build/decision-corpus")
    out.mkdir(parents=True, exist_ok=True)
    rows = generate(rows_per_class)
    with (out / "decisions.jsonl").open("w") as handle:
        for row in rows:
            handle.write(json.dumps(row) + "\n")
    counts = {}
    for row in rows:
        counts.setdefault(row["question"], {}).setdefault(row["split"], {}).setdefault(row["label"], 0)
        counts[row["question"]][row["split"]][row["label"]] += 1
    (out / "manifest.json").write_text(json.dumps(
        {"schema": 1, "seed": SEED, "separator": SEPARATOR, "questions": QUESTIONS, "counts": counts}, indent=2))
    print(json.dumps(counts, indent=1))


if __name__ == "__main__":
    main()
