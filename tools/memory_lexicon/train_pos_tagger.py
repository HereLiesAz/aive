#!/usr/bin/env python3
"""Trains the averaged-perceptron part-of-speech tagger the programmatic memory clerks use.

Algorithm: Honnibal's greedy averaged perceptron
(https://explosion.ai/blog/part-of-speech-pos-tagger-in-python), Penn Treebank tags (XPOS).
Training data: Universal Dependencies English-EWT (CC BY-SA 4.0), train + dev; evaluated on test.
GUM is not used: its UD release is CC BY-NC-SA (non-commercial).

Every token also carries a WordNet feature: the parts of speech WordNet 3.1 allows for its base
forms (morphy over aive-wordnet-v1), so words EWT never saw are still constrained by WordNet.

Code tokens (backtick spans, identifiers, paths, URLs) are masked to CODE_TOKEN before tagging at
runtime, so training replaces a share of proper nouns with it and the model learns its contexts.

Output: shared/src/commonMain/composeResources/files/aive-pos-tagger-v1.txt.gz, read by
`PerceptronPosTagger` (shared/.../memory/text/PerceptronPosTagger.kt). The Kotlin port must build
the same feature strings as `features()` here; tools/memory_lexicon/pos_tagger_golden.tsv pins it.

  #aive-pos-tagger v1
  #license    "# " lines
  #tags       one tag per line (index = line order)
  #tagdict    word  tag                  frequent, unambiguous words: tagged without the model
  #weights    feature  tag:weight ...    weights are averaged values x 1000, rounded, zeros dropped

  python3 tools/memory_lexicon/train_pos_tagger.py
"""
from __future__ import annotations

import gzip
import io
import random
import sys
import urllib.request
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / "build/ud"
OUTPUT = ROOT / "shared/src/commonMain/composeResources/files/aive-pos-tagger-v1.txt.gz"
LEXICON = ROOT / "shared/src/commonMain/composeResources/files/aive-wordnet-v1.txt.gz"
GOLDEN = Path(__file__).resolve().parent / "pos_tagger_golden.tsv"
BASE_URL = "https://raw.githubusercontent.com/UniversalDependencies/UD_English-EWT/master/"
FILES = ["en_ewt-ud-train.conllu", "en_ewt-ud-dev.conllu", "en_ewt-ud-test.conllu", "LICENSE.txt"]

CODE_TOKEN = "CODE_TOKEN"
ITERATIONS = 8
SCALE = 1000
SEED = 7
CODE_MASK_RATE = 0.08
START = ["-START-", "-START2-"]
END = ["-END-", "-END2-"]


def fetch() -> None:
    DATA.mkdir(parents=True, exist_ok=True)
    for name in FILES:
        target = DATA / (name if name != "LICENSE.txt" else "LICENSE-EWT.txt")
        if not target.exists():
            with urllib.request.urlopen(BASE_URL + name) as response:
                target.write_bytes(response.read())


def read_conllu(path: Path) -> list[tuple[list[str], list[str]]]:
    sentences, words, tags = [], [], []
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line:
            if words:
                sentences.append((words, tags))
            words, tags = [], []
            continue
        if line.startswith("#"):
            continue
        cols = line.split("\t")
        if "-" in cols[0] or "." in cols[0]:
            continue  # multiword token ranges and empty nodes
        words.append(cols[1])
        tags.append(cols[4] if cols[4] != "_" else cols[3])
    if words:
        sentences.append((words, tags))
    return sentences


class WordNetPos:
    """Part-of-speech membership from the shipped lexicon, with WordNet's morphy detachment rules."""

    RULES = {
        "n": [("s", ""), ("ses", "s"), ("xes", "x"), ("zes", "z"), ("ches", "ch"), ("shes", "sh"), ("men", "man"), ("ies", "y")],
        "v": [("s", ""), ("ies", "y"), ("es", "e"), ("es", ""), ("ed", "e"), ("ed", ""), ("ing", "e"), ("ing", "")],
        "a": [("er", ""), ("est", ""), ("er", "e"), ("est", "e")],
        "r": [],
    }

    def __init__(self, path: Path) -> None:
        self.words: dict[str, set[str]] = defaultdict(set)
        self.exceptions: dict[tuple[str, str], list[str]] = {}
        section = None
        with gzip.open(path, "rt", encoding="utf-8") as lines:
            for line in lines:
                line = line.rstrip("\n")
                if line.startswith("#"):
                    section = line
                    continue
                cols = line.split("\t")
                if section == "#index":
                    self.words[cols[0]].add(cols[1])
                elif section == "#pos":
                    self.words[cols[0]].update(cols[1])
                elif section == "#exceptions":
                    self.exceptions[(cols[0], cols[1])] = cols[2].split("|")
        self.cache: dict[str, str] = {}

    def tags(self, word: str) -> str:
        """Sorted WordNet parts of speech for any base form of `word` ("" when unknown)."""
        lower = word.lower()
        cached = self.cache.get(lower)
        if cached is not None:
            return cached
        found = set()
        for pos in "nvar":
            if pos in self.words.get(lower, ()):
                found.add(pos)
                continue
            bases = self.exceptions.get((pos, lower), [])
            bases = bases + [lower[: -len(suffix)] + ending for suffix, ending in self.RULES[pos] if lower.endswith(suffix) and len(lower) > len(suffix)]
            if any(pos in self.words.get(base, ()) for base in bases):
                found.add(pos)
        result = "".join(sorted(found))
        self.cache[lower] = result
        return result


WORDNET: WordNetPos | None = None


def normalize(word: str) -> str:
    if word == CODE_TOKEN:
        return word
    if "-" in word and word[0] != "-":
        return "!HYPHEN"
    if word.isdigit() and len(word) == 4:
        return "!YEAR"
    if word[:1].isdigit():
        return "!DIGITS"
    return word.lower()


def features(i: int, word: str, context: list[str], prev: str, prev2: str) -> list[str]:
    """Feature strings for token i; `context` is START + normalized words + END, so i is offset by 2."""
    i += len(START)
    wordnet = WORDNET.tags(word) if word != CODE_TOKEN else "code"
    next_word = context[i + 1]
    next_wordnet = WORDNET.tags(next_word) if next_word not in END and next_word != CODE_TOKEN else "-"
    return [
        "i wn " + wordnet,
        "i wn+i-1 tag " + wordnet + " " + prev,
        "i+1 wn " + next_wordnet,
        "bias",
        "i suffix " + word[-3:],
        "i pref1 " + word[:1],
        "i-1 tag " + prev,
        "i-2 tag " + prev2,
        "i tag+i-2 tag " + prev + " " + prev2,
        "i word " + context[i],
        "i-1 tag+i word " + prev + " " + context[i],
        "i-1 word " + context[i - 1],
        "i-1 suffix " + context[i - 1][-3:],
        "i-2 word " + context[i - 2],
        "i+1 word " + context[i + 1],
        "i+1 suffix " + context[i + 1][-3:],
        "i+2 word " + context[i + 2],
    ]


class AveragedPerceptron:
    def __init__(self) -> None:
        self.weights: dict[str, dict[str, float]] = {}
        self.classes: list[str] = []
        self._totals: dict[tuple[str, str], float] = defaultdict(float)
        self._stamps: dict[tuple[str, str], int] = defaultdict(int)
        self.i = 0

    def predict(self, feats: list[str]) -> str:
        scores: dict[str, float] = defaultdict(float)
        for feat in feats:
            for label, weight in self.weights.get(feat, {}).items():
                scores[label] += weight
        # ties break by class order, which the Kotlin port mirrors
        return max(self.classes, key=lambda label: (scores[label], -self.classes.index(label)))

    def update(self, truth: str, guess: str, feats: list[str]) -> None:
        self.i += 1
        if truth == guess:
            return
        for feat in feats:
            weights = self.weights.setdefault(feat, {})
            for label, delta in ((truth, 1.0), (guess, -1.0)):
                key = (feat, label)
                current = weights.get(label, 0.0)
                self._totals[key] += (self.i - self._stamps[key]) * current
                self._stamps[key] = self.i
                weights[label] = current + delta

    def average(self) -> None:
        for feat, weights in self.weights.items():
            averaged = {}
            for label, weight in weights.items():
                key = (feat, label)
                total = self._totals[key] + (self.i - self._stamps[key]) * weight
                value = total / self.i
                if round(value * SCALE) != 0:
                    averaged[label] = value
            self.weights[feat] = averaged


def looks_like_code(word: str) -> bool:
    return any(c in word for c in "_/()") or ("." in word[1:-1] and not word.replace(".", "").isdigit()) or (
        word[:1].islower() and any(c.isupper() for c in word[1:])
    )


def masked(sentence: tuple[list[str], list[str]], rng: random.Random) -> tuple[list[str], list[str]]:
    words, tags = sentence
    out = []
    for word, tag in zip(words, tags):
        if looks_like_code(word) and tag.startswith("NN"):
            out.append(CODE_TOKEN)
        elif tag in ("NNP", "NNPS") and rng.random() < CODE_MASK_RATE:
            out.append(CODE_TOKEN)
        else:
            out.append(word)
    return out, tags


def tagdict(sentences) -> dict[str, str]:
    counts: dict[str, dict[str, int]] = defaultdict(lambda: defaultdict(int))
    for words, tags in sentences:
        for word, tag in zip(words, tags):
            counts[word][tag] += 1
    result = {}
    for word, tag_counts in counts.items():
        tag, mode = max(tag_counts.items(), key=lambda kv: (kv[1], kv[0]))
        total = sum(tag_counts.values())
        if total >= 20 and mode / total >= 0.97:
            result[word] = tag
    return result


def tag_sentence(model: AveragedPerceptron, tags: dict[str, str], words: list[str]) -> list[str]:
    context = START + [normalize(w) for w in words] + END
    prev, prev2 = START
    out = []
    for i, word in enumerate(words):
        guess = tags.get(word)
        if guess is None:
            guess = model.predict(features(i, word, context, prev, prev2))
        out.append(guess)
        prev2, prev = prev, guess
    return out


def main() -> int:
    global WORDNET
    fetch()
    WORDNET = WordNetPos(LEXICON)
    rng = random.Random(SEED)
    train = read_conllu(DATA / "en_ewt-ud-train.conllu") + read_conllu(DATA / "en_ewt-ud-dev.conllu")
    test = read_conllu(DATA / "en_ewt-ud-test.conllu")

    model = AveragedPerceptron()
    model.classes = sorted({tag for _, tags in train for tag in tags})
    tags = tagdict(train)
    for iteration in range(ITERATIONS):
        correct = total = 0
        for sentence in train:
            words, gold = masked(sentence, rng)
            context = START + [normalize(w) for w in words] + END
            prev, prev2 = START
            for i, word in enumerate(words):
                guess = tags.get(word)
                if guess is None:
                    feats = features(i, word, context, prev, prev2)
                    guess = model.predict(feats)
                    model.update(gold[i], guess, feats)
                prev2, prev = prev, guess
                correct += guess == gold[i]
                total += 1
        rng.shuffle(train)
        print(f"iteration {iteration + 1}: train {correct / total:.4f}", file=sys.stderr)
    model.average()

    correct = total = 0
    for words, gold in test:
        guessed = tag_sentence(model, tags, words)
        correct += sum(g == t for g, t in zip(guessed, gold))
        total += len(gold)
    print(f"EWT test accuracy: {correct / total:.4f}", file=sys.stderr)

    out = io.StringIO()
    out.write("#aive-pos-tagger v1\n#license\n")
    out.write("# Averaged perceptron trained on Universal Dependencies English-EWT (CC BY-SA 4.0):\n")
    out.write("# https://github.com/UniversalDependencies/UD_English-EWT\n")
    out.write("# These weights are an adaptation of that corpus and are shared under CC BY-SA 4.0.\n")
    out.write("#tags\n")
    for tag in model.classes:
        out.write(tag + "\n")
    out.write("#tagdict\n")
    for word in sorted(tags):
        out.write(f"{word}\t{tags[word]}\n")
    out.write("#weights\n")
    index = {tag: i for i, tag in enumerate(model.classes)}
    for feat in sorted(model.weights):
        weights = model.weights[feat]
        if not weights or "\t" in feat or "\n" in feat:
            continue
        cells = " ".join(f"{index[label]}:{round(value * SCALE)}" for label, value in sorted(weights.items()))
        out.write(f"{feat}\t{cells}\n")
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    with open(OUTPUT, "wb") as raw, gzip.GzipFile(fileobj=raw, mode="wb", compresslevel=9, mtime=0, filename="") as gz:
        gz.write(out.getvalue().encode("utf-8"))
    print(f"{OUTPUT.relative_to(ROOT)}: {OUTPUT.stat().st_size:,} bytes", file=sys.stderr)

    # Golden sample for the Kotlin port, tagged with the exported (rounded) weights.
    exported = AveragedPerceptron()
    exported.classes = model.classes
    # Integer weights, summed exactly, as the Kotlin port sums them.
    exported.weights = {f: {l: round(v * SCALE) for l, v in w.items()} for f, w in model.weights.items()}
    with open(GOLDEN, "w", encoding="utf-8") as golden:
        for words, _ in test[:200]:
            golden.write(" ".join(words).replace("\t", " ") + "\t" + " ".join(tag_sentence(exported, tags, words)) + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
