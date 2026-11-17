"""Merge a newly released orchestration catalog into the app catalog.

Usage: python3 tools/orchestration_training/register_catalog.py path/to/catalog.json

New passing artifacts are preferred, while previously released roles and compatible older variants
remain registered as fallbacks. Scores stay in the release catalog and model manifests.

The one other id accepted is `app-integration:assistant` (tools/app_integration_training): it is not an
orchestration specialist, so it is written to the constant's own `appIntegration` field, with its scores
(the app shows the gate line) and the adapter's size, and a newer release replaces the older one whole
(an adapter only fits the base it was trained on). Any other id outside `orchestration:*` is refused.
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
    "sizeBytes",
)
APP_INTEGRATION_ID = "app-integration:assistant"
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


def app_integration(value):
    """The catalog's `appIntegration` field from a release catalog's app-integration specialist entry."""
    adapter = value.get("adapter")
    bases = [artifact(item) for item in value.get("sharedBaseVariants", [])]
    if not adapter or not bases:
        raise SystemExit(f"{APP_INTEGRATION_ID} needs an adapter and at least one shared base")
    if adapter.get("kind") != "Adapter" or any(item.get("kind") != "SharedBase" for item in bases):
        raise SystemExit(f"{APP_INTEGRATION_ID}: expected a shared base and an Adapter artifact")
    entry = {"specialistId": APP_INTEGRATION_ID, "sharedBaseVariants": bases, "adapter": artifact(adapter)}
    if value.get("scores"):
        entry["scores"] = value["scores"]
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
    entry = catalog.get("appIntegration")
    if entry:
        seen = set()
        for value in entry["sharedBaseVariants"] + [entry["adapter"]]:
            if value["logicalArtifactId"] in seen:
                raise SystemExit(f"{APP_INTEGRATION_ID}: duplicate artifact {value['logicalArtifactId']}")
            seen.add(value["logicalArtifactId"])
            if not re.fullmatch(r"[0-9a-f]{64}", value["sha256"]):
                raise SystemExit(f"{value['logicalArtifactId']}: sha256 is not a lowercase SHA-256 digest")
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
    # OrchestrationSpecialistCatalog.parse() rejects any id outside orchestration:* in `specialists`, which would make
    # the whole released catalog unreadable in the app. The app-integration adapter has its own field; everything
    # else is refused here, before anything is written.
    ids = [e["specialistId"] for e in incoming.get("specialists", [])]
    foreign = [i for i in ids if not i.startswith("orchestration:") and i != APP_INTEGRATION_ID]
    if foreign:
        raise SystemExit(
            f"refusing to register {foreign}: the app's catalog only accepts orchestration:* specialists "
            f"and {APP_INTEGRATION_ID}. Nothing was written."
        )
    if ids.count(APP_INTEGRATION_ID) > 1:
        raise SystemExit(f"refusing to register {APP_INTEGRATION_ID} more than once. Nothing was written.")
    app_entries = [e for e in incoming.get("specialists", []) if e["specialistId"] == APP_INTEGRATION_ID]
    orchestration = {"specialists": [e for e in incoming.get("specialists", []) if e["specialistId"] != APP_INTEGRATION_ID]}
    source = Path(kotlin).read_text()
    existing = current_catalog(source)
    merged = merge_specialists(existing, orchestration)
    app = app_integration(app_entries[0]) if app_entries else existing.get("appIntegration")
    if app:
        merged["appIntegration"] = app
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
    adapter = " and the app-integration adapter" if merged.get("appIntegration") else ""
    print(
        f"registered {len(merged['specialists'])} specialists{adapter} in "
        f"{KOTLIN.relative_to(KOTLIN.parents[8])}"
    )


if __name__ == "__main__":
    main(sys.argv[1])
