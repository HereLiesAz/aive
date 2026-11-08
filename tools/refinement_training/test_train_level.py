import json
import tempfile
import unittest
from pathlib import Path

import train_level as t
from build_corpus import canonical


def row(i, split, expected, coarser=None):
    return {
        "id": f"r{i}", "split": split, "tags": [],
        "input": json.dumps({"level": 2, "of": 3, "objective": f"o{i}", "coarser": coarser}, sort_keys=True),
        "expected": canonical(expected),
    }


def corpus(rows, ready=True, test_rows=None):
    directory = Path(tempfile.mkdtemp())
    entry = {"rows": len(rows), "splits": {"test": sum(1 for r in rows if r["split"] == "test")},
             "shortcut_rates": {"copy": 0.0, "constant": 0.1}, "ready": ready, "corpus_sha256": "x"}
    (directory / "manifest.json").write_text(json.dumps({"levels": {"2": entry}, "label_note": "n"}))
    (directory / "refinement-level-2.config.json").write_text(json.dumps({
        "specialist_id": "refinement:level-2", "system_prompt": "s", "foundation_model_id": "m",
        "gates": {"metric": "json_exact", "min_rows": 200, "min_test_score": 0.6, "max_shortcut_rate": 0.2},
    }))
    (directory / "refinement-level-2.jsonl").write_text("".join(json.dumps(r) + "\n" for r in rows))
    return directory


class TrainLevelTest(unittest.TestCase):
    def test_json_exact_reads_the_object_inside_chatter_and_nothing_else(self):
        expected = canonical({"a": 1})
        self.assertTrue(t.json_exact('Sure: {"a": 1} done', expected))
        self.assertFalse(t.json_exact('{"a": 2}', expected))
        self.assertFalse(t.json_exact("no json here", expected))

    def test_baselines_are_copying_the_level_above_and_the_most_common_answer(self):
        same = {"k": 1}
        train = [row(1, "train", {"k": 9}), row(2, "train", {"k": 9}), row(3, "train", {"k": 8})]
        test = [row(4, "test", same, coarser=same), row(5, "test", {"k": 9}), row(6, "test", {"k": 7}), row(7, "test", {"k": 6})]
        base = t.baselines(train, test)
        self.assertEqual(0.25, base["copy"])
        self.assertEqual(0.25, base["constant"])
        self.assertEqual({"copy": 0.0, "constant": 0.0}, t.baselines(train, []))

    def test_a_model_must_beat_the_best_baseline_by_more_than_the_noise(self):
        gates = {"min_test_score": 0.6}
        base = {"copy": 0.1, "constant": 0.3}
        clear = t.verdict(0.9, base, 400, gates, ready=True)
        self.assertTrue(clear["passed"])
        within_noise = t.verdict(0.32, base, 50, gates, ready=True)
        self.assertFalse(within_noise["beats_baselines"])
        low = t.verdict(0.5, {"copy": 0.0, "constant": 0.0}, 400, gates, ready=True)
        self.assertTrue(low["beats_baselines"])
        self.assertFalse(low["passed"], "beating the baselines does not meet the minimum score")
        self.assertFalse(t.verdict(0.9, base, 400, gates, ready=False)["passed"], "an unready level never passes")
        self.assertEqual(1.0, t.noise_margin(0.5, 0))

    def test_score_counts_exact_matches_and_samples_failures(self):
        rows = [row(1, "test", {"a": 1}), row(2, "test", {"a": 2})]
        rate, failures = t.score(['{"a": 1}', "nope"], rows)
        self.assertEqual(0.5, rate)
        self.assertEqual(["r2"], [f["id"] for f in failures])
        self.assertEqual((0.0, []), t.score([], []))

    def test_an_unready_level_is_refused_unless_allowed_and_then_marked(self):
        rows = [row(i, "train", {"a": i % 2}) for i in range(6)] + [row(10, "test", {"a": 1})]
        directory = corpus(rows, ready=False)
        with self.assertRaises(t.NotReady):
            t.plan(directory, 2, allow_unready=False)
        decided = t.plan(directory, 2, allow_unready=True)
        self.assertFalse(decided["ready"])
        self.assertEqual({"train": 6, "validation": 0, "test": 1}, decided["rows"])
        with self.assertRaises(t.NotReady):
            t.plan(directory, 5, allow_unready=True)

    def test_dry_run_refuses_with_exit_code_2_and_prints_the_plan_when_ready(self):
        rows = [row(i, "train", {"a": i % 2}) for i in range(4)] + [row(10, "test", {"a": 1})]
        self.assertEqual(2, t.main(["--corpus", str(corpus(rows, ready=False)), "--level", "2", "--dry-run"]))
        self.assertEqual(0, t.main(["--corpus", str(corpus(rows, ready=True)), "--level", "2", "--dry-run"]))


if __name__ == "__main__":
    unittest.main()
