import json
import tempfile
import unittest
from pathlib import Path

import build_corpus as bc


def level_doc(level, total, decisions, refines=None, steps=None):
    return {
        "schema": bc.LEVEL_SCHEMA,
        "level": level,
        "of": total,
        "summary": "s",
        "decisions": [
            {"id": d, "statement": d, "refines": (refines or {}).get(d, [])} for d in decisions
        ],
        "steps": steps or [],
        "assumptions": [],
        "openQuestions": [],
    }


def record(ladder, level, doc, verdict="approve", valid=True, total=2, attempt=1, objective="Do it"):
    return {
        "schema": bc.RECORD_SCHEMA,
        "id": f"{ladder}/level-{level}/a{attempt}",
        "kind": "level",
        "ladder": ladder,
        "taskId": f"t{level}",
        "level": level,
        "total": total,
        "attempt": attempt,
        "objective": objective,
        "artifact": json.dumps(doc),
        "structured": True,
        "valid": valid,
        "errors": [],
        "gateVerdict": verdict,
        "capturedAtEpochMillis": 1,
    }


def outcome(ladder, verdict):
    return {
        "schema": bc.RECORD_SCHEMA,
        "id": f"{ladder}/outcome",
        "kind": "outcome",
        "ladder": ladder,
        "taskId": "t",
        "verifierVerdict": verdict,
        "capturedAtEpochMillis": 1,
    }


def ladder_records(name, objective="Do it"):
    one = level_doc(1, 2, ["d1"])
    two = level_doc(2, 2, ["e1"], refines={"e1": ["d1"]}, steps=[{"id": "s", "summary": "s"}])
    return [
        record(f"run/{name}", 1, one, objective=objective),
        record(f"run/{name}", 2, two, objective=objective),
        outcome(f"run/{name}", "approve"),
    ]


class BuildCorpusTest(unittest.TestCase):
    def test_a_level_row_carries_the_approved_answer_above_it(self):
        rows, rejected, ladders = bc.build(ladder_records("a"))
        self.assertEqual(rejected, [])
        self.assertEqual(ladders, {"run/a"})
        self.assertEqual(sorted(rows), [1, 2])
        first = json.loads(rows[1][0]["input"])
        second = json.loads(rows[2][0]["input"])
        self.assertIsNone(first["coarser"])
        self.assertEqual(second["coarser"]["level"], 1)
        self.assertEqual(second["objective"], "Do it")
        self.assertIn("outcome:approve", rows[2][0]["tags"])
        self.assertEqual(json.loads(rows[2][0]["expected"])["level"], 2)

    def test_turned_back_invalid_and_unlabelled_levels_are_not_trained_on(self):
        records = ladder_records("b")
        records[1]["gateVerdict"] = "reject"
        records.append(record("run/c", 1, level_doc(1, 2, ["d1"]), valid=False))
        records.append(record("run/d", 1, level_doc(1, 2, ["d1"]), verdict=None))
        rows, rejected, _ = bc.build(records)
        self.assertEqual(len(rejected), 1)
        self.assertEqual([r["id"] for r in rows[1]], ["run/b/level-1/a1"])
        self.assertNotIn(2, rows)

    def test_a_level_is_skipped_when_the_level_above_was_not_approved(self):
        records = ladder_records("e")
        records[0]["gateVerdict"] = "reject"
        rows, rejected, _ = bc.build(records)
        self.assertEqual(len(rejected), 1)
        self.assertNotIn(2, rows)

    def test_a_document_that_disagrees_with_its_record_is_dropped(self):
        records = ladder_records("f")
        records[0]["artifact"] = json.dumps(level_doc(2, 2, ["d1"]))
        rows, _, _ = bc.build(records)
        self.assertNotIn(1, rows)
        records = ladder_records("g")
        records[0]["artifact"] = "not json"
        self.assertNotIn(1, bc.build(records)[0])

    def test_the_latest_attempt_of_a_level_wins(self):
        records = ladder_records("h")
        newer = record("run/h", 1, level_doc(1, 2, ["d1", "d9"]), attempt=2)
        rows, _, _ = bc.build(records + [newer])
        self.assertEqual([r["id"] for r in rows[1]], ["run/h/level-1/a2"])

    def test_splits_follow_the_ladder_and_never_straddle(self):
        records = []
        for n in range(60):
            records += ladder_records(f"l{n}", objective=f"Task {n}")
        rows, _, _ = bc.build(records)
        by_ladder = {}
        for level_rows in rows.values():
            for row in level_rows:
                by_ladder.setdefault(row["id"].split("/level-")[0], set()).add(row["split"])
        self.assertTrue(all(len(splits) == 1 for splits in by_ladder.values()))
        self.assertEqual({s for splits in by_ladder.values() for s in splits}, set(bc.SPLITS))
        again, _, _ = bc.build(records)
        self.assertEqual(rows[1][0]["split"], again[1][0]["split"])

    def test_duplicate_inputs_are_kept_once(self):
        rows, _, _ = bc.build(ladder_records("i") + ladder_records("j"))
        self.assertEqual(len(rows[1]), 2)
        self.assertEqual(len(bc.dedupe(rows[1])), 1)

    def test_shortcut_rates_catch_a_copy_of_the_level_above(self):
        row = {"input": "x", "expected": bc.canonical({"a": 1}), "split": "test", "_coarser": {"a": 1}}
        other = {"input": "y", "expected": bc.canonical({"b": 2}), "split": "test", "_coarser": {"a": 1}}
        rates = bc.shortcut_rates([row, other])
        self.assertEqual(rates["copy"], 0.5)
        self.assertEqual(rates["constant"], 0.5)

    def test_writing_produces_hashed_files_and_a_manifest_that_says_where_labels_came_from(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "export.jsonl"
            records = []
            for n in range(20):
                records += ladder_records(f"w{n}", objective=f"Task {n}")
            source.write_text("".join(json.dumps(r) + "\n" for r in records), encoding="utf-8")
            out = root / "out"
            self.assertEqual(bc.main(["--input", str(source), "--out", str(out)]), 0)
            manifest = json.loads((out / "manifest.json").read_text())
            self.assertEqual(manifest["label_source"], "gate-agent")
            self.assertEqual(manifest["ladders"], 20)
            body = (out / "refinement-level-1.jsonl").read_bytes()
            self.assertEqual(manifest["levels"]["1"]["corpus_sha256"], bc.sha256(body))
            self.assertFalse(manifest["levels"]["1"]["ready"])  # far below min_rows
            config = json.loads((out / "refinement-level-2.config.json").read_text())
            self.assertEqual(config["specialist_id"], "refinement:level-2")
            self.assertTrue(all(not k.startswith("_") for k in json.loads(body.splitlines()[0])))

    def test_nothing_is_written_without_usable_rows_and_a_wrong_schema_is_refused(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "export.jsonl"
            source.write_text(json.dumps(outcome("run/x", "approve")) + "\n", encoding="utf-8")
            self.assertEqual(bc.main(["--input", str(source), "--out", str(root / "out")]), 1)
            self.assertFalse((root / "out").exists())
            source.write_text(json.dumps({"schema": "other"}) + "\n", encoding="utf-8")
            with self.assertRaises(SystemExit):
                bc.read_records(source)


if __name__ == "__main__":
    unittest.main()
