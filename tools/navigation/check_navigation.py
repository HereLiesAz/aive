"""Fail when a declared destination cannot be reached (docs/Navigation-Map.md).

Reads docs/nav-graph.json (the machine-readable navigation map) and the Kotlin enums that declare places:
  1. every edge names nodes that exist;
  2. every node is reachable from the start node over PRESENT edges, except those the graph lists under
     meta.exempt (each with its reason);
  3. every entry of ControlRoomDestination and KeeperSection in the source is named by some node's symbols, so a
     new place cannot be added without joining the map.
Run:  python3 tools/navigation/check_navigation.py     (exit 1 with the list of problems)
"""
import collections
import json
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
GRAPH = os.path.join(ROOT, "docs", "nav-graph.json")
SRC = os.path.join(ROOT, "shared", "src", "commonMain", "kotlin", "com", "hereliesaz", "geministrator")
ENUMS = {"ControlRoomDestination": "ControlRoomUi.kt", "KeeperSection": "KeeperRoom.kt"}


def unreachable(graph):
    nodes = {n["id"] for n in graph["nodes"]}
    adj = collections.defaultdict(list)
    for e in graph["edges"]:
        if e["status"] == "present":
            adj[e["from"]].append(e["to"])
    seen = {graph["start"]}
    queue = collections.deque([graph["start"]])
    while queue:
        u = queue.popleft()
        for v in adj[u]:
            if v not in seen:
                seen.add(v)
                queue.append(v)
    exempt = set(graph.get("meta", {}).get("exempt", {}))
    return sorted(nodes - seen - exempt)


def dangling_edges(graph):
    nodes = {n["id"] for n in graph["nodes"]}
    return sorted({f"{e['from']} -> {e['to']}" for e in graph["edges"] if e["from"] not in nodes or e["to"] not in nodes})


def enum_entries(text, enum):
    """Entry names of `enum class <enum>`: capitalised identifiers at four spaces' indent before the first `;`."""
    lines = text.splitlines()
    start = next((i for i, line in enumerate(lines) if re.search(r"enum class " + enum + r"\b", line)), None)
    if start is None:
        return []
    entries = []
    for line in lines[start + 1:]:
        if re.match(r"\s*;\s*$", line):
            break
        m = re.match(r"\s{4}([A-Z]\w*)\s*(\(|,|$)", line)
        if m:
            entries.append(m.group(1))
    return entries


def undeclared_places(graph, read=lambda path: open(path, encoding="utf-8").read()):
    symbols = {s for n in graph["nodes"] for s in n["symbols"]}
    missing = []
    for enum, name in ENUMS.items():
        for entry in enum_entries(read(os.path.join(SRC, name)), enum):
            if f"{enum}.{entry}" not in symbols:
                missing.append(f"{enum}.{entry}")
    return missing


def problems(graph, read=lambda path: open(path, encoding="utf-8").read()):
    found = []
    found += [f"edge to or from an unknown node: {e}" for e in dangling_edges(graph)]
    found += [f"not reachable from {graph['start']}: {n}" for n in unreachable(graph)]
    found += [f"place not in the map: {p}" for p in undeclared_places(graph, read)]
    return found


def main():
    with open(GRAPH, encoding="utf-8") as f:
        graph = json.load(f)
    found = problems(graph)
    for line in found:
        print(line)
    if found:
        print(f"{len(found)} navigation problem(s); fix the code or docs/nav-graph.json and docs/Navigation-Map.md")
        return 1
    print(f"navigation map ok: {len(graph['nodes'])} nodes reachable from {graph['start']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
