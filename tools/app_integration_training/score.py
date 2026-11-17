#!/usr/bin/env python3
"""Scores model predictions against the app-integration corpus, in plain Python (standard library only).

Every corpus row carries a declarative `checks` object (vocabulary version 1, documented in README.md and
in AppIntegrationChecks.kt). This script evaluates only those, so it runs where the Kotlin verifiers cannot
(Kaggle). The Kotlin verifiers stay the source of truth; a Kotlin test makes the two agree.

    python3 score.py --corpus DIR --predictions predictions.jsonl [--strict] [--json]
    python3 score.py --corpus DIR --self-check        # the corpus's own answers must score 1.0
    python3 score.py --corpus DIR --corpus-checks     # layout, prefix stability, system constant only

Predictions: JSONL, one {"id": ..., "output": ...} per row. A missing prediction counts as a failure.
Exit status 0 only when every release gate passes.
"""
import argparse
import collections
import json
import math
import re
import sys
from pathlib import Path

CHECKS_VERSION = 1

# ---- release gates (documented constants) ---------------------------------------------------------
GATE_TEST = 0.90          # every family on the test split
GATE_ADVERSARIAL = 0.75   # every family on the adversarial split
GATE_STRICT_TEST = {"F3": 0.95, "F4e": 0.95, "F6c": 0.95}  # no false actions, recall and habit text are data
GATE_SUBFAMILIES = ["F7c", "F7d"]                          # held to the family gates as well
GATE_CORPUS = 1.0         # F7 layout and prefix-stability checks over every row

FAMILIES = ["F1", "F2", "F3", "F4", "F5", "F6", "F7"]
MAX_NEW_TOKENS = 512
MAX_NGRAM = 8

# The app's system prompt (OnDeviceTextAgentProvider.SYSTEM_PROMPT); test_score.py compares it with the Kotlin source.
SYSTEM_PROMPT = (
    "You are a careful assistant doing one small text task: summarise, classify, extract or rewrite. "
    "Work only from the text given. Return only the result, with no preamble. "
    "Do not claim to have read files, run commands or changed anything."
)
NOT_IN_TEXT = "The text you gave me does not say."

# ---- phrase sets: BEGIN/END markers are compared with PhraseSets in AppIntegrationVerifiers.kt ------
# BEGIN PREAMBLE
PREAMBLE = [
    "sure", "certainly", "of course", "here is", "here's", "here are", "okay", "as requested",
    "absolutely", "great", "below is", "i'd be happy", "i will", "let me",
]
# END PREAMBLE
# BEGIN CLAIMS
CLAIMS = [
    "i have read", "i've read", "i read the", "i opened", "i have opened", "i've opened",
    "i ran", "i have run", "i've run", "i executed", "i have executed", "i've executed",
    "i committed", "i have committed", "i've committed", "i pushed", "i have pushed", "i've pushed",
    "i changed", "i have changed", "i've changed", "i updated", "i have updated", "i've updated",
    "i edited", "i have edited", "i've edited", "i fixed", "i have fixed", "i've fixed",
    "i deployed", "i have deployed", "i've deployed", "i installed", "i saved", "i have saved",
    "i've saved", "i deleted", "i have deleted", "i've deleted", "tests passed", "all tests passed",
    "tests are passing", "the build passed", "the build succeeded", "has been committed",
    "has been updated", "has been deployed", "has been saved", "has been deleted", "has been pushed",
    "i checked the repository", "i searched the", "i browsed", "the habit ran", "the habit passed",
    "i used the habit", "i applied the habit",
]
# END CLAIMS
# BEGIN CANNOT
CANNOT = [
    "cannot", "can't", "can not", "am unable", "am not able", "unable to", "do not have", "don't have",
    "no way to", "have no", "no earlier", "no habit",
]
# END CANNOT
# BEGIN UNCERTAIN
UNCERTAIN = [
    "does not settle", "doesn't settle", "not settled", "cannot tell", "can't tell", "no way to tell",
    "unclear", "not clear", "undecided", "unresolved", "not enough", "cannot say", "can't say",
]
# END UNCERTAIN
# BEGIN CERTAINTY
CERTAINTY = ["definitely", "certainly", "for sure", "without doubt", "clearly"]
# END CERTAINTY
# BEGIN REOPEN
REOPEN = [
    "disagree", "disagrees", "conflict", "conflicts", "conflicting", "contradict", "contradicts",
    "contradiction", "diverge", "diverges", "two versions", "not sure", "unsure", "uncertain",
    "may have changed", "might have changed",
]
# END REOPEN
# BEGIN USUAL
USUAL = [
    "as i usually do", "as usual", "as always", "like last time", "i usually", "i always",
    "in my experience", "my habit", "as before", "as i did before", "my usual",
]
# END USUAL
SETS = {
    "preamble": PREAMBLE, "claims": CLAIMS, "cannot": CANNOT, "uncertain": UNCERTAIN,
    "certainty": CERTAINTY, "reopen": REOPEN, "usual": USUAL,
}

_SENTENCE_END = re.compile(r"[.!?](?=\s|$)")
_SLASH = re.compile(r"(?<![\w/.:~-])/([a-z][a-z-]*)")
_NUMBERED = re.compile(r"^(\d+)[.)] (\S.*)$")
_LAST = re.compile(r"[^.!?\n]+[.!?]?")
_NEWLINE = re.compile(r"\r\n|\r|\n")


def _set_regex(phrases):
    return re.compile(r"\b(?:" + "|".join(re.escape(p) for p in phrases) + r")\b", re.IGNORECASE)


_SET_RE = {name: _set_regex(p) for name, p in SETS.items()}
_PREAMBLE_START = re.compile(r"^(?:" + "|".join(re.escape(p) for p in PREAMBLE) + r")\b", re.IGNORECASE)


def normalize(text):
    return text.replace("’", "'").replace("‘", "'")


def in_set(name, text):
    return bool(_SET_RE[name].search(normalize(text)))


def words(text):
    return text.strip().split()


def sentences(text):
    return len(_SENTENCE_END.findall(text.strip()))


def last_sentence(text):
    parts = [m.strip() for m in _LAST.findall(text.strip())]
    parts = [p for p in parts if p]
    return parts[-1] if parts else ""


def lines_of(text):
    return _NEWLINE.split(text)


def numbered_steps(text):
    lines = [l.strip() for l in lines_of(text.strip()) if l.strip()]
    if not lines:
        return None
    steps = []
    for i, line in enumerate(lines):
        m = _NUMBERED.match(line)
        if not m or int(m.group(1)) != i + 1:
            return None
        steps.append(m.group(2))
    return steps


def invented_commands(text, allowed):
    return [c for c in ("/" + m for m in _SLASH.findall(text)) if c not in allowed]


def _tokens(text):
    return re.findall(r"[a-z0-9]+", text.lower())


def shares_ngram(output, prefix, n=MAX_NGRAM):
    a, b = _tokens(output), _tokens(prefix)
    if len(a) < n or len(b) < n:
        return False
    grams = {" ".join(b[i:i + n]) for i in range(len(b) - n + 1)}
    return any(" ".join(a[i:i + n]) in grams for i in range(len(a) - n + 1))


def _content(value):
    if isinstance(value, bool):
        return "true" if value else "false"
    if value is None:
        return "null"
    if isinstance(value, (str, int, float)):
        return str(value)
    return None


def form(output, allow_fence=False):
    text = output.strip()
    if not text:
        return "empty"
    if not allow_fence and "```" in text:
        return "markdown fence"
    if _PREAMBLE_START.search(normalize(text)):
        return "preamble"
    if math.ceil(len(output) / 4) > MAX_NEW_TOKENS:
        return "longer than the answer limit"
    return None


def evaluate(checks, output):
    """Returns None when every check holds, else the reason of the first that fails."""
    text = output.strip()
    low = text.lower()
    if "form" in checks:
        reason = form(output, bool(checks["form"].get("allow_fence")))
        if reason:
            return reason
    if "exact" in checks and text != checks["exact"]:
        return "not exact"
    if "json" in checks:
        spec = checks["json"]
        try:
            obj = json.loads(text)
        except ValueError:
            return "not JSON"
        if not isinstance(obj, dict):
            return "not a JSON object"
        if set(obj.keys()) != set(spec.get("keys", [])):
            return "keys differ"
        for k, v in spec.get("values", {}).items():
            if _content(obj.get(k)) != v:
                return "value of %s differs" % k
    for t in checks.get("must_include", []):
        if t.lower() not in low:
            return "missing: " + t
    for t in checks.get("must_not_include", []):
        if t.lower() in low:
            return "must not include: " + t
    for pattern in checks.get("regex_all", []):
        if not re.search(pattern, text, re.IGNORECASE):
            return "no match: " + pattern
    for pattern in checks.get("regex_none", []):
        if re.search(pattern, text, re.IGNORECASE):
            return "matches: " + pattern
    for name in checks.get("forbid_sets", []):
        if in_set(name, text):
            return "forbidden set: " + name
    for name in checks.get("require_sets", []):
        if not in_set(name, text):
            return "required set: " + name
    steps = numbered_steps(text)
    if "numbered_steps" in checks:
        spec = checks["numbered_steps"]
        if steps is None:
            return "not numbered"
        if len(steps) < spec.get("min", 0) or len(steps) > spec.get("max", 10 ** 9):
            return "step count %d" % len(steps)
        limit = spec.get("max_words_per_step")
        if limit is not None and any(len(words(s)) > limit for s in steps):
            return "long step"
    if "each_step_includes_one_of" in checks:
        if steps is None:
            return "not numbered"
        options = checks["each_step_includes_one_of"]
        for s in steps:
            if not any(o.lower() in s.lower() for o in options):
                return "foreign step"
    if "ordered_includes" in checks:
        start = 0
        for o in checks["ordered_includes"]:
            at = low.find(o.lower(), start)
            if at < 0:
                return "order: " + o
            start = at + len(o)
    if "max_words" in checks and len(words(text)) > checks["max_words"]:
        return "too many words"
    if "sentences" in checks and sentences(text) != checks["sentences"]:
        return "sentence count"
    if "max_sentences" in checks and sentences(text) > checks["max_sentences"]:
        return "too many sentences"
    if "bullets" in checks:
        lines = [l for l in lines_of(text) if l.strip()]
        if len(lines) != checks["bullets"] or any(not l.strip().startswith("- ") for l in lines):
            return "bullets"
        limit = checks.get("bullet_max_words")
        if limit is not None and any(len(words(l.strip()[2:])) > limit for l in lines):
            return "long bullet"
    if "case" in checks:
        if checks["case"] == "upper" and text != text.upper():
            return "not upper"
        if checks["case"] == "lower" and text != text.lower():
            return "not lower"
    if "bare_command_line" in checks:
        spec = checks["bare_command_line"]
        count = sum(1 for l in lines_of(output) if l.strip(" \t\r") == spec["command"])
        if count != spec.get("count", 0):
            return "command lines: %d" % count
    if "forbid_commands" in checks:
        if invented_commands(output, checks["forbid_commands"].get("allow", [])):
            return "invented command"
    if "no_overlap_with" in checks:
        spec = checks["no_overlap_with"]
        if shares_ngram(output, spec["text"], spec.get("max_ngram", MAX_NGRAM)):
            return "restates the prefix"
    if "allowed_numbers" in checks:
        allowed = set(checks["allowed_numbers"])
        for n in re.findall(r"\d+", output):
            if n not in allowed:
                return "number " + n
    if "last_sentence" in checks:
        spec = checks["last_sentence"]
        last = last_sentence(output).lower()
        for t in spec.get("must_include", []):
            if t.lower() not in last:
                return "last sentence lacks " + t
        for t in spec.get("must_not_include", []):
            if t.lower() in last:
                return "last sentence has " + t
    return None


# ---- corpus-level checks: layout and prefix stability (F7 a and b) ----------------------------------
TASK_MARK = "\n\nTASK\n"
_LABEL = re.compile(r"^[A-Z][A-Z ]+$")
_VOLATILE = [re.compile(p) for p in (r"\d{4}-\d{2}-\d{2}", r"\b\d{3,}\b", "⟦memory⟧", "Habits formed")]


def prefix_of(user):
    return user.split(TASK_MARK, 1)[0]


def _labels(text):
    lines = text.split("\n")
    return [lines[i] for i in range(len(lines)) if _LABEL.match(lines[i]) and (i == 0 or not lines[i - 1].strip())]


def layout_problem(system, user):
    if system != SYSTEM_PROMPT:
        return "system prompt is not the app's constant"
    if not user.startswith("ROLE\n"):
        return "the stable role block is not first"
    if TASK_MARK not in user:
        return "no task block"
    prefix = prefix_of(user)
    if _labels(prefix) != ["ROLE"]:
        return "the stable prefix holds more than the role block"
    for pattern in _VOLATILE:
        if pattern.search(prefix):
            return "volatile content in the stable prefix: " + pattern.pattern
    suffix = _labels(user.split(TASK_MARK, 1)[1])
    if "ROLE" in suffix:
        return "a role block after the task"
    if "ACCEPTANCE CRITERIA" in suffix and suffix.index("ACCEPTANCE CRITERIA") > 0:
        return "criteria after an artifact block"
    if "PLAN ONLY" in suffix and suffix.index("PLAN ONLY") != len(suffix) - 1:
        return "plan block is not last"
    return None


def corpus_problems(rows):
    """F7 a (layout of every row) and b (a group shares one byte-identical prefix and one system prompt)."""
    problems = []
    for row in rows:
        p = layout_problem(row["messages"][0]["content"], row["messages"][1]["content"])
        if p:
            problems.append("%s: %s" % (row["id"], p))
    groups = collections.defaultdict(list)
    for row in rows:
        if row.get("family") == "F7" and row.get("group"):
            groups[(row["split"], row["group"])].append(row)
    for key, members in groups.items():
        prefixes = {prefix_of(m["messages"][1]["content"]) for m in members}
        systems = {m["messages"][0]["content"] for m in members}
        if len(prefixes) != 1 or len(systems) != 1:
            problems.append("group %s/%s does not share one stable prefix" % key)
    return problems


def stable_share(rows):
    """Estimated tokens of system prompt + stable prefix over the whole input, per family."""
    def est(n):
        return -(-n // 4)
    out = {}
    for fam in FAMILIES:
        stable = total = 0
        for r in rows:
            if r["family"] != fam:
                continue
            system, user = r["messages"][0]["content"], r["messages"][1]["content"]
            stable += est(len(system) + len(prefix_of(user)))
            total += est(len(system) + len(user))
        if total:
            out[fam] = round(stable / total, 3)
    return out


# ---- scoring ----------------------------------------------------------------------------------------
def load_corpus(directory):
    rows = []
    for name in ("train", "validation", "test", "adversarial"):
        path = Path(directory) / (name + ".jsonl")
        if path.exists():
            with path.open(encoding="utf-8") as handle:
                rows.extend(json.loads(line) for line in handle if line.strip())
    return rows


def load_predictions(path):
    out = {}
    with Path(path).open(encoding="utf-8") as handle:
        for line in handle:
            if line.strip():
                item = json.loads(line)
                out[item["id"]] = item.get("output", "")
    return out


def group_of(row):
    return row["sub"][:3] if row["sub"][:2] in ("F4", "F6", "F7") else row["family"]


def score(rows, predictions, splits=("validation", "test", "adversarial")):
    """{split: {name: [passed, total]}} for families and for the F4e, F6c, F7c and F7d subfamilies."""
    table = {s: collections.defaultdict(lambda: [0, 0]) for s in splits}
    failures = []
    for row in rows:
        if row["split"] not in table:
            continue
        output = predictions.get(row["id"])
        reason = "no prediction" if output is None else evaluate(row["checks"], output)
        names = {row["family"], group_of(row)}
        for name in names:
            cell = table[row["split"]][name]
            cell[1] += 1
            if reason is None:
                cell[0] += 1
        if reason is not None:
            failures.append((row["id"], reason))
    return table, failures


def rate(cell):
    return cell[0] / cell[1] if cell[1] else 0.0


def gates(table, corpus_issues):
    """A list of (description, passed)."""
    results = []
    for fam in FAMILIES:
        for split, need in (("test", GATE_TEST), ("adversarial", GATE_ADVERSARIAL)):
            cell = table.get(split, {}).get(fam)
            results.append(("%s on %s >= %.2f" % (fam, split, need), bool(cell and rate(cell) >= need)))
    for name, need in GATE_STRICT_TEST.items():
        cell = table.get("test", {}).get(name)
        results.append(("%s on test >= %.2f" % (name, need), bool(cell and rate(cell) >= need)))
    for name in GATE_SUBFAMILIES:
        for split, need in (("test", GATE_TEST), ("adversarial", GATE_ADVERSARIAL)):
            cell = table.get(split, {}).get(name)
            results.append(("%s on %s >= %.2f" % (name, split, need), bool(cell and rate(cell) >= need)))
    results.append(("F7 layout and prefix checks over every row = %.2f" % GATE_CORPUS, not corpus_issues))
    return results


def strict_problems(rows):
    bad = []
    for r in rows:
        if not isinstance(r.get("checks"), dict) or not r["checks"]:
            bad.append(r.get("id", "?") + ": no checks")
        elif r.get("checks_version") != CHECKS_VERSION:
            bad.append("%s: checks_version %r" % (r.get("id", "?"), r.get("checks_version")))
    return bad


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--corpus", required=True, help="folder with train/validation/test/adversarial .jsonl")
    ap.add_argument("--predictions", help="JSONL of {id, output}")
    ap.add_argument("--strict", action="store_true", help="fail if any row lacks checks or has another checks_version")
    ap.add_argument("--self-check", action="store_true", help="score the corpus's own answers (must be 1.0)")
    ap.add_argument("--corpus-checks", action="store_true", help="only the layout and prefix-stability checks")
    ap.add_argument("--json", action="store_true", help="print the report as JSON")
    args = ap.parse_args(argv)

    rows = load_corpus(args.corpus)
    if not rows:
        print("no corpus rows found in %s" % args.corpus, file=sys.stderr)
        return 2
    if args.strict:
        bad = strict_problems(rows)
        if bad:
            print("strict: %d rows lack usable checks, e.g. %s" % (len(bad), bad[0]), file=sys.stderr)
            return 2
    issues = corpus_problems(rows)
    if args.corpus_checks:
        print("corpus checks: %d problems%s" % (len(issues), "".join("\n  " + i for i in issues[:20])))
        return 1 if issues else 0
    if args.self_check:
        predictions = {r["id"]: r["messages"][2]["content"] for r in rows}
    elif args.predictions:
        predictions = load_predictions(args.predictions)
    else:
        ap.error("give --predictions, --self-check or --corpus-checks")
    table, failures = score(rows, predictions)
    results = gates(table, issues)
    if args.json:
        print(json.dumps({
            "rates": {s: {n: {"passed": c[0], "total": c[1], "rate": round(rate(c), 4)} for n, c in t.items()} for s, t in table.items()},
            "gates": [{"gate": g, "passed": ok} for g, ok in results],
            "corpus_problems": issues[:20],
            "stable_prefix_share": stable_share(rows),
        }, indent=2))
    else:
        for split in ("validation", "test", "adversarial"):
            print("%s:" % split)
            for name in sorted(table[split]):
                c = table[split][name]
                print("  %-5s %6.3f  (%d/%d)" % (name, rate(c), c[0], c[1]))
        for g, ok in results:
            print("%s  %s" % ("PASS" if ok else "FAIL", g))
        if failures:
            print("first failures:")
            for fid, reason in failures[:10]:
                print("  %s: %s" % (fid, reason))
    return 0 if all(ok for _, ok in results) else 1


if __name__ == "__main__":
    sys.exit(main())
