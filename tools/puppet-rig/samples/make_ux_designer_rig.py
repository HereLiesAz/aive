#!/usr/bin/env python3
"""Transcribes the hand-coded UX Designer rig (UxDesignerSpritePuppetSurface.kt + MascotPuppetRig.pose)
into an aive-puppet-rig v1 document. Atlas rects and placements exist only in Kotlin code, so they
are copied here by hand. Motion is an approximation of the procedural sin() rig (see README)."""
import json, math, sys

S = 200.0  # artboard; the Kotlin surface lays parts out as fractions of a square box
TAU = 2 * math.pi

def part(pid, rect, fx, fy, sw, sh, pivot=(0.5, 0.5), rot=0.0, z=0, parent=None, parent_at=(0, 0)):
    w, h = sw * S, sh * S
    px, py = fx * S + pivot[0] * w, fy * S + pivot[1] * h
    return {"id": pid, "parent": parent, "z": z,
            "rect": dict(zip("xywh", rect)), "size": {"w": round(w, 3), "h": round(h, 3)},
            "pivot": {"x": pivot[0], "y": pivot[1]},
            "rest": {"x": round(px - parent_at[0], 3), "y": round(py - parent_at[1], 3), "rotation": rot,
                     "scaleX": 1, "scaleY": 1, "alpha": 1}}

body_at = (0.32 * S + 0.185 * S, 0.31 * S + 0.185 * S)  # soma centre
parts = [{"id": "body", "parent": None, "z": 0, "pivot": {"x": 0.5, "y": 0.5},
          "rest": {"x": body_at[0], "y": body_at[1], "rotation": 0, "scaleX": 1, "scaleY": 1, "alpha": 1}}]
tendrils = [((0, 64, 48, 96), .20, .17, .19, .40, -55), ((48, 64, 48, 96), .58, .16, .18, .39, 50),
            ((96, 64, 48, 96), .17, .48, .18, .40, -120), ((144, 64, 48, 96), .64, .46, .18, .40, 120)]
for i, (r, fx, fy, sw, sh, rot) in enumerate(tendrils):
    parts.append(part(f"tendril.{i}", r, fx, fy, sw, sh, (0.5, 0.88), rot, 0, "body", body_at))
parts.append(part("soma", (0, 0, 64, 64), .32, .31, .37, .37, z=1, parent="body", parent_at=body_at))
for side, fx in (("left", .385), ("right", .505)):
    parts.append(part(f"eye.{side}", (64 if side == "left" else 96, 0, 32, 32), fx, .405, .105, .105, z=2))
for side, fx in (("left", .405), ("right", .525)):
    parts.append(part(f"pupil.{side}", (128 if side == "left" else 160, 0, 32, 32), fx, .425, .07, .07, z=3))
for side, fx in (("left", .382), ("right", .502)):
    parts.append(part(f"lid.upper.{side}", (192, 0, 32, 32), fx, .392, .112, .07, (0.5, 0.0), z=4))
    parts.append(part(f"lid.lower.{side}", (224, 0, 32, 32), fx, .455, .112, .064, (0.5, 1.0), z=4))
parts.append(part("prop.heart", (192, 64, 64, 96), .70, .33, .17, .25, z=5))

ROLE_PHASE = 5 * 0.17  # UxDesigner ordinal * 0.17 rad, as in MascotPuppetRig

def state(kind):
    blocked = kind in ("Blocked", "Failed")
    m = {}
    def put(pid, keys=None, sine=None):
        m[pid] = {"keys": keys or {}, "sine": sine or {}}
    # body = root + head world rotation in MascotPuppetRig
    if blocked:
        put("body", keys={"rotation": [{"t": 0, "v": -10, "ease": "linear"}]})
    else:
        amp = {"Active": 6.7, "Complete": 3.4}.get(kind, 1.8)
        put("body", sine={"rotation": {"amplitude": amp, "frequency": 1, "phase": 0}})
    state_amp = 0.35 if blocked else {"Active": 1.0, "Complete": 0.72}.get(kind, 0.32)
    for i in range(4):
        alt = 1 if i % 2 == 0 else -1
        put(f"tendril.{i}", sine={"rotation": {"amplitude": round(8 * state_amp * alt, 3), "frequency": 1,
                                              "phase": round((i * 0.73 + ROLE_PHASE) / TAU, 4)}})
    for side in ("left", "right"):
        put(f"pupil.{side}", sine={"x": {"amplitude": 2.0, "frequency": 1, "phase": 0}})
        lid = [{"t": 0, "v": 0.64, "ease": "step"}] if blocked else \
              [{"t": 0, "v": 0.5, "ease": "step"}, {"t": 0.92, "v": 0.91, "ease": "step"}]
        put(f"lid.upper.{side}", keys={"scaleY": lid})
        put(f"lid.lower.{side}", keys={"scaleY": lid})
    if blocked:
        put("prop.heart", keys={"rotation": [{"t": 0, "v": 9}], "alpha": [{"t": 0, "v": 0.55}]})
    elif kind == "Complete":
        put("prop.heart", keys={"rotation": [{"t": 0, "v": -8, "ease": "easeOut"}, {"t": .25, "v": -13, "ease": "easeIn"},
                                             {"t": .5, "v": -8, "ease": "easeOut"}, {"t": .75, "v": -13, "ease": "easeIn"}]},
            sine={"rotation": {"amplitude": 3.2, "frequency": 1, "phase": 0}})
    else:
        put("prop.heart", sine={"rotation": {"amplitude": -4.8 if kind == "Active" else 0.6, "frequency": 1, "phase": 0}})
    return {"parts": m}

rig = {"format": "aive-puppet-rig", "version": 1, "role": "ux-designer",
       "canvas": {"width": S, "height": S},
       "atlas": {"image": "ux_designer_rig_atlas.png", "width": 256, "height": 256},
       "parts": parts,
       "attachments": [{"id": "arm.socket.a", "part": "soma", "x": 0.02, "y": 0.62, "kind": "arm-socket"},
                       {"id": "arm.socket.b", "part": "soma", "x": 0.98, "y": 0.62, "kind": "arm-socket"},
                       {"id": "prop.slot", "part": "soma", "x": 0.95, "y": 0.45, "kind": "prop-slot"}],
       "states": {s: state(s) for s in ("Pending", "Ready", "Active", "Gate", "Blocked", "Complete", "Failed")},
       "fallbackState": "Pending"}
json.dump(rig, open(sys.argv[1], "w"), indent=2)
