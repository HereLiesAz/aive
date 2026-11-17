"""Writes app_integration_adapter.ipynb from the cell sources below.

The notebook is generated so its code can be reviewed as plain Python in this file. Regenerate with
`python3 tools/app_integration_training/make_notebook.py` after editing; test_make_notebook.py fails when
the committed notebook differs from this generator's output.
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
# Aive app-integration adapter: train, gate, export, release

Trains **one LoRA adapter** on **Qwen2.5-0.5B-Instruct** so the model works inside The Aive: it follows
the app's prompt layout, obeys acceptance criteria, uses the memory layer's interface (recall gists,
divergence, resolved contrasts, `/remindme`), treats habits as information only, never claims an action
it cannot take, keeps the stable-prefix/dynamic layout that context caching relies on, and answers
`NOT_IN_TEXT` when the text lacks the answer.

The notebook gates the adapter twice and refuses to release when any gate fails:

1. **Gate 1**: the trained PEFT adapter, greedy decoding (`max_new_tokens` 512, the app's limit), on the
   test and adversarial splits.
2. **Gate 2**: the exported ONNX Runtime model on CPU (the published shared INT8 base plus this adapter's
   weights as graph inputs, the layout the app already loads), same gates, and at most a 0.03 drop per
   family against Gate 1.

It then converts a **merged full model** for LiteRT-LM (the preferred runtime) and smoke-tests it.

**What is exported for which runtime.** ONNX Runtime gets a *separate* ~18 MB adapter file on the shared
base. LiteRT-LM gets a *merged full model* (`merge_and_unload()`, then `litert-torch export_hf`): Android's
`LoraConfig(loraPath)` exists in `SessionConfig`, but no converter that writes a separate LiteRT-LM LoRA
file is documented, so none is attempted.

The gates, the scoring and the corpus come from `tools/app_integration_training/` in the cloned commit
(`score.py` is called here, not reimplemented).

---

## The lesson from the retired adapters

The nine orchestration role adapters were retired. Their labels were the deterministic baseline's own
answers and the runtime accepted a model answer only when it matched or was stricter than that baseline, so
a 0.5B model could add nothing but a download. This adapter is only worth shipping if it does something the
prompt alone does not: so it is scored by declarative **checks** per family (not by copying a baseline),
on held-out and adversarial rows, with high bars for the families where a failure is a lie or a corrupted
prompt (F3, F4e, F6c >= 0.95; F7 layout and prefix checks 100%). Passing these gates proves the model
follows the corpus's contract. It does not prove the app benefits on a device.

## Environment & Pipeline Linkage (Local to Cloud to App)

### 1. Credentials
- **Kaggle**: Add-ons, Secrets: `GITHUB_TOKEN` (`contents:write` on `HereLiesAz/aive`), notebook access on.
- **Colab**: Secrets (key icon): `GITHUB_TOKEN`, notebook access on. Work is kept in Google Drive
  (`MyDrive/aive-app-integration`), so a cut-off run resumes from `state.json`.
- Local: the `GITHUB_TOKEN` environment variable. The token is never printed.

### 2. Operational loop
1. **Run** on a GPU (T4 or P100) with Internet on. The first stage builds the corpus itself
   (`BUILD_CORPUS_ON_KAGGLE = True`): it clones the repository at `SOURCE_REF`, makes sure a JDK 21 is there,
   runs the generator through the repo's Gradle wrapper, verifies the files against the manifest's SHA-256,
   and saves `aive-app-integration-corpus.zip` as a notebook output (optional to upload or commit). Set
   `SMOKE = True` first: a 20-row corpus, 20 training steps on 64 rows, and every later cell on tiny splits,
   so the whole pipeline is proven quickly. Then `SMOKE = False`.
   With `BUILD_CORPUS_ON_KAGGLE = False` it uses an attached dataset
   `hereliesaz/aive-app-integration-corpus`, else the zip committed in the repository.
2. **Release**: `UPLOAD = True` publishes the pre-release `app-integration-v1` (adapter, optional merged
   LiteRT-LM file, `catalog.json`, `results.json`).
3. **Register**: the last cell prints the exact commands.

The ONNX shared base (`orchestration-base-v4`) must already be published; if it is not, run
`tools/orchestration_training/notebooks/base.ipynb` once with `UPLOAD = True`.

## Honest limits
Nothing here has been measured on a phone. The corpus defines "works inside The Aive"; a model can pass
every gate and still be worse in the app than the unadapted model. The LiteRT-LM flags and the
`litert_lm` Python API are marked where they could not be verified when this was written.
""")

code("""
# Kaggle images ship torchao 0.10; current peft refuses to import next to a torchao older than 0.16.
# Some images (Colab) also ship a diffusers that cannot import against the huggingface_hub installed
# below, and optimum's exporter imports diffusers when present. Nothing here uses either, so remove
# both. optimum's ONNX exporter (optimum-onnx) imports helpers that transformers 5 removed, so pin
# transformers below 4.58, the newest it supports. If transformers, peft or optimum was already
# imported in this session, restart the kernel after this cell (the files stay) and run all again.
# (litert-torch is installed later into its own virtual environment: it pulls its own torch and
# would fight these pins.)
%pip uninstall -y -q torchao diffusers
%pip install -q "transformers>=4.45,<4.58" "peft>=0.13" "optimum[onnxruntime]>=1.23" onnx onnx_ir "onnxruntime>=1.22" requests
""")

code(r'''
import collections, gc, hashlib, importlib.util, json, math, os, random, shutil, subprocess, sys, tarfile, time
from pathlib import Path

os.environ.setdefault("PYTORCH_ALLOC_CONF", "expandable_segments:True")  # before torch is imported

if importlib.util.find_spec("diffusers") is not None:
    # optimum's ONNX exporter imports diffusers whenever it is installed, and the image's copy may not
    # import against the huggingface_hub installed above. Fail now, not at the export hours from here.
    raise RuntimeError("diffusers is still installed: run `!pip uninstall -y diffusers`, restart the runtime, and run all cells again")

# ---- Switches ----------------------------------------------------------------------------------------
SMOKE = False                        # True: 20 steps on 64 rows, tiny splits, every cell runs; nothing is published
UPLOAD = False                       # True: publish to the GitHub pre-release (needs the GITHUB_TOKEN secret)
EXPORT_ONNX = True                   # Export A: ONNX Runtime shared-base adapter + Gate 2
EXPORT_LITERTLM = True               # Export B: merged full model for LiteRT-LM (litert-torch)

# ---- Identity ----------------------------------------------------------------------------------------
BASE_MODEL = "Qwen/Qwen2.5-0.5B-Instruct"
RELEASE_REPOSITORY = "HereLiesAz/aive"
RELEASE_TAG = "app-integration-v1"
VERSION = "v1"
SPECIALIST_ID = "app-integration:assistant"
ADAPTER_ARTIFACT_ID = f"app-integration:lora:{VERSION}"
CAPABILITY = "app-integration"
CORPUS_DATASET = "hereliesaz/aive-app-integration-corpus"
# The shared ONNX base every adapter of this app runs on (published by the orchestration base notebook).
BASE_FAMILY, BASE_VERSION = "orchestration", "v4"
BASE_RELEASE_TAG = f"{BASE_FAMILY}-base-{BASE_VERSION}"
BASE_ARTIFACT_ID = f"{BASE_FAMILY}:base:{BASE_VERSION}:int8"
ADAPTER_FILE = f"aive-app-integration-lora-{VERSION}.safetensors"
LITERTLM_FILE = f"Qwen2.5-0.5B-Instruct-aive-app-integration_multi-prefill-seq_q8_ekv1280-{VERSION}"  # + extension

# ---- Training recipe (same LoRA shape as the orchestration notebook, so the ONNX base fits) ------------
SEED = 8
LORA_R, LORA_ALPHA, LORA_DROPOUT = 16, 32, 0.05
KV_CACHE_TOKENS = 1280               # the installed LiteRT-LM model's KV cache: prompt + answer must fit
MAX_LENGTH = KV_CACHE_TOKENS         # prompt + answer tokens; longer rows are skipped and counted
MAX_NEW = 512                        # the app's OnDeviceTextAgentProvider.DEFAULT_MAX_NEW_TOKENS
EPOCHS = 5
LEARNING_RATE = 2e-4
# Logits alone are BATCH x 1280 x 151936 floats; 2 x 8 keeps 16 rows per step inside a 15 GB T4
# (not measured on Kaggle: lower BATCH_SIZE to 1 and raise GRAD_ACCUM to 16 on an out-of-memory error).
BATCH_SIZE, GRAD_ACCUM = 2, 8
GATE_BATCH = 8                       # rows generated together in the GPU gates (length-sorted, left-padded)
SELECT_ROWS_PER_FAMILY = 12          # validation rows per family that judge each epoch
SELECT_PATIENCE = 2                  # epochs without a better validation score before training stops
TIME_BUDGET_HOURS = 11.0             # Kaggle ends a session at 12 h; training stops at the deadline and resumes
SESSION_STARTED = time.time()

# ---- Gates beyond score.py's own (score.py holds the per-family thresholds) --------------------------------
ONNX_MAX_DROP = 0.03                 # Gate 2 may lose at most this per family against Gate 1
ONNX_GATE_TEST_ROWS = None           # None scores every test row on CPU; an int samples (fixed seed), slower runs only
LITERT_AGREEMENT_ROWS = 50           # test rows run through the converted LiteRT-LM file
GITHUB_ASSET_LIMIT = 2 * 1024 ** 3   # GitHub release assets must be smaller than 2 GiB

# ---- LiteRT-LM conversion: UNVERIFIED, written from memory of the litert-torch docs ------------------------
# The notebook runs `litert-torch export_hf --help` first and refuses to continue, printing the help text,
# when any flag below is missing from it. Fix these constants, not the cell.
LITERT_PACKAGE = "litert-torch"
LITERT_CLI = ["litert-torch", "export_hf"]
LITERT_QUANTIZATION_RECIPE = "dynamic_wi8_afp32"   # the q8 recipe (8-bit weights, float activations)
LITERT_PREFILL_LENGTHS = "8,64,256,1024"           # multi-prefill signatures; the installed file's set is not known here
LITERT_BUNDLE_FLAG = ("--bundle_litert_lm", "true")  # write a .litertlm bundle (tokenizer and metadata inside)
LITERT_SMOKE_PACKAGES = ["litert-lm", "litert_lm"]   # pip names tried in order for the Python runtime

# ---- Corpus build stage: the notebook builds its own corpus from the repository -----------------------------
BUILD_CORPUS_ON_KAGGLE = True        # True: clone, run the generator through Gradle, verify, zip. False: use an attached
                                     # Kaggle dataset, else the zip committed in the repository
SOURCE_REPOSITORY = "https://github.com/HereLiesAz/aive"
SOURCE_REF = "main"                  # branch, tag or commit to build the corpus (and its score.py) from
CORPUS_ROWS = None                   # regular rows through AIVE_APP_INTEGRATION_ROWS; None keeps the generator's default
JDK_MAJOR = 21
GRADLE_TIMEOUT_HOURS = 2.5           # first run downloads Gradle, the Kotlin toolchain and dependencies
# The ONE place the generator's invocation lives. UNVERIFIED: the Kotlin writer was still being written
# when this was authored (expected form below, after the existing orchestration builder). {out} is the
# directory it must fill with train/validation/test/adversarial .jsonl and manifest.json; fix these three
# constants if the real test class, property or variable name differs.
GENERATOR_GRADLE_ARGS = ["--no-daemon", "--console=plain", ":shared:desktopTest",
                         "--tests", "*AppIntegrationDatasetWriterTest", "-Daive.appIntegration.outDir={out}", "--rerun"]
GENERATOR_ROWS_ENV = "AIVE_APP_INTEGRATION_ROWS"
GENERATOR_EXTRA_ENV = {}             # e.g. {"AIVE_EXPORT_APP_INTEGRATION_DATASETS": "{out}"} if the writer reads an env var
CORPUS_ZIP_NAME = "aive-app-integration-corpus.zip"

if SMOKE:
    UPLOAD = False
    EPOCHS, MAX_NEW, SELECT_ROWS_PER_FAMILY, LITERT_AGREEMENT_ROWS = 5, 96, 4, 8
    SMOKE_STEPS, SMOKE_TRAIN_ROWS, SMOKE_EVAL_ROWS = 20, 64, 16
    CORPUS_ROWS = 20                 # a small corpus through the generator keeps the build stage fast

def work_root():
    """Where the session's work lives. Colab loses /content when a runtime disconnects, so on Colab the
    work goes to Google Drive: a rerun resumes from state.json. Kaggle keeps /kaggle/working."""
    try:
        from google.colab import drive
    except ImportError:
        return Path("/kaggle/working")
    drive.mount("/content/drive")
    return Path("/content/drive/MyDrive")

ROOT = work_root()
WORK = ROOT / ("aive-app-integration-smoke" if SMOKE else "aive-app-integration")
# Shared by SMOKE and full runs, so Gradle, the JDK and the clone are downloaded once.
TOOLCHAIN = ROOT / "aive-app-integration-toolchain"
STATE_FILE, RESULTS_FILE = WORK / "state.json", WORK / "results.json"
ASSETS = WORK / "release-assets"
ASSETS.mkdir(parents=True, exist_ok=True)
state = json.loads(STATE_FILE.read_text()) if STATE_FILE.exists() else {}
RESULTS = json.loads(RESULTS_FILE.read_text()) if RESULTS_FILE.exists() else {}

def save_state():
    STATE_FILE.write_text(json.dumps(state, indent=2))

def save_results():
    RESULTS_FILE.write_text(json.dumps(RESULTS, indent=2))

def deadline_left():
    return SESSION_STARTED + TIME_BUDGET_HOURS * 3600 - time.time()

def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(8 << 20), b""):
            h.update(chunk)
    return h.hexdigest()

import numpy as np
import torch
random.seed(SEED); np.random.seed(SEED); torch.manual_seed(SEED); torch.cuda.manual_seed_all(SEED)

DEVICE = "cuda" if torch.cuda.is_available() else "cpu"
if DEVICE != "cuda":
    print("WARNING: no GPU. Turn on a GPU accelerator; training on CPU will not finish." if not SMOKE
          else "no GPU: SMOKE continues on CPU, slowly")
else:
    print("GPU:", torch.cuda.get_device_name(0), f"{torch.cuda.get_device_properties(0).total_memory / 2**30:.1f} GiB")
RESULTS.update({"smoke": SMOKE, "baseModel": BASE_MODEL, "releaseTag": RELEASE_TAG})
print(f"SMOKE={SMOKE} UPLOAD={UPLOAD} EXPORT_ONNX={EXPORT_ONNX} EXPORT_LITERTLM={EXPORT_LITERTLM}; work in {WORK}")
''')

code(r'''
# ---- Corpus build stage: clone, JDK 21, Gradle generator, verify, zip ---------------------------------------
# No GPU is needed here, Internet must be on. The first run downloads Gradle, the Kotlin toolchain and the
# project's dependencies into TOOLCHAIN (several GB, tens of minutes); a rerun reuses them. Any failure stops
# the notebook: it never falls back to a different corpus after a failed build.
import re, urllib.request, zipfile
from collections import deque

def manifest_entries(manifest):
    """(relative path, sha256) for every file the manifest lists, whichever of the two shapes it uses."""
    files = manifest.get("files")
    if isinstance(files, dict):
        return [(name, v if isinstance(v, str) else v["sha256"]) for name, v in files.items()]
    if isinstance(files, list):
        return [(v.get("path") or v.get("name") or v["file"], v["sha256"]) for v in files]
    raise ValueError("manifest.json has no `files` (a name -> sha256 map, or a list of {path, sha256})")

def sh(cmd, cwd=None, env=None):
    proc = subprocess.run([str(c) for c in cmd], cwd=cwd, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    if proc.returncode != 0:
        raise RuntimeError(f"`{' '.join(map(str, cmd))}` failed with exit code {proc.returncode}:\n{proc.stdout[-3000:]}")
    return proc.stdout

def java_major(env):
    try:
        proc = subprocess.run(["java", "-version"], env=env, capture_output=True, text=True)
    except FileNotFoundError:
        return None
    found = re.search(r'version "(\d+)', proc.stderr + proc.stdout)
    return int(found.group(1)) if found else None

def with_java_home(home):
    env = dict(os.environ)
    env["JAVA_HOME"] = str(home)
    env["PATH"] = f"{home}/bin:{env.get('PATH', '')}"
    return env

def ensure_jdk():
    """An environment with JDK JDK_MAJOR (21) on its PATH: the installed one, apt's, or a downloaded Temurin."""
    env = dict(os.environ)
    if java_major(env) == JDK_MAJOR:
        print(f"JDK {JDK_MAJOR} already installed")
        return env
    print(f"java is {java_major(env)}, not {JDK_MAJOR}: installing openjdk-{JDK_MAJOR}-jdk-headless with apt-get")
    try:
        sh(["apt-get", "update", "-qq"])
        sh(["apt-get", "install", "-y", "-qq", f"openjdk-{JDK_MAJOR}-jdk-headless"])
        for home in sorted(Path("/usr/lib/jvm").glob(f"*{JDK_MAJOR}*")):
            if (home / "bin" / "java").is_file() and java_major(with_java_home(home)) == JDK_MAJOR:
                print("JDK from apt:", home)
                return with_java_home(home)
        print(f"apt installed a package but no JDK {JDK_MAJOR} was found under /usr/lib/jvm")
    except Exception as failure:
        print(f"apt-get route failed: {str(failure)[:500]}")
    # Temurin from Adoptium: the API redirects to the latest GA tarball.
    jdk_dir = TOOLCHAIN / "jdk"
    url = f"https://api.adoptium.net/v3/binary/latest/{JDK_MAJOR}/ga/linux/x64/jdk/hotspot/normal/eclipse"
    homes = [p for p in jdk_dir.glob("*") if (p / "bin" / "java").is_file()] if jdk_dir.is_dir() else []
    if not homes:
        shutil.rmtree(jdk_dir, ignore_errors=True)
        jdk_dir.mkdir(parents=True)
        archive = jdk_dir / "temurin.tar.gz"
        print("downloading Temurin from", url)
        with urllib.request.urlopen(url, timeout=600) as response, open(archive, "wb") as out:
            shutil.copyfileobj(response, out, 8 << 20)
        with tarfile.open(archive) as tar:
            tar.extractall(jdk_dir, **({"filter": "data"} if hasattr(tarfile, "data_filter") else {}))
        archive.unlink()
        homes = [p for p in jdk_dir.glob("*") if (p / "bin" / "java").is_file()]
    if homes and java_major(with_java_home(homes[0])) == JDK_MAJOR:
        print("JDK from Temurin:", homes[0])
        return with_java_home(homes[0])
    raise RuntimeError(f"No JDK {JDK_MAJOR}: java is absent or another version, apt-get failed and the Temurin download did not give a working JDK")

def resolve_source():
    """Clone SOURCE_REPOSITORY at SOURCE_REF (shallow); returns (checkout, full commit SHA)."""
    checkout = TOOLCHAIN / "source"
    TOOLCHAIN.mkdir(parents=True, exist_ok=True)
    if not (checkout / ".git").is_dir():
        shutil.rmtree(checkout, ignore_errors=True)
        checkout.mkdir(parents=True)
        sh(["git", "init", "-q"], cwd=checkout)
        sh(["git", "remote", "add", "origin", SOURCE_REPOSITORY], cwd=checkout)
    sh(["git", "fetch", "--depth", "1", "origin", SOURCE_REF], cwd=checkout)
    sh(["git", "checkout", "-q", "--force", "FETCH_HEAD"], cwd=checkout)
    commit = sh(["git", "rev-parse", "HEAD"], cwd=checkout).strip()
    assert re.fullmatch(r"[0-9a-f]{40}", commit), f"unexpected commit {commit!r}"
    return checkout, commit

def run_gradle(checkout, env, out_dir, log_path):
    """Run the generator through the repository's Gradle wrapper; stream progress; stop on failure with the log tail."""
    env = dict(env)
    env["GRADLE_USER_HOME"] = str(TOOLCHAIN / "gradle-home")   # wrapper distribution, dependencies and the build cache
    for key, value in GENERATOR_EXTRA_ENV.items():
        env[key] = value.replace("{out}", str(out_dir))
    if CORPUS_ROWS is not None:
        env[GENERATOR_ROWS_ENV] = str(CORPUS_ROWS)
    gradlew = checkout / "gradlew"
    gradlew.chmod(0o755)
    cmd = [str(gradlew), "--build-cache", *[a.replace("{out}", str(out_dir)) for a in GENERATOR_GRADLE_ARGS]]
    print("gradle:", " ".join(cmd[1:]))
    tail, started, last_report = deque(maxlen=200), time.time(), 0.0
    with open(log_path, "w") as log:
        proc = subprocess.Popen(cmd, cwd=checkout, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
        for line in proc.stdout:
            log.write(line)
            tail.append(line.rstrip())
            if time.time() - last_report > 60:
                last_report = time.time()
                print(f"  [{(time.time() - started) / 60:5.1f} min] {tail[-1][:160]}", flush=True)
            if time.time() - started > GRADLE_TIMEOUT_HOURS * 3600:
                proc.kill()
                tail.append(f"TIMEOUT: stopped after {GRADLE_TIMEOUT_HOURS} h")
                break
        proc.wait()
    if proc.returncode != 0:
        print("\n".join(tail))
        raise RuntimeError(f"The corpus generator failed (exit {proc.returncode}); the last {len(tail)} log lines are above, the full log is {log_path}")
    print(f"gradle finished in {(time.time() - started) / 60:.1f} min")

def deterministic_zip(source_dir, zip_path, top):
    """A zip whose bytes depend only on the files: sorted entries, fixed timestamps and modes."""
    source_dir, zip_path = Path(source_dir), Path(zip_path)
    with zipfile.ZipFile(zip_path, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for path in sorted(p for p in source_dir.rglob("*") if p.is_file()):
            info = zipfile.ZipInfo(f"{top}/{path.relative_to(source_dir).as_posix()}", date_time=(1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            archive.writestr(info, path.read_bytes())
    return zip_path

def build_corpus():
    """The corpus directory for this run, built from SOURCE_REF (or reused from an identical earlier build)."""
    env = ensure_jdk()
    checkout, commit = resolve_source()
    print(f"corpus source: {SOURCE_REPOSITORY} @ {SOURCE_REF} = {commit}")
    RESULTS["corpusBuild"] = {"repository": SOURCE_REPOSITORY, "ref": SOURCE_REF, "commit": commit, "rows": CORPUS_ROWS,
                              "java": [l for l in subprocess.run(["java", "-version"], env=env, capture_output=True, text=True).stderr.splitlines() if "version" in l][:1]}
    out = WORK / "corpus-build" / "aive-app-integration-corpus"
    previous = state.get("corpusBuild", {})
    if (out / "manifest.json").is_file() and previous.get("commit") == commit and previous.get("rows") == CORPUS_ROWS:
        print("corpus already built from this commit and row count; reusing it")
    else:
        shutil.rmtree(out.parent, ignore_errors=True)
        out.mkdir(parents=True)
        run_gradle(checkout, env, out, WORK / "gradle.log")
        if not (out / "manifest.json").is_file():
            raise RuntimeError(f"Gradle finished but {out}/manifest.json was not written: the generator did not write to {{out}}; "
                               "check GENERATOR_GRADLE_ARGS / GENERATOR_EXTRA_ENV against the Kotlin writer")
    # score.py and the Kaggle metadata live in the repository, not in the Kotlin output.
    for name in ("score.py", "dataset-metadata.json"):
        source = checkout / "tools" / "app_integration_training" / name
        if not (out / name).is_file() and source.is_file():
            shutil.copy2(source, out / name)
    (out / "build-info.json").write_text(json.dumps(RESULTS["corpusBuild"], indent=2, sort_keys=True) + "\n")
    manifest = json.loads((out / "manifest.json").read_text())
    if manifest.get("source_commit") not in (None, commit):
        raise RuntimeError(f"manifest.json says source_commit {manifest['source_commit']} but the checkout is {commit}")
    return out, commit

def verify_corpus(directory):
    """Every file the manifest lists must exist and match its SHA-256 (the same check an attached dataset gets)."""
    manifest = json.loads((Path(directory) / "manifest.json").read_text())
    for name, digest in manifest_entries(manifest):
        path = Path(directory) / name
        assert path.is_file(), f"manifest.json lists {name}, which the generator did not write"
        assert sha256(path) == digest, f"{name} does not match manifest.json: refusing this corpus"
    return manifest
''')

code(r'''
# ---- Corpus: found, verified against manifest.json (SHA-256), counted ----------------------------------------
SPLITS = ("train", "validation", "test", "adversarial")
REQUIRED_ROW_KEYS = ("id", "family", "system", "user", "expected", "split", "checks")
# Used when no Kaggle dataset is attached: the same corpus zip, committed in this folder.
CORPUS_URLS = [
    f"https://raw.githubusercontent.com/HereLiesAz/aive/{ref}/tools/app_integration_training/aive-app-integration-corpus.zip"
    for ref in ("main",)
]

def find_dataset():
    for root in (Path("/kaggle/input"), WORK / "corpus"):
        for manifest in sorted(root.rglob("manifest.json")) if root.exists() else []:
            if (manifest.parent / "train.jsonl").is_file():
                return manifest.parent, json.loads(manifest.read_text())
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
    raise FileNotFoundError(f"No dataset attached and the corpus download failed; attach {CORPUS_DATASET} or turn on internet")

if BUILD_CORPUS_ON_KAGGLE:
    # Built here, from the repository, by the generator: a failure stops the notebook (no silent fallback).
    DATA, SOURCE_COMMIT = build_corpus()
    MANIFEST = verify_corpus(DATA)
    zip_path = deterministic_zip(DATA, WORK / CORPUS_ZIP_NAME, "aive-app-integration-corpus")
    output_dir = Path("/kaggle/working") if Path("/kaggle/working").is_dir() else WORK
    if zip_path.parent != output_dir:
        shutil.copy2(zip_path, output_dir / CORPUS_ZIP_NAME)   # a notebook output: upload or commit it if you want to
    RESULTS["corpusBuild"].update({"zip": CORPUS_ZIP_NAME, "zipBytes": zip_path.stat().st_size, "zipSha256": sha256(zip_path)})
    state["corpusBuild"] = {"commit": SOURCE_COMMIT, "rows": CORPUS_ROWS}; save_state()
    print(f"corpus zip: {output_dir / CORPUS_ZIP_NAME}, {zip_path.stat().st_size / 2**20:.2f} MB, sha256 {RESULTS['corpusBuild']['zipSha256']}")
else:
    # Fallbacks: an attached Kaggle dataset, then the zip committed in the repository.
    found = find_dataset()
    if found is None:
        download_corpus()
        found = find_dataset()
    if found is None:
        raise FileNotFoundError(f"No corpus found: attach {CORPUS_DATASET}, or set BUILD_CORPUS_ON_KAGGLE = True")
    DATA, MANIFEST = found
    verify_corpus(DATA)
    SOURCE_COMMIT = MANIFEST.get("source_commit", "unknown")
listed = dict(manifest_entries(MANIFEST))
for name in [f"{s}.jsonl" for s in SPLITS]:
    assert name in listed, f"manifest.json does not list {name}"
SCORE_PATH = DATA / "score.py"
assert SCORE_PATH.is_file(), "the corpus must ship score.py next to its manifest"
if "score.py" not in listed:
    print(f"WARNING: manifest.json does not list score.py, so it was not verified (sha256 {sha256(SCORE_PATH)}, from the same commit)")
RESULTS["scoreSha256"] = sha256(SCORE_PATH)

ALL = {}
for split in SPLITS:
    rows = [json.loads(line) for line in (DATA / f"{split}.jsonl").open() if line.strip()]
    for row in rows:
        missing = [k for k in REQUIRED_ROW_KEYS if k not in row]
        assert not missing, f"{split} row {row.get('id')} lacks {missing}"
        assert row["split"] == split, f"{row['id']} is in {split}.jsonl but says split={row['split']}"
        assert row["system"].strip() and row["expected"].strip(), f"{row['id']}: empty system or expected"
    ALL[split] = rows
ids = [r["id"] for rows in ALL.values() for r in rows]
assert len(ids) == len(set(ids)), "row ids are not unique across splits"
FAMILIES = sorted({r["family"] for rows in ALL.values() for r in rows})
print(f"dataset {DATA} from commit {str(SOURCE_COMMIT)[:12]}")
print(f"{'family':8}" + "".join(f"{s:>13}" for s in SPLITS))
for fam in FAMILIES:
    print(f"{fam:8}" + "".join(f"{sum(r['family'] == fam for r in ALL[s]):>13}" for s in SPLITS))
print(f"{'all':8}" + "".join(f"{len(ALL[s]):>13}" for s in SPLITS))

# ---- score.py: the only place scoring and the release gates are defined ----------------------------------
spec = importlib.util.spec_from_file_location("aive_app_integration_score", SCORE_PATH)
SCORE = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = SCORE
spec.loader.exec_module(SCORE)
for name in ("score_rows", "gates"):
    assert callable(getattr(SCORE, name, None)), f"score.py must define {name}()"

# Documented interface: score_rows(rows, predictions) -> {family: score}; gates(per_family) -> (ok, failures).
# What is not pinned down is how predictions are keyed and what gates() receives, so both are probed here,
# once, on the corpus's own expected answers (which must score 1.0), before any hour of training is spent.
SCORE_STYLE, GATES_STYLE = {}, {}

def family_value(value):
    if isinstance(value, (int, float)):
        return float(value)
    for key in ("score", "pass_rate", "rate", "value"):
        if key in value:
            return float(value[key])
    raise KeyError(f"cannot read a score out of {value!r}")

def call_score(rows, answers):
    """Per-family scores for [rows] given [answers] (a list in row order): {family: float}."""
    by_id = {r["id"]: a for r, a in zip(rows, answers)}
    styles = [SCORE_STYLE["style"]] if SCORE_STYLE else ["by_id", "list"]
    failure = None
    for style in styles:
        try:
            result = SCORE.score_rows(rows, by_id if style == "by_id" else list(answers))
            SCORE_STYLE["style"] = style
            return {family: family_value(v) for family, v in result.items()}
        except Exception as caught:
            failure = caught
    raise RuntimeError(f"score.py's score_rows could not be called with the predictions as an id->text map or a list: {failure!r}")

def call_gates(test_scores, adversarial_scores):
    """(ok, failures) from score.py's gates() given the per-family scores of both splits."""
    attempts = {
        "by_split": lambda: SCORE.gates({"test": test_scores, "adversarial": adversarial_scores}),
        "positional": lambda: SCORE.gates(test_scores, adversarial_scores),
    }
    order = [GATES_STYLE["style"]] if GATES_STYLE else list(attempts)
    failure = None
    for style in order:
        try:
            ok, failures = attempts[style]()
            GATES_STYLE["style"] = style
            return bool(ok), failures
        except Exception as caught:
            failure = caught
    raise RuntimeError(f"score.py's gates could not be called with {{'test':..,'adversarial':..}} or (test, adversarial): {failure!r}")

perfect = {s: call_score(ALL[s], [r["expected"] for r in ALL[s]]) for s in ("test", "adversarial")}
for s, per_family in perfect.items():
    flawed = {f: v for f, v in per_family.items() if v < 1.0}
    assert not flawed, f"score.py gives the corpus's own expected answers less than 1.0 on {s}: {flawed}; the corpus or score.py is wrong"
ok, failures = call_gates(perfect["test"], perfect["adversarial"])
assert ok, f"score.py's gates fail the corpus's own expected answers: {failures}"
print(f"score.py checked on the expected answers (predictions as {SCORE_STYLE['style']}, gates as {GATES_STYLE['style']})")

if SMOKE:
    def tiny(rows, n):
        by = collections.defaultdict(list)
        for r in rows:
            by[r["family"]].append(r)
        out, i = [], 0
        while len(out) < min(n, len(rows)):
            for fam in sorted(by):
                if i < len(by[fam]) and len(out) < n:
                    out.append(by[fam][i])
            i += 1
        return out
    ALL = {s: tiny(ALL[s], SMOKE_TRAIN_ROWS if s == "train" else SMOKE_EVAL_ROWS) for s in SPLITS}
    print("SMOKE: splits cut to", {s: len(r) for s, r in ALL.items()})
RESULTS["sourceCommit"] = SOURCE_COMMIT
RESULTS["splitSizes"] = {s: len(r) for s, r in ALL.items()}
RESULTS["familyCounts"] = {s: dict(collections.Counter(r["family"] for r in ALL[s])) for s in SPLITS}
save_results()
''')

code(r'''
# ---- The prompt: byte-identical to QwenChatPrompt.format in OnDeviceTextRuntime.kt ---------------------------
from transformers import AutoModelForCausalLM, AutoTokenizer, DataCollatorForSeq2Seq, Trainer, TrainerCallback, TrainingArguments
from peft import LoraConfig, PeftModel, TaskType, get_peft_model

def render_prompt(system, user):
    """What the app sends: QwenChatPrompt.format(system, user), character for character."""
    return ("<|im_start|>system\n" + system + "<|im_end|>\n"
            "<|im_start|>user\n" + user + "<|im_end|>\n"
            "<|im_start|>assistant\n")

TOKENIZER = AutoTokenizer.from_pretrained(BASE_MODEL)
IM_END = TOKENIZER.convert_tokens_to_ids("<|im_end|>")
STOP_IDS = [IM_END, TOKENIZER.convert_tokens_to_ids("<|endoftext|>")]

# The tokenizer's own chat template must give the same text for every row of every split, and the same
# token ids on a sample: training through it is then training on exactly what the app sends.
for split, rows in ALL.items():
    for row in rows:
        templated = TOKENIZER.apply_chat_template(
            [{"role": "system", "content": row["system"]}, {"role": "user", "content": row["user"]}],
            tokenize=False, add_generation_prompt=True)
        assert templated == render_prompt(row["system"], row["user"]), f"{row['id']}: chat template differs from QwenChatPrompt"
for row in ALL["train"][:50]:
    templated_ids = TOKENIZER.apply_chat_template(
        [{"role": "system", "content": row["system"]}, {"role": "user", "content": row["user"]}],
        tokenize=True, add_generation_prompt=True)
    templated_ids = templated_ids["input_ids"] if hasattr(templated_ids, "keys") else templated_ids
    assert templated_ids == TOKENIZER(render_prompt(row["system"], row["user"]), add_special_tokens=False)["input_ids"], row["id"]
print("chat template matches QwenChatPrompt on", sum(len(r) for r in ALL.values()), "rows")

def encode_row(row):
    prompt = TOKENIZER(render_prompt(row["system"], row["user"]), add_special_tokens=False)["input_ids"]
    answer = TOKENIZER(row["expected"] + "<|im_end|>", add_special_tokens=False)["input_ids"]
    ids = prompt + answer
    if len(ids) > MAX_LENGTH:
        return None
    # Loss on the answer only (and its end-of-turn token): the model must not learn to reproduce the prompt.
    return {"input_ids": ids, "attention_mask": [1] * len(ids), "labels": [-100] * len(prompt) + answer}

def lora_config():
    """The orchestration notebook's shape: an adapter only runs on a base exported with it."""
    return LoraConfig(
        task_type=TaskType.CAUSAL_LM, r=LORA_R, lora_alpha=LORA_ALPHA, lora_dropout=LORA_DROPOUT,
        target_modules=["q_proj", "k_proj", "v_proj", "o_proj", "gate_proj", "up_proj", "down_proj"],
    )

ENCODED = {}
for split in ("train",):
    encoded = [(r, encode_row(r)) for r in ALL[split]]
    ENCODED[split] = [e for _, e in encoded if e]
    skipped = [r["id"] for r, e in encoded if not e]
    RESULTS["skippedTooLong"] = {"count": len(skipped), "ids": skipped[:50]}
    print(f"{split}: {len(ENCODED[split])} rows encoded, {len(skipped)} longer than {MAX_LENGTH} tokens skipped")
    assert len(skipped) <= 0.02 * len(ALL[split]), "more than 2% of the training rows do not fit the 1,280-token cache: the corpus must be shortened"
for split in ("validation", "test", "adversarial"):
    too_long = [r["id"] for r in ALL[split] if len(TOKENIZER(render_prompt(r["system"], r["user"]), add_special_tokens=False)["input_ids"]) + 1 > MAX_LENGTH]
    if too_long:
        print(f"WARNING: {len(too_long)} {split} prompts do not fit the cache: {too_long[:5]}")
save_results()
''')

code(r'''
# ---- Generation and scoring helpers --------------------------------------------------------------------------
def generate_batch(model, prompts, max_new=None):
    """Greedy answers for [prompts], GATE_BATCH at a time, longest first, left-padded. Stops at <|im_end|>."""
    max_new = max_new or MAX_NEW
    TOKENIZER.padding_side = "left"
    order = sorted(range(len(prompts)), key=lambda i: -len(prompts[i]))
    answers = [None] * len(prompts)
    for start in range(0, len(order), GATE_BATCH):
        chunk = order[start:start + GATE_BATCH]
        ids = TOKENIZER([prompts[i] for i in chunk], return_tensors="pt", padding=True, add_special_tokens=False).to(model.device)
        with torch.no_grad(), torch.autocast("cuda", dtype=torch.float16, enabled=DEVICE == "cuda"):
            out = model.generate(**ids, max_new_tokens=max_new, do_sample=False, eos_token_id=STOP_IDS,
                                 pad_token_id=TOKENIZER.pad_token_id)
        for k, i in enumerate(chunk):
            answers[i] = TOKENIZER.decode(out[k][ids["input_ids"].shape[1]:], skip_special_tokens=True)
    return answers

def prompts_of(rows):
    return [render_prompt(r["system"], r["user"]) for r in rows]

def worst_and_mean(per_family):
    values = list(per_family.values())
    return (min(values), sum(values) / len(values)) if values else (0.0, 0.0)

def selection_rows():
    """A fixed validation sample per family: what each epoch is judged on."""
    picked = []
    for fam in FAMILIES:
        rows = [r for r in ALL["validation"] if r["family"] == fam]
        picked += random.Random(f"{fam}:select").sample(rows, min(SELECT_ROWS_PER_FAMILY, len(rows)))
    return picked
''')

code(r'''
# ---- Training: LoRA, loss on answers only, cosine schedule, fp16, early stopping on the validation score ----
from transformers.trainer_utils import get_last_checkpoint

class SelectByScore(TrainerCallback):
    """After each epoch, score a validation sample with score.py; keep the best epoch's adapter on disk.

    The measure is the worst family's score (mean as the tie-break), not loss: a model can lower loss while
    getting worse at the checks that gate the release. Stops after SELECT_PATIENCE epochs without a better
    score, and at the session deadline. Progress is in state.json, so a resumed run continues the count.
    """
    def __init__(self, model, picked, best_dir):
        self.model, self.picked, self.best_dir = model, picked, best_dir
        self.record = state.setdefault("train", {"history": [], "best": None, "bestEpoch": None, "stale": 0, "done": False})
        self.out_of_time = False
    def on_step_end(self, args, tstate, control, **kwargs):
        if deadline_left() <= 0:
            self.out_of_time = True
            control.should_training_stop = True
    def on_epoch_end(self, args, tstate, control, **kwargs):
        was_training = self.model.training
        self.model.eval()
        answers = generate_batch(self.model, prompts_of(self.picked))
        if was_training:
            self.model.train()
        worst, mean = worst_and_mean(call_score(self.picked, answers))
        epoch = int(round(tstate.epoch))
        self.record["history"].append({"epoch": epoch, "worstFamily": worst, "mean": mean})
        print(f"epoch {epoch}: worst-family validation score {worst:.3f}, mean {mean:.3f}")
        best = self.record["best"]
        if best is None or (worst, mean) > (best["worst"], best["mean"]):
            self.record["best"], self.record["bestEpoch"], self.record["stale"] = {"worst": worst, "mean": mean}, epoch, 0
            self.model.save_pretrained(self.best_dir)
        else:
            self.record["stale"] += 1
            if self.record["stale"] >= SELECT_PATIENCE and tstate.epoch < args.num_train_epochs:
                print(f"validation score levelled off: stopping after epoch {epoch}")
                control.should_training_stop = True
        save_state()

def train():
    out, best_dir, checkpoints = WORK / "adapter", WORK / "best", WORK / "checkpoints"
    if state.get("train", {}).get("done") and (out / "adapter_model.safetensors").is_file():
        print("training already done:", state["train"]["bestEpoch"], state["train"]["best"])
        return out
    model = AutoModelForCausalLM.from_pretrained(BASE_MODEL, torch_dtype=torch.float32).to(DEVICE)
    # Recompute activations in the backward pass instead of keeping them: long rows fit in memory.
    model.gradient_checkpointing_enable(gradient_checkpointing_kwargs={"use_reentrant": False})
    model.enable_input_require_grads()
    model = get_peft_model(model, lora_config())
    select = SelectByScore(model, selection_rows(), best_dir)
    rng = random.Random(SEED)
    rows = list(ENCODED["train"]); rng.shuffle(rows)
    trainer = Trainer(
        model=model,
        args=TrainingArguments(
            output_dir=str(checkpoints), per_device_train_batch_size=BATCH_SIZE, gradient_accumulation_steps=GRAD_ACCUM,
            num_train_epochs=EPOCHS, max_steps=SMOKE_STEPS if SMOKE else -1,
            learning_rate=LEARNING_RATE, lr_scheduler_type="cosine", warmup_ratio=0.05,
            fp16=DEVICE == "cuda", logging_steps=25, eval_strategy="no", save_strategy="epoch", save_total_limit=1,
            report_to=[], seed=SEED, data_seed=SEED,
        ),
        train_dataset=rows,
        data_collator=DataCollatorForSeq2Seq(TOKENIZER, padding=True, label_pad_token_id=-100),
        callbacks=[select],
    )
    last = get_last_checkpoint(str(checkpoints)) if checkpoints.is_dir() else None
    if last:
        print("resuming from", last)
    trainer.train(resume_from_checkpoint=last)
    if select.record["best"] is None or not (best_dir / "adapter_model.safetensors").is_file():
        raise RuntimeError("Session deadline reached before the first epoch finished: nothing saved; run again")
    if select.out_of_time:
        raise RuntimeError("Session deadline reached mid-training: progress is kept in state.json; run all cells again to resume")
    shutil.rmtree(out, ignore_errors=True)
    shutil.copytree(best_dir, out)
    TOKENIZER.save_pretrained(out)
    select.record["done"] = True; save_state()
    shutil.rmtree(checkpoints, ignore_errors=True)
    print(f"kept epoch {select.record['bestEpoch']}: worst-family validation {select.record['best']['worst']:.3f}")
    del trainer, model; gc.collect()
    if DEVICE == "cuda": torch.cuda.empty_cache()
    return out

ADAPTER_DIR = train()
RESULTS["training"] = state["train"]; save_results()
''')

code(r'''
# ---- Gate 1: the PEFT adapter, greedy, max_new_tokens 512, on test + adversarial ------------------------------
def row_failures(rows, answers):
    """Every row that scores below 1.0 on its own, with the prediction: the evidence a failure needs."""
    failed = []
    for row, answer in zip(rows, answers):
        single = call_score([row], [answer]).get(row["family"], 0.0)
        if single < 1.0:
            failed.append({"id": row["id"], "family": row["family"], "score": single, "expected": row["expected"], "prediction": answer})
    return failed

def evaluate(label, answers_by_split, rows_by_split):
    """Score both splits with score.py, print the per-family table, apply score.py's gates."""
    per = {s: call_score(rows_by_split[s], answers_by_split[s]) for s in ("test", "adversarial")}
    ok, gate_failures = call_gates(per["test"], per["adversarial"])
    print(f"\n{label}: per-family scores")
    print(f"{'family':8}{'test':>8}{'adversarial':>13}")
    for fam in sorted(set(per["test"]) | set(per["adversarial"])):
        print(f"{fam:8}{per['test'].get(fam, float('nan')):>8.3f}{per['adversarial'].get(fam, float('nan')):>13.3f}")
    print(f"{label}: {'PASS' if ok else 'FAIL'}", "" if ok else gate_failures)
    failed = {s: row_failures(rows_by_split[s], answers_by_split[s]) for s in ("test", "adversarial")}
    return {"ok": ok, "gateFailures": gate_failures, "perFamily": per, "rows": {s: len(rows_by_split[s]) for s in per},
            "failingRows": failed}

def gate1():
    model = AutoModelForCausalLM.from_pretrained(BASE_MODEL, torch_dtype=torch.float16 if DEVICE == "cuda" else torch.float32).to(DEVICE)
    model = PeftModel.from_pretrained(model, ADAPTER_DIR).eval()
    answers = {s: generate_batch(model, prompts_of(ALL[s])) for s in ("test", "adversarial")}
    del model; gc.collect()
    if DEVICE == "cuda": torch.cuda.empty_cache()
    (WORK / "gate1-predictions.json").write_text(json.dumps({s: dict(zip([r["id"] for r in ALL[s]], answers[s])) for s in answers}))
    return answers

if "gate1" not in state:
    gate1_answers = gate1()
    RESULTS["gate1"] = evaluate("Gate 1 (adapter, PyTorch)", gate1_answers, ALL)
    state["gate1"] = {"ok": RESULTS["gate1"]["ok"]}; save_state(); save_results()
else:
    stored = json.loads((WORK / "gate1-predictions.json").read_text())
    gate1_answers = {s: [stored[s][r["id"]] for r in ALL[s]] for s in stored}
    print("Gate 1 already run:", "PASS" if state["gate1"]["ok"] else "FAIL")
GATE1_BY_ID = {s: dict(zip([r["id"] for r in ALL[s]], gate1_answers[s])) for s in gate1_answers}
if not RESULTS["gate1"]["ok"]:
    failing = {s: [f["id"] for f in rows] for s, rows in RESULTS["gate1"]["failingRows"].items()}
    if SMOKE:
        print("SMOKE: Gate 1 failed on a 20-step adapter, as expected; continuing to prove the pipeline")
    else:
        raise RuntimeError(
            f"Gate 1 failed: {RESULTS['gate1']['gateFailures']}. No release. Failing row ids and predictions are in {RESULTS_FILE}. "
            f"Failing test rows: {failing['test'][:20]}; adversarial: {failing['adversarial'][:20]}")
''')

code(r'''
# ---- Export A: ONNX Runtime, the published shared INT8 base + this adapter's weights as graph inputs --------
# This is the orchestration notebook's machinery, reused as is: the base was exported once with every LoRA
# weight as a graph input (found by fingerprint), so an adapter is just a small safetensors file keyed by
# those input names. Nothing is exported or quantized here; the base is downloaded and verified.
import onnxruntime as ort
from safetensors.numpy import load_file, save_file

def check_base(raw, archive):
    data = json.loads(raw)
    base = data["base"]
    assert base["logicalArtifactId"] == BASE_ARTIFACT_ID, f"found {base['logicalArtifactId']}, not {BASE_ARTIFACT_ID}"
    assert (data["loraRank"], data["loraAlpha"]) == (LORA_R, LORA_ALPHA), "the published base was exported for another LoRA shape"
    assert sha256(archive) == base["sha256"], f"{archive} does not match its base.json"
    return data, raw, archive

def published_base(into):
    # The published base as (base.json, its exact bytes, verified archive): from an attached Kaggle model
    # when there is one, else downloaded from the GitHub release into [into]. None when not published.
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
    """The published shared base this adapter runs on: verified and unpacked once."""
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
            f"{BASE_RELEASE_TAG} is not published. Run tools/orchestration_training/notebooks/base.ipynb with "
            "UPLOAD = True first, or attach its Kaggle model. (Or set EXPORT_ONNX = False to skip this export.)")
    data, raw, archive = found
    shutil.rmtree(int8, ignore_errors=True)
    with tarfile.open(archive) as tar:
        tar.extractall(int8, **({"filter": "data"} if hasattr(tarfile, "data_filter") else {}))
    if archive.parent == root:
        archive.unlink()
    cached.write_bytes(raw)
    verified.write_text(data["base"]["sha256"] + "\n")
    return data["base"], int8, json.loads((int8 / "lora-inputs.json").read_text())

def adapter_tensors(adapter, mapping):
    """The adapter's LoRA weights keyed by base-graph input name, oriented as the graph expects."""
    state_dict = load_file(str(Path(adapter) / "adapter_model.safetensors"))
    out = {}
    for peft_name, m in mapping.items():
        value = state_dict[peft_name.replace(".default.weight", ".weight")].astype(np.float32)
        out[m["input"]] = np.ascontiguousarray(value.T if m["transposed"] else value)
    return out

def save_adapter_file(mapping):
    """The release asset: LoRA inputs as fp16 safetensors keyed by base-graph input name (~18 MB)."""
    tensors = {k: v.astype(np.float16) for k, v in adapter_tensors(ADAPTER_DIR, mapping).items()}
    path = ASSETS / ADAPTER_FILE
    save_file(tensors, str(path), metadata={
        "format": "aive-lora-inputs", "base": BASE_ARTIFACT_ID, "specialistId": SPECIALIST_ID,
        "loraRank": str(LORA_R), "loraAlpha": str(LORA_ALPHA),
    })
    return path

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

ONNX = {}
if EXPORT_ONNX:
    base_descriptor, base_int8, lora_mapping = fetch_base()
    adapter_path = save_adapter_file(lora_mapping)
    print(f"ONNX adapter: {adapter_path.name}, {adapter_path.stat().st_size / 2**20:.1f} MB, base {base_descriptor['sha256'][:12]}")
    ONNX.update({"base": base_descriptor, "adapterPath": adapter_path})
    RESULTS["onnxAdapter"] = {"assetName": adapter_path.name, "bytes": adapter_path.stat().st_size, "sha256": sha256(adapter_path),
                              "baseSha256": base_descriptor["sha256"], "baseTag": BASE_RELEASE_TAG}
    save_results()
''')

code(r'''
# ---- Gate 2: the exported ONNX model on CPU (what ships: INT8 base + this adapter's fp16 file) ----------------
def gate2():
    tokenizer = AutoTokenizer.from_pretrained(base_int8)
    session = ort.InferenceSession(str(base_int8 / "model.onnx"), providers=["CPUExecutionProvider"])
    # Round-trip through the fp16 release file so the gate sees exactly what ships.
    extra = {k: v.astype(np.float32) for k, v in load_file(str(ONNX["adapterPath"])).items()}
    tests = ALL["test"]
    if ONNX_GATE_TEST_ROWS is not None and len(tests) > ONNX_GATE_TEST_ROWS:
        tests = random.Random("onnx-gate").sample(tests, ONNX_GATE_TEST_ROWS)
    rows_by_split, answers = {"test": tests, "adversarial": ALL["adversarial"]}, {}
    started = time.time()
    for split, rows in rows_by_split.items():
        answers[split] = []
        for i, row in enumerate(rows):
            answers[split].append(ort_generate(session, base_int8, tokenizer, render_prompt(row["system"], row["user"]), MAX_NEW, extra))
            if (i + 1) % 25 == 0:
                print(f"  onnx {split}: {i + 1}/{len(rows)} rows, {(time.time() - started) / 60:.1f} min", flush=True)
    del session; gc.collect()
    result = evaluate("Gate 2 (ONNX INT8 + adapter, CPU)", answers, rows_by_split)
    # The same rows scored from Gate 1's answers: the drop is measured like for like.
    g1 = {s: call_score(rows_by_split[s], [GATE1_BY_ID[s][r["id"]] for r in rows_by_split[s]]) for s in rows_by_split}
    drops = {s: {f: round(g1[s][f] - result["perFamily"][s].get(f, 0.0), 4) for f in g1[s]} for s in g1}
    worst_drop = max(d for per in drops.values() for d in per.values())
    result.update({"gate1SameRows": g1, "drops": drops, "worstDrop": worst_drop, "maxDrop": ONNX_MAX_DROP,
                   "ok": result["ok"] and worst_drop <= ONNX_MAX_DROP})
    print(f"worst per-family drop against Gate 1: {worst_drop:.3f} (allowed {ONNX_MAX_DROP}) -> {'ok' if worst_drop <= ONNX_MAX_DROP else 'TOO LARGE'}")
    return result

if EXPORT_ONNX:
    if "gate2" not in state:
        RESULTS["gate2"] = gate2()
        state["gate2"] = {"ok": RESULTS["gate2"]["ok"]}; save_state(); save_results()
    else:
        print("Gate 2 already run:", "PASS" if state["gate2"]["ok"] else "FAIL")
    if not RESULTS["gate2"]["ok"]:
        if SMOKE:
            print("SMOKE: Gate 2 failed on a 20-step adapter, as expected; continuing")
        else:
            raise RuntimeError(f"Gate 2 failed: {RESULTS['gate2']['gateFailures']}, worst drop {RESULTS['gate2']['worstDrop']}. "
                               f"No release. Failing rows and predictions are in {RESULTS_FILE}.")
''')

code(r'''
# ---- Export B: merged full model for LiteRT-LM (the preferred runtime) --------------------------------------
# LiteRT-LM has no adapter-as-input path in this app, and no converter for a separate LiteRT-LM LoRA file is
# documented (Android's LoraConfig(loraPath) exists in SessionConfig only), so the adapter is merged into
# the base and the whole model is converted. litert-torch runs in its own virtual environment so its torch
# does not collide with the pins above.
def run(cmd, log, timeout=None, **kwargs):
    """Run [cmd], keep the full output in [log], print its tail, return (exit code, output)."""
    proc = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=timeout, **kwargs)
    Path(log).write_text(proc.stdout)
    print(proc.stdout[-1500:])
    return proc.returncode, proc.stdout

def sanitize_on_device_text(raw):
    """sanitizeOnDeviceText from OnDeviceTextRuntime.kt: cut at the first end-of-turn marker, drop other markers, trim."""
    end = raw.find("<|im_end|>")
    text = raw[:end] if end >= 0 else raw
    for marker in ("<|im_start|>", "<|im_end|>", "<|endoftext|>"):
        text = text.replace(marker, "")
    return text.strip()

LITERT = RESULTS.setdefault("litertLm", {"enabled": EXPORT_LITERTLM})
LITERT["enabled"] = EXPORT_LITERTLM
if EXPORT_LITERTLM and not (LITERT.get("modelPath") and Path(LITERT["modelPath"]).is_file()):
    root = WORK / "litert"
    merged, converted, venv = root / "merged", root / "converted", root / "venv"
    root.mkdir(exist_ok=True)
    if not (merged / "model.safetensors").is_file():
        shutil.rmtree(merged, ignore_errors=True)
        full = AutoModelForCausalLM.from_pretrained(BASE_MODEL, torch_dtype=torch.float32)
        full = PeftModel.from_pretrained(full, ADAPTER_DIR).merge_and_unload()
        full.save_pretrained(merged, safe_serialization=True)
        TOKENIZER.save_pretrained(merged)
        del full; gc.collect()
    if not (venv / "bin" / LITERT_CLI[0]).is_file():
        shutil.rmtree(venv, ignore_errors=True)
        subprocess.run([sys.executable, "-m", "venv", str(venv)], check=True)
        exit_code, _ = run([str(venv / "bin" / "python"), "-m", "pip", "install", "-q", LITERT_PACKAGE], root / "pip-litert-torch.log")
        if exit_code != 0:
            LITERT.update({"ran": False, "reason": f"pip install {LITERT_PACKAGE} failed (see {root / 'pip-litert-torch.log'})"})
            save_results()
            raise RuntimeError(LITERT["reason"] + ". Set EXPORT_LITERTLM = False to release the ONNX adapter alone.")
    cli = [str(venv / "bin" / LITERT_CLI[0]), *LITERT_CLI[1:]]
    flags = [("--model", str(merged)), ("--output_dir", str(converted)), ("--quantization_recipe", LITERT_QUANTIZATION_RECIPE),
             ("--cache_length", str(KV_CACHE_TOKENS)), ("--prefill_lengths", LITERT_PREFILL_LENGTHS), LITERT_BUNDLE_FLAG]
    _, help_text = run([*cli, "--help"], root / "export-help.log")
    absent = [f for f, _ in flags if f.lstrip("-") not in help_text]
    LITERT["helpText"] = help_text[-6000:]
    if absent:
        LITERT.update({"ran": False, "reason": f"`{' '.join(LITERT_CLI)} --help` does not list {absent}; fix the LITERT_* constants"})
        save_results()
        raise RuntimeError(
            f"litert-torch does not accept the flags {absent}. The flag names in the LITERT_* constants were written without "
            f"being able to check them. Its help text is in results.json and {root / 'export-help.log'}; correct the constants, run again.")
    shutil.rmtree(converted, ignore_errors=True)
    exit_code, output = run([*cli, *[f"{k}={v}" for k, v in flags]], root / "export.log", timeout=4 * 3600)
    produced = sorted([p for ext in ("*.litertlm", "*.task") for p in converted.rglob(ext)], key=lambda p: -p.stat().st_size)
    if exit_code != 0 or not produced:
        LITERT.update({"ran": False, "reason": f"litert-torch export_hf exited {exit_code} and produced {[p.name for p in produced]}"})
        save_results()
        raise RuntimeError(LITERT["reason"] + f" (log: {root / 'export.log'})")
    final = ASSETS / (LITERTLM_FILE + produced[0].suffix)
    shutil.copy2(produced[0], final)
    LITERT.update({"ran": True, "modelPath": str(final), "assetName": final.name, "bytes": final.stat().st_size, "sha256": sha256(final),
                   "format": produced[0].suffix.lstrip("."), "quantizationRecipe": LITERT_QUANTIZATION_RECIPE,
                   "cacheLength": KV_CACHE_TOKENS, "prefillLengths": LITERT_PREFILL_LENGTHS,
                   "mergedFullModel": True, "separateLoraFile": False})
    save_results()
if EXPORT_LITERTLM:
    print(f"LiteRT-LM file: {LITERT['assetName']}, {LITERT['bytes'] / 2**20:.0f} MB, sha256 {LITERT['sha256'][:12]} "
          "(the installed Qwen2.5-0.5B-Instruct q8 .task is 547 MB: a size far from that deserves a look)")
''')

code(r'''
# ---- Smoke-test the converted file with the litert_lm Python package; agreement with Gate 1 ------------------
# UNVERIFIED API: the Python runtime's session calls below are written from memory. The app feeds the engine
# an already-templated raw prompt (LiteRtLmModel.complete), so only the raw-session API is used here; the
# conversation API would apply a template of its own and make the comparison meaningless. If the package
# cannot be installed or its API differs, that is recorded in results.json, not skipped silently.
SMOKE_SCRIPT = r"""
import json, sys
model_path, prompts_path, out_path = sys.argv[1:4]
import litert_lm
prompts = json.load(open(prompts_path))
engine = litert_lm.Engine(model_path)
outputs = []
for prompt in prompts:
    session = engine.create_session()
    session.run_prefill([prompt])
    outputs.append(session.run_decode())
    close = getattr(session, "close", None)
    if close: close()
json.dump(outputs, open(out_path, "w"))
"""

def litert_smoke():
    root, venv = WORK / "litert", WORK / "litert" / "venv"
    python = str(venv / "bin" / "python")
    installed = None
    for package in LITERT_SMOKE_PACKAGES:
        exit_code, _ = run([python, "-m", "pip", "install", "-q", package], root / f"pip-{package}.log")
        if exit_code == 0 and subprocess.run([python, "-c", "import litert_lm"], capture_output=True).returncode == 0:
            installed = package
            break
    if installed is None:
        return {"ran": False, "reason": f"none of {LITERT_SMOKE_PACKAGES} installed and imported as litert_lm; the converted file was not loaded"}
    freeze = subprocess.run([python, "-m", "pip", "freeze"], capture_output=True, text=True).stdout.splitlines()
    tests = ALL["test"]
    stride = max(1, len(tests) // LITERT_AGREEMENT_ROWS)
    rows = tests[::stride][:LITERT_AGREEMENT_ROWS]
    (root / "smoke.py").write_text(SMOKE_SCRIPT)
    (root / "smoke-prompts.json").write_text(json.dumps(prompts_of(rows)))
    exit_code, output = run([python, str(root / "smoke.py"), LITERT["modelPath"], str(root / "smoke-prompts.json"), str(root / "smoke-out.json")],
                            root / "smoke.log", timeout=3 * 3600)
    if exit_code != 0:
        return {"ran": False, "package": installed, "pipFreeze": freeze, "reason": f"the smoke script failed (exit {exit_code}); log in {root / 'smoke.log'}: {output[-800:]}"}
    answers = [sanitize_on_device_text(a) for a in json.loads((root / "smoke-out.json").read_text())]
    gate1_same = [GATE1_BY_ID["test"][r["id"]] for r in rows]
    litert_scores, gate1_scores = call_score(rows, answers), call_score(rows, gate1_same)
    agree = sum((call_score([r], [a])[r["family"]] >= 1.0) == (call_score([r], [b])[r["family"]] >= 1.0)
                for r, a, b in zip(rows, answers, gate1_same))
    result = {"ran": True, "package": installed, "pipFreeze": freeze, "rows": len(rows),
              "litertScores": litert_scores, "gate1ScoresSameRows": gate1_scores,
              "rowLevelAgreement": agree / len(rows),
              "worstFamilyDrop": max(gate1_scores[f] - litert_scores.get(f, 0.0) for f in gate1_scores),
              "predictions": {r["id"]: a for r, a in zip(rows, answers)}}
    print(f"LiteRT-LM on {len(rows)} test rows: worst per-family drop against Gate 1 {result['worstFamilyDrop']:.3f}, row-level pass agreement {result['rowLevelAgreement']:.2f}")
    return result

if EXPORT_LITERTLM and LITERT.get("ran") and "smoke" not in LITERT:
    LITERT["smoke"] = litert_smoke()
    save_results()
if EXPORT_LITERTLM:
    smoke_result = LITERT.get("smoke", {})
    print("LiteRT-LM smoke test:", "ran" if smoke_result.get("ran") else f"DID NOT RUN: {smoke_result.get('reason')}")
''')

code(r'''
# ---- Release: assets, catalog.json, results.json -------------------------------------------------------------
import requests

def github_token():
    """GITHUB_TOKEN from Kaggle secrets, Colab secrets, or the environment, whichever this runs on. Never printed."""
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

def descriptor(logical_id, path, kind, precision, fmt, adapter_id=None, capabilities=()):
    d = {
        "logicalArtifactId": logical_id, "foundationModelId": BASE_MODEL,
        "releaseRepository": RELEASE_REPOSITORY, "releaseTag": RELEASE_TAG,
        "assetName": path.name, "sha256": sha256(path), "format": fmt, "precision": precision,
        "kind": kind, "capabilities": sorted(capabilities),
    }
    if adapter_id:
        d["adapterId"] = adapter_id
    return d

def pip_freeze():
    return subprocess.run([sys.executable, "-m", "pip", "freeze"], capture_output=True, text=True).stdout.splitlines()

def upload(paths, tag):
    token = github_token()
    api = f"https://api.github.com/repos/{RELEASE_REPOSITORY}"
    headers = {"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json"}
    r = requests.get(f"{api}/releases/tags/{tag}", headers=headers, timeout=60)
    if r.status_code == 404:
        r = requests.post(f"{api}/releases", headers=headers, timeout=60, json={
            "tag_name": tag, "name": tag, "prerelease": True,
            "body": "Aive app-integration LoRA adapter for Qwen2.5-0.5B-Instruct. See catalog.json and results.json.",
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
            raise RuntimeError(f"{tag}/{path.name} already exists with a different digest; release assets are immutable. "
                               "Bump RELEASE_TAG and VERSION before publishing a changed artifact.")
        with open(path, "rb") as f:
            up = requests.post(f"https://uploads.github.com/repos/{RELEASE_REPOSITORY}/releases/{release['id']}/assets",
                               params={"name": path.name}, data=f,
                               headers={**headers, "Content-Type": "application/octet-stream"}, timeout=3600)
        up.raise_for_status()
        uploaded = up.json()
        if uploaded.get("digest") not in (None, digest):
            raise RuntimeError(f"GitHub reported an unexpected digest for {path.name}")
        print("uploaded", path.name, digest)

gate_status = {"gate1": RESULTS["gate1"]["ok"], "gate2": RESULTS["gate2"]["ok"] if EXPORT_ONNX else None}
RELEASABLE = all(v for v in gate_status.values() if v is not None) and not SMOKE
RESULTS["releasable"] = RELEASABLE
RESULTS["pipFreeze"] = pip_freeze()
release_paths = []
if EXPORT_ONNX and RELEASABLE:
    adapter_descriptor = descriptor(ADAPTER_ARTIFACT_ID, ONNX["adapterPath"], "Adapter", "fp16", "safetensors",
                                    adapter_id=f"app-integration-{VERSION}", capabilities=[CAPABILITY])
    catalog = {"releaseRepository": RELEASE_REPOSITORY, "releaseTag": RELEASE_TAG, "specialists": [{
        "specialistId": SPECIALIST_ID,
        "scores": {"gate1": RESULTS["gate1"]["perFamily"], "gate2": RESULTS["gate2"]["perFamily"]},
        "sharedBaseVariants": [ONNX["base"]],
        "adapter": adapter_descriptor,
    }]}
    (ASSETS / "catalog.json").write_text(json.dumps(catalog, indent=2))
    release_paths += [ONNX["adapterPath"], ASSETS / "catalog.json"]
if EXPORT_LITERTLM and LITERT.get("ran") and RELEASABLE:
    litert_path = Path(LITERT["modelPath"])
    if litert_path.stat().st_size >= GITHUB_ASSET_LIMIT:
        LITERT["release"] = f"not uploaded: {litert_path.stat().st_size} bytes is over GitHub's 2 GiB asset limit; host it elsewhere and pin it by SHA-256"
        print(LITERT["release"])
    else:
        release_paths.append(litert_path)
        LITERT["release"] = f"uploaded as {litert_path.name}; pin it in LiteRtLmModelCatalog by hand (id, size, sha256 are in results.json)"
elif EXPORT_LITERTLM and not RELEASABLE:
    LITERT["release"] = "not released: a gate failed or this is a SMOKE run"
save_results()
if RELEASABLE:
    release_paths.append(RESULTS_FILE)
print("assets:", [p.name for p in release_paths] or "none")
if not RELEASABLE:
    print("NO RELEASE: " + ("SMOKE run" if SMOKE else f"gates {gate_status}"))
elif UPLOAD:
    upload(release_paths, RELEASE_TAG)
else:
    print(f"UPLOAD is False: the release files are in {ASSETS}; set UPLOAD = True to publish {RELEASE_TAG}")
''')

code(r'''
# ---- What to run next -----------------------------------------------------------------------------------------
if SMOKE:
    print("SMOKE run finished: every stage ran on tiny splits. Check results.json for what each stage reported, then set SMOKE = False and run again.")
elif not RELEASABLE:
    print(f"Nothing to release. Read the failing rows in {RESULTS_FILE}, improve the corpus, rebuild the dataset, run again.")
elif not UPLOAD:
    print(f"Set UPLOAD = True (with the GITHUB_TOKEN secret) and run this cell group again; nothing was published. Files: {ASSETS}")
else:
    print(f"""Published {RELEASE_TAG}. On your machine, from a checkout of {RELEASE_REPOSITORY}:

  curl -LO https://github.com/{RELEASE_REPOSITORY}/releases/download/{RELEASE_TAG}/catalog.json
  python3 tools/orchestration_training/register_catalog.py catalog.json
  git add -A && git commit -m "feat: register the app-integration adapter"

Notes:
  - register_catalog.py refuses specialist ids outside orchestration:* until the app has a catalog that
    reads {SPECIALIST_ID}; today that command stops with a message and changes nothing.
  - The merged LiteRT-LM file is not in catalog.json. To ship it, add its size and SHA-256 from results.json
    to LiteRtLmModelCatalog by hand, and test it on a device first.""")
''')

notebook = {
    "cells": CELLS,
    "metadata": {
        "kernelspec": {"display_name": "Python 3", "language": "python", "name": "python3"},
        "language_info": {"name": "python"},
        "kaggle": {"accelerator": "gpu", "isInternetEnabled": True, "isGpuEnabled": True},
    },
    "nbformat": 4,
    "nbformat_minor": 4,
}

TARGET = Path(__file__).with_name("app_integration_adapter.ipynb")


def render():
    return json.dumps(notebook, indent=1) + "\n"


if __name__ == "__main__":
    TARGET.write_text(render())
    print("wrote", TARGET)
