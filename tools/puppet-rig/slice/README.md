# Creature parts slicer and starter rigs

These scripts turn the node-creature **parts sheets** in `docs/swarm-terrarium/characters/<NNN_role>/` into
starter `aive-puppet-rig` v1 rigs (`docs/architecture/PUPPET_RIG_FORMAT.md`). The rigs are a starting point.
Open a rig in the editor (`tools/puppet-rig/`) to hand-tune it.

Requirements: Python 3 with Pillow, numpy and scipy. `opencv-python-headless` is optional and only used for eye
template matching against references.

```sh
python3 tools/puppet-rig/slice/slice_parts.py        # stage 1: slice + classify (2-3 min)
python3 tools/puppet-rig/slice/build_rigs.py         # stage 2: rigs, atlases and compare renders
for f in shared/src/commonMain/composeResources/files/rigs/*.rig.json; do node tools/puppet-rig/validate-rig.mjs "$f"; done
```

Both stages take `--only <substring>` (directory, sheet name or rig slug). `slice_parts.py --list` shows how every PNG was categorised.

## Stage 1: `slice_parts.py`

- **Detection.** A parts sheet is an RGBA PNG with at least 5 large alpha components, where no single component holds more than 75% of the pixels.
  Composed images (`node_*`, `Sep 25` sheets, `approved_character*`, `character*`, `Matplotlib*`) are skipped and used as references instead.
- **Slicing.** The script labels the alpha > 16 components. It merges small satellites (antialiasing-split bulbs, droplets) that lie within 7 px of a large part into that part, and drops specks.
- **Classification.** Each part gets a class:
  - soma: the part with the largest inscribed circle.
  - eyes: the best-matching pair of disc-shaped parts with a centred dark pupil.
  - lids: thin arcs about one eye wide. An arc that opens downward is an upper lid; one that opens upward is a lower lid.
  - tail: the elongated part with the biggest terminal bulb.
  - beak/prop: small compact pieces.
  - tendrils: everything else, numbered clockwise from 12 o'clock.
- **Base point.** Sheets are exploded views, so each tendril's attachment end is its thin end nearest the soma. For the tail, it is the thin end farthest from the egg.
- **Overrides.** `overrides.json` corrects individual sheets after review of `preview.png`. It maps component indices to classes, and also supports `drop`, `tail_chain` (a segmented tail) and `no_rig`.
- **Output** (generated, git-ignored — re-run to reproduce). `<dir>/rig/parts/<sheet-slug>/`: `<part>.png` (original pixels, 2 px pad), `parts.json` and `preview.png`.

## Stage 2: `build_rigs.py`

- **Hierarchy.** An invisible `body` bone sits at the soma centre. Children:
  - `soma`.
  - tendrils and tail, drawn below the soma and pivoted at their base.
  - eyes, parented to `soma`, drawn above it.
  - lids, parented to their eye.
  - beak and props.
- **Placement.** Each tendril base is pulled onto the soma rim, with a small overlap, and the stalk is pointed outward. The tail hangs from the soma bottom.
  - With a reference (`node_*.png`, else a composed `Sep 25` or `Matplotlib` image), the reference sets the scale (inscribed soma circle), the eyes (template matching) and the limb angles and lengths (alpha outside the soma disc).
  - Without a reference, the script uses a heuristic face-right layout.
- **Motion.** Every state is defined (`Pending`, `Ready`, `Active`, `Gate`, `Blocked`, `Complete`, `Failed`). Motion includes body bob, tendril sway with per-tendril phase, tail wave and blinks (eye `scaleY` plus lid `scaleY` keys). Blocked droops, Failed sags and trembles, and Complete settles with a bounce. All sine frequencies are integers.
- **Output.**
  - `shared/src/commonMain/composeResources/files/rigs/<slug>.rig.json` and `<slug_underscored>_rig_atlas.png`.
  - `<dir>/rig/<slug>.compare.png`: reference | rest | Active ×3 | Blocked | Failed. A small Python renderer draws these with the format's transform math.
  - `rigs-report.json`: which reference each rig used, eye and limb match counts, and the newest sheet per role.
- **Slugs.** Rig slugs are the kebab-case role. 000_generic gives `generic-01`…`generic-06`. Roles with several sheets get `-1`, `-2` suffixes, ordered oldest to newest.
