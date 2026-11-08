#!/usr/bin/env python3
"""Builds the refinement-level training corpus from an exported ladder dataset.

Input: JSONL of `aive.ladder.record.v1` records (LadderDatasetCapture.exportJsonl()).
Output, per ladder level N: `refinement-level-N.jsonl` and `refinement-level-N.config.json`, in the
same row shape as tools/orchestration_training (`id`, `input`, `expected`, `split`, `tags`), plus
`rejected.jsonl` (levels a gate turned back, never trained on) and an immutable `manifest.json`.

A row is a level's answer that parsed as the level schema, validated against the level above, and
was approved by its gate. Its input carries the level above's approved answer (`coarser`), so a level
model learns to build on the structure it is given, as it will at inference: on a coarser model's
prediction, not on a gold one, because the data are real runs.

The labels are gate verdicts from a reviewing agent, not an exact checker. The manifest says so, and
the anti-shortcut baselines below exist because the retired orchestration specialists were trained
on labels that equalled a rule and added nothing.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from collections import Counter
from pathlib import Path

RECORD_SCHEMA = "aive.ladder.record.v1"
LEVEL_SCHEMA = "aive.ladder.level.v1"
MANIFEST_SCHEMA = "aive.refinement.manifest.v1"
FOUNDATION_MODEL = "Qwen/Qwen2.5-0.5B-Instruct"
SPLITS = ("train", "validation", "test")
SYSTEM_PROMPT = (
    "You are one level of a coarse-to-fine refinement ladder. The input is JSON with the level, the "
    "number of levels, the objective as given, and the approved answer of the coarser level (or null at "
    "level 1). Answer with one aive.ladder.level.v1 JSON object that refines every decision of the "
    "coarser level and nothing else."
)
GATES = {
    "metric": "json_exact",
    "min_rows": 200,
    "min_test_score": 0.6,
    "max_shortcut_rate": 0.2,
}


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def canonical(value) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def split_of(ladder: str) -> str:
    """By ladder, so the levels of one ladder never straddle splits (70/15/15)."""
    bucket = int(sha256(ladder.encode())[:8], 16) % 100
    return "train" if bucket < 70 else "validation" if bucket < 85 else "test"


def read_records(path: Path) -> list[dict]:
    records = []
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip():
            continue
        try:
            record = json.loads(line)
        except json.JSONDecodeError as failure:
            raise SystemExit(f"{path}:{number}: not JSON ({failure.msg})")
        if record.get("schema") != RECORD_SCHEMA:
            raise SystemExit(f"{path}:{number}: schema is {record.get('schema')!r}, expected {RECORD_SCHEMA}")
        records.append(record)
    return records


def parse_level(text: str | None, level: int, total: int):
    """The level document in `text`, or None when it is not a sound level `level` of `total`."""
    if not text:
        return None
    try:
        document = json.loads(text)
    except json.JSONDecodeError:
        return None
    if not isinstance(document, dict):
        return None
    if document.get("schema") != LEVEL_SCHEMA or document.get("level") != level or document.get("of") != total:
        return None
    return document


def build(records: list[dict]):
    """Returns (rows by level number, rejected records, ladders seen)."""
    outcomes = {r["ladder"]: r.get("verifierVerdict") for r in records if r.get("kind") == "outcome"}
    by_ladder: dict[str, dict[int, dict]] = {}
    for record in records:
        if record.get("kind") == "level" and record.get("level") and record.get("total"):
            # The latest attempt of a level wins.
            held = by_ladder.setdefault(record["ladder"], {}).get(record["level"])
            if held is None or record.get("attempt", 1) >= held.get("attempt", 1):
                by_ladder[record["ladder"]][record["level"]] = record

    rows: dict[int, list[dict]] = {}
    rejected: list[dict] = []
    for ladder, levels in sorted(by_ladder.items()):
        for level, record in sorted(levels.items()):
            if record.get("gateVerdict") in ("reject", "revise"):
                rejected.append(record)
                continue
            if record.get("gateVerdict") != "approve" or not record.get("valid"):
                continue
            total = record["total"]
            answer = parse_level(record.get("artifact"), level, total)
            if answer is None:
                continue
            coarser = None
            if level > 1:
                above = levels.get(level - 1)
                if above is None or above.get("gateVerdict") != "approve" or not above.get("valid"):
                    continue
                coarser = parse_level(above.get("artifact"), level - 1, total)
                if coarser is None:
                    continue
            rows.setdefault(level, []).append(
                {
                    "id": record["id"],
                    "input": canonical(
                        {
                            "level": level,
                            "of": total,
                            "objective": record.get("objective") or "",
                            "coarser": coarser,
                        }
                    ),
                    "expected": canonical(answer),
                    "split": split_of(ladder),
                    "tags": [
                        f"level:{level}",
                        f"of:{total}",
                        f"outcome:{outcomes.get(ladder) or 'unknown'}",
                    ],
                    "_coarser": coarser,
                }
            )
    return rows, rejected, set(by_ladder)


def dedupe(rows: list[dict]) -> list[dict]:
    """One row per input: a duplicate input could otherwise sit in two splits."""
    seen, kept = set(), []
    for row in rows:
        if row["input"] not in seen:
            seen.add(row["input"])
            kept.append(row)
    return kept


def shortcut_rates(rows: list[dict]) -> dict:
    """Exact-match rate of answers a model could give without reading the task."""
    test = [r for r in rows if r["split"] == "test"] or rows
    if not test:
        return {"copy": 0.0, "empty": 0.0, "constant": 0.0}
    train = [r["expected"] for r in rows if r["split"] == "train"] or [r["expected"] for r in rows]
    most_common = Counter(train).most_common(1)[0][0]
    copy = sum(1 for r in test if r["_coarser"] is not None and canonical(r["_coarser"]) == r["expected"])
    return {
        "copy": copy / len(test),
        "empty": 0.0,
        "constant": sum(1 for r in test if r["expected"] == most_common) / len(test),
    }


def write(out: Path, rows: dict[int, list[dict]], rejected: list[dict], source: Path, ladders: set[str]) -> dict:
    out.mkdir(parents=True, exist_ok=True)
    manifest = {
        "schema": MANIFEST_SCHEMA,
        "generator": "tools/refinement_training/build_corpus.py",
        "source_sha256": sha256(source.read_bytes()),
        "label_source": "gate-agent",
        "label_note": "Labels are verdicts of a reviewing agent, not an exact checker.",
        "ladders": len(ladders),
        "adversarial_split": "none: adversarial rows cannot be generated from runs and must be hand-written",
        "levels": {},
        "rejected_rows": len(rejected),
    }
    rejected_path = out / "rejected.jsonl"
    rejected_text = "".join(json.dumps(r, ensure_ascii=False, sort_keys=True) + "\n" for r in rejected)
    rejected_path.write_text(rejected_text, encoding="utf-8")
    manifest["rejected_sha256"] = sha256(rejected_text.encode())

    for level, level_rows in sorted(rows.items()):
        level_rows = dedupe(level_rows)
        slug = f"refinement-level-{level}"
        body = "".join(
            json.dumps({k: v for k, v in row.items() if not k.startswith("_")}, ensure_ascii=False, sort_keys=True)
            + "\n"
            for row in level_rows
        )
        (out / f"{slug}.jsonl").write_text(body, encoding="utf-8")
        config = {
            "specialist_id": f"refinement:level-{level}",
            "system_prompt": SYSTEM_PROMPT,
            "foundation_model_id": FOUNDATION_MODEL,
            "gates": GATES,
        }
        config_text = json.dumps(config, ensure_ascii=False, sort_keys=True, indent=2) + "\n"
        (out / f"{slug}.config.json").write_text(config_text, encoding="utf-8")
        counts = Counter(row["split"] for row in level_rows)
        rates = shortcut_rates(level_rows)
        manifest["levels"][str(level)] = {
            "rows": len(level_rows),
            "splits": {name: counts.get(name, 0) for name in SPLITS},
            "corpus_sha256": sha256(body.encode()),
            "config_sha256": sha256(config_text.encode()),
            "shortcut_rates": rates,
            "ready": len(level_rows) >= GATES["min_rows"]
            and counts.get("test", 0) > 0
            and max(rates["copy"], rates["constant"]) <= GATES["max_shortcut_rate"],
        }
    (out / "manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, sort_keys=True, indent=2) + "\n", encoding="utf-8"
    )
    return manifest


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--input", required=True, type=Path, help="exported ladder dataset (JSONL)")
    parser.add_argument("--out", required=True, type=Path, help="output directory")
    args = parser.parse_args(argv)
    records = read_records(args.input)
    rows, rejected, ladders = build(records)
    if not rows:
        print("no approved, valid level rows in the input; nothing written", file=sys.stderr)
        return 1
    manifest = write(args.out, rows, rejected, args.input, ladders)
    for level, info in manifest["levels"].items():
        state = "ready" if info["ready"] else "NOT ready (see README, Gates)"
        print(f"level {level}: {info['rows']} rows {info['splits']} {state}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
