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
| `notebooks/all.ipynb` | Every clerk in one session: train, gate and publish each clerk's adapter |
| `notebooks/<role>.ipynb` | The same notebook set to one clerk |
| `notebooks/base.ipynb` | Exports and publishes the shared INT8 base every clerk's adapter runs on |
| `register_catalog.py` | Registers a released `catalog.json` in `MemoryClerkCatalog` |

The notebooks are generated with the orchestration ones by `tools/orchestration_training/make_notebook.py`
(same training, gating and export code); edit that file and regenerate, never a notebook.

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
generator restates recurring facts (two per third session) and condenses clusters from three
members instead of the runtime's eight or more; a packet looks the same either way. Adversarial
condensation material restates facts with negation, quoted strings and several numbers, beside
restatements that change a value, which never reach the clerk. The Category Classifier's
instruction carries the taxonomy it is labelled with (`memoryCategoryGuide`). Every role must have
rows in every split; the generator test fails otherwise. `AIVE_MEMORY_DATASET_SESSIONS` changes the
number of sessions (default 1500).

## 2. Train on Kaggle

Each clerk is its own LoRA adapter on one shared base, so a clerk trains, gates and ships on its own,
and a failing one costs only its own rerun.

`notebooks/all.ipynb` trains every clerk in one session and `notebooks/<clerk>.ipynb` one clerk; both
are the same code (`tools/orchestration_training/make_notebook.py`) and publish each clerk the same
way. They share one work folder (`/kaggle/working/aive-memory`), so in one session each resumes from
what the others left. A clerk whose `memory-<clerk>-v1` release already exists is reused, not
retrained (`REUSE_RELEASED`).

1. Run `notebooks/base.ipynb` once with internet on and `UPLOAD = True`. It needs no GPU and no
   training: it exports Qwen2.5-0.5B-Instruct with every LoRA weight as a graph input, quantizes the
   weights only to INT8 (MatMulNBits; LoRA inputs stay float32), and publishes
   `aive-memory-base-int8.tar.gz` plus `base.json` to the `memory-base-v1` pre-release.
2. Run `notebooks/all.ipynb` or `notebooks/<clerk>.ipynb` on a GPU with internet on. Per clerk, it trains the adapter (prompt +
   answer up to 3072 tokens, a budget of `ADAPTER_MIN_STEPS` optimizer steps, stopping at the first
   epoch whose validation loss improves less than 5% and keeping the best epoch), gates it on the clerk's
   test and adversarial splits (`json_exact`, thresholds from the role's config), downloads the
   published base and verifies its SHA-256, and gates the adapter on it on CPU, exactly as it ships (100 sampled test rows plus every adversarial row;
   `ONNX_GATE_TEST_ROWS = None` scores them all).
   With `UPLOAD = True` it publishes `aive-memory-<clerk>-lora-v1.safetensors` and a one-clerk
   `catalog.json` to `memory-<clerk>-v1`. A clerk that fails uploads nothing. The `catalog.json` left in
   the assets folder lists every released clerk, reused ones included.

With `UPLOAD = True` each notebook also publishes the same files to Kaggle as a new version of the
`hereliesaz/aive-memory-clerks` model (variation `base` or the clerk's name; created private), unless
`KAGGLE_MIRROR = False`. On Kaggle, attach that model's `base` variation to a clerk notebook and it
uses that copy instead of downloading 620 MB from GitHub; the SHA-256 check is the same. Rerunning
`base.ipynb` after the base is published reuses it rather than exporting a new one, so it can
mirror an existing release to Kaggle.

Uploading needs a `GITHUB_TOKEN` (Kaggle or Colab secret, or environment variable) with contents write on `HereLiesAz/aive`, and a `KAGGLE_KEY` secret (a Kaggle API key, or a token from Kaggle > Settings > API) for the Kaggle copy. Release
assets are immutable: a changed adapter needs a new version, and a changed base needs a new version
and every adapter retrained against it.

## 3. Register the release

~~~
python3 tools/memory_training/register_catalog.py catalog.json
~~~

Run it on the combined `catalog.json`, or once per clerk's. It writes the clerk into `MemoryClerkCatalog.RELEASED` and keeps
the clerks registered before; commit that change. Android and desktop then list those stages on the
Memory screen for install. Installing a clerk downloads the shared base once (~640 MB) and the clerk's
adapter (~18 MB); the base is removed with the last clerk that uses it. A stage set to its local model
runs on the base with that clerk's weights fed as graph inputs.
