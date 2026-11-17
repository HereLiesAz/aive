#!/usr/bin/env bash
# Builds the app-integration adapter corpus: a thin wrapper around the one Gradle invocation the Kaggle
# notebook also uses (see README.md). Usage: build_dataset.sh [output-dir]
# AIVE_APP_INTEGRATION_ROWS sets the regular rows per family (default 1500).
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
out="${1:-$root/build/app-integration-corpus}"

"$root/gradlew" -p "$root" --no-daemon --console=plain :shared:desktopTest \
  --tests 'com.hereliesaz.geministrator.adapter.AppIntegrationDatasetWriterTest' \
  -Daive.appIntegration.outDir="$out" \
  -Daive.appIntegration.commit="$(git -C "$root" rev-parse HEAD)"

python3 "$out/score.py" --corpus "$out" --corpus-checks --strict
python3 "$out/score.py" --corpus "$out" --self-check --strict >/dev/null
echo "corpus: $out"
