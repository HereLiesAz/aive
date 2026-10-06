# Orchestration decision model

The orchestration utilities are exact code (`DeterministicLocalOrchestrationUtilities`). The few
calls that need judgement are typed questions (`OrchestrationQuestion` in
`shared/.../orchestration/OrchestrationDecisions.kt`), answered by one tiny encoder with one linear
head per question:

| Question | Reads | Options | Used by |
|---|---|---|---|
| `ambiguous-objective` | objective | no / yes | escalation gate |
| `architectural-decision` | objective | no / yes | escalation gate |
| `multi-step-reasoning` | objective | no / yes | escalation gate |
| `chronological-context` | objective | no / yes | memory query composer |
| `contradictory-criteria` | criteria joined with ` ; ` | no / yes | escalation gate |
| `verification-operation` | one part of a criterion | evidence-check, lint, test, build, health-check, source-verification | verification planner |

`DecisionInformedOrchestrationUtilities` uses an answer only at or above 0.8 confidence; otherwise
the heuristic stands. Escalation flags only turn on, never off.

## Files

| File | Purpose |
|---|---|
| `generate_corpus.py` | Builds the corpus. Templates are split by index and slot vocabulary is split too, so the test split is unseen phrasings about unseen things; `adversarial` is hand-written near misses |
| `make_notebook.py` | Writes `decisions.ipynb`; edit it, never the notebook |
| `decisions.ipynb` | Trains each candidate encoder (smallest first), gates it, exports INT8 ONNX, checks INT8 against float, releases the smallest that passes |

## Running

Colab or Kaggle, GPU optional. Secrets: `GITHUB_TOKEN` (`contents:write` on `HereLiesAz/aive`).
On Colab the work is kept in `MyDrive/aive-decisions`, so a rerun resumes. The release is
`orchestration-decisions-v1`: `aive-orchestration-decisions-int8.tar.gz` (`model.onnx`,
`tokenizer.json`, `config.json`), `catalog.json` and `results.json`. Register it with

~~~
python3 tools/orchestration_training/register_catalog.py catalog.json
~~~

The Android app installs it from Settings → Local orchestration (`AndroidOrchestrationDecisionModel`).

## Gates

Per question, on the test split: every class at least 0.85, answers at or above the confidence
threshold at least 0.97 right; adversarial confident answers at least 0.8 right; INT8 within 0.02 of
float. A candidate that fails is not released, however small.

## Candidates

Smallest first: BERT 2×128 (4.4M), BERT 4×256 (11M), Ettin 17M, MiniLM-L6 (22M), Ettin 32M. A CPU
smoke run of the 4.4M model: 4.5 MB INT8, 0.7 ms per text on CPU, INT8 within one point of float;
it failed the held-out-template gate (66% accuracy), so it would not ship.
