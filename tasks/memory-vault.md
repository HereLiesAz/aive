# Memory Vault: Obsidian-style viewing of the memory bank

Decisions (keeper): all three views; no node cap (a level-of-detail threshold on the map if needed); notes are viewable AND the keeper may write custom memories, handled like every other memory (banked, consolidated, recallable); a "Vault" part inside the Memory destination for now.

## PR A: the vault model (pure) and custom memories
- [ ] `MemoryVault` (memory package): `MemorySnapshot` -> notes (unique title, text, kind, salience, provenance, outgoing links, backlinks, pair summaries on their link). Engine bookkeeping (Outline, PairSummary nodes; Recalled, Outlines edges) is not a note.
- [ ] `MemoryVault.toMarkdown` / `export`: one `.md` per note with `[[links]]` and front matter; stable file names; no overwrites.
- [ ] `MemoryLayerController.bankKeeperMemory(text, title)`: `enqueueBank` as a keeper note, so it is ordinary memory afterwards.
- [ ] Tests: titles unique, backlinks symmetric, bookkeeping excluded, markdown links resolve, keeper memory is queued.
## PR B: the Vault part (notes + backlinks + write a memory)
## PR C: mind map (force layout, pan/zoom, filter, level of detail by zoom)
## PR D: export as a vault to a chosen folder (reuses ArtifactFolderService)
