"""Train one refinement level's LoRA adapter and judge it against the shortcut baselines.

Reads a corpus built by build_corpus.py. It refuses a level the manifest does not call `ready`
unless --allow-unready is given (the result is then marked `unready`, so it cannot be mistaken for
a pass). It writes `refinement-level-N.result.json` beside the adapter: the model's exact-match rate
on the held-out ladders, the copy and constant baselines on the same rows, the noise margin, and
whether the model beat the best baseline by more than that margin. That comparison is the finding;
a level specialist that does not beat the baselines is not worth shipping, whatever its score.

Nothing here publishes or registers anything. Torch, transformers and peft are imported only when
training, so the readiness check, scoring and the dry run work without them.

    python3 tools/refinement_training/train_level.py --corpus build/refinement-corpus --level 2 --dry-run
    python3 tools/refinement_training/train_level.py --corpus build/refinement-corpus --level 2 --out build/refinement-adapters
"""

from __future__ import annotations

import argparse
import json
import math
import random
import sys
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from build_corpus import canonical  # noqa: E402

BASE_MODEL_FALLBACK = "Qwen/Qwen2.5-0.5B-Instruct"
EPOCHS = 2
LEARNING_RATE = 2e-4
LORA_R, LORA_ALPHA, LORA_DROPOUT = 16, 32, 0.05
MAX_LENGTH = 1024
SEED = 8
FAILURE_SAMPLES = 5


class NotReady(Exception):
    """The level has not met the corpus gates, so training it would measure noise."""


def slug_of(level: int) -> str:
    return f"refinement-level-{level}"


def load_level(corpus: Path, level: int):
    manifest = json.loads((corpus / "manifest.json").read_text(encoding="utf-8"))
    entry = manifest["levels"].get(str(level))
    if entry is None:
        raise NotReady(f"level {level} is not in the corpus manifest")
    slug = slug_of(level)
    config = json.loads((corpus / f"{slug}.config.json").read_text(encoding="utf-8"))
    rows = [json.loads(line) for line in (corpus / f"{slug}.jsonl").read_text(encoding="utf-8").splitlines() if line]
    splits = {name: [r for r in rows if r["split"] == name] for name in ("train", "validation", "test")}
    return manifest, entry, config, splits


def require_ready(entry: dict, level: int, allow_unready: bool) -> bool:
    """True when the level is ready; False when training goes ahead anyway; raises otherwise."""
    if entry.get("ready"):
        return True
    if allow_unready:
        return False
    rates = entry.get("shortcut_rates", {})
    raise NotReady(
        f"level {level} is not ready: {entry.get('rows', 0)} rows, test split {entry.get('splits', {}).get('test', 0)}, "
        f"copy {rates.get('copy')}, constant {rates.get('constant')}. Pass --allow-unready to train it anyway "
        f"(the result is marked unready)."
    )


def json_exact(text: str, expected: str) -> bool:
    try:
        start, end = text.index("{"), text.rindex("}") + 1
        return json.loads(text[start:end]) == json.loads(expected)
    except (ValueError, json.JSONDecodeError):
        return False


def baselines(train_rows: list[dict], test_rows: list[dict]) -> dict:
    """Rates of answers a model could give without reading the task, on the same test rows."""
    if not test_rows:
        return {"copy": 0.0, "constant": 0.0}
    pool = [r["expected"] for r in train_rows] or [r["expected"] for r in test_rows]
    most_common = Counter(pool).most_common(1)[0][0]

    def coarser(row):
        try:
            return json.loads(row["input"]).get("coarser")
        except (ValueError, AttributeError):
            return None

    copy = sum(1 for r in test_rows if coarser(r) is not None and canonical(coarser(r)) == r["expected"])
    constant = sum(1 for r in test_rows if r["expected"] == most_common)
    return {"copy": copy / len(test_rows), "constant": constant / len(test_rows)}


def noise_margin(rate: float, n: int) -> float:
    """Two standard errors of a rate over n rows, at least one row's worth."""
    if n <= 0:
        return 1.0
    return max(2 * math.sqrt(max(rate * (1 - rate), 0.0) / n), 1.0 / n)


def verdict(model_rate: float, base: dict, n_test: int, gates: dict, ready: bool) -> dict:
    best = max(base["copy"], base["constant"])
    margin = noise_margin(model_rate, n_test)
    beats = model_rate - best > margin
    meets = model_rate >= gates.get("min_test_score", 0.0)
    return {
        "model": model_rate,
        "copy": base["copy"],
        "constant": base["constant"],
        "best_baseline": best,
        "noise_margin": margin,
        "test_rows": n_test,
        "beats_baselines": beats,
        "meets_min_test_score": meets,
        "ready": ready,
        "passed": bool(ready and beats and meets),
    }


def score(predictions: list[str], rows: list[dict]) -> tuple[float, list[dict]]:
    hits, failures = 0, []
    for text, row in zip(predictions, rows):
        if json_exact(text, row["expected"]):
            hits += 1
        elif len(failures) < FAILURE_SAMPLES:
            failures.append({"id": row["id"], "got": text[:300]})
    return (hits / len(rows) if rows else 0.0), failures


def plan(corpus: Path, level: int, allow_unready: bool) -> dict:
    """Everything decided before any model is loaded: readiness, sizes and the baselines to beat."""
    manifest, entry, config, splits = load_level(corpus, level)
    ready = require_ready(entry, level, allow_unready)
    base = baselines(splits["train"], splits["test"])
    return {
        "level": level,
        "specialist_id": config["specialist_id"],
        "foundation_model_id": config.get("foundation_model_id", BASE_MODEL_FALLBACK),
        "ready": ready,
        "rows": {name: len(rows) for name, rows in splits.items()},
        "baselines": base,
        "gates": config["gates"],
        "corpus_sha256": entry.get("corpus_sha256"),
        "label_note": manifest.get("label_note"),
    }


def train_and_test(corpus: Path, level: int, out: Path, allow_unready: bool) -> dict:
    """Fine-tune the adapter and score it on the held-out ladders. Imports the ML stack here only."""
    import gc

    import torch
    from datasets import Dataset
    from peft import LoraConfig, TaskType, get_peft_model
    from transformers import AutoModelForCausalLM, AutoTokenizer, DataCollatorForSeq2Seq, Trainer, TrainingArguments

    decided = plan(corpus, level, allow_unready)
    _, _, config, splits = load_level(corpus, level)
    base_model = decided["foundation_model_id"]
    device = "cuda" if torch.cuda.is_available() else "cpu"
    tokenizer = AutoTokenizer.from_pretrained(base_model)

    def messages(row):
        return [{"role": "system", "content": config["system_prompt"]}, {"role": "user", "content": row["input"]}]

    def encode(row):
        prompt = tokenizer.apply_chat_template(messages(row), tokenize=True, add_generation_prompt=True)
        prompt = prompt["input_ids"] if isinstance(prompt, dict) or hasattr(prompt, "input_ids") else list(prompt)
        answer = tokenizer(row["expected"] + "<|im_end|>", add_special_tokens=False)["input_ids"]
        ids = list(prompt) + list(answer)
        if len(ids) > MAX_LENGTH:
            return None
        return {"input_ids": ids, "attention_mask": [1] * len(ids), "labels": [-100] * len(prompt) + answer}

    encoded = {n: [e for e in (encode(r) for r in splits[n]) if e] for n in ("train", "validation")}
    random.Random(SEED).shuffle(encoded["train"])
    if not encoded["train"]:
        raise NotReady("no training row fits within the length limit")

    model = AutoModelForCausalLM.from_pretrained(
        base_model, torch_dtype=torch.float16 if device == "cuda" else torch.float32
    ).to(device)
    model = get_peft_model(
        model,
        LoraConfig(
            task_type=TaskType.CAUSAL_LM, r=LORA_R, lora_alpha=LORA_ALPHA, lora_dropout=LORA_DROPOUT,
            target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"],
        ),
    )
    trainer = Trainer(
        model=model,
        args=TrainingArguments(
            output_dir=str(out / "checkpoints"), per_device_train_batch_size=1, per_device_eval_batch_size=1,
            gradient_accumulation_steps=8, num_train_epochs=EPOCHS, learning_rate=LEARNING_RATE,
            lr_scheduler_type="cosine", warmup_ratio=0.05, fp16=(device == "cuda"),
            gradient_checkpointing=True, gradient_checkpointing_kwargs={"use_reentrant": False},
            logging_steps=50, eval_strategy="epoch" if encoded["validation"] else "no", save_strategy="no",
            report_to=[], seed=SEED,
        ),
        train_dataset=Dataset.from_list(encoded["train"]),
        eval_dataset=Dataset.from_list(encoded["validation"]) if encoded["validation"] else None,
        data_collator=DataCollatorForSeq2Seq(tokenizer, padding=True, label_pad_token_id=-100),
    )
    trainer.train()
    adapter = out / slug_of(level)
    adapter.mkdir(parents=True, exist_ok=True)
    model.save_pretrained(adapter)
    tokenizer.save_pretrained(adapter)

    model.eval()
    predictions = []
    for row in splits["test"]:
        prompt = tokenizer.apply_chat_template(messages(row), tokenize=False, add_generation_prompt=True)
        ids = tokenizer(prompt, return_tensors="pt").to(device)
        budget = len(tokenizer(row["expected"], add_special_tokens=False)["input_ids"]) + 32
        with torch.no_grad():
            generated = model.generate(**ids, max_new_tokens=budget, do_sample=False)
        predictions.append(tokenizer.decode(generated[0][ids["input_ids"].shape[1]:], skip_special_tokens=True))
    del trainer, model
    gc.collect()
    if device == "cuda":
        torch.cuda.empty_cache()

    rate, failures = score(predictions, splits["test"])
    result = {
        **decided,
        "adapter": str(adapter),
        "verdict": verdict(rate, decided["baselines"], len(splits["test"]), config["gates"], decided["ready"]),
        "failures": failures,
    }
    (out / f"{slug_of(level)}.result.json").write_text(
        json.dumps(result, ensure_ascii=False, sort_keys=True, indent=2) + "\n", encoding="utf-8"
    )
    return result


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--corpus", required=True, type=Path, help="directory written by build_corpus.py")
    parser.add_argument("--level", required=True, type=int)
    parser.add_argument("--out", type=Path, default=Path("build/refinement-adapters"))
    parser.add_argument("--allow-unready", action="store_true", help="train a level the manifest does not call ready")
    parser.add_argument("--dry-run", action="store_true", help="check readiness and print the baselines to beat; load no model")
    args = parser.parse_args(argv)
    try:
        if args.dry_run:
            print(json.dumps(plan(args.corpus, args.level, args.allow_unready), indent=2, sort_keys=True))
            return 0
        result = train_and_test(args.corpus, args.level, args.out, args.allow_unready)
    except NotReady as error:
        print(f"refused: {error}", file=sys.stderr)
        return 2
    print(json.dumps(result["verdict"], indent=2, sort_keys=True))
    return 0 if result["verdict"]["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
