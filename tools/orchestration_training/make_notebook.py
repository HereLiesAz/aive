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

Builds the nine local orchestration specialists on **Qwen2.5-0.5B-Instruct** in one or both shapes
(`MODE` below). Both ship in the layout the app loads (`model.onnx` + `tokenizer.json`, KV-cache
inputs, float32 logits, weight-only INT8), both are gated **per role** twice (the trained adapter, then
the exported ONNX model on CPU), and both land in one `catalog.json`. The app decides at run time which
shape to use.

- **`multitask`**: one LoRA trained on every role (each row carries its role's system prompt), merged
  into one model. One ~640 MB download, one session, all roles.
- **`adapters`**: one LoRA per role over a shared base. The base is exported once with every LoRA
  weight as a graph **input**, so one ~640 MB base serves all roles and each role adds a ~18 MB adapter.
  Roles train and update independently.
- **`both`** (default): builds both.

---

## Environment & Pipeline Linkage (Local ↔ Cloud ↔ App)

### 1. Credentials Handshake
Configure secrets once:
- **Local**: `~/.kaggle/kaggle.json` (Kaggle Settings → *Create New Token*)
- **Colab**: Secrets (key icon) → `KAGGLE_USERNAME`, `KAGGLE_KEY`, `GITHUB_TOKEN` (`contents:write` on `HereLiesAz/aive`)
- **Kaggle**: Add-ons → Secrets → `GITHUB_TOKEN` (`contents:write` on `HereLiesAz/aive`)

### 2. Operational Loop
1. **Local Mint → Cloud**:
   ```bash
   tools/orchestration_training/build_kaggle_dataset.sh
   kaggle datasets version -p build/kaggle/aive-orchestration-corpus -m "refresh"
   git add tools/orchestration_training/aive-orchestration-corpus.zip && git commit -m "chore: update corpus" && git push
   ```
2. **Cloud GPU Execution (Colab / Kaggle)**:
   - Accelerator: GPU (T4 or P100), Internet: on.
   - Set `UPLOAD = True` below to push packages and `catalog.json` to GitHub Release `orchestration-utilities-v3`.
   - Run all cells.
3. **Cloud → Local Ingestion**:
   ```bash
   curl -LO https://github.com/HereLiesAz/aive/releases/download/orchestration-utilities-v3/catalog.json
   python3 tools/orchestration_training/register_catalog.py catalog.json
   git commit -am "feat: register released orchestration specialists"
   ```

A role that fails a gate is left out of the catalog. The runtime guard falls back to the deterministic
baseline for any role without a released specialist.
""")

code("""
# Kaggle images ship torchao 0.10; current peft refuses to import next to a torchao older than 0.16.
# Some images (Colab) also ship a diffusers that cannot import against the huggingface_hub installed
# below, and optimum's exporter imports diffusers when present. Nothing here uses either, so remove
# both. If peft or optimum was already imported in this session, restart it.
%pip uninstall -y -q torchao diffusers
%pip install -q "peft>=0.13" "optimum[onnxruntime]>=1.23" onnx onnx_ir "onnxruntime>=1.22"
""")

code(r'''
import gc, hashlib, json, os, shutil, tarfile, time
from pathlib import Path

os.environ.setdefault("PYTORCH_ALLOC_CONF", "expandable_segments:True")  # before torch is imported

import importlib.util
if importlib.util.find_spec("diffusers") is not None:
    # optimum's ONNX exporter imports diffusers whenever it is installed, and the image's copy may not
    # import against the huggingface_hub installed above. Fail now, not at the export hours from here.
    raise RuntimeError("diffusers is still installed: run `!pip uninstall -y diffusers`, restart the runtime, and run all cells again")

BASE_MODEL = "Qwen/Qwen2.5-0.5B-Instruct"
RELEASE_REPOSITORY = "HereLiesAz/aive"
RELEASE_TAG = "orchestration-utilities-v3"
UPLOAD = False                       # True: push the archive and catalog to the GitHub release (needs GITHUB_TOKEN secret)
ROLES = None                         # None = every role in the dataset; or e.g. ["tool-router", "completion-gate"]
MODE = "both"                        # "multitask", "adapters" or "both"
ARTIFACT_ID = "orchestration:utilities:v3:int8"
ASSET_NAME = "aive-orchestration-utilities-int8.tar.gz"
BASE_ARTIFACT_ID = "orchestration:base:v3:int8"
BASE_ASSET_NAME = "aive-orchestration-base-int8.tar.gz"
ADAPTER_VERSION = "v3"
assert MODE in ("multitask", "adapters", "both")

MAX_LENGTH = 1024                    # prompt + answer tokens; longer rows are skipped and counted
EPOCHS = 2                           # nine roles' worth of rows per epoch
# A per-role adapter sees one role's rows (~1400), so two epochs are ~175 optimizer steps. That was
# too few: Agent Router adapters picked ineligible candidates, dropped the fallback and preferred the
# cheaper agent, while the multitask model (nine roles' rows) passed. Adapters train at least this
# many optimizer steps.
ADAPTER_MIN_STEPS = 700
LEARNING_RATE = 2e-4
# Per-device batch 4 x 4 accumulation keeps 16 rows per optimizer step on one GPU. Batch 8 ran a
# 15 GB T4 out of memory: the logits alone are 8 x 1024 x 151936 floats (~5 GB).
BATCH_SIZE = 4
GRAD_ACCUM = 4
LORA_R, LORA_ALPHA, LORA_DROPOUT = 16, 32, 0.05

WORK = Path("/kaggle/working/aive-orchestration")
STATE_FILE = WORK / "state.json"
WORK.mkdir(parents=True, exist_ok=True)

# Used when no Kaggle dataset is attached: the same corpus, committed next to this notebook.
CORPUS_URLS = [
    f"https://raw.githubusercontent.com/HereLiesAz/aive/{ref}/tools/orchestration_training/aive-orchestration-corpus.zip"
    for ref in ("main", "claude/amazing-fermi-3o92qn")
]

def find_dataset():
    for root in (Path("/kaggle/input"), WORK / "corpus"):
        for manifest in root.rglob("manifest.json") if root.exists() else []:
            data = json.loads(manifest.read_text())
            # Both corpora may be attached; take the one that holds the requested roles.
            if data.get("schema") == 1 and "roles" in data and set(ROLES or []) <= set(data["roles"]):
                return manifest.parent, data
    return None

def download_corpus():
    import io, urllib.request, zipfile
    for url in CORPUS_URLS:
        try:
            payload = urllib.request.urlopen(url, timeout=60).read()
        except Exception as failure:
            print(f"  {url}: {failure}")
            continue
        zipfile.ZipFile(io.BytesIO(payload)).extractall(WORK / "corpus")
        print(f"downloaded corpus from {url}")
        return
    raise FileNotFoundError("No dataset attached and the corpus download failed; attach aive-orchestration-corpus or turn on internet")

found = find_dataset()
if found is None:
    download_corpus()
    found = find_dataset()

DATA, MANIFEST = found
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
import math
import random
from datasets import Dataset
from peft import LoraConfig, PeftModel, TaskType, get_peft_model

def lora_config():
    """Every adapter and the shared base use this shape: an adapter only runs on a base exported with it."""
    return LoraConfig(
        task_type=TaskType.CAUSAL_LM, r=LORA_R, lora_alpha=LORA_ALPHA, lora_dropout=LORA_DROPOUT,
        target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"],
    )

def train(slugs, out, min_steps=0):
    """Train one LoRA on the rows of [slugs] into [out]. Multi-task: every role; adapters: one role.

    [min_steps] raises the epoch count until training takes at least that many optimizer steps.
    """
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
    # Trainer spreads each batch over every visible GPU (Kaggle's T4 x2), so count them.
    devices = max(1, torch.cuda.device_count())
    steps_per_epoch = max(1, math.ceil(len(encoded["train"]) / (BATCH_SIZE * GRAD_ACCUM * devices)))
    epochs = max(EPOCHS, math.ceil(min_steps / steps_per_epoch))
    model = AutoModelForCausalLM.from_pretrained(BASE_MODEL, torch_dtype=torch.float32).to(DEVICE)
    # Recompute activations in the backward pass instead of keeping them: long rows fit in memory.
    model.gradient_checkpointing_enable(gradient_checkpointing_kwargs={"use_reentrant": False})
    model.enable_input_require_grads()
    model = get_peft_model(model, lora_config())
    trainer = Trainer(
        model=model,
        args=TrainingArguments(
            output_dir=str(out / "checkpoints"), per_device_train_batch_size=BATCH_SIZE,
            per_device_eval_batch_size=BATCH_SIZE, gradient_accumulation_steps=GRAD_ACCUM,
            num_train_epochs=epochs, learning_rate=LEARNING_RATE, lr_scheduler_type="cosine", warmup_ratio=0.05,
            fp16=DEVICE == "cuda", logging_steps=50, eval_strategy="epoch", save_strategy="no", report_to=[], seed=8,
        ),
        train_dataset=Dataset.from_list(encoded["train"]),
        eval_dataset=Dataset.from_list(encoded["validation"]),
        data_collator=DataCollatorForSeq2Seq(tokenizer, padding=True, label_pad_token_id=-100),
    )
    trainer.train()
    model.save_pretrained(out)
    tokenizer.save_pretrained(out)
    shutil.rmtree(out / "checkpoints", ignore_errors=True)
    del trainer, model; gc.collect()
    if DEVICE == "cuda": torch.cuda.empty_cache()
    return {"trainRows": len(encoded["train"]), "epochs": epochs, "skippedTooLong": skipped}
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
    # An empty split proves nothing: say so instead of scoring it 0 and looking like a bad model.
    empty = [name for name in ("test", "adversarial") if not splits[name]]
    if empty:
        print(f"[{slug}] {label}: no {' or '.join(empty)} rows to judge it on -> FAIL")
        return {"test": 0.0, "adversarial": 0.0, "passed": False, "reason": f"no {' or '.join(empty)} rows"}
    test, test_fail = score(generate_fn, tokenizer, config, splits["test"])
    adversarial, adv_fail = score(generate_fn, tokenizer, config, splits["adversarial"])
    gates = config["gates"]
    passed = test >= gates["min_test_score"] and adversarial >= gates["min_adversarial_score"]
    print(f"[{slug}] {label}: test {test:.3f} (>= {gates['min_test_score']}), adversarial {adversarial:.3f} (>= {gates['min_adversarial_score']}) -> {'PASS' if passed else 'FAIL'}")
    for f in test_fail + adv_fail:
        print("   miss", f["id"], f["got"])
    return {"test": test, "adversarial": adversarial, "passed": passed}

def torch_gates(adapter, slugs):
    tokenizer = AutoTokenizer.from_pretrained(BASE_MODEL)
    model = AutoModelForCausalLM.from_pretrained(BASE_MODEL, torch_dtype=torch.float16 if DEVICE == "cuda" else torch.float32).to(DEVICE)
    model = PeftModel.from_pretrained(model, adapter).eval()
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
from onnxruntime.quantization.matmul_nbits_quantizer import DefaultWeightOnlyQuantConfig, MatMulNBitsQuantizer
from optimum.onnxruntime import ORTModelForCausalLM

QUANT_OPS = ("MatMul",)

def quantize_weights(fp32, int8):
    """Weight-only INT8 (MatMulNBits): weights are quantized, activations and logits stay float32.

    Dynamic INT8 (quantized activations) wrecks these small models: in a probe it took a model scoring
    2/5 in fp32 to 0/5, with or without the LM head excluded; weight-only INT8 kept 2/5. MatMuls whose
    weight is a graph input (the LoRA branches in adapters mode) are left alone.
    """
    shutil.rmtree(int8, ignore_errors=True)
    int8.mkdir(parents=True)
    for f in fp32.iterdir():
        if f.is_file() and not f.name.startswith("model.onnx"):
            shutil.copy2(f, int8 / f.name)
    model = onnx.load(str(fp32 / "model.onnx"))
    # bits goes through algo_config: older onnxruntime releases have no `bits` argument on the quantizer.
    config = DefaultWeightOnlyQuantConfig(
        block_size=32, is_symmetric=True, accuracy_level=4, op_types_to_quantize=QUANT_OPS, bits=8,
    )
    quantizer = MatMulNBitsQuantizer(
        model, block_size=32, is_symmetric=True, accuracy_level=4, op_types_to_quantize=QUANT_OPS, algo_config=config,
    )
    quantizer.process()
    quantizer.model.save_model_to_file(str(int8 / "model.onnx"), use_external_data_format=True)
    del model, quantizer; gc.collect()

def export():
    """Merge the multi-task adapter and export fp32 ONNX, then quantize. Each stage resumes."""
    root = WORK / "multitask"
    merged, fp32, int8 = root / "merged", root / "onnx_fp32", root / "onnx_int8"
    if not (int8 / "model.onnx").is_file() and not (fp32 / "model.onnx").is_file():
        shutil.rmtree(merged, ignore_errors=True); shutil.rmtree(fp32, ignore_errors=True)
        tokenizer = AutoTokenizer.from_pretrained(BASE_MODEL)
        model = AutoModelForCausalLM.from_pretrained(BASE_MODEL, torch_dtype=torch.float32)
        model = PeftModel.from_pretrained(model, root / "adapter").merge_and_unload()
        model.save_pretrained(merged, safe_serialization=True)
        tokenizer.save_pretrained(merged)
        del model; gc.collect()
        main_export(str(merged), fp32, task="text-generation-with-past", device="cpu")
        shutil.rmtree(merged, ignore_errors=True)  # free the checkpoint before quantizing (disk)
    if not (int8 / "model.onnx").is_file():
        quantize_weights(fp32, int8)
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
# Adapters mode: one base graph whose LoRA weights are inputs, one small weight file per role.
import numpy as np
import onnxruntime as ort
from onnx import numpy_helper
from optimum.exporters.onnx import onnx_export_from_model
from safetensors.numpy import load_file, save_file

def export_base_with_lora_inputs(any_adapter=None):
    """Export the base with LoRA branches whose A/B weights are graph inputs; returns (int8 dir, mapping).

    The exporter renames and transposes weights, so each LoRA tensor is first filled with a unique
    fingerprint and found again by value. Feeding a role's adapter reproduces that role's merged model;
    feeding zeros reproduces the base (verified to ~1e-4 on logits). Only the adapter's shape matters,
    so without [any_adapter] a fresh one of `lora_config()` stands in.
    """
    root = WORK / "adapters"
    fp32, int8, mapping_file = root / "base_fp32", root / "base_int8", root / "lora-inputs.json"
    if (int8 / "model.onnx").is_file() and mapping_file.is_file():
        return int8, json.loads(mapping_file.read_text())
    shutil.rmtree(fp32, ignore_errors=True)
    tokenizer = AutoTokenizer.from_pretrained(BASE_MODEL)
    base_model = AutoModelForCausalLM.from_pretrained(BASE_MODEL, torch_dtype=torch.float32)
    peft = (PeftModel.from_pretrained(base_model, any_adapter) if any_adapter else get_peft_model(base_model, lora_config())).eval()
    lora = {n: p for n, p in peft.named_parameters() if "lora_" in n}
    fingerprints = {}
    with torch.no_grad():
        for i, (n, p) in enumerate(lora.items()):
            fp = torch.arange(p.numel(), dtype=torch.float32).reshape(p.shape) * 1e-6 + (i + 1) * 1e-2
            p.copy_(fp)
            fingerprints[n] = fp.numpy()
    onnx_export_from_model(peft.get_base_model(), fp32, task="text-generation-with-past", do_validation=False)
    tokenizer.save_pretrained(fp32)
    del peft, lora; gc.collect()
    graph = onnx.load(str(fp32 / "model.onnx"))
    mapping = {}
    for init in graph.graph.initializer:
        if len(init.dims) != 2 or LORA_R not in tuple(init.dims):
            continue
        arr = numpy_helper.to_array(init)
        for n, fp in fingerprints.items():
            if n in mapping:
                continue
            if arr.shape == fp.shape and np.array_equal(arr, fp):
                mapping[n] = {"input": init.name, "transposed": False}; break
            if arr.shape == fp.T.shape and np.array_equal(arr, fp.T):
                mapping[n] = {"input": init.name, "transposed": True}; break
    assert len(mapping) == len(fingerprints), f"found {len(mapping)} of {len(fingerprints)} LoRA tensors in the graph"
    inputs = {m["input"] for m in mapping.values()}
    keep = []
    for init in graph.graph.initializer:
        if init.name in inputs:
            graph.graph.input.append(onnx.helper.make_tensor_value_info(init.name, init.data_type, list(init.dims)))
        else:
            keep.append(init)
    del graph.graph.initializer[:]
    graph.graph.initializer.extend(keep)
    for f in list(fp32.iterdir()):
        if f.name != "model.onnx" and (f.name.startswith(("model.onnx", "onnx__")) or f.suffix == ".data"):
            f.unlink()
    onnx.save(graph, str(fp32 / "model.onnx"), save_as_external_data=True, location="model.onnx.data", all_tensors_to_one_file=True)
    del graph; gc.collect()
    quantize_weights(fp32, int8)
    shutil.rmtree(fp32, ignore_errors=True)
    mapping_file.write_text(json.dumps(mapping, indent=1))
    shutil.copy2(mapping_file, int8 / "lora-inputs.json")
    return int8, mapping

def adapter_tensors(adapter, mapping):
    """A role's LoRA weights keyed by base-graph input name, oriented as the graph expects."""
    state = load_file(str(Path(adapter) / "adapter_model.safetensors"))
    out = {}
    for peft_name, m in mapping.items():
        value = state[peft_name.replace(".default.weight", ".weight")].astype(np.float32)
        out[m["input"]] = np.ascontiguousarray(value.T if m["transposed"] else value)
    return out

def ort_generate(session, model_dir, tokenizer, prompt, max_new, extra):
    """Greedy decoding with the KV cache: the same loop the app runs (DesktopOrtCausalGenerator)."""
    cfg = json.loads((Path(model_dir) / "config.json").read_text())
    heads, dim = cfg["num_key_value_heads"], cfg["hidden_size"] // cfg["num_attention_heads"]
    stop = {tokenizer.convert_tokens_to_ids("<|im_end|>"), tokenizer.convert_tokens_to_ids("<|endoftext|>")}
    names = [i.name for i in session.get_inputs()]
    outputs = [o.name for o in session.get_outputs()]
    past = {n: np.zeros((1, heads, 0, dim), np.float32) for n in names if n.startswith("past_key_values.")}
    step, total, out = tokenizer(prompt)["input_ids"], 0, []
    while True:
        total += len(step)
        feeds = {"input_ids": np.array([step], np.int64), "attention_mask": np.ones((1, total), np.int64), **past, **extra}
        if "position_ids" in names:
            feeds["position_ids"] = np.arange(total - len(step), total, dtype=np.int64)[None]
        result = dict(zip(outputs, session.run(None, feeds)))
        token = int(result["logits"][0, -1].argmax())
        if token in stop or len(out) >= max_new:
            break
        out.append(token)
        past = {n: result[n.replace("past_key_values.", "present.")] for n in past}
        step = [token]
    return tokenizer.decode(out, skip_special_tokens=True)

def adapter_onnx_gates(int8, mapping, slugs):
    """Gate each role on the exact artifacts the app will run: the INT8 base plus that role's adapter."""
    tokenizer = AutoTokenizer.from_pretrained(int8)
    session = ort.InferenceSession(str(int8 / "model.onnx"), providers=["CPUExecutionProvider"])
    results = {}
    for slug in slugs:
        # Round-trip through the fp16 release file so the gate sees what ships.
        extra = {k: v.astype(np.float32) for k, v in load_file(str(save_adapter_file(slug, mapping))).items()}
        generate = lambda prompt, row: ort_generate(session, int8, tokenizer, prompt, max_new_tokens(tokenizer, row), extra)
        results[slug] = gate(slug, generate, tokenizer, "onnx-int8+adapter")
    del session; gc.collect()
    return results

def save_adapter_file(slug, mapping):
    """The per-role release asset: LoRA inputs as fp16 safetensors keyed by base-graph input name."""
    tensors = {k: v.astype(np.float16) for k, v in adapter_tensors(WORK / "adapters" / slug, mapping).items()}
    config, _ = load_role(slug)
    path = ASSETS / f"aive-orchestration-{slug}-lora-{ADAPTER_VERSION}.safetensors"
    save_file(tensors, str(path), metadata={
        "format": "aive-lora-inputs", "base": BASE_ARTIFACT_ID, "specialistId": config["specialist_id"],
        "loraRank": str(LORA_R), "loraAlpha": str(LORA_ALPHA),
    })
    return path
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

def descriptor(logical_id, path, kind, precision, fmt="onnx", adapter_id=None, capabilities=()):
    d = {
        "logicalArtifactId": logical_id, "foundationModelId": BASE_MODEL,
        "releaseRepository": RELEASE_REPOSITORY, "releaseTag": RELEASE_TAG,
        "assetName": path.name, "sha256": sha256(path), "format": fmt, "precision": precision,
        "kind": kind, "capabilities": sorted(capabilities),
    }
    if adapter_id:
        d["adapterId"] = adapter_id
    return d

def tar_dir(directory, asset):
    with tarfile.open(asset, "w:gz", compresslevel=6) as tar:
        for p in sorted(directory.rglob("*")):
            if p.is_file():
                tar.add(p, arcname=p.relative_to(directory))
    return asset

def role_manifest(slugs, scores):
    return {
        slug: {
            "specialistId": load_role(slug)[0]["specialist_id"],
            "systemPromptSha256": hashlib.sha256(load_role(slug)[0]["system_prompt"].encode()).hexdigest(),
            "corpusSha256": MANIFEST["roles"][slug]["corpus_sha256"],
            "scores": scores[slug],
        }
        for slug in slugs
    }

def package_multitask(int8, passing, scores):
    """One merged archive; every passing role points at it."""
    (int8 / "model-manifest.json").write_text(json.dumps({
        "artifactId": ARTIFACT_ID, "foundationModelId": BASE_MODEL, "format": "onnx", "precision": "int8",
        "weightQuantization": "MatMulNBits 8-bit, block 32, symmetric", "sourceCommit": MANIFEST["source_commit"],
        "roles": role_manifest(passing, scores),
    }, indent=2))
    asset = tar_dir(int8, ASSETS / ASSET_NAME)
    return descriptor(ARTIFACT_ID, asset, "MergedModel", "int8", capabilities=["orchestration-utility", *passing])

def package_adapters(base_int8, passing, scores, mapping):
    """One shared base archive plus one fp16 LoRA-inputs file per passing role."""
    (base_int8 / "model-manifest.json").write_text(json.dumps({
        "artifactId": BASE_ARTIFACT_ID, "foundationModelId": BASE_MODEL, "format": "onnx", "precision": "int8",
        "weightQuantization": "MatMulNBits 8-bit, block 32, symmetric (LoRA inputs left float32)",
        "loraInputs": len(mapping), "loraRank": LORA_R, "loraAlpha": LORA_ALPHA,
        "sourceCommit": MANIFEST["source_commit"], "roles": role_manifest(passing, scores),
    }, indent=2))
    base = descriptor(BASE_ARTIFACT_ID, tar_dir(base_int8, ASSETS / BASE_ASSET_NAME), "SharedBase", "int8",
                      capabilities=["orchestration-utility", "lora-inputs"])
    adapters = {}
    for slug in passing:
        specialist = load_role(slug)[0]["specialist_id"]
        adapters[slug] = descriptor(
            f"{specialist}:lora:{ADAPTER_VERSION}", save_adapter_file(slug, mapping), "Adapter", "fp16",
            fmt="safetensors", adapter_id=f"{slug}-{ADAPTER_VERSION}", capabilities=["orchestration-utility", slug],
        )
    return base, adapters

def write_catalog(merged=None, merged_roles=(), base=None, adapters=None, scores=None):
    """One catalog for both shapes: a role lists whichever variants passed; the app chooses at run time."""
    adapters = adapters or {}
    specialists = []
    for slug in sorted(set(merged_roles) | set(adapters)):
        entry = {"specialistId": load_role(slug)[0]["specialist_id"], "scores": (scores or {}).get(slug, {})}
        if slug in merged_roles:
            entry["mergedVariants"] = [merged]
        if slug in adapters:
            entry["sharedBaseVariants"] = [base]
            entry["adapter"] = adapters[slug]
        specialists.append(entry)
    catalog = {"releaseRepository": RELEASE_REPOSITORY, "releaseTag": RELEASE_TAG, "specialists": specialists}
    (ASSETS / "catalog.json").write_text(json.dumps(catalog, indent=2))
    return catalog
''')

code(r'''
import requests

def github_token():
    """GITHUB_TOKEN from Kaggle secrets, Colab secrets, or the environment, whichever this runs on."""
    try:
        from kaggle_secrets import UserSecretsClient
        return UserSecretsClient().get_secret("GITHUB_TOKEN")
    except ImportError:
        pass
    try:
        from google.colab import userdata
        return userdata.get("GITHUB_TOKEN")
    except ImportError:
        pass
    token = os.environ.get("GITHUB_TOKEN")
    if not token:
        raise RuntimeError("No GITHUB_TOKEN: add it as a Kaggle or Colab secret, or set the environment variable")
    return token

def upload(paths):
    token = github_token()
    api = f"https://api.github.com/repos/{RELEASE_REPOSITORY}"
    headers = {"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json"}
    r = requests.get(f"{api}/releases/tags/{RELEASE_TAG}", headers=headers)
    if r.status_code == 404:
        r = requests.post(f"{api}/releases", headers=headers, json={
            "tag_name": RELEASE_TAG, "name": RELEASE_TAG, "prerelease": True,
            "body": "Local orchestration specialists: gated multitask and per-role adapter releases for Qwen2.5-0.5B. See catalog.json.",
        })
    r.raise_for_status()
    release = r.json()
    existing = {a["name"]: a for a in release.get("assets", [])}
    for path in paths:
        digest = "sha256:" + sha256(path)
        current = existing.get(path.name)
        if current is not None:
            if current.get("digest") == digest:
                print("already uploaded", path.name, digest)
                continue
            raise RuntimeError(
                f"{RELEASE_TAG}/{path.name} already exists with a different digest; "
                "release assets are immutable. Bump RELEASE_TAG before publishing a changed artifact."
            )
        with open(path, "rb") as f:
            up = requests.post(
                f"https://uploads.github.com/repos/{RELEASE_REPOSITORY}/releases/{release['id']}/assets",
                params={"name": path.name}, data=f,
                headers={**headers, "Content-Type": "application/octet-stream"}, timeout=3600,
            )
        up.raise_for_status()
        uploaded = up.json()
        if uploaded.get("digest") not in (None, digest):
            raise RuntimeError(f"GitHub reported unexpected digest for {path.name}: {uploaded.get('digest')}")
        print("uploaded", path.name, digest)
''')

code(r'''
started = time.time()
scores, merged, merged_roles, base, adapters = {}, None, [], None, {}

if MODE in ("multitask", "both"):
    mt = state.setdefault("multitask", {})
    if not mt.get("trained"):
        mt["train"] = train(SLUGS, WORK / "multitask" / "adapter"); mt["trained"] = True; save_state()
    if "adapterGates" not in mt:
        mt["adapterGates"] = torch_gates(WORK / "multitask" / "adapter", SLUGS); save_state()
    candidates = [s for s in SLUGS if mt["adapterGates"][s]["passed"]]
    if candidates and "onnxGates" not in mt:
        mt["onnxGates"] = onnx_gates(export(), candidates); save_state()
    merged_roles = [s for s in candidates if mt.get("onnxGates", {}).get(s, {}).get("passed")]
    if merged_roles:
        merged = package_multitask(WORK / "multitask" / "onnx_int8", merged_roles,
                                   {s: {"adapter": mt["adapterGates"][s], "onnxInt8": mt["onnxGates"][s]} for s in merged_roles})
    for s in merged_roles:
        scores.setdefault(s, {})["multitask"] = {"adapter": mt["adapterGates"][s], "onnxInt8": mt["onnxGates"][s]}
    print("multitask released roles:", merged_roles or "none")

if MODE in ("adapters", "both"):
    ad = state.setdefault("adapters", {})
    for slug in SLUGS:
        role = ad.setdefault(slug, {})
        if not role.get("trained"):
            role["train"] = train([slug], WORK / "adapters" / slug, min_steps=ADAPTER_MIN_STEPS); role["trained"] = True; save_state()
        if "adapterGate" not in role:
            role["adapterGate"] = torch_gates(WORK / "adapters" / slug, [slug])[slug]; save_state()
    candidates = [s for s in SLUGS if ad[s]["adapterGate"]["passed"]]
    if candidates:
        base_int8, mapping = export_base_with_lora_inputs(WORK / "adapters" / candidates[0])
        pending = [s for s in candidates if "onnxGate" not in ad[s]]
        for slug, result in adapter_onnx_gates(base_int8, mapping, pending).items():
            ad[slug]["onnxGate"] = result; save_state()
        passing = [s for s in candidates if ad[s]["onnxGate"]["passed"]]
        if passing:
            base, adapters = package_adapters(base_int8, passing, {s: {"adapter": ad[s]["adapterGate"], "onnxInt8": ad[s]["onnxGate"]} for s in passing}, mapping)
        for s in passing:
            scores.setdefault(s, {})["adapters"] = {"adapter": ad[s]["adapterGate"], "onnxInt8": ad[s]["onnxGate"]}
    print("adapter released roles:", sorted(adapters) or "none")

catalog = write_catalog(merged, merged_roles, base, adapters, scores)
print(f"catalog: {len(catalog['specialists'])} roles; assets in {ASSETS}; done in {(time.time() - started) / 60:.1f} min")
''')

code(r'''
# Upload directly only for an explicitly credentialed manual run. Centralized Kaggle execution leaves
# UPLOAD false and publishes the compact output from HereLiesAz/workflows after the kernel completes.
if UPLOAD:
    upload(sorted(ASSETS.glob("*.tar.gz")) + sorted(ASSETS.glob("*.safetensors")) + [ASSETS / "catalog.json"])
else:
    export_root = Path("/kaggle/working/orchestration-v3-output")
    if export_root.exists():
        shutil.rmtree(export_root)
    export_assets = export_root / "assets"
    export_assets.mkdir(parents=True)
    for path in ASSETS.iterdir():
        if path.is_file():
            shutil.copy2(path, export_assets / path.name)
    if STATE_FILE.exists():
        shutil.copy2(STATE_FILE, export_root / "state.json")
    (export_root / "run-summary.json").write_text(json.dumps({
        "releaseTag": RELEASE_TAG,
        "mode": MODE,
        "rolesRequested": SLUGS,
        "releasedRoles": [entry["specialistId"] for entry in catalog["specialists"]],
    }, indent=2) + "\n")
    # Keep Kaggle/GitHub artifact output bounded to the actual release payload.
    for child in WORK.iterdir():
        if child != export_root:
            if child.is_dir():
                shutil.rmtree(child)
            else:
                child.unlink()
    print("compact release output:", export_root)
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
targets = [
    Path(__file__).with_name("aive_orchestration_specialists.ipynb"),
    Path(__file__).resolve().parents[2] / "aive_orchestration_specialists.ipynb",
]
rendered = json.dumps(notebook, indent=1) + "\n"
for target in targets:
    target.write_text(rendered)
    print("wrote", target)


# -------------------------------------------------------------------------------------------------
# One notebook per role. Each family has a base notebook, which exports and publishes the shared INT8
# base with LoRA weights as graph inputs, and one notebook per role, which trains, gates and publishes
# only that role's adapter against the published base. Both reuse the cells above: the config cell is
# rewritten by substitutions (each must apply) and the run and output cells are replaced.
# -------------------------------------------------------------------------------------------------
import zipfile

ROOT = Path(__file__).resolve().parents[2]
GENERATOR_NOTE = "Generated by `tools/orchestration_training/make_notebook.py`; edit that file, not this notebook."

FAMILIES = {
    "orchestration": {
        "title": "orchestration specialist",
        "corpus": "tools/orchestration_training/aive-orchestration-corpus.zip",
        "notebooks": ROOT / "tools/orchestration_training/notebooks",
        "register": "tools/orchestration_training/register_catalog.py",
        "version": "v4",
        "capability": "orchestration-utility",
        "substitutions": [],
    },
    "memory": {
        "title": "memory clerk",
        "corpus": "tools/memory_training/aive-memory-corpus.zip",
        "notebooks": ROOT / "tools/memory_training/notebooks",
        "register": "tools/memory_training/register_catalog.py",
        "version": "v1",
        "capability": "memory-clerk",
        "substitutions": [
            ("MAX_LENGTH = 1024 ", "MAX_LENGTH = 3072 "),
            ("BATCH_SIZE = 4\n", "BATCH_SIZE = 1\n"),
            ("GRAD_ACCUM = 4\n", "GRAD_ACCUM = 16\n"),
            # Memory rows run ~3x longer than orchestration rows: 400 steps is ~6 epochs of one clerk.
            ("ADAPTER_MIN_STEPS = 700\n", "ADAPTER_MIN_STEPS = 400\n"),
            ("tools/orchestration_training/aive-orchestration-corpus.zip", "tools/memory_training/aive-memory-corpus.zip"),
            ("attach aive-orchestration-corpus", "attach aive-memory-corpus"),
        ],
    },
}

ROLE_RUN = r'''
def fetch_base():
    """The published shared base this role's adapter runs on: downloaded, verified and unpacked once."""
    import urllib.request
    root = WORK / "base"
    int8 = root / "int8"
    verified = int8 / ".verified-sha256"
    url = f"https://github.com/{RELEASE_REPOSITORY}/releases/download/{BASE_RELEASE_TAG}"
    try:
        published = json.loads(urllib.request.urlopen(f"{url}/base.json", timeout=60).read())
    except Exception as failure:
        raise RuntimeError(
            f"{BASE_RELEASE_TAG}/base.json is not published ({failure}). "
            "Run this family's base notebook with UPLOAD = True first."
        ) from None
    base = published["base"]
    assert base["logicalArtifactId"] == BASE_ARTIFACT_ID, f"{BASE_RELEASE_TAG} holds {base['logicalArtifactId']}, not {BASE_ARTIFACT_ID}"
    assert (published["loraRank"], published["loraAlpha"]) == (LORA_R, LORA_ALPHA), "the published base was exported for another LoRA shape"
    if not (verified.is_file() and verified.read_text().strip() == base["sha256"]):
        shutil.rmtree(root, ignore_errors=True)
        root.mkdir(parents=True)
        archive = root / base["assetName"]
        with urllib.request.urlopen(f"{url}/{base['assetName']}", timeout=600) as response, open(archive, "wb") as out:
            shutil.copyfileobj(response, out, 8 << 20)
        assert sha256(archive) == base["sha256"], f"{base['assetName']} does not match {BASE_RELEASE_TAG}/base.json"
        with tarfile.open(archive) as tar:
            tar.extractall(int8, **({"filter": "data"} if hasattr(tarfile, "data_filter") else {}))
        archive.unlink()
        verified.write_text(base["sha256"] + "\n")
    return base, int8, json.loads((int8 / "lora-inputs.json").read_text())

started = time.time()
slug = SLUGS[0]
role = state.setdefault("adapters", {}).setdefault(slug, {})
if not role.get("trained"):
    role["train"] = train([slug], WORK / "adapters" / slug, min_steps=ADAPTER_MIN_STEPS); role["trained"] = True; save_state()
if "adapterGate" not in role:
    role["adapterGate"] = torch_gates(WORK / "adapters" / slug, [slug])[slug]; save_state()
base, adapters, scores = None, {}, {}
if role["adapterGate"]["passed"]:
    base, base_int8, mapping = fetch_base()
    if "onnxGate" not in role:
        role["onnxGate"] = adapter_onnx_gates(base_int8, mapping, [slug])[slug]; save_state()
    if role["onnxGate"]["passed"]:
        scores[slug] = {"adapters": {"adapter": role["adapterGate"], "onnxInt8": role["onnxGate"]}}
        specialist = load_role(slug)[0]["specialist_id"]
        adapters[slug] = descriptor(
            f"{specialist}:lora:{ADAPTER_VERSION}", save_adapter_file(slug, mapping), "Adapter", "fp16",
            fmt="safetensors", adapter_id=f"{slug}-{ADAPTER_VERSION}", capabilities=[CAPABILITY, slug],
        )
if not adapters:
    # The ONNX gate writes the adapter file to gate it; a failing one is not a release asset, and
    # publishing an empty catalog would take this role's release tag for nothing.
    for path in ASSETS.glob("*.safetensors"):
        path.unlink()
    if UPLOAD:
        print("nothing passed: not uploading")
        UPLOAD = False
catalog = write_catalog(base=base, adapters=adapters, scores=scores)
print(f"{slug}: {'released' if adapters else 'not released'}; assets in {ASSETS}; done in {(time.time() - started) / 60:.1f} min")
'''

BASE_RUN = r"""
started = time.time()
base_int8, mapping = export_base_with_lora_inputs()
(base_int8 / "model-manifest.json").write_text(json.dumps({
    "artifactId": BASE_ARTIFACT_ID, "foundationModelId": BASE_MODEL, "format": "onnx", "precision": "int8",
    "weightQuantization": "MatMulNBits 8-bit, block 32, symmetric (LoRA inputs left float32)",
    "loraInputs": len(mapping), "loraRank": LORA_R, "loraAlpha": LORA_ALPHA, "sourceCommit": MANIFEST["source_commit"],
}, indent=2))
base = descriptor(BASE_ARTIFACT_ID, tar_dir(base_int8, ASSETS / BASE_ASSET_NAME), "SharedBase", "int8",
                  capabilities=[CAPABILITY, "lora-inputs"])
# What every role notebook of this family downloads and verifies before gating its adapter.
(ASSETS / "base.json").write_text(json.dumps(
    {"base": base, "loraRank": LORA_R, "loraAlpha": LORA_ALPHA, "loraInputs": len(mapping)}, indent=2,
))
catalog = {"specialists": []}
print(f"{BASE_ARTIFACT_ID}: sha256 {base['sha256'][:12]}; assets in {ASSETS}; done in {(time.time() - started) / 60:.1f} min")
"""


def role_intro(family, spec, slug, tag, base_tag):
    return f"""
# Aive {spec['title']}: `{slug}`

Trains one LoRA adapter for **{slug}** on **Qwen2.5-0.5B-Instruct** and gates it twice: the trained
adapter, then the published INT8 shared base with this adapter's weights fed as graph inputs, on CPU
like a device. `catalog.json` lists the role only when both gates pass; a failing role ships nothing.

1. **Base first**: the `{base_tag}` release must exist. Run `base.ipynb` in this folder once with
   `UPLOAD = True`; this notebook downloads the base and verifies its SHA-256.
2. **Train**: run all cells on a GPU with internet on. `UPLOAD = True` publishes the adapter and
   `catalog.json` to the `{tag}` pre-release (needs a `GITHUB_TOKEN` secret with contents write).
   Release assets are immutable; a changed adapter needs a new version.
3. **Register**: `python3 {spec['register']} catalog.json`, then commit. Other roles stay registered.

{GENERATOR_NOTE}
"""


def base_intro(family, spec, tag):
    return f"""
# Aive {spec['title']}s: shared base

Exports **Qwen2.5-0.5B-Instruct** once with every LoRA weight as a graph **input**, quantizes the
weights to INT8 (MatMulNBits; LoRA inputs stay float32), and packages it with `base.json`, which every
{family} role notebook downloads and verifies. Each role then ships only its ~18 MB adapter.

No training: a GPU is not needed. Run all cells with internet on and `UPLOAD = True` to publish the
`{tag}` pre-release (needs a `GITHUB_TOKEN` secret with contents write). Publish it once, before any
role notebook; a changed base needs a new version, and every adapter must be retrained against it.

{GENERATOR_NOTE}
"""


def variant_cells(intro, substitutions, run, output_substitutions=()):
    cells = json.loads(json.dumps(CELLS))
    cells[0]["source"] = intro.strip("\n").splitlines(keepends=True)
    for old, new in substitutions:
        hits = 0
        for cell in cells[1:]:
            text = "".join(cell["source"])
            if old in text:
                hits += text.count(old)
                cell["source"] = text.replace(old, new).splitlines(keepends=True)
        assert hits, f"notebook substitution did not apply: {old!r}"
    run_cell = next(c for c in cells if "".join(c["source"]).startswith("started = time.time()"))
    run_cell["source"] = run.strip("\n").splitlines(keepends=True)
    output = next(c for c in cells if "".join(c["source"]).startswith("# Upload directly only"))
    text = "".join(output["source"])
    for old, new in output_substitutions:
        assert old in text, f"output substitution did not apply: {old!r}"
        text = text.replace(old, new)
    output["source"] = text.splitlines(keepends=True)
    return cells


def config_substitutions(family, spec, tag, roles, body):
    version = spec["version"]
    return [
        ('RELEASE_TAG = "orchestration-utilities-v3"',
         f'RELEASE_TAG = "{tag}"\nBASE_RELEASE_TAG = "{family}-base-{version}"  # the shared base every role runs on'),
        ('ROLES = None                         # None = every role in the dataset; or e.g. ["tool-router", "completion-gate"]',
         f"ROLES = {json.dumps(roles)}"),
        ('MODE = "both"                        # "multitask", "adapters" or "both"',
         f'MODE = "adapters"                    # one adapter per role on the shared base\nCAPABILITY = "{spec["capability"]}"'),
        ('ARTIFACT_ID = "orchestration:utilities:v3:int8"\n', ""),
        ('ASSET_NAME = "aive-orchestration-utilities-int8.tar.gz"\n', ""),
        ('BASE_ARTIFACT_ID = "orchestration:base:v3:int8"', f'BASE_ARTIFACT_ID = "{family}:base:{version}:int8"'),
        ('BASE_ASSET_NAME = "aive-orchestration-base-int8.tar.gz"', f'BASE_ASSET_NAME = "aive-{family}-base-int8.tar.gz"'),
        ('ADAPTER_VERSION = "v3"', f'ADAPTER_VERSION = "{version}"'),
        ('Path("/kaggle/working/aive-orchestration")', f'Path("/kaggle/working/aive-{tag}")'),
        ('f"aive-orchestration-{slug}-lora-{ADAPTER_VERSION}.safetensors"', f'f"aive-{family}-{{slug}}-lora-{{ADAPTER_VERSION}}.safetensors"'),
        ('"Local orchestration specialists: gated multitask and per-role adapter releases for Qwen2.5-0.5B. See catalog.json."',
         json.dumps(body)),
        *spec["substitutions"],
    ]


OUTPUT_DIR = ('"/kaggle/working/orchestration-v3-output"', 'f"/kaggle/working/{RELEASE_TAG}-output"')

for family, spec in FAMILIES.items():
    with zipfile.ZipFile(ROOT / spec["corpus"]) as corpus:
        manifest = next(n for n in corpus.namelist() if n.endswith("/manifest.json"))
        slugs = sorted(json.loads(corpus.read(manifest))["roles"])
    out = spec["notebooks"]
    out.mkdir(parents=True, exist_ok=True)
    for stale in out.glob("*.ipynb"):
        stale.unlink()
    version = spec["version"]
    base_tag = f"{family}-base-{version}"
    files = {
        "base.ipynb": variant_cells(
            base_intro(family, spec, base_tag),
            config_substitutions(family, spec, base_tag, slugs, f"Aive {family} shared base (INT8, LoRA inputs) for Qwen2.5-0.5B. See base.json."),
            BASE_RUN,
            [OUTPUT_DIR, ('[ASSETS / "catalog.json"]', '[ASSETS / "base.json"]')],
        ),
    }
    for slug in slugs:
        tag = f"{family}-{slug}-{version}"
        files[f"{slug}.ipynb"] = variant_cells(
            role_intro(family, spec, slug, tag, base_tag),
            config_substitutions(family, spec, tag, [slug], f"Aive {spec['title']} {slug}: a gated LoRA adapter on {base_tag}. See catalog.json."),
            ROLE_RUN,
            [OUTPUT_DIR],
        )
    for name, cells in files.items():
        (out / name).write_text(json.dumps({**notebook, "cells": cells}, indent=1) + "\n")
    print(f"wrote {len(files)} notebooks to {out.relative_to(ROOT)}")
