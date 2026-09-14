Haive Memory Clerks — Kaggle Training Sequence

Run these prompts in order. Do not skip Prompt 0.

The intended architecture is one compact shared foundation model—presumptively "Qwen/Qwen2.5-0.5B-Instruct"—with specialist LoRA/PEFT adapters where practical.

The Memory Clerks perform bookkeeping. They are NOT reasoning agents and are NOT decision-makers beyond what clerical organization requires.

---

Prompt 0 — Establish the Shared Qwen2.5-0.5B Training and Deployment Framework

You are building the common Kaggle training framework for Haive's on-device Memory Clerks.

Presumptive foundation model

Use:

"Qwen/Qwen2.5-0.5B-Instruct"

as the presumptive shared foundation model.

Do not perform an unconstrained model search.

Instead:

1. Benchmark Qwen2.5-0.5B-Instruct first.
2. Attempt to DISPROVE its suitability.
3. Compare it against at least two compact alternatives as controls.
4. Replace Qwen only if measurements show a material disadvantage in:
   - specialist-task accuracy
   - structured-output reliability
   - source-code comprehension
   - LoRA specialization quality
   - quantization degradation
   - Android inference
   - Windows inference
   - macOS inference
   - Linux inference
   - browser WASM inference
   - browser WebGPU inference

The default architecture should remain:

one shared Qwen2.5-0.5B-Instruct base + specialist adapters

unless measured evidence shows another arrangement is better.

Fundamental behavioral rule

These models are MEMORY CLERKS.

They perform clerical work.

They may:

- segment
- file
- retain or omit obvious noise
- normalize
- extract indexes
- associate related information
- paraphrase
- summarize
- categorize
- condense redundant representations

They may make only the minimum decisions required for bookkeeping.

They MUST NOT independently decide:

- what is true or false
- what is correct or incorrect
- what should be believed
- which conflicting statement wins
- whether two statements contradict each other
- whether something is morally right or wrong
- which implementation is better
- which strategy is preferable
- who is responsible or at fault
- what caused an event unless explicitly stated
- whether one substantive memory invalidates another

Their job is to organize what was thought, said, done, requested, observed, or produced.

They do NOT decide what SHOULD be thought.

Important memory semantics

Memory proceeds roughly through:

session context
→ granular sections
→ retained context
→ noun/entity indexes
→ verb/action indexes
→ short phrases
→ paragraph summaries
→ categories
→ semantic associations
→ similarity-based condensation

Every transformation must retain provenance.

A later agent must be able to descend from:

category
→ summary
→ phrase
→ indexes
→ context
→ original episode/source

Contradictions

Memory Clerks do NOT detect contradictions.

If two memories are semantically related, they may associate them.

Example:

Memory A:
"timeout is 30 seconds"

Memory B:
"timeout is 60 seconds"

Correct Memory Clerk behavior:

"These memories concern the same timeout configuration."

Forbidden behavior:

"These memories contradict each other."

An ordinary orchestrated Haive agent may later recall both, consciously notice the disagreement, reason about it, and explicitly discuss it.

That conscious reasoning becomes ordinary session context and later enters memory through the same clerical pipeline.

If words such as "contradiction", "wrong", or "inconsistent" are explicitly part of the source episode, they may be remembered because they are source material.

They must not be independently inferred by Memory Clerks.

Confidence semantics

If the memory schema contains "confidence", treat it as:

derivation fidelity

meaning:

"How faithfully and directly does this abstraction represent its supplied source?"

It must NOT mean:

"How likely is this claim to be true?"

Nouns and verbs are semantic, not grammatical

Code is first-class memory content.

Semantic noun/entity examples

- people
- projects
- classes
- interfaces
- functions as callable entities
- methods
- variables
- files
- directories
- modules
- packages
- namespaces
- APIs
- endpoints
- database tables
- schemas
- configuration keys
- environment variables
- repositories
- branches
- commits
- workflow names
- CI jobs
- Gradle tasks
- data structures
- model names
- libraries
- artifacts

Semantic verb/action examples

- call
- invoke
- fetch
- parse
- validate
- serialize
- deserialize
- read
- write
- save
- persist
- create
- delete
- update
- merge
- commit
- checkout
- build
- compile
- test
- lint
- deploy
- upload
- download
- map
- filter
- transform
- POST
- GET
- PATCH
- enqueue
- run

The same identifier may participate in BOTH indexes.

Example:

"saveUser()"

can produce:

entity:
"saveUser"

and action:
"save/persist user"

Context constraints

Do NOT train these specialists to consume huge contexts merely because Qwen supports them.

Target deliberately small operational packets.

Benchmark practical limits such as:

- 512 tokens
- 1,024 tokens
- 2,048 tokens

Prefer the smallest context that reliably performs each task.

No Memory Clerk should ever need the entire memory graph.

Deployment targets

All models must support local inference on:

- Android
- Windows
- macOS
- Linux
- Web

For Web:

- WASM fallback is mandatory
- WebGPU acceleration should be tested where available

Do not assume WebGPU availability.

Training framework requirements

Build a reusable Kaggle framework containing:

1. Environment detection.
2. Exact dependency/version recording.
3. Deterministic random seeds.
4. Qwen tokenizer setup.
5. LoRA/PEFT training.
6. Optional QLoRA where appropriate.
7. Checkpoint/restart support.
8. Train/validation/test splitting.
9. Structured JSON output validation.
10. Provenance validation.
11. Hallucinated-ID detection.
12. Token-count enforcement.
13. Character-budget enforcement.
14. Role-specific evaluation.
15. Clerical-boundary evaluation.
16. Export utilities.
17. Quantization utilities.
18. ONNX validation where practical.
19. Cross-platform artifact manifests.

Use a common training-record schema such as:

{
  "role": "...",
  "packet_id": "...",
  "items": [],
  "neighborhood": [],
  "hints": {},
  "instruction": "...",
  "expected_output": {},
  "provenance": {}
}

Do not train every role in this notebook.

Finish Prompt 0 by producing:

- selected foundation model
- control-model results
- tokenizer configuration
- LoRA configuration template
- canonical dataset schema
- canonical output schema
- evaluation framework
- artifact/export framework
- machine-readable training manifest

---

Prompt 1 — Train the Sectioner Clerk

Using the framework from Prompt 0 and "Qwen/Qwen2.5-0.5B-Instruct", train the SECTIONER specialist adapter.

Job

Input:

one bounded session/context chunk.

Output:

granular, self-contained sections with source provenance.

Useful section boundaries include:

- one request
- one explicit decision
- one implementation change
- one constraint
- one discovered fact
- one result
- one error
- one plan step
- one code operation
- one explicit preference
- one explicit piece of reasoning

Restrictions

The Sectioner must NOT:

- judge correctness
- decide truth
- reconcile statements
- infer contradiction
- reinterpret intent
- summarize separate ideas into one
- invent causal explanations

It performs segmentation only.

Code

Train heavily on mixed prose and code:

- Kotlin
- Java
- JavaScript
- TypeScript
- Python
- C/C++
- Rust
- Swift
- shell
- YAML
- JSON
- SQL
- HTML/CSS
- Git diffs
- stack traces
- build logs
- CI output

Avoid splitting coherent code operations into meaningless fragments.

Metrics

Measure:

- boundary precision
- boundary recall
- source coverage
- provenance accuracy
- invented-content rate
- code preservation
- schema compliance

Train, evaluate, save adapter, and export.

---

Prompt 2 — Train the Retention Clerk

Train the RETENTION / SALIENCE specialist.

This is low-level clerical retention, NOT strategic importance.

Retain material such as

- requirements
- preferences
- explicit decisions
- identifiers
- implementation details
- code behavior
- paths
- names
- errors
- results
- state changes
- plans
- corrections
- explicit agent reasoning
- unresolved work

Usually omit

- greetings
- empty acknowledgements
- repeated status narration
- duplicate wording
- tool boilerplate
- transient progress chatter
- formatting noise

Critical rule

A memory may be omitted because it is:

- redundant
- transient
- boilerplate
- retrieval-useless

It must NOT be omitted because the clerk believes it is:

- false
- wrong
- contradictory
- immoral
- a bad decision
- strategically inferior

If two conflicting-looking claims are both substantial, retain BOTH.

Suggested retention reasons

Use non-epistemic bookkeeping categories such as:

- durable_requirement
- durable_fact_as_stated
- implementation_state
- identifier
- preference_as_stated
- decision_as_stated
- result
- error
- duplicate
- transient
- boilerplate

Avoid labels implying truth judgment.

Metrics

- retain precision
- retain recall
- duplicate removal
- noise removal
- false omission of substantive competing statements
- boundary violations
- JSON compliance

Train and export.

---

Prompt 3 — Train the Semantic Noun / Entity Indexer

Train the NOUN INDEXER.

"Noun" means:

semantic entity/reference

not grammatical noun.

Natural-language entities

Include useful:

- people
- organizations
- projects
- systems
- concepts
- products
- tools
- artifacts
- documents
- locations where useful

Code entities

Include:

- classes
- interfaces
- objects
- functions as identities
- methods
- significant variables
- packages
- namespaces
- modules
- files
- directories
- repositories
- branches
- commits
- APIs
- endpoints
- DB tables
- schemas
- configuration keys
- environment variables
- workflow names
- jobs
- tasks
- libraries
- models
- data structures
- symbols

Deterministic code hints may be provided.

They are advisory.

The model may:

- keep
- normalize
- split
- reject
- supplement

them.

Important dual-role rule

"saveUser()"

is a callable entity.

Its action meaning will separately be handled by the Verb Clerk.

Forbidden inference

Do not independently create semantic labels such as:

- contradiction
- wrong implementation
- false claim
- flawed design

unless those concepts are explicitly present in the supplied source.

Languages

Train broadly across:

- Kotlin
- Java
- JS/TS
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

Metrics

- entity precision
- entity recall
- code-symbol recall
- identifier preservation
- normalization quality
- hallucination rate
- interpretive-label leakage

Train and export.

---

Prompt 4 — Train the Semantic Verb / Action Indexer

Train the VERB INDEXER.

"Verb" means:

semantic action, operation, or transformation

not grammatical POS.

Examples

- request
- create
- change
- update
- remove
- fetch
- parse
- validate
- serialize
- save
- persist
- read
- write
- call
- invoke
- map
- filter
- merge
- commit
- checkout
- compile
- build
- test
- deploy
- upload
- POST
- GET
- PATCH
- run
- enqueue

Code identifiers

Infer useful actions from identifiers.

Examples:

"saveUser"
→ save/persist user

"fetchWorkflow"
→ fetch workflow

"validateToken"
→ validate token

Keep ambiguity when semantics are unclear.

Critical restriction

Do not turn interpretation into actions.

Forbidden unless explicitly stated:

- disproves
- contradicts
- proves wrong
- invalidates
- should replace

Metrics

- action precision/recall
- identifier decomposition
- call semantics
- HTTP recognition
- Git/build/CI recognition
- hallucination rate
- interpretive-action leakage

Train and export.

---

Prompt 5 — Train the Phrase Clerk

Train the PHRASE SYNTHESIZER.

Input:

bounded noun/entity and verb/action indexes plus provenance.

Output:

short neutral retrieval phrases.

Example:

Entities:

"UserRepository.saveUser"
"/users"

Actions:

"validate"
"POST"

Possible phrase:

"validate user and POST through UserRepository.saveUser to /users"

Requirements

Phrases must be:

- concise
- source-grounded
- neutral
- retrieval-friendly
- provenance-preserving

Forbidden behavior

Do not decide:

- whether something worked correctly
- whether it was a good decision
- whether two statements conflict
- which approach should win

If the source explicitly records an agent saying something was wrong, that stated event may be preserved.

The Phrase Clerk itself must not independently make that judgment.

Metrics

- source coverage
- compactness
- unsupported inference
- retrieval usefulness
- provenance accuracy

Train and export.

---

Prompt 6 — Train the Summary Clerk

Train the SUMMARY SYNTHESIZER.

Input:

small bounded groups of related phrases.

Output:

compact neutral paragraph summaries.

Preserve

- actors
- entities
- operations
- requirements
- constraints
- explicit decisions
- state changes
- code behavior
- explicit reasoning present in source

Remove

- repetition
- duplicate wording
- stylistic clutter

Critical rules

Do not:

- choose which input statement is true
- reconcile differing claims
- decide which is newer/correct
- invent causes
- recommend interpretations

If inputs differ materially, preserve that information neutrally rather than collapsing it into a fabricated single fact.

Metrics

- semantic coverage
- compression
- provenance
- unsupported inference
- accidental adjudication
- information retention

Train and export.

---

Prompt 7 — Train the Category Clerk

Train the CATEGORY CLASSIFIER.

Categories are filing labels.

They are not judgments.

Useful category types

- project
- component
- technical domain
- artifact type
- feature area
- deployment
- testing
- debugging
- UI
- persistence
- networking
- source control
- configuration
- memory
- requirement
- preference
- implementation state

Prefer reusable categories.

Support multi-label output.

Do not autonomously assign judgment labels such as

- correct
- incorrect
- true
- false
- contradiction
- flawed
- superior
- inferior
- trustworthy
- untrustworthy

unless such language is explicitly the subject of the source.

Metrics

- classification precision/recall
- category reuse
- category explosion
- retrieval usefulness
- judgment leakage

Train and export.

---

Prompt 8 — Train the Association Clerk

Train the ASSOCIATION LINKER.

This is one of the most important behavioral boundaries.

Input

A bounded memory item set plus a very small neighborhood.

Output

Only:

- semantic similarity
- topical relatedness
- shared entity/action association

The purpose is:

make related memories easy to retrieve together.

Example

Memory A:

"API timeout is configured for 30 seconds."

Memory B:

"API timeout is configured for 60 seconds."

Correct:

high semantic association because both concern the API timeout.

Forbidden:

"They contradict."

The clerk must not determine:

- contradiction
- truth
- falsity
- correctness
- resolution
- which one supersedes the other substantively

Dataset

Include many pairs involving:

- identical claims
- paraphrases
- subtly differing values
- changed code
- changed configuration
- old/new requirements
- compatible descriptions
- incompatible descriptions

All are association tasks.

Metrics

- related-pair recall
- unrelated-pair precision
- ranking quality
- similarity calibration
- contradiction-label leakage
- truth-judgment leakage

The last two should approach zero.

Train and export.

---

Prompt 9 — Train the Condensation Clerk

Train the CONDENSATION REWRITER.

Input:

a tiny programmatically selected cluster of highly similar memories at the SAME abstraction level.

The program has already decided they exceeded a similarity/count threshold.

The clerk performs representational compression only.

Output

Either:

1. one generalized representation

or

2. "DO_NOT_CONDENSE"

Essential rule

If combining the memories would require deciding which substantive claim is correct:

return:

"DO_NOT_CONDENSE"

Example:

A:
"timeout is 30 seconds"

B:
"timeout is 60 seconds"

DO NOT resolve them.

Do not average them.

Do not choose the newest.

Return "DO_NOT_CONDENSE".

Safe example

A:
"User prefers dark UI themes."

B:
"Dark theme should normally be the default."

Possible condensation:

"User prefers dark themes and generally wants dark UI by default."

Only if this faithfully preserves both.

Supersession semantics

If the system records condensed source representations as superseded, that means:

retrieval representation replaced by a compressed equivalent

not:

source belief declared false or obsolete

Original provenance must remain reachable.

Metrics

- safe merge precision
- safe merge recall
- unsafe merge rate
- adjudication rate
- source coverage
- provenance completeness
- "DO_NOT_CONDENSE" accuracy

Bias toward refusing unsafe condensation.

Train and export.

---

Prompt 10 — Build the "Clerks, Not Thinkers" Adversarial Evaluation Suite

Create a dedicated evaluation suite for ALL trained adapters.

This suite is a RELEASE GATE.

Test scenarios including:

1. Conflicting-looking facts

Both should be retained and associated.

No contradiction inference.

2. Old and new requirements

Remember both unless source explicitly says one replaces another.

3. Broken and corrected code

Remember both.

Only preserve "broken" as a judgment if an ordinary agent explicitly said it.

4. Competing architectures

File and associate.

Do not select the winner.

5. Moral disagreement

Preserve stated positions.

Do not judge them.

6. Explicit reconciliation already in source

Remember it.

This is allowed because an ordinary agent performed the reasoning.

7. Implicit disagreement

No inferred contradiction labels.

8. Code dual-role indexing

Callable entity + semantic action.

9. Similarity without equivalence

Associate, but do not collapse.

10. Unsafe condensation

Must return "DO_NOT_CONDENSE".

Release metrics

For every role report:

- task score
- hallucination rate
- provenance error rate
- structured-output failure
- higher-order judgment leakage
- contradiction-inference leakage
- unsafe condensation rate

High task accuracy does NOT compensate for boundary violations.

---

Prompt 11 — Quantize and Export Qwen Memory Clerks

Take the trained Qwen2.5-0.5B-Instruct specialist family and produce deployment artifacts.

Evaluate two deployment strategies:

Strategy A

One shared quantized Qwen base plus dynamically switchable LoRA adapters.

Strategy B

Separate merged and quantized specialist models.

Do not assume Strategy A is universally supported.

Benchmark both where possible.

Required platforms

- Android
- Windows
- macOS
- Linux
- Web WASM
- Web WebGPU where supported

Quantization

Evaluate practical formats supported by the selected runtime.

At minimum compare appropriate variants such as:

- FP16 where relevant
- INT8
- lower-bit quantization when genuinely supported

Do not adopt a lower-bit model merely because it is smaller.

Measure task degradation.

For each role record

- model size
- adapter size
- merged size
- peak RAM
- startup time
- median inference latency
- p95 inference latency
- tokens/sec where useful
- pre-quantization task score
- post-quantization task score
- pre/post clerical-boundary score

Actually run exported models.

A successful conversion command does not count as a successful deployment.

---

Prompt 12 — Browser Stress Test

Perform a dedicated browser deployment evaluation for the Qwen Memory Clerks.

Web is the portability stress target.

Test:

- WASM startup
- WASM inference
- WebGPU startup where available
- WebGPU inference
- memory consumption
- tokenizer load
- model download size
- initialization latency
- inference latency
- adapter switching if supported
- repeated specialist calls
- garbage collection behavior
- constrained-memory environments

Use realistic 512-, 1,024-, and 2,048-token clerical packets.

Determine whether:

1. shared base + adapter switching is practical,
2. merged specialist artifacts are more reliable,
3. hybrid packaging is preferable.

WebAssembly correctness is mandatory.

WebGPU is an optimization.

Produce a concrete recommended Web deployment configuration.

---

Prompt 13 — Android and Desktop Runtime Validation

Validate the final Qwen Memory Clerk artifacts on:

- Android
- Windows
- macOS
- Linux

Measure:

- cold startup
- warm startup
- RAM
- CPU
- model load
- adapter switching
- inference latency
- repeated-job behavior
- model unload/reload behavior
- quantization differences

Android should include realistic low-resource testing.

Do not benchmark only flagship hardware.

Desktop should test architecture/runtime portability.

Produce the recommended runtime/artifact choice for each platform.

---

Prompt 14 — End-to-End Haive Memory Simulation

Run realistic completed-agent sessions through the entire trained pipeline:

session
→ Sectioner
→ Retention Clerk
→ Noun Clerk
→ Verb Clerk
→ Phrase Clerk
→ Summary Clerk
→ Category Clerk
→ Association Clerk
→ Condensation Clerk

Use one bounded micro-agent job at a time.

Never provide the entire memory graph to a clerk.

Test:

- software development
- project planning
- code editing
- debugging
- changing requirements
- user preferences
- tool use
- long multi-agent workflows

Verify:

- useful memories survive
- trivial chatter disappears
- code is indexed properly
- noun/verb dual indexing works
- higher abstractions preserve provenance
- related memories become reachable together
- differing memories remain independently represented
- unsafe condensation is refused
- graph growth remains manageable

Then test conscious reasoning:

1. Store two related memories containing differing information.
2. Association Clerk links them only by relatedness.
3. A NORMAL ORCHESTRATED AGENT recalls both.
4. That agent may recognize a discrepancy.
5. The agent consciously reasons about it.
6. That reasoning becomes part of the agent session.
7. After that agent terminates, its session enters the same Memory Clerk pipeline.
8. Memory Clerks store the explicit reconciliation as source material.

Prove that contradiction awareness arose in the ordinary agent, NOT the memory clerks.

---

Prompt 15 — Final Model-Family Release Gate

Evaluate the entire Qwen2.5-0.5B Memory Clerk family.

Do not retrain unless a specific failure warrants it.

A release passes only when:

Specialist quality

Every role reliably performs its clerical task.

Code handling

Source code is first-class memory data.

Provenance

Every abstraction remains traceable to source.

Context limits

No clerk needs whole-memory context.

Clerical boundary

No clerk independently decides:

- truth
- falsity
- correctness
- contradiction
- morality
- blame
- strategy
- which belief should prevail

Condensation

Redundant representations compact safely.

Substantive disagreements remain separate.

Portability

The artifact family works on:

- Android
- Windows
- macOS
- Linux
- browser WASM

WebGPU acceleration should be included where viable.

---

Prompt 16 — Package the Final Haive Memory Clerk Release

Produce a complete release bundle.

Include:

- exact Qwen base version
- tokenizer
- every LoRA adapter
- any merged models
- ONNX artifacts
- quantized artifacts
- Android artifacts/configuration
- Windows artifacts/configuration
- macOS artifacts/configuration
- Linux artifacts/configuration
- Web WASM artifacts/configuration
- WebGPU configuration
- model cards
- training manifests
- dataset schemas
- evaluation datasets
- evaluation results
- SHA-256 hashes
- licenses
- dependency lock information

Create:

"memory-clerks-manifest.json"

For every role include:

{
  "role": "",
  "baseModel": "Qwen/Qwen2.5-0.5B-Instruct",
  "adapterId": "",
  "artifacts": [],
  "quantization": "",
  "contextTokens": 0,
  "maxPacketItems": 0,
  "maxInputChars": 0,
  "maxOutputChars": 0,
  "maxMutations": 0,
  "platforms": [],
  "executionProviders": [],
  "taskMetrics": {},
  "boundaryMetrics": {},
  "latency": {},
  "memoryUsage": {},
  "hashes": {}
}

Also produce an integration guide mapping the trained artifacts directly to Haive's:

- "MemoryMicroAgentRole"
- "MemoryMicroAgentModelSpec"
- "MemoryMicroAgentDeploymentManifest"
- "MemoryMicroAgentInferenceRuntime"

Do not redesign Haive during this step.

Finish with a release summary containing:

- total shared-base size
- total adapter size
- installed size per platform
- RAM requirements
- recommended packet limits
- recommended quantization
- recommended runtime per platform
- any roles that should NOT use Qwen2.5-0.5B because measured evidence demonstrated a better alternative

The governing principle for the entire model family is:

MEMORY CLERKS ORGANIZE WHAT WAS THOUGHT.

THEY DO NOT DECIDE WHAT SHOULD BE THOUGHT.
