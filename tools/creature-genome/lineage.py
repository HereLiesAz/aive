"""Turn measured traits (genes.json) into alleles, kinship, families, rarity and breeding difficulty.

Writes lineage.json: one record per creature plus the family tree (average linkage).
"""
import json, math, sys
from collections import Counter
import numpy as np
from scipy.cluster.hierarchy import linkage, fcluster, dendrogram
from scipy.spatial.distance import squareform

rows = json.load(open(sys.argv[1]))
OUT = sys.argv[2]
N = len(rows)


def tertile(values, labels):
    q1, q2 = np.quantile(values, [1 / 3, 2 / 3])
    return [labels[0] if v <= q1 else labels[1] if v <= q2 else labels[2] for v in values]


core_shade = ["Dark" if r["somaValue"] < 0.45 else "Dusk" if r["somaValue"] < 0.65 else "Bright" for r in rows]
core_pattern = tertile([r["somaPattern"] for r in rows], ["Plain", "Mottled", "Speckled"])
skin = ["Glass" if r["glass"] > 0.13 else "Sheen" if r["glass"] > 0.09 else "Opaque" for r in rows]
arbor = tertile([r["arbor"] for r in rows], ["Sparse", "Branching", "Overgrown"])
spectrum = ["Monotone" if r["hueSpread"] <= 1 else "Duotone" if r["hueSpread"] == 2 else "Prismatic" for r in rows]

for i, r in enumerate(rows):
    r["genes"] = {
        "Pigment": r["pigment"],
        "Accent": r["accent"] or "none",
        "Spectrum": spectrum[i],
        "Core": core_shade[i],
        "Pattern": core_pattern[i],
        "Skin": skin[i],
        "Arbor": arbor[i],
    }

# Kinship: colour (limb hue histograms, cosine) and form (scalar traits), weighted.
H = np.array([r["limbHist"] for r in rows])
H = H / np.linalg.norm(H, axis=1, keepdims=True).clip(1e-9)
colour_sim = H @ H.T
scal = np.array([[r["somaValue"], r["somaPattern"], r["glass"], r["arbor"], r["hueSpread"]] for r in rows])
scal = (scal - scal.mean(0)) / scal.std(0).clip(1e-9)
form_d = np.sqrt(((scal[:, None, :] - scal[None, :, :]) ** 2).sum(-1))
form_sim = 1 - form_d / form_d.max()
sim = 0.6 * colour_sim + 0.4 * form_sim
np.fill_diagonal(sim, 1.0)
dist = 1 - sim
np.fill_diagonal(dist, 0.0)
dist = (dist + dist.T) / 2

Z = linkage(squareform(dist, checks=False), method="average")
fam = fcluster(Z, t=14, criterion="maxclust")
order = dendrogram(Z, no_plot=True)["leaves"]

# Rarity: how improbable a creature's allele combination is in the population.
freq = {k: Counter(r["genes"][k] for r in rows) for k in rows[0]["genes"]}
surprisal = [sum(-math.log(freq[k][r["genes"][k]] / N) for k in freq) for r in rows]
ranks = np.argsort(np.argsort(surprisal)) / (N - 1)
TIERS = [(0.5, "Common"), (0.75, "Uncommon"), (0.9, "Rare"), (0.97, "Very rare"), (1.01, "Mythic")]

# Breeding difficulty: how isolated a creature is from its nearest kin.
nearest = np.sort(sim, axis=1)[:, -2]
diff_rank = np.argsort(np.argsort(-nearest)) / (N - 1)

def name(folder):
    words = folder.split("_")[1:]
    for cut in ("sheet",):
        if cut in words:
            words = words[: words.index(cut)] + ["(" + words[words.index(cut) + 1] + ")"]
    return " ".join(w.capitalize() for w in words)

families = {}
for i, r in enumerate(rows):
    families.setdefault(int(fam[i]), []).append(i)

def family_name(members):
    pig = Counter(rows[m]["genes"]["Pigment"] for m in members).most_common(1)[0][0]
    core = Counter(rows[m]["genes"]["Core"] for m in members).most_common(1)[0][0]
    return f"{pig.capitalize()} {core.lower()}-cores"

out = []
for i, r in enumerate(rows):
    kin = [int(j) for j in np.argsort(-sim[i]) if j != i][:3]
    tier = next(t for cut, t in TIERS if ranks[i] < cut)
    difficulty = "Easy" if diff_rank[i] < 0.5 else "Tricky" if diff_rank[i] < 0.8 else "Hard" if diff_rank[i] < 0.95 else "Near impossible"
    out.append({
        "id": r["id"],
        "name": name(r["id"]),
        "genes": r["genes"],
        "family": int(fam[i]),
        "rarity": tier,
        "breeding": difficulty,
        "kin": [{"id": rows[j]["id"], "name": name(rows[j]["id"]), "similarity": round(float(sim[i, j]) * 100)} for j in kin],
        "order": order.index(i),
    })

json.dump({
    "creatures": sorted(out, key=lambda c: c["order"]),
    "families": [{"id": k, "name": family_name(v), "members": [rows[m]["id"] for m in sorted(v, key=order.index)]}
                 for k, v in sorted(families.items(), key=lambda kv: min(order.index(m) for m in kv[1]))],
    "linkage": [[int(a), int(b), round(float(d), 4), int(n)] for a, b, d, n in Z],
    "leaves": [rows[i]["id"] for i in order],
    "alleleFrequencies": {k: dict(v) for k, v in freq.items()},
}, open(OUT, "w"), indent=1)

for f in json.load(open(OUT))["families"]:
    print(f["id"], f["name"], len(f["members"]), f["members"])
print(Counter(c["rarity"] for c in out), Counter(c["breeding"] for c in out))
