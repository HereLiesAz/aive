#!/usr/bin/env bash
# Builds the Kaggle dataset for the local orchestration specialists.
#
# Output: build/kaggle/aive-orchestration-corpus/ (upload with `kaggle datasets create -p <dir>`, or
# `kaggle datasets version -p <dir> -m "<note>"` for an update) and a zip of the same directory.
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
out="$root/build/kaggle/aive-orchestration-corpus"
rows="${AIVE_ORCHESTRATION_DATASET_ROWS:-2000}"

rm -rf "$out"
mkdir -p "$out"

AIVE_EXPORT_ORCHESTRATION_DATASETS="$out" AIVE_ORCHESTRATION_DATASET_ROWS="$rows" \
  "$root/gradlew" -p "$root" :shared:desktopTest --tests '*OrchestrationDatasetGeneratorTest' --rerun -q

cp "$root/tools/orchestration_training/dataset-metadata.json" "$out/dataset-metadata.json"

python3 - "$out" "$rows" "$(git -C "$root" rev-parse HEAD)" <<'PY'
import collections, hashlib, json, pathlib, sys

out, rows, commit = pathlib.Path(sys.argv[1]), int(sys.argv[2]), sys.argv[3]
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
    "generator": "shared/src/desktopTest/.../orchestration/OrchestrationDatasetGenerator.kt",
    "source_commit": commit,
    "regular_rows_per_role": rows,
    "roles": roles,
}
(out / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
print(f"{len(roles)} roles written to {out}")
PY

(cd "$out/.." && rm -f aive-orchestration-corpus.zip && zip -qr aive-orchestration-corpus.zip aive-orchestration-corpus)
cp "$out/../aive-orchestration-corpus.zip" "$root/tools/orchestration_training/aive-orchestration-corpus.zip"
echo "zip: $out/../aive-orchestration-corpus.zip (copied next to the notebook; commit it)"
