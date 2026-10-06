#!/usr/bin/env python3
"""Stage 1: find node-creature *parts sheets* and slice them into classified body parts.

A parts sheet is an RGBA PNG on a transparent background with the creature's pieces laid out
separately (exploded view): one round soma, tendrils, a tail, two eyes, eyelid arcs, sometimes a
beak or prop.  Composed full characters (node_XX.png, the 1254x1254 Sep 25 sheets, ...) are not
parts sheets; they are used as assembly references by `build_rigs.py`.

For every parts sheet under docs/swarm-terrarium/characters/<dir>/ this writes

    <dir>/rig/parts/<sheet-slug>/<part>.png   tightly cropped original pixels (2 px pad)
    <dir>/rig/parts/<sheet-slug>/parts.json   ids, classes, source bboxes, confidence, base point
    <dir>/rig/parts/<sheet-slug>/preview.png  labelled contact sheet on a dark background

Classification is heuristic; `overrides.json` (next to this file) corrects individual sheets.
Usage:  python3 tools/puppet-rig/slice/slice_parts.py [--only <dir-substring>] [--list]
"""
from __future__ import annotations

import argparse
import json
import math
import os
import re
import sys
from dataclasses import dataclass, field

import numpy as np
from PIL import Image, ImageDraw
from scipy import ndimage as ndi

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", "..", ".."))
CHAR_ROOT = os.path.join(REPO, "docs", "swarm-terrarium", "characters")
ALPHA_T = 16


# ----------------------------------------------------------------------------- discovery

def slugify(s: str) -> str:
    s = re.sub(r"[^a-zA-Z0-9]+", "-", s).strip("-").lower()
    return s


def role_slug(dir_name: str) -> str:
    """'016_data_miner_sheet_1_variant' -> 'data-miner-sheet-1-variant'; '000_generic' -> 'generic'."""
    name = re.sub(r"^\d+_", "", dir_name)
    return slugify(name)


def sheet_slug(file_name: str) -> str:
    base = os.path.splitext(file_name)[0]
    m = re.match(r"ChatGPT Image (\w+) (\d+), (\d+), (\d+)_(\d+)_(\d+) (AM|PM)(?:-(\d+))?", base)
    if m:
        mon, day, _y, hh, mm, ss, ap, idx = m.groups()
        s = f"{mon.lower()}{int(day):02d}-{hh}{mm}{ss}{ap.lower()}"
        return s + (f"-{idx}" if idx else "")
    return slugify(base)


def is_composed_name(f: str) -> bool:
    low = f.lower()
    return (low.startswith("node_") or "node_" in low or low.startswith("approved_character")
            or low.startswith("character") or low.startswith("source_character")
            or low.startswith("matplotlib") or "sep 25" in low)


def alpha_stats(path: str):
    im = Image.open(path)
    if im.mode != "RGBA":
        return None
    a = np.asarray(im)[..., 3]
    if a.min() > ALPHA_T:
        return None
    sc = max(1, max(im.size) // 600)
    lab, n = ndi.label(a[::sc, ::sc] > ALPHA_T)
    if n == 0:
        return None
    sz = np.bincount(lab.ravel())[1:]
    big = int((sz > sz.max() * 0.003).sum())
    return dict(size=im.size, comps=n, big=big, maxfrac=float(sz.max() / sz.sum()))


def discover():
    """Yield (dir, file, stats, verdict) for every PNG directly in a character dir."""
    for d in sorted(os.listdir(CHAR_ROOT)):
        p = os.path.join(CHAR_ROOT, d)
        if not os.path.isdir(p):
            continue
        for f in sorted(os.listdir(p)):
            if not f.lower().endswith(".png"):
                continue
            st = alpha_stats(os.path.join(p, f))
            if st is None:
                verdict = "skip:no-alpha"
            elif is_composed_name(f):
                verdict = "reference"
            elif st["big"] >= 5 and st["maxfrac"] < 0.75:
                verdict = "candidate"
            else:
                verdict = "skip:not-parts-sheet"
            yield d, f, st, verdict


# ----------------------------------------------------------------------------- slicing

@dataclass
class Part:
    idx: int
    mask: np.ndarray  # full-sheet bool mask
    bbox: tuple  # x0, y0, x1, y1 (exclusive)
    area: int
    cls: str = "tendril"
    pid: str = ""
    conf: float = 0.5
    feats: dict = field(default_factory=dict)
    base: tuple | None = None  # (x, y) sheet px, attachment end
    tip: tuple | None = None


def load_overrides():
    p = os.path.join(HERE, "overrides.json")
    if os.path.exists(p):
        with open(p) as fh:
            return json.load(fh)
    return {}


def components(alpha: np.ndarray, merge_px: int, scale: float):
    """Label alpha>T; merge satellites within merge_px of a larger part; drop specks."""
    m = alpha > ALPHA_T
    lab, n = ndi.label(m, structure=np.ones((3, 3)))
    sizes = np.bincount(lab.ravel())
    total = sizes[1:].sum()
    speck = max(30, total * 0.00012)
    big_min = total * 0.0025  # anything smaller is a satellite candidate
    objs = ndi.find_objects(lab)
    keep = [i for i in range(1, n + 1) if sizes[i] >= big_min]
    sats = [i for i in range(1, n + 1) if speck <= sizes[i] < big_min]
    owner = {i: i for i in keep}
    if keep:
        # distance from every pixel to the nearest "big" component and which one it is
        bigmask = np.isin(lab, keep)
        dist, (iy, ix) = ndi.distance_transform_edt(~bigmask, return_indices=True)
        for s in sats:
            sl = objs[s - 1]
            sub = lab[sl] == s
            dsub = dist[sl][sub]
            k = int(np.argmin(dsub))
            yy, xx = np.nonzero(sub)
            ny, nx = iy[sl][yy[k], xx[k]], ix[sl][yy[k], xx[k]]
            if dsub[k] <= merge_px * scale:
                owner[s] = int(lab[ny, nx])
            elif sizes[s] >= speck * 4:
                owner[s] = s  # standalone small part (droplet, beak tip)
    groups: dict[int, list[int]] = {}
    for c, o in owner.items():
        groups.setdefault(o, []).append(c)
    parts = []
    for o, members in groups.items():
        mk = np.isin(lab, members)
        ys, xs = np.nonzero(mk)
        parts.append(Part(0, mk, (int(xs.min()), int(ys.min()), int(xs.max()) + 1, int(ys.max()) + 1), int(mk.sum())))
    parts.sort(key=lambda p: (p.bbox[1], p.bbox[0]))
    for i, p in enumerate(parts):
        p.idx = i
    return parts


def features(p: Part, rgb: np.ndarray):
    x0, y0, x1, y1 = p.bbox
    sub = p.mask[y0:y1, x0:x1]
    w, h = x1 - x0, y1 - y0
    dt = ndi.distance_transform_edt(np.pad(sub, 1))[1:-1, 1:-1]
    r_in = float(dt.max())
    cy, cx = np.unravel_index(int(dt.argmax()), dt.shape)
    pix = rgb[y0:y1, x0:x1][sub].astype(float)
    lum = pix @ np.array([0.299, 0.587, 0.114])
    ys, xs = np.nonzero(sub)
    f = dict(
        w=w, h=h, fill=p.area / float(w * h), aspect=w / float(h), r_in=r_in,
        # roundness: inscribed circle area vs part area (1 for a disc)
        roundness=math.pi * r_in * r_in / p.area,
        incx=x0 + cx, incy=y0 + cy,
        cx=x0 + xs.mean(), cy=y0 + ys.mean(),
        dark=float((lum < 70).mean()), bright=float((lum > 215).mean()),
        lum=float(lum.mean()), hue=pix.mean(0).tolist(),
    )
    # thin-arc test: thickness small relative to width
    f["thin"] = r_in * 2 / max(w, h)
    dk = (lum < 70)
    if dk.sum() > 4:
        f["pupil_off"] = float(np.hypot(xs[dk].mean() - cx, ys[dk].mean() - cy) / max(w, h))
    else:
        f["pupil_off"] = 1.0
    # arc direction: mean y of the middle third columns vs the outer thirds (normalised by h)
    cols = np.arange(w)
    colmass = sub.sum(0)
    ymean = np.array([np.mean(np.nonzero(sub[:, c])[0]) if colmass[c] else np.nan for c in cols])
    mid = np.nanmean(ymean[w // 3: 2 * w // 3]) if w >= 3 else np.nan
    ends = np.nanmean(np.r_[ymean[: w // 4], ymean[3 * w // 4:]]) if w >= 4 else np.nan
    f["arc"] = float((ends - mid) / h) if h else 0.0  # >0: middle higher -> opens downward (upper lid)
    p.feats = f


def is_eye_like(p: Part):
    """Disc that fills its box, with a centred dark pupil."""
    f = p.feats
    disc = f["r_in"] / (0.5 * min(f["w"], f["h"]))
    return 0.55 < f["aspect"] < 1.6 and disc > 0.62 and f["dark"] > 0.1 and f["pupil_off"] < 0.22


def eye_pair_score(a: Part, b: Part):
    fa, fb = a.feats, b.feats
    if not (is_eye_like(a) and is_eye_like(b)):
        return -1
    ar = max(a.area, b.area) / min(a.area, b.area)
    if ar > 2.4:
        return -1
    s = 0.0
    # the right eye is often foreshortened (narrower), so heights matter more than widths
    s += 1.5 * (1.0 - min(1.0, abs(fa["h"] - fb["h"]) / max(fa["h"], fb["h"]) * 3))
    s += 0.5 * (1.0 - min(1.0, abs(fa["w"] - fb["w"]) / max(fa["w"], fb["w"]) * 2))
    s += min(1.0, min(fa["dark"], fb["dark"]) * 4)
    s += 0.5 * min(1.0, min(fa["bright"], fb["bright"]) * 20)
    s += 1.0 - min(1.0, abs(fa["cy"] - fb["cy"]) / (2 * max(fa["h"], fb["h"])))  # same row
    s += 1.0 - min(1.0, abs(fa["cx"] - fb["cx"]) / (6 * max(fa["w"], fb["w"])))  # near each other
    return s


def single_eye_score(a: Part):
    f = a.feats
    return (2.0 if is_eye_like(a) else 0.0) + min(1.0, f["dark"] * 4) + 0.5 * min(1.0, f["bright"] * 20)


def classify(parts: list[Part], ov: dict):
    total = sum(p.area for p in parts)
    # soma: biggest inscribed circle, weighted by area and roundness
    soma = max(parts, key=lambda p: p.feats["r_in"] * (0.6 + 0.4 * p.feats["roundness"]) * (p.area / total) ** 0.25)
    soma.cls, soma.conf = "soma", 0.9
    rest = [p for p in parts if p is not soma]
    R = soma.feats["r_in"]

    # eyes: best near-identical disc pair (must be small relative to soma)
    cands = [p for p in rest if p.feats["r_in"] < R * 0.8 and is_eye_like(p)]
    best, bs = None, 2.5
    for i in range(len(cands)):
        for j in range(i + 1, len(cands)):
            s = eye_pair_score(cands[i], cands[j])
            if s > bs:
                best, bs = (cands[i], cands[j]), s
    eyes = []
    if best:
        l, r = sorted(best, key=lambda p: p.feats["cx"])
        l.cls, r.cls = "eye.left", "eye.right"
        l.conf = r.conf = round(min(1.0, bs / 6), 2)
        eyes = [l, r]
    else:
        sc = [p for p in cands if single_eye_score(p) > 2.5]
        if sc:
            e = max(sc, key=single_eye_score)
            e.cls, e.conf = "eye.right", 0.5
            eyes = [e]
    rest = [p for p in rest if p not in eyes]

    # lids: thin arcs about eye-width
    ew = np.mean([e.feats["w"] for e in eyes]) if eyes else R * 0.6
    lids = []
    for p in rest:
        f = p.feats
        if f["aspect"] > 1.6 and f["thin"] < 0.55 and 0.45 * ew < f["w"] < 1.9 * ew and f["h"] < 0.8 * ew and f["r_in"] < 0.3 * ew:
            lids.append(p)
    up = [p for p in lids if p.feats["arc"] > 0.02]
    lo = [p for p in lids if p.feats["arc"] < -0.02]
    for grp, name in ((up, "upper"), (lo, "lower")):
        grp.sort(key=lambda p: p.feats["cx"])
        grp[:] = grp[:2]
        if len(grp) == 2:
            grp[0].cls, grp[1].cls = f"lid.{name}.left", f"lid.{name}.right"
        elif len(grp) == 1:
            # side by nearest eye
            side = "right"
            if len(eyes) == 2:
                side = "left" if abs(grp[0].feats["cx"] - eyes[0].feats["cx"]) < abs(grp[0].feats["cx"] - eyes[1].feats["cx"]) else "right"
            grp[0].cls = f"lid.{name}.{side}"
        for p in grp:
            p.conf = round(min(1.0, abs(p.feats["arc"]) * 4 + 0.4), 2)
    rest = [p for p in rest if not p.cls.startswith("lid.")]

    # tail: elongated piece with the largest terminal bulb, preferring low ones
    sy0, sy1 = soma.bbox[1], soma.bbox[3]
    elong = [p for p in rest if max(p.feats["w"], p.feats["h"]) > 1.2 * R and p.feats["r_in"] > 0.08 * R]
    if elong:
        def tail_score(p):
            low = (p.feats["cy"] - soma.feats["incy"]) / R
            return p.feats["r_in"] / R + 0.15 * max(-1, min(2, low)) + 0.1 * max(p.feats["w"], p.feats["h"]) / R
        t = max(elong, key=tail_score)
        t.cls, t.conf = "tail", 0.6
        rest = [p for p in rest if p is not t]
    # beak / small compact props
    small = [p for p in rest if p.area < 0.5 * (eyes[0].area if eyes else R * R) and p.feats["fill"] > 0.35]
    for k, p in enumerate(sorted(small, key=lambda p: p.area, reverse=True)):
        p.cls, p.conf = ("beak" if k == 0 else "prop"), 0.35
    # apply overrides (by index -> class) before numbering
    for k, v in ov.get("classes", {}).items():
        for p in parts:
            if p.idx == int(k):
                p.cls, p.conf = v, 1.0
    if ov.get("drop"):
        parts[:] = [p for p in parts if p.idx not in ov["drop"]]
    return soma


def number_parts(parts: list[Part], soma: Part):
    cx, cy = soma.feats["incx"], soma.feats["incy"]
    counts: dict[str, int] = {}
    tend = [p for p in parts if p.cls == "tendril"]
    # number clockwise from 12 o'clock
    tend.sort(key=lambda p: (math.degrees(math.atan2(p.feats["cx"] - cx, -(p.feats["cy"] - cy))) + 360) % 360)
    for i, p in enumerate(tend):
        p.pid = f"tendril.{i}"
    props = sorted([p for p in parts if p.cls in ("prop", "beak.extra")], key=lambda p: p.feats["cx"])
    for i, p in enumerate(props):
        p.pid = f"prop.{i}"
    for p in parts:
        if not p.pid:
            n = counts.get(p.cls, 0)
            counts[p.cls] = n + 1
            p.pid = p.cls if n == 0 else f"{p.cls}.{n}"


def find_base(p: Part, soma: Part):
    """Attachment end of a stalk: the thin end of the part nearest the soma centre.

    Sheets are exploded views laid out around the soma, so a stalk's base points at the soma. We take
    the part's pixels whose local thickness is small (not a bulb) and pick the one nearest the soma's
    inscribed-circle centre; the tip is the farthest pixel.
    """
    x0, y0, x1, y1 = p.bbox
    sub = p.mask[y0:y1, x0:x1]
    dt = ndi.distance_transform_edt(np.pad(sub, 1))[1:-1, 1:-1]
    ys, xs = np.nonzero(sub)
    cx, cy = soma.feats["incx"] - x0, soma.feats["incy"] - y0
    d = np.hypot(xs - cx, ys - cy)
    thick = dt[ys, xs]
    # skip bulbs: pixels that belong to a fat region (dilated local maximum of dt)
    fat = ndi.maximum_filter(dt, size=max(3, int(p.feats["r_in"]))) [ys, xs] > 0.75 * p.feats["r_in"]
    ok = ~fat if (~fat).sum() > 20 else np.ones_like(fat)
    if p.cls == "tail":
        # the stalk end farthest from the egg (largest bulb)
        by_, bx_ = np.unravel_index(int(dt.argmax()), dt.shape)
        d = -np.hypot(xs - bx_, ys - by_)
    k = int(np.argmin(np.where(ok, d, np.inf)))
    # base = centroid of the thin pixels within a small radius of that nearest point
    near = ok & (np.hypot(xs - xs[k], ys - ys[k]) < max(6, 2.5 * np.median(thick[ok])))
    bx, by = xs[near].mean(), ys[near].mean()
    j = int(np.argmax(np.hypot(xs - bx, ys - by)))
    p.base = (float(bx + x0), float(by + y0))
    p.tip = (float(xs[j] + x0), float(ys[j] + y0))


PALETTE = {"soma": (255, 220, 80), "eye": (80, 220, 255), "lid": (120, 255, 140), "tail": (255, 120, 200),
           "tendril": (240, 240, 240), "beak": (255, 150, 60), "prop": (200, 160, 255)}


def preview(img: Image.Image, parts: list[Part], out: str, title: str):
    W, H = img.size
    sc = 900 / max(W, H)
    bg = Image.new("RGBA", (int(W * sc), int(H * sc) + 24), (24, 26, 34, 255))
    small = img.resize((int(W * sc), int(H * sc)), Image.LANCZOS)
    bg.alpha_composite(small, (0, 24))
    d = ImageDraw.Draw(bg)
    d.text((6, 5), title, fill=(255, 255, 255, 255))
    for p in parts:
        col = PALETTE.get(p.cls.split(".")[0], (255, 255, 255)) + (255,)
        x0, y0, x1, y1 = [v * sc for v in p.bbox]
        d.rectangle([x0, y0 + 24, x1, y1 + 24], outline=col)
        d.text((x0 + 2, y0 + 25), f"{p.idx}:{p.pid} {p.conf:.2f}", fill=col)
        if p.base:
            bx, by = p.base[0] * sc, p.base[1] * sc + 24
            d.ellipse([bx - 4, by - 4, bx + 4, by + 4], outline=(255, 0, 0, 255), width=2)
    bg.convert("RGB").quantize(256, method=Image.Quantize.MEDIANCUT).save(out, optimize=True)  # diagnostic only: palette PNG keeps it small


def process_sheet(d: str, f: str, ov_all: dict, out_root: str | None = None):
    key = f"{d}/{f}"
    ov = ov_all.get(key, {})
    path = os.path.join(CHAR_ROOT, d, f)
    img = Image.open(path).convert("RGBA")
    arr = np.asarray(img)
    scale = max(img.size) / 1448.0
    parts = components(arr[..., 3], ov.get("merge_px", 7), scale)
    for p in parts:
        features(p, arr[..., :3])
    soma = classify(parts, ov)
    number_parts(parts, soma)
    for p in parts:
        if p.cls in ("tendril", "tail"):
            find_base(p, soma)
    for pid, xy in ov.get("base", {}).items():
        for p in parts:
            if p.pid == pid:
                p.base = tuple(xy)
    slug = sheet_slug(f)
    out = out_root or os.path.join(CHAR_ROOT, d, "rig", "parts", slug)
    os.makedirs(out, exist_ok=True)
    for old in os.listdir(out):
        if old.endswith(".png"):
            os.remove(os.path.join(out, old))
    recs = []
    for p in parts:
        x0, y0, x1, y1 = p.bbox
        X0, Y0 = max(0, x0 - 2), max(0, y0 - 2)
        X1, Y1 = min(img.width, x1 + 2), min(img.height, y1 + 2)
        crop = arr[Y0:Y1, X0:X1].copy()
        crop[..., 3] = np.where(p.mask[Y0:Y1, X0:X1] | ndi.binary_dilation(p.mask[Y0:Y1, X0:X1], iterations=2), crop[..., 3], 0)
        Image.fromarray(crop).save(os.path.join(out, f"{p.pid}.png"), optimize=True)
        rec = dict(idx=p.idx, id=p.pid, cls=p.cls.split(".")[0], source_bbox=[int(X0), int(Y0), int(X1 - X0), int(Y1 - Y0)], size=[int(X1 - X0), int(Y1 - Y0)],
                   area=int(p.area), confidence=float(p.conf))
        if p.base:
            rec["base"] = [round(p.base[0], 1), round(p.base[1], 1)]
            rec["tip"] = [round(p.tip[0], 1), round(p.tip[1], 1)] if p.tip else None
        if p.cls == "soma":
            rec["inscribed"] = [round(p.feats["incx"], 1), round(p.feats["incy"], 1), round(p.feats["r_in"], 1)]
        recs.append(rec)
    meta = dict(sheet=f, dir=d, sheet_size=list(img.size), slug=slug, mtime=os.path.getmtime(path),
                parts=recs, overridden=bool(ov))
    with open(os.path.join(out, "parts.json"), "w") as fh:
        json.dump(meta, fh, indent=1, default=lambda o: o.item() if hasattr(o, "item") else str(o))
    preview(img, parts, os.path.join(out, "preview.png"), f"{d} / {f}")
    return meta


def parts_sheets():
    ov = load_overrides()
    skip = set(ov.get("_skip_sheets", []))
    res = []
    for d, f, st, verdict in discover():
        if verdict == "candidate" and f"{d}/{f}" not in skip:
            res.append((d, f))
    return res


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", default="")
    ap.add_argument("--list", action="store_true")
    a = ap.parse_args()
    ov = load_overrides()
    if a.list:
        for d, f, st, v in discover():
            print(f"{v:22s} {d} | {f} {st}")
        return
    for d, f in parts_sheets():
        if a.only and a.only not in f"{d}/{f}":
            continue
        m = process_sheet(d, f, ov)
        cls = [r["id"] for r in m["parts"]]
        print(f"{d}/{m['slug']}: {len(cls)} parts: {' '.join(cls)}")


if __name__ == "__main__":
    sys.exit(main())
