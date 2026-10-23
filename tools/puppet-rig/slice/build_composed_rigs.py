#!/usr/bin/env python3
"""Build `aive-puppet-rig` v1 rigs for the core roles from their composed character art.

The 14 core roles (orchestrator ... hall monitor) have no exploded parts sheet, only one composed,
transparent-background painting each (`docs/swarm-terrarium/characters/<NNN_role>/`).  This script
turns each painting into a rig + atlas in `files/rigs/` without hand work:

* single-part rigs: the painting (alpha cleaned, cropped, scaled to <= 512 px) is one `main` part
  under an invisible `body` bone placed at the soma centre.  Motion is whole-body: bob, tilt,
  squash/stretch (breathing), a droop for Blocked/Failed and a hop for Complete.  Eyes and limbs are
  not separated because the painting has no hidden layers behind them (cutting them out would leave
  holes), so the creature does not blink.
* layered rigs (`LAYERED`): where hand-cut, full-canvas-registered layers exist
  (`<dir>/rig/bodyparts/*.png`) they are packed into the atlas as separate parts with a real
  hierarchy (soma, eyes + lids, dendrites, tail chain) and blink/sway motion.

Usage:  python3 tools/puppet-rig/slice/build_composed_rigs.py [--only <slug>] [--preview]
Requires Python 3 with Pillow, numpy and scipy.  Rig/atlas names follow the rest of `files/rigs/`:
`<slug>.rig.json` and `<slug_underscored>_rig_atlas.png`.  `--preview` writes contact sheets to
`$TMPDIR/composed-rig-preview/`.
"""
from __future__ import annotations

import argparse
import glob
import json
import math
import os
import sys
import tempfile

import numpy as np
from PIL import Image
from scipy import ndimage as ndi

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import build_rigs as br  # noqa: E402  (pack, evaluate, render helpers shared with the sheet pipeline)

REPO = br.REPO
CHAR_ROOT = br.CHAR_ROOT
RIG_OUT = br.RIG_OUT
MAX_SIDE = 512
PAD = 2

# slug -> (character directory, composed image glob inside it)
COMPOSED = {
    "orchestrator": ("001_orchestrator", "ChatGPT Image Sep 25*.png"),
    "ux-designer": ("002_ux_designer", "ChatGPT Image Sep 25*.png"),
    "researcher": ("003_researcher", "ChatGPT Image Sep 25*.png"),
    "implementation-engineer": ("004_implementation_engineer", "approved_character.png"),
    "epa-representative": ("006_epa_representative", "ChatGPT Image Sep 25*.png"),
    "adversarial-reviewer": ("007_adversarial_reviewer", "ChatGPT Image Sep 25*.png"),
    "qa-engineer": ("008_qa_engineer", "ChatGPT Image Sep 25*.png"),
    "release-engineer": ("009_release_engineer", "ChatGPT Image Sep 25*.png"),
    "antagonist": ("010_antagonist", "ChatGPT Image Sep 25*.png"),
    "product-manager": ("011_product_manager", "ChatGPT Image Sep 25*.png"),
    "crash-test-dummy": ("012_crash_test_dummy", "ChatGPT Image Sep 25*.png"),
    "recovery-engineer": ("013_recovery_engineer", "ChatGPT Image Sep 25*.png"),
    "code-reviewer": ("014_code_reviewer", "ChatGPT Image Sep 25*.png"),
    "hall-monitor": ("015_hall_monitor", "ChatGPT Image Sep 25*.png"),
}

# Roles rigged from hand-cut layers: slug -> dict(
#   dir, parts = [(id, file stem, parent id, role)], order = draw order low -> high)
LAYERED = {
    "ux-designer": dict(
        dir="002_ux_designer",
        parts=[
            # id, file, parent, cls
            ("tail.base", "gestation_base", "body", "tail"),
            ("tail.mid", "gestation_mid", "tail.base", "tail"),
            ("tail.lower", "gestation_lower", "tail.mid", "tail"),
            ("tail.egg", "gestation_egg", "tail.lower", "tail"),
            ("tail.pod.0", "gestation_pod_01", "tail.base", "tailpod"),
            ("tail.pod.1", "gestation_pod_02", "tail.mid", "tailpod"),
            ("tendril.0", "dendrite_top", "body", "tendril"),
            ("tendril.1", "dendrite_right_heart", "body", "tendril"),
            ("tendril.2", "dendrite_right_upper", "body", "tendril"),
            ("tendril.3", "dendrite_right_lower", "body", "tendril"),
            ("tendril.4", "dendrite_left_lower", "body", "tendril"),
            ("tendril.5", "dendrite_left_upper", "body", "tendril"),
            ("soma", "body_core", "body", "soma"),
            ("eye.left", "eye_left", "soma", "eye"),
            ("eye.right", "eye_right", "soma", "eye"),
            ("lid.upper.left", "eyelid_upper_left", "eye.left", "lidup"),
            ("lid.lower.left", "eyelid_lower_left", "eye.left", "lidlow"),
            ("lid.upper.right", "eyelid_upper_right", "eye.right", "lidup"),
            ("lid.lower.right", "eyelid_lower_right", "eye.right", "lidlow"),
        ],
    ),
}
LID_REST = 0.02  # lids rest collapsed (eyes open); keyed scaleY is a factor on this
Z = {"tail": 0, "tailpod": 1, "tendril": 2, "soma": 3, "eye": 4, "lidup": 5, "lidlow": 5}


def r2(v):
    return round(float(v), 2)


# ----------------------------------------------------------------------------- image helpers

def clean_alpha(a: np.ndarray) -> np.ndarray:
    """Drop the 1..5 alpha haze left by background removal and snap near-opaque pixels to 255."""
    a = a.astype(np.float32)
    out = np.clip((a - 6.0) / (246.0 - 6.0), 0.0, 1.0) * 255.0
    return np.rint(out).astype(np.uint8)


def load_clean(path: str) -> Image.Image:
    arr = np.array(Image.open(path).convert("RGBA"))
    arr[..., 3] = clean_alpha(arr[..., 3])
    arr[arr[..., 3] == 0, :3] = 0
    return Image.fromarray(arr, "RGBA")


def drop_specks(img: Image.Image, frac: float = 0.04) -> Image.Image:
    """Remove stray fragments (cut-out debris) smaller than `frac` of the layer's largest component."""
    arr = np.array(img)
    lab, n = ndi.label(arr[..., 3] > 8, structure=np.ones((3, 3)))
    if n < 2:
        return img
    sizes = np.bincount(lab.ravel())
    sizes[0] = 0
    keep = sizes >= sizes.max() * frac
    keep[0] = False
    grown = ndi.binary_dilation(keep[lab], iterations=2)  # keep the soft edge pixels around kept parts
    arr[~grown] = 0
    return Image.fromarray(arr, "RGBA")


def resize_premult(img: Image.Image, size: tuple[int, int]) -> Image.Image:
    """LANCZOS resize in premultiplied space so soft edges get no dark/light halo."""
    arr = np.asarray(img).astype(np.float32)
    a = arr[..., 3:4] / 255.0
    chans = []
    for k in range(3):
        ch = Image.fromarray((arr[..., k] * a[..., 0]).astype(np.float32), "F").resize(size, Image.LANCZOS)
        chans.append(np.asarray(ch))
    al = np.asarray(Image.fromarray(arr[..., 3].astype(np.float32), "F").resize(size, Image.LANCZOS))
    al = np.clip(al, 0, 255)
    safe = np.maximum(al / 255.0, 1e-4)
    rgb = np.stack([np.clip(c / safe, 0, 255) for c in chans], axis=-1)
    rgb[al < 0.5] = 0
    out = np.concatenate([rgb, al[..., None]], axis=-1)
    return Image.fromarray(np.rint(out).astype(np.uint8), "RGBA")


def crop_to_alpha(img: Image.Image, thresh: int = 8):
    a = np.asarray(img)[..., 3]
    ys, xs = np.nonzero(a >= thresh)
    box = (int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1)
    return img.crop(box), box


def soma_circle(alpha: np.ndarray):
    """Centre and radius of the thickest blob (the soma): maximum of the smoothed distance transform."""
    dist = ndi.distance_transform_edt(alpha > 128)
    dist = ndi.gaussian_filter(dist, 3)
    cy, cx = np.unravel_index(int(np.argmax(dist)), dist.shape)
    return float(cx), float(cy), float(dist[cy, cx])


# ----------------------------------------------------------------------------- motion

def keys_blink(times, lo, hi, dur=0.035):
    """Keyed channel that sits at `hi`, dips to `lo` at each blink time and returns."""
    keys = [{"t": 0.0, "v": hi, "ease": "step"}]
    for t in times:
        keys += [{"t": r2(t), "v": hi, "ease": "easeIn"}, {"t": r2(t + dur), "v": lo, "ease": "easeOut"},
                 {"t": r2(t + 2 * dur), "v": hi, "ease": "step"}]
    return keys


def sine(amp, freq, phase=0.0):
    return {"amplitude": r2(amp), "frequency": freq, "phase": r2(phase)}


def hop_keys(u):
    return {
        "y": [{"t": 0, "v": 0, "ease": "easeOut"}, {"t": 0.12, "v": r2(-11 * u), "ease": "easeIn"},
              {"t": 0.26, "v": 0, "ease": "easeOut"}, {"t": 0.34, "v": r2(-3.9 * u), "ease": "easeIn"},
              {"t": 0.42, "v": 0, "ease": "linear"}],
        "scaleY": [{"t": 0, "v": 1, "ease": "linear"}, {"t": 0.26, "v": 1, "ease": "easeOut"},
                   {"t": 0.3, "v": 0.93, "ease": "easeOut"}, {"t": 0.38, "v": 1, "ease": "linear"}],
        "scaleX": [{"t": 0, "v": 1, "ease": "linear"}, {"t": 0.26, "v": 1, "ease": "easeOut"},
                   {"t": 0.3, "v": 1.05, "ease": "easeOut"}, {"t": 0.38, "v": 1, "ease": "linear"}],
    }


# state -> (bob amp, bob freq, tilt amp, squash amp, squash freq, wobble amp)
SINGLE_STATE = {
    "Pending": (1.3, 1, 0.8, 0.012, 1, 0.5),
    "Ready": (2.0, 1, 1.2, 0.018, 1, 0.9),
    "Active": (3.9, 2, 2.2, 0.035, 2, 1.6),
    "Gate": (0.55, 1, 0.4, 0.008, 1, 0.3),
    "Blocked": (1.1, 1, 0.0, 0.01, 1, 0.3),
    "Complete": (0.0, 1, 0.0, 0.0, 1, 0.0),
    "Failed": (0.66, 1, 0.0, 0.0, 1, 0.0),
}


def single_states(u: float):
    """Whole-body motion for a one-part creature: `body` bone (bob/tilt/hop) and `main` (squash/wobble)."""
    states = {}
    for name, (bob, bf, tilt, sq, sf, wob) in SINGLE_STATE.items():
        body: dict = {"keys": {}, "sine": {}}
        main: dict = {"keys": {}, "sine": {}}
        if bob:
            body["sine"]["y"] = sine(bob * u, bf, 0)
        if tilt:
            body["sine"]["rotation"] = sine(tilt, 1, 0.25)
        if sq:
            main["sine"]["scaleY"] = sine(sq, sf, 0.25)
            main["sine"]["scaleX"] = sine(-sq * 0.7, sf, 0.25)
        if wob:
            main["sine"]["rotation"] = sine(wob, 1, 0.6)
        if name == "Blocked":
            body["keys"] = {"y": [{"t": 0, "v": r2(4.4 * u), "ease": "linear"}], "rotation": [{"t": 0, "v": -5, "ease": "linear"}]}
            main["keys"] = {"scaleY": [{"t": 0, "v": 0.965, "ease": "linear"}], "scaleX": [{"t": 0, "v": 1.02, "ease": "linear"}]}
        elif name == "Failed":
            body["keys"] = {"y": [{"t": 0, "v": r2(10 * u), "ease": "linear"}], "rotation": [{"t": 0, "v": -7, "ease": "linear"}],
                            "alpha": [{"t": 0, "v": 0.92, "ease": "linear"}]}
            body["sine"]["x"] = sine(1.1 * u, 13, 0)
            main["keys"] = {"scaleY": [{"t": 0, "v": 0.93, "ease": "linear"}], "scaleX": [{"t": 0, "v": 1.04, "ease": "linear"}]}
        elif name == "Gate":
            main["keys"] = {"scaleX": [{"t": 0, "v": 1.02, "ease": "linear"}]}
        elif name == "Complete":
            body["keys"] = hop_keys(u)
            main["sine"]["scaleY"] = sine(0.012, 1, 0.25)
            main["sine"]["scaleX"] = sine(-0.008, 1, 0.25)
        states[name] = {"parts": {"body": body, "main": main}}
    return states


# ----------------------------------------------------------------------------- single-part rig

def build_single(slug: str, directory: str, pattern: str):
    src = glob.glob(os.path.join(CHAR_ROOT, directory, pattern))
    if not src:
        raise SystemExit(f"{slug}: no composed art matching {directory}/{pattern}")
    img = load_clean(sorted(src)[0])
    img, _ = crop_to_alpha(img)
    s = MAX_SIDE / max(img.size)
    inner = (max(2, round(img.width * s)), max(2, round(img.height * s)))
    img = resize_premult(img, inner)
    atlas = Image.new("RGBA", (inner[0] + 2 * PAD, inner[1] + 2 * PAD), (0, 0, 0, 0))
    atlas.paste(img, (PAD, PAD))
    W, H = atlas.size
    cx, cy, rad = soma_circle(np.asarray(atlas)[..., 3])
    atlas_name = slug.replace("-", "_") + "_rig_atlas.png"
    parts = [
        {"id": "body", "parent": None, "z": 0, "size": {"w": 0, "h": 0}, "pivot": {"x": 0.5, "y": 0.5},
         "rest": {"x": r2(cx), "y": r2(cy), "rotation": 0, "scaleX": 1, "scaleY": 1, "alpha": 1}},
        {"id": "main", "parent": "body", "z": 1, "kind": "body",
         "rect": {"x": 0, "y": 0, "w": W, "h": H}, "size": {"w": W, "h": H},
         "pivot": {"x": r2(cx / W), "y": r2(cy / H)},
         "rest": {"x": 0, "y": 0, "rotation": 0, "scaleX": 1, "scaleY": 1, "alpha": 1}},
    ]

    def socket(pid, dx, dy):
        return {"id": pid, "part": "main", "x": r2((cx + dx * rad * 0.92) / W), "y": r2((cy + dy * rad * 0.92) / H), "kind": "arm-socket"}

    attachments = [
        socket("arm.socket.left", -1, 0.25), socket("arm.socket.right", 1, 0.25),
        socket("arm.socket.top", 0, -1), socket("arm.socket.bottom", 0, 1),
    ]
    rig = {
        "format": "aive-puppet-rig", "version": 1, "role": slug,
        "canvas": {"width": W, "height": H},
        "atlas": {"image": atlas_name, "width": W, "height": H},
        "parts": parts, "attachments": attachments,
        "states": single_states(max(W, H) / 520.0),
        "fallbackState": "Pending",
    }
    return rig, atlas


# ----------------------------------------------------------------------------- layered rig

class Layer:
    def __init__(self, pid, parent, cls, img, x0, y0):
        self.id, self.parent, self.cls = pid, parent, cls
        self.img, self.x0, self.y0 = img, x0, y0  # top-left in canvas px
        self.w, self.h = img.size
        self.pivot = (x0 + self.w / 2, y0 + self.h / 2)

    def canvas_mask(self, W, H):
        m = np.zeros((H, W), bool)
        a = np.asarray(self.img)[..., 3] > 64
        m[self.y0:self.y0 + self.h, self.x0:self.x0 + self.w] = a[: H - self.y0, : W - self.x0]
        return m


def build_layered(slug: str, spec: dict):
    base = os.path.join(CHAR_ROOT, spec["dir"], "rig", "bodyparts")
    raw = {}
    for pid, stem, parent, cls in spec["parts"]:
        raw[pid] = (drop_specks(load_clean(os.path.join(base, stem + ".png"))), parent, cls)
    # union bbox over all layers (layers are full-canvas and registered to each other)
    boxes = [crop_to_alpha(im)[1] for im, _, _ in raw.values()]
    ux0, uy0 = min(b[0] for b in boxes), min(b[1] for b in boxes)
    ux1, uy1 = max(b[2] for b in boxes), max(b[3] for b in boxes)
    s = MAX_SIDE / max(ux1 - ux0, uy1 - uy0)
    W = int(math.ceil((ux1 - ux0) * s)) + 2 * PAD
    H = int(math.ceil((uy1 - uy0) * s)) + 2 * PAD
    layers: dict[str, Layer] = {}
    for pid, (im, parent, cls) in raw.items():
        c, b = crop_to_alpha(im)
        w, h = max(2, round(c.width * s)), max(2, round(c.height * s))
        c = resize_premult(c, (w, h))
        x0 = int(round((b[0] - ux0) * s)) + PAD
        y0 = int(round((b[1] - uy0) * s)) + PAD
        layers[pid] = Layer(pid, parent, cls, c, x0, y0)
    masks = {pid: L.canvas_mask(W, H) for pid, L in layers.items()}

    # soma frame
    soma = layers["soma"]
    sa = masks["soma"]
    dist = ndi.gaussian_filter(ndi.distance_transform_edt(sa), 3)
    scy, scx = np.unravel_index(int(np.argmax(dist)), dist.shape)
    scx, scy, srad = float(scx), float(scy), float(dist[scy, scx])
    soma.pivot = (scx, scy)

    def nearest_point(child, parent_mask, count=40):
        """Mean of the child's pixels nearest to the parent mask (where the child attaches)."""
        d = ndi.distance_transform_edt(~parent_mask)
        ys, xs = np.nonzero(masks[child])
        order = np.argsort(d[ys, xs])[:count]
        return float(xs[order].mean()), float(ys[order].mean())

    def far_point(child, pivot):
        ys, xs = np.nonzero(masks[child])
        k = int(np.argmax((xs - pivot[0]) ** 2 + (ys - pivot[1]) ** 2))
        return float(xs[k]), float(ys[k])

    for pid, L in layers.items():
        if L.cls in ("tendril",):
            L.pivot = nearest_point(pid, masks["soma"])
        elif L.cls in ("tail", "tailpod"):
            parent_mask = masks["soma"] if L.parent == "body" else masks[L.parent]
            L.pivot = nearest_point(pid, parent_mask)
        elif L.cls == "eye":
            L.pivot = (L.x0 + L.w / 2, L.y0 + L.h / 2)
        elif L.cls == "lidup":
            L.pivot = (L.x0 + L.w / 2, L.y0)
        elif L.cls == "lidlow":
            L.pivot = (L.x0 + L.w / 2, L.y0 + L.h)

    # atlas
    items = [(pid, L.w, L.h) for pid, L in layers.items()]
    rects, (AW, AH) = br.pack(items)
    atlas = Image.new("RGBA", (AW, AH), (0, 0, 0, 0))
    for pid, (x, y, w, h) in rects.items():
        atlas.paste(layers[pid].img, (x, y))

    def world_pivot(pid):
        return (scx, scy) if pid == "body" else layers[pid].pivot

    parts = [{"id": "body", "parent": None, "z": 0, "size": {"w": 0, "h": 0}, "pivot": {"x": 0.5, "y": 0.5},
              "rest": {"x": r2(scx), "y": r2(scy), "rotation": 0, "scaleX": 1, "scaleY": 1, "alpha": 1}}]
    for pid, L in layers.items():
        x, y, w, h = rects[pid]
        px, py = world_pivot(L.parent)
        parts.append({
            "id": pid, "parent": L.parent, "z": Z[L.cls],
            "rect": {"x": x, "y": y, "w": w, "h": h}, "size": {"w": w, "h": h},
            "pivot": {"x": r2((L.pivot[0] - L.x0) / L.w), "y": r2((L.pivot[1] - L.y0) / L.h)},
            "rest": {"x": r2(L.pivot[0] - px), "y": r2(L.pivot[1] - py), "rotation": 0, "scaleX": 1,
                     "scaleY": LID_REST if L.cls.startswith("lid") else 1, "alpha": 1},
        })
    atlas_name = slug.replace("-", "_") + "_rig_atlas.png"

    def at(pid, fx, fy, kind, aid):
        L = layers[pid]
        return {"id": aid, "part": pid, "x": r2(fx), "y": r2(fy), "kind": kind}

    soma_w = soma
    attachments = [
        {"id": "arm.socket.left", "part": "soma", "x": r2((scx - 0.92 * srad - soma_w.x0) / soma_w.w), "y": r2((scy + 0.25 * srad - soma_w.y0) / soma_w.h), "kind": "arm-socket"},
        {"id": "arm.socket.right", "part": "soma", "x": r2((scx + 0.92 * srad - soma_w.x0) / soma_w.w), "y": r2((scy + 0.25 * srad - soma_w.y0) / soma_w.h), "kind": "arm-socket"},
        {"id": "arm.socket.top", "part": "soma", "x": r2((scx - soma_w.x0) / soma_w.w), "y": r2((scy - 0.92 * srad - soma_w.y0) / soma_w.h), "kind": "arm-socket"},
        {"id": "arm.socket.bottom", "part": "soma", "x": r2((scx - soma_w.x0) / soma_w.w), "y": r2((scy + 0.92 * srad - soma_w.y0) / soma_w.h), "kind": "arm-socket"},
        at("eye.left", 0.5, 0.5, "eye", "eye.left"), at("eye.right", 0.5, 0.5, "eye", "eye.right"),
    ]

    # droop direction: clockwise rotation lowers the tip of a limb whose tip is right of its pivot
    droop = {}
    for pid, L in layers.items():
        if L.cls in ("tendril", "tail", "tailpod"):
            fx, _ = far_point(pid, L.pivot)
            droop[pid] = 1.0 if fx >= L.pivot[0] else -1.0
    rig = {
        "format": "aive-puppet-rig", "version": 1, "role": slug,
        "canvas": {"width": W, "height": H},
        "atlas": {"image": atlas_name, "width": AW, "height": AH},
        "parts": parts, "attachments": attachments,
        "states": layered_states(layers, droop, max(W, H) / 520.0),
        "fallbackState": "Pending",
    }
    return rig, atlas


def layered_states(layers: dict[str, Layer], droop: dict[str, float], u: float):
    tendrils = sorted(p for p, L in layers.items() if L.cls == "tendril")
    tails = [p for p in ("tail.base", "tail.mid", "tail.lower", "tail.egg") if p in layers]
    pods = sorted(p for p, L in layers.items() if L.cls == "tailpod")
    eyes = sorted(p for p, L in layers.items() if L.cls == "eye")
    lidup = sorted(p for p, L in layers.items() if L.cls == "lidup")
    lidlow = sorted(p for p, L in layers.items() if L.cls == "lidlow")

    # (bob, bob f, tilt, tendril amp, tendril f, tail amp, blink times, droop deg, lid close, sag, tremor, alpha)
    cfg = {
        "Pending": (1.3, 1, 0.0, 2.0, 1, 2.0, [0.62], 0, 0.0, 0, 0, None),
        "Ready": (2.0, 1, 0.8, 3.0, 1, 3.0, [0.45, 0.9], 0, 0.0, 0, 0, None),
        "Active": (3.9, 2, 2.0, 8.0, 2, 5.0, [0.3, 0.8], 0, 0.0, 0, 0, None),
        "Gate": (0.55, 1, 0.0, 1.0, 1, 1.0, [0.2, 0.28, 0.7], 0, 0.0, 0, 0, None),
        "Blocked": (1.1, 1, 0.0, 2.0, 1, 2.0, [0.5], 14, 0.38, 4.4, 0, None),
        "Complete": (0.0, 1, 0.0, 3.0, 1, 3.0, [0.7], 0, 0.0, 0, 0, None),
        "Failed": (0.66, 1, 0.0, 1.5, 1, 1.5, [0.4], 24, 0.62, 10, 1.1, 0.92),
    }
    states = {}
    for name, (bob, bf, tilt, ta, tf, tla, blinks, dr, lidclose, sag, tremor, alpha) in cfg.items():
        parts = {}
        body = {"keys": {}, "sine": {}}
        if bob:
            body["sine"]["y"] = sine(bob * u, bf, 0)
        if tilt:
            body["sine"]["rotation"] = sine(tilt, 1, 0.25)
        if sag:
            body["keys"]["y"] = [{"t": 0, "v": r2(sag * u), "ease": "linear"}]
            body["keys"]["rotation"] = [{"t": 0, "v": -5, "ease": "linear"}]
        if tremor:
            body["sine"]["x"] = sine(tremor * u, 13, 0)
        if alpha is not None:
            body["keys"]["alpha"] = [{"t": 0, "v": alpha, "ease": "linear"}]
        if name == "Complete":
            body["keys"] = hop_keys(u)
        parts["body"] = body
        for i, pid in enumerate(tendrils):
            m = {"keys": {}, "sine": {"rotation": sine(ta, tf, (i * 0.618) % 1)}}
            if dr:
                m["keys"]["rotation"] = [{"t": 0, "v": r2(dr * droop[pid]), "ease": "linear"}]
            parts[pid] = m
        for j, pid in enumerate(tails):
            m = {"keys": {}, "sine": {"rotation": sine(tla * (0.7 + 0.25 * j), max(1, tf // 2 or 1), 0.1 + 0.15 * j)}}
            if dr:
                m["keys"]["rotation"] = [{"t": 0, "v": r2(0.12 * dr * droop[pid]), "ease": "linear"}]
            parts[pid] = m
        for j, pid in enumerate(pods):
            parts[pid] = {"keys": {}, "sine": {"rotation": sine(tla * 0.5, 1, 0.3 + 0.2 * j)}}
        for pid in eyes:
            m = {"keys": {}, "sine": {}}
            if name == "Gate":
                m["keys"]["scaleX"] = [{"t": 0, "v": 1.08, "ease": "linear"}]
            parts[pid] = m
        # lids rest open (collapsed + invisible) and sweep shut on a blink; Blocked/Failed droop them
        for pid in lidup + lidlow:
            if lidclose > 0:
                droop_closed = lidclose if pid in lidup else lidclose * 0.45
                parts[pid] = {"keys": {"scaleY": keys_blink(blinks, 1.0, droop_closed),
                                       "alpha": [{"t": 0, "v": 1, "ease": "linear"}]}, "sine": {}}
            else:
                parts[pid] = {"keys": {"scaleY": blink_open(blinks), "alpha": blink_alpha(blinks)}, "sine": {}}
        for pid in lidup + lidlow:
            parts[pid]["keys"]["scaleY"] = [dict(k, v=r2(k["v"] / LID_REST)) for k in parts[pid]["keys"]["scaleY"]]
        states[name] = {"parts": parts}
    return states


def blink_open(times, dur=0.035):
    """Lid scaleY: collapsed (0.02) except for a blink at each time, where it sweeps shut (1.0)."""
    keys = [{"t": 0.0, "v": 0.02, "ease": "step"}]
    for t in times:
        keys += [{"t": r2(t), "v": 0.02, "ease": "easeOut"}, {"t": r2(t + dur), "v": 1.0, "ease": "easeIn"},
                 {"t": r2(t + 2 * dur), "v": 0.02, "ease": "step"}]
    return keys


def blink_alpha(times, dur=0.035):
    """Lid alpha: invisible except during a blink."""
    keys = [{"t": 0.0, "v": 0.0, "ease": "step"}]
    for t in times:
        keys += [{"t": r2(t), "v": 0.0, "ease": "step"}, {"t": r2(t + 0.002), "v": 1.0, "ease": "step"},
                 {"t": r2(t + 2 * dur), "v": 0.0, "ease": "step"}]
    return keys


# ----------------------------------------------------------------------------- driver

def write(slug, rig, atlas):
    os.makedirs(RIG_OUT, exist_ok=True)
    with open(os.path.join(RIG_OUT, slug + ".rig.json"), "w") as fh:
        json.dump(rig, fh, indent=1)
        fh.write("\n")
    atlas.save(os.path.join(RIG_OUT, rig["atlas"]["image"]), optimize=True)


def preview(slugs):
    out = os.path.join(tempfile.gettempdir(), "composed-rig-preview")
    os.makedirs(out, exist_ok=True)
    for slug in slugs:
        rig = json.load(open(os.path.join(RIG_OUT, slug + ".rig.json")))
        atlas = Image.open(os.path.join(RIG_OUT, rig["atlas"]["image"])).convert("RGBA")
        frames = [br.render(dict(rig, states={}), atlas, "Pending", 0.0)]
        for st, ph in (("Active", 0.2), ("Active", 0.45), ("Blocked", 0.3), ("Failed", 0.37), ("Complete", 0.12)):
            frames.append(br.render(rig, atlas, st, ph))
        H = max(f.height for f in frames) + 10
        sheet = Image.new("RGBA", (sum(f.width + 10 for f in frames) + 10, H + 10), (90, 94, 108, 255))
        x = 10
        for f in frames:
            sheet.alpha_composite(f, (x, 10))
            x += f.width + 10
        sheet.convert("RGB").save(os.path.join(out, slug + ".png"))
    print("previews in", out)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", help="only this slug")
    ap.add_argument("--preview", action="store_true")
    args = ap.parse_args()
    slugs = [s for s in COMPOSED if not args.only or s == args.only]
    for slug in slugs:
        directory, pattern = COMPOSED[slug]
        if slug in LAYERED:
            rig, atlas = build_layered(slug, LAYERED[slug])
            kind = "layered"
        else:
            rig, atlas = build_single(slug, directory, pattern)
            kind = "single-part"
        write(slug, rig, atlas)
        print(f"{slug:26s} {kind:12s} canvas {rig['canvas']['width']}x{rig['canvas']['height']} atlas {rig['atlas']['width']}x{rig['atlas']['height']}")
    if args.preview:
        preview(slugs)


if __name__ == "__main__":
    main()
