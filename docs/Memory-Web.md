# The Memory Web

The whole memory layer, drawn as a mind map and readable as linked notes, the way Obsidian reads a folder of markdown. It is the **Memory Web** pill beside the Setup / Swarm switch on the Memory destination. It reads the selected swarm's bank (its own and what it inherits); nothing in it is a second copy.

## What is in it

Everything the layer holds, none held back:

| Colour family | What it is |
| --- | --- |
| Source (grey) | Episodes and their sections: where memories came from |
| Context (blue) | Retained contexts |
| Keyword (cyan) | Noun and verb tags |
| Phrase (teal) | Phrases |
| Summary (green) | Summaries, the summary tree's outlines and the pair summaries on links |
| Category (gold) | Category and subject tags |
| Generalization (orange) | A memory condensed from others (what it was `CondensedFrom` is its links) |
| Contrast (red) | Frames, variants and deliberations |
| Yours (magenta) | Memories the keeper wrote here, and the episodes they came from |
| Habit (purple) | Habits and sequences the swarm's runs formed |
| Procedure (violet) | Procedures |

## How to read the map

- **Size** of a node is its weight: salience, plus a little for each time it was recalled; for a habit, how often it was used.
- **Width** of a line is the strength of the link (its edge weight). A recall is not a line: it makes the memory bigger.
- **Colour** is the family above.
- Pan by dragging; zoom by pinch, the mouse wheel or the buttons; **Fit** puts it all back. Tap a node to open its note.
- Level of detail follows the zoom: far out, a big web names only its weighty nodes (and never two names on top of each other) and draws only its strong links; closer in, everything. There is no cap on how many memories it shows.
- The filter dims what does not match.

## Notes

The Notes view lists every memory (heaviest first), and a note reads like an Obsidian note: its words, where it came from, **Links** (what it points to, with the link's pair summary when it has one) and **Backlinks** (what points to it). A link is a tap.

## Writing a memory

The keeper may write a memory of their own. It is banked into the selected swarm's bank through `MemoryLayerController.bankKeeperMemory`, the same queue and consolidation as an agent's deliberate note, so afterwards it is an ordinary memory: recalled, linked and condensed like any other. It appears in the web (in the keeper's colour) once consolidated. Memories stay add-only: nothing is edited or deleted here.

## Code

`memory/MemoryWeb.kt` (the model: graph, notes, markdown, files), `memory/MemoryWebLayout.kt` (a deterministic force layout with grid-based repulsion), `MemoryWebView.kt` (the screen), `MemoryLayerController.webSource` and `bankKeeperMemory`.

The model also writes each note as a markdown file with front matter and `[[links]]` (`MemoryWeb.files`), one folder per family, for opening in Obsidian; a button for that is not in the screen yet.
