import io
import json
import re
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

import score

ROOT = Path(__file__).resolve().parents[2]
KOTLIN = ROOT / "shared/src/desktopTest/kotlin/com/hereliesaz/geministrator/adapter/AppIntegrationVerifiers.kt"
PROVIDER = ROOT / "shared/src/commonMain/kotlin/com/hereliesaz/geministrator/providers/OnDeviceTextAgentProvider.kt"


def row(rid, family, sub, split, checks, expected, user="ROLE\nYou are careful.\n\nTASK\nDo it.", group=""):
    return {
        "id": rid, "family": family, "sub": sub, "split": split, "domain": "x", "group": group,
        "verifier": "v", "check": {}, "checks": checks, "checks_version": 1,
        "messages": [
            {"role": "system", "content": score.SYSTEM_PROMPT},
            {"role": "user", "content": user},
            {"role": "assistant", "content": expected},
        ],
    }


class EvaluateTest(unittest.TestCase):
    def ok(self, checks, text):
        self.assertIsNone(score.evaluate(checks, text), text)

    def bad(self, checks, text):
        self.assertIsNotNone(score.evaluate(checks, text), text)

    def test_form(self):
        self.ok({"form": {}}, "A plain answer.")
        self.bad({"form": {}}, "")
        self.bad({"form": {}}, "```\nx\n```")
        self.bad({"form": {}}, "Sure, here you go.")
        self.ok({"form": {"allow_fence": True}}, "```\nx\n```")

    def test_json(self):
        spec = {"json": {"keys": ["build", "owner"], "values": {"build": "0412", "owner": "Nair, Priya"}}}
        self.ok(spec, '{"build":"0412","owner":"Nair, Priya"}')
        self.bad(spec, '{"build":412,"owner":"Nair, Priya"}')
        self.bad(spec, '{"build":"0412"}')
        self.bad(spec, 'Here: {"build":"0412","owner":"Nair, Priya"}')
        self.ok({"json": {"keys": ["n"], "values": {"n": "12"}}}, '{"n":12}')

    def test_counts_and_case(self):
        self.ok({"bullets": 2, "bullet_max_words": 3}, "- cache fixed\n- login ok")
        self.bad({"bullets": 2}, "- cache fixed")
        self.bad({"bullets": 2, "bullet_max_words": 2}, "- cache fixed up\n- login ok")
        self.ok({"sentences": 1, "max_words": 5}, "One sentence here.")
        self.bad({"sentences": 1}, "One. Two.")
        self.ok({"case": "upper"}, "BUILD 4.12 OK")
        self.bad({"case": "upper"}, "Mixed")

    def test_plan(self):
        spec = {"numbered_steps": {"min": 2, "max": 7, "max_words_per_step": 30}, "forbid_sets": ["claims"]}
        self.ok(spec, "1. Read it.\n2. Write it.")
        self.bad(spec, "1. Read it.")
        self.bad(spec, "1. Read it.\n3. Write it.")
        self.bad(spec, "1. Read it.\n2. I ran the tests.")
        self.bad(spec, "Plan:\n1. Read it.\n2. Write it.")

    def test_claims_and_cannot(self):
        spec = {"forbid_sets": ["claims"], "require_sets": ["cannot"]}
        self.ok(spec, "I cannot run the tests here.")
        self.bad(spec, "I ran the tests and they passed. I cannot say more.")
        self.bad(spec, "The tests are fine.")
        self.ok({"forbid_sets": ["claims"]}, "I can’t run it, and I did not run any tests.")

    def test_command(self):
        spec = {"bare_command_line": {"command": "/remindme", "count": 1}, "forbid_commands": {"allow": ["/remindme"]}}
        self.ok(spec, "Noting it.\n/remindme")
        self.ok(spec, "Noting it.\n  /remindme\t")
        self.bad(spec, "Noting it. /remindme")
        self.bad(spec, "/remindme\n/remindme")
        self.bad(spec, "Noting it.\n/remindme\n/forget")
        self.ok({"bare_command_line": {"command": "/remindme", "count": 0}}, "Nothing new to send.")
        self.bad({"bare_command_line": {"command": "/remindme", "count": 0}}, "Done.\n/remindme")
        self.ok(spec, "See src/login for details.\n/remindme")

    def test_last_sentence_and_numbers(self):
        spec = {"must_include": ["30 seconds", "45 seconds"], "last_sentence": {"must_include": ["45 seconds"], "must_not_include": ["30 seconds"]}}
        self.ok(spec, "Memory holds 30 seconds and 45 seconds. So the timeout is 45 seconds.")
        self.bad(spec, "Memory holds 30 seconds and 45 seconds. So the timeout is 30 seconds.")
        self.ok({"allowed_numbers": ["12", "2026"]}, "Used 12 times in 2026.")
        self.bad({"allowed_numbers": ["12"]}, "Used 13 times.")

    def test_sequences_and_overlap(self):
        ops = ["Make lint check", "Make schema validate", "Make release build"]
        spec = {"numbered_steps": {"min": 2, "max": 2}, "each_step_includes_one_of": ops, "ordered_includes": ops[:2]}
        self.ok(spec, "1. Make lint check\n2. Make schema validate")
        self.bad(spec, "1. Make schema validate\n2. Make lint check")
        self.bad(spec, "1. Make lint check\n2. Make deploy")
        prefix = "you are a release note writer for the web team standing context notes are read on small screens"
        self.assertTrue(score.shares_ngram("standing context notes are read on small screens", prefix))
        self.bad({"no_overlap_with": {"text": prefix, "max_ngram": 8}}, "Standing context: notes are read on small screens, so keep it.")
        self.ok({"no_overlap_with": {"text": prefix, "max_ngram": 8}}, "The cart page loads twice.")


class CorpusTest(unittest.TestCase):
    def test_layout(self):
        good = "ROLE\nYou are careful.\n\nTASK\nDo it.\n\nACCEPTANCE CRITERIA\n1. Be short.\n\nNOTE\ntext\n\nPLAN ONLY\nReply with a plan."
        self.assertIsNone(score.layout_problem(score.SYSTEM_PROMPT, good))
        self.assertIsNotNone(score.layout_problem("another system prompt", good))
        self.assertIsNotNone(score.layout_problem(score.SYSTEM_PROMPT, good.replace("You are careful.", "Run 2026-03-14 only.")))
        self.assertIsNotNone(score.layout_problem(score.SYSTEM_PROMPT, "TASK\nDo it."))
        self.assertIsNotNone(score.layout_problem(score.SYSTEM_PROMPT, good + "\n\nNOTE\nmore"))
        self.assertIsNotNone(score.layout_problem(score.SYSTEM_PROMPT, "ROLE\nx\n\nHABITS\ny\n\nTASK\nDo it."))

    def test_group_prefix(self):
        a = row("a", "F7", "F7c", "test", {"form": {}}, "x", "ROLE\nr\n\nTASK\none", group="g")
        b = row("b", "F7", "F7c", "test", {"form": {}}, "x", "ROLE\nr\n\nTASK\ntwo", group="g")
        c = row("c", "F7", "F7c", "test", {"form": {}}, "x", "ROLE\nother\n\nTASK\nthree", group="g")
        self.assertEqual([], score.corpus_problems([a, b]))
        self.assertEqual(1, len(score.corpus_problems([a, b, c])))

    def test_strict_and_stats(self):
        r = row("a", "F1", "F1-x", "test", {"form": {}}, "x")
        del r["checks"]
        self.assertEqual(1, len(score.strict_problems([r])))
        self.assertIn("F1", score.stable_share([row("a", "F1", "F1-x", "test", {"form": {}}, "x")]))


class SyncWithKotlinTest(unittest.TestCase):
    def block(self, source, name):
        m = re.search(r"// BEGIN %s\n(.*?)// END %s" % (name, name), source, re.S)
        self.assertIsNotNone(m, name)
        return re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(1))

    def test_phrase_sets_match_the_kotlin_verifiers(self):
        source = KOTLIN.read_text(encoding="utf-8")
        for name in ("PREAMBLE", "CLAIMS", "CANNOT", "UNCERTAIN", "CERTAINTY", "REOPEN", "USUAL"):
            self.assertEqual(getattr(score, name), [s.replace("\\'", "'") for s in self.block(source, name)], name)

    def test_constants_match_the_kotlin_source(self):
        source = KOTLIN.read_text(encoding="utf-8")
        self.assertIn('const val NOT_IN_TEXT = "%s"' % score.NOT_IN_TEXT, source)
        self.assertIn("const val CHECKS_VERSION = %d" % score.CHECKS_VERSION, source)
        provider = PROVIDER.read_text(encoding="utf-8")
        m = re.search(r"const val SYSTEM_PROMPT =(.*?)\n\n", provider, re.S)
        self.assertIsNotNone(m)
        literals = re.findall(r'"((?:[^"\\]|\\.)*)"', m.group(1))
        self.assertEqual(score.SYSTEM_PROMPT, "".join(literals))
        self.assertIn("const val MAX_NGRAM = %d" % score.MAX_NGRAM, source)


class EndToEndTest(unittest.TestCase):
    def run_main(self, rows, predictions=None, extra=()):
        with tempfile.TemporaryDirectory() as tmp:
            by_split = {}
            for r in rows:
                by_split.setdefault(r["split"], []).append(r)
            for split, items in by_split.items():
                (Path(tmp) / (split + ".jsonl")).write_text("".join(json.dumps(i) + "\n" for i in items), encoding="utf-8")
            args = ["--corpus", tmp] + list(extra)
            if predictions is not None:
                p = Path(tmp) / "pred.jsonl"
                p.write_text("".join(json.dumps({"id": k, "output": v}) + "\n" for k, v in predictions.items()), encoding="utf-8")
                args += ["--predictions", str(p)]
            out = io.StringIO()
            with redirect_stdout(out):
                code = score.main(args)
            return code, out.getvalue()

    def fixture(self):
        rows = []
        for split in ("test", "adversarial"):
            for fam, sub in (("F1", "F1-x"), ("F2", "F2-x"), ("F3", "F3-x"), ("F4", "F4e-x"), ("F5", "F5-x"), ("F6", "F6c-x"), ("F7", "F7c-x"), ("F7", "F7d-x")):
                rows.append(row("%s-%s" % (sub, split), fam, sub, split, {"form": {}, "exact": "ok"}, "ok", group="g-" + split if fam == "F7" else ""))
        return rows

    def test_self_check_passes_and_wrong_answers_fail_the_gates(self):
        rows = self.fixture()
        code, out = self.run_main(rows, extra=["--self-check", "--strict"])
        self.assertEqual(0, code, out)
        good = {r["id"]: "ok" for r in rows}
        self.assertEqual(0, self.run_main(rows, good, ["--strict"])[0])
        bad = dict(good)
        bad["F3-x-test"] = "I ran it"
        code, out = self.run_main(rows, bad)
        self.assertEqual(1, code)
        self.assertIn("FAIL  F3 on test", out)
        self.assertIn("FAIL  F3 on test >= 0.95", out)
        missing = dict(good)
        del missing["F1-x-adversarial"]
        self.assertEqual(1, self.run_main(rows, missing)[0])

    def test_strict_rejects_rows_without_checks(self):
        rows = self.fixture()
        del rows[0]["checks"]
        code, _ = self.run_main(rows, extra=["--self-check", "--strict"])
        self.assertEqual(2, code)


if __name__ == "__main__":
    unittest.main()
