"""Measure visible traits of every node creature painting and write genes.json.

Run from docs/swarm-terrarium/characters. Picks, per folder, the painting whose largest opaque
component dominates (a composed creature, not a parts sheet).
"""
import colorsys, glob, json, math, os, sys
import numpy as np
from PIL import Image
from scipy import ndimage

OUT = sys.argv[1]
HUE_NAMES = ["red", "orange", "amber", "yellow", "lime", "green", "teal", "cyan", "blue", "indigo", "violet", "magenta"]


def load(path):
    im = Image.open(path).convert("RGBA")
    im.thumbnail((400, 400))
    return np.asarray(im).astype(np.float32) / 255.0


def composed_share(a):
    mask = a[..., 3] > 0.15
    lab, n = ndimage.label(mask)
    if n == 0:
        return 0.0, n
    sizes = ndimage.sum(mask, lab, range(1, n + 1))
    return float(sizes.max() / sizes.sum()), n


def pick(folder):
    cands = sorted(glob.glob(folder + "/*.png"))
    best, best_s = None, -1
    for c in cands:
        if "Matplotlib" in c:
            continue
        s, _ = composed_share(load(c))
        if s > best_s:
            best, best_s = c, s
    return best, best_s


def features(a):
    alpha = a[..., 3]
    mask = alpha > 0.15
    rgb = a[..., :3]
    hsv = np.zeros_like(rgb)
    flat = rgb.reshape(-1, 3)
    hsv = np.array([colorsys.rgb_to_hsv(*p) for p in flat]).reshape(rgb.shape)
    h, s, v = hsv[..., 0], hsv[..., 1], hsv[..., 2]

    # Soma: the thickest part of the body.
    dist = ndimage.distance_transform_edt(mask)
    cy, cx = np.unravel_index(np.argmax(dist), dist.shape)
    r = dist[cy, cx]
    yy, xx = np.indices(mask.shape)
    soma = ((yy - cy) ** 2 + (xx - cx) ** 2 <= (0.8 * r) ** 2) & mask
    limbs = mask & ~(((yy - cy) ** 2 + (xx - cx) ** 2) <= (1.2 * r) ** 2)

    def hue_hist(region):
        sel = region & (s > 0.35) & (v > 0.3)
        hist = np.zeros(12)
        if sel.sum() == 0:
            return hist
        idx = (h[sel] * 12).astype(int) % 12
        np.add.at(hist, idx, s[sel])
        return hist / hist.sum()

    limb_hist = hue_hist(limbs)
    soma_hist = hue_hist(soma)
    order = np.argsort(limb_hist)[::-1]
    area = mask.sum()
    perim = (mask ^ ndimage.binary_erosion(mask)).sum()
    return {
        "pigment": HUE_NAMES[order[0]],
        "pigmentHue": int(order[0]),
        "accent": HUE_NAMES[order[1]] if limb_hist[order[1]] > 0.12 else None,
        "accentHue": int(order[1]) if limb_hist[order[1]] > 0.12 else None,
        "hueSpread": int((limb_hist > 0.1).sum()),
        "somaValue": float(v[soma].mean()) if soma.any() else 0.0,
        "somaSat": float(s[soma].mean()) if soma.any() else 0.0,
        "somaHue": HUE_NAMES[int(np.argmax(soma_hist))] if soma_hist.sum() else None,
        "somaPattern": float(v[soma].std()) if soma.any() else 0.0,
        "glass": float(((alpha > 0.15) & (alpha < 0.9)).sum() / max(area, 1)),
        "arbor": float(perim / math.sqrt(max(area, 1))),
        "somaShare": float(soma.sum() / max(area, 1)),
        "limbHist": [round(float(x), 4) for x in limb_hist],
    }


def main():
    rows = []
    for folder in sorted(d for d in os.listdir(".") if os.path.isdir(d)):
        if folder == "000_generic":
            continue
        path, share = pick(folder)
        if path is None or share < 0.25:
            print("skip", folder, share, file=sys.stderr)
            continue
        f = features(load(path))
        f.update({"id": folder, "file": path, "share": round(share, 3)})
        rows.append(f)
        print(folder, f["pigment"], f["accent"], round(f["somaValue"], 2), round(f["glass"], 2), round(f["arbor"], 1), file=sys.stderr)
    json.dump(rows, open(OUT, "w"), indent=1)


main()
