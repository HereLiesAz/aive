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
| `verification-operation` | one part of a criterion | evidence-check, lint, test, build, health-check, source-verification | verification planner |

`DecisionInformedOrchestrationUtilities` uses an answer only at or above 0.8 confidence; otherwise
the heuristic stands. Escalation flags only turn on, never off.

## Files

| File | Purpose |
|---|---|
| `teacher/*.jsonl` | The labelled rows the model trains and is gated on (below) |
| `teacher/SPEC.md` | The label definitions and writing rules the teacher rows follow |
| `generate_corpus.py` | Builds the corpus: the teacher rows plus the hand-written adversarial split (`--templates` adds the older template rows to train). Templates are split by index and slot vocabulary is split too, so the test split is unseen phrasings about unseen things; `adversarial` is hand-written near misses |
| `make_notebook.py` | Writes `decisions.ipynb`; edit it, never the notebook |
| `decisions.ipynb` | Trains each candidate encoder (smallest first), gates it, exports INT8 ONNX, checks INT8 against float, releases the smallest that passes |

## Where the rows come from

Contradictory acceptance criteria were asked at first and dropped: tiny encoders could not compare
criteria pairwise (41–63% on real contradictions), and the question was not needed.

Template-generated rows were not enough: every tiny encoder trained on them scored 58–66% on
unseen phrasings. The rows in `teacher/` were written by Claude agents following `teacher/SPEC.md`,
then relabelled by separate agents that saw only the shuffled texts and the definitions; a row is kept
only where both agree (4,421 of 4,432 kept). Writer and checker are the same model, so agreement shows
the labels follow the definitions consistently, not that a human would agree with every one.

Splits are by project domain, so the test split is unseen subject matter as well as unseen wording:
train covers mobile, web, backend, developer tooling and desktop; validation covers data pipelines;
test covers game development, infrastructure, documentation and research, and embedded/IoT.

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

Per question, on the INT8 model as shipped. Each question's threshold is the lowest (0.8 up) at
which its validation answers are 98% right; on the test split those answers must then be at least
95% right and cover at least 30% of rows, and adversarial confident answers at least 80% right. A
question that fails is left out of `config.json` and the app keeps its heuristic for it. INT8 must
stay within 0.02 of float. The candidate releasing the most questions wins, the smallest among equals.

## Candidates and results

Smallest first: BERT 2×128 (4.4M), BERT 4×256 (11M), Ettin 17M, MiniLM-L6 (22M), Ettin 32M.
CPU run on the teacher corpus (test = unseen domains):

| Candidate | Questions released | INT8 | CPU per text | Test accuracy (INT8) |
|---|---|---|---|---|
| BERT 2×128 | 4 of 5 | 4.5 MB | 0.7 ms | 0.922 |
| **BERT 4×256** | **5 of 5** | 11.4 MB (9 MB download) | 1.5 ms | 0.950 |
| Ettin 17M | failed (INT8 lost 5 points) | 17.1 MB | 2.1 ms | 0.914 |

BERT 4×256 per question (threshold, share answered, right when answered): ambiguous-objective 0.80,
98%, 99.0%; architectural-decision 0.91, 87%, 98.9%; multi-step-reasoning 0.88, 81%, 96.3%;
chronological-context 0.80, 97%, 97.9%; verification-operation 0.80, 95%, 97.3%.
