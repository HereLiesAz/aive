# Mobile vector stores

On-device similarity search for the memory layer and the workflow agents. Pure Kotlin in `shared/src/commonMain/kotlin/com/hereliesaz/geministrator/vector/`, so Android, desktop, JS and Wasm run the same code with no native libraries.

## Indexes

All implement `VectorIndex` (`upsert`, `remove`, `search(query, k, filter)`), store L2-normalized vectors and score by cosine similarity.

| Index | Storage per 384-dim vector | Search | Use |
|---|---|---|---|
| `FlatVectorIndex` | 1.5 KB (float32) | exact, linear | reference; small sets (roles, tools, one bank's recent memories) |
| `QuantizedVectorIndex` | 388 B (int8 + scale) + optional floats for re-rank | linear on bytes, exact re-rank | medium banks where memory matters |
| `BinaryVectorIndex` | 48 B (1 bit/dim) + optional floats | Hamming shortlist, exact re-rank | large banks; cheap first cut |
| `HnswVectorIndex` | floats + graph links | approximate, roughly logarithmic | large banks and repository chunks |

Indexes live in memory and are rebuilt from stored vectors. The stored form is the source of truth: `VectorCodec` writes a tagged little-endian BLOB (float32 or int8) into SQLite rows beside the record it describes. HNSW is seeded, so the same vectors inserted in the same order give the same graph and the same results; removal marks nodes deleted and `compact()` rebuilds without them.

`VectorIndexTest` asserts recall@10 against the exact index on clustered data: HNSW ≥ 0.95, int8 ≥ 0.98, binary ≥ 0.80 (with re-rank).

## Embedders

Nothing produced embeddings before this work: the ONNX embedding runtimes exist (`MemoryEmbeddingInferenceRuntime`, desktop/Android/browser) but no model adapter was wired. Two embedders feed the indexes:

- **Built-in (always available).** Pure Kotlin, deterministic, no download: hashed word and character n-gram features, expanded through the shipped WordNet synonym groups, projected to a fixed dimension. Closer to concept-aware keyword matching than to a neural model, but it works on every platform from first launch.
- **MiniLM (optional).** A sentence-embedding ONNX model (int8, about 23 MB) installed from Settings like the other local models, with SHA verification, run through the existing ONNX runtimes.

Each embedder is its own vector space: a vector records the embedder id and version it came from, and indexes never mix spaces. Installing or removing MiniLM re-embeds lazily in the background; the built-in space stays as the fallback.

## Where they plug in

| Use | Seam | Change |
|---|---|---|
| Memory recall | `GraphMemoryTool.grip` / `fusedOrder` (`memory/MemoryTool.kt`) | Union semantic nearest neighbours into the seed set and add a semantic signal to the reciprocal-rank fusion beside relevance, scope, salience, confidence, recency and keywords. |
| Memory associations | `MemoryConsolidator.semanticNeighbours` → `relatedNeighborhood` (`memory/MemoryConsolidation.kt`) | Done: with an embedder (on by default via `AgentMemoryLayer.create`), the association packet's neighbourhood adds memories from anywhere in the bank at cosine ≥ 0.45, fused with shared-word overlap by reciprocal rank; same-kind still first. The index is persistent per consolidator (each node embedded once), HNSW past 5,000 memories. |
| Agent retrieval | `ProviderBackedManagedSessionGateway` (memory blocks are packed there today) | Retrieve repository-file and artifact chunks by similarity to the task and add them as `ContextEvidence` for the Context Packer. Needs a repository chunking/reading step that does not exist yet. |
| Agent routing | `routeAgent` / `routeTool` (`orchestration/LocalOrchestrationUtilities.kt`) | Add task text and role/tool descriptions to the routing inputs; similarity breaks ties among candidates that already satisfy the hard capability filter, never overriding it. |

## Storage

Memory vectors live in each memory bank's SQLite database (new table keyed by node id, embedder id and version). Repository and artifact chunk vectors live in the workflow database. On the web, the memory banks already use SQLite in OPFS through the worker driver; the workflow store stays on Settings for 1.0, so agent retrieval on the web keeps its index in memory for the session.

## Order of work

1. Indexes and codec.
2. Built-in embedder; vector table in memory banks; semantic recall signal.
3. ~~Whole-bank neighbours for associations.~~ Done.
4. MiniLM adapter and Settings install.
5. Routing tie-breaks.
6. Repository/artifact chunking and retrieval.
