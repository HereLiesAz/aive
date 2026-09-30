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
   - Set `UPLOAD = True` below to push packages and `catalog.json` to GitHub Release `orchestration-utilities-v1`.
   - Run all cells.
3. **Cloud → Local Ingestion**:
   ```bash
   curl -LO https://github.com/HereLiesAz/aive/releases/download/orchestration-utilities-v1/catalog.json
   python3 tools/orchestration_training/register_catalog.py catalog.json
   git commit -am "feat: register released orchestration specialists"
   ```

A role that fails a gate is left out of the catalog. The runtime guard falls back to the deterministic
baseline for any role without a released specialist.
""")

code("""
# Kaggle images ship torchao 0.10; current peft refuses to import next to a torchao older than 0.16.
# Nothing here uses torchao, so remove it. If peft was already imported in this session, restart it.
%pip uninstall -y -q torchao
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
MODE = "both"                        # "multitask", "adapters" or "both"
ARTIFACT_ID = "orchestration:utilities:int8"
ASSET_NAME = "aive-orchestration-utilities-int8.tar.gz"
BASE_ARTIFACT_ID = "orchestration:base:int8"
BASE_ASSET_NAME = "aive-orchestration-base-int8.tar.gz"
ADAPTER_VERSION = "v1"
assert MODE in ("multitask", "adapters", "both")

MAX_LENGTH = 1024                    # prompt + answer tokens; longer rows are skipped and counted
EPOCHS = 2                           # nine roles' worth of rows per epoch
LEARNING_RATE = 2e-4
BATCH_SIZE = 8
GRAD_ACCUM = 2
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
            if data.get("schema") == 1 and "roles" in data:
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
import random
from datasets import Dataset
from peft import LoraConfig, PeftModel, TaskType, get_peft_model

def train(slugs, out):
    """Train one LoRA on the rows of [slugs] into [out]. Multi-task: every role; adapters: one role."""
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
            output_dir=str(out / "checkpoints"), per_device_train_batch_size=BATCH_SIZE,
            per_device_eval_batch_size=BATCH_SIZE, gradient_accumulation_steps=GRAD_ACCUM,
            num_train_epochs=EPOCHS, learning_rate=LEARNING_RATE, lr_scheduler_type="cosine", warmup_ratio=0.05,
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
from onnxruntime.quantization.matmul_nbits_quantizer import MatMulNBitsQuantizer
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
    quantizer = MatMulNBitsQuantizer(
        model, bits=8, block_size=32, is_symmetric=True, accuracy_level=4, op_types_to_quantize=QUANT_OPS,
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

def export_base_with_lora_inputs(any_adapter):
    """Export the base with LoRA branches whose A/B weights are graph inputs; returns (int8 dir, mapping).

    The exporter renames and transposes weights, so each LoRA tensor is first filled with a unique
    fingerprint and found again by value. Feeding a role's adapter reproduces that role's merged model;
    feeding zeros reproduces the base (verified to ~1e-4 on logits).
    """
    root = WORK / "adapters"
    fp32, int8, mapping_file = root / "base_fp32", root / "base_int8", root / "lora-inputs.json"
    if (int8 / "model.onnx").is_file() and mapping_file.is_file():
        return int8, json.loads(mapping_file.read_text())
    shutil.rmtree(fp32, ignore_errors=True)
    tokenizer = AutoTokenizer.from_pretrained(BASE_MODEL)
    peft = PeftModel.from_pretrained(AutoModelForCausalLM.from_pretrained(BASE_MODEL, torch_dtype=torch.float32), any_adapter).eval()
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
            role["train"] = train([slug], WORK / "adapters" / slug); role["trained"] = True; save_state()
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
# Upload the archive and the catalog. Commit catalog.json to the repo afterwards so the app can
# register the released specialists (tools/orchestration_training/README.md).
if UPLOAD:
    upload(sorted(ASSETS.glob("*.tar.gz")) + sorted(ASSETS.glob("*.safetensors")) + [ASSETS / "catalog.json"])
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
