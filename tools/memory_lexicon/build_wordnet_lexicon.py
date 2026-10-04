#!/usr/bin/env python3
"""Builds the compact WordNet lexicon the programmatic memory clerks read.

Source: Princeton WordNet 3.1 (the nltk_data `wordnet31` package), WordNet License (see the
LICENSE block written into the output). The output is a gzip'd, line-oriented text file at
shared/src/commonMain/composeResources/files/aive-wordnet-v1.txt.gz, loaded by
`WordNetLexicon` (shared/.../memory/WordNetLexicon.kt).

Format (UTF-8, tab-separated; one section header per block):

  #aive-wordnet v1
  #license            ...license lines prefixed "# "
  #lexnames           one lexicographer file name per line (index = line order)
  #synsets            pos  lexname  lemmas(|)  hypernyms(,)  derivations(,)  domains(,)
                      pos is n or v; hypernyms include instance hypernyms; derivations are
                      synsets of the other part of speech a lemma is derivationally related to;
                      domains are topic-domain synsets. Synset references are line indexes
                      within this section.
  #index              lemma  pos  synsets(,)      senses in WordNet's frequency order (n, v)
  #pos                lemma  poses               part-of-speech membership for adj (a) and adv (r)
  #exceptions         pos  inflected  base(|)     irregular forms (noun.exc, verb.exc, adj.exc)

Lemmas are lowercase with spaces for WordNet's underscores. Re-run after changing the format:

  python3 tools/memory_lexicon/build_wordnet_lexicon.py
"""
from __future__ import annotations

import gzip
import io
import os
import sys
import urllib.request
import zipfile
from pathlib import Path

SOURCE_URL = "https://raw.githubusercontent.com/nltk/nltk_data/gh-pages/packages/corpora/wordnet31.zip"
ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / "shared/src/commonMain/composeResources/files/aive-wordnet-v1.txt.gz"
CACHE = ROOT / "build/wordnet/wordnet31.zip"


def fetch() -> zipfile.ZipFile:
    if not CACHE.exists():
        CACHE.parent.mkdir(parents=True, exist_ok=True)
        with urllib.request.urlopen(SOURCE_URL) as response:
            CACHE.write_bytes(response.read())
    return zipfile.ZipFile(CACHE)


def read(archive: zipfile.ZipFile, name: str) -> list[str]:
    return archive.read(f"wordnet31/{name}").decode("utf-8").splitlines()


def lemma(word: str) -> str:
    # data files mark adjective position as word(a)/word(p)/word(ip)
    if word.endswith(")") and "(" in word:
        word = word[: word.index("(")]
    return word.replace("_", " ").lower()


def parse_data(lines: list[str], pos: str) -> dict[int, dict]:
    synsets: dict[int, dict] = {}
    for line in lines:
        if line.startswith("  "):
            continue
        body = line.split(" | ", 1)[0].split()
        offset = int(body[0])
        lexname = int(body[1])
        word_count = int(body[3], 16)
        words = [lemma(body[4 + 2 * i]) for i in range(word_count)]
        cursor = 4 + 2 * word_count
        pointer_count = int(body[cursor])
        cursor += 1
        pointers = []
        for _ in range(pointer_count):
            symbol, target, target_pos, _source_target = body[cursor:cursor + 4]
            cursor += 4
            pointers.append((symbol, int(target), "n" if target_pos == "n" else target_pos))
        synsets[offset] = {"pos": pos, "lexname": lexname, "words": list(dict.fromkeys(words)), "pointers": pointers}
    return synsets


def main() -> int:
    archive = fetch()
    lexnames = [line.split()[1] for line in read(archive, "lexnames")]
    nouns = parse_data(read(archive, "data.noun"), "n")
    verbs = parse_data(read(archive, "data.verb"), "v")

    keys = [("n", offset) for offset in sorted(nouns)] + [("v", offset) for offset in sorted(verbs)]
    index_of = {key: i for i, key in enumerate(keys)}

    def ref(pos: str, offset: int):
        return index_of.get((pos, offset))

    out = io.StringIO()
    out.write("#aive-wordnet v1\n#license\n")
    for line in read(archive, "LICENSE"):
        out.write(f"# {line}\n")
    out.write("#lexnames\n")
    for name in lexnames:
        out.write(f"{name}\n")
    out.write("#synsets\n")
    for pos, offset in keys:
        synset = (nouns if pos == "n" else verbs)[offset]
        hypernyms, derivations, domains = [], [], []
        for symbol, target, target_pos in synset["pointers"]:
            target_ref = ref(target_pos, target)
            if target_ref is None:
                continue
            if symbol in ("@", "@i"):
                hypernyms.append(target_ref)
            elif symbol == "+" and target_pos != pos:
                derivations.append(target_ref)
            elif symbol == ";c":
                domains.append(target_ref)
        out.write(
            "\t".join(
                [
                    pos,
                    str(synset["lexname"]),
                    "|".join(synset["words"]),
                    ",".join(map(str, dict.fromkeys(hypernyms))),
                    ",".join(map(str, dict.fromkeys(derivations))),
                    ",".join(map(str, dict.fromkeys(domains))),
                ]
            )
            + "\n"
        )

    out.write("#index\n")
    for pos in ("n", "v"):
        for line in read(archive, f"index.{'noun' if pos == 'n' else 'verb'}"):
            if line.startswith("  "):
                continue
            parts = line.split()
            synset_count = int(parts[2])
            offsets = [int(value) for value in parts[-synset_count:]]
            refs = [ref(pos, offset) for offset in offsets]
            out.write(f"{lemma(parts[0])}\t{pos}\t{','.join(str(r) for r in refs if r is not None)}\n")

    out.write("#pos\n")
    membership: dict[str, set[str]] = {}
    for pos, name in (("a", "adj"), ("r", "adv")):
        for line in read(archive, f"index.{name}"):
            if line.startswith("  "):
                continue
            membership.setdefault(lemma(line.split()[0]), set()).add(pos)
    for word in sorted(membership):
        out.write(f"{word}\t{''.join(sorted(membership[word]))}\n")

    out.write("#exceptions\n")
    for pos, name in (("n", "noun"), ("v", "verb"), ("a", "adj")):
        for line in read(archive, f"{name}.exc"):
            parts = line.split()
            if len(parts) >= 2:
                out.write(f"{pos}\t{lemma(parts[0])}\t{'|'.join(lemma(p) for p in parts[1:])}\n")

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    # mtime=0 keeps the output byte-identical across runs.
    with open(OUTPUT, "wb") as raw, gzip.GzipFile(fileobj=raw, mode="wb", compresslevel=9, mtime=0, filename="") as gz:
        gz.write(out.getvalue().encode("utf-8"))
    print(f"{OUTPUT.relative_to(ROOT)}: {len(keys)} synsets, {os.path.getsize(OUTPUT):,} bytes")
    return 0


if __name__ == "__main__":
    sys.exit(main())
