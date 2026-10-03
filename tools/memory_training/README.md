# Memory clerk training

Trains, gates and releases the eight generative memory clerks (Sectioner, Salience Filter, Noun Tagger,
Verb Tagger, Phrase Synthesizer, Summary Synthesizer, Category Classifier, Condensation Rewriter). The
Association Linker is an embedding model and stays on its epoch-8 release. Memory runs without any of
them: every stage defaults to the programmatic clerks, and a stage set to a local model that is not
released runs programmatically, with the reason shown on the Memory screen.

| File | Purpose |
|---|---|
| `build_kaggle_dataset.sh` | Builds the corpus from the running memory layer |
| `dataset-metadata.json` | Kaggle dataset identity (`hereliesaz/aive-memory-corpus`) |
| `aive-memory-corpus.zip` | The corpus, committed so the notebook can download it |
| `aive_memory_clerks.ipynb` | Kaggle notebook: train, gate, export, package, upload |
| `register_catalog.py` | Registers a released `catalog.json` in `MemoryClerkCatalog` |

The notebook is generated with the orchestration one by `tools/orchestration_training/make_notebook.py`
(same training, gating and export code); edit that file and regenerate, never the notebook.

## 1. Corpus

~~~
tools/memory_training/build_kaggle_dataset.sh
kaggle datasets create -p build/kaggle/aive-memory-corpus          # first time
kaggle datasets version -p build/kaggle/aive-memory-corpus -m "…"  # updates
~~~

Rows come from `MemoryDatasetGenerator` (`shared/src/desktopTest/…/memory/`). Synthetic sessions are
consolidated with the programmatic clerks behind recorders that present the local model's limits, so
each recorded packet is fitted exactly as the runtime fits it for a model:

- `input` is `MemoryMicroAgentPrompts.render` of the packet: the user turn. The config's
  `system_prompt` is `MemoryMicroAgentPrompts.system`. The app's local runtimes send both in the same
  Qwen chat template (`MemoryMicroAgentPrompts.chatPrompt`).
- `expected` is the programmatic clerk's answer in the sections/nodes/links contract. A row is kept only
  when that answer decodes through `StructuredMemoryMicroAgent` back to the clerk's own batch, and fits
  the model's output budget.
- `split` is by session (70/15/15), plus hand-written `adversarial` sessions (conflicting values,
  negation, chatter only).

Like the orchestration corpus, it is a distillation seed: it teaches the contract and the conservative
baseline, nothing beyond it. Condensation only happens when similar memories pile up, so the
Condensation Rewriter has few rows and no adversarial ones; expect it to fail its gates until the
generator produces more clusters. `AIVE_MEMORY_DATASET_SESSIONS` changes the number of sessions
(default 1500).

## 2. Train on Kaggle

Open `aive_memory_clerks.ipynb` on Kaggle, use a GPU accelerator with internet on, and run all cells.
It trains one multitask LoRA on Qwen2.5-0.5B-Instruct (prompt + answer up to 3072 tokens), gates every
role on its test and adversarial splits (`json_exact`, thresholds from the role's config), merges,
exports ONNX, quantizes weights only to INT8, gates the exported model per role on CPU, and packages
`aive-memory-clerks-int8.tar.gz` with a `catalog.json` listing only the passing roles. The memory
runtimes load merged models only, so the notebook runs `MODE = "multitask"`.

To publish, add a Kaggle secret `GITHUB_TOKEN` with contents write on `HereLiesAz/aive`, set
`UPLOAD = True`, and run the last cell: it uploads to the `memory-clerks-v1` pre-release. Release assets
are immutable; a changed archive needs a new tag.

## 3. Register the release

~~~
python3 tools/memory_training/register_catalog.py catalog.json
~~~

This writes the released clerks into `MemoryClerkCatalog.RELEASED`; commit that change. Android and
desktop then list those stages on the Memory screen for install, and a stage set to its local model
runs it.
