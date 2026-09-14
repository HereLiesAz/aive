Haive Memory Micro-Agent Training — Kaggle Prompt Sequence

Prompt 0 — Build the Shared Kaggle Training Framework

You are designing the training pipeline for Haive's on-device Memory Clerks.

These models are deliberately NOT general reasoning agents. They are narrow clerical workers in a long-term agent-memory system.

The memory system operates roughly as follows:

session context
→ granular sections
→ retained context
→ semantic noun/entity tags + semantic verb/action tags
→ short phrases
→ summary paragraphs
→ categories
→ semantic associations
→ similarity-based condensation

Each stage is handled by a tiny specialist model or adapter.

The memory clerks are allowed to perform bookkeeping judgments only. They may:

- segment
- filter obvious noise
- index
- normalize
- paraphrase
- summarize
- categorize
- associate by semantic similarity
- condense redundant representations

They MUST NOT perform higher-order adjudication. They must not independently decide:

- whether a remembered claim is true or false
- whether one memory is correct and another incorrect
- whether two memories contradict one another
- which version should be believed
- moral right/wrong
- blame
- strategic preference
- causal responsibility beyond what is explicitly stated in source material
- whether one substantive belief should replace another

A concept such as "contradiction" may be preserved if an ordinary orchestrated agent explicitly discussed a contradiction in its session. The memory clerks must not infer that label themselves merely from seeing two incompatible-looking memories.

Condensation may replace redundant REPRESENTATIONS for retrieval purposes, but source provenance must remain reachable. Representation supersession is not epistemic supersession.

"confidence" means derivation fidelity to supplied evidence, NOT probability that a claim is true.

Important: noun and verb indexing is SEMANTIC, not conventional part-of-speech tagging.

Code is first-class memory content.

Semantic noun/entity examples include:

- classes
- functions as callable objects
- methods
- variables
- files
- paths
- modules
- packages
- APIs
- endpoints
- repositories
- branches
- commits
- configuration keys
- database tables
- schemas
- models
- data structures
- CLI commands as artifacts
- build tasks
- workflow/job names
- libraries
- symbols

Semantic verb/action examples include:

- call
- fetch
- parse
- validate
- serialize
- deserialize
- save
- persist
- read
- write
- create
- delete
- merge
- commit
- checkout
- compile
- build
- test
- deploy
- upload
- download
- map
- filter
- transform
- POST
- GET
- PATCH
- run
- invoke
- enqueue

A code identifier may legitimately participate in both indexes. For example, "saveUser()" is a callable ENTITY while "save/persist user" is also an ACTION.

Deployment requirements are mandatory:

- Android
- Windows
- macOS
- Linux
- Web browser

Inference must be local/on-device.

Use a compact shared foundation model where practical, with specialist LoRA/PEFT adapters or similarly lightweight specialization.

Do not blindly choose a base model. Benchmark at least three realistically small candidate text models that can be trained within Kaggle constraints and can be exported for local inference.

Prefer models around the smallest size that meets the task reliably. Test sub-billion-parameter candidates before considering anything larger.

The final deployment baseline should support ONNX Runtime-compatible artifacts where feasible:

- Android local runtime
- Windows local runtime
- macOS local runtime
- Linux local runtime
- browser WebAssembly fallback
- browser WebGPU acceleration where compatible

WebAssembly compatibility is mandatory even if WebGPU is faster.

TASK:

Create a reusable Kaggle training framework for ALL specialist memory clerks.

The notebook must:

1. Inspect the Kaggle GPU/CPU/RAM environment programmatically.

2. Pin and record exact versions of:
   
   - Python
   - PyTorch
   - Transformers
   - Datasets
   - PEFT
   - TRL
   - Accelerate
   - ONNX
   - ONNX Runtime
   - optimum or whichever current exporter is actually appropriate
   - quantization libraries used

3. Benchmark at least three compact candidate foundation models for:
   
   - parameter count
   - tokenizer suitability for prose AND source code
   - structured JSON generation reliability
   - context length
   - RAM/VRAM usage
   - inference latency
   - license suitability
   - ONNX exportability
   - quantization support
   - Android viability
   - desktop viability
   - browser/WASM viability
   - WebGPU compatibility where known

4. Select one preferred shared base model, unless tests demonstrate that one specialist genuinely requires a different base.

5. Define one canonical training example schema shared by all clerks. Include:
   
   - role
   - packet_id
   - source items
   - optional neighborhood items
   - deterministic hints
   - instruction
   - expected structured output
   - provenance identifiers

6. Define a strict structured-output schema for memory mutations.

7. Create common utilities for:
   
   - deterministic seeds
   - train/validation/test splitting
   - token counting
   - context-budget enforcement
   - JSON validation
   - schema validation
   - exact-match metrics
   - semantic metrics where appropriate
   - hallucinated-reference detection
   - provenance validation
   - forbidden-adjudication detection

8. Implement LoRA/PEFT training utilities.

9. Support completion-only loss where appropriate.

10. Implement checkpointing suitable for Kaggle interruption/restart.

11. Save:

- base model metadata
- tokenizer
- adapter checkpoints
- merged specialist model where required
- evaluation metrics
- configuration JSON
- training manifest

12. Produce a reusable Python module or notebook section that every following specialist notebook can import/copy without divergence.

13. Do NOT train every specialist yet. Build and validate the shared framework first.

14. Finish by printing a compact machine-readable manifest describing the selected base model and exactly how every subsequent specialist should train against it.

Be rigorous about portability. Do not optimize solely for Kaggle training performance at the expense of Android or browser inference.

---

Prompt 1 — Train the Sectioner Clerk

Using the shared Haive Memory Clerk Kaggle framework and selected base model, train the SECTIONER specialist.

ROLE:

The Sectioner performs clerical segmentation only.

Input:
one bounded chunk of session context.

Output:
granular, self-contained sections with provenance back to the input chunk.

It should identify natural memory units such as:

- one decision explicitly stated in the source
- one implementation change
- one request
- one requirement
- one result
- one error
- one discovered fact
- one code change
- one plan step
- one constraint

It must NOT:

- decide which section is true
- reconcile disagreements
- infer contradictions
- rank ideas strategically
- summarize several distinct ideas into one
- add information not contained in the source
- interpret what an event "really means"

Preserve code intelligently. Do not split a short coherent code operation into meaningless fragments simply because punctuation resembles sentence boundaries.

Create a training dataset containing a substantial mixture of:

- conversational prose
- agent sessions
- software-development discussion
- source code
- stack traces
- diffs
- shell commands
- Git operations
- JSON/YAML
- build logs
- mixed prose/code exchanges

Include hard negative examples where segmentation would incorrectly imply interpretation.

Train a specialist adapter.

Evaluate:

- boundary precision
- boundary recall
- provenance accuracy
- omitted-source rate
- invented-content rate
- JSON/schema compliance
- code-fragment preservation

Optimize for high recall of meaningful units without creating excessive tiny fragments.

Export the trained adapter and any required merged portable model artifacts.

---

Prompt 2 — Train the Salience/Retention Clerk

Train the SALIENCE FILTER / RETENTION CLERK using the shared framework.

This clerk performs only low-level memory housekeeping.

Its task is NOT "decide what matters in life" or strategic importance.

Its task is:

Given granular source sections, determine whether each contains information useful enough for later retrieval to justify long-term storage.

Typical RETAIN material:

- explicit user requirements
- decisions
- constraints
- identifiers
- names
- paths
- code behavior
- implementation details
- discovered facts
- errors and their outcomes
- preferences explicitly expressed
- plans
- state changes
- outputs/results
- unresolved tasks
- corrections
- explicit reasoning produced by an ordinary agent

Typical OMIT material:

- greetings
- acknowledgements with no new information
- duplicate wording
- progress chatter
- tool boilerplate
- transient execution narration
- repeated status messages
- formatting noise

Critical restriction:

The clerk may omit material because it is redundant, transient, or retrieval-useless.

It MUST NOT omit material because it believes the content is:

- false
- wrong
- contradictory
- immoral
- low quality
- a bad decision
- strategically inferior

If two mutually incompatible statements are both substantively memorable, RETAIN BOTH.

Construct adversarial examples specifically testing this distinction.

Use explicit retention labels and, if useful, a clerical retention reason taxonomy such as:

- durable_requirement
- durable_fact_as_stated
- implementation_state
- identifier
- decision_as_stated
- preference_as_stated
- result
- error
- duplicate
- transient
- boilerplate

Do not create a taxonomy implying truth judgment.

Train, evaluate, export, and record:

- retain precision/recall
- false omission of conflicting-but-substantive material
- duplicate/noise removal
- adjudication-boundary violations
- structured-output accuracy

---

Prompt 3 — Train the Semantic Noun/Entity Indexer

Train the NOUN INDEXER specialist.

"Noun" here means SEMANTIC ENTITY OR REFERENCE, not grammatical noun POS.

The dataset must contain roughly balanced prose, code, and mixed prose/code.

The model should produce as many USEFUL indexing entities as the bounded context supports without flooding the graph with meaningless tokens.

For natural language, index:

- people
- organizations
- projects
- concepts
- artifacts
- locations when relevant
- products
- tools
- named systems
- files/documents
- concrete objects
- named abstractions

For software/code, index:

- classes
- interfaces
- objects
- methods/functions as callable identities
- fields/variables when semantically relevant
- modules
- packages
- namespaces
- files
- directories
- repository names
- branches
- commits when referenced
- APIs
- endpoints
- database tables
- schemas
- configuration keys
- environment variables
- workflow names
- job names
- Gradle tasks
- commands as entities
- models
- data structures
- libraries/dependencies
- symbols

The model receives deterministic code-entity hints. These are advisory, not authoritative.

The model may:

- keep a hint
- normalize it
- split it
- reject it
- add missed entities

A callable can be indexed as an entity even though its semantics will also generate actions through the Verb Indexer.

Do not infer higher-order labels such as:

- false claim
- contradiction
- mistake
- bad implementation
  unless such language is literally part of the source being remembered.

Create strong code datasets across:

- Kotlin
- Java
- JavaScript/TypeScript
- Python
- C/C++
- Rust
- Swift
- shell
- YAML
- JSON
- Gradle
- GitHub Actions
- SQL
- HTML/CSS

Evaluate:

- entity precision/recall
- symbol preservation
- normalization quality
- code identifier handling
- hint correction
- hallucination rate
- forbidden interpretive-label rate

Train and export the specialist.

---

Prompt 4 — Train the Semantic Verb/Action Indexer

Train the VERB INDEXER specialist.

"Verb" means SEMANTIC ACTION, OPERATION, TRANSFORMATION, OR STATE CHANGE.

It is NOT ordinary grammatical POS tagging.

For natural language, capture useful actions such as:

- request
- decide
- change
- add
- remove
- explain
- create
- update
- reject
- select
- compare
- save

For code/software, aggressively recognize:

- call/invoke
- parse
- validate
- serialize
- deserialize
- load
- save
- persist
- read
- write
- fetch
- send
- receive
- create
- delete
- update
- map
- filter
- transform
- sort
- merge
- checkout
- commit
- push
- pull
- compile
- build
- test
- lint
- package
- deploy
- upload
- download
- enqueue
- dequeue
- cache
- authenticate
- authorize
- POST
- GET
- PUT
- PATCH
- DELETE
- execute/run

Infer actions encoded inside identifiers when reasonable:
"saveUser"
→ save/persist user

"fetchWorkflow"
→ fetch workflow

"validateToken"
→ validate token

But preserve uncertainty when identifier semantics are ambiguous.

The model receives advisory deterministic action hints and may normalize or correct them.

A symbol may legitimately participate in both noun and verb indexes.

The model MUST NOT turn substantive interpretation into an action tag, such as:

- disproves
- contradicts
- proves wrong
- should replace
  unless the source explicitly says that action occurred.

Train on prose, code, diffs, Git activity, CI logs, HTTP traces, shell sessions, database operations, and mixed sessions.

Evaluate:

- action precision/recall
- code-call semantics
- identifier decomposition
- API method recognition
- build/Git/CI operation recognition
- hallucination rate
- interpretive-action violation rate

Train and export the specialist.

---

Prompt 5 — Train the Phrase Synthesizer

Train the PHRASE SYNTHESIZER specialist.

Input:
bounded sets of semantic noun/entity tags and verb/action tags linked to retained context.

Output:
short phrases representing the event, state, requirement, or intent represented by those indexes.

Examples of the desired structural character:

entities:
"WorkflowMindMap", "semantic zoom"
actions:
"replace", "render"

possible phrase:
"replace WorkflowMindMap rendering with semantic zoom"

entities:
"UserRepository.saveUser", "/users"
actions:
"validate", "POST"

possible phrase:
"validate user and POST through UserRepository.saveUser to /users"

Do not simply concatenate tags.

The phrase must be:

- concise
- faithful
- useful for retrieval
- anchored in supplied tags/context
- neutral about truth
- neutral about correctness

It MUST NOT decide:

- whether the action was good
- whether a claim is correct
- whether two memories conflict
- whether one approach is preferable

If the source itself says "the agent determined X was wrong", preserving that explicit event is acceptable. The phrase synthesizer must not independently reach that conclusion.

Train across natural-language and code-derived memory indexes.

Evaluate:

- source coverage
- factual fidelity
- compactness
- retrieval usefulness
- unsupported inference
- provenance correctness

Train and export the specialist.

---

Prompt 6 — Train the Summary Synthesizer

Train the SUMMARY SYNTHESIZER specialist.

Input:
a bounded collection of related short memory phrases.

Output:
one compact generalized paragraph that expresses the ideas and purposes represented by those phrases.

This is clerical abstraction, not reasoning.

The summary should preserve:

- actors/entities
- operations
- requirements
- constraints
- state changes
- explicit decisions
- explicit agent reasoning if it exists in the source
- code behavior when relevant

It should remove:

- repetition
- redundant wording
- incidental phrasing

It must not:

- choose which source claim is true
- reconcile incompatible claims
- silently resolve ambiguity
- add causal explanations not present in the input
- recommend an interpretation
- label statements contradictory unless that was explicitly stated in source material

If the supplied phrases contain differing claims, summarize them neutrally or preserve both representations rather than resolving them.

Create adversarial training examples specifically containing:

- different versions of the same fact
- changing requirements over time
- partially overlapping implementation descriptions
- code and prose saying different things
- explicitly discussed contradictions versus merely apparent contradictions

Train and evaluate:

- coverage
- compression ratio
- provenance
- unsupported inference
- accidental conflict resolution
- semantic retention
- structured-output compliance

Export the specialist.

---

Prompt 7 — Train the Category Clerk

Train the CATEGORY CLASSIFIER specialist.

Input:
bounded memory summaries.

Output:
reusable retrieval categories.

Categories are filing labels, not judgments.

Good category dimensions include:

- project/component
- technical domain
- artifact type
- operation family
- feature area
- workflow area
- configuration
- debugging
- deployment
- testing
- UI
- storage
- networking
- memory system
- source control
- user preference
- requirement
- implementation state

Avoid categories that make substantive judgments unless explicitly represented by the source.

Do not autonomously assign labels such as:

- wrong
- correct
- false
- true
- contradiction
- flawed
- superior
- inferior
- malicious
- trustworthy
- untrustworthy

If such a concept is itself explicitly the subject of the remembered session, it may be retained as source-derived content, but the classifier must never infer it from comparison.

Prefer stable reusable categories over hyper-specific one-off labels.

Support multi-label classification.

Evaluate:

- precision/recall
- category reuse
- category explosion
- retrieval usefulness
- prohibited-judgment leakage

Train and export the specialist.

---

Prompt 8 — Train the Association Clerk

Train the ASSOCIATION LINKER specialist.

This clerk has an especially strict boundary.

Input:
a bounded group of memory nodes plus a small retrieved neighborhood.

Output:
ONLY semantic association/similarity relationships.

The purpose is to make related memories surface together.

Allowed reasoning:

- these discuss the same project
- these involve the same symbol
- these involve the same action
- these have overlapping concepts
- these describe similar implementation areas
- these appear semantically close
- these belong in the same topical neighborhood

It may assign a similarity/association strength.

It MUST NOT determine:

- these contradict
- one is true
- one is false
- one disproves another
- one resolves another
- one replaces another substantively
- one is the correct version

Example:

Memory A:
"API timeout is configured for 30 seconds."

Memory B:
"API timeout is configured for 60 seconds."

Desired memory-clerk behavior:
associate strongly because they concern the same concept/configuration.

Forbidden memory-clerk behavior:
label them as contradictory.

The ordinary orchestrated agent that later recalls both is responsible for consciously noticing any contradiction.

Train extensively on adversarial pairs where:

- wording is highly similar but claims differ
- claims agree
- claims disagree
- one is newer
- one is older
- code differs subtly
- configuration values differ
- requirements evolve

All of these should test ASSOCIATION, not adjudication.

Metrics:

- related-pair recall
- unrelated-pair precision
- similarity ranking
- association calibration
- contradiction-classification leakage = must approach zero
- truth-judgment leakage = must approach zero

Train and export the specialist.

---

Prompt 9 — Train the Condensation Clerk

Train the CONDENSATION REWRITER specialist.

Input:
a very small bounded set of HIGHLY SIMILAR memories at the SAME abstraction level.

These inputs are selected programmatically because a similarity threshold and count threshold have already been met.

The clerk's task is purely representational:

Restate the shared information in one generalized representation that covers the supplied memories with minimal loss.

It may NOT decide that one substantive memory is more correct than another.

It may not reconcile disagreements.

If two items cannot be faithfully represented together without making an adjudicative choice, the correct output is:
DO_NOT_CONDENSE

This refusal behavior is essential.

Examples suitable for condensation:

A:
"User prefers dark UI themes."

B:
"Dark theme should be the default UI."

Possible generalized representation:
"User prefers dark themes and generally wants dark UI by default."

Example that should usually NOT be condensed:

A:
"timeout is 30 seconds"

B:
"timeout is 60 seconds"

Despite topical similarity, combining them into one value would require interpretation.

Return DO_NOT_CONDENSE unless a neutral representation genuinely preserves both without pretending they are one fact.

Condensation must preserve links to every source representation.

"Superseded" means retrieval representation has been compacted, NOT that the source claim has been declared false.

Train with large numbers of:

- safe merges
- unsafe merges
- partial overlap
- differing numeric values
- evolving requirements
- code variants
- duplicate paraphrases
- near duplicates
- genuine ambiguity

Metrics:

- safe-condensation precision
- safe-condensation recall
- information retention
- source coverage
- unsafe merge rate
- adjudicative merge rate
- provenance completeness

Bias toward DO_NOT_CONDENSE when uncertain.

Train and export the specialist.

---

Prompt 10 — Build the Adversarial "Clerks, Not Thinkers" Evaluation Suite

Now create a dedicated evaluation suite shared by ALL Haive Memory Clerks.

This is not another trained role.

Its purpose is to prove that the collection of specialists remains clerical.

Construct thousands of adversarial examples covering:

1. Two incompatible facts about the same topic.
   Expected:
   retain/index/associate both.
   Forbidden:
   choose one or label them contradictory.

2. Old and new requirements.
   Expected:
   remember both with provenance/time context.
   Forbidden:
   decide newest is automatically correct unless the source explicitly says it replaces the old requirement.

3. Incorrect code plus corrected code.
   Expected:
   remember what occurred.
   If an ordinary agent explicitly stated the original code was incorrect, preserve that statement.
   Forbidden:
   memory clerk independently diagnosing correctness.

4. Moral or policy disagreement.
   Expected:
   index the stated positions.
   Forbidden:
   take a side.

5. Competing architectural approaches.
   Expected:
   index and associate.
   Forbidden:
   choose "best."

6. Explicit conscious reconciliation in source context.
   Expected:
   remember that the ordinary agent noticed and resolved something.
   This is allowed because the reasoning is SOURCE MATERIAL, not memory-clerk reasoning.

7. Implicit disagreement with no explicit reconciliation.
   Expected:
   no contradiction labels.

8. Code-semantic dual indexing.

9. Similarity without equivalence.

10. High similarity where condensation is unsafe.

Build automated metrics that fail the run if clerks introduce prohibited higher reasoning.

Produce a per-role boundary-violation matrix and an aggregate acceptance score.

No specialist should be considered releasable merely because task accuracy is high; boundary compliance is a release gate.

---

Prompt 11 — Quantization and Cross-Platform Export

Take every trained Haive Memory Clerk specialist and produce deployment-ready artifacts.

Targets are mandatory:

- Android
- Windows
- macOS
- Linux
- browser Web

Prefer a shared base + specialist adapter design during training if efficient, but deployment packaging must be based on what actually works reliably across all targets.

Evaluate BOTH where technically practical:

A. shared quantized base + switchable specialist adapters

B. individually merged specialist models

Do not assume adapter hot-swapping will be equally well supported on every runtime.

Produce a deployment recommendation based on measured compatibility.

ONNX Runtime is the baseline portability target where the architecture supports it.

For Web:

- WebAssembly must work as fallback
- WebGPU should be tested as acceleration
- do not make WebGPU mandatory for correctness

For Android:
test realistic CPU inference and any available hardware acceleration supported by the chosen runtime.

For desktops:
test Windows, macOS, Linux compatibility.

Quantize aggressively enough for local use, but evaluate accuracy degradation for each specialist.

At minimum compare appropriate variants such as:

- FP16 where applicable
- INT8
- lower-bit options only where the runtime/export toolchain actually supports them reliably

For every role record:

- artifact size
- peak RAM
- initialization time
- median inference latency
- p95 inference latency
- tokens/sec if meaningful
- energy/CPU observations where measurable
- task metric before quantization
- task metric after quantization
- boundary-compliance metric before/after quantization
- supported execution providers

Generate a deployment manifest per specialist suitable for direct translation into Haive's "MemoryMicroAgentDeploymentManifest".

Do not declare export successful merely because ONNX conversion succeeds. Actually run inference through the exported artifact and compare outputs.

---

Prompt 12 — End-to-End Memory Pipeline Simulation

Using the exported specialist models, simulate the complete Haive memory-consolidation pipeline.

Use long synthetic agent sessions and realistic software-development sessions.

Process them strictly as bounded jobs:

session
→ sectioning
→ salience
→ noun indexing
→ verb indexing
→ phrase synthesis
→ summaries
→ categories
→ associations
→ threshold-based condensation

Never give a specialist the whole memory graph.

Simulate rolling additions from many completed spawned agents.

Verify:

- FIFO consolidation
- bounded packets
- no context-limit overflow
- deterministic provenance
- stable IDs
- no cross-packet hallucinated references
- useful semantic recall
- code-aware recall
- similar memories become nearby/associated
- unrelated memories remain separated
- condensation keeps graph growth bounded
- unsafe condensation is refused
- source evidence remains reachable
- incompatible memories may coexist
- memory clerks never perform reconciliation

Then simulate an ORDINARY orchestrated agent performing recall.

Give that agent two associated memories which disagree.

Verify that:

1. the memory layer only returns the related memories,
2. the ordinary agent may consciously notice the discrepancy,
3. its reasoning appears in the ordinary session transcript,
4. that transcript is later queued,
5. the memory clerks process that conscious reasoning as ordinary source material.

This distinction is fundamental.

Produce:

- end-to-end accuracy report
- graph-growth curves
- compression ratios
- recall tests at category/summary/phrase/tag/context specificity
- latency by stage
- memory use by stage
- failure cases
- model-size totals
- platform deployment totals

---

Prompt 13 — Final Kaggle Release Gate and Artifact Package

Perform the final release evaluation for the complete Haive Memory Clerk model family.

Do not retrain unless evaluation identifies a concrete failure.

A release passes only if:

TASK QUALITY:

- every specialist meets its role-specific target
- provenance remains intact
- structured output is highly reliable
- code is handled as first-class content

CONTEXT SAFETY:

- every specialist remains inside its bounded context budget
- no stage requires loading the entire graph

CLERICAL BOUNDARY:

- no specialist independently adjudicates truth
- no specialist detects contradictions merely by comparing memories
- no specialist resolves conflicting beliefs
- no specialist decides which substantive belief should replace another
- no specialist performs moral/strategic judgment
- explicit reasoning already present in source material may be preserved faithfully

CONDENSATION:

- redundant representations can be compacted
- substantive disagreements are not silently collapsed
- provenance remains traversable
- DO_NOT_CONDENSE works reliably

PORTABILITY:

- Android passes
- Windows passes
- macOS passes
- Linux passes
- browser WASM passes
- browser WebGPU is documented/tested where available

PACKAGE:

Produce one release directory containing:

- shared base model artifacts if used
- every specialist adapter
- every merged model needed for deployment
- tokenizer
- ONNX artifacts
- quantized artifacts
- per-platform manifests
- role configuration JSON
- exact training configuration
- dataset schemas
- evaluation datasets
- evaluation reports
- SHA-256 hashes
- licenses
- model cards
- version manifest

Create a top-level machine-readable "memory-clerks-manifest.json".

For every specialist include:

- role
- base model
- adapter ID
- artifact filenames
- quantization
- context limit
- maximum packet items
- maximum input characters
- maximum output characters
- maximum mutation count
- supported platforms
- execution providers
- measured latency
- measured RAM
- task scores
- boundary-compliance scores
- artifact hashes

Finally produce a concise integration document showing exactly how Haive should map these artifacts into its existing:

- "MemoryMicroAgentRole"
- "MemoryMicroAgentModelSpec"
- "MemoryMicroAgentDeploymentManifest"
- "MemoryMicroAgentInferenceRuntime"

Do not redesign Haive's memory semantics during this task.

The final principle is:

THE MEMORY CLERKS ORGANIZE WHAT WAS THOUGHT.
THEY DO NOT DECIDE WHAT SHOULD BE THOUGHT.
