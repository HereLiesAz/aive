"""Merge a newly released orchestration catalog into the app catalog.

Usage: python3 tools/orchestration_training/register_catalog.py path/to/catalog.json

New passing artifacts are preferred, while previously released roles and compatible older variants
remain registered as fallbacks. Scores stay in the release catalog and model manifests.
"""
import json
import re
import sys
from pathlib import Path

KOTLIN = Path(__file__).resolve().parents[2] / (
    "shared/src/commonMain/kotlin/com/hereliesaz/geministrator/orchestration/OrchestrationSpecialistCatalog.kt"
)
FIELDS = (
    "logicalArtifactId",
    "foundationModelId",
    "releaseRepository",
    "releaseTag",
    "assetName",
    "sha256",
    "format",
    "precision",
    "kind",
    "adapterId",
    "capabilities",
)
BEGIN = "    // register_catalog.py:begin\n"
END = "    // register_catalog.py:end\n"


def artifact(value):
    return {key: value[key] for key in FIELDS if key in value}


def specialist(value):
    entry = {"specialistId": value["specialistId"]}
    for key in ("mergedVariants", "sharedBaseVariants"):
        variants = [artifact(item) for item in value.get(key, [])]
        if variants:
            entry[key] = variants
    if value.get("adapter"):
        entry["adapter"] = artifact(value["adapter"])
    return entry


def current_catalog(source):
    try:
        _, rest = source.split(BEGIN, 1)
        block, _ = rest.split(END, 1)
    except ValueError as failure:
        raise SystemExit("OrchestrationSpecialistCatalog registration markers are missing") from failure
    match = re.search(r'const val RELEASED: String = """(.*?)"""', block, flags=re.DOTALL)
    if not match:
        raise SystemExit("Could not find OrchestrationSpecialistCatalog.RELEASED")
    return json.loads(match.group(1))


def merge_variants(new_variants, old_variants):
    merged = {}
    order = []
    for item in list(new_variants) + list(old_variants):
        logical_id = item["logicalArtifactId"]
        if logical_id not in merged:
            order.append(logical_id)
            merged[logical_id] = item
    return [merged[logical_id] for logical_id in order]


def merge_specialists(existing, incoming):
    old_by_id = {entry["specialistId"]: specialist(entry) for entry in existing.get("specialists", [])}
    new_by_id = {entry["specialistId"]: specialist(entry) for entry in incoming.get("specialists", [])}
    result = []
    for specialist_id in sorted(set(old_by_id) | set(new_by_id)):
        old = old_by_id.get(specialist_id, {"specialistId": specialist_id})
        new = new_by_id.get(specialist_id, {"specialistId": specialist_id})
        entry = {"specialistId": specialist_id}
        for key in ("mergedVariants", "sharedBaseVariants"):
            variants = merge_variants(new.get(key, []), old.get(key, []))
            if variants:
                entry[key] = variants
        adapter_value = new.get("adapter") or old.get("adapter")
        if adapter_value:
            entry["adapter"] = adapter_value
        result.append(entry)
    return {"specialists": result}


def validate(catalog):
    seen_specialists = set()
    for entry in catalog["specialists"]:
        specialist_id = entry["specialistId"]
        if specialist_id in seen_specialists:
            raise SystemExit(f"duplicate specialist: {specialist_id}")
        seen_specialists.add(specialist_id)

        artifacts = (
            entry.get("mergedVariants", [])
            + entry.get("sharedBaseVariants", [])
            + ([entry["adapter"]] if "adapter" in entry else [])
        )
        seen_artifacts = set()
        for value in artifacts:
            logical_id = value["logicalArtifactId"]
            if logical_id in seen_artifacts:
                raise SystemExit(f"{specialist_id}: duplicate artifact {logical_id}")
            seen_artifacts.add(logical_id)
            if not re.fullmatch(r"[0-9a-f]{64}", value["sha256"]):
                raise SystemExit(f"{logical_id}: sha256 is not a lowercase SHA-256 digest")


def register(path, kotlin=KOTLIN):
    incoming = json.loads(Path(path).read_text())
    source = Path(kotlin).read_text()
    merged = merge_specialists(current_catalog(source), incoming)
    validate(merged)

    compact = json.dumps(merged, separators=(",", ":"))
    if '"""' in compact or "$" in compact:
        raise SystemExit("catalog contains characters that cannot go into a Kotlin raw string")

    head, rest = source.split(BEGIN, 1)
    _, tail = rest.split(END, 1)
    Path(kotlin).write_text(
        f'{head}{BEGIN}    const val RELEASED: String = """{compact}"""\n{END}{tail}'
    )
    return merged


def main(path):
    merged = register(path)
    print(
        f"registered {len(merged['specialists'])} specialists in "
        f"{KOTLIN.relative_to(KOTLIN.parents[8])}"
    )


if __name__ == "__main__":
    main(sys.argv[1])
