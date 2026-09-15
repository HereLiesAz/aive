# Deterministic-first memory semantics

Haive should spend model inference where interpretation or abstraction is genuinely required, not where structure can be derived cheaply and reproducibly.

The governing rule is:

> Models for abstraction; algorithms for structure.

This document defines how deterministic semantic processing coexists with the epoch-8 Memory Clerk models.

## Evidence layers

Haive deliberately distinguishes three kinds of association evidence.

### 1. Bookkeeping facts

`MemoryProgrammaticAssociator` creates relationships that are mechanically provable from stored memory data:

- exact semantic cue identity
- exact machine-readable identifiers
- direct provenance
- orchestration/session/workflow/task scope
- chronological adjacency
- temporal buckets
- condensation overlap

These edges use `deterministic=true` because they are directly derived from stored bookkeeping facts.

### 2. Deterministic lexical/structural heuristics

`MemoryLexicalAssociator` creates reproducible semantic evidence from lexical and structural features:

- normalized code entities
- normalized code actions
- noun/verb lemmas
- exact lexical senses when a richer `MemoryLexicon` supplies them
- verb semantic classes
- weak subject/verb and verb/object signatures
- weak subject/verb/object signatures

These edges carry both `deterministic=true` and `heuristic=true`. The first means the same input produces the same output. The second prevents downstream consumers from confusing reproducibility with certainty.

The built-in `RuleBasedMemoryLexicon` is deliberately conservative and dependency-free. It provides bounded technical-action normalization plus light noun morphology. `MemoryLexicon` is the extension seam for a compact WordNet/VerbNet-derived implementation inspired by Convey's deterministic noun and verb classifiers.

Until that richer lexical resource is shipped and benchmarked, Haive must not claim WordNet-level sense resolution from the default rule-based implementation.

### 3. Embedding similarity

Specialist 08 is the semantic fallback for relationships that cannot be explained confidently by bookkeeping or decisive lexical structure.

`EmbeddingAssociationLinkerMicroAgent`:

- creates only neutral `SimilarTo` edges
- never infers contradiction, truth, falsity, or resolution
- removes decisively explained pairs from the embedding workload
- does **not** suppress embeddings merely because two memories share a generic verb/action or short acronym
- caches embeddings by model/artifact/text so repeated bounded neighborhoods do not re-run inference unnecessarily

Only nodes participating in unexplained pairs are embedded. A packet whose candidate pairs are all decisively explained can bypass Specialist 08 completely.

The skipped deterministic relationship is later materialized by `MemoryLexicalAssociator` as `AssociatedWith`; it is intentionally not converted into `SimilarTo`. This distinction prevents broad lexical association from accidentally becoming a condensation signal.

## Noun and verb tagging

Epoch-8 specialists 03 and 04 remain the fallback models:

- Specialist 03: Noun/Entity Indexer
- Specialist 04: Verb/Action Indexer

`ProgrammaticSemanticTaggerMicroAgent` wraps those specialists with a conservative fast path.

A model call is bypassed only when every item in the bounded Tags packet:

1. is a retained `Context` node,
2. is strongly technical/code-like, and
3. has at least one deterministic candidate for the requested role.

Natural-language prose, proper names, ambiguous morphology, and mixed/partial packets continue to the trained specialist.

Programmatic nodes record:

- `semanticSource=programmatic-code`
- `modelBypassed=true`
- `fallbackModel=<epoch-8 model id>`

`AgentMemoryLayer.createWithMicroAgents(..., programmaticSemanticFastPaths = false)` disables the fast path for direct A/B comparison with the trained models.

## Convey-inspired structural semantics

The lexical analyzer adopts the useful shape of Convey's deterministic semantic code without pretending that its lightweight built-in parser is a full dependency parser.

For a sentence with a confidently known verb, Haive may derive weak structural signatures such as:

- `(subject, verb)`
- `(verb, object)`
- `(subject, verb, object)`

These are association cues only. They do not assert that the sentence has been parsed perfectly, that one memory is true, or that one memory supersedes another.

A future WordNet/VerbNet-backed `MemoryLexicon` may provide stable synset/sense IDs and richer verb classes. Those features can strengthen lexical association without changing the graph contract.

## GRIP

`LexicalMemoryTool` decorates the existing `GraphMemoryTool`.

Before free-text or tag-addressed GRIP, it adds normalized lexical cues using the same deterministic analyzer used by consolidation. For example, a query containing `writes` can additionally address a stored `write` action cue.

The underlying graph traversal and ranking implementation remains unchanged. The public `MemoryRecallBundle` preserves the caller's original query rather than leaking the internal expansion.

## Epoch-8 model release

`MemoryEpoch8ModelCatalog` registers the published `memory-layer-epoch8` release and its INT8 specialist archives.

The catalog stores role, release tag, archive asset name, archive SHA-256, logical runtime artifact id, and quantization.

The SHA-256 verifies the downloaded release archive. ONNX Runtime does **not** consume the archive directly. A platform installer must:

1. download the selected release bundle,
2. verify the archive SHA-256,
3. extract the bundle,
4. locate its ONNX payload,
5. bind the catalog's logical `runtimeArtifactId` to that local file through the platform artifact resolver.

This keeps release packaging separate from the local runtime/session layer.

## Retirement criteria

A trained specialist should be removed only after the deterministic path is measured against the epoch-8 baseline.

For NounTagger and VerbTagger, collect at minimum:

- deterministic coverage rate
- precision/recall against epoch-8 evaluation data
- false-positive tag rate
- missed proper-name/entity rate
- mixed prose/code performance
- model bypass percentage
- latency and energy reduction by target platform

For AssociationLinker, collect:

- percentage of useful links explained by bookkeeping evidence
- percentage explained by lexical/structural evidence
- percentage of candidate pairs removed before embedding
- embedding cache hit rate
- residual nodes/pairs requiring embedding inference
- retrieval quality with and without each evidence family

Only then should Specialist 03, 04, or 08 move from fallback/baseline to optional or retired status.
