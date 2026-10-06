#!/usr/bin/env python3
"""Stage 2: assemble sliced parts (from slice_parts.py) into starter `aive-puppet-rig` v1 rigs.

For every sliced parts sheet this writes

    shared/src/commonMain/composeResources/files/rigs/<slug>.rig.json
    shared/src/commonMain/composeResources/files/rigs/<slug_underscored>_rig_atlas.png
    docs/swarm-terrarium/characters/<dir>/rig/<slug>.compare.png   reference | rest | Active phases | Blocked | Failed

Placement
---------
Parts sheets are exploded views: every stalk lies around the soma with its base pointing at it.
* soma: origin; its inscribed circle (centre C, radius R) is the body frame.
* tendrils: the base (attachment end found by slice_parts.py) is pulled along the ray C->base onto the
  soma rim (with a small overlap so no seam shows) and the stalk is turned to point radially outward.
* tail: hung from the bottom of the soma, pointing down.
* eyes on the face (front-right; creatures face right, beak on the left), lids parented to the eyes.
If the role has an assembled reference (node_XX.png), its soma (largest inscribed circle), eyes
(multi-scale template matching) and limbs (alpha outside the soma disc, split into connected limbs)
replace the heuristic scale, eye positions and limb angles/lengths.

Motion follows docs/swarm-terrarium/README.md: queued/ready restrained, active moves, gate alert and
still, blocked droops, failed sags and trembles, complete settles with a bounce.  All sine
frequencies are integers so the loops are seamless.

Usage: python3 tools/puppet-rig/slice/build_rigs.py [--only <substring>] [--no-compare]
"""
from __future__ import annotations

import argparse
import glob
import json
import math
import os
import re
import subprocess
import sys

import numpy as np
from PIL import Image, ImageDraw
from scipy import ndimage as ndi

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import slice_parts as sp  # noqa: E402

try:
    import cv2  # opencv-python-headless; optional, only used for reference matching
except ImportError:  # pragma: no cover
    cv2 = None

REPO = sp.REPO
CHAR_ROOT = sp.CHAR_ROOT
RIG_OUT = os.path.join(REPO, "shared", "src", "commonMain", "composeResources", "files", "rigs")
STATES = ["Pending", "Ready", "Active", "Gate", "Blocked", "Complete", "Failed"]
TARGET_R = 64.0  # soma inscribed radius in artboard px when there is no reference
MAX_SIDE = 512
Z = {"tail": 0, "tailseg": 0, "tendril": 1, "soma": 2, "beak": 3, "prop": 3, "eye": 4, "lid": 5, "pupil": 5}


# ----------------------------------------------------------------------------- loading

def load_sheet(parts_dir: str):
    with open(os.path.join(parts_dir, "parts.json")) as fh:
        meta = json.load(fh)
    imgs = {}
    for r in meta["parts"]:
        imgs[r["id"]] = Image.open(os.path.join(parts_dir, r["id"] + ".png")).convert("RGBA")
    return meta, imgs


def sheet_time(d: str, f: str) -> float:
    """Timestamp of a sheet: from the 'ChatGPT Image <date>, <time>' name, else its git commit time."""
    m = re.search(r"(\w{3}) (\d+), (\d{4}), (\d+)_(\d+)_(\d+) (AM|PM)", f)
    if m:
        import datetime as dt
        mon, day, yr, hh, mm, ss, ap = m.groups()
        h = int(hh) % 12 + (12 if ap == "PM" else 0)
        t = dt.datetime.strptime(f"{mon} {day} {yr} {h}:{mm}:{ss}", "%b %d %Y %H:%M:%S")
        return t.timestamp()
    try:
        out = subprocess.run(["git", "log", "-1", "--format=%ct", "--", os.path.join(CHAR_ROOT, d, f)],
                             cwd=REPO, capture_output=True, text=True).stdout.strip()
        return float(out or 0)
    except Exception:
        return 0.0


def references(d: str):
    """Assembled-creature images of a role, best first: node renders, then other composed images."""
    nodes, other = [], []
    for f in sorted(os.listdir(os.path.join(CHAR_ROOT, d))):
        low = f.lower()
        if not low.endswith(".png"):
            continue
        if low.startswith("node_") or "variant_node" in low:
            nodes.append(os.path.join(CHAR_ROOT, d, f))
        elif "sep 25" in low or low.startswith("approved_character") or low.startswith("matplotlib"):
            other.append(os.path.join(CHAR_ROOT, d, f))
    return nodes or other


def keyed_rgba(path: str) -> Image.Image:
    """RGBA image; an opaque image gets its border-connected flat background keyed out."""
    img = Image.open(path).convert("RGBA")
    arr = np.asarray(img).copy()
    if arr[..., 3].min() > sp.ALPHA_T:
        rgb = arr[..., :3].astype(int)
        corner = np.median(np.vstack([rgb[0, 0], rgb[0, -1], rgb[-1, 0], rgb[-1, -1]]), axis=0)
        near = np.abs(rgb - corner).sum(-1) < 40
        lab, n = ndi.label(near)
        border = set(np.unique(np.r_[lab[0], lab[-1], lab[:, 0], lab[:, -1]])) - {0}
        bg = np.isin(lab, list(border))
        arr[..., 3] = np.where(bg, 0, 255)
        img = Image.fromarray(arr)
    return img


# ----------------------------------------------------------------------------- geometry helpers

def ray_radius(mask: np.ndarray, cx: float, cy: float, ang: float, rmax: float) -> float:
    """Distance from (cx, cy) along `ang` (radians, screen coords) to the mask boundary."""
    dx, dy = math.cos(ang), math.sin(ang)
    h, w = mask.shape
    r = 0.0
    step = 1.0
    while r < rmax:
        x, y = int(round(cx + dx * r)), int(round(cy + dy * r))
        if x < 0 or y < 0 or x >= w or y >= h or not mask[y, x]:
            break
        r += step
    return r


def rot(v, deg):
    a = math.radians(deg)
    c, s = math.cos(a), math.sin(a)
    return (v[0] * c - v[1] * s, v[0] * s + v[1] * c)


def angdiff(a, b):
    return (a - b + 180) % 360 - 180


class Placed:
    """A part placed in artboard space: pivot at world (X, Y), rotation, uniform scale k (vs sheet px)."""

    def __init__(self, rec, img, cls):
        self.rec, self.img, self.cls = rec, img, cls
        self.id = rec["id"]
        x0, y0, w, h = rec["source_bbox"]
        self.ox, self.oy, self.w, self.h = x0, y0, w, h
        self.pivot_sheet = (x0 + w / 2, y0 + h / 2)
        self.X = self.Y = 0.0
        self.rot = 0.0
        self.k = 1.0
        self.parent = "body"
        self.z = Z.get(cls, 1)
        self.size_mul = (1.0, 1.0)  # extra non-uniform drawn-size factor (foreshortened eye)

    @property
    def pivot_norm(self):
        return ((self.pivot_sheet[0] - self.ox) / self.w, (self.pivot_sheet[1] - self.oy) / self.h)

    def world_points(self, pts_sheet):
        """Map sheet-px points of this part to artboard px."""
        out = []
        for (x, y) in pts_sheet:
            v = ((x - self.pivot_sheet[0]) * self.k * self.size_mul[0], (y - self.pivot_sheet[1]) * self.k * self.size_mul[1])
            v = rot(v, self.rot)
            out.append((self.X + v[0], self.Y + v[1]))
        return out

    def centroid_sheet(self):
        a = np.asarray(self.img)[..., 3] > sp.ALPHA_T
        ys, xs = np.nonzero(a)
        return (self.ox + xs.mean(), self.oy + ys.mean())

    def far_point_sheet(self):
        a = np.asarray(self.img)[..., 3] > sp.ALPHA_T
        ys, xs = np.nonzero(a)
        d = np.hypot(xs + self.ox - self.pivot_sheet[0], ys + self.oy - self.pivot_sheet[1])
        j = int(np.argmax(d))
        return (self.ox + xs[j], self.oy + ys[j]), float(d[j])

    def corners_world(self):
        return self.world_points([(self.ox, self.oy), (self.ox + self.w, self.oy), (self.ox, self.oy + self.h), (self.ox + self.w, self.oy + self.h)])


# ----------------------------------------------------------------------------- reference analysis

def analyse_reference(path: str):
    img = keyed_rgba(path)
    arr = np.asarray(img)
    A = arr[..., 3] > sp.ALPHA_T
    lab, n = ndi.label(A)
    if n > 1:  # keep the main body only
        sizes = np.bincount(lab.ravel())
        sizes[0] = 0
        A = lab == int(sizes.argmax())
    H, W = A.shape
    dt = ndi.distance_transform_edt(np.pad(A, 1))[1:-1, 1:-1]
    upper = dt.copy()
    upper[int(H * 0.62):] = 0
    cy, cx = np.unravel_index(int(upper.argmax()), upper.shape)
    r = float(upper.max())
    yy, xx = np.mgrid[:H, :W]
    dist = np.hypot(xx - cx, yy - cy)
    limbs_mask = A & (dist > r * 1.1)
    lab, n = ndi.label(limbs_mask, structure=np.ones((3, 3)))
    limbs = []
    total = A.sum()
    for i in range(1, n + 1):
        m = lab == i
        if m.sum() < total * 0.004:
            continue
        ring = m & (dist < r * 1.45)
        if ring.sum() < 3:
            continue
        ys, xs = np.nonzero(ring)
        att = math.degrees(math.atan2((ys - cy).mean(), (xs - cx).mean()))
        ys2, xs2 = np.nonzero(m)
        ext = float(dist[m].max())
        cen = math.degrees(math.atan2(ys2.mean() - cy, xs2.mean() - cx))
        bulb = float(dt[m].max())
        limbs.append(dict(att=att, cen=cen, ext=ext, area=int(m.sum()), bulb=bulb,
                          cy=float(ys2.mean()), cx=float(xs2.mean()), lowest=float(ys2.max())))
    # tail: limb hanging below the soma with the biggest bulb
    tail = None
    below = [l for l in limbs if l["cy"] > cy + 0.4 * r]
    if below:
        tail = max(below, key=lambda l: l["bulb"] * 2 + l["lowest"] / H * r)
    return dict(img=img, arr=arr, mask=A, cx=float(cx), cy=float(cy), r=r, W=W, H=H, limbs=limbs, tail=tail)


def match_eyes(ref, eye_img: Image.Image, scale: float):
    """Find the two best matches of the eye template inside the reference soma. Returns [(x, y, s, score)]."""
    if cv2 is None:
        return []
    g = cv2.cvtColor(ref["arr"][..., :3].copy(), cv2.COLOR_RGB2GRAY).astype(np.float32)
    x0 = int(max(0, ref["cx"] - ref["r"] * 1.5)); x1 = int(min(ref["W"], ref["cx"] + ref["r"] * 1.6))
    y0 = int(max(0, ref["cy"] - ref["r"] * 1.4)); y1 = int(min(ref["H"], ref["cy"] + ref["r"] * 1.4))
    roi = g[y0:y1, x0:x1]
    e = np.asarray(eye_img).astype(np.float32)
    ea = e[..., 3] / 255.0
    eg = (e[..., 0] * 0.299 + e[..., 1] * 0.587 + e[..., 2] * 0.114) * ea + 128 * (1 - ea)
    best = []
    for f in np.linspace(0.55, 1.6, 15):
        s = scale * f
        tw, th = max(6, int(eg.shape[1] * s)), max(6, int(eg.shape[0] * s))
        if tw >= roi.shape[1] or th >= roi.shape[0]:
            continue
        t = cv2.resize(eg, (tw, th), interpolation=cv2.INTER_AREA)
        res = cv2.matchTemplate(roi, t, cv2.TM_CCOEFF_NORMED)
        for _ in range(2):
            _, mv, _, ml = cv2.minMaxLoc(res)
            best.append((ml[0] + x0 + tw / 2, ml[1] + y0 + th / 2, s, float(mv), tw, th))
            cv2.rectangle(res, (max(0, ml[0] - tw // 2), max(0, ml[1] - th // 2)), (ml[0] + tw // 2, ml[1] + th // 2), -1, -1)
    best.sort(key=lambda b: -b[3])
    picks = []
    for b in best:
        if all(math.hypot(b[0] - p[0], b[1] - p[1]) > 0.6 * max(b[4], p[4]) for p in picks):
            picks.append(b)
        if len(picks) == 2:
            break
    return picks


def ref_soma_score(ref, soma_img: Image.Image, scale: float) -> float:
    """Colour similarity of the sheet soma and the reference soma disc (for assigning generic sheets)."""
    s = np.asarray(soma_img).astype(float)
    a = s[..., 3] > 128
    sh = np.histogramdd(s[a][:, :3], bins=6, range=[(0, 256)] * 3)[0].ravel()
    yy, xx = np.mgrid[:ref["H"], :ref["W"]]
    disc = (np.hypot(xx - ref["cx"], yy - ref["cy"]) < ref["r"] * 1.15) & ref["mask"]
    rh = np.histogramdd(ref["arr"][disc][:, :3].astype(float), bins=6, range=[(0, 256)] * 3)[0].ravel()
    sh /= sh.sum() or 1
    rh /= rh.sum() or 1
    return float(np.minimum(sh, rh).sum())


# ----------------------------------------------------------------------------- layout

def layout(meta, imgs, ov, ref):
    recs = {r["id"]: r for r in meta["parts"]}
    soma_rec = next(r for r in meta["parts"] if r["cls"] == "soma")
    Cx, Cy, R = soma_rec["inscribed"]
    soma_img = imgs[soma_rec["id"]]
    soma_mask = np.asarray(soma_img)[..., 3] > 128
    sx0, sy0 = soma_rec["source_bbox"][:2]
    scx, scy = Cx - sx0, Cy - sy0

    def rim(ang_deg):
        r = ray_radius(soma_mask, scx, scy, math.radians(ang_deg), R * 3)
        return min(max(r, R * 0.92), R * 1.3)

    used_ref = ref is not None
    S = (ref["r"] / R) if used_ref else (TARGET_R / R)
    O = (ref["cx"], ref["cy"]) if used_ref else (0.0, 0.0)  # soma centre in artboard px

    placed: dict[str, Placed] = {}

    def mk(rid, cls):
        p = Placed(recs[rid], imgs[rid], cls)
        p.k = S
        placed[rid] = p
        return p

    soma = mk(soma_rec["id"], "soma")
    soma.pivot_sheet = (Cx, Cy)
    soma.X, soma.Y = O
    soma.parent = "body"

    tail_chain = ov.get("tail_chain_ids") or []
    tendrils = [r for r in meta["parts"] if r["cls"] == "tendril" and r["id"] not in tail_chain]
    tails = [r for r in meta["parts"] if r["cls"] == "tail" and r["id"] not in tail_chain]
    eyes = sorted([r for r in meta["parts"] if r["cls"] == "eye"], key=lambda r: r["id"])
    lids = [r for r in meta["parts"] if r["cls"] == "lid"]
    others = [r for r in meta["parts"] if r["cls"] in ("beak", "prop")]

    # ---- tendrils: collapse along the ray, point radially
    tinfo = []
    for r in tendrils:
        p = mk(r["id"], "tendril")
        b = tuple(r.get("base") or p.pivot_sheet)
        p.pivot_sheet = b
        ang = math.degrees(math.atan2(b[1] - Cy, b[0] - Cx))
        cen = p.centroid_sheet()
        own = math.degrees(math.atan2(cen[1] - b[1], cen[0] - b[0]))
        _, length = p.far_point_sheet()
        tinfo.append(dict(p=p, ang=ang, own=own, length=length))
    has_tail = bool(tails or tail_chain)
    # spread angles a bit so stalks don't stack, and keep them out of the tail sector
    if tinfo:
        n = len(tinfo)
        minsep = min(32.0, 300.0 / max(n, 1))
        for _ in range(120):
            tinfo.sort(key=lambda t: t["ang"] % 360)
            for i in range(n):
                a, b = tinfo[i], tinfo[(i + 1) % n]
                d = (b["ang"] - a["ang"]) % 360
                if n > 1 and d < minsep:
                    push = (minsep - d) / 2 * 0.5
                    a["ang"] -= push
                    b["ang"] += push
            if has_tail:
                for t in tinfo:
                    d = angdiff(t["ang"], 95)
                    if abs(d) < 32:
                        t["ang"] += (1 if d >= 0 else -1) * 1.5
    ref_limbs = list(ref["limbs"]) if used_ref else []
    if used_ref and ref["tail"] is not None:
        ref_limbs = [l for l in ref_limbs if l is not ref["tail"]]
    # assign reference limbs to tendrils (greedy nearest angle)
    assign = {}
    if ref_limbs and tinfo:
        pairs = sorted(((abs(angdiff(t["ang"], l["att"])), i, j) for i, t in enumerate(tinfo) for j, l in enumerate(ref_limbs)))
        ti, lj = set(), set()
        for d, i, j in pairs:
            if i in ti or j in lj or d > 75:
                continue
            assign[i] = ref_limbs[j]
            ti.add(i)
            lj.add(j)
    for i, t in enumerate(tinfo):
        p = t["p"]
        ang = t["ang"]
        out_ang = ang
        k = S
        if i in assign:
            l = assign[i]
            ang = l["att"]
            out_ang = l["cen"] if abs(angdiff(l["cen"], l["att"])) < 50 else l["att"]
            want = l["ext"] - ref["r"] * 0.95  # reach beyond the rim, artboard px
            k = S * min(1.45, max(0.6, want / max(1.0, t["length"] * S)))
        rr = rim(ang)
        ov_px = R * 0.14
        p.X = O[0] + math.cos(math.radians(ang)) * (rr - ov_px) * S
        p.Y = O[1] + math.sin(math.radians(ang)) * (rr - ov_px) * S
        p.rot = angdiff(out_ang, t["own"])
        p.k = k
        p.ref_matched = i in assign

    # ---- tail: from the soma bottom, hanging down
    def place_tail(p, att=100.0, down=85.0, k=S):
        b = p.pivot_sheet
        cen = p.centroid_sheet()
        own = math.degrees(math.atan2(cen[1] - b[1], cen[0] - b[0]))
        rr = rim(att)
        p.X = O[0] + math.cos(math.radians(att)) * (rr - R * 0.18) * S
        p.Y = O[1] + math.sin(math.radians(att)) * (rr - R * 0.18) * S
        p.rot = angdiff(down, own)
        p.k = k

    for r in tails:
        p = mk(r["id"], "tail")
        p.pivot_sheet = tuple(r.get("base") or p.pivot_sheet)
        if used_ref and ref["tail"] is not None:
            l = ref["tail"]
            _, length = p.far_point_sheet()
            want = l["ext"] - ref["r"] * 0.95
            k = S * min(1.5, max(0.6, want / max(1.0, length * S)))
            place_tail(p, att=l["att"], down=l["cen"], k=k)
        else:
            place_tail(p)
    # segmented tail: chain of pieces, each hanging from the previous one's bottom
    prev = None
    for j, rid in enumerate(tail_chain):
        p = mk(rid, "tailseg")
        p.id = f"tail.{j}" if j else "tail"
        w, h = p.w, p.h
        p.pivot_sheet = (p.ox + w * 0.5, p.oy + h * 0.06)  # top-centre
        # stand each segment upright: principal axis vertical
        a = np.asarray(p.img)[..., 3] > 128
        ys, xs = np.nonzero(a)
        cov = np.cov(np.vstack([xs - xs.mean(), ys - ys.mean()]))
        ev, evec = np.linalg.eigh(cov)
        major = math.degrees(math.atan2(evec[1, 1], evec[0, 1]))
        top_w = p.w
        if h < w:  # lying segment: pivot on its left end, rotate so it hangs
            p.pivot_sheet = (p.ox + w * 0.06, p.oy + h * 0.5)
            p.rot = angdiff(90, major if abs(angdiff(major, 0)) < 90 else major + 180)
        if prev is None:
            rr = rim(100)
            p.X = O[0] + math.cos(math.radians(100)) * (rr - R * 0.2) * S
            p.Y = O[1] + math.sin(math.radians(100)) * (rr - R * 0.2) * S
            p.parent = "body"
        else:
            # far end of the previous segment
            fp, _ = prev.far_point_sheet()
            (fx, fy), = prev.world_points([fp])
            p.X, p.Y = fx, fy - 4
            p.parent = prev.id
        prev = p

    # ---- eyes
    eye_ids = {r["id"] for r in eyes}
    eh_target = R * 0.62
    defaults = {"eye.left": (0.02, -0.08), "eye.right": (0.62, -0.10)}
    matched_eyes = []
    if used_ref and eyes:
        er = eyes[0]
        es = S * eh_target / er["size"][1]
        matched_eyes = [m for m in match_eyes(ref, imgs[er["id"]], es) if m[3] > 0.35]
        if len(matched_eyes) == 2 and len(eyes) == 2:
            a, b = sorted(matched_eyes, key=lambda m: m[0])
            eh = max(a[5], b[5])
            same_row = abs(a[1] - b[1]) < 0.35 * eh and (b[0] - a[0]) > 0.6 * max(a[4], b[4]) and (b[0] - a[0]) < 1.4 * ref["r"]
            if same_row:
                matched_eyes = [a, b]
            else:
                # keep the stronger match; put its partner on the same row, on the face side
                m = max(matched_eyes, key=lambda m: m[3])
                gap = 0.62 * R * S
                if m[0] < ref["cx"] + 0.3 * ref["r"]:
                    matched_eyes = [m, (m[0] + gap, m[1] - 0.02 * R * S) + m[2:]]
                else:
                    matched_eyes = [(m[0] - gap, m[1] + 0.02 * R * S) + m[2:], m]
        elif len(eyes) == 1 and matched_eyes:
            matched_eyes = [max(matched_eyes, key=lambda m: m[0])]
        else:
            matched_eyes = []
    for j, r in enumerate(sorted(eyes, key=lambda r: r["id"])):
        p = mk(r["id"], "eye")
        p.parent = soma.id
        k = S * eh_target / r["size"][1]
        if matched_eyes:
            mx, my, ms, sc, tw, th = matched_eyes[j]
            ms = max(m[2] for m in matched_eyes)  # both eyes share the stronger (larger) match scale
            ms = min(1.4 * k, max(0.7 * k, ms))  # a stray match must not shrink or blow up the eyes
            p.X, p.Y, p.k = mx, my, ms
            if r["id"] == "eye.right" and len(eyes) == 2:
                p.size_mul = (0.86, 1.0)
            p.ref_matched = True
        else:
            dx, dy = defaults[r["id"]] if r["id"] in defaults else defaults["eye.right"]
            p.X, p.Y = O[0] + dx * R * S, O[1] + dy * R * S
            p.k = k
            if r["id"] == "eye.right" and len(eyes) == 2:
                p.size_mul = (0.86, 1.0)  # turned away a little
    # ---- lids, parented to the eye on their side
    for r in lids:
        side = r["id"].split(".")[-1]
        upper = ".upper." in r["id"]
        eid = f"eye.{side}" if f"eye.{side}" in placed else (next(iter(eye_ids)) if eye_ids else None)
        if eid is None:
            continue
        e = placed[eid]
        p = mk(r["id"], "lid")
        p.parent = eid
        ew = e.w * e.k * e.size_mul[0]
        eh = e.h * e.k * e.size_mul[1]
        p.k = ew * 1.06 / r["size"][0]
        lh = r["size"][1] * p.k
        p.pivot_sheet = (p.ox + p.w / 2, p.oy + p.h / 2)
        p.X = e.X
        p.Y = e.Y + (-1 if upper else 1) * (eh * 0.5 + lh * 0.12)
    # ---- beak / props
    for r in others:
        p = mk(r["id"], r["cls"])
        if r["cls"] == "beak":
            rr = rim(180)
            p.X = O[0] - (rr - R * 0.05) * S
            p.Y = O[1] + R * 0.18 * S
            p.pivot_sheet = (p.ox + p.w * 0.85, p.oy + p.h * 0.5)
            p.k = S * min(1.0, R * 0.55 / max(r["size"]))
        else:
            # props are held at the arm sockets: first on the right, next on the left
            nprop = sum(1 for q in placed.values() if q.cls == "prop") - 1
            side = 1 if nprop % 2 == 0 else -1
            p.k = S * min(1.0, R * 1.1 / max(r["size"]))
            p.X = O[0] + side * R * 1.25 * S
            p.Y = O[1] + R * 0.55 * S
            p.z = 3
    return placed, soma, S, used_ref, matched_eyes


# ----------------------------------------------------------------------------- rig document

def r2(v):
    return round(float(v), 2)


def pack(items, gap=2):
    """Shelf packing; items = list of (id, w, h). Returns rects {id: (x, y, w, h)}, atlas size."""
    total = sum((w + gap) * (h + gap) for _, w, h in items)
    width = max(max(w for _, w, h in items) + gap, int(math.sqrt(total * 1.25)))
    order = sorted(items, key=lambda t: -t[2])
    x = y = shelf = 0
    rects = {}
    for pid, w, h in order:
        if x + w > width:
            x = 0
            y += shelf + gap
            shelf = 0
        rects[pid] = (x, y, w, h)
        x += w + gap
        shelf = max(shelf, h)
    return rects, (width, y + shelf)


def motion(placed: dict[str, Placed], soma: Placed, S: float):
    """Per-state motion. Amplitudes in artboard px / degrees, scaled by soma size."""
    u = soma.h * soma.k / 128.0  # ~1 for a soma ~128 px tall
    tend = [p for p in placed.values() if p.cls == "tendril"]
    tails = [p for p in placed.values() if p.cls in ("tail", "tailseg")]
    eyes = [p for p in placed.values() if p.cls == "eye"]
    lids = [p for p in placed.values() if p.cls == "lid"]

    def droop_sign(p):
        # rotating clockwise (+) moves a right-pointing tip down; we want tips to sag downward
        far, _ = p.far_point_sheet()
        (fx, fy), = p.world_points([far])
        return 1.0 if fx >= p.X else -1.0

    def blink(times, close=0.08, dur=0.035):
        keys = [{"t": 0.0, "v": 1.0, "ease": "step"}]
        for t in times:
            keys += [{"t": r2(t), "v": 1.0, "ease": "easeIn"}, {"t": r2(t + dur), "v": close, "ease": "easeOut"},
                     {"t": r2(t + 2 * dur), "v": 1.0, "ease": "step"}]
        return keys

    def lid_keys(times, dur=0.035):
        keys = [{"t": 0.0, "v": 1.0, "ease": "step"}]
        for t in times:
            keys += [{"t": r2(t), "v": 1.0, "ease": "easeIn"}, {"t": r2(t + dur), "v": 5.0, "ease": "easeOut"},
                     {"t": r2(t + 2 * dur), "v": 1.0, "ease": "step"}]
        return keys

    def base_state(body_amp, body_f, rot_amp, tend_amp, tend_f, tail_amp, blinks, droop=0.0, eye_sy=None, tremor=0.0, sag=0.0, alpha=None):
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
        for i, p in enumerate(sorted(tend, key=lambda p: p.id)):
            m = {"sine": {"rotation": {"amplitude": r2(tend_amp), "frequency": tend_f, "phase": r2((i * 0.618) % 1)}}}
            if droop:
                m["keys"] = {"rotation": [{"t": 0, "v": r2(droop * droop_sign(p))}]}
            parts[p.id] = m
        for j, p in enumerate(sorted(tails, key=lambda p: p.id)):
            m = {"sine": {"rotation": {"amplitude": r2(tail_amp * (1 if j == 0 else 1.4)), "frequency": max(1, tend_f // 2 or 1),
                                       "phase": r2(0.1 + 0.15 * j)}}}
            if droop:
                m["keys"] = {"rotation": [{"t": 0, "v": r2(0.5 * droop * droop_sign(p))}]}
            parts[p.id] = m
        for p in eyes:
            keys = blink(blinks)
            if eye_sy is not None:
                keys = [dict(k, v=r2(k["v"] * eye_sy)) for k in keys]
            parts[p.id] = {"keys": {"scaleY": keys}}
        for p in lids:
            parts[p.id] = {"keys": {"scaleY": lid_keys(blinks)}}
        return {"parts": parts}

    st = {}
    st["Pending"] = base_state(1.2, 1, 0, 2.0, 1, 2.0, [0.62])
    st["Ready"] = base_state(1.8, 1, 0.8, 3.0, 1, 3.0, [0.45, 0.9])
    st["Active"] = base_state(3.5, 2, 2.0, 8.0, 2, 9.0, [0.3, 0.8])
    st["Gate"] = base_state(0.5, 1, 0, 1.0, 1, 1.0, [0.2, 0.28, 0.7], eye_sy=None)
    for p in eyes:  # alert: eyes slightly wider
        st["Gate"]["parts"][p.id]["keys"]["scaleX"] = [{"t": 0, "v": 1.08}]
    st["Blocked"] = base_state(1.0, 1, 0, 2.0, 1, 2.0, [0.5], droop=14.0, eye_sy=0.75, sag=4.0)
    st["Failed"] = base_state(0.6, 1, 0, 1.5, 1, 1.5, [0.4], droop=24.0, eye_sy=0.5, tremor=1.0, sag=9.0, alpha=0.92)
    # Complete: a settle bounce, then rest
    comp = base_state(0.0, 1, 0, 3.0, 1, 3.0, [0.7])
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


def build_rig(slug, meta, imgs, ov, ref):
    placed, soma, S, used_ref, matched_eyes = layout(meta, imgs, ov, ref)
    plist = sorted(placed.values(), key=lambda p: (p.z, p.id))
    # artboard: bounding box of all parts
    pts = [c for p in plist for c in p.corners_world()]
    xs, ys = [p[0] for p in pts], [p[1] for p in pts]
    # tighter bbox using alpha: approximate with corners shrunk 6%
    minx, maxx, miny, maxy = min(xs), max(xs), min(ys), max(ys)
    shrink = 1.0
    if max(maxx - minx, maxy - miny) > MAX_SIDE:
        shrink = MAX_SIDE / max(maxx - minx, maxy - miny)
    margin = 4
    # shift so the artboard starts at (margin, margin); apply global shrink around the origin
    for p in plist:
        p.X = (p.X - minx) * shrink + margin
        p.Y = (p.Y - miny) * shrink + margin
        p.k *= shrink
    W = (maxx - minx) * shrink + 2 * margin
    H = (maxy - miny) * shrink + 2 * margin
    # rasterize parts at their drawn size
    items, rasters = [], {}
    for p in plist:
        w = max(2, int(round(p.w * p.k * p.size_mul[0])))
        h = max(2, int(round(p.h * p.k * p.size_mul[1])))
        rasters[p.id] = p.img.resize((w, h), Image.LANCZOS)
        items.append((p.id, w, h))
    rects, (AW, AH) = pack(items)
    atlas = Image.new("RGBA", (AW, AH), (0, 0, 0, 0))
    for pid, (x, y, w, h) in rects.items():
        atlas.paste(rasters[pid], (x, y))
    atlas_name = slug.replace("-", "_") + "_rig_atlas.png"
    # local transforms
    byid = {p.id: p for p in plist}
    body = {"id": "body", "parent": None, "z": 0, "pivot": {"x": 0.5, "y": 0.5},
            "rest": {"x": r2(soma.X), "y": r2(soma.Y), "rotation": 0, "scaleX": 1, "scaleY": 1, "alpha": 1}}
    parts = [body]
    for p in plist:
        if p.parent == "body":
            px, py, prot = soma.X, soma.Y, 0.0
        else:
            par = byid[p.parent]
            px, py, prot = par.X, par.Y, par.rot
        lx, ly = rot((p.X - px, p.Y - py), -prot)
        x, y, w, h = rects[p.id]
        pv = p.pivot_norm
        parts.append({
            "id": p.id, "parent": p.parent, "z": p.z,
            "rect": {"x": x, "y": y, "w": w, "h": h},
            "size": {"w": w, "h": h},
            "pivot": {"x": r2(min(1.2, max(-0.2, pv[0])) * 1000 / 1000), "y": r2(min(1.2, max(-0.2, pv[1])))},
            "rest": {"x": r2(lx), "y": r2(ly), "rotation": r2(angdiff(p.rot - prot, 0)), "scaleX": 1, "scaleY": 1, "alpha": 1},
        })
    sp_ = {p["id"]: p for p in parts}
    atts = []
    spv = sp_[soma.id]["pivot"]
    atts.append({"id": "arm.socket.left", "part": soma.id, "x": 0.06, "y": r2(min(0.9, spv["y"] + 0.12)), "kind": "arm-socket"})
    atts.append({"id": "arm.socket.right", "part": soma.id, "x": 0.94, "y": r2(min(0.9, spv["y"] + 0.12)), "kind": "arm-socket"})
    for e in ("eye.left", "eye.right"):
        if e in sp_:
            atts.append({"id": e, "part": e, "x": 0.5, "y": 0.5, "kind": "eye"})
    rig = {
        "format": "aive-puppet-rig", "version": 1, "role": slug,
        "canvas": {"width": r2(W), "height": r2(H)},
        "atlas": {"image": atlas_name, "width": AW, "height": AH},
        "parts": parts, "attachments": atts,
        "states": motion(placed, soma, S),
        "fallbackState": "Pending",
    }
    info = dict(used_ref=used_ref, eyes_matched=bool(matched_eyes),
                tendrils_matched=sum(1 for p in plist if p.cls == "tendril" and getattr(p, "ref_matched", False)),
                tendrils=sum(1 for p in plist if p.cls == "tendril"))
    return rig, atlas, info


# ----------------------------------------------------------------------------- evaluator + renderer (mirror of rig-core.js)

def _ease(kind, u):
    return {"step": 0.0, "easeIn": u * u, "easeOut": 1 - (1 - u) ** 2,
            "easeInOut": (2 * u * u if u < 0.5 else 1 - 2 * (1 - u) ** 2)}.get(kind or "linear", u)


def _sample(keys, c):
    ks = sorted(keys, key=lambda k: k["t"])
    if len(ks) == 1:
        return ks[0]["v"]
    if c < ks[0]["t"]:
        frm, to, t0, t1, cc = ks[-1], ks[0], ks[-1]["t"], ks[0]["t"] + 1, c + 1
    else:
        i = max(j for j in range(len(ks)) if ks[j]["t"] <= c)
        frm = ks[i]
        if i == len(ks) - 1:
            to, t0, t1 = ks[0], frm["t"], ks[0]["t"] + 1
        else:
            to, t0, t1 = ks[i + 1], frm["t"], ks[i + 1]["t"]
        cc = c
    u = (cc - t0) / (t1 - t0) if t1 > t0 else 0.0
    return frm["v"] + (to["v"] - frm["v"]) * _ease(frm.get("ease"), min(1.0, max(0.0, u)))


def evaluate(rig, state, phase):
    c = phase - math.floor(phase)
    st = (rig.get("states") or {}).get(state) or (rig.get("states") or {}).get(rig.get("fallbackState") or "", {}) or {}
    pm = st.get("parts", {})
    ident = {"x": 0, "y": 0, "rotation": 0, "scaleX": 1, "scaleY": 1, "alpha": 1}

    def ch(pid, name):
        m = pm.get(pid, {})
        v = ident[name]
        if name in m.get("keys", {}):
            v = _sample(m["keys"][name], c)
        s = m.get("sine", {}).get(name)
        if s:
            v += s["amplitude"] * math.sin(2 * math.pi * (s.get("frequency", 1) * c + s.get("phase", 0)))
        return v

    world = {}
    byid = {p["id"]: p for p in rig["parts"]}

    def w(pid):
        if pid in world:
            return world[pid]
        p = byid[pid]
        r = p.get("rest", {})
        tx = r.get("x", 0) + ch(pid, "x")
        ty = r.get("y", 0) + ch(pid, "y")
        th = math.radians(r.get("rotation", 0) + ch(pid, "rotation"))
        sx = r.get("scaleX", 1) * ch(pid, "scaleX")
        sy = r.get("scaleY", 1) * ch(pid, "scaleY")
        a = r.get("alpha", 1) * ch(pid, "alpha")
        L = np.array([[math.cos(th) * sx, -math.sin(th) * sy, tx], [math.sin(th) * sx, math.cos(th) * sy, ty], [0, 0, 1]])
        if p.get("parent"):
            PM, pa = w(p["parent"])
            M, al = PM @ L, pa * a
        else:
            M, al = L, a
        world[pid] = (M, min(1, max(0, al)))
        return world[pid]

    for p in rig["parts"]:
        w(p["id"])
    return world


def render(rig, atlas: Image.Image, state, phase, scale=1.0):
    W, H = int(math.ceil(rig["canvas"]["width"] * scale)), int(math.ceil(rig["canvas"]["height"] * scale))
    out = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    world = evaluate(rig, state, phase)
    order = sorted(enumerate(rig["parts"]), key=lambda t: (t[1].get("z", 0), t[0]))
    for _, p in order:
        if "rect" not in p:
            continue
        M, al = world[p["id"]]
        r = p["rect"]
        sz = p.get("size") or {"w": r["w"], "h": r["h"]}
        pv = p.get("pivot") or {"x": 0.5, "y": 0.5}
        crop = atlas.crop((r["x"], r["y"], r["x"] + r["w"], r["y"] + r["h"]))
        # source pixel (u, v) -> local (u * w / rw - px * w, ...) -> world (M) -> out (scale)
        A = np.array([[sz["w"] / r["w"], 0, -pv["x"] * sz["w"]], [0, sz["h"] / r["h"], -pv["y"] * sz["h"]], [0, 0, 1]])
        T = np.diag([scale, scale, 1]) @ M @ A
        Ti = np.linalg.inv(T)
        layer = crop.transform((W, H), Image.AFFINE, data=tuple(Ti[:2].ravel()), resample=Image.BICUBIC)
        if al < 1:
            a = np.asarray(layer).copy()
            a[..., 3] = (a[..., 3] * al).astype(np.uint8)
            layer = Image.fromarray(a)
        out.alpha_composite(layer)
    return out


def compare(rig, atlas, ref_path, out_path, title):
    frames = [("rest", render(rig, atlas, "__rest__", 0.0) if False else render(dict(rig, states={}), atlas, "Pending", 0))]
    for ph in (0.2, 0.55, 0.8):
        frames.append((f"Active {ph}", render(rig, atlas, "Active", ph)))
    frames.append(("Blocked", render(rig, atlas, "Blocked", 0.3)))
    frames.append(("Failed", render(rig, atlas, "Failed", 0.37)))
    H = max(f.height for _, f in frames)
    refimg = None
    if ref_path:
        refimg = keyed_rgba(ref_path)
        if refimg.height != H:
            refimg = refimg.resize((max(1, int(refimg.width * H / refimg.height)), H), Image.LANCZOS)
    cells = ([("reference", refimg)] if refimg else []) + frames
    Wt = sum(f.width + 10 for _, f in cells) + 10
    sheet = Image.new("RGBA", (Wt, H + 30), (24, 26, 34, 255))
    d = ImageDraw.Draw(sheet)
    d.text((8, 4), title, fill=(255, 255, 255, 255))
    x = 10
    for name, f in cells:
        sheet.alpha_composite(f, (x, 26))
        d.text((x, 16), name, fill=(170, 200, 255, 255))
        x += f.width + 10
    sheet.convert("RGB").quantize(256, method=Image.Quantize.MEDIANCUT).save(out_path, optimize=True)


# ----------------------------------------------------------------------------- driver

def all_sheets():
    """[(dir, file, parts_dir, meta)] for every sliced parts sheet."""
    res = []
    for d, f in sp.parts_sheets():
        pdir = os.path.join(CHAR_ROOT, d, "rig", "parts", sp.sheet_slug(f))
        if os.path.exists(os.path.join(pdir, "parts.json")):
            res.append((d, f, pdir))
    return res


def plan_slugs(sheets, ov_all):
    """Assign rig slugs; mark the newest sheet of a role."""
    by_dir: dict[str, list] = {}
    for d, f, pdir in sheets:
        by_dir.setdefault(d, []).append((d, f, pdir))
    plan = []
    for d, lst in by_dir.items():
        role = sp.role_slug(d)
        lst.sort(key=lambda t: sheet_time(t[0], t[1]))
        newest = lst[-1][1]
        for i, (dd, f, pdir) in enumerate(lst):
            if role == "generic":
                slug = f"generic-{i + 1:02d}"
            elif len(lst) > 1:
                slug = f"{role}-{i + 1}"
            else:
                slug = role
            slug = ov_all.get(f"{dd}/{f}", {}).get("slug", slug)
            plan.append(dict(dir=dd, file=f, pdir=pdir, slug=slug, newest=(f == newest), n_in_role=len(lst)))
    return plan


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", default="")
    ap.add_argument("--no-compare", action="store_true")
    a = ap.parse_args()
    ov_all = sp.load_overrides()
    plan = plan_slugs(all_sheets(), ov_all)
    if a.only:
        plan = [it for it in plan if a.only in f"{it['dir']}/{it['file']}" or a.only in it["slug"]]
    os.makedirs(RIG_OUT, exist_ok=True)
    # reference assignment per dir (Hungarian over soma colour similarity when a dir has several sheets/refs)
    refs_cache = {}
    report = []
    by_dir: dict[str, list] = {}
    for item in plan:
        by_dir.setdefault(item["dir"], []).append(item)
    for d, items in by_dir.items():
        refs = references(d)
        for it in items:
            it["ref"] = None
        if not refs:
            continue
        loaded = [(p, refs_cache.setdefault(p, analyse_reference(p))) for p in refs]
        if len(items) == 1 and len(loaded) == 1:
            items[0]["ref"] = loaded[0][0]
            continue
        from scipy.optimize import linear_sum_assignment
        cost = np.zeros((len(items), len(loaded)))
        for i, it in enumerate(items):
            meta, imgs = load_sheet(it["pdir"])
            sr = next(r for r in meta["parts"] if r["cls"] == "soma")
            for j, (p, ref) in enumerate(loaded):
                cost[i, j] = -ref_soma_score(ref, imgs[sr["id"]], 1.0)
        ri, cj = linear_sum_assignment(cost)
        for i, j in zip(ri, cj):
            items[i]["ref"] = loaded[j][0]
    for it in plan:
        key = f"{it['dir']}/{it['file']}"
        ov = ov_all.get(key, {})
        if a.only and a.only not in key and a.only not in it["slug"]:
            continue
        if ov.get("no_rig"):
            report.append(dict(slug=it["slug"], key=key, skipped=ov.get("no_rig")))
            print(f"skip {key}: {ov.get('no_rig')}")
            continue
        meta, imgs = load_sheet(it["pdir"])
        if ov.get("tail_chain"):
            idx2id = {r.get("idx"): r["id"] for r in meta["parts"]}
            ov = dict(ov, tail_chain_ids=[idx2id[i] for i in ov["tail_chain"]])
        ref = None
        if it["ref"] and not ov.get("ignore_reference"):
            ref = refs_cache.get(it["ref"]) or analyse_reference(it["ref"])
        rig, atlas, info = build_rig(it["slug"], meta, imgs, ov, ref)
        jpath = os.path.join(RIG_OUT, it["slug"] + ".rig.json")
        with open(jpath, "w") as fh:
            json.dump(rig, fh, indent=1)
            fh.write("\n")
        atlas.save(os.path.join(RIG_OUT, rig["atlas"]["image"]), optimize=True)
        if not a.no_compare:
            cmp_path = os.path.join(CHAR_ROOT, it["dir"], "rig", it["slug"] + ".compare.png")
            compare(rig, atlas, it["ref"] if ref else None, cmp_path, f"{it['slug']}  ({it['file']})  ref={os.path.basename(it['ref']) if ref else 'heuristic'}")
        rec = dict(slug=it["slug"], key=key, ref=os.path.basename(it["ref"]) if ref else None, newest=it["newest"],
                   sheets_in_role=it["n_in_role"], canvas=[rig["canvas"]["width"], rig["canvas"]["height"]],
                   atlas=[rig["atlas"]["width"], rig["atlas"]["height"]], **info)
        report.append(rec)
        print(json.dumps(rec))
    if not a.only:
        with open(os.path.join(sp.HERE, "rigs-report.json"), "w") as fh:
            json.dump(report, fh, indent=1)


if __name__ == "__main__":
    sys.exit(main())
