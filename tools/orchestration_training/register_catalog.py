"""Registers a released orchestration catalog in the app.

Usage: python3 tools/orchestration_training/register_catalog.py path/to/catalog.json

Rewrites OrchestrationSpecialistCatalog.RELEASED with the specialists from catalog.json, keeping only
the fields the app reads. Scores stay in the release's catalog.json and each archive's
model-manifest.json.
"""
import json
import re
import sys
from pathlib import Path

KOTLIN = Path(__file__).resolve().parents[2] / (
    "shared/src/commonMain/kotlin/com/hereliesaz/geministrator/orchestration/OrchestrationSpecialistCatalog.kt"
)
FIELDS = ("logicalArtifactId", "foundationModelId", "releaseRepository", "releaseTag", "assetName", "sha256",
          "format", "precision", "kind", "adapterId", "capabilities")


def artifact(v):
    return {k: v[k] for k in FIELDS if k in v}


def main(path):
    catalog = json.loads(Path(path).read_text())
    specialists = []
    for s in sorted(catalog["specialists"], key=lambda s: s["specialistId"]):
        entry = {"specialistId": s["specialistId"]}
        for key in ("mergedVariants", "sharedBaseVariants"):
            if s.get(key):
                entry[key] = [artifact(v) for v in s[key]]
        if s.get("adapter"):
            entry["adapter"] = artifact(s["adapter"])
        specialists.append(entry)
    for s in specialists:
        for v in s.get("mergedVariants", []) + s.get("sharedBaseVariants", []) + ([s["adapter"]] if "adapter" in s else []):
            if not re.fullmatch(r"[0-9a-f]{64}", v["sha256"]):
                raise SystemExit(f"{v['logicalArtifactId']}: sha256 is not a lowercase SHA-256 digest")
    compact = json.dumps({"specialists": specialists}, separators=(",", ":"))
    if '"""' in compact or "$" in compact:
        raise SystemExit("catalog contains characters that cannot go into a Kotlin raw string")
    source = KOTLIN.read_text()
    begin, end = "    // register_catalog.py:begin\n", "    // register_catalog.py:end\n"
    head, rest = source.split(begin)
    _, tail = rest.split(end)
    KOTLIN.write_text(f'{head}{begin}    const val RELEASED: String = """{compact}"""\n{end}{tail}')
    print(f"registered {len(specialists)} specialists in {KOTLIN.relative_to(KOTLIN.parents[8])}")


if __name__ == "__main__":
    main(sys.argv[1])
