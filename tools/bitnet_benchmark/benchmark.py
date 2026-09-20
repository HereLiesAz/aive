#!/usr/bin/env python3
"""Admission benchmark: native BitNet b1.58 versus an Epoch-8 Qwen/ONNX specialist.

The benchmark is intentionally incapable of changing production model selection.  It records
representative specialist quality and runtime measurements, then reports whether the candidate may
advance to platform-specific evaluation.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import resource
import subprocess
import time
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any

import numpy as np
import onnxruntime as ort
from transformers import AutoTokenizer

OUTPUT_MARKER = "MODEL_OUTPUT_START"
BITNET_MODEL_ID = "microsoft/bitnet-b1.58-2B-4T-gguf"
QWEN_MODEL_ID = "Qwen/Qwen2.5-0.5B-Instruct"

CASES = [
    {
        "id": "noun-entities",
        "text": "The Android release uses ONNX Runtime and Qwen for local memory inference.",
        "expected_kind": "NounTag",
        "expected_terms": ["Android", "ONNX Runtime", "Qwen"],
    },
    {
        "id": "noun-artifacts",
        "text": "GitHub Actions uploads the signed APK and the verification report.",
        "expected_kind": "NounTag",
        "expected_terms": ["GitHub Actions", "APK", "verification report"],
    },
    {
        "id": "noun-components",
        "text": "The workflow coordinator reconnects the Jules provider session after restart.",
        "expected_kind": "NounTag",
        "expected_terms": ["workflow coordinator", "Jules", "provider session"],
    },
]


@dataclass
class CaseResult:
    case_id: str
    valid_json: bool
    correct_kind: bool
    term_recall: float
    output: str
    elapsed_seconds: float
    generated_tokens: int | None = None


@dataclass
class RunnerResult:
    model: str
    runtime: str
    model_bytes: int
    startup_seconds: float
    peak_rss_kib: int | None
    cases: list[CaseResult]

    @property
    def valid_json_rate(self) -> float:
        return sum(item.valid_json for item in self.cases) / len(self.cases)

    @property
    def correct_kind_rate(self) -> float:
        return sum(item.correct_kind for item in self.cases) / len(self.cases)

    @property
    def mean_term_recall(self) -> float:
        return sum(item.term_recall for item in self.cases) / len(self.cases)

    @property
    def mean_elapsed_seconds(self) -> float:
        return sum(item.elapsed_seconds for item in self.cases) / len(self.cases)

    def to_json(self) -> dict[str, Any]:
        data = asdict(self)
        data.update(
            valid_json_rate=self.valid_json_rate,
            correct_kind_rate=self.correct_kind_rate,
            mean_term_recall=self.mean_term_recall,
            mean_elapsed_seconds=self.mean_elapsed_seconds,
        )
        return data


def prompt_for(text: str) -> str:
    return f"""You are a local Aive memory micro-agent.
ROLE: NounTagger
STAGE: Tags
Extract only explicit nouns, named entities, concrete components, services, tools, artifacts, and products from the supplied item.
Do not infer facts that are not stated.

INPUT ITEMS
- ID=item-1 KIND=context
{text}

Return exactly one JSON object and nothing else.
{{"sections":[],"nodes":[{{"key":"local-key","kind":"NounTag","text":"...","sourceIds":["item-1"],"salience":0.5,"confidence":1.0,"metadata":{{}}}}],"links":[]}}
Use NounTag for every node kind. Use item-1 as every sourceIds entry.
{OUTPUT_MARKER}
"""


def extract_json(text: str) -> dict[str, Any] | None:
    candidate = text.split(OUTPUT_MARKER)[-1].strip()
    start = candidate.find("{")
    if start < 0:
        return None
    decoder = json.JSONDecoder()
    try:
        value, _ = decoder.raw_decode(candidate[start:])
    except json.JSONDecodeError:
        return None
    return value if isinstance(value, dict) else None


def score(
    case: dict[str, Any],
    output: str,
    elapsed: float,
    generated_tokens: int | None = None,
) -> CaseResult:
    parsed = extract_json(output)
    nodes = parsed.get("nodes", []) if parsed else []
    if not isinstance(nodes, list):
        nodes = []
    dict_nodes = [node for node in nodes if isinstance(node, dict)]
    kinds = [str(node.get("kind", "")) for node in dict_nodes]
    rendered_text = " ".join(str(node.get("text", "")) for node in dict_nodes).casefold()
    expected = [term.casefold() for term in case["expected_terms"]]
    hits = sum(term in rendered_text for term in expected)
    return CaseResult(
        case_id=case["id"],
        valid_json=parsed is not None,
        correct_kind=bool(dict_nodes) and len(dict_nodes) == len(nodes) and
            all(kind == case["expected_kind"] for kind in kinds),
        term_recall=hits / len(expected),
        output=output[-6000:],
        elapsed_seconds=elapsed,
        generated_tokens=generated_tokens,
    )


class QwenOnnxRunner:
    def __init__(self, root: Path) -> None:
        self.root = root
        model_candidates = sorted(root.rglob("*.onnx"))
        if not model_candidates:
            raise RuntimeError(f"No ONNX model found below {root}")
        self.model_path = model_candidates[0]
        tokenizer_root = next((path.parent for path in root.rglob("tokenizer.json")), None)
        if tokenizer_root is None:
            raise RuntimeError(f"No tokenizer.json found below {root}")
        self.tokenizer = AutoTokenizer.from_pretrained(
            str(tokenizer_root),
            local_files_only=True,
            trust_remote_code=False,
        )
        started = time.perf_counter()
        self.session = ort.InferenceSession(
            str(self.model_path),
            providers=["CPUExecutionProvider"],
        )
        self.startup_seconds = time.perf_counter() - started
        config_path = next(iter(root.rglob("config.json")), None)
        if config_path is None:
            raise RuntimeError("Qwen archive does not contain config.json")
        config = json.loads(config_path.read_text(encoding="utf-8"))
        self.num_kv_heads = int(config["num_key_value_heads"])
        self.head_dim = int(config["hidden_size"]) // int(config["num_attention_heads"])
        self.eos_ids = {
            token_id
            for token_id in (
                self.tokenizer.eos_token_id,
                self.tokenizer.convert_tokens_to_ids("<|im_end|>"),
                self.tokenizer.convert_tokens_to_ids("<|endoftext|>"),
            )
            if isinstance(token_id, int) and token_id >= 0
        }

    def _zero_past(self, ort_type: str) -> np.ndarray:
        dtype = np.float16 if "float16" in ort_type else np.float32
        return np.zeros((1, self.num_kv_heads, 0, self.head_dim), dtype=dtype)

    def generate(self, prompt: str, max_new_tokens: int = 128) -> tuple[str, int, float]:
        prompt_ids = self.tokenizer.encode(prompt, add_special_tokens=False)
        if not prompt_ids:
            raise RuntimeError("Qwen tokenizer returned no prompt tokens")
        total_length = len(prompt_ids)
        present: dict[str, np.ndarray] = {}
        generated: list[int] = []
        started = time.perf_counter()

        for step in range(max_new_tokens):
            token_ids = prompt_ids if step == 0 else [generated[-1]]
            inputs: dict[str, np.ndarray] = {}
            for input_meta in self.session.get_inputs():
                name = input_meta.name
                if name == "input_ids":
                    inputs[name] = np.asarray([token_ids], dtype=np.int64)
                elif name == "attention_mask":
                    inputs[name] = np.ones((1, total_length), dtype=np.int64)
                elif name == "position_ids":
                    start = total_length - len(token_ids)
                    inputs[name] = np.arange(start, total_length, dtype=np.int64)[None, :]
                elif name == "cache_position":
                    start = total_length - len(token_ids)
                    inputs[name] = np.arange(start, total_length, dtype=np.int64)
                elif name == "use_cache_branch":
                    inputs[name] = np.asarray([step > 0], dtype=np.bool_)
                elif name.startswith("past_key_values."):
                    output_name = name.replace("past_key_values.", "present.", 1)
                    inputs[name] = present.get(output_name, self._zero_past(input_meta.type))
                else:
                    raise RuntimeError(f"Unsupported Qwen ONNX input: {name}")

            output_names = [output.name for output in self.session.get_outputs()]
            values = self.session.run(output_names, inputs)
            by_name = dict(zip(output_names, values))
            logits = by_name["logits"]
            next_token = int(np.argmax(logits[0, -1]))
            present = {
                name: value
                for name, value in by_name.items()
                if name.startswith("present.")
            }
            if next_token in self.eos_ids:
                break
            generated.append(next_token)
            total_length += 1

        elapsed = time.perf_counter() - started
        return self.tokenizer.decode(generated, skip_special_tokens=True), len(generated), elapsed


def bitnet_generate(
    cli: Path,
    model: Path,
    prompt: str,
    threads: int,
    max_new_tokens: int = 128,
) -> tuple[str, int | None, float]:
    command = [
        str(cli),
        "-m", str(model),
        "-p", prompt,
        "-n", str(max_new_tokens),
        "-t", str(threads),
        "-ngl", "0",
        "-c", "2048",
        "--temp", "0",
    ]
    started = time.perf_counter()
    process = subprocess.run(
        command,
        text=True,
        capture_output=True,
        timeout=300,
        check=True,
    )
    elapsed = time.perf_counter() - started
    combined = process.stdout + "\n" + process.stderr
    token_match = re.search(r"eval time\s*=.*?/\s*(\d+)\s+runs", combined)
    return combined, int(token_match.group(1)) if token_match else None, elapsed


def run_qwen(root: Path) -> RunnerResult:
    runner = QwenOnnxRunner(root)
    results = []
    for case in CASES:
        output, tokens, elapsed = runner.generate(prompt_for(case["text"]))
        results.append(score(case, output, elapsed, tokens))
    return RunnerResult(
        model=QWEN_MODEL_ID,
        runtime="onnxruntime-cpu/epoch8-int8-noun-specialist",
        model_bytes=sum(path.stat().st_size for path in root.rglob("*") if path.is_file()),
        startup_seconds=runner.startup_seconds,
        peak_rss_kib=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,
        cases=results,
    )


def run_bitnet(cli: Path, model: Path, threads: int) -> RunnerResult:
    started = time.perf_counter()
    bitnet_generate(cli, model, "Reply with OK.", threads, max_new_tokens=1)
    startup = time.perf_counter() - started
    results = []
    for case in CASES:
        output, tokens, elapsed = bitnet_generate(
            cli,
            model,
            prompt_for(case["text"]),
            threads,
        )
        results.append(score(case, output, elapsed, tokens))
    return RunnerResult(
        model=BITNET_MODEL_ID,
        runtime="microsoft-bitnet.cpp/i2_s-cpu",
        model_bytes=model.stat().st_size,
        startup_seconds=startup,
        peak_rss_kib=None,
        cases=results,
    )


def decision(qwen: RunnerResult, bitnet: RunnerResult) -> dict[str, Any]:
    quality_parity = (
        bitnet.valid_json_rate >= qwen.valid_json_rate
        and bitnet.correct_kind_rate >= qwen.correct_kind_rate
        and bitnet.mean_term_recall >= qwen.mean_term_recall
    )
    runtime_ready = False
    return {
        "qualityParity": quality_parity,
        "haiveRuntimeReady": runtime_ready,
        "adoptBitNet": quality_parity and runtime_ready,
        "reason": (
            "Do not adopt: Aive BITCOS currently provides ternary storage/decode primitives, "
            "not tokenizer/transformer execution. "
            + (
                "Task-quality parity was observed."
                if quality_parity
                else "Task-quality parity was not observed."
            )
        ),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--qwen-root", type=Path, required=True)
    parser.add_argument("--bitnet-cli", type=Path, required=True)
    parser.add_argument("--bitnet-model", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument(
        "--threads",
        type=int,
        default=max(1, min(4, os.cpu_count() or 1)),
    )
    args = parser.parse_args()

    qwen = run_qwen(args.qwen_root)
    bitnet = run_bitnet(args.bitnet_cli, args.bitnet_model, args.threads)
    report = {
        "schemaVersion": 1,
        "benchmark": "aive-bitnet-vs-epoch8-representative",
        "candidate": bitnet.to_json(),
        "control": qwen.to_json(),
        "decision": decision(qwen, bitnet),
        "limitations": [
            "Representative NounTagger workload only; this is an admission benchmark, not a claim that every role is equivalent.",
            "GitHub-hosted x86 measurements do not substitute for Android energy/thermal testing.",
            "BitNet cases are cold-process measurements; elapsed time is informational, not an apples-to-apples steady-state latency claim.",
            "BitNet runs through Microsoft's bitnet.cpp; Aive's BITCOS runtime is not yet a transformer runtime.",
        ],
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(report, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
