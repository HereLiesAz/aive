"""Writes aive_orchestration_specialists.ipynb from the cell sources below.

The notebook is generated so its code can be reviewed as plain Python in this file. Regenerate with
`python3 tools/orchestration_training/make_notebook.py` after editing.
"""
import json
from pathlib import Path

CELLS = []


def md(text):
    CELLS.append({"cell_type": "markdown", "metadata": {}, "source": text.strip("\n").splitlines(keepends=True)})


def code(text):
    CELLS.append({
        "cell_type": "code",
        "execution_count": None,
        "metadata": {},
        "outputs": [],
        "source": text.strip("\n").splitlines(keepends=True),
    })


md("""
# Aive orchestration specialists: train, gate, export

Trains **one multi-task LoRA** on **Qwen2.5-0.5B-Instruct** covering all nine local orchestration
utilities (each row carries its role's system prompt). It gates the adapter **per role** on held-out and
adversarial splits, merges it, exports ONNX (fp32, then weight-only INT8) in the layout the app loads
(`model.onnx` + `tokenizer.json`, KV-cache inputs, float32 logits), re-gates the **exported INT8
model** per role with ONNX Runtime, and packages one archive plus `catalog.json`. Every role that passes
both gates points at that one artifact: one download, one session in memory.

**Setup**
1. Add the dataset `hereliesaz/aive-orchestration-corpus` (built by
   `tools/orchestration_training/build_kaggle_dataset.sh`).
2. Accelerator: GPU (T4 or P100). Internet: on.
3. Optional upload: add a Kaggle secret `GITHUB_TOKEN` (contents: write on `HereLiesAz/aive`) and set
   `UPLOAD = True` below.

A role that fails a gate is left out of the catalog. The runtime guard falls back to the deterministic
baseline for any role without a released specialist.
""")

code("""
%pip install -q "peft>=0.13" "optimum[onnxruntime]>=1.23" onnx onnx_ir "onnxruntime>=1.22"
""")

code(r'''
import gc, hashlib, json, os, shutil, tarfile, time
from pathlib import Path

BASE_MODEL = "Qwen/Qwen2.5-0.5B-Instruct"
RELEASE_REPOSITORY = "HereLiesAz/aive"
RELEASE_TAG = "orchestration-utilities-v1"
UPLOAD = False                       # True: push the archive and catalog to the GitHub release (needs GITHUB_TOKEN secret)
ROLES = None                         # None = every role in the dataset; or e.g. ["tool-router", "completion-gate"]
ARTIFACT_ID = "orchestration:utilities:int8"
ASSET_NAME = "aive-orchestration-utilities-int8.tar.gz"

MAX_LENGTH = 1024                    # prompt + answer tokens; longer rows are skipped and counted
EPOCHS = 2                           # nine roles' worth of rows per epoch
LEARNING_RATE = 2e-4
BATCH_SIZE = 8
GRAD_ACCUM = 2
LORA_R, LORA_ALPHA, LORA_DROPOUT = 16, 32, 0.05

WORK = Path("/kaggle/working/aive-orchestration")
STATE_FILE = WORK / "state.json"
WORK.mkdir(parents=True, exist_ok=True)

def find_dataset():
    for manifest in Path("/kaggle/input").rglob("manifest.json"):
        data = json.loads(manifest.read_text())
        if data.get("schema") == 1 and "roles" in data:
            return manifest.parent, data
    raise FileNotFoundError("Add the aive-orchestration-corpus dataset to this notebook")

DATA, MANIFEST = find_dataset()
for slug, entry in MANIFEST["roles"].items():
    for key in ("corpus", "config"):
        digest = hashlib.sha256((DATA / entry[key]).read_bytes()).hexdigest()
        assert digest == entry[f"{key}_sha256"], f"{entry[key]} does not match manifest.json"
SLUGS = ROLES or sorted(MANIFEST["roles"])
state = json.loads(STATE_FILE.read_text()) if STATE_FILE.exists() else {}
print(f"dataset {DATA} from commit {MANIFEST['source_commit'][:12]}; roles: {', '.join(SLUGS)}")
''')

code(r'''
import torch
from transformers import AutoModelForCausalLM, AutoTokenizer, DataCollatorForSeq2Seq, Trainer, TrainingArguments

DEVICE = "cuda" if torch.cuda.is_available() else "cpu"
print("training device:", DEVICE)

def load_role(slug):
    config = json.loads((DATA / f"{slug}.config.json").read_text())
    rows = [json.loads(line) for line in (DATA / f"{slug}.jsonl").open()]
    splits = {name: [r for r in rows if r["split"] == name] for name in ("train", "validation", "test", "adversarial")}
    return config, splits

def prompt_messages(config, row):
    return [{"role": "system", "content": config["system_prompt"]}, {"role": "user", "content": row["input"]}]

def json_exact(text, expected):
    """Same rule as tools/specialist_optimization: parsed JSON must be equal."""
    try:
        start, end = text.index("{"), text.rindex("}") + 1
        return json.loads(text[start:end]) == json.loads(expected)
    except ValueError:
        return False

def encode_row(tokenizer, config, row):
    prompt = tokenizer.apply_chat_template(prompt_messages(config, row), tokenize=True, add_generation_prompt=True)
    answer = tokenizer(row["expected"] + "<|im_end|>", add_special_tokens=False)["input_ids"]
    ids = prompt + answer
    if len(ids) > MAX_LENGTH:
        return None
    # Loss on the answer only: the model must not learn to reproduce the prompt.
    return {"input_ids": ids, "attention_mask": [1] * len(ids), "labels": [-100] * len(prompt) + answer}

def save_state():
    STATE_FILE.write_text(json.dumps(state, indent=2))
''')

code(r'''
import random
from datasets import Dataset
from peft import LoraConfig, PeftModel, TaskType, get_peft_model

def train(slugs):
    # One adapter for every role: the system prompt tells the model which contract it is answering.
    tokenizer = AutoTokenizer.from_pretrained(BASE_MODEL)
    encoded, skipped = {"train": [], "validation": []}, {}
    for slug in slugs:
        config, splits = load_role(slug)
        for name in encoded:
            rows = [e for e in (encode_row(tokenizer, config, r) for r in splits[name]) if e]
            if name == "train":
                skipped[slug] = len(splits[name]) - len(rows)
            encoded[name] += rows
    random.Random(8).shuffle(encoded["train"])
    model = AutoModelForCausalLM.from_pretrained(BASE_MODEL, torch_dtype=torch.float32).to(DEVICE)
    model = get_peft_model(model, LoraConfig(
        task_type=TaskType.CAUSAL_LM, r=LORA_R, lora_alpha=LORA_ALPHA, lora_dropout=LORA_DROPOUT,
        target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"],
    ))
    trainer = Trainer(
        model=model,
        args=TrainingArguments(
            output_dir=str(WORK / "checkpoints"), per_device_train_batch_size=BATCH_SIZE,
            per_device_eval_batch_size=BATCH_SIZE, gradient_accumulation_steps=GRAD_ACCUM,
            num_train_epochs=EPOCHS, learning_rate=LEARNING_RATE, lr_scheduler_type="cosine", warmup_ratio=0.05,
            fp16=DEVICE == "cuda", logging_steps=50, eval_strategy="epoch", save_strategy="no", report_to=[], seed=8,
        ),
        train_dataset=Dataset.from_list(encoded["train"]),
        eval_dataset=Dataset.from_list(encoded["validation"]),
        data_collator=DataCollatorForSeq2Seq(tokenizer, padding=True, label_pad_token_id=-100),
    )
    trainer.train()
    model.save_pretrained(WORK / "adapter")
    tokenizer.save_pretrained(WORK / "adapter")
    shutil.rmtree(WORK / "checkpoints", ignore_errors=True)
    del trainer, model; gc.collect()
    if DEVICE == "cuda": torch.cuda.empty_cache()
    return {"trainRows": len(encoded["train"]), "skippedTooLong": skipped}
''')

code(r'''
def score(generate_fn, tokenizer, config, rows):
    hits = 0
    failures = []
    for row in rows:
        text = generate_fn(tokenizer.apply_chat_template(prompt_messages(config, row), tokenize=False, add_generation_prompt=True), row)
        if json_exact(text, row["expected"]):
            hits += 1
        elif len(failures) < 3:
            failures.append({"id": row["id"], "got": text[:300]})
    return (hits / len(rows) if rows else 0.0), failures

def max_new_tokens(tokenizer, row):
    return len(tokenizer(row["expected"], add_special_tokens=False)["input_ids"]) + 32

def gate(slug, generate_fn, tokenizer, label):
    config, splits = load_role(slug)
    test, test_fail = score(generate_fn, tokenizer, config, splits["test"])
    adversarial, adv_fail = score(generate_fn, tokenizer, config, splits["adversarial"])
    gates = config["gates"]
    passed = test >= gates["min_test_score"] and adversarial >= gates["min_adversarial_score"]
    print(f"[{slug}] {label}: test {test:.3f} (>= {gates['min_test_score']}), adversarial {adversarial:.3f} (>= {gates['min_adversarial_score']}) -> {'PASS' if passed else 'FAIL'}")
    for f in test_fail + adv_fail:
        print("   miss", f["id"], f["got"])
    return {"test": test, "adversarial": adversarial, "passed": passed}

def torch_gates(slugs):
    tokenizer = AutoTokenizer.from_pretrained(BASE_MODEL)
    model = AutoModelForCausalLM.from_pretrained(BASE_MODEL, torch_dtype=torch.float16 if DEVICE == "cuda" else torch.float32).to(DEVICE)
    model = PeftModel.from_pretrained(model, WORK / "adapter").eval()
    def generate(prompt, row):
        ids = tokenizer(prompt, return_tensors="pt").to(DEVICE)
        with torch.no_grad():
            out = model.generate(**ids, max_new_tokens=max_new_tokens(tokenizer, row), do_sample=False)
        return tokenizer.decode(out[0][ids["input_ids"].shape[1]:], skip_special_tokens=True)
    results = {slug: gate(slug, generate, tokenizer, "adapter") for slug in slugs}
    del model; gc.collect()
    if DEVICE == "cuda": torch.cuda.empty_cache()
    return results
''')

code(r'''
from optimum.exporters.onnx import main_export
import onnx
from onnxruntime.quantization.matmul_nbits_quantizer import MatMulNBitsQuantizer
from optimum.onnxruntime import ORTModelForCausalLM

QUANT_OPS = ("MatMul",)

def export():
    """Merge the adapter and export fp32 ONNX, then quantize. Each stage resumes if its output exists."""
    merged, fp32, int8 = WORK / "merged", WORK / "onnx_fp32", WORK / "onnx_int8"
    if not (int8 / "model.onnx").is_file() and not (fp32 / "model.onnx").is_file():
        shutil.rmtree(merged, ignore_errors=True); shutil.rmtree(fp32, ignore_errors=True)
        tokenizer = AutoTokenizer.from_pretrained(BASE_MODEL)
        model = AutoModelForCausalLM.from_pretrained(BASE_MODEL, torch_dtype=torch.float32)
        model = PeftModel.from_pretrained(model, WORK / "adapter").merge_and_unload()
        model.save_pretrained(merged, safe_serialization=True)
        tokenizer.save_pretrained(merged)
        del model; gc.collect()
        main_export(str(merged), fp32, task="text-generation-with-past", device="cpu")
        shutil.rmtree(merged, ignore_errors=True)  # free the checkpoint before quantizing (disk)
    if not (int8 / "model.onnx").is_file():
        shutil.rmtree(int8, ignore_errors=True)
        int8.mkdir(parents=True)
        for f in fp32.iterdir():
            if f.is_file() and not f.name.startswith("model.onnx"):
                shutil.copy2(f, int8 / f.name)
        # Weight-only INT8 (MatMulNBits): weights are quantized, activations and logits stay float32.
        # Dynamic INT8 (quantized activations) wrecks these small models: in a probe it took a model
        # scoring 2/5 in fp32 to 0/5, with or without the LM head excluded; weight-only INT8 kept 2/5.
        model = onnx.load(str(fp32 / "model.onnx"))
        quantizer = MatMulNBitsQuantizer(
            model, bits=8, block_size=32, is_symmetric=True, accuracy_level=4, op_types_to_quantize=QUANT_OPS,
        )
        quantizer.process()
        quantizer.model.save_model_to_file(str(int8 / "model.onnx"), use_external_data_format=True)
        del model, quantizer; gc.collect()
    assert (int8 / "model.onnx").is_file() and (int8 / "tokenizer.json").is_file(), "app needs model.onnx + tokenizer.json"
    shutil.rmtree(fp32, ignore_errors=True)
    return int8

def onnx_gates(int8, slugs):
    """Gate the exact artifact the app will run, per role, on CPU like a device."""
    tokenizer = AutoTokenizer.from_pretrained(int8)
    model = ORTModelForCausalLM.from_pretrained(int8, use_cache=True, use_io_binding=False, provider="CPUExecutionProvider")
    def generate(prompt, row):
        ids = tokenizer(prompt, return_tensors="pt")
        out = model.generate(**ids, max_new_tokens=max_new_tokens(tokenizer, row), do_sample=False)
        return tokenizer.decode(out[0][ids["input_ids"].shape[1]:], skip_special_tokens=True)
    results = {slug: gate(slug, generate, tokenizer, "onnx-int8") for slug in slugs}
    del model; gc.collect()
    return results
''')

code(r'''
ASSETS = WORK / "release-assets"
ASSETS.mkdir(exist_ok=True)

def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(8 << 20), b""):
            h.update(chunk)
    return h.hexdigest()

def package(int8, passing, scores):
    """One archive; every passing role's catalog entry points at it."""
    configs = {slug: load_role(slug)[0] for slug in passing}
    (int8 / "model-manifest.json").write_text(json.dumps({
        "artifactId": ARTIFACT_ID,
        "foundationModelId": BASE_MODEL,
        "format": "onnx",
        "precision": "int8",
        "weightQuantization": "MatMulNBits 8-bit, block 32, symmetric",
        "sourceCommit": MANIFEST["source_commit"],
        "roles": {
            slug: {
                "specialistId": config["specialist_id"],
                "systemPromptSha256": hashlib.sha256(config["system_prompt"].encode()).hexdigest(),
                "corpusSha256": MANIFEST["roles"][slug]["corpus_sha256"],
                "scores": scores[slug],
            }
            for slug, config in configs.items()
        },
    }, indent=2))
    asset = ASSETS / ASSET_NAME
    with tarfile.open(asset, "w:gz", compresslevel=6) as tar:
        for p in sorted(int8.rglob("*")):
            if p.is_file():
                tar.add(p, arcname=p.relative_to(int8))
    artifact = {
        "logicalArtifactId": ARTIFACT_ID,
        "foundationModelId": BASE_MODEL,
        "releaseRepository": RELEASE_REPOSITORY,
        "releaseTag": RELEASE_TAG,
        "assetName": asset.name,
        "sha256": sha256(asset),
        "format": "onnx",
        "precision": "int8",
        "kind": "MergedModel",
        "capabilities": ["orchestration-utility"] + sorted(passing),
    }
    catalog = {
        "releaseRepository": RELEASE_REPOSITORY,
        "releaseTag": RELEASE_TAG,
        "specialists": [
            {"specialistId": configs[slug]["specialist_id"], "mergedVariants": [artifact], "scores": scores[slug]}
            for slug in sorted(passing)
        ],
    }
    (ASSETS / "catalog.json").write_text(json.dumps(catalog, indent=2))
    return catalog
''')

code(r'''
import requests

def upload(paths):
    from kaggle_secrets import UserSecretsClient
    token = UserSecretsClient().get_secret("GITHUB_TOKEN")
    api = f"https://api.github.com/repos/{RELEASE_REPOSITORY}"
    headers = {"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json"}
    r = requests.get(f"{api}/releases/tags/{RELEASE_TAG}", headers=headers)
    if r.status_code == 404:
        r = requests.post(f"{api}/releases", headers=headers, json={
            "tag_name": RELEASE_TAG, "name": RELEASE_TAG, "prerelease": True,
            "body": "Local orchestration specialists: one multi-task Qwen2.5-0.5B, ONNX weight-only INT8. See catalog.json.",
        })
    r.raise_for_status()
    release = r.json()
    existing = {a["name"]: a["id"] for a in release.get("assets", [])}
    for path in paths:
        if path.name in existing:
            requests.delete(f"{api}/releases/assets/{existing[path.name]}", headers=headers).raise_for_status()
        with open(path, "rb") as f:
            up = requests.post(
                f"https://uploads.github.com/repos/{RELEASE_REPOSITORY}/releases/{release['id']}/assets",
                params={"name": path.name}, data=f,
                headers={**headers, "Content-Type": "application/octet-stream"}, timeout=3600,
            )
        up.raise_for_status()
        print("uploaded", path.name)
''')

code(r'''
started = time.time()
if not state.get("trained"):
    state["train"] = train(SLUGS); state["trained"] = True; save_state()
if "adapterGates" not in state:
    state["adapterGates"] = torch_gates(SLUGS); save_state()
candidates = [s for s in SLUGS if state["adapterGates"][s]["passed"]]
print("adapter passed:", candidates or "none")
if candidates and "onnxGates" not in state:
    int8 = export()
    state["onnxGates"] = onnx_gates(int8, candidates); save_state()
passing = [s for s in candidates if state.get("onnxGates", {}).get(s, {}).get("passed")]
if passing:
    scores = {s: {"adapter": state["adapterGates"][s], "onnxInt8": state["onnxGates"][s]} for s in passing}
    package(WORK / "onnx_int8", passing, scores)
    print(f"packaged {ASSET_NAME} for {len(passing)} roles: {', '.join(passing)}")
else:
    print("no role passed both gates; nothing packaged")
print(f"done in {(time.time() - started) / 60:.1f} min")
''')

code(r'''
# Upload the archive and the catalog. Commit catalog.json to the repo afterwards so the app can
# register the released specialists (tools/orchestration_training/README.md).
if UPLOAD:
    upload(sorted(ASSETS.glob("*.tar.gz")) + [ASSETS / "catalog.json"])
else:
    print("UPLOAD is False; assets are in", ASSETS)
''')

notebook = {
    "cells": CELLS,
    "metadata": {
        "kernelspec": {"display_name": "Python 3", "language": "python", "name": "python3"},
        "language_info": {"name": "python"},
        "kaggle": {"accelerator": "gpu", "isInternetEnabled": True, "isGpuEnabled": True},
    },
    "nbformat": 4,
    "nbformat_minor": 5,
}
target = Path(__file__).with_name("aive_orchestration_specialists.ipynb")
target.write_text(json.dumps(notebook, indent=1) + "\n")
print("wrote", target)
