"""Merge a released memory-clerk catalog into the app's MemoryClerkCatalog.

Usage: python3 tools/memory_training/register_catalog.py path/to/catalog.json

Same merge rules as the orchestration registration: new passing artifacts are preferred, previously
released roles stay registered.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "orchestration_training"))
import register_catalog as orchestration  # noqa: E402

KOTLIN = Path(__file__).resolve().parents[2] / (
    "shared/src/commonMain/kotlin/com/hereliesaz/geministrator/memory/MemoryClerkCatalog.kt"
)


def main(path):
    merged = orchestration.register(path, KOTLIN)
    for entry in merged["specialists"]:
        if not entry["specialistId"].startswith("memory:"):
            raise SystemExit(f"{entry['specialistId']} is not a memory clerk")
    print(f"registered {len(merged['specialists'])} memory clerks in {KOTLIN.relative_to(KOTLIN.parents[8])}")


if __name__ == "__main__":
    main(sys.argv[1])
