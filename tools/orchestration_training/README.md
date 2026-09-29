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

Open `aive_orchestration_specialists.ipynb` on Kaggle, add the dataset, use a GPU accelerator with
internet on, and run all cells. It trains **one multi-task model** for all nine roles; each row carries
its role's system prompt, which is how the model knows which contract it is answering. Then it:

1. trains one LoRA adapter on Qwen2.5-0.5B-Instruct over every role's rows (loss on the answer only);
2. gates the adapter **per role** on that role's test and adversarial splits (`json_exact`, thresholds
   from the role's config);
3. merges, exports ONNX (fp32 graph), then quantizes weights only to INT8 with MatMulNBits.
   Activations and logits stay float32, which the app's ONNX Runtime loop expects. Dynamic INT8
   (quantized activations) is not used: it broke these models outright in testing;
4. gates the **exported INT8 model** per role on CPU with ONNX Runtime;
5. packages one `aive-orchestration-utilities-int8.tar.gz` (`model.onnx`, `tokenizer.json`, configs,
   `model-manifest.json` with per-role scores) and writes `catalog.json`, in which every role that
   passed both gates points at that one artifact.

A role that fails either gate is left out of the catalog; the others still ship. One download (about
640 MB compressed) serves every released role, and the app loads one ONNX session for all of them.
Updating one role means retraining the shared model. Progress is kept in `state.json`, so a restarted
session resumes where it stopped.

To publish, add a Kaggle secret `GITHUB_TOKEN` with contents write on `HereLiesAz/aive`, set
`UPLOAD = True`, and run the last cell. It uploads the archive and `catalog.json` to the
`orchestration-utilities-v1` pre-release.

## 3. Register the release

~~~
python3 tools/orchestration_training/register_catalog.py catalog.json
~~~

This writes the released specialists into `OrchestrationSpecialistCatalog.RELEASED`. Commit that change.
The catalog only lists artifacts; the app uses them once a platform `LocalOrchestrationModelExecutor` is
wired in (see `TODO.md`).
