# Haive Memory Clerks — Training and Deployment Contract

This document is normative for Haive's local memory-clerk model family.

Memory clerks organize what was thought, said, done, requested, observed, or produced. They do not decide what should be thought.

## Architecture

Haive uses two distinct local-inference families:

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

Correct behavior: high semantic association because both concern the API timeout.

Forbidden behavior: declaring that they contradict, deciding which is correct, or deciding which supersedes the other.

Measure related-pair recall, unrelated-pair precision, ranking quality, similarity calibration, and accidental judgment leakage. Contradiction/truth-judgment leakage must be zero by construction: the embedding runtime has no generative judgment contract.

## Prompt 9 — CondensationRewriter

Train `CondensationRewriter` on tiny programmatically selected clusters of highly similar memories at the same abstraction level.

The program decides that a cluster is eligible for review. The clerk performs representational compression only.

Output either one faithful generalized representation or `DO_NOT_CONDENSE`.

If combining memories would require deciding which substantive claim is correct, return `DO_NOT_CONDENSE`.

`Supersedes` in this stage means a retrieval representation has been replaced by a compressed equivalent. It does not mean the source belief was declared false or obsolete. Original provenance must remain reachable.

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
2. `AssociationLinker` links them only by similarity/relatedness.
3. A normal orchestrated Haive agent recalls both.
4. That agent may consciously notice and reason about the discrepancy.
5. Its reasoning becomes ordinary session context.
6. The resulting episode later passes through the same clerical pipeline.

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
