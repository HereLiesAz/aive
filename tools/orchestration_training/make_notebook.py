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

- **`multitask`** (default): one LoRA trained on every role (each row carries its role's system
  prompt), merged into one model. One ~640 MB download, one session, all roles.
- **`adapters`**: one LoRA per role on the published shared base (`orchestration-base-v4`, from
  `notebooks/base.ipynb`), each released as `orchestration-<role>-v4`. This is the same code as each
  role's own notebook (`notebooks/<role>.ipynb`), which is this notebook with `ROLES` set to one role:
  a role already released is reused, and work left by a role notebook in this session is picked up.
- **`both`**: builds both.

---

## Environment & Pipeline Linkage (Local ↔ Cloud ↔ App)

### 1. Credentials Handshake
Configure secrets once:
- **Local**: `~/.kaggle/kaggle.json` (Kaggle Settings → *Create New Token*)
- **Colab**: Secrets (key icon) → `KAGGLE_USERNAME`, `KAGGLE_KEY`, `GITHUB_TOKEN` (`contents:write` on `HereLiesAz/aive`), each with notebook access on. Work is kept in Google Drive (`MyDrive/aive-<family>`), so a run cut off by a disconnect resumes where it stopped
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
# both. optimum's ONNX exporter (optimum-onnx) imports helpers that transformers 5 removed, so pin
# transformers below 4.58, the newest it supports. If transformers, peft or optimum was already
# imported in this session, restart the kernel after this cell (the files stay) and run all again.
%pip uninstall -y -q torchao diffusers
%pip install -q "transformers>=4.45,<4.58" "peft>=0.13" "optimum[onnxruntime]>=1.23" onnx onnx_ir "onnxruntime>=1.22" "kagglehub>=1.0"
""")

code(r'''
import collections, gc, hashlib, json, os, shutil, tarfile, time
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
UPLOAD = False                       # True: publish to the GitHub releases (needs GITHUB_TOKEN secret)
ROLES = None                         # None = every role in the dataset; or e.g. ["tool-router", "completion-gate"]
MODE = "multitask"                   # "multitask", "adapters" or "both"; per-role adapters come from notebooks/<role>.ipynb
ARTIFACT_ID = "orchestration:utilities:v3:int8"
ASSET_NAME = "aive-orchestration-utilities-int8.tar.gz"
FAMILY, ADAPTER_VERSION = "orchestration", "v4"   # adapters: release <FAMILY>-<role>-<ADAPTER_VERSION> each
BASE_RELEASE_TAG = f"{FAMILY}-base-{ADAPTER_VERSION}"  # the shared base every adapter runs on
BASE_ARTIFACT_ID = f"{FAMILY}:base:{ADAPTER_VERSION}:int8"
BASE_ASSET_NAME = f"aive-{FAMILY}-base-int8.tar.gz"
CAPABILITY = "orchestration-utility"
REUSE_RELEASED = True                # adapters: a role whose release already exists is reused, not retrained
KAGGLE_MIRROR = True                 # with UPLOAD: also publish adapters to Kaggle (KAGGLE_KEY secret)
KAGGLE_MODEL = "hereliesaz/aive-orchestration-specialists"
assert MODE in ("multitask", "adapters", "both")

MAX_LENGTH = 1024                    # prompt + answer tokens; longer rows are skipped and counted
EPOCHS = 2                           # nine roles' worth of rows per epoch
# A per-role adapter sees one role's rows (~1400), so two epochs are ~175 optimizer steps. That was
# too few: Agent Router adapters picked ineligible candidates, dropped the fallback and preferred the
# cheaper agent, while the multitask model (nine roles' rows) passed. Adapters train at least this
# many optimizer steps. 700 overshot: validation loss stopped improving near step 500, and the
# remaining steps only fit the training rows harder. 450 stops near that plateau.
ADAPTER_MIN_STEPS = 450
# Each epoch is judged by the gate's own measure on a validation sample: the worst class's exact-match
# score (overall score for a role without classes). Validation loss picked collapsed clerks: on a
# corpus that is mostly empty proposals, answering empty to everything has the lowest loss, so the
# Category Classifier and Condensation Rewriter stopped at epoch 3 answering one thing. Training stops
# after SELECT_PATIENCE epochs without a better score and keeps the best epoch's weights.
SELECT_ROWS_PER_CLASS = 12
SELECT_PATIENCE = 2
# Training rows are oversampled per class (tag class:<name>) up to the largest class, so a minority
# class (writes, condense) weighs as much in the loss as the majority.
BALANCE_CLASSES = True
# Bumped whenever training changes: a role trained by an older recipe that failed its gate is trained
# again instead of being skipped as done.
TRAIN_RECIPE = 2
LEARNING_RATE = 2e-4
# Kaggle ends a session at 12 hours and loses whatever was mid-flight. A role is started only when
# the time left covers the longest role so far (ROLE_HOURS_ESTIMATE before the first), and training
# itself stops at the deadline. A run that stops early still publishes what passed; run it again.
SESSION_STARTED = time.time()
TIME_BUDGET_HOURS = 11.0
ROLE_HOURS_ESTIMATE = 2.5
GATE_BATCH = 8                       # rows generated together in the GPU gates (length-sorted, left-padded)
# Per-device batch 4 x 4 accumulation keeps 16 rows per optimizer step on one GPU. Batch 8 ran a
# 15 GB T4 out of memory: the logits alone are 8 x 1024 x 151936 floats (~5 GB).
BATCH_SIZE = 4
GRAD_ACCUM = 4
LORA_R, LORA_ALPHA, LORA_DROPOUT = 16, 32, 0.05
# The ONNX gates run on CPU, as a device would, and a full test split takes hours per role there. The
# adapter gate already scored every row on the GPU; the ONNX gate confirms the export with a fixed
# sample of test rows plus every adversarial row. None scores every test row.
ONNX_GATE_TEST_ROWS = 100

def work_root():
    """Where the session's work lives. Colab loses /content when a runtime disconnects, so on Colab the
    work goes to Google Drive: a rerun after a disconnect or the time guard resumes from state.json
    instead of training again. Kaggle keeps /kaggle/working for the session."""
    try:
        from google.colab import drive
    except ImportError:
        return Path("/kaggle/working")
    drive.mount("/content/drive")
    return Path("/content/drive/MyDrive")

WORK = work_root() / f"aive-{FAMILY}"  # shared by every notebook of the family
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
            # Both corpora may be attached; take this family's, which holds the requested roles.
            if (data.get("schema") == 1 and "roles" in data and f"/{FAMILY}/" in data.get("generator", "")
                    and set(ROLES or []) <= set(data["roles"])):
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
# Role notebooks used to keep their work in aive-<family>-<role>-<version>: fold it in, so a role
# trained there is not trained again. Their ONNX gates ran on the published base, recorded with it.
for legacy in sorted(WORK.parent.glob(f"aive-{FAMILY}-*-{ADAPTER_VERSION}")):
    if not (legacy / "state.json").is_file():
        continue
    legacy_base = legacy / "base" / "base.json"
    legacy_sha = json.loads(legacy_base.read_text())["base"]["sha256"] if legacy_base.is_file() else None
    for slug, entry in json.loads((legacy / "state.json").read_text()).get("adapters", {}).items():
        source = legacy / "adapters" / slug
        if not entry.get("trained") or not source.is_dir() or state.get("adapters", {}).get(slug, {}).get("trained"):
            continue
        (WORK / "adapters").mkdir(parents=True, exist_ok=True)
        shutil.rmtree(WORK / "adapters" / slug, ignore_errors=True)
        shutil.move(str(source), str(WORK / "adapters" / slug))
        if "onnxGate" in entry and legacy_sha:
            entry["onnxGate"]["base"] = legacy_sha
        state.setdefault("adapters", {})[slug] = entry
        STATE_FILE.write_text(json.dumps(state, indent=2))
        print(f"{slug}: picked up from {legacy.name}")
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

EMPTY_PROPOSAL = {"sections": [], "nodes": [], "links": []}

def json_exact(text, expected):
    """Same rule as tools/specialist_optimization: parsed JSON must be equal.

    A memory clerk may also decline with DO_NOT_CONDENSE (before any JSON), which the runtime reads
    exactly as the empty proposal, so it matches an empty-proposal label.
    """
    try:
        wanted = json.loads(expected)
    except ValueError:
        return False
    marker = text.find("DO_NOT_CONDENSE")
    if wanted == EMPTY_PROPOSAL and marker >= 0 and ("{" not in text or text.index("{") > marker):
        return True
    try:
        start, end = text.index("{"), text.rindex("}") + 1
        return json.loads(text[start:end]) == wanted
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
from transformers import TrainerCallback

def deadline_left():
    """Seconds left of the session's TIME_BUDGET_HOURS."""
    return SESSION_STARTED + TIME_BUDGET_HOURS * 3600 - time.time()

def generate_batch(model, tokenizer, prompts, max_new):
    """Greedy answers for [prompts], GATE_BATCH at a time, longest first, left-padded."""
    tokenizer.padding_side = "left"
    order = sorted(range(len(prompts)), key=lambda i: -len(prompts[i]))
    answers = [None] * len(prompts)
    for start in range(0, len(order), GATE_BATCH):
        chunk = order[start:start + GATE_BATCH]
        ids = tokenizer([prompts[i] for i in chunk], return_tensors="pt", padding=True).to(model.device)
        with torch.no_grad():
            out = model.generate(**ids, max_new_tokens=max(max_new[i] for i in chunk), do_sample=False,
                                 pad_token_id=tokenizer.pad_token_id)
        for k, i in enumerate(chunk):
            answers[i] = tokenizer.decode(out[k][ids["input_ids"].shape[1]:], skip_special_tokens=True)
    return answers

def class_floor(rows, answers):
    """The gate's measure: the worst class's exact-match score (all rows as one class when untagged)."""
    by_class = {}
    for row, answer in zip(rows, answers):
        by_class.setdefault(row_class(row) or "all", []).append(json_exact(answer, row["expected"]))
    return min(sum(h) / len(h) for h in by_class.values()) if by_class else 0.0

def selection_rows(slugs):
    """A fixed validation sample per role and class: what each epoch is judged on."""
    picked = []
    for slug in slugs:
        config, splits = load_role(slug)
        by_class = {}
        for row in splits["validation"]:
            by_class.setdefault(row_class(row) or "all", []).append(row)
        for name, rows in sorted(by_class.items()):
            rng = random.Random(f"{slug}:{name}:select")
            picked += [(slug, config, r) for r in rng.sample(rows, min(SELECT_ROWS_PER_CLASS, len(rows)))]
    return picked

class SelectByGate(TrainerCallback):
    """After each epoch, score the validation sample with the gate's measure; keep the best weights.

    Stops after SELECT_PATIENCE epochs without a better score, and at the session deadline.
    """
    def __init__(self, model, tokenizer, picked):
        self.model, self.tokenizer, self.picked = model, tokenizer, picked
        self.best, self.best_epoch, self.best_weights, self.stale, self.stopped_epoch, self.out_of_time = None, None, None, 0, None, False
        self.history = []
    def on_step_end(self, args, state, control, **kwargs):
        if deadline_left() <= 0:
            self.out_of_time = True
            control.should_training_stop = True
    def on_epoch_end(self, args, state, control, **kwargs):
        if not self.picked:
            return
        from peft import get_peft_model_state_dict
        was_training = self.model.training
        self.model.eval()
        prompts = [self.tokenizer.apply_chat_template(prompt_messages(c, r), tokenize=False, add_generation_prompt=True) for _, c, r in self.picked]
        max_new = [max_new_tokens(self.tokenizer, r) for _, _, r in self.picked]
        answers = generate_batch(self.model, self.tokenizer, prompts, max_new)
        if was_training:
            self.model.train()
        by_role = {}
        for (slug, _, row), answer in zip(self.picked, answers):
            by_role.setdefault(slug, ([], []))
            by_role[slug][0].append(row); by_role[slug][1].append(answer)
        value = min(class_floor(rows, ans) for rows, ans in by_role.values())
        epoch = round(state.epoch)
        self.history.append({"epoch": epoch, "classFloor": value})
        print(f"epoch {epoch}: worst-class validation score {value:.3f}")
        if self.best is None or value > self.best:
            self.best, self.best_epoch, self.stale = value, epoch, 0
            self.best_weights = {k: v.detach().cpu().clone() for k, v in get_peft_model_state_dict(self.model).items()}
        else:
            self.stale += 1
            if self.stale >= SELECT_PATIENCE and state.epoch < args.num_train_epochs:
                self.stopped_epoch = epoch
                control.should_training_stop = True

def lora_config():
    """Every adapter and the shared base use this shape: an adapter only runs on a base exported with it."""
    return LoraConfig(
        task_type=TaskType.CAUSAL_LM, r=LORA_R, lora_alpha=LORA_ALPHA, lora_dropout=LORA_DROPOUT,
        target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"],
    )

def balance(pairs):
    """Oversample one role's (row, encoded) pairs per class (tag class:<name>) up to its largest class."""
    by_class = {}
    for row, enc in pairs:
        by_class.setdefault(row_class(row) or "all", []).append(enc)
    if len(by_class) < 2:
        return [enc for _, enc in pairs]
    target = max(len(v) for v in by_class.values())
    rng, out = random.Random(8), []
    for name, rows in sorted(by_class.items()):
        out += rows + [rng.choice(rows) for _ in range(target - len(rows))]
        print(f"  class {name}: {len(rows)} rows, oversampled to {target}")
    return out

def train(slugs, out, min_steps=0):
    """Train one LoRA on the rows of [slugs] into [out]. Multi-task: every role; adapters: one role.

    [min_steps] raises the epoch budget until it allows that many optimizer steps; SelectByGate ends
    training sooner once the worst class's validation score stops improving (or the session deadline
    comes), and the best epoch's weights are kept. Returns None when the deadline stopped it before
    any epoch finished: nothing is saved and the role is trained again next session.
    """
    tokenizer = AutoTokenizer.from_pretrained(BASE_MODEL)
    encoded, skipped = {"train": []}, {}
    for slug in slugs:
        config, splits = load_role(slug)
        pairs = [(r, e) for r, e in ((r, encode_row(tokenizer, config, r)) for r in splits["train"]) if e]
        skipped[slug] = len(splits["train"]) - len(pairs)
        encoded["train"] += balance(pairs) if BALANCE_CLASSES else [e for _, e in pairs]
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
    select = SelectByGate(model, tokenizer, selection_rows(slugs))
    trainer = Trainer(
        model=model,
        args=TrainingArguments(
            output_dir=str(out / "checkpoints"), per_device_train_batch_size=BATCH_SIZE,
            gradient_accumulation_steps=GRAD_ACCUM,
            num_train_epochs=epochs, learning_rate=LEARNING_RATE, lr_scheduler_type="cosine", warmup_ratio=0.05,
            fp16=DEVICE == "cuda", logging_steps=50, eval_strategy="no", save_strategy="no", report_to=[], seed=8,
        ),
        train_dataset=Dataset.from_list(encoded["train"]),
        data_collator=DataCollatorForSeq2Seq(tokenizer, padding=True, label_pad_token_id=-100),
        callbacks=[select],
    )
    trainer.train()
    if select.best_weights is None:
        print("Session deadline reached before the first epoch finished: nothing saved")
        del trainer, model; gc.collect()
        if DEVICE == "cuda": torch.cuda.empty_cache()
        return None
    from peft import set_peft_model_state_dict
    set_peft_model_state_dict(model, select.best_weights)
    if select.stopped_epoch:
        print(f"Worst-class score levelled off: stopped after epoch {select.stopped_epoch} of {epochs}")
    if select.out_of_time:
        print("Session deadline reached: stopped training early")
    print(f"kept epoch {select.best_epoch} (worst-class validation score {select.best:.3f})")
    model.save_pretrained(out)
    tokenizer.save_pretrained(out)
    shutil.rmtree(out / "checkpoints", ignore_errors=True)
    del trainer, model; gc.collect()
    if DEVICE == "cuda": torch.cuda.empty_cache()
    return {"trainRows": len(encoded["train"]), "epochs": epochs, "stoppedAfterEpoch": select.stopped_epoch,
            "bestEpoch": select.best_epoch, "bestClassFloor": select.best, "selection": select.history,
            "outOfTime": select.out_of_time, "recipe": TRAIN_RECIPE, "skippedTooLong": skipped}
''')

code(r'''
def score(generate_fn, tokenizer, config, rows):
    """(score, first failures, every answer): the answers feed the class and shortcut gates.

    [generate_fn] answers one (prompt, row), or, with a `many` attribute, all of them in one call.
    """
    hits, failures = 0, []
    prompts = [tokenizer.apply_chat_template(prompt_messages(config, row), tokenize=False, add_generation_prompt=True) for row in rows]
    if hasattr(generate_fn, "many"):
        answers = generate_fn.many(prompts, rows)
    else:
        answers = [generate_fn(p, row) for p, row in zip(prompts, rows)]
    for row, text in zip(rows, answers):
        if json_exact(text, row["expected"]):
            hits += 1
        elif len(failures) < 3:
            failures.append({"id": row["id"], "got": text[:300]})
    return (hits / len(rows) if rows else 0.0), failures, answers

def max_new_tokens(tokenizer, row):
    return len(tokenizer(row["expected"], add_special_tokens=False)["input_ids"]) + 32

def row_class(row):
    return next((t[len("class:"):] for t in row.get("tags", []) if t.startswith("class:")), None)

def shortcut_answer(name, row, constant):
    """What a collapsed model would answer: copy its input, answer nothing, or one fixed answer."""
    if name == "copy":
        return row.get("copy")
    if name == "empty":
        return json.dumps(EMPTY_PROPOSAL)
    if name == "constant":
        return constant
    raise ValueError(f"unknown shortcut {name}")

def shortcut_gates(slug, label, rows, answers, gates, train_rows):
    """Per-class scores and shortcut baselines over the gated rows ([answers] in row order).

    Sources of the failure modes these catch: a small model fine-tuned on a skewed corpus learns to
    copy its input, ignore it, or always give one answer, and still scores well on the majority.
    - Each class (tag class:<name>) with at least min_class_rows rows must score min_class_score.
    - For each shortcut, on the rows where the shortcut is wrong, the clerk must score
      min_nontrivial_score and give the shortcut's answer at most max_shortcut_rate of the time.
    Returns (passed, report). A config without these gates (orchestration roles) passes.
    """
    report, passed = {"classes": {}, "shortcuts": {}}, True
    if "min_class_score" in gates:
        by_class = {}
        for row, answer in zip(rows, answers):
            by_class.setdefault(row_class(row) or "unclassified", []).append(json_exact(answer, row["expected"]))
        for name, hits in sorted(by_class.items()):
            value = sum(hits) / len(hits)
            judged = len(hits) >= gates.get("min_class_rows", 1)
            ok = value >= gates["min_class_score"] or not judged
            report["classes"][name] = {"score": value, "rows": len(hits), "judged": judged, "passed": ok}
            passed &= ok
            print(f"[{slug}] {label}: class {name}: {value:.3f} on {len(hits)} rows" + ("" if judged else " (too few to judge)") + ("" if ok else " -> FAIL"))
    if gates.get("shortcuts"):
        labels = collections.Counter(r["expected"] for r in train_rows)
        constant = labels.most_common(1)[0][0] if labels else None
        for name in gates["shortcuts"]:
            judged = []
            for row, answer in zip(rows, answers):
                shortcut = shortcut_answer(name, row, constant)
                if shortcut is None or json_exact(shortcut, row["expected"]):
                    continue  # the shortcut is right here (or does not apply): nothing to learn from this row
                judged.append((json_exact(answer, row["expected"]), json_exact(answer, shortcut)))
            baseline = sum(
                1 for r in rows
                if shortcut_answer(name, r, constant) is not None and json_exact(shortcut_answer(name, r, constant), r["expected"])
            ) / max(1, len(rows))
            if len(judged) < gates.get("min_class_rows", 1):
                report["shortcuts"][name] = {"baseline": baseline, "rows": len(judged), "judged": False, "passed": True}
                print(f"[{slug}] {label}: shortcut {name}: baseline {baseline:.3f}; {len(judged)} rows where it is wrong (too few to judge)")
                continue
            nontrivial = sum(hit for hit, _ in judged) / len(judged)
            rate = sum(same for _, same in judged) / len(judged)
            ok = nontrivial >= gates["min_nontrivial_score"] and rate <= gates["max_shortcut_rate"]
            report["shortcuts"][name] = {"baseline": baseline, "rows": len(judged), "nontrivial": nontrivial, "shortcutRate": rate, "judged": True, "passed": ok}
            passed &= ok
            print(f"[{slug}] {label}: shortcut {name}: baseline {baseline:.3f}; where it is wrong ({len(judged)} rows) the clerk scores {nontrivial:.3f} (>= {gates['min_nontrivial_score']}) and answers it {rate:.3f} (<= {gates['max_shortcut_rate']})" + ("" if ok else " -> FAIL"))
    # A clerk that gives one answer to everything collapsed, whatever its score.
    if gates.get("shortcuts") and answers:
        top = collections.Counter(answers).most_common(1)[0][1] / len(answers)
        top_label = collections.Counter(r["expected"] for r in rows).most_common(1)[0][1] / len(rows)
        ok = top <= top_label + gates["max_shortcut_rate"]
        report["collapse"] = {"topAnswerShare": top, "topLabelShare": top_label, "passed": ok}
        passed &= ok
        if not ok:
            print(f"[{slug}] {label}: one answer is {top:.3f} of all answers but only {top_label:.3f} of labels -> FAIL (collapsed)")
    return passed, report

def gate(slug, generate_fn, tokenizer, label, test_rows=None):
    """Score a role's test and adversarial splits. test_rows samples that many test rows (fixed seed)."""
    config, splits = load_role(slug)
    # An empty split proves nothing: say so instead of scoring it 0 and looking like a bad model.
    empty = [name for name in ("test", "adversarial") if not splits[name]]
    if empty:
        print(f"[{slug}] {label}: no {' or '.join(empty)} rows to judge it on -> FAIL")
        return {"test": 0.0, "adversarial": 0.0, "passed": False, "reason": f"no {' or '.join(empty)} rows"}
    tests = splits["test"]
    if test_rows is not None and len(tests) > test_rows:
        tests = random.Random(f"{slug}:onnx-gate").sample(tests, test_rows)
    test, test_fail, test_answers = score(generate_fn, tokenizer, config, tests)
    adversarial, adv_fail, adv_answers = score(generate_fn, tokenizer, config, splits["adversarial"])
    gates = config["gates"]
    passed = test >= gates["min_test_score"] and adversarial >= gates["min_adversarial_score"]
    print(f"[{slug}] {label}: test {test:.3f} on {len(tests)}/{len(splits['test'])} rows (>= {gates['min_test_score']}), adversarial {adversarial:.3f} on {len(splits['adversarial'])} (>= {gates['min_adversarial_score']}) -> {'PASS' if passed else 'FAIL'}")
    for f in test_fail + adv_fail:
        print("   miss", f["id"], f["got"])
    shortcuts_ok, report = shortcut_gates(slug, label, tests + splits["adversarial"], test_answers + adv_answers, gates, splits["train"])
    return {"test": test, "adversarial": adversarial, "testRows": len(tests), "adversarialRows": len(splits["adversarial"]),
            **report, "passed": passed and shortcuts_ok}

def torch_gates(adapter, slugs):
    tokenizer = AutoTokenizer.from_pretrained(BASE_MODEL)
    model = AutoModelForCausalLM.from_pretrained(BASE_MODEL, torch_dtype=torch.float16 if DEVICE == "cuda" else torch.float32).to(DEVICE)
    model = PeftModel.from_pretrained(model, adapter).eval()
    def generate(prompt, row):
        ids = tokenizer(prompt, return_tensors="pt").to(DEVICE)
        with torch.no_grad():
            out = model.generate(**ids, max_new_tokens=max_new_tokens(tokenizer, row), do_sample=False)
        return tokenizer.decode(out[0][ids["input_ids"].shape[1]:], skip_special_tokens=True)
    generate.many = lambda prompts, rows: generate_batch(model, tokenizer, prompts, [max_new_tokens(tokenizer, r) for r in rows])
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
    results = {slug: gate(slug, generate, tokenizer, "onnx-int8", ONNX_GATE_TEST_ROWS) for slug in slugs}
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
        results[slug] = gate(slug, generate, tokenizer, "onnx-int8+adapter", ONNX_GATE_TEST_ROWS)
    del session; gc.collect()
    return results

def save_adapter_file(slug, mapping):
    """The per-role release asset: LoRA inputs as fp16 safetensors keyed by base-graph input name."""
    tensors = {k: v.astype(np.float16) for k, v in adapter_tensors(WORK / "adapters" / slug, mapping).items()}
    config, _ = load_role(slug)
    role_dir = ASSETS / role_tag(slug)
    role_dir.mkdir(parents=True, exist_ok=True)
    path = role_dir / f"aive-{FAMILY}-{slug}-lora-{ADAPTER_VERSION}.safetensors"
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

def role_tag(slug):
    """The release of one role's adapter, shared with that role's own notebook."""
    return f"{FAMILY}-{slug}-{ADAPTER_VERSION}"

def descriptor(logical_id, path, kind, precision, fmt="onnx", adapter_id=None, capabilities=(), tag=None):
    d = {
        "logicalArtifactId": logical_id, "foundationModelId": BASE_MODEL,
        "releaseRepository": RELEASE_REPOSITORY, "releaseTag": tag or RELEASE_TAG,
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

def write_catalog(merged=None, merged_roles=(), base=None, adapters=None, scores=None, into=None, tag=None):
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
    catalog = {"releaseRepository": RELEASE_REPOSITORY, "releaseTag": tag or RELEASE_TAG, "specialists": specialists}
    (into or ASSETS).mkdir(parents=True, exist_ok=True)
    ((into or ASSETS) / "catalog.json").write_text(json.dumps(catalog, indent=2))
    return catalog
''')

code(r'''
import requests

def github_token():
    """GITHUB_TOKEN from Kaggle secrets, Colab secrets, or the environment, whichever this runs on."""
    try:
        from kaggle_secrets import UserSecretsClient
        return UserSecretsClient().get_secret("GITHUB_TOKEN").strip()
    except ImportError:
        pass
    try:
        from google.colab import userdata
        return (userdata.get("GITHUB_TOKEN") or "").strip()
    except ImportError:
        pass
    token = os.environ.get("GITHUB_TOKEN")
    if not token:
        raise RuntimeError("No GITHUB_TOKEN: add it as a Kaggle or Colab secret, or set the environment variable")
    return token

def already_published(paths, tag):
    """True when the public release [tag] already holds every file with the same digest: nothing to
    upload, and no token needed to know it."""
    r = requests.get(f"https://api.github.com/repos/{RELEASE_REPOSITORY}/releases/tags/{tag}",
                     headers={"Accept": "application/vnd.github+json"}, timeout=60)
    if r.status_code != 200:
        return False
    existing = {a["name"]: a.get("digest") for a in r.json().get("assets", [])}
    return all(existing.get(p.name) == "sha256:" + sha256(p) for p in paths)

def upload(paths, tag):
    if already_published(paths, tag):
        print(f"{tag}: already published with identical files; nothing to upload")
        return
    token = github_token()
    api = f"https://api.github.com/repos/{RELEASE_REPOSITORY}"
    headers = {"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json"}
    r = requests.get(f"{api}/releases/tags/{tag}", headers=headers)
    if r.status_code == 404:
        r = requests.post(f"{api}/releases", headers=headers, json={
            "tag_name": tag, "name": tag, "prerelease": True,
            "body": f"Aive {FAMILY}: gated release for Qwen2.5-0.5B. See catalog.json (base.json for a shared base).",
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
                f"{tag}/{path.name} already exists with a different digest; "
                "release assets are immutable. Bump the version before publishing a changed artifact."
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

def secret(name):
    # From the environment, Kaggle secrets or Colab secrets, whichever this runs on.
    if os.environ.get(name):
        return os.environ[name].strip()
    try:
        from kaggle_secrets import UserSecretsClient
        return UserSecretsClient().get_secret(name).strip()
    except Exception:
        pass
    try:
        from google.colab import userdata
        return (userdata.get(name) or "").strip() or None
    except Exception:
        return None

def kaggle_credentials():
    # The KAGGLE_KEY secret: a legacy 32-hex API key (its user is KAGGLE_USERNAME, else the owner of
    # KAGGLE_MODEL) or an API token from Kaggle > Settings > API. Set explicitly, so it wins over any
    # notebook-scoped token. Fails here, before uploading, when there is none.
    import re
    from kagglehub import config
    key = secret("KAGGLE_KEY") or secret("KAGGLE_API_TOKEN")
    if not key:
        raise RuntimeError("No Kaggle credentials: add a KAGGLE_KEY secret with notebook access, or set KAGGLE_MIRROR = False")
    if re.fullmatch(r"[0-9a-f]{32}", key):
        config.set_kaggle_credentials(secret("KAGGLE_USERNAME") or KAGGLE_MODEL.split("/")[0], key)
    else:
        config.set_kaggle_api_token(key)

def upload_and_mirror(paths, tag, variation=None):
    # Publish to the GitHub release [tag], then, given a [variation], the same files as a new version
    # of that variation of the Kaggle model (created private on first upload).
    upload(paths, tag)
    if not (KAGGLE_MIRROR and variation):
        return
    if KAGGLE_MIRROR == "auto" and not (secret("KAGGLE_KEY") or secret("KAGGLE_API_TOKEN")):
        print(f"no KAGGLE_KEY secret: {tag} is not mirrored to Kaggle")
        return
    import kagglehub
    kaggle_credentials()
    mirror = WORK / "kaggle-mirror"
    shutil.rmtree(mirror, ignore_errors=True)
    mirror.mkdir(parents=True)
    for path in paths:
        try:
            os.link(path, mirror / path.name)
        except OSError:
            shutil.copy2(path, mirror / path.name)
    notes = f"{tag}: " + ", ".join(f"{p.name} sha256:{sha256(p)}" for p in paths)
    handle = f"{KAGGLE_MODEL}/onnx/{variation}"
    kagglehub.model_upload(handle, str(mirror), license_name="Apache 2.0", version_notes=notes[:4000])
    print("mirrored to Kaggle model", handle)

def check_base(raw, archive):
    data = json.loads(raw)
    base = data["base"]
    assert base["logicalArtifactId"] == BASE_ARTIFACT_ID, f"found {base['logicalArtifactId']}, not {BASE_ARTIFACT_ID}"
    assert (data["loraRank"], data["loraAlpha"]) == (LORA_R, LORA_ALPHA), "the published base was exported for another LoRA shape"
    assert sha256(archive) == base["sha256"], f"{archive} does not match its base.json"
    return data, raw, archive

def published_base(into):
    # The published base as (base.json, its exact bytes, verified archive): from an attached Kaggle
    # model when there is one, else downloaded from the GitHub release into [into]. None when it is
    # not published yet.
    import urllib.request
    attached = Path("/kaggle/input")
    for manifest in attached.rglob("base.json") if attached.exists() else []:
        raw = manifest.read_bytes()
        base = json.loads(raw).get("base", {})
        archive = manifest.parent / base.get("assetName", "")
        if base.get("logicalArtifactId") == BASE_ARTIFACT_ID and archive.is_file():
            print("base from attached Kaggle model:", manifest.parent)
            return check_base(raw, archive)
    url = f"https://github.com/{RELEASE_REPOSITORY}/releases/download/{BASE_RELEASE_TAG}"
    try:
        raw = urllib.request.urlopen(f"{url}/base.json", timeout=60).read()
    except Exception as failure:
        print(f"{BASE_RELEASE_TAG}/base.json is not available: {failure}")
        return None
    base = json.loads(raw)["base"]
    into.mkdir(parents=True, exist_ok=True)
    archive = into / base["assetName"]
    if not (archive.is_file() and sha256(archive) == base["sha256"]):
        with urllib.request.urlopen(f"{url}/{base['assetName']}", timeout=600) as response, open(archive, "wb") as out:
            shutil.copyfileobj(response, out, 8 << 20)
    print("base from GitHub release", BASE_RELEASE_TAG)
    return check_base(raw, archive)

def fetch_base():
    """The published shared base this role's adapter runs on: verified and unpacked once."""
    root = WORK / "base"
    int8, cached = root / "int8", root / "base.json"
    verified = int8 / ".verified-sha256"
    if cached.is_file() and verified.is_file():
        data = json.loads(cached.read_text())
        if verified.read_text().strip() == data["base"]["sha256"]:
            return data["base"], int8, json.loads((int8 / "lora-inputs.json").read_text())
    found = published_base(root)
    if found is None:
        raise RuntimeError(
            f"{BASE_RELEASE_TAG} is not published. Run this family's base notebook with UPLOAD = True "
            "first, or attach its Kaggle model."
        )
    data, raw, archive = found
    shutil.rmtree(int8, ignore_errors=True)
    with tarfile.open(archive) as tar:
        tar.extractall(int8, **({"filter": "data"} if hasattr(tarfile, "data_filter") else {}))
    if archive.parent == root:
        archive.unlink()
    cached.write_bytes(raw)
    verified.write_text(data["base"]["sha256"] + "\n")
    return data["base"], int8, json.loads((int8 / "lora-inputs.json").read_text())

def role_release(slug):
    """This role's entry in its already-published release, or None when it is not released."""
    import urllib.request
    url = f"https://github.com/{RELEASE_REPOSITORY}/releases/download/{role_tag(slug)}/catalog.json"
    try:
        catalog = json.loads(urllib.request.urlopen(url, timeout=60).read())
    except Exception:
        return None
    specialist = load_role(slug)[0]["specialist_id"]
    return next((e for e in catalog.get("specialists", []) if e["specialistId"] == specialist and e.get("adapter")), None)
''')

code(r'''
started = time.time()
scores, merged, merged_roles, base, adapters, new_roles = {}, None, [], None, {}, []
if UPLOAD == "auto":
    # Publish when the GITHUB_TOKEN secret is there, so a run needs no edit beyond its secrets.
    try:
        UPLOAD = bool(github_token())
    except Exception as missing:
        UPLOAD = False
        print(f"UPLOAD = \"auto\": no GITHUB_TOKEN ({missing}); keeping the release in the output folder")
PUBLISHED = set()  # role releases already uploaded (and mirrored) during this run

def role_release_files(slug):
    """Write [slug]'s one-role release, exactly as its role notebook would publish it; (tag, paths)."""
    role_dir = ASSETS / role_tag(slug)
    write_catalog(base=base, adapters={slug: adapters[slug]}, scores={slug: scores[slug]}, into=role_dir, tag=role_tag(slug))
    return role_tag(slug), sorted(role_dir.glob("*.safetensors")) + [role_dir / "catalog.json"]

def publish_role(slug):
    """Publish one passing role as soon as it passes, so a later failure or timeout never loses it:
    a rerun finds the release and reuses it instead of retraining."""
    if not UPLOAD:
        return
    tag, paths = role_release_files(slug)
    upload_and_mirror(paths, tag, slug)
    PUBLISHED.add(slug)

if MODE in ("multitask", "both"):
    mt = state.setdefault("multitask", {})
    if not mt.get("trained"):
        mt["train"] = train(SLUGS, WORK / "multitask" / "adapter")
        if mt["train"] is None:
            raise RuntimeError("Session deadline reached while training the multitask model; run again")
        mt["trained"] = True; save_state()
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
    # Every adapter runs on the published shared base, the same one each role's own notebook uses, so
    # adapters from any notebook mix in one catalog.
    base, base_int8, mapping = fetch_base()
    ad = state.setdefault("adapters", {})
    role_hours = []
    for slug in SLUGS:
        released = role_release(slug) if REUSE_RELEASED else None
        if released:
            adapters[slug] = released["adapter"]
            scores.setdefault(slug, {}).update(released.get("scores", {}))
            print(f"{slug}: already released as {role_tag(slug)}; reusing it")
            continue
        role = ad.setdefault(slug, {})
        # A role an older recipe trained into a failing gate is trained again, not skipped as done.
        if role.get("trained") and role.get("train", {}).get("recipe") != TRAIN_RECIPE and not role.get("adapterGate", {}).get("passed", True):
            print(f"{slug}: failed its gate under an older training recipe; training again")
            role.clear(); save_state()
        if not role.get("trained"):
            needed = max(role_hours, default=ROLE_HOURS_ESTIMATE) * 3600
            if deadline_left() < needed:
                print(f"{slug}: {deadline_left() / 3600:.1f} h left of the {TIME_BUDGET_HOURS} h budget, a role takes up to {needed / 3600:.1f} h; stopping here. Run again to continue.")
                break
            role_started = time.time()
            result = train([slug], WORK / "adapters" / slug, min_steps=ADAPTER_MIN_STEPS)
            if result is None:
                break
            role["train"] = result; role["trained"] = True; save_state()
            role_hours.append((time.time() - role_started) / 3600 * 1.5)  # training plus both gates
        if "adapterGate" not in role:
            role["adapterGate"] = torch_gates(WORK / "adapters" / slug, [slug])[slug]; save_state()
        if not role["adapterGate"]["passed"]:
            continue
        # A gate run on another base (earlier all-roles runs exported their own) does not count.
        if role.get("onnxGate", {}).get("base") != base["sha256"]:
            role["onnxGate"] = {**adapter_onnx_gates(base_int8, mapping, [slug])[slug], "base": base["sha256"]}; save_state()
        if not role["onnxGate"]["passed"]:
            # The ONNX gate writes the adapter file to gate it; a failing one is not a release asset.
            shutil.rmtree(ASSETS / role_tag(slug), ignore_errors=True)
            continue
        specialist = load_role(slug)[0]["specialist_id"]
        adapters[slug] = descriptor(
            f"{specialist}:lora:{ADAPTER_VERSION}", save_adapter_file(slug, mapping), "Adapter", "fp16",
            fmt="safetensors", adapter_id=f"{slug}-{ADAPTER_VERSION}", capabilities=[CAPABILITY, slug], tag=role_tag(slug),
        )
        scores.setdefault(slug, {})["adapters"] = {"adapter": role["adapterGate"], "onnxInt8": role["onnxGate"]}
        new_roles.append(slug)
        publish_role(slug)
    print("adapter roles:", sorted(adapters) or "none", "| newly released:", new_roles or "none")

# catalog.json lists every passing role in every shape: register it. Each new adapter is also its own
# release with a one-role catalog, exactly as its role notebook would publish it; with UPLOAD, each
# was already published the moment it passed, so only the rest are left for the upload cell.
catalog = write_catalog(merged, merged_roles, base, adapters, scores)
RELEASES = [(RELEASE_TAG, [ASSETS / ASSET_NAME, ASSETS / "catalog.json"], None)] if merged else []
for slug in new_roles:
    tag, paths = role_release_files(slug)
    if slug not in PUBLISHED:
        RELEASES.append((tag, paths, slug))
print(f"catalog: {len(catalog['specialists'])} roles; assets in {ASSETS}; done in {(time.time() - started) / 60:.1f} min")
''')

code(r'''
# Upload directly only for an explicitly credentialed manual run. Centralized Kaggle execution leaves
# UPLOAD false and publishes the compact output from HereLiesAz/workflows after the kernel completes.
if UPLOAD == "auto":
    # Publish when the GITHUB_TOKEN secret is there, so a run needs no edit beyond its secrets.
    try:
        UPLOAD = bool(github_token())
    except Exception as missing:
        UPLOAD = False
        print(f"UPLOAD = \"auto\": no GITHUB_TOKEN ({missing}); keeping the release in the output folder")
if UPLOAD:
    for tag, paths, variation in RELEASES:
        upload_and_mirror(paths, tag, variation)
    if not RELEASES:
        published = globals().get("PUBLISHED")  # the base notebook publishes nothing early
        print("nothing left to upload" + (f" ({len(published)} published as they passed)" if published else ""))
else:
    export_root = Path("/kaggle/working/orchestration-v3-output")
    if export_root.exists():
        shutil.rmtree(export_root)
    export_assets = export_root / "assets"
    export_assets.mkdir(parents=True)
    for path in ASSETS.rglob("*"):
        if path.is_file():
            (export_assets / path.relative_to(ASSETS)).parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, export_assets / path.relative_to(ASSETS))
    if STATE_FILE.exists():
        shutil.copy2(STATE_FILE, export_root / "state.json")
    (export_root / "run-summary.json").write_text(json.dumps({
        "releaseTag": RELEASE_TAG,
        "mode": MODE,
        "rolesRequested": SLUGS,
        "releasedRoles": [entry["specialistId"] for entry in catalog["specialists"]],
    }, indent=2) + "\n")
    # A batch run (Save Version, central) ends with this cell: keep its output bounded to the release
    # payload. An interactive session keeps its work, which every notebook of this family resumes from.
    if os.environ.get("KAGGLE_KERNEL_RUN_TYPE") == "Batch":
        for child in WORK.iterdir():
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
        "kaggle_model": "hereliesaz/aive-orchestration-specialists",
        "substitutions": [],
    },
    "memory": {
        "title": "memory clerk",
        "corpus": "tools/memory_training/aive-memory-corpus.zip",
        "notebooks": ROOT / "tools/memory_training/notebooks",
        "register": "tools/memory_training/register_catalog.py",
        "version": "v1",
        "capability": "memory-clerk",
        "kaggle_model": "hereliesaz/aive-memory-clerks",
        "substitutions": [
            ("MAX_LENGTH = 1024 ", "MAX_LENGTH = 3072 "),
            ("BATCH_SIZE = 4\n", "BATCH_SIZE = 1\n"),
            ("GRAD_ACCUM = 4\n", "GRAD_ACCUM = 16\n"),
            # Memory rows run ~3x longer than orchestration rows: 400 steps is ~6 epochs of one clerk.
            ("ADAPTER_MIN_STEPS = 450\n", "ADAPTER_MIN_STEPS = 400\n"),
            ("tools/orchestration_training/aive-orchestration-corpus.zip", "tools/memory_training/aive-memory-corpus.zip"),
            ("attach aive-orchestration-corpus", "attach aive-memory-corpus"),
            # Without a dataset attached, the corpus comes from main, or this branch before it merges.
            ('for ref in ("main", "claude/amazing-fermi-3o92qn")', 'for ref in ("main", "claude/memory-clerk-corpus")'),
            # No edit beyond secrets: publish when GITHUB_TOKEN is set, mirror when KAGGLE_KEY is.
            ("UPLOAD = False                       # True: publish to the GitHub releases (needs GITHUB_TOKEN secret)",
             'UPLOAD = "auto"                      # "auto": publish when a GITHUB_TOKEN secret is set; True or False to force'),
            ("KAGGLE_MIRROR = True                 # with UPLOAD: also publish adapters to Kaggle (KAGGLE_KEY secret)",
             'KAGGLE_MIRROR = "auto"               # with UPLOAD, "auto": mirror to Kaggle when a KAGGLE_KEY secret is set'),
        ],
    },
}

BASE_RUN = r"""
started = time.time()
found = published_base(ASSETS)
if found:
    # Published already: mirror exactly those bytes. A new export would not match them, and release
    # assets are immutable.
    data, raw, archive = found
    if archive.parent != ASSETS:
        shutil.copy2(archive, ASSETS / archive.name)
    (ASSETS / "base.json").write_bytes(raw)
    base = data["base"]
    print(f"{BASE_RELEASE_TAG} is already published; reusing it, not exporting a new base")
else:
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
RELEASES = [(RELEASE_TAG, [ASSETS / BASE_ASSET_NAME, ASSETS / "base.json"], "base")]
print(f"{BASE_ARTIFACT_ID}: sha256 {base['sha256'][:12]}; assets in {ASSETS}; done in {(time.time() - started) / 60:.1f} min")
"""


def role_intro(family, spec, slug, tag, base_tag):
    return f"""
# Aive {spec['title']}: `{slug}`

Trains one LoRA adapter for **{slug}** on **Qwen2.5-0.5B-Instruct** and gates it twice: the trained
adapter, then the published INT8 shared base with this adapter's weights fed as graph inputs, on CPU
like a device. `catalog.json` lists the role only when both gates pass; a failing role ships nothing.

1. **Base first**: the `{base_tag}` release must exist. Run `base.ipynb` in this folder once with
   `UPLOAD = True`. On Kaggle, attach the `{spec['kaggle_model']}` model (variation `base`) to skip the
   620 MB download; either way the base is checked against its SHA-256.
2. **Train**: run all cells on a GPU with internet on. `UPLOAD = True` publishes the adapter and
   `catalog.json` to the `{tag}` pre-release (`GITHUB_TOKEN` secret with contents write), then, with
   `KAGGLE_MIRROR`, as a new version of the `{spec['kaggle_model']}` model, variation `{slug}`
   (`KAGGLE_KEY` secret). Release assets are immutable; a changed adapter needs
   a new version.
3. **Register**: `python3 {spec['register']} catalog.json`, then commit. Other roles stay registered.

This is the all-roles notebook with `ROLES` set to one role: same code, same work folder
(`/kaggle/working/aive-{family}`). A role already released is reused rather than retrained, and in one
session every {family} notebook resumes from the work the others left.

{GENERATOR_NOTE}
"""


def base_intro(family, spec, tag):
    return f"""
# Aive {spec['title']}s: shared base

Exports **Qwen2.5-0.5B-Instruct** once with every LoRA weight as a graph **input**, quantizes the
weights to INT8 (MatMulNBits; LoRA inputs stay float32), and packages it with `base.json`, which every
{family} role notebook downloads and verifies. Each role then ships only its ~18 MB adapter.

No training: a GPU is not needed. Run all cells with internet on and `UPLOAD = True` to publish the
`{tag}` pre-release (`GITHUB_TOKEN` secret with contents write) and, with `KAGGLE_MIRROR`, the
`{spec['kaggle_model']}` Kaggle model, variation `base` (`KAGGLE_KEY` secret; created
private). Once published, a rerun reuses the published base instead of exporting a new one,
so it can mirror an existing release. A changed base needs a new version, and every adapter must be
retrained against it.

{GENERATOR_NOTE}
"""


def all_intro(family, spec, base_tag):
    return f"""
# Aive {spec['title']}s: every role

Trains, gates and releases every {family} role's LoRA adapter on the published `{base_tag}` shared
base, one after another, in one session. Each passing role is published exactly as its own notebook
(`<role>.ipynb`) would publish it: its own `{family}-<role>-{spec['version']}` release with a one-role
`catalog.json`, and, with `KAGGLE_MIRROR`, its variation of the `{spec['kaggle_model']}` Kaggle model.
A role already released is reused rather than retrained; `catalog.json` in the assets folder lists
every released role, ready to register. Run `base.ipynb` first if `{base_tag}` is not published.

{GENERATOR_NOTE}
"""


def variant_cells(intro, substitutions, run=None, output_substitutions=()):
    """The notebook above with [intro], its config rewritten, and (for the base notebook) its own run cell."""
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
    if run is not None:
        run_cell = next(c for c in cells if "".join(c["source"]).startswith("started = time.time()"))
        run_cell["source"] = run.strip("\n").splitlines(keepends=True)
    output = next(c for c in cells if "".join(c["source"]).startswith("# Upload directly only"))
    text = "".join(output["source"])
    for old, new in output_substitutions:
        assert old in text, f"output substitution did not apply: {old!r}"
        text = text.replace(old, new)
    output["source"] = text.splitlines(keepends=True)
    return cells


def config_substitutions(family, spec, tag, roles):
    return [
        ('RELEASE_TAG = "orchestration-utilities-v3"', f'RELEASE_TAG = "{tag}"'),
        ('ROLES = None                         # None = every role in the dataset; or e.g. ["tool-router", "completion-gate"]',
         f"ROLES = {json.dumps(roles) if roles else 'None'}"),
        ('MODE = "multitask"                   # "multitask", "adapters" or "both"; per-role adapters come from notebooks/<role>.ipynb',
         'MODE = "adapters"                    # adapters on the shared base'),
        ('FAMILY, ADAPTER_VERSION = "orchestration", "v4"', f'FAMILY, ADAPTER_VERSION = "{family}", "{spec["version"]}"'),
        ('CAPABILITY = "orchestration-utility"', f'CAPABILITY = "{spec["capability"]}"'),
        ('KAGGLE_MODEL = "hereliesaz/aive-orchestration-specialists"', f'KAGGLE_MODEL = "{spec["kaggle_model"]}"'),
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
            config_substitutions(family, spec, base_tag, slugs),
            BASE_RUN,
            [OUTPUT_DIR],
        ),
    }
    if family != "orchestration":
        # Orchestration's all-roles notebook is aive_orchestration_specialists.ipynb itself.
        files["all.ipynb"] = variant_cells(
            all_intro(family, spec, base_tag),
            config_substitutions(family, spec, f"{family}-all-{version}", None),
            output_substitutions=[OUTPUT_DIR],
        )
    for slug in slugs:
        tag = f"{family}-{slug}-{version}"
        files[f"{slug}.ipynb"] = variant_cells(
            role_intro(family, spec, slug, tag, base_tag),
            config_substitutions(family, spec, tag, [slug]),
            output_substitutions=[OUTPUT_DIR],
        )
    for name, cells in files.items():
        (out / name).write_text(json.dumps({**notebook, "cells": cells}, indent=1) + "\n")
    print(f"wrote {len(files)} notebooks to {out.relative_to(ROOT)}")
