import json
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import check_navigation as nav


def tiny():
    return {
        "start": "a",
        "nodes": [{"id": "a", "symbols": []}, {"id": "b", "symbols": []}, {"id": "c", "symbols": []}],
        "edges": [{"from": "a", "to": "b", "status": "present"}, {"from": "b", "to": "c", "status": "missing"}],
        "meta": {},
    }


class CheckNavigationTest(unittest.TestCase):
    def test_the_checked_in_map_is_clean(self):
        with open(nav.GRAPH, encoding="utf-8") as f:
            self.assertEqual([], nav.problems(json.load(f)))

    def test_a_node_reached_only_by_a_proposed_edge_is_unreachable(self):
        self.assertEqual(["c"], nav.unreachable(tiny()))

    def test_an_exempt_node_is_not_reported(self):
        g = tiny()
        g["meta"]["exempt"] = {"c": "debug only"}
        self.assertEqual([], nav.unreachable(g))

    def test_an_edge_to_a_missing_node_is_reported(self):
        g = tiny()
        g["edges"].append({"from": "a", "to": "zz", "status": "present"})
        self.assertEqual(["a -> zz"], nav.dangling_edges(g))

    def test_enum_entries_are_read_up_to_the_semicolon(self):
        text = "enum class Foo(val x: Int) {\n    One(1),\n    Two(2),\n    ;\n    val y get() = 1\n    Three(3)\n}\n"
        self.assertEqual(["One", "Two"], nav.enum_entries(text, "Foo"))

    def test_a_new_place_missing_from_the_map_is_reported(self):
        g = tiny()
        src = "enum class ControlRoomDestination(val label: String) {\n    Overview(\"x\"),\n    Brand(\"y\"),\n    ;\n}\n"
        g["nodes"][0]["symbols"] = ["ControlRoomDestination.Overview"]
        self.assertEqual(["ControlRoomDestination.Brand"], [m for m in nav.undeclared_places(g, lambda p: src if p.endswith("ControlRoomUi.kt") else "")])


if __name__ == "__main__":
    unittest.main()
