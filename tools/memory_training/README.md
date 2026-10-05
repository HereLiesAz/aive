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
- `tags` are `[stage, regular|adversarial, class:<class>]`. Classes: `writes` / `empty` (whether
  the label writes anything), `condense` / `keep-apart:values` / `keep-apart:contrast` for the
  Condensation Rewriter, and `chain:tree` / `chain:pair` / `chain:divergence` for the Summary
  Synthesizer's summary-chain rows (below).
- `copy` is the copy shortcut for the row: the label's shape with each text copied verbatim from
  its first source (null when the label writes nothing). The gates use it.

Labels follow today's engine contract. No label writes `Supersedes`, `ResolvesConflict` or
`ConflictsWith` (the generator test checks every row against `MODEL_WRITABLE_RELATIONS`): the
programmatic Condensation Rewriter writes `CondensedFrom` only, and the engine adds `Supersedes`
when it commits. Contrasting or value-changing clusters are never offered to the clerk, and the
condensed text is fitted to its sources' S-curve size budget by the engine (`fitCondensation`)
after the clerk answers, so neither appears in a condensation packet.

Two kinds of rows do not come from the packets the clerks receive in the synthetic sessions:

- **Keep-apart clusters.** Every condensation packet appears again with its last member changed to
  state another value (a number or quoted string) or to contrast (same frame, different filler;
  `MemoryContrast`). The engine never offers such a cluster, but a clerk shown one must decline
  (`{"sections":[],"nodes":[],"links":[]}`, or `DO_NOT_CONDENSE`). They sit in the source packet's
  split, so condense and keep-apart rows are balanced in training and in every gated split. Most
  condensation clusters in the synthetic sessions are short tags with nothing to contrast, so the
  1500-session corpus has `keep-apart:values` rows only (157 beside 157 `condense`). Identical
  members make the copy shortcut right on many `condense` rows; those rows are left out of the
  shortcut gate, and `keep-apart` is what tells a copying clerk apart.
- **Summary-chain requests.** The summary tree and pair summaries (`MemorySummaryChain.kt`) call the
  Summary Synthesizer clerk when it is installed, through one-off Summaries packets
  (`memorySummaryChainPacket`) that now state the character limit. The generator records every
  request the extractive engines answered (verbatim ones never reach a model) and labels it with
  that engine's summary, kept only when it passes the chain's own abstractive check
  (`MemorySummarizerChain.validate`). The same adapter serves both packet shapes, so these rows
  are part of `summary-synthesizer.jsonl`; there is no separate clerk or notebook.

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
what the others left. Each clerk is published (GitHub release, then Kaggle mirror) the moment it
passes, not at the end of the run, so a timeout or a later failure never loses it. A clerk whose
`memory-<clerk>-v1` release already exists is reused, not retrained (`REUSE_RELEASED`), so a rerun
picks up where the last one stopped.

`UPLOAD = "auto"` (the default in these notebooks) publishes when a `GITHUB_TOKEN` secret is set
and `KAGGLE_MIRROR = "auto"` mirrors when a `KAGGLE_KEY` secret is set, so a run needs no edit
beyond its secrets. Without an attached dataset the corpus is downloaded from `main` (or the
`claude/memory-clerk-corpus` branch before it merges).

1. Run `notebooks/base.ipynb` once with internet on. It needs no GPU and no
   training: it exports Qwen2.5-0.5B-Instruct with every LoRA weight as a graph input, quantizes the
   weights only to INT8 (MatMulNBits; LoRA inputs stay float32), and publishes
   `aive-memory-base-int8.tar.gz` plus `base.json` to the `memory-base-v1` pre-release.
2. Run `notebooks/all.ipynb` or `notebooks/<clerk>.ipynb` on a GPU with internet on. Per clerk, it trains the adapter (prompt +
   answer up to 3072 tokens, a budget of `ADAPTER_MIN_STEPS` optimizer steps, stopping at the first
   epoch whose validation loss improves less than 5% and keeping the best epoch), gates it on the clerk's
   test and adversarial splits (`json_exact`, thresholds from the role's config), downloads the
   published base and verifies its SHA-256, and gates the adapter on it on CPU, exactly as it ships (100 sampled test rows plus every adversarial row;
   `ONNX_GATE_TEST_ROWS = None` scores them all). Both gates apply the shortcut gates below.
   With upload on it publishes `aive-memory-<clerk>-lora-v1.safetensors` and a one-clerk
   `catalog.json` to `memory-<clerk>-v1`. A clerk that fails uploads nothing. The `catalog.json` left in
   the assets folder lists every released clerk, reused ones included.

### Gates against shortcut collapse

A small model fine-tuned on a skewed corpus can collapse into a shortcut (copy its input, ignore
it, or always give one answer) and still score well on the majority of rows. Besides the overall
`json_exact` thresholds, every clerk's config carries gates that the notebook applies to the
trained adapter and again to the shipped artifact (INT8 base plus the fp16 adapter file, on CPU):

- **Per class**: each `class:` with at least `min_class_rows` gated rows must score
  `min_class_score` on its own (so "always condense" fails on `keep-apart`, "never write" fails
  on `writes`, a copy fails on `chain:*`, whose inputs are always over the limit).
- **Shortcut baselines**: `copy` (the row's `copy`), `empty` (the empty proposal) and `constant`
  (the most common training label). On the rows where a shortcut is wrong, the clerk must score
  `min_nontrivial_score` and give the shortcut's answer at most `max_shortcut_rate` of the time.
  Each baseline's own score is printed and recorded with the gate.
- **Collapse**: no single answer may be more common among the clerk's answers than the most common
  label is among the labels, plus `max_shortcut_rate`.

A clerk failing any of them fails its gate and ships nothing. Contract exactness on the shipped
artifact was already gated: the ONNX gate decodes greedily on the INT8 base with the adapter
round-tripped through its fp16 release file and requires the parsed JSON to equal the label.

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
