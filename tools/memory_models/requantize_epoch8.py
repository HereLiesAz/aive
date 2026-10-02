#!/usr/bin/env python3
"""Rebuild the epoch-8 memory INT8 models as weight-only INT8 that ONNX Runtime can load.

The released `haive-specialist_*-int8-epoch8` models were dynamically quantized over an fp16 graph:
`DequantizeLinear` gets fp16 scales under opset 18 and `DynamicQuantizeLinear` gets fp16
activations, neither of which is valid, so every one fails with INVALID_GRAPH. This script starts
from the released fp16 model of each role (verified against the SHA-256 the app pins), widens the
graph to fp32, and quantizes only the MatMul weights to INT8 (MatMulNBits), the same scheme the
orchestration helpers ship. Activations, the KV cache and logits stay fp32, which is what the
app's ONNX Runtime loops expect.

Each role is checked before it is packaged: the INT8 model must load and greedily decode the same
first tokens as the fp32 graph it came from on a fixed prompt.

    python3 tools/memory_models/requantize_epoch8.py                 # every generative role
    python3 tools/memory_models/requantize_epoch8.py --roles 01_sectioner
    python3 tools/memory_models/requantize_epoch8.py --upload        # needs GITHUB_TOKEN
    python3 tools/memory_models/requantize_epoch8.py --register build/memory-int8/release.json

Peak disk is about 4.5 GB per role (fp16 extract, fp32 graph, INT8 output); intermediates are
deleted as soon as they are used. `--upload` publishes the archives and `release.json` to the
`memory-layer-epoch8-r2` pre-release; release assets are immutable, so a changed archive needs a
new tag. `--register` writes the new INT8 hashes into `MemoryEpoch8LocalModelLibrary.kt`.
"""

import argparse
import hashlib
import json
import os
import re
import shutil
import sys
import tarfile
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CATALOG = ROOT / "shared/src/commonMain/kotlin/com/hereliesaz/geministrator/memory/MemoryEpoch8LocalModelLibrary.kt"
REPOSITORY = "HereLiesAz/aive"
SOURCE_TAG = "memory-layer-epoch8"
RELEASE_TAG = "memory-layer-epoch8-r2"
ROLES = [
    "01_sectioner", "02_salience", "03_noun_indexer", "04_verb_indexer", "05_phrase_synthesizer",
    "06_summary_synthesizer", "07_category_classifier", "09_condensation_rewriter",
]
CHECK_PROMPT = (
    "<|im_start|>system\nYou are a memory clerk. Answer with JSON only.<|im_end|>\n"
    "<|im_start|>user\nThe API timeout was raised to 60 seconds after the deploy failed.<|im_end|>\n"
    "<|im_start|>assistant\n"
)
CHECK_TOKENS = 12


def asset_name(slug: str) -> str:
    return f"haive-specialist_{slug}-int8-epoch8r2.tar.gz"


def pinned_fp16_sha(slug: str) -> str:
    """The fp16 SHA-256 the app pins for [slug], read from the Kotlin catalog."""
    text = CATALOG.read_text()
    match = re.search(r'slug = "' + re.escape(slug) + r'",\s*fp16Sha = "([0-9a-f]{64})"', text)
    if not match:
        sys.exit(f"{slug}: no fp16Sha in {CATALOG}")
    return match.group(1)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


class HashingReader:
    """Hashes a stream while tarfile reads it, so the archive is never written to disk."""

    def __init__(self, stream):
        self.stream, self.digest = stream, hashlib.sha256()

    def read(self, size=-1):
        block = self.stream.read(size)
        self.digest.update(block)
        return block


def fetch_fp16(slug: str, destination: Path):
    url = f"https://github.com/{REPOSITORY}/releases/download/{SOURCE_TAG}/haive-specialist_{slug}-fp16-epoch8.tar.gz"
    expected = pinned_fp16_sha(slug)
    shutil.rmtree(destination, ignore_errors=True)
    destination.mkdir(parents=True)
    print(f"[{slug}] downloading fp16", flush=True)
    with urllib.request.urlopen(url) as response:
        reader = HashingReader(response)
        with tarfile.open(fileobj=reader, mode="r|gz") as archive:
            archive.extractall(destination, filter="data")
        while reader.read(1 << 20):
            pass
    actual = reader.digest.hexdigest()
    if actual != expected:
        shutil.rmtree(destination)
        sys.exit(f"[{slug}] fp16 SHA-256 mismatch: expected {expected}, got {actual}")


def model_root(directory: Path) -> Path:
    return next(directory.rglob("model.onnx")).parent


def widen_to_fp32(source: Path, target: Path):
    """Rewrites every fp16 initializer, constant, cast and declared type in the graph as fp32."""
    import numpy as np
    import onnx
    from onnx import TensorProto, numpy_helper

    model = onnx.load(str(source))
    graph = model.graph

    def widen_tensor(tensor):
        if tensor.data_type == TensorProto.FLOAT16:
            tensor.CopyFrom(numpy_helper.from_array(numpy_helper.to_array(tensor).astype(np.float32), tensor.name))

    def widen_type(value):
        tensor_type = value.type.tensor_type
        if tensor_type.elem_type == TensorProto.FLOAT16:
            tensor_type.elem_type = TensorProto.FLOAT

    for initializer in graph.initializer:
        widen_tensor(initializer)
    for value in list(graph.input) + list(graph.output) + list(graph.value_info):
        widen_type(value)
    for node in graph.node:
        for attribute in node.attribute:
            if node.op_type == "Cast" and attribute.name == "to" and attribute.i == TensorProto.FLOAT16:
                attribute.i = TensorProto.FLOAT
            if attribute.type == onnx.AttributeProto.TENSOR:
                widen_tensor(attribute.t)
        if any(a.type == onnx.AttributeProto.GRAPH for a in node.attribute):
            sys.exit(f"{source}: subgraphs are not handled")
    target.parent.mkdir(parents=True, exist_ok=True)
    onnx.save(model, str(target), save_as_external_data=True, location="model.onnx.data")


def quantize(source: Path, target: Path):
    import onnx
    from onnxruntime.quantization.matmul_nbits_quantizer import DefaultWeightOnlyQuantConfig, MatMulNBitsQuantizer

    model = onnx.load(str(source))
    config = DefaultWeightOnlyQuantConfig(
        block_size=32, is_symmetric=True, accuracy_level=4, op_types_to_quantize=("MatMul",), bits=8,
    )
    quantizer = MatMulNBitsQuantizer(
        model, block_size=32, is_symmetric=True, accuracy_level=4, op_types_to_quantize=("MatMul",),
        algo_config=config,
    )
    quantizer.process()
    target.parent.mkdir(parents=True, exist_ok=True)
    quantizer.model.save_model_to_file(str(target), use_external_data_format=True)


def greedy_tokens(model_path: Path, tokenizer_path: Path) -> list[int]:
    """Greedy decode without a cache: every step reruns the whole sequence with empty past tensors."""
    import numpy as np
    import onnxruntime as ort
    from tokenizers import Tokenizer

    session = ort.InferenceSession(str(model_path), providers=["CPUExecutionProvider"])
    ids = Tokenizer.from_file(str(tokenizer_path)).encode(CHECK_PROMPT).ids
    generated = []
    for _ in range(CHECK_TOKENS):
        sequence = np.array([ids + generated], dtype=np.int64)
        feeds = {}
        for spec in session.get_inputs():
            if spec.name == "input_ids":
                feeds[spec.name] = sequence
            elif spec.name == "attention_mask":
                feeds[spec.name] = np.ones_like(sequence)
            elif spec.name == "position_ids":
                feeds[spec.name] = np.arange(sequence.shape[1], dtype=np.int64)[None, :]
            elif spec.name.startswith("past_key_values."):
                heads, head_dim = spec.shape[1], spec.shape[3]
                feeds[spec.name] = np.zeros((1, heads, 0, head_dim), dtype=np.float32)
            else:
                sys.exit(f"{model_path}: unexpected input {spec.name}")
        logits = session.run(["logits"], feeds)[0]
        if not np.isfinite(logits[0, -1]).all():
            sys.exit(f"{model_path}: non-finite logits")
        generated.append(int(logits[0, -1].argmax()))
    return generated


def build(slug: str, work: Path, dist: Path) -> dict:
    fp16_dir, fp32_dir, int8_dir = work / slug / "fp16", work / slug / "fp32", work / slug / "int8"
    if (fp32_dir / "model.onnx").is_file() and (fp16_dir / ".consumed").is_file():
        source_root = next(fp16_dir.rglob("tokenizer.json")).parent  # resumed after the widen step
    else:
        fetch_fp16(slug, fp16_dir)
        source_root = model_root(fp16_dir)
        print(f"[{slug}] widening to fp32", flush=True)
        widen_to_fp32(source_root / "model.onnx", fp32_dir / "model.onnx")
        for name in ("model.onnx", "model.onnx.data", "model.onnx_data"):
            (source_root / name).unlink(missing_ok=True)
        (fp16_dir / ".consumed").touch()
    print(f"[{slug}] quantizing MatMul weights to INT8", flush=True)
    quantize(fp32_dir / "model.onnx", int8_dir / "model.onnx")

    tokenizer = source_root / "tokenizer.json"
    reference = greedy_tokens(fp32_dir / "model.onnx", tokenizer)
    shutil.rmtree(fp32_dir)
    quantized = greedy_tokens(int8_dir / "model.onnx", tokenizer)
    print(f"[{slug}] fp32 {reference}\n[{slug}] int8 {quantized}", flush=True)
    if quantized != reference:
        sys.exit(f"[{slug}] INT8 greedy tokens differ from the fp32 graph; not packaging")

    for item in source_root.iterdir():
        if item.is_file() and not item.name.startswith("."):
            shutil.copy2(item, int8_dir / item.name)
    shutil.rmtree(fp16_dir)
    manifest = int8_dir / "model-manifest.json"
    data = json.loads(manifest.read_text()) if manifest.is_file() else {}
    data.update({"precision": "int8", "quantization": "weight-only MatMulNBits, 8-bit, block 32",
                 "source": f"{SOURCE_TAG}/haive-specialist_{slug}-fp16-epoch8.tar.gz"})
    manifest.write_text(json.dumps(data, indent=2))

    dist.mkdir(parents=True, exist_ok=True)
    archive = dist / asset_name(slug)
    print(f"[{slug}] packaging {archive.name}", flush=True)
    with tarfile.open(archive, "w:gz") as tar:
        for item in sorted(int8_dir.iterdir()):
            tar.add(item, arcname=item.name)
    shutil.rmtree(work / slug)
    return {"slug": slug, "assetName": archive.name, "sha256": sha256(archive), "size": archive.stat().st_size}


def upload(dist: Path, entries: list[dict]):
    token = os.environ.get("GITHUB_TOKEN") or sys.exit("--upload needs GITHUB_TOKEN with contents write")
    headers = {"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json"}

    def call(url, data=None, content_type="application/json"):
        request = urllib.request.Request(url, data=data, headers={**headers, "Content-Type": content_type})
        with urllib.request.urlopen(request) as response:
            return json.loads(response.read() or b"null")

    api = f"https://api.github.com/repos/{REPOSITORY}"
    try:
        release = call(f"{api}/releases/tags/{RELEASE_TAG}")
    except urllib.error.HTTPError as failure:
        if failure.code != 404:
            raise
        release = call(f"{api}/releases", json.dumps({
            "tag_name": RELEASE_TAG, "name": RELEASE_TAG, "prerelease": True,
            "body": "Epoch-8 memory models, re-quantized as weight-only INT8 from the epoch-8 fp16 releases.",
        }).encode())
    existing = {asset["name"]: asset.get("digest") for asset in release.get("assets", [])}
    for path in [dist / entry["assetName"] for entry in entries] + [dist / "release.json"]:
        digest = "sha256:" + sha256(path)
        if path.name in existing:
            if existing[path.name] != digest:
                sys.exit(f"{RELEASE_TAG}/{path.name} exists with a different digest; release assets are immutable")
            print("already uploaded", path.name)
            continue
        call(f"https://uploads.github.com/repos/{REPOSITORY}/releases/{release['id']}/assets?name={path.name}",
             path.read_bytes(), "application/octet-stream")
        print("uploaded", path.name)


def register(release_json: Path):
    """Writes the new INT8 hashes into the Kotlin catalog."""
    entries = json.loads(release_json.read_text())["assets"]
    text = CATALOG.read_text()
    for entry in entries:
        pattern = r'(slug = "' + re.escape(entry["slug"]) + r'",\s*fp16Sha = "[0-9a-f]{64}",\s*int8Sha = ")[0-9a-f]{64}(")'
        text, count = re.subn(pattern, r"\g<1>" + entry["sha256"] + r"\g<2>", text)
        if count != 1:
            sys.exit(f"{entry['slug']}: no int8Sha to replace in {CATALOG}")
    CATALOG.write_text(text)
    print(f"registered {len(entries)} INT8 model(s) in {CATALOG.relative_to(ROOT)}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--roles", nargs="*", default=ROLES, choices=ROLES)
    parser.add_argument("--work", type=Path, default=ROOT / "build/memory-int8/work")
    parser.add_argument("--dist", type=Path, default=ROOT / "build/memory-int8")
    parser.add_argument("--upload", action="store_true")
    parser.add_argument("--register", type=Path, metavar="RELEASE_JSON")
    args = parser.parse_args()
    if args.register:
        register(args.register)
        return
    release_json = args.dist / "release.json"
    previous = json.loads(release_json.read_text())["assets"] if release_json.is_file() else []
    entries = {entry["slug"]: entry for entry in previous}
    for slug in args.roles:
        if slug in entries and (args.dist / entries[slug]["assetName"]).is_file():
            print(f"[{slug}] already built")
            continue
        entries[slug] = build(slug, args.work, args.dist)
        release_json.write_text(json.dumps({"releaseTag": RELEASE_TAG, "assets": sorted(entries.values(), key=lambda e: e["slug"])}, indent=2))
    if args.upload:
        upload(args.dist, sorted(entries.values(), key=lambda e: e["slug"]))


if __name__ == "__main__":
    main()
