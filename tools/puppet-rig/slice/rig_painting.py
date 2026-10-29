#!/usr/bin/env python3
"""Slice and rig a creature drawn as one painting, rather than as an exploded parts sheet.

The neuron (docs/swarm-terrarium/characters/000_neuron/neuron.png) is the body of everything done
programmatically rather than by an agent (docs/Node-Creatures.md, Programmatic work). It is one
painting on a white ground: a faceted soma, dendrites branching out of it, and a myelinated axon
ending in a terminal arbor. This script

1. keys the white ground out ("colour to alpha": each pixel's coverage is how far it is from white,
   and its colour is un-mixed from the white beneath it),
2. finds the soma (the largest inscribed circle of the silhouette),
3. cuts every limb at the soma: whatever lies beyond 1.2 soma radii splits into connected limbs, and
   each limb is grown back into the soma (by nearest-limb assignment) so it tucks under the body
   without a seam; the longest limb is the axon (the tail), the rest are dendrites (tendrils),
4. rigs them in place: an invisible `body` bone at the soma centre, the soma above, each limb
   pivoting at its base on the soma, with the house motion per workflow state (build_rigs.py),
5. writes `<slug>.rig.json` and its atlas into composeResources/files/rigs, and a compare render.

Usage: python3 tools/puppet-rig/slice/rig_painting.py [--source PATH] [--slug neuron]
"""
from __future__ import annotations

import argparse
import json
import math
import os
import sys

import numpy as np
from PIL import Image
from scipy import ndimage as ndi

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import build_rigs as br  # noqa: E402

REPO = br.REPO
DEFAULT_SOURCE = os.path.join(br.CHAR_ROOT, "000_neuron", "neuron.png")
MAX_SIDE = 480  # artboard's longer side, px
CUT = 1.2  # limbs are separated beyond this many soma radii
TUCK = 0.55  # and grown back into the soma down to this many radii
MIN_LIMB = 400  # source px; smaller fragments are dropped


def colour_to_alpha(path: str) -> np.ndarray:
    """RGBA float array (0..1) with the white ground keyed out.

    Coverage is how far a pixel is from white. The painting is pale, so a pure colour-to-alpha
    would make its light facets translucent (dark on the tissue): anything a quarter covered or
    more is opaque and keeps its painted colour; only the antialiased rim is un-mixed from white.
    """
    rgb = np.asarray(Image.open(path).convert("RGB")).astype(np.float64) / 255.0
    coverage = (1.0 - rgb).max(axis=2)
    # The ground is not pure white (253-254): treat the faintest coverage as ground.
    coverage = np.clip((coverage - 0.035) / 0.965, 0.0, 1.0)
    alpha = np.clip(coverage / 0.25, 0.0, 1.0)
    with np.errstate(divide="ignore", invalid="ignore"):
        unmixed = np.where(coverage[..., None] > 0, (rgb - (1.0 - coverage[..., None])) / np.maximum(coverage[..., None], 1e-6), 0.0)
    # Specular highlights are near white too: whatever the silhouette encloses is body, not ground.
    inside = ndi.binary_fill_holes(ndi.binary_closing(alpha >= 1.0, iterations=2))
    alpha = np.where(inside, 1.0, alpha)
    colour = np.where(alpha[..., None] >= 1.0, rgb, np.clip(unmixed, 0.0, 1.0))
    return np.dstack([colour, alpha])


def to_image(rgba: np.ndarray) -> Image.Image:
    return Image.fromarray((np.clip(rgba, 0, 1) * 255).round().astype(np.uint8), "RGBA")


def slice_painting(rgba: np.ndarray):
    """(soma centre, soma radius, soma mask, [limb masks], axon index)."""
    solid = rgba[..., 3] > 0.25
    solid = ndi.binary_opening(ndi.binary_fill_holes(solid), iterations=1)
    dist = ndi.distance_transform_edt(solid)
    cy, cx = np.unravel_index(int(np.argmax(dist)), dist.shape)
    r = float(dist[cy, cx])
    yy, xx = np.mgrid[: solid.shape[0], : solid.shape[1]]
    d = np.hypot(xx - cx, yy - cy)
    coverage = rgba[..., 3] > 0.02

    outside = coverage & (d > CUT * r)
    lab, n = ndi.label(outside)
    sizes = ndi.sum(np.ones_like(lab), lab, index=range(1, n + 1))
    keep = [i + 1 for i, s in enumerate(sizes) if s >= MIN_LIMB]
    # Grow each limb back into the annulus between TUCK and CUT radii, by nearest limb pixel.
    seeds = np.where(np.isin(lab, keep), lab, 0)
    _, (iy, ix) = ndi.distance_transform_edt(seeds == 0, return_indices=True)
    nearest = seeds[iy, ix]
    tuck = coverage & (d > TUCK * r) & (d <= CUT * r)
    grown = np.where(tuck, nearest, seeds)

    limbs = [grown == k for k in keep]
    reach = [float(d[m].max()) for m in limbs]
    axon = int(np.argmax(reach))
    soma = coverage & (d <= r * 1.04)
    return (cx, cy), r, soma, limbs, axon, d


def crop(rgba: np.ndarray, mask: np.ndarray, pad: int = 2):
    """The masked pixels' RGBA, cropped to their box (+pad): (image, x0, y0)."""
    ys, xs = np.nonzero(mask)
    x0, x1 = max(0, xs.min() - pad), min(mask.shape[1], xs.max() + pad + 1)
    y0, y1 = max(0, ys.min() - pad), min(mask.shape[0], ys.max() + pad + 1)
    part = rgba[y0:y1, x0:x1].copy()
    part[..., 3] *= mask[y0:y1, x0:x1]
    return part, x0, y0


def motion(tendrils: list[dict], tail: dict | None, soma_h: float):
    """The house motion (build_rigs.motion) for a creature with no face: bob, sway, droop, sag."""
    u = soma_h / 128.0
    r2 = br.r2

    def droop_sign(p):
        return 1.0 if p["tip_x"] >= 0 else -1.0

    def state(body_amp, body_f, rot_amp, tend_amp, tend_f, tail_amp, droop=0.0, tremor=0.0, sag=0.0, alpha=None):
        parts = {}
        b = {"sine": {"y": {"amplitude": r2(body_amp * u), "frequency": body_f, "phase": 0}}}
        if rot_amp:
            b["sine"]["rotation"] = {"amplitude": r2(rot_amp), "frequency": 1, "phase": 0.25}
        if tremor:
            b["sine"]["x"] = {"amplitude": r2(tremor * u), "frequency": 13, "phase": 0}
        if sag:
            b["keys"] = {"y": [{"t": 0, "v": r2(sag * u)}], "rotation": [{"t": 0, "v": -5.0}]}
        if alpha is not None:
            b.setdefault("keys", {})["alpha"] = [{"t": 0, "v": alpha}]
        parts["body"] = b
        for i, p in enumerate(tendrils):
            m = {"sine": {"rotation": {"amplitude": r2(tend_amp), "frequency": tend_f, "phase": r2((i * 0.618) % 1)}}}
            if droop:
                m["keys"] = {"rotation": [{"t": 0, "v": r2(droop * droop_sign(p))}]}
            parts[p["id"]] = m
        if tail is not None:
            m = {"sine": {"rotation": {"amplitude": r2(tail_amp), "frequency": max(1, tend_f // 2 or 1), "phase": 0.1}}}
            if droop:
                m["keys"] = {"rotation": [{"t": 0, "v": r2(0.4 * droop * droop_sign(tail))}]}
            parts[tail["id"]] = m
        return {"parts": parts}

    # The axon is long, so its sway is a fraction of a dendrite's: the tip moves, the arbor does not whip.
    st = {
        "Pending": state(1.2, 1, 0, 2.0, 1, 0.8),
        "Ready": state(1.8, 1, 0.8, 3.0, 1, 1.2),
        "Active": state(3.5, 2, 2.0, 7.0, 2, 2.5),
        "Gate": state(0.5, 1, 0, 1.0, 1, 0.4),
        "Blocked": state(1.0, 1, 0, 2.0, 1, 0.8, droop=12.0, sag=4.0),
        "Failed": state(0.6, 1, 0, 1.5, 1, 0.6, droop=20.0, tremor=1.0, sag=9.0, alpha=0.92),
    }
    comp = state(0.0, 1, 0, 3.0, 1, 1.0)
    comp["parts"]["body"] = {"keys": {
        "y": [{"t": 0, "v": 0, "ease": "easeOut"}, {"t": 0.12, "v": r2(-10 * u), "ease": "easeIn"}, {"t": 0.26, "v": 0, "ease": "easeOut"},
              {"t": 0.34, "v": r2(-3.5 * u), "ease": "easeIn"}, {"t": 0.42, "v": 0, "ease": "linear"}],
        "scaleY": [{"t": 0, "v": 1, "ease": "linear"}, {"t": 0.26, "v": 1, "ease": "easeOut"}, {"t": 0.3, "v": 0.93, "ease": "easeOut"},
                   {"t": 0.38, "v": 1, "ease": "linear"}],
        "scaleX": [{"t": 0, "v": 1, "ease": "linear"}, {"t": 0.26, "v": 1, "ease": "easeOut"}, {"t": 0.3, "v": 1.05, "ease": "easeOut"},
                   {"t": 0.38, "v": 1, "ease": "linear"}],
    }}
    st["Complete"] = comp
    return st


def build(source: str, slug: str):
    rgba = colour_to_alpha(source)
    (cx, cy), r, soma_mask, limbs, axon, d = slice_painting(rgba)
    # The myelin capsules' highlights run off their edge, so no hole fill reaches them: close the
    # axon alone (it has no fingers to web) and make what that encloses opaque, in its painted colour.
    painted = np.asarray(Image.open(source).convert("RGB")).astype(np.float64) / 255.0
    closed = ndi.binary_fill_holes(ndi.binary_closing(limbs[axon], structure=np.ones((3, 3)), iterations=6)) & (d > TUCK * r)
    added = closed & ~(rgba[..., 3] >= 1.0)
    rgba[added, :3] = painted[added]
    rgba[added, 3] = 1.0
    limbs[axon] = limbs[axon] | closed
    coverage = rgba[..., 3] > 0.02
    ys, xs = np.nonzero(coverage)
    bx0, bx1, by0, by1 = xs.min(), xs.max(), ys.min(), ys.max()
    k = MAX_SIDE / max(bx1 - bx0, by1 - by0)
    margin = 4

    def art(x, y):
        return ((x - bx0) * k + margin, (y - by0) * k + margin)

    pieces = []  # (id, kind, z, image, pivot_src(x, y), crop origin)
    soma_img, sx0, sy0 = crop(rgba, soma_mask)
    pieces.append(("soma", "body", 2, soma_img, (cx, cy), (sx0, sy0)))
    # Number dendrites clockwise from 12 o'clock, by the angle of their base.
    bases = {}
    for i, m in enumerate(limbs):
        my, mx = np.nonzero(m)
        j = int(np.argmin(np.hypot(mx - cx, my - cy)))
        bases[i] = (float(mx[j]), float(my[j]))
    clock = sorted((i for i in range(len(limbs)) if i != axon),
                   key=lambda i: (math.degrees(math.atan2(bases[i][0] - cx, -(bases[i][1] - cy))) + 360) % 360)
    for n, i in enumerate(clock):
        img, x0, y0 = crop(rgba, limbs[i])
        pieces.append((f"tendril.{n}", "tendril", 1, img, bases[i], (x0, y0)))
    img, x0, y0 = crop(rgba, limbs[axon])
    pieces.append(("tail", "tail", 0, img, bases[axon], (x0, y0)))

    # Rasterize at the artboard scale and pack the atlas.
    rasters, items = {}, []
    for pid, _, _, img, _, _ in pieces:
        w = max(2, int(round(img.shape[1] * k)))
        h = max(2, int(round(img.shape[0] * k)))
        rasters[pid] = to_image(img).resize((w, h), Image.LANCZOS)
        items.append((pid, w, h))
    rects, (AW, AH) = br.pack(items)
    atlas = Image.new("RGBA", (AW, AH), (0, 0, 0, 0))
    for pid, (x, y, w, h) in rects.items():
        atlas.paste(rasters[pid], (x, y))

    body_x, body_y = art(cx, cy)
    parts = [{"id": "body", "parent": None, "z": 0, "kind": "body", "pivot": {"x": 0.5, "y": 0.5},
              "rest": {"x": br.r2(body_x), "y": br.r2(body_y), "rotation": 0, "scaleX": 1, "scaleY": 1, "alpha": 1}}]
    meta = {}
    for pid, kind, z, img, (px, py), (x0, y0) in sorted(pieces, key=lambda p: (p[2], p[0])):
        x, y, w, h = rects[pid]
        pv = ((px - x0) / img.shape[1], (py - y0) / img.shape[0])
        ax, ay = art(px, py)
        parts.append({
            "id": pid, "parent": "body", "z": z, "kind": kind,
            "rect": {"x": x, "y": y, "w": w, "h": h}, "size": {"w": w, "h": h},
            "pivot": {"x": br.r2(pv[0]), "y": br.r2(pv[1])},
            "rest": {"x": br.r2(ax - body_x), "y": br.r2(ay - body_y), "rotation": 0, "scaleX": 1, "scaleY": 1, "alpha": 1},
        })
        alpha = img[..., 3] > 0.2
        yy, xx = np.nonzero(alpha)
        far = int(np.argmax(np.hypot(xx + x0 - cx, yy + y0 - cy)))
        meta[pid] = {"id": pid, "tip_x": float(xx[far] + x0 - cx), "tip": (float(xx[far] + x0), float(yy[far] + y0)),
                     "box": (x0, y0, img.shape[1], img.shape[0])}

    def norm(pid, point):
        x0, y0, w, h = meta[pid]["box"]
        return br.r2((point[0] - x0) / w), br.r2((point[1] - y0) / h)

    soma_h = soma_img.shape[0] * k
    atts = [
        {"id": "arm.socket.left", "part": "soma", "x": 0.06, "y": 0.56, "kind": "arm-socket"},
        {"id": "arm.socket.right", "part": "soma", "x": 0.94, "y": 0.56, "kind": "arm-socket"},
    ]
    tx, ty = norm("tail", meta["tail"]["tip"])
    atts.append({"id": "tail.tip", "part": "tail", "x": tx, "y": ty, "kind": "tail-tip"})
    for p in parts:
        if p.get("kind") == "tendril":
            ex, ey = norm(p["id"], meta[p["id"]]["tip"])
            atts.append({"id": p["id"] + ".tip", "part": p["id"], "x": ex, "y": ey, "kind": "tendril-tip"})

    W = (bx1 - bx0) * k + 2 * margin
    H = (by1 - by0) * k + 2 * margin
    tendrils = [meta[p["id"]] for p in parts if p.get("kind") == "tendril"]
    rig = {
        "format": "aive-puppet-rig", "version": 1, "role": slug,
        "canvas": {"width": br.r2(W), "height": br.r2(H)},
        "atlas": {"image": slug.replace("-", "_") + "_rig_atlas.png", "width": AW, "height": AH},
        "parts": parts, "attachments": atts,
        "states": motion(tendrils, meta["tail"], soma_h),
        "fallbackState": "Pending",
    }
    return rig, atlas, dict(soma_radius=r, limbs=len(limbs), axon_reach=float(d[limbs[axon]].max()), scale=k)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--source", default=DEFAULT_SOURCE)
    ap.add_argument("--slug", default="neuron")
    args = ap.parse_args()
    rig, atlas, info = build(args.source, args.slug)
    os.makedirs(br.RIG_OUT, exist_ok=True)
    with open(os.path.join(br.RIG_OUT, f"{args.slug}.rig.json"), "w") as f:
        json.dump(rig, f, indent=1)
        f.write("\n")
    atlas.save(os.path.join(br.RIG_OUT, rig["atlas"]["image"]), optimize=True)
    out_dir = os.path.join(os.path.dirname(args.source), "rig")
    os.makedirs(out_dir, exist_ok=True)
    br.compare(rig, atlas, args.source, os.path.join(out_dir, f"{args.slug}.compare.png"), args.slug)
    print(json.dumps(info))


if __name__ == "__main__":
    main()
