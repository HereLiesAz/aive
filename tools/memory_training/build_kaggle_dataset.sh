#!/usr/bin/env bash
# Builds the Kaggle dataset for the local memory clerks.
#
# Output: build/kaggle/aive-memory-corpus/ (upload with `kaggle datasets create -p <dir>`, or
# `kaggle datasets version -p <dir> -m "<note>"` for an update) and a zip of the same directory,
# copied next to the notebook.
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
out="$root/build/kaggle/aive-memory-corpus"
sessions="${AIVE_MEMORY_DATASET_SESSIONS:-1500}"

rm -rf "$out"
mkdir -p "$out"

AIVE_EXPORT_MEMORY_DATASETS="$out" AIVE_MEMORY_DATASET_SESSIONS="$sessions" \
  "$root/gradlew" -p "$root" :shared:desktopTest --tests '*MemoryDatasetGeneratorTest' --rerun -q

cp "$root/tools/memory_training/dataset-metadata.json" "$out/dataset-metadata.json"

python3 - "$out" "$sessions" "$(git -C "$root" rev-parse HEAD)" <<'PY'
import collections, hashlib, json, pathlib, sys

out, sessions, commit = pathlib.Path(sys.argv[1]), int(sys.argv[2]), sys.argv[3]
roles = {}
for corpus in sorted(out.glob("*.jsonl")):
    slug = corpus.stem
    splits = collections.Counter(json.loads(line)["split"] for line in corpus.open())
    config = out / f"{slug}.config.json"
    roles[slug] = {
        "specialist_id": json.loads(config.read_text())["specialist_id"],
        "corpus": corpus.name,
        "corpus_sha256": hashlib.sha256(corpus.read_bytes()).hexdigest(),
        "config": config.name,
        "config_sha256": hashlib.sha256(config.read_bytes()).hexdigest(),
        "splits": dict(splits),
    }
manifest = {
    "schema": 1,
    "generator": "shared/src/desktopTest/.../memory/MemoryDatasetGenerator.kt",
    "source_commit": commit,
    "sessions": sessions,
    "roles": roles,
}
(out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
print(f"{len(roles)} roles written to {out}")
for slug, entry in roles.items():
    print(f"  {slug}: {entry['splits']}")
PY

(cd "$out/.." && rm -f aive-memory-corpus.zip && zip -qr aive-memory-corpus.zip aive-memory-corpus)
cp "$out/../aive-memory-corpus.zip" "$root/tools/memory_training/aive-memory-corpus.zip"
echo "zip: $out/../aive-memory-corpus.zip (copied next to the notebook; commit it)"
