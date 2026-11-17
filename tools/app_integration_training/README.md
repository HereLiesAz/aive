# App-integration adapter training

One LoRA adapter on Qwen2.5-0.5B-Instruct that teaches the model to work inside The Aive. The corpus
(`train/validation/test/adversarial.jsonl`, `manifest.json`, `score.py`) and its generator are described in
the corpus sections of this folder's documentation; this file covers the notebook.

| File | Purpose |
|---|---|
| `make_notebook.py` | Source of the notebook; edit this, then regenerate |
| `app_integration_adapter.ipynb` | Generated: build the corpus, train, gate, export, release |
| `test_make_notebook.py` | Fails when the committed notebook is not the generator's output, is not valid nbformat, has an unparsable cell or holds a secret; also checks the corpus zip is deterministic |
| `dataset-metadata.json` | Identity of the optional Kaggle dataset copy (`hereliesaz/aive-app-integration-corpus`) |

## Kaggle

Nothing was run on Kaggle or a GPU when this was written; the notebook was only generated and statically
checked.

### Before the first run

1. Add a `GITHUB_TOKEN` secret (contents write on `HereLiesAz/aive`) under Add-ons, Secrets, with notebook
   access on. Only needed for `UPLOAD = True`.
2. Check that the shared ONNX base `orchestration-base-v4` is published. If it is not, run
   `tools/orchestration_training/notebooks/base.ipynb` once with `UPLOAD = True`.

There is no corpus to build or upload by hand: the notebook builds it (stage 1 below).

### Run

Open `app_integration_adapter.ipynb` on Kaggle with a GPU (T4 or P100) and **Internet on**.

1. Set `SMOKE = True` and run all cells. The corpus is built with 20 regular rows
   (`AIVE_APP_INTEGRATION_ROWS`), then it trains 20 steps on 64 rows and runs every later cell on tiny splits
   (generation capped at 96 new tokens), so a broken pipeline shows up quickly. Gates are expected to fail on
   a 20-step adapter; the smoke run releases nothing and writes to its own work folder.
2. Set `SMOKE = False` and run all cells. Set `UPLOAD = True` to publish.

What the cells do:

1. **Corpus build** (`BUILD_CORPUS_ON_KAGGLE = True`, the default; no GPU is needed for this stage):
   - clones `https://github.com/HereLiesAz/aive` at `SOURCE_REF` (default `main`, shallow) and prints and
     records the resolved full commit SHA in `results.json` and in `build-info.json` next to the corpus;
   - makes sure a JDK 21 is available: the installed one, else `apt-get install openjdk-21-jdk-headless`,
     else a Temurin 21 tarball from `api.adoptium.net` into the toolchain folder. It stops if none works;
   - runs the generator through the repo's Gradle wrapper (`./gradlew --no-daemon --console=plain
     :shared:desktopTest --tests '*AppIntegrationDatasetWriterTest' -Daive.appIntegration.outDir=<dir>`; the
     invocation is the single constant `GENERATOR_GRADLE_ARGS`), with `GRADLE_USER_HOME` and the build
     cache under the toolchain folder (`/kaggle/working/aive-app-integration-toolchain`) so a rerun resumes
     without downloading again. It prints a progress line every minute, times out after 2.5 hours and, on
     failure, prints the last 200 log lines and stops. It never falls back to another corpus after a failed
     build;
   - verifies every produced file against the produced `manifest.json` (SHA-256), builds
     `aive-app-integration-corpus.zip` deterministically (sorted entries, fixed timestamps), prints its size
     and SHA-256, and leaves it in `/kaggle/working` as a notebook output. Upload or commit it only if you
     want a copy; nothing needs it.

   With `BUILD_CORPUS_ON_KAGGLE = False` it uses an attached dataset `hereliesaz/aive-app-integration-corpus`,
   else the zip committed in the repository.
2. **Corpus check**: refuses a manifest mismatch and prints rows per split and family. It imports `score.py`
   (from the cloned commit) and scores the corpus's own `expected` answers first, which must give 1.0 in
   every family, so a scoring or corpus mistake stops the run before any training.
3. **Prompt**: `render_prompt` is `QwenChatPrompt.format` from `OnDeviceTextRuntime.kt`, character for
   character. The notebook asserts the tokenizer's own chat template gives the same text for every row.
4. **Training**: LoRA r=16, alpha=32, dropout 0.05 on all attention and MLP projections (the orchestration
   notebook's shape, so the ONNX exporter's base fits), loss on the answer and its `<|im_end|>` only, fp16,
   cosine schedule, seed 8. After each epoch a fixed validation sample is generated and scored by
   `score.py`; the best epoch (worst family first, mean as tie-break) is kept on disk, and training stops
   after two epochs without improvement. Progress and checkpoints survive a restart (`state.json`).
   Rows longer than 1,280 tokens (the installed model's KV cache) are skipped and counted; more than 2%
   stops the run.
5. **Gate 1**: greedy generation, 512 new tokens, on test and adversarial through the PEFT model.
   `score.py` gives per-family scores and the gate verdict. A failure raises, and `results.json` holds every
   failing row id, its expected text and the prediction.
6. **Export A (ONNX Runtime)**: downloads and verifies the published shared INT8 base and writes this
   adapter as fp16 safetensors keyed by the base's LoRA graph inputs (~18 MB). **Gate 2** re-scores that
   exact pair on CPU with onnxruntime over the same splits, requires the same gates, and at most a 0.03 drop
   per family against Gate 1 on the same rows. A full CPU pass over the test split is slow; set
   `ONNX_GATE_TEST_ROWS` to sample it.
7. **Export B (LiteRT-LM)**, behind `EXPORT_LITERTLM = True`: merges the adapter into the base
   (`merge_and_unload()`), saves safetensors and converts with `litert-torch export_hf` (q8 recipe, 1,280-token
   cache, multi-prefill) in its own virtual environment. It records size and SHA-256, then tries to run 50 test
   rows through the file with the `litert_lm` Python package and reports per-family score against Gate 1 on the
   same rows. If the package cannot be installed or its API differs, `results.json` says so.
8. **Release**: with `UPLOAD = True` and the secret, publishes the pre-release `app-integration-v1`: the ONNX
   adapter, the LiteRT-LM file when it is under GitHub's 2 GiB asset limit (otherwise a message says it was
   not uploaded), `catalog.json` and `results.json` (gates per family, split sizes, source commit, pip freeze).
   Release assets are immutable: a changed artifact needs a new tag.

**Kaggle caveats for the build stage.** The first run downloads Gradle, the Kotlin toolchain and the
project's dependencies (several GB, possibly tens of minutes); a rerun in the same session reuses them from
`/kaggle/working`. Internet must be on. If the session is not kept, they are downloaded again.

**Two runtimes, two shapes.** ONNX Runtime gets a separate adapter on the shared base. LiteRT-LM gets a
merged full model: Android's `LoraConfig(loraPath)` exists in `SessionConfig`, but no converter for a separate
LiteRT-LM LoRA file is documented, so none is attempted.

### After the run

~~~
curl -LO https://github.com/HereLiesAz/aive/releases/download/app-integration-v1/catalog.json
python3 tools/orchestration_training/register_catalog.py catalog.json
~~~

`register_catalog.py` currently refuses this catalog: the app's orchestration catalog accepts only
`orchestration:*` specialists, and registering `app-integration:assistant` there would make the whole released
catalog unreadable. Nothing in the app consumes an `app-integration` adapter yet; that wiring is not part of
this notebook. The LiteRT-LM file is pinned in `LiteRtLmModelCatalog` by hand (size and SHA-256 are in
`results.json`) after it has been tried on a device.

### What was not verified

- The generator's Gradle invocation (test class `*AppIntegrationDatasetWriterTest`, property
  `-Daive.appIntegration.outDir`, row count variable `AIVE_APP_INTEGRATION_ROWS`): the Kotlin writer did not
  exist yet when this was written. Fix the `GENERATOR_*` constants if the real names differ. The stage was
  exercised only against a fake `gradlew` in a local git repository.
- Whether Kaggle's image lets `apt-get` install a JDK (it needs root), and how long the first Gradle run takes.
- That `:shared:desktopTest` builds in a plain clone without optional vendored sources (`vendor/*`, which CI
  clones separately).
- The `litert-torch export_hf` flag names, the q8 recipe name and the prefill lengths are constants written
  from memory; the notebook checks `--help` and stops with the help text when a flag is absent.
- The `litert_lm` Python package's name and session API.
- Whether a T4's memory holds batch 2 at 1,280 tokens (batch and accumulation are constants).
- How `score.py` takes predictions and what `gates()` receives. The notebook probes an id-to-text map or a
  list, and `gates({"test": ..., "adversarial": ...})` or `gates(test, adversarial)`, on the corpus's own
  answers before training. Per-family values may be numbers or objects with a `score` field.
- That a model passing these gates is better than the unadapted model inside the app.
