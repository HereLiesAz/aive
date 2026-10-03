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
| `notebooks/<role>.ipynb` | The all-roles notebook set to one role: train, gate and publish its adapter |
| `notebooks/base.ipynb` | Exports and publishes the shared INT8 base every role's adapter runs on |
| `aive_orchestration_specialists.ipynb` | All roles in one run (multitask and/or adapters on the shared base); the central training workflow runs the root copy |
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

Every notebook here is the same code. `aive_orchestration_specialists.ipynb` covers all roles;
`notebooks/<role>.ipynb` is that notebook with `ROLES` set to one role; `notebooks/base.ipynb` swaps in
the run cell that publishes the shared base. They share one work folder
(`/kaggle/working/aive-orchestration`) and one `state.json`, so within a session each picks up what the
others left, and every adapter, whichever notebook trained it, runs on the one published base.

1. **Base, once:** run `notebooks/base.ipynb` with `UPLOAD = True`. It needs no GPU and no training: it
   exports the base with every LoRA weight as a graph input (see **Adapters** below), quantizes it, and
   publishes `aive-orchestration-base-int8.tar.gz` plus `base.json` to the `orchestration-base-v4`
   pre-release. A rerun mirrors the published base rather than exporting a new one.
2. **Train:** on a GPU with internet on, run the all-roles notebook or a role's notebook. Attaching the
   Kaggle dataset is optional: without it, the notebook downloads `aive-orchestration-corpus.zip`
   (committed next to it, rebuilt by `build_kaggle_dataset.sh`). `MODE` chooses what it builds:

   | `MODE` | Builds | Download | Updating one role |
   |---|---|---|---|
   | `"multitask"` (all-roles default) | one merged model for all nine roles | ~640 MB, once | retrain the shared model |
   | `"adapters"` (role notebooks) | one LoRA file per role on the shared base | base ~640 MB + ~18 MB per role | retrain that role's adapter |
   | `"both"` | both; the app picks at run time | | |

3. **Register** `catalog.json` (section 3).

**Multitask:**

1. trains one LoRA adapter on Qwen2.5-0.5B-Instruct over every role's rows (loss on the answer only;
   every row carries its role's system prompt, which is how one model knows which contract it is
   answering). Every run, multitask or adapter, stops at the first epoch whose validation loss improves
   on the best by less than `PLATEAU_MIN_IMPROVEMENT` (5%) and keeps the best epoch's weights;
2. gates the adapter **per role** on that role's test and adversarial splits (`json_exact`, thresholds
   from the role's config);
3. merges, exports ONNX (fp32 graph), then quantizes weights only to INT8 with MatMulNBits.
   Activations and logits stay float32, which the app's ONNX Runtime loop expects. Dynamic INT8
   (quantized activations) is not used: it broke these models outright in testing;
4. gates the **exported INT8 model** per role on CPU with ONNX Runtime, on a fixed sample of
   `ONNX_GATE_TEST_ROWS` (100) test rows plus every adversarial row; the adapter gate already scored
   every row on the GPU, and a full split on CPU takes hours per role (`None` scores every row);
5. packages `aive-orchestration-utilities-int8.tar.gz` (`model.onnx`, `tokenizer.json`, configs,
   `model-manifest.json` with per-role scores) for the `orchestration-utilities-v3` pre-release.

**Adapters**, per role:

1. reuses the role's release when `orchestration-<role>-v4` already exists (`REUSE_RELEASED`);
2. otherwise trains one LoRA adapter on that role's rows (an epoch budget of `ADAPTER_MIN_STEPS`, 450,
   optimizer steps, cut short on a validation plateau; two epochs over one role were too few) and
   gates it in PyTorch;
3. downloads the published base (or uses the attached `hereliesaz/aive-orchestration-specialists`
   model's `base` variation) and checks its SHA-256. The base was exported **once** with the LoRA
   branches left in and every LoRA weight turned into a graph input (found by fingerprint, since the
   exporter renames and transposes them): feeding a role's weights reproduces that role's merged
   model, feeding zeros reproduces the base;
4. writes the role's weights as fp16 safetensors keyed by graph input name
   (`aive-orchestration-<role>-lora-v4.safetensors`, metadata `format=aive-lora-inputs`) and gates
   them on the INT8 base on CPU, exactly what ships. A gate recorded against any other base is rerun.

`catalog.json` lists every role that passed in every shape (`mergedVariants`, `sharedBaseVariants` +
`adapter`), including released roles that were reused; register it. A role that fails is left out and
the others still ship. Progress is kept in `state.json`, so a restarted session resumes where it
stopped; work left in the per-role folders of earlier notebook versions
(`aive-orchestration-<role>-v4`) is folded in. A batch run (Save Version) clears its work folder after
writing the compact release output; an interactive session keeps it.

To publish, add a `GITHUB_TOKEN` secret with contents write on `HereLiesAz/aive` and set
`UPLOAD = True`. The merged model and the combined `catalog.json` go to `orchestration-utilities-v3`;
each newly passing adapter goes to its own `orchestration-<role>-v4` with a one-role `catalog.json`,
and, unless `KAGGLE_MIRROR = False`, to the `hereliesaz/aive-orchestration-specialists` Kaggle model as
variation `<role>` (needs a `KAGGLE_KEY` secret: a Kaggle API key, or a token from Kaggle > Settings >
API). Release assets are immutable: a rerun may reuse an identical asset, but a changed one needs a new
version. A changed base needs a new version, and every adapter must be retrained against it. No v3
multitask run has passed its gates yet; the shipped Agent Router/Handoff Composer bundle remains pinned
to v1.

## 3. Register the release

~~~
python3 tools/orchestration_training/register_catalog.py catalog.json
~~~

This writes the released specialists into `OrchestrationSpecialistCatalog.RELEASED`. Commit that change.
Android and desktop install released artifacts explicitly from **Settings → Local Orchestration**
(SHA-256 verified; nothing downloads during inference) and use them once any is installed. Both
support merged INT8 models and the shared INT8 base + fp16 adapter shape, and prefer the adapter shape
when the catalog offers both. The web keeps the deterministic utility family.