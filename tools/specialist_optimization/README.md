# Specialist optimization pipeline

This directory is the offline model-development boundary for The Aive's small specialist and control models. Runtime workflow correctness never invokes an optimizer.

The pipeline supports the roadmap's two current DSPy prompt optimizers, **GEPA** and **MIPROv2**. A release run consumes a typed JSON config plus a JSONL evaluation corpus, optimizes an `input_text -> output_text` DSPy program, evaluates untouched test and adversarial splits, then emits:

- `optimized-program.json` — the DSPy program/instructions/examples;
- `adapter-training.jsonl` — supervised material suitable for a later LoRA/adapter training stage;
- `runtime-manifest.json` — model identity, optimizer identity, input/artifact SHA-256 digests, holdout scores, adversarial scores, and release-gate result;
- `specialist-release.zip` — immutable bundle of those artifacts.

A failed quality or adversarial gate returns exit code 2 and must not be published as a runtime specialist.

## Dataset contract

Every JSONL row has these fields:

```json
{"id":"unique-id","input":"input text","expected":"expected output","split":"train","tags":["optional"]}
```

`split` must be one of `train`, `validation`, `test`, or `adversarial`, and all four splits are mandatory. IDs must be unique. Test/adversarial examples are never passed to the optimizer.

## Config contract

```json
{
  "specialist_id": "orchestration:execution-state-summarizer",
  "foundation_model_id": "Qwen/Qwen2.5-0.5B-Instruct",
  "optimizer": "gepa",
  "student_model": "openai/gpt-5-mini",
  "reflection_model": "openai/gpt-5",
  "metric": "normalized_exact",
  "auto": "light",
  "num_threads": 2,
  "gates": {
    "min_test_score": 0.8,
    "min_improvement": 0.0,
    "min_adversarial_score": 0.75
  }
}
```

Supported metrics are `normalized_exact`, `json_exact`, and `contains_all`. Specialist-specific corpora should prefer structured outputs and `json_exact` where possible.

## Run

```bash
python -m pip install -r tools/specialist_optimization/requirements.txt
python tools/specialist_optimization/pipeline.py \
  --config path/to/specialist.json \
  --dataset path/to/evaluation.jsonl \
  --output-dir build/specialist-release
```

CI validates the release contract without model credentials on every PR. The manual **Specialist Optimization** workflow performs the real optimization and uploads the gated release bundle. Provider credentials are supplied only to that offline job.

DSPy is pinned to stable 3.3.1 so optimizer behavior cannot silently drift underneath an evaluation corpus.
