import unittest

import benchmark


class BenchmarkScoringTest(unittest.TestCase):
    def test_extract_json_ignores_prompt_schema_before_output_marker(self):
        text = (
            'prompt {"nodes":[]} MODEL_OUTPUT_START\\nnoise\\n'
            '{"sections":[],"nodes":[{"kind":"NounTag","text":"Android"}],"links":[]}'
        )
        parsed = benchmark.extract_json(text)
        self.assertEqual("NounTag", parsed["nodes"][0]["kind"])

    def test_score_requires_json_kind_and_terms(self):
        case = {
            "id": "x",
            "expected_kind": "NounTag",
            "expected_terms": ["Android", "Qwen"],
        }
        output = (
            'MODEL_OUTPUT_START\\n'
            '{"sections":[],"nodes":[{"kind":"NounTag","text":"Android"},'
            '{"kind":"NounTag","text":"Qwen"}],"links":[]}'
        )
        result = benchmark.score(case, output, 1.0, 4)
        self.assertTrue(result.valid_json)
        self.assertTrue(result.correct_kind)
        self.assertEqual(1.0, result.term_recall)

    def test_decision_never_adopts_without_aive_transformer_runtime(self):
        case = benchmark.CaseResult("x", True, True, 1.0, "{}", 1.0, 1)
        qwen = benchmark.RunnerResult("q", "onnx", 1, 1.0, 1, [case])
        bitnet = benchmark.RunnerResult("b", "bitnet", 1, 1.0, 1, [case])
        result = benchmark.decision(qwen, bitnet)
        self.assertTrue(result["qualityParity"])
        self.assertFalse(result["haiveRuntimeReady"])
        self.assertFalse(result["adoptBitNet"])


if __name__ == "__main__":
    unittest.main()
