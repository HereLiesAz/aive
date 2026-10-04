# Deterministic-first memory semantics

The Aive should spend model inference where interpretation or abstraction is genuinely required, not where structure can be derived cheaply and reproducibly.

The governing rule is:

> Models for abstraction; algorithms for structure.

This document defines how deterministic semantic processing coexists with the epoch-8 Memory Clerk models.

## Evidence layers

The Aive deliberately distinguishes three kinds of association evidence.

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

Until that richer lexical resource is shipped and benchmarked, The Aive must not claim WordNet-level sense resolution from the default rule-based implementation.

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

## Programmatic sectioning and salience

The Sectioner (`MemorySectioning.kt`) splits structure first: typed blocks for headings, fenced code, stack traces, diff hunks, log runs, lists, tables, quotes and paragraphs; a heading joins the block after it. Typed blocks stay whole (oversized logs, traces and diffs split only between lines, diffs between hunks; fences never). Prose over 1,200 characters gets TextTiling topic boundaries when long enough to measure, then a recursive split (paragraphs, lines, sentences, clauses, words) that guarantees the limit; small neighbours merge back to at least 160. Sentences never split inside URLs, paths, versions, decimals, dotted identifiers or after common abbreviations.

The Salience clerk (`MemorySalienceFeatures.kt`) drops acknowledgements, symbol-only text and whole sections of build-tool bookkeeping (up-to-date tasks, download progress), and near-duplicates (shingle Jaccard ≥ 0.8; word 3-shingles for prose, character 5-grams for code). Repeated tool-output lines collapse Drain-style to their first and last instance, copied verbatim, with the count in `collapsedRepeatedLines`. User prompts are never dropped. The score is a sum of recorded parts (`salienceFeatures`): source kind, technical text, decision/error/action-item cues, IDF-weighted overlap with the user's prompt, specificity, and repetition. It measures; it never judges whether a section is true.

## Programmatic noun and verb clerks

The default (programmatic) Noun and Verb clerks read text with `MemoryTextAnalyzer`, which runs on two shipped static resources and no model:

- **WordNet 3.1** (`composeResources/files/aive-wordnet-v1.txt.gz`, ~2.3 MB): noun and verb synsets with hypernyms, cross-part-of-speech derivations and topic domains, in WordNet's sense order; adjective/adverb membership; irregular forms. Built by `tools/memory_lexicon/build_wordnet_lexicon.py`.
- **A part-of-speech tagger** (`aive-pos-tagger-v1.txt.gz`, ~1.1 MB): a greedy averaged perceptron (Penn Treebank tags) trained by `tools/memory_lexicon/train_pos_tagger.py` on Universal Dependencies English-EWT, with WordNet's allowed parts of speech for each word as features. 94.4% on the EWT test set. `pos_tagger_golden.tsv` pins the Kotlin port to the training script.

Both load once, on first use, into compact hashed tables (about 19 MB of JVM heap together).

Per text, the analyzer:

1. takes code first: fenced blocks and stack-trace lines yield code entities only; in prose, backtick spans, identifiers, paths, URLs, `#123` references, commit hashes, versions, flags and Gradle task paths are single tokens, masked from the tagger;
2. splits sentences without breaking `v1.2`, `foo.bar()`, paths or common abbreviations;
3. tags parts of speech, then reads noun-phrase chunks as entities and verbs (minus auxiliaries) as actions, with phrasal particles (`roll back`) and NegEx-style negation (`didn't delete` is the tag `not remove`, never `remove`);
4. chooses a sense: the curated software overlay (`MemoryTechnicalLexicon`) first, since WordNet's first sense of `bug` is the insect; then WordNet, preferring computing-domain senses in technical text and skipping senses that cannot be meant there; otherwise the most frequent sense. Synonyms of the chosen sense become the tag's `aliases` and share its key, so `delete` and `remove` are one tag;
5. adds implied entities and actions, each marked `impliedBy` with lower confidence and salience: broader terms (only from a confident sense), compound heads (`gradle cache` → `cache`), identifier heads (`FooRepository` → `repository`), exception classes, file languages, URL hosts and GitHub repositories/pull requests, commits, options, Gradle tasks, acronym expansions (defined in the text by Schwartz–Hearst, or unambiguous in the overlay), and nominalizations (`deletion` → `delete`);
6. records verb–object pairs ReVerb-style (verb, optional particle or preposition, the next noun phrase in the same clause; passive voice takes the subject). `it`/`they`/`them` resolve to the most salient agreeing phrase in the last three sentences, or stay unresolved when two candidates are close.

Tag nodes carry `conceptKey`, `aliases`, `impliedBy`, `sense`, `negated`, and for verbs `objectKeys`/`objectPhrases`/`objectSpans`. The Phrase clerk pairs a verb only with the objects recorded for it; tags without that record (from a model-backed tagger) keep the earlier pairing.

None of this judges truth or compares memories: it records what the text says, negations included. If the resources cannot load, the clerks fall back to the earlier keyphrase and verb-list rules.

Sense choice is the most-frequent-sense baseline plus the overlay and domain preference, not full word-sense disambiguation; the claim above about richer resources stands for `RuleBasedMemoryLexicon`, which the associators still use.

## Convey-inspired structural semantics

The lexical analyzer adopts the useful shape of Convey's deterministic semantic code without pretending that its lightweight built-in parser is a full dependency parser.

For a sentence with a confidently known verb, The Aive may derive weak structural signatures such as:

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

`MemoryEpoch8ModelCatalog` lists the memory models the runtime installs: the `memory-layer-epoch8` Association Linker (embeddings) and the generative clerks released in `MemoryClerkCatalog` (`tools/memory_training`). The epoch-8 generative INT8 archives are no longer offered: they do not load in ONNX Runtime and answer in their training schema, not the clerk contract.

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
