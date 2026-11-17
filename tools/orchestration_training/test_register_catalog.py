import json
import tempfile
import unittest
from pathlib import Path

import register_catalog


def descriptor(logical_id, release_tag, kind="MergedModel", fmt="onnx", adapter_id=None):
    value = {
        "logicalArtifactId": logical_id,
        "foundationModelId": "Qwen/Qwen2.5-0.5B-Instruct",
        "releaseRepository": "HereLiesAz/aive",
        "releaseTag": release_tag,
        "assetName": logical_id.replace(":", "-") + ".bin",
        "sha256": ("a" if release_tag.endswith("v1") else "b") * 64,
        "format": fmt,
        "precision": "fp16" if kind == "Adapter" else "int8",
        "kind": kind,
    }
    if adapter_id is not None:
        value["adapterId"] = adapter_id
    return value


class RegisterCatalogTest(unittest.TestCase):
    def test_partial_new_release_preserves_old_roles_and_fallback_shapes(self):
        old_merged = descriptor("orchestration:utilities:v1:int8", "orchestration-utilities-v1")
        existing = {
            "specialists": [
                {"specialistId": "orchestration:agent-router", "mergedVariants": [old_merged]},
                {"specialistId": "orchestration:handoff-composer", "mergedVariants": [old_merged]},
            ]
        }
        new_base = descriptor(
            "orchestration:base:v3:int8",
            "orchestration-utilities-v3",
            kind="SharedBase",
        )
        new_adapter = descriptor(
            "orchestration:agent-router:lora:v3",
            "orchestration-utilities-v3",
            kind="Adapter",
            fmt="safetensors",
            adapter_id="agent-router-v3",
        )
        new_tool = descriptor("orchestration:utilities:v3:int8", "orchestration-utilities-v3")
        incoming = {
            "specialists": [
                {
                    "specialistId": "orchestration:agent-router",
                    "sharedBaseVariants": [new_base],
                    "adapter": new_adapter,
                },
                {
                    "specialistId": "orchestration:tool-router",
                    "mergedVariants": [new_tool],
                },
            ]
        }

        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            kotlin = root / "OrchestrationSpecialistCatalog.kt"
            incoming_path = root / "catalog.json"
            kotlin.write_text(
                'object OrchestrationSpecialistCatalog {\n'
                + register_catalog.BEGIN
                + '    const val RELEASED: String = """'
                + json.dumps(existing, separators=(",", ":"))
                + '"""\n'
                + register_catalog.END
                + '}\n'
            )
            incoming_path.write_text(json.dumps(incoming))

            merged = register_catalog.register(incoming_path, kotlin)

        by_id = {entry["specialistId"]: entry for entry in merged["specialists"]}
        self.assertEqual(
            {
                "orchestration:agent-router",
                "orchestration:handoff-composer",
                "orchestration:tool-router",
            },
            set(by_id),
        )
        self.assertEqual(
            "orchestration:utilities:v1:int8",
            by_id["orchestration:agent-router"]["mergedVariants"][0]["logicalArtifactId"],
        )
        self.assertEqual(
            "orchestration:base:v3:int8",
            by_id["orchestration:agent-router"]["sharedBaseVariants"][0]["logicalArtifactId"],
        )
        self.assertEqual(
            "agent-router-v3",
            by_id["orchestration:agent-router"]["adapter"]["adapterId"],
        )
        self.assertEqual(
            "orchestration:utilities:v1:int8",
            by_id["orchestration:handoff-composer"]["mergedVariants"][0]["logicalArtifactId"],
        )
        self.assertEqual(
            "orchestration:utilities:v3:int8",
            by_id["orchestration:tool-router"]["mergedVariants"][0]["logicalArtifactId"],
        )

    def test_new_variant_precedes_old_variant_when_both_exist(self):
        existing = {
            "specialists": [{
                "specialistId": "orchestration:tool-router",
                "mergedVariants": [descriptor("orchestration:tool:v1:int8", "orchestration-utilities-v1")],
            }]
        }
        incoming = {
            "specialists": [{
                "specialistId": "orchestration:tool-router",
                "mergedVariants": [descriptor("orchestration:tool:v3:int8", "orchestration-utilities-v3")],
            }]
        }
        merged = register_catalog.merge_specialists(existing, incoming)
        ids = [
            value["logicalArtifactId"]
            for value in merged["specialists"][0]["mergedVariants"]
        ]
        self.assertEqual(
            ["orchestration:tool:v3:int8", "orchestration:tool:v1:int8"],
            ids,
        )

    def _write_kotlin(self, root, existing):
        kotlin = root / "OrchestrationSpecialistCatalog.kt"
        kotlin.write_text(
            'object OrchestrationSpecialistCatalog {\n'
            + register_catalog.BEGIN
            + '    const val RELEASED: String = """'
            + json.dumps(existing, separators=(",", ":"))
            + '"""\n'
            + register_catalog.END
            + '}\n'
        )
        return kotlin

    def _app_entry(self, tag="app-integration-v1", sha_letter="a"):
        base = descriptor("orchestration:base:v4:int8", tag, kind="SharedBase")
        adapter = descriptor("app-integration:assistant:lora", tag, kind="Adapter", fmt="safetensors", adapter_id="app-integration-v1")
        adapter["sha256"] = sha_letter * 64
        adapter["sizeBytes"] = 18874368
        return {
            "specialistId": "app-integration:assistant",
            "scores": {"gate1": {"F1": 0.97}, "gate2": {"F1": 0.95}},
            "sharedBaseVariants": [base],
            "adapter": adapter,
        }

    def test_refuses_a_specialist_the_app_catalog_cannot_read(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            kotlin = self._write_kotlin(root, {"specialists": []})
            original = kotlin.read_text()
            incoming = root / "catalog.json"
            # An unknown id is refused even next to the app-integration one; nothing is written.
            incoming.write_text(json.dumps({"specialists": [
                self._app_entry(),
                {"specialistId": "memory:noun-tagger"},
            ]}))
            with self.assertRaises(SystemExit) as raised:
                register_catalog.register(incoming, kotlin)
            self.assertIn("memory:noun-tagger", str(raised.exception))
            self.assertEqual(original, kotlin.read_text())

    def test_registers_the_app_integration_adapter_in_its_own_field(self):
        existing = {"specialists": [{"specialistId": "orchestration:agent-router", "mergedVariants": [descriptor("orchestration:utilities:v1:int8", "orchestration-utilities-v1")]}]}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            kotlin = self._write_kotlin(root, existing)
            incoming = root / "catalog.json"
            # The shape the notebook writes: the entry sits in `specialists`, with scores and the adapter's size.
            incoming.write_text(json.dumps({"releaseRepository": "HereLiesAz/aive", "releaseTag": "app-integration-v1", "specialists": [self._app_entry()]}))
            merged = register_catalog.register(incoming, kotlin)

            self.assertEqual(["orchestration:agent-router"], [e["specialistId"] for e in merged["specialists"]])
            app = merged["appIntegration"]
            self.assertEqual("app-integration:assistant", app["specialistId"])
            self.assertEqual(18874368, app["adapter"]["sizeBytes"])
            self.assertEqual({"F1": 0.97}, app["scores"]["gate1"])
            self.assertEqual("orchestration:base:v4:int8", app["sharedBaseVariants"][0]["logicalArtifactId"])
            # What was written into the Kotlin constant is the merged catalog, readable again.
            self.assertEqual(merged, register_catalog.current_catalog(kotlin.read_text()))

            # An orchestration-only release keeps the adapter; a newer adapter release replaces it whole.
            incoming.write_text(json.dumps({"specialists": [{"specialistId": "orchestration:tool-router", "mergedVariants": [descriptor("orchestration:tool:v3:int8", "orchestration-utilities-v3")]}]}))
            kept = register_catalog.register(incoming, kotlin)
            self.assertEqual("a" * 64, kept["appIntegration"]["adapter"]["sha256"])
            incoming.write_text(json.dumps({"specialists": [self._app_entry(tag="app-integration-v2", sha_letter="b")]}))
            replaced = register_catalog.register(incoming, kotlin)
            self.assertEqual("b" * 64, replaced["appIntegration"]["adapter"]["sha256"])
            self.assertEqual(2, len(replaced["specialists"]))

    def test_refuses_an_app_integration_entry_without_an_adapter_or_with_a_bad_digest(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            kotlin = self._write_kotlin(root, {"specialists": []})
            original = kotlin.read_text()
            incoming = root / "catalog.json"
            entry = self._app_entry()
            no_adapter = dict(entry)
            del no_adapter["adapter"]
            bad = self._app_entry()
            bad["adapter"]["sha256"] = "XYZ"
            for value in (no_adapter, bad):
                incoming.write_text(json.dumps({"specialists": [value]}))
                with self.assertRaises(SystemExit):
                    register_catalog.register(incoming, kotlin)
                self.assertEqual(original, kotlin.read_text())


if __name__ == "__main__":
    unittest.main()
