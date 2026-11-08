# Refinement-level training

Status: **corpus builder and a per-level trainer exist; release does not.** The trainer was built without
data and its torch path has not been run (no GPU or ML libraries in the place it was written); the readiness
check, scoring, baselines and dry run are tested. The first real run is what finds out whether it works.

A refinement ladder ([COMPOUND_INFERENCE.md](../../docs/architecture/COMPOUND_INFERENCE.md#refinement-ladder))
works a task coarse to fine, one gated level at a time. The idea here is one small specialist per level
on a shared base (a LoRA adapter each, as the memory clerks and orchestration utilities are), each
trained to produce the structure of its level and to build on the structure the level above gave it.

| File | Purpose |
|---|---|
| `build_corpus.py` | Turns an exported ladder dataset into per-level corpora, configs and an immutable manifest |
| `test_build_corpus.py` | Its tests (`python3 -m unittest discover -s tools/refinement_training -p 'test_*.py'`) |
| `train_level.py` | Trains one level's LoRA adapter and judges it against the shortcut baselines; `--dry-run` checks readiness and prints the baselines to beat without loading a model |
| `test_train_level.py` | Its tests (readiness, scoring, baselines, the noise margin) |

## 1. Where the data comes from

Real ladder runs, not a generator. With capture switched on (`ladder.dataset.capture.enabled`; see
[privacy](../../docs/PRIVACY.md#refinement-ladder-training-data-off-by-default)), the app records each finished
level (`LadderDatasetRecord`: the objective as the agent was given it, its answer, whether the answer
fits `aive.ladder.level.v1` and refines the level above, and its gate's verdict) and how each ladder ended.
`LadderDatasetCapture.exportJsonl()` is this builder's input. Levels must be set `structured` for their
answers to count.

Because the data are real runs, a level's input is the **approved answer of the level above as that run
produced it**, not a gold one. A level model therefore trains on the same kind of input it will see at
inference, which is the exposure-bias problem of training a finer model on gold coarse structure.

## 2. Build

~~~
python3 tools/refinement_training/build_corpus.py --input ladder-export.jsonl --out build/refinement-corpus
~~~

Per level N: `refinement-level-N.jsonl` (rows `id`, `input`, `expected`, `split`, `tags`, the shape of
`tools/orchestration_training`), `refinement-level-N.config.json` (`specialist_id`
`refinement:level-N`, base `Qwen/Qwen2.5-0.5B-Instruct`, gates), and `rejected.jsonl` (levels a gate
turned back; never trained on, kept to evaluate a gate model later). `manifest.json` records the source
hash, per-file hashes, split counts and the shortcut rates, and says where the labels came from.

- `input` is JSON: `level`, `of`, `objective`, `coarser` (the approved answer above, or null at level 1).
- `expected` is the level's `aive.ladder.level.v1` document, canonical JSON.
- A row needs a parsed document that agrees with its record, `valid` (it refines the level above), an
  `approve` verdict, and an approved, valid level above it. A level whose gate said `reject` or `revise` is
  not a row.
- `split` is by ladder (70/15/15), so the levels of one ladder never straddle splits. Duplicate inputs
  are kept once.

## 3. Gates

`ready` in the manifest is true for a level only when it has at least `min_rows` (200) rows, a test
split, and `max(copy, constant)` shortcut rate at most `max_shortcut_rate` (0.2):

- **copy**: the rate at which repeating the level above verbatim is exactly right on test;
- **constant**: the rate at which the most common training answer is right;
- **empty**: not applicable (a level always writes something).

These exist because the nine orchestration specialists were retired: their labels equalled a
deterministic rule, so the model could only match it
([README](../orchestration_training/README.md)). A level specialist is worth training only if it beats
these baselines on held-out ladders by more than noise.

## 4. What is not done, and what the data cannot show

- **Labels are a reviewing agent's verdicts**, not an exact checker. A specialist trained on them learns
  what one reviewer approves. `outcome:` tags (the final verifier's verdict) help but are the same kind
  of label. Where the finest level yields `ProcedureStep`-shaped operations, `ProcedureStep` and
  `WorkflowGraphValidator` give an exact check; use them as the held-out gate before trusting a model.
- **No adversarial split.** Adversarial rows cannot be derived from runs; they must be written by hand.
- **No volume yet.** Hundreds of structured ladder runs are needed before any level is `ready`.
- **Not yet built:** a `refinement` family in `tools/orchestration_training/make_notebook.py` (the CI
  check regenerates every notebook, so it is added there, never by hand), a Kotlin catalog and
  installers (`OrchestrationSpecialistCatalog` allows only its own ids), and a comparison against the
  single-model ladder at equal cost, which decides whether one model per level helps at all.

## 5. Training one level

~~~
python3 tools/refinement_training/train_level.py --corpus build/refinement-corpus --level 2 --dry-run
python3 tools/refinement_training/train_level.py --corpus build/refinement-corpus --level 2 --out build/refinement-adapters
~~~

- A level the manifest does not call `ready` is refused (exit 2). `--allow-unready` trains it anyway and the
  result carries `ready: false`, so it can never read as a pass.
- It writes `refinement-level-N.result.json` beside the adapter: the model's exact-match rate on the
  held-out ladders, the **copy** and **constant** baselines on the same rows, the noise margin (two
  standard errors, at least one row), `beats_baselines`, `meets_min_test_score` and `passed`. Exit 0 only
  when passed, 1 when trained but not passed.
- **What would show it is wrong:** a level that trains to a high score but does not beat the best baseline by
  more than the margin (the labels are a rule it learned); a `passed` level whose answers fail
  `ProcedureStep` or `WorkflowGraphValidator` when the finest level is checked exactly (the reviewer's
  verdicts are not the truth); or a result that moves a lot between runs with the same corpus (too few rows).
- Not done, and not needed to find out: ONNX export, the `make_notebook.py` family, a Kotlin catalog and
  installers. They come after a level passes.
