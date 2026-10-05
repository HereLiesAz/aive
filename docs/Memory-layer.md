# The Aive Memory Clerks — Training and Deployment Contract

This document is normative for The Aive's local memory-clerk model family.

Memory clerks organize what was thought, said, done, requested, observed, or produced. They do not decide what should be thought.

## Architecture

The Aive uses two distinct local-inference families:

1. **Eight structured generative clerks** based presumptively on `Qwen/Qwen2.5-0.5B-Instruct`, with specialist LoRA/PEFT adapters where practical.
2. **One semantic association clerk** based on a compact MiniLM-style embedding model and cosine similarity.

`AssociationLinker` is not a Qwen generation role. Do not train it as one.

The eight generative roles are:

- `Sectioner`
- `SalienceFilter`
- `NounTagger`
- `VerbTagger`
- `PhraseSynthesizer`
- `SummarySynthesizer`
- `CategoryClassifier`
- `CondensationRewriter`

The embedding role is:

- `AssociationLinker`

The corresponding runtime contracts are intentionally separate:

- `MemoryGenerativeInferenceRuntime` — autoregressive structured generation for the eight Qwen-style clerks.
- `MemoryEmbeddingInferenceRuntime` — vector embeddings for `AssociationLinker`.

Do not reintroduce a unified inference contract that lets the association worker masquerade as a generator.

## Governing boundary

Memory clerks may:

- segment
- retain or omit obvious noise
- normalize
- extract semantic entity indexes
- extract semantic action indexes
- synthesize short retrieval phrases
- summarize bounded source material
- categorize
- associate related memories
- condense redundant representations

They must not independently decide:

- truth or falsity
- correctness
- contradiction
- which conflicting statement wins
- morality
- blame
- strategy
- which implementation is better
- which belief should prevail
- whether one substantive memory invalidates another

If source material explicitly contains such a judgment, a clerk may preserve that judgment as source material. It may not originate the judgment.

There is no memory-layer `ConflictResolver` role.

`AssociationLinker` may emit only semantic relatedness such as `SimilarTo` and `AssociatedWith`. It must never infer or emit `ConflictsWith`.

No model-backed clerk may write `Supersedes`, `ResolvesConflict` or `ConflictsWith`: they are not in
the relation list any model prompt offers (`MODEL_WRITABLE_RELATIONS`), and both model decoders
(`StructuredMemoryMicroAgent`, `TextGenerationMemoryManagerAgent`) reject an answer that uses them, or
the engine-owned kinds below. `ConflictsWith` and `ResolvesConflict` remain only so older stored
graphs still load; nothing writes them.

The engine enforces the boundary; it does not trust a clerk to keep it. Whatever runs a stage
(programmatic, local model, hosted provider), `MemoryConsolidator` validates the answer in code:

- Clerks only add. No answer edits or deletes a stored memory.
- Every derived node and section must point at sources inside its packet, and keep its source episode.
- A condensation cluster is never offered to any clerk if two of its members assert different values
  (a number, a quoted string, or negation in some but not all; `memoryClaimSignature`) or **contrast**
  (same frame, different filler; below). Members are dropped from a cluster greedily until none
  contrast or carry a divergence marker to each other, so agreeing members can still fold.
- A condensation must restate its sources' values unchanged (same claim signature), create exactly one
  memory, and link every source through `CondensedFrom`. A clerk that also emits `Supersedes` (or any
  relation other than `CondensedFrom`/`AssociatedWith`) is rejected, and the cluster is declined once
  it keeps failing.
- **Supersession is coverage only.** After accepting a condensation, the engine itself adds
  `Supersedes` from the generalized memory to each source whose every sentence the generalized text
  contains (case and spacing folded), which includes an identical repeat (`memoryCovers`). Every other
  source stays active: the generalized memory is an extra index entry beside it, linked by
  `CondensedFrom`. A superseded memory leaves ranking but stays in the store with its provenance, and
  is still returned as a divergent partner (below). A memory already folded into a condensation is not
  offered for condensation again.
- Forgetting an episode removes what was derived only from it; memories with other sources stay, and
  a register variant no remaining memory attests is removed with it.

Condensation exists to fold repeated and similar memories, as people do. The defect it must avoid is
treating a **frame** match as similarity. Every statement has a frame (what it is about: its subject
and the question or predicate it answers) and a filler (what it says in that slot). "I chose Postgres
for the database" and "I chose MySQL for the database" share a frame and differ in filler: they are
not similar, they are a contrast, and memory keeps both. The terms are borrowed loosely from frame
semantics (Fillmore; FrameNet's frames and frame elements); the implementation is a surface diff, not
a frame lexicon.

### Contrast, variant register, divergence marker, deliberation

All of this is deterministic code in the engine (`MemoryContrast.kt`, `MemoryVariantRegister.kt`),
the same for every clerk engine, and none of it judges which memory is right.

- **Contrast** (`MemoryContrast.between`): tokenize both texts, fold entity aliases
  (`MemoryAliases`, a small explicit alias table: "Postgres", "PostgreSQL", "psql" are one name), and
  align them by longest common subsequence. The shared tokens are the frame; each gap is a slot. A pair
  contrasts when the shared part holds at least half the content words of the shorter text and some
  slot either holds different content words on both sides, at least one of which looks like a filler
  (a number or identifier, a capitalized name, a quoted string, or a value word such as
  `enabled`/`disabled`, `tabs`/`spaces`), or carries negation on one side only. A repeat, an aliased
  spelling or an added word ("…today") is not a contrast. This is a heuristic: it can miss a contrast
  phrased with different structure, and it can flag two lowercase names it reads as values.
- **Divergence marker** (`Diverges`): when an episode reaches the condensation stage, before any
  condensation, the engine compares each of its Context, Phrase and Summary memories with up to 48
  same-kind memories sharing a content word (any project) and links every contrasting pair. The marker
  is structural and advisory, like a genealogy finding: it says the two differ, nothing more. A
  generalized memory inherits its sources' markers.
- **Variant register**: for each frame, a `Frame` node and one `Variant` node per filler
  (`VariantOf`), and an `Attests` edge from every memory that states that filler. Each attestation
  records age (the memory's record time, its episode times and, when the text states an ISO date, that
  event date), context (sessions, project, workflow run, task run, role), subject (the frame's words
  before the first slot) and source episodes; a variant's occurrence count is its distinct source
  episodes. It is add-only and keyed by content, so writing it twice is a no-op. Read it with
  `MemorySnapshot.variantRegister()`. Nothing in it is ranked as correct. Keeping both record and
  event time is the bitemporal pattern (transaction vs valid time) applied to attestations only; it is
  not a bitemporal store.
- **Recall unit**: every recall result carries its divergent partners (`MemoryRecallHit.conflicts`,
  from `Diverges` and legacy `ConflictsWith`) and any deliberations about them, including partners
  that are superseded, out of the query's scope or did not match. Ranking, the result budget, the
  attention dial and prompt rendering all handle a hit and its partners as one item; rendering adds
  whole units only and never cuts one in two. Surfacing is on by default (`includeConflicts = true`).
- **Deliberation record**: an agent's conscious conclusion about a divergence,
  `MemoryTool.deliberate(MemoryDeliberationRequest)`. It is stored as its own episode and a
  `Deliberation` node citing the memories (and naming the evidence) it considered (`Deliberates`). It
  is not queued for consolidation, so it is never sectioned or condensed, and it never supersedes: the
  marker and both sides stay, and recall returns the deliberation with the unit ("has deliberations").

Keeping every contrasting trace and handing the set to a reasoner follows the spirit of an
assumption-based truth maintenance system (de Kleer's ATMS keeps contradictory environments rather
than retracting one). Unlike an ATMS, memory never labels anything inconsistent.

Differing memories therefore stay separate and marked, so recall brings them to an ordinary agent
together. That agent may notice the discrepancy ("wait a sec…"), reason about it, and record its
conclusion as a deliberation or bank it as new experience through the same pipeline
(`docs/architecture/MEMORY_BANKING_AND_ATTENTION.md`).

### Why not a self-edited knowledge wiki

Google Research's WikiSkill (arXiv:2608.27454) keeps agent experience as wiki pages that a maintainer
LLM edits in place, and walls the acting agent off from them. It puts conscious reasoning in the
curator and keeps the thinker away from memory: the inverse of this layer.

- **The curator does the thinking.** The maintainer analyses root causes, reconciles evidence and
  patches pages in place, so a disagreement is settled by whoever writes, and the losing evidence is
  gone. Here clerks only file; recognizing and reasoning about a conflict belongs to an ordinary agent.
- **The thinker never meets the conflict.** WikiSkill's acting agent cannot read the wiki; it gets
  only distilled skills. Here associated traces, clashing ones included, surface into the agent's
  context; attention decides when, never whether they exist.
- **Conclusions replace experience.** A maintainer's explanation becomes the page itself, unattributed
  and read back as fact. Here an agent's conclusion is one more episode: attributed to its session,
  stored beside the evidence it reasoned about, and open to being reconsidered the same way.
- **Knowledge outlives its evidence.** WikiSkill reverts skills but never the wiki, so lessons drawn
  from rejected runs keep steering. Here every derived memory carries provenance, and nothing is
  destroyed because a curator judged it obsolete, wrong or contradictory.
- **Prose over schema.** Free-form pages cannot be validated; every clerk answer here is a typed,
  add-only mutation checked before it is stored, and clerks see bounded packets, never the whole store.

## Memory flow

The intended pipeline is:

session context
→ sections
→ retained context
→ noun/entity indexes
→ verb/action indexes
→ phrases
→ summaries
→ categories
→ semantic associations
→ similarity-based condensation

Every abstraction must preserve provenance so a later agent can descend back to its source episode.

No clerk should require the whole memory graph. Use deliberately bounded packets.

### Memory screen

The **Memory** destination (every platform) is the whole layer in one place, driven by the shared
`MemoryLayerController`:

- **Terrarium:** an intake plus one creature per stage, in pipeline order. A creature is active while
  its stage processes a packet, ready while entries wait for it, blocked when entries are parked
  there, and complete once it has produced memories; the link into the working stage carries.
- **Engines:** tap a creature to choose Programmatic, Local model or Hosted (with provider and model)
  for each of its clerks; fallbacks and their reasons are shown.
- **Queue:** waiting and parked counts; retry or discard parked entries.
- **Tuning:** attempts before parking, packet size, similarity needed to condense, batch size.
- **On-device models:** install or remove each clerk's model where the platform has them (Android).
- **Stored memory:** counts, forget one episode (and what came only from it), export/import JSON,
  forget everything. Memory can be switched off or its consolidation paused.

Every platform stores memory in SQLite through one `SqlMemoryStore`. On the web the database
lives in the Origin Private File System, opened in a worker (`shared/memory-worker/`, the official
SQLite WebAssembly build with its `opfs-sahpool` VFS, which needs no COOP/COEP headers).
`openWebMemoryStore` creates or migrates the schema through `PRAGMA user_version` and imports the
older browser-storage log once. A browser without OPFS keeps that browser-storage store. A second
tab cannot open the database while the first holds it; it gets memory that lasts only for the
session rather than a second copy that would diverge.

### Engines

Every stage runs on one of three engines, chosen per stage in `MemoryLayerSettings`
(persisted by `MemoryLayerSettingsStore`):

| Engine | What runs | Notes |
|---|---|---|
| Programmatic (default) | `ProgrammaticMemoryClerks` | Deterministic, nothing downloaded |
| Local model | The platform's installed on-device clerk | Android and desktop; only clerks released in `MemoryClerkCatalog` (`tools/memory_training`) and the epoch-8 Association Linker |
| Hosted model | A configured provider's text API, through `StructuredMemoryMicroAgent` | Not for AssociationLinker (embeddings) |

`assembleAgents` builds the clerks; a stage whose engine is unavailable on the platform runs
programmatically and the reason is reported. Settings also carry `enabled` (off: nothing banked or
recalled, stored memory kept), `consolidationPaused` (banking continues, consolidation waits) and the
consolidation `policy`. Changes apply between packets.

### Storage

The graph lives in SQLite through SQLDelight (`SqlMemoryStore`; schema in
`shared/src/commonMain/sqldelight/.../Memory.sq`). One row per episode, section, node, edge, queue
entry and declined cluster; each row holds the full record as JSON plus indexed columns (node kind,
edge endpoints and relation, episode) for querying. A commit is one transaction containing only its
own rows. Queries are generated as suspend functions so the same store runs on the browser's
asynchronous worker driver. `SettingsMemoryStore` remains for platforms not yet on SQLite and as the
source of the one-time import.

### Failure and output contract

- Every generative clerk answers one JSON object with `sections`, `nodes` and/or `links`. Any other
  shape is rejected as a failure, never read as "nothing to add". The epoch-8 generative releases were
  trained on a different schema (`{"mutations":[{op,target_ref,payload}]}`) and are no longer offered;
  local clerks are retrained on this contract (`tools/memory_training`). Local models receive
  `MemoryMicroAgentPrompts.chatPrompt`, the same chat template they are trained on; hosted engines get
  the rendered packet alone.
- A queue entry that fails `MemoryConsolidationPolicy.maxAttempts` times (default 3) is parked: it
  stays `Failed` with its `lastError`, and consolidation moves on to the next entry.
- `DO_NOT_CONDENSE` is a valid answer. The cluster is recorded in `declinedCondensations` and not
  offered again until its membership changes. A cluster that fails `maxAttempts` times is declined the
  same way, so one bad cluster cannot keep an entry from completing.

## Confidence semantics

If a schema contains `confidence`, it means **derivation fidelity**:

> How faithfully and directly does this abstraction represent its supplied source?

It does not mean probability that the underlying claim is true.

## Semantic nouns and verbs

Nouns and verbs are semantic indexes, not grammatical parts of speech.

Useful entity references include people, projects, classes, interfaces, functions as callable identities, methods, variables, files, directories, modules, packages, namespaces, APIs, endpoints, database tables, schemas, configuration keys, environment variables, repositories, branches, commits, workflows, CI jobs, Gradle tasks, data structures, model names, libraries, and artifacts.

Useful actions include call, invoke, fetch, parse, validate, serialize, deserialize, read, write, save, persist, create, delete, update, merge, commit, checkout, build, compile, test, lint, deploy, upload, download, map, filter, transform, POST, GET, PATCH, enqueue, and run.

The same identifier may appear in both indexes. `saveUser()` can be an entity named `saveUser` and an action meaning save/persist user.

## Prompt 0 — Shared generative training framework

Build the common Kaggle framework for the eight generative clerks.

Use `Qwen/Qwen2.5-0.5B-Instruct` as the presumptive shared foundation model. Benchmark it first and attempt to disprove its suitability against at least two compact controls. Replace it only when measured evidence shows a material disadvantage in specialist accuracy, structured-output reliability, source-code comprehension, LoRA specialization, quantization degradation, or supported-platform inference.

The framework must provide:

- deterministic seeds
- exact dependency/version recording
- tokenizer setup
- LoRA/PEFT training
- optional QLoRA where useful
- checkpoint/restart support
- train/validation/test splitting
- JSON validation
- provenance validation
- hallucinated-ID detection
- token and character budgets
- role-specific evaluation
- clerical-boundary evaluation
- export and quantization utilities
- ONNX validation
- cross-platform deployment manifests

Benchmark practical context limits such as 512, 1,024, and 2,048 tokens. Prefer the smallest reliable operating packet.

## Prompt 1 — Sectioner

Train `Sectioner` to divide a bounded episode into granular self-contained sections with provenance.

It may identify requests, decisions as stated, implementation changes, constraints, discovered facts, results, errors, plan steps, code operations, preferences, and explicit reasoning.

It must not judge correctness, reconcile statements, infer contradiction, reinterpret intent, or invent causality.

Train heavily on mixed prose, code, diffs, logs, stack traces, CI output, JSON, YAML, SQL, shell, Kotlin, Java, JavaScript/TypeScript, Python, C/C++, Rust, Swift, and HTML/CSS.

## Prompt 2 — SalienceFilter

Train `SalienceFilter` for clerical retention, not strategic judgment.

Retain durable requirements, preferences, explicit decisions, identifiers, implementation details, code behavior, paths, errors, results, state changes, corrections, plans, explicit reasoning, and unresolved work.

Usually omit greetings, empty acknowledgements, duplicate wording, tool boilerplate, transient progress chatter, and formatting noise.

Never omit material because the clerk believes it false, wrong, contradictory, immoral, or strategically inferior.

## Prompt 3 — NounTagger

Train `NounTagger` as a semantic entity/reference indexer.

Deterministic code hints may be supplied as advisory candidates. The clerk may keep, normalize, split, reject, or supplement them.

Do not invent interpretive labels such as contradiction, wrong implementation, false claim, or flawed design unless those concepts are explicitly present in source material.

## Prompt 4 — VerbTagger

Train `VerbTagger` as a semantic action/operation indexer.

Infer useful actions from identifiers such as `saveUser`, `fetchWorkflow`, and `validateToken` while preserving ambiguity when semantics are unclear.

Do not generate interpretive actions such as disproves, contradicts, proves wrong, invalidates, or should replace unless explicitly stated in source material.

## Prompt 5 — PhraseSynthesizer

Train `PhraseSynthesizer` to convert bounded entity/action indexes plus provenance into concise, neutral, retrieval-friendly phrases.

It must not decide whether an implementation worked correctly, whether a decision was good, whether statements conflict, or which approach should win.

## Prompt 6 — SummarySynthesizer

Train `SummarySynthesizer` on small bounded groups of related phrases.

Preserve actors, entities, operations, requirements, constraints, explicit decisions, state changes, code behavior, and explicit reasoning present in source material. Remove repetition and stylistic clutter.

Do not choose which input statement is true, reconcile differing claims, decide which is newer/correct, invent causes, or recommend interpretations.

## Prompt 7 — CategoryClassifier

Train `CategoryClassifier` to assign reusable filing labels such as project, component, technical domain, artifact type, deployment, testing, debugging, UI, persistence, networking, source control, configuration, memory, requirement, preference, and implementation state.

Support multi-label output.

Do not autonomously assign judgment labels such as correct, incorrect, true, false, contradiction, flawed, superior, inferior, trustworthy, or untrustworthy.

## Prompt 8 — AssociationLinker embedding model

Do **not** train Qwen for this role.

Use a compact MiniLM-style sentence-embedding model exported for local ONNX inference.

The association path is:

bounded memory text
→ embeddings
→ normalized vectors
→ cosine similarity
→ threshold/ranking logic
→ `SimilarTo` / `AssociatedWith` edges

Evaluate on identical claims, paraphrases, subtly differing values, changed code, changed configuration, old/new requirements, compatible descriptions, incompatible descriptions, and unrelated controls.

The role exists only to make semantically related memories retrievable together.

Example:

- A: `API timeout is configured for 30 seconds.`
- B: `API timeout is configured for 60 seconds.`

Correct behavior: high semantic association because both concern the API timeout. (The engine's deterministic contrast step separately marks them as a divergence, which is a structural fact, not a verdict.)

Forbidden behavior: declaring that they contradict, deciding which is correct, or deciding which supersedes the other.

Measure related-pair recall, unrelated-pair precision, ranking quality, similarity calibration, and accidental judgment leakage. Contradiction/truth-judgment leakage must be zero by construction: the embedding runtime has no generative judgment contract.

## Prompt 9 — CondensationRewriter

Train `CondensationRewriter` on tiny programmatically selected clusters of highly similar memories at the same abstraction level.

The program decides that a cluster is eligible for review. The clerk performs representational compression only.

Output either one faithful generalized representation or `DO_NOT_CONDENSE`.

If combining memories would require deciding which substantive claim is correct, return `DO_NOT_CONDENSE`.

Clusters that disagree on a value never reach the clerk (see **Governing boundary**), so the clerk's
remaining judgment is whether agreeing members are truly redundant. Its output must keep their values
exactly: no number, quoted string or negation added or dropped.

The clerk links the generalized representation to every source with `CondensedFrom` only. It never writes `Supersedes`: the engine adds it, and only for a source whose every sentence the generalized text contains. `Supersedes` therefore means a retrieval representation is fully covered by another; it never means the source belief was declared false or obsolete. Original provenance remains reachable.

## Adversarial release gate

All generative clerks must pass a `Clerks, Not Thinkers` suite covering:

- conflicting-looking facts
- old and new requirements
- broken and corrected code
- competing architectures
- moral disagreement
- explicit reconciliation already present in source
- implicit disagreement
- callable entity + semantic-action dual indexing
- similarity without equivalence
- unsafe condensation

Report task score, hallucination rate, provenance errors, structured-output failures, higher-order judgment leakage, contradiction-inference leakage, and unsafe-condensation rate.

High task accuracy does not compensate for boundary violations.

The embedding `AssociationLinker` is evaluated separately on retrieval quality and similarity calibration. It does not participate in generative boundary testing because it does not generate semantic judgments.

## Export and deployment

For the eight generative Qwen-style clerks, evaluate:

- shared quantized Qwen base + switchable adapters
- merged and separately quantized specialist models

For `AssociationLinker`, export the selected MiniLM-style embedding model independently.

Required platforms:

- Android
- Windows
- macOS
- Linux
- Web

Use portable ONNX artifacts where supported by the current deployment manifests.

Web requires WASM fallback. WebGPU is an optimization.

Hardware selection is a runtime concern, not an artifact-family label. An ONNX model is not a CUDA, CoreML, QNN, or WebGPU model merely because one of those providers may execute it.

## Hardware execution policy

Runtime selection uses `MemoryComputePreference`:

- `AUTO`
- `HIGH_PERFORMANCE`
- `LOW_POWER`
- `CPU_ONLY`

`AUTO` should prefer NPU-class devices for embedding workloads and GPU-class devices for autoregressive generation, subject to model compatibility and platform/provider availability.

Try compatible accelerators in ranked order. Use CPU only after accelerator candidates fail or when policy explicitly selects CPU.

Platform provider candidates may include:

- Android: NNAPI, QNN, WebGPU where supported, then CPU
- Windows: CUDA, TensorRT, DirectML, QNN, WebGPU, then CPU as available
- macOS: CoreML, WebGPU, then CPU as available
- Linux: CUDA, TensorRT, ROCm, WebGPU, then CPU as available
- Web: WebGPU, then WASM

Do not claim acceleration merely because an accelerated provider was selected. Execution reports distinguish configured sessions from observed provider execution.

## End-to-end validation

Run realistic completed-agent sessions through:

session
→ `Sectioner`
→ `SalienceFilter`
→ `NounTagger`
→ `VerbTagger`
→ `PhraseSynthesizer`
→ `SummarySynthesizer`
→ `CategoryClassifier`
→ MiniLM embeddings / `AssociationLinker`
→ `CondensationRewriter`

Verify that useful memories survive, chatter disappears, code is indexed correctly, provenance remains navigable, related memories are retrievable together, differing memories remain independently represented, unsafe condensation is refused, and graph growth remains manageable.

To validate conscious conflict reasoning:

1. Store two semantically related memories containing differing information.
2. `AssociationLinker` links them only by similarity/relatedness; when they share a frame and differ in filler, the engine's contrast step marks them `Diverges` and records both fillers in the variant register.
3. A normal orchestrated Aive agent recalls both, as one unit.
4. That agent may consciously notice and reason about the discrepancy.
5. Its reasoning becomes ordinary session context.
6. The resulting episode later passes through the same clerical pipeline, or the agent records its conclusion as a deliberation that cites both memories and leaves them and the marker in place.

Contradiction awareness belongs to the ordinary agent, not to the memory clerks.

## Release manifest

Produce `memory-clerks-manifest.json` with entries for all nine roles.

For generative roles, include the Qwen base/adapters or merged artifacts, quantization, limits, platforms, provider compatibility, task metrics, boundary metrics, latency, memory usage, and hashes.

For `AssociationLinker`, include the MiniLM embedding model, embedding dimensions, normalization policy, similarity thresholds, platforms, provider compatibility, retrieval metrics, latency, memory usage, and hashes.

Integration must map artifacts directly to:

- `MemoryMicroAgentRole`
- `MemoryMicroAgentModelSpec`
- `MemoryMicroAgentDeploymentManifest`
- `MemoryGenerativeInferenceRuntime`
- `MemoryEmbeddingInferenceRuntime`

Do not reference the obsolete `MemoryMicroAgentInferenceRuntime` contract.

## Final release rule

The eight generative clerks organize source material without performing higher-order adjudication.

The association clerk computes semantic relatedness through embeddings and cosine similarity without generating conclusions.

MEMORY CLERKS ORGANIZE WHAT WAS THOUGHT.

THEY DO NOT DECIDE WHAT SHOULD BE THOUGHT.
