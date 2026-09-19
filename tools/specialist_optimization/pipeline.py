#!/usr/bin/env python3
"""Offline specialist optimization and release pipeline for The Aive.

DSPy is imported only when optimization is requested. Validation, release gates,
manifest construction, hashing, and bundle creation use the Python standard library
so CI can exercise the release contract without model credentials.
"""
from __future__ import annotations

import argparse
import dataclasses
import hashlib
import importlib.util
import json
import math
import sys
import zipfile
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Mapping, Sequence

SUPPORTED_OPTIMIZERS = {"gepa", "mipro"}
SUPPORTED_METRICS = {"normalized_exact", "json_exact", "contains_all"}
REQUIRED_SPLITS = {"train", "validation", "test", "adversarial"}


@dataclass(frozen=True)
class Example:
    example_id: str
    input_text: str
    expected_output: str
    split: str
    tags: tuple[str, ...] = ()


@dataclass(frozen=True)
class ReleaseGates:
    min_test_score: float = 0.80
    min_improvement: float = 0.00
    min_adversarial_score: float = 0.75

    def __post_init__(self) -> None:
        for name, value in dataclasses.asdict(self).items():
            if not math.isfinite(value) or value < 0.0 or value > 1.0:
                raise ValueError(f"{name} must be finite and in [0, 1]")


@dataclass(frozen=True)
class OptimizationConfig:
    specialist_id: str
    foundation_model_id: str
    optimizer: str
    student_model: str
    reflection_model: str | None
    metric: str
    auto: str
    num_threads: int
    gates: ReleaseGates

    @classmethod
    def from_mapping(cls, data: Mapping[str, Any]) -> "OptimizationConfig":
        optimizer = str(data.get("optimizer", "")).strip().lower()
        metric = str(data.get("metric", "normalized_exact")).strip().lower()
        if optimizer not in SUPPORTED_OPTIMIZERS:
            raise ValueError(f"optimizer must be one of {sorted(SUPPORTED_OPTIMIZERS)}")
        if metric not in SUPPORTED_METRICS:
            raise ValueError(f"metric must be one of {sorted(SUPPORTED_METRICS)}")
        specialist_id = str(data.get("specialist_id", "")).strip()
        foundation_model_id = str(data.get("foundation_model_id", "")).strip()
        student_model = str(data.get("student_model", "")).strip()
        if not specialist_id:
            raise ValueError("specialist_id is required")
        if not foundation_model_id:
            raise ValueError("foundation_model_id is required")
        if not student_model:
            raise ValueError("student_model is required")
        reflection_raw = data.get("reflection_model")
        reflection_model = str(reflection_raw).strip() if reflection_raw is not None else None
        if reflection_model == "":
            reflection_model = None
        auto = str(data.get("auto", "light")).strip().lower()
        if auto not in {"light", "medium", "heavy"}:
            raise ValueError("auto must be light, medium, or heavy")
        num_threads = int(data.get("num_threads", 2))
        if num_threads < 1:
            raise ValueError("num_threads must be at least 1")
        gates_raw = data.get("gates") or {}
        gates = ReleaseGates(
            min_test_score=float(gates_raw.get("min_test_score", 0.80)),
            min_improvement=float(gates_raw.get("min_improvement", 0.00)),
            min_adversarial_score=float(gates_raw.get("min_adversarial_score", 0.75)),
        )
        return cls(
            specialist_id=specialist_id,
            foundation_model_id=foundation_model_id,
            optimizer=optimizer,
            student_model=student_model,
            reflection_model=reflection_model,
            metric=metric,
            auto=auto,
            num_threads=num_threads,
            gates=gates,
        )


@dataclass(frozen=True)
class Evaluation:
    score: float
    passed: int
    total: int
    failures: tuple[str, ...]


@dataclass(frozen=True)
class GateResult:
    passed: bool
    reasons: tuple[str, ...]


def canonical_json(value: Any) -> str:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def normalize_text(value: str) -> str:
    return " ".join(value.strip().split()).casefold()


def score_output(metric: str, expected: str, actual: str) -> float:
    if metric == "normalized_exact":
        return 1.0 if normalize_text(expected) == normalize_text(actual) else 0.0
    if metric == "json_exact":
        try:
            return 1.0 if json.loads(expected) == json.loads(actual) else 0.0
        except json.JSONDecodeError:
            return 0.0
    if metric == "contains_all":
        expected_terms = [normalize_text(term) for term in expected.splitlines() if term.strip()]
        haystack = normalize_text(actual)
        return 1.0 if expected_terms and all(term in haystack for term in expected_terms) else 0.0
    raise ValueError(f"Unsupported metric {metric!r}")


def load_config(path: Path) -> OptimizationConfig:
    return OptimizationConfig.from_mapping(json.loads(path.read_text(encoding="utf-8")))


def load_dataset(path: Path) -> list[Example]:
    examples: list[Example] = []
    seen: set[str] = set()
    with path.open("r", encoding="utf-8") as handle:
        for line_number, raw in enumerate(handle, start=1):
            if not raw.strip():
                continue
            value = json.loads(raw)
            example_id = str(value.get("id", "")).strip()
            input_text = str(value.get("input", ""))
            expected_output = str(value.get("expected", ""))
            split = str(value.get("split", "")).strip().lower()
            tags = tuple(str(tag) for tag in value.get("tags", []))
            if not example_id:
                raise ValueError(f"{path}:{line_number}: id is required")
            if example_id in seen:
                raise ValueError(f"{path}:{line_number}: duplicate id {example_id!r}")
            if not input_text.strip():
                raise ValueError(f"{path}:{line_number}: input is required")
            if not expected_output.strip():
                raise ValueError(f"{path}:{line_number}: expected is required")
            if split not in REQUIRED_SPLITS:
                raise ValueError(
                    f"{path}:{line_number}: split must be one of {sorted(REQUIRED_SPLITS)}"
                )
            seen.add(example_id)
            examples.append(Example(example_id, input_text, expected_output, split, tags))
    present = {example.split for example in examples}
    missing = REQUIRED_SPLITS - present
    if missing:
        raise ValueError(f"dataset is missing required split(s): {', '.join(sorted(missing))}")
    return examples


def evaluate(
    examples: Sequence[Example],
    predict: Callable[[str], str],
    metric: str,
) -> Evaluation:
    if not examples:
        raise ValueError("evaluation split must not be empty")
    passed = 0
    failures: list[str] = []
    for example in examples:
        actual = predict(example.input_text)
        score = score_output(metric, example.expected_output, actual)
        if score >= 1.0:
            passed += 1
        else:
            failures.append(example.example_id)
    return Evaluation(
        score=passed / len(examples),
        passed=passed,
        total=len(examples),
        failures=tuple(failures),
    )


def check_release_gates(
    baseline_test: Evaluation,
    optimized_test: Evaluation,
    optimized_adversarial: Evaluation,
    gates: ReleaseGates,
) -> GateResult:
    reasons: list[str] = []
    if optimized_test.score < gates.min_test_score:
        reasons.append(
            f"optimized test score {optimized_test.score:.4f} < required {gates.min_test_score:.4f}"
        )
    improvement = optimized_test.score - baseline_test.score
    if improvement < gates.min_improvement:
        reasons.append(
            f"test improvement {improvement:.4f} < required {gates.min_improvement:.4f}"
        )
    if optimized_adversarial.score < gates.min_adversarial_score:
        reasons.append(
            "optimized adversarial score "
            f"{optimized_adversarial.score:.4f} < required {gates.min_adversarial_score:.4f}"
        )
    return GateResult(passed=not reasons, reasons=tuple(reasons))


def _split(examples: Sequence[Example], name: str) -> list[Example]:
    return [example for example in examples if example.split == name]


def _require_dspy() -> Any:
    if importlib.util.find_spec("dspy") is None:
        raise RuntimeError(
            "DSPy is not installed. Run `python -m pip install -r "
            "tools/specialist_optimization/requirements.txt`."
        )
    import dspy  # type: ignore
    return dspy


def _to_dspy_examples(dspy: Any, examples: Sequence[Example]) -> list[Any]:
    return [
        dspy.Example(input_text=e.input_text, output_text=e.expected_output).with_inputs("input_text")
        for e in examples
    ]


def _prediction_text(prediction: Any) -> str:
    value = getattr(prediction, "output_text", None)
    if value is None and isinstance(prediction, Mapping):
        value = prediction.get("output_text")
    if value is None:
        raise RuntimeError("DSPy prediction did not contain output_text")
    return str(value)


def optimize_with_dspy(
    config: OptimizationConfig,
    examples: Sequence[Example],
) -> tuple[Any, Any, Callable[[str], str], Callable[[str], str]]:
    dspy = _require_dspy()
    student_lm = dspy.LM(config.student_model)
    dspy.configure(lm=student_lm)
    baseline = dspy.Predict("input_text -> output_text")

    def metric(example: Any, prediction: Any, trace: Any = None) -> float:
        del trace
        return score_output(config.metric, str(example.output_text), _prediction_text(prediction))

    trainset = _to_dspy_examples(dspy, _split(examples, "train"))
    valset = _to_dspy_examples(dspy, _split(examples, "validation"))

    if config.optimizer == "gepa":
        reflection_model = config.reflection_model or config.student_model
        optimizer = dspy.GEPA(
            metric=metric,
            reflection_lm=dspy.LM(reflection_model),
            auto=config.auto,
            num_threads=config.num_threads,
        )
        optimized = optimizer.compile(baseline.deepcopy(), trainset=trainset, valset=valset)
    else:
        optimizer = dspy.MIPROv2(
            metric=metric,
            auto=config.auto,
            num_threads=config.num_threads,
        )
        try:
            optimized = optimizer.compile(baseline.deepcopy(), trainset=trainset, valset=valset)
        except TypeError:
            optimized = optimizer.compile(baseline.deepcopy(), trainset=trainset)

    def baseline_predict(text: str) -> str:
        return _prediction_text(baseline(input_text=text))

    def optimized_predict(text: str) -> str:
        return _prediction_text(optimized(input_text=text))

    return baseline, optimized, baseline_predict, optimized_predict


def write_adapter_training_data(
    path: Path,
    examples: Sequence[Example],
    optimized_predict: Callable[[str], str],
) -> None:
    with path.open("w", encoding="utf-8") as handle:
        for example in [e for e in examples if e.split in {"train", "validation"}]:
            record = {
                "id": example.example_id,
                "input": example.input_text,
                "target": example.expected_output,
                "optimized_output": optimized_predict(example.input_text),
                "tags": list(example.tags),
            }
            handle.write(canonical_json(record) + "\n")


def build_manifest(
    *,
    config: OptimizationConfig,
    config_path: Path,
    dataset_path: Path,
    program_path: Path,
    adapter_data_path: Path,
    baseline_test: Evaluation,
    optimized_test: Evaluation,
    optimized_adversarial: Evaluation,
    gate: GateResult,
) -> dict[str, Any]:
    manifest: dict[str, Any] = {
        "schemaVersion": 1,
        "specialistId": config.specialist_id,
        "foundationModelId": config.foundation_model_id,
        "optimizer": config.optimizer,
        "metric": config.metric,
        "studentModel": config.student_model,
        "reflectionModel": config.reflection_model,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "inputs": {
            "configSha256": sha256_file(config_path),
            "datasetSha256": sha256_file(dataset_path),
        },
        "artifacts": {
            "optimizedProgram": {
                "path": program_path.name,
                "sha256": sha256_file(program_path),
            },
            "adapterTrainingData": {
                "path": adapter_data_path.name,
                "sha256": sha256_file(adapter_data_path),
            },
        },
        "evaluation": {
            "baselineTest": dataclasses.asdict(baseline_test),
            "optimizedTest": dataclasses.asdict(optimized_test),
            "optimizedAdversarial": dataclasses.asdict(optimized_adversarial),
        },
        "releaseGate": {
            "passed": gate.passed,
            "reasons": list(gate.reasons),
            "thresholds": dataclasses.asdict(config.gates),
        },
    }
    manifest["releaseDigest"] = sha256_bytes(canonical_json(manifest).encode("utf-8"))
    return manifest


def bundle_release(output_dir: Path, manifest_path: Path, artifacts: Sequence[Path]) -> Path:
    bundle = output_dir / "specialist-release.zip"
    with zipfile.ZipFile(bundle, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        archive.write(manifest_path, manifest_path.name)
        for artifact in artifacts:
            archive.write(artifact, artifact.name)
    return bundle


def validate(config_path: Path, dataset_path: Path) -> tuple[OptimizationConfig, list[Example]]:
    config = load_config(config_path)
    examples = load_dataset(dataset_path)
    return config, examples


def run(config_path: Path, dataset_path: Path, output_dir: Path) -> int:
    config, examples = validate(config_path, dataset_path)
    output_dir.mkdir(parents=True, exist_ok=True)
    _, optimized, baseline_predict, optimized_predict = optimize_with_dspy(config, examples)

    baseline_test = evaluate(_split(examples, "test"), baseline_predict, config.metric)
    optimized_test = evaluate(_split(examples, "test"), optimized_predict, config.metric)
    optimized_adversarial = evaluate(
        _split(examples, "adversarial"), optimized_predict, config.metric
    )
    gate = check_release_gates(
        baseline_test,
        optimized_test,
        optimized_adversarial,
        config.gates,
    )

    program_path = output_dir / "optimized-program.json"
    optimized.save(str(program_path))
    if not program_path.is_file():
        raise RuntimeError(f"DSPy did not create optimized program at {program_path}")

    adapter_data_path = output_dir / "adapter-training.jsonl"
    write_adapter_training_data(adapter_data_path, examples, optimized_predict)

    manifest = build_manifest(
        config=config,
        config_path=config_path,
        dataset_path=dataset_path,
        program_path=program_path,
        adapter_data_path=adapter_data_path,
        baseline_test=baseline_test,
        optimized_test=optimized_test,
        optimized_adversarial=optimized_adversarial,
        gate=gate,
    )
    manifest_path = output_dir / "runtime-manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    bundle_release(output_dir, manifest_path, [program_path, adapter_data_path])

    print(json.dumps(manifest["evaluation"], indent=2, sort_keys=True))
    if not gate.passed:
        for reason in gate.reasons:
            print(f"RELEASE GATE FAILED: {reason}", file=sys.stderr)
        return 2
    print(f"Release gate passed for {config.specialist_id}")
    return 0


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--dataset", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, default=Path("build/specialist-release"))
    parser.add_argument(
        "--validate-only",
        action="store_true",
        help="Validate config/dataset without importing DSPy or calling a model.",
    )
    args = parser.parse_args(argv)
    config, examples = validate(args.config, args.dataset)
    if args.validate_only:
        counts = {split: len(_split(examples, split)) for split in sorted(REQUIRED_SPLITS)}
        print(canonical_json({"specialistId": config.specialist_id, "splits": counts}))
        return 0
    return run(args.config, args.dataset, args.output_dir)


if __name__ == "__main__":
    raise SystemExit(main())
