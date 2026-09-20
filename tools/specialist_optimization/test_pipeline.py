import json
import tempfile
import unittest
from pathlib import Path

import pipeline


class PipelineTest(unittest.TestCase):
    def test_config_and_dataset_contract(self):
        config = pipeline.OptimizationConfig.from_mapping(
            {
                "specialist_id": "utility:test",
                "foundation_model_id": "Qwen/Qwen2.5-0.5B-Instruct",
                "optimizer": "gepa",
                "student_model": "openai/gpt-5-mini",
                "metric": "normalized_exact",
                "gates": {"min_test_score": 0.75, "min_adversarial_score": 0.5},
            }
        )
        self.assertEqual("gepa", config.optimizer)
        self.assertEqual(2, config.num_threads)

    def test_metrics_are_deterministic(self):
        self.assertEqual(1.0, pipeline.score_output("normalized_exact", "A  B", " a b "))
        self.assertEqual(1.0, pipeline.score_output("json_exact", '{"a":1}', '{ "a": 1 }'))
        self.assertEqual(1.0, pipeline.score_output("contains_all", "alpha\nbeta", "Beta then ALPHA"))
        self.assertEqual(0.0, pipeline.score_output("contains_all", "alpha\nbeta", "alpha only"))

    def test_release_gate_rejects_regression_and_adversarial_failure(self):
        baseline = pipeline.Evaluation(0.9, 9, 10, ())
        optimized = pipeline.Evaluation(0.8, 8, 10, ("x", "y"))
        adversarial = pipeline.Evaluation(0.6, 3, 5, ("a", "b"))
        result = pipeline.check_release_gates(
            baseline,
            optimized,
            adversarial,
            pipeline.ReleaseGates(
                min_test_score=0.75,
                min_improvement=0.0,
                min_adversarial_score=0.8,
            ),
        )
        self.assertFalse(result.passed)
        self.assertEqual(2, len(result.reasons))

    def test_dataset_requires_named_release_splits(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "data.jsonl"
            records = [
                {"id": "tr", "input": "i", "expected": "o", "split": "train"},
                {"id": "va", "input": "i", "expected": "o", "split": "validation"},
                {"id": "te", "input": "i", "expected": "o", "split": "test"},
                {"id": "ad", "input": "i", "expected": "o", "split": "adversarial"},
            ]
            path.write_text("\n".join(json.dumps(x) for x in records) + "\n", encoding="utf-8")
            loaded = pipeline.load_dataset(path)
            self.assertEqual(4, len(loaded))
            self.assertEqual(
                {"train", "validation", "test", "adversarial"},
                {x.split for x in loaded},
            )

    def test_release_bundle_contains_manifest_and_artifacts(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            manifest = root / "runtime-manifest.json"
            program = root / "optimized-program.json"
            data = root / "adapter-training.jsonl"
            manifest.write_text("{}\n")
            program.write_text("{}\n")
            data.write_text("{}\n")
            bundle = pipeline.bundle_release(root, manifest, [program, data])
            self.assertTrue(bundle.is_file())


if __name__ == "__main__":
    unittest.main()
