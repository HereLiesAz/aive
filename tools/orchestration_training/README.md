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
| `aive_orchestration_specialists.ipynb` | Kaggle notebook: train, gate, export, package, upload |
| `make_notebook.py` | Source of the notebook; edit this, then regenerate |
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

1. trains one LoRA adapter per role on that role's rows, and gates each in PyTorch;
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
`orchestration-utilities-v2` pre-release. Release assets are immutable: reruns may reuse an identical existing asset, but a changed same-name asset requires a new release tag. The currently shipped Agent Router/Handoff Composer bundle remains pinned to v1.

## 3. Register the release

~~~
python3 tools/orchestration_training/register_catalog.py catalog.json
~~~

This writes the released specialists into `OrchestrationSpecialistCatalog.RELEASED`. Commit that change.
On desktop, `AIVE_LOCAL_ORCHESTRATION_SPECIALISTS=1` turns the specialists on and
`AIVE_ORCHESTRATION_SPECIALIST_MODE=adapters` prefers base + adapter over the merged model (default
`merged`). Android installs released artifacts explicitly from Settings and supports both merged INT8
models and the shared INT8 base + fp16 adapter shape. Platforms without a local executor keep the
deterministic utility family.
