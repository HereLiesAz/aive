# Orchestration specialist training

Trains, gates and releases the nine local orchestration specialists (Memory Query Composer, Context
Packer, Agent Router, Tool Router, Handoff Composer, Escalation Gate, Completion Gate, Execution State
Summarizer, Verification Planner). The app runs without them: `GuardedModelBackedOrchestrationUtilities`
falls back to `DeterministicLocalOrchestrationUtilities` for any role with no released specialist, and
for any model answer that is malformed or less conservative than the baseline.

| File | Purpose |
|---|---|
| `build_kaggle_dataset.sh` | Builds the Kaggle dataset from the Kotlin contracts |
| `dataset-metadata.json` | Kaggle dataset identity (`hereliesaz/aive-orchestration-corpus`) |
| `notebooks/<role>.ipynb` | One Kaggle notebook per role: train, gate and publish that role's adapter |
| `notebooks/base.ipynb` | Exports and publishes the shared INT8 base every role's adapter runs on |
| `aive_orchestration_specialists.ipynb` | All roles in one run (multitask and/or adapters); the central training workflow runs the root copy |
| `make_notebook.py` | Source of every notebook here and in `tools/memory_training/notebooks`; edit this, then regenerate |
| `register_catalog.py` | Registers a released `catalog.json` in the app |

## 1. Dataset

~~~
tools/orchestration_training/build_kaggle_dataset.sh
kaggle datasets create -p build/kaggle/aive-orchestration-corpus        # first time
kaggle datasets version -p build/kaggle/aive-orchestration-corpus -m "…" # updates
~~~

Rows come from `OrchestrationDatasetGenerator` (`shared/src/desktopTest/…/orchestration/`):

- `input` is byte-identical to the JSON the runtime guard sends a specialist. A test replays every row
  through the guard to prove it.
- `expected` is the deterministic baseline's answer.
- `split` is `train` / `validation` / `test` (70/15/15, no duplicate inputs across splits) or
  `adversarial` (hand-written edge cases).

Each `<role>.config.json` carries the specialist ID, foundation model, the system prompt from
`OrchestrationSpecialistPrompts`, and the release gates. `manifest.json` records the source commit and
SHA-256 of every file; the notebook refuses a dataset that does not match it.

The labels teach the exact wire schema and the conservative baseline policy. They are a distillation
seed, not ground truth beyond the baseline. `AIVE_ORCHESTRATION_DATASET_ROWS` changes the regular rows per
role (default 2000).

## 2. Train on Kaggle

### One notebook per role

Each role trains, gates and ships on its own, so one role can be retrained without touching the others:

1. Run `notebooks/base.ipynb` once with `UPLOAD = True`. It needs no GPU and no training: it exports
   the base with every LoRA weight as a graph input (see **Adapters** below), quantizes it, and
   publishes `aive-orchestration-base-int8.tar.gz` plus `base.json` to the `orchestration-base-v4`
   pre-release.
2. Run `notebooks/<role>.ipynb` on a GPU. It trains that role's adapter (at least
   `ADAPTER_MIN_STEPS`), gates it in PyTorch, downloads the published base and verifies its SHA-256,
   gates the adapter on it, and with `UPLOAD = True` publishes the adapter and a one-role
   `catalog.json` to `orchestration-<role>-v4`. A role that fails uploads nothing.
3. Register each role's `catalog.json` (section 3); the roles registered before stay.

A changed base needs a new version, and every adapter must be retrained against it.

Each upload is mirrored to Kaggle as a new version of the `hereliesaz/aive-orchestration-specialists`
model (variation `base` or the role's name; created private), unless `KAGGLE_MIRROR = False`; it needs a
`KAGGLE_KEY` secret (a Kaggle API key, or a token from Kaggle > Settings > API). A role notebook on Kaggle with that model's `base`
variation attached uses it instead of downloading the base from GitHub, after the same SHA-256 check.

### All roles in one notebook

Open `aive_orchestration_specialists.ipynb` on Kaggle, use a GPU accelerator with internet on, and
run all cells. Attaching the Kaggle dataset is optional: without it, the notebook downloads
`aive-orchestration-corpus.zip` (committed next to it, rebuilt by `build_kaggle_dataset.sh`) from GitHub. `MODE` in the first config cell chooses what it builds:

| `MODE` | Builds | Download | Updating one role |
|---|---|---|---|
| `"multitask"` | one merged model for all nine roles | ~640 MB, once | retrain the shared model |
| `"adapters"` | one shared base + one LoRA file per role | base ~640 MB + ~18 MB per role | retrain that role's adapter |
| `"both"` (default) | both; the app picks at runtime | | |

Every row carries its role's system prompt, which is how a multi-task model knows which contract it is
answering.

**Multitask:**

1. trains one LoRA adapter on Qwen2.5-0.5B-Instruct over every role's rows (loss on the answer only);
2. gates the adapter **per role** on that role's test and adversarial splits (`json_exact`, thresholds
   from the role's config);
3. merges, exports ONNX (fp32 graph), then quantizes weights only to INT8 with MatMulNBits.
   Activations and logits stay float32, which the app's ONNX Runtime loop expects. Dynamic INT8
   (quantized activations) is not used: it broke these models outright in testing;
4. gates the **exported INT8 model** per role on CPU with ONNX Runtime;
5. packages `aive-orchestration-utilities-int8.tar.gz` (`model.onnx`, `tokenizer.json`, configs,
   `model-manifest.json` with per-role scores).

**Adapters:**

1. trains one LoRA adapter per role on that role's rows (at least `ADAPTER_MIN_STEPS` optimizer steps;
   two epochs over one role were too few), and gates each in PyTorch;
2. exports the base **once** with the LoRA branches left in, and turns every LoRA weight into a graph
   input (found by fingerprint, since the exporter renames and transposes them). Feeding a role's
   weights reproduces that role's merged model; feeding zeros reproduces the base. The base is then
   weight-only INT8, as above: `aive-orchestration-base-int8.tar.gz`;
3. writes each role's weights as fp16 safetensors keyed by graph input name
   (`aive-orchestration-<role>-lora-v1.safetensors`, metadata `format=aive-lora-inputs`);
4. gates each role on the INT8 base fed with its fp16 file, i.e. exactly what ships.

Either way, `catalog.json` lists for each role only the shapes that passed both gates (`mergedVariants`,
`sharedBaseVariants` + `adapter`). A role that fails is left out; the others still ship. Progress is
kept in `state.json`, so a restarted session resumes where it stopped.

To publish, add a Kaggle secret `GITHUB_TOKEN` with contents write on `HereLiesAz/aive`, set
`UPLOAD = True`, and run the last cell. It uploads the archives, adapter files and `catalog.json` to the
`orchestration-utilities-v3` pre-release. Release assets are immutable: reruns may reuse an identical existing asset, but a changed same-name asset requires a new release tag. No v3 run has passed its gates yet; the shipped Agent Router/Handoff Composer bundle remains pinned to v1.

## 3. Register the release

~~~
python3 tools/orchestration_training/register_catalog.py catalog.json
~~~

This writes the released specialists into `OrchestrationSpecialistCatalog.RELEASED`. Commit that change.
Android and desktop install released artifacts explicitly from **Settings → Local Orchestration**
(SHA-256 verified; nothing downloads during inference) and use them once any is installed. Both
support merged INT8 models and the shared INT8 base + fp16 adapter shape, and prefer the adapter shape
when the catalog offers both. The web keeps the deterministic utility family.