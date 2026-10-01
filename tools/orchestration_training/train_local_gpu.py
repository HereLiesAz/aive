import gc
import json
import os
import random
import sys
import zipfile
from pathlib import Path

os.environ["PYTORCH_CUDA_ALLOC_CONF"] = "expandable_segments:True"

import torch
from datasets import Dataset
from peft import LoraConfig, PeftModel, TaskType, get_peft_model
from transformers import (
    AutoModelForCausalLM,
    AutoTokenizer,
    DataCollatorForSeq2Seq,
    Trainer,
    TrainingArguments,
)

BASE_MODEL = "Qwen/Qwen2.5-0.5B-Instruct"
ROLES = ["tool-router", "completion-gate", "escalation-gate"]
EPOCHS = 2
LEARNING_RATE = 2e-4
LORA_R, LORA_ALPHA, LORA_DROPOUT = 16, 32, 0.05
MAX_LENGTH = 512

ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / "build" / "local_training"
CORPUS_DIR = WORK / "corpus" / "aive-orchestration-corpus"
CORPUS_ZIP = ROOT / "tools" / "orchestration_training" / "aive-orchestration-corpus.zip"

WORK.mkdir(parents=True, exist_ok=True)
CORPUS_DIR.mkdir(parents=True, exist_ok=True)

# Unpack corpus if needed
if not (CORPUS_DIR / "manifest.json").exists():
    print(f"Extracting {CORPUS_ZIP} to {CORPUS_DIR}...")
    with zipfile.ZipFile(CORPUS_ZIP, "r") as z:
        z.extractall(CORPUS_DIR)

DEVICE = "cuda" if torch.cuda.is_available() else "cpu"
print(f"Local Training Device: {DEVICE}")
if DEVICE == "cuda":
    print(f"GPU: {torch.cuda.get_device_name(0)} (VRAM: {torch.cuda.get_device_properties(0).total_memory / (1024**3):.1f} GB)")


def load_role(slug):
    config = json.loads((CORPUS_DIR / f"{slug}.config.json").read_text())
    rows = [json.loads(line) for line in (CORPUS_DIR / f"{slug}.jsonl").open(encoding="utf-8")]
    splits = {name: [r for r in rows if r["split"] == name] for name in ("train", "validation", "test", "adversarial")}
    return config, splits


def prompt_messages(config, row):
    return [{"role": "system", "content": config["system_prompt"]}, {"role": "user", "content": row["input"]}]


def json_exact(text, expected):
    try:
        start, end = text.index("{"), text.rindex("}") + 1
        return json.loads(text[start:end]) == json.loads(expected)
    except (ValueError, json.JSONDecodeError):
        return False


def encode_row(tokenizer, config, row):
    res = tokenizer.apply_chat_template(prompt_messages(config, row), tokenize=True, add_generation_prompt=True)
    prompt = res["input_ids"] if (hasattr(res, "input_ids") or isinstance(res, dict)) else list(res)
    answer = tokenizer(row["expected"] + "<|im_end|>", add_special_tokens=False)["input_ids"]
    ids = list(prompt) + list(answer)
    if len(ids) > MAX_LENGTH:
        return None
    return {"input_ids": ids, "attention_mask": [1] * len(ids), "labels": [-100] * len(prompt) + answer}


def train_role(slug, out_dir):
    print(f"\n==========================================")
    print(f"Starting training for role: {slug}")
    print(f"==========================================")
    tokenizer = AutoTokenizer.from_pretrained(BASE_MODEL)
    config, splits = load_role(slug)

    encoded = {name: [e for e in (encode_row(tokenizer, config, r) for r in splits[name]) if e]
               for name in ("train", "validation")}
    random.Random(8).shuffle(encoded["train"])
    print(f"Train rows: {len(encoded['train'])}, Validation rows: {len(encoded['validation'])}")

    model = AutoModelForCausalLM.from_pretrained(
        BASE_MODEL,
        torch_dtype=torch.float16 if DEVICE == "cuda" else torch.float32,
    ).to(DEVICE)

    model = get_peft_model(
        model,
        LoraConfig(
            task_type=TaskType.CAUSAL_LM,
            r=LORA_R,
            lora_alpha=LORA_ALPHA,
            lora_dropout=LORA_DROPOUT,
            target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"],
        ),
    )

    trainer = Trainer(
        model=model,
        args=TrainingArguments(
            output_dir=str(out_dir / "checkpoints"),
            per_device_train_batch_size=2,
            per_device_eval_batch_size=2,
            gradient_accumulation_steps=4,
            num_train_epochs=EPOCHS,
            learning_rate=LEARNING_RATE,
            lr_scheduler_type="cosine",
            warmup_ratio=0.05,
            fp16=(DEVICE == "cuda"),
            gradient_checkpointing=True,
            gradient_checkpointing_kwargs={"use_reentrant": False},
            logging_steps=50,
            eval_strategy="epoch",
            save_strategy="no",
            report_to=[],
            seed=8,
        ),
        train_dataset=Dataset.from_list(encoded["train"]),
        eval_dataset=Dataset.from_list(encoded["validation"]),
        data_collator=DataCollatorForSeq2Seq(tokenizer, padding=True, label_pad_token_id=-100),
    )

    trainer.train()
    out_dir.mkdir(parents=True, exist_ok=True)
    model.save_pretrained(out_dir)
    tokenizer.save_pretrained(out_dir)
    print(f"Saved adapter for {slug} to {out_dir}")

    del trainer, model
    gc.collect()
    if DEVICE == "cuda":
        torch.cuda.empty_cache()


def score(generate_fn, tokenizer, config, rows):
    hits = 0
    failures = []
    for row in rows:
        text = generate_fn(tokenizer.apply_chat_template(prompt_messages(config, row), tokenize=False, add_generation_prompt=True), row)
        if json_exact(text, row["expected"]):
            hits += 1
        elif len(failures) < 3:
            failures.append({"id": row["id"], "got": text[:200]})
    return (hits / len(rows) if rows else 0.0), failures


def gate(slug, adapter_dir):
    config, splits = load_role(slug)
    tokenizer = AutoTokenizer.from_pretrained(BASE_MODEL)
    model = AutoModelForCausalLM.from_pretrained(
        BASE_MODEL,
        torch_dtype=torch.float16 if DEVICE == "cuda" else torch.float32,
    ).to(DEVICE)
    model = PeftModel.from_pretrained(model, adapter_dir).eval()

    def generate(prompt, row):
        ids = tokenizer(prompt, return_tensors="pt").to(DEVICE)
        max_new = len(tokenizer(row["expected"], add_special_tokens=False)["input_ids"]) + 32
        with torch.no_grad():
            out = model.generate(**ids, max_new_tokens=max_new, do_sample=False)
        return tokenizer.decode(out[0][ids["input_ids"].shape[1]:], skip_special_tokens=True)

    test, test_fail = score(generate, tokenizer, config, splits["test"])
    adv, adv_fail = score(generate, tokenizer, config, splits["adversarial"])
    gates = config["gates"]
    passed = test >= gates["min_test_score"] and adv >= gates["min_adversarial_score"]

    print(f"[{slug}] GATES: test {test:.3f} (>= {gates['min_test_score']}), adv {adv:.3f} (>= {gates['min_adversarial_score']}) -> {'PASS' if passed else 'FAIL'}")
    del model
    gc.collect()
    if DEVICE == "cuda":
        torch.cuda.empty_cache()
    return {"test": test, "adversarial": adv, "passed": passed}


def main():
    results = {}
    for slug in ROLES:
        adapter_path = WORK / "adapters" / slug
        train_role(slug, adapter_path)
        results[slug] = gate(slug, adapter_path)

    summary_file = WORK / "local_results.json"
    summary_file.write_text(json.dumps(results, indent=2))
    print(f"\n==========================================")
    print(f"Local training complete! Results written to {summary_file}")
    for slug, res in results.items():
        print(f"  {slug:25s}: {'PASS' if res['passed'] else 'FAIL'} (test: {res['test']:.3f}, adv: {res['adversarial']:.3f})")
    print(f"==========================================")


if __name__ == "__main__":
    main()
