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


if __name__ == "__main__":
    unittest.main()
