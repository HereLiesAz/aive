# The Aive: collective cognition

**Status:** Owner-directed architectural target, not a claim that the target is implemented. The canonical execution backlog is [TODO.md](../../TODO.md#collective-cognition--hive-mind-transition). The existing workflow runtime remains supported throughout migration. No milestone is complete without production wiring and automated verification; live acceptance is tracked separately.

## 1. Product direction and rationale

The Aive is a hive mind: the intelligence is the collective, not the Queen, not an individual model, and not the workflow. Specialists are its cognitive organs. Their intentional exchanges are its internal discourse; their distinct capabilities, shared experience and capacity for disagreement constitute its intelligence. The Keeper prompts The Aive as one coherent, accomplished conversational intelligence. The Keeper should not need to plan a workflow, choose a committee, relay messages, or arbitrate routine disagreements to get an answer.

The former architecture starts with an objective, constructs a DAG, assigns workers, and follows execution state. This was valuable because it made dependencies, effects, approval, verification, budgets and recovery explicit. Keep those properties. Change the direction of causality: the collective reasons about an objective, decides what work is necessary, and constructs or revises a governed workflow when work requires it. A workflow is an instrument of action, not the collective's thought process or lifetime.

This is neither a single sovereign planner with disposable workers nor a perpetual group chat. The target is a persistent, decentralized community of cooperating specialists with bounded autonomy, independent judgments and a coherent external voice. A specialist can initiate a question, propose a course of action, recruit help, challenge evidence, resume a conversation, or decline work. It need not wait for the Queen to assign every act. The Queen facilitates, coordinates and resolves process deadlocks, but is not the arbiter of truth or a source of permission.

This is a functional cognitive architecture, not a claim of sentience or access to providers' private chain of thought. Only actual model-authored messages and observed runtime events may be represented as activity.

## 2. Non-negotiable boundaries

The [threat model](../THREAT_MODEL.md), [policy decision point](POLICY.md), and existing memory contracts remain normative. Collective design does not relax any of them.

- Model outputs, peer messages, memory, tool results and externally supplied objectives are untrusted content. Transport authenticity establishes who sent something, not whether it is true.
- No specialist, facilitator, committee, habit, imported skill, schedule, remote client or majority can grant itself authority. The existing Keeper approvals, tool allowlists, source/provider constraints, context-taint rules, effect gates and spend stops remain engine-owned.
- All inference and tool work uses metered, governed adapters. Background activity is off unless authorized, bounded in time, tokens, cost, concurrency and depth, cancellable, and subject to source availability and privacy restrictions.
- Preserve independent verification, dedicated branch/workspace isolation, completion only on observed executor completion, durable correlation and restart safety.
- Memory clerks organize and associate, never judge truth. No new memory command or model-directed write/delete API. Preserve passive cue-first recall, progressive depth, contradiction retention and the Keeper's `/remindme` semantics. The cognitive layer reasons over relevant recalled material; do not inject recalled text indiscriminately into the planning model. The original planner-memory boundary exists to prevent injection and unauthorized data flow.
- No synthetic telemetry, fabricated dissent, simulated consensus represented as real, or invented internal monologue.
- Provider changes, cross-project sharing, remote agents, export and cross-device transport require their own explicit consent. A connection or a role assignment alone is not consent to disclose conversation content.

## 3. Lifetimes and ownership

| Entity | Lifetime and identity | Responsibility |
| --- | --- | --- |
| Collective | installation/account and authorized project partitions | Persistent cognitive state, policy and capabilities; not a run |
| Specialist | stable specialist ID, anchored initially to existing role identity | Expertise, instructions, preferences, permissions, skills, relationships and history |
| Provider session | ephemeral execution identity | A particular inference or managed session; may be replaced without changing specialist identity |
| Conversation | durable conversation ID, Keeper and project scope | User turns, responses, unresolved questions, selected evidence |
| Deliberation | bounded episode ID | Question, participants, proposals, objections, evidence, decision and revision history |
| Message | immutable message ID and correlation IDs | Intentional exchange with provenance and delivery state |
| Workflow/run/task | existing definitions and IDs | Governed execution of an authorized objective |
| Artifact/evidence | stable reference, provenance and version | Observed result or source; not automatically trusted |

An individual role template, installed specialist instance, task assignment and provider session must not be conflated. Several specialists may share a role or model without sharing identity. A specialist may have several assignments, or none. A provider change must not erase its mail, expertise or history. Migration from role IDs must preserve existing configurations, pins, references, instructions and historical runs. Never infer that historical task IDs are persistent specialist IDs.

The current peer admission path uses the existing persisted role catalog as its identity anchor, checks current enabled state and provider pin on enqueue and delivery, and advertises only unique live eligible same-run peers (up to 16) at prompt construction. A missing or unreadable catalog disables peer traffic. This does not yet establish multiple independent specialist instances per role. Cross-run disclosure is separately authorized by the Keeper.

The specialist catalog is durable. Model sessions are created on demand, not kept alive for appearance. Idle specialists are available to be awakened; idle does not mean a provider is billing. Retirement disables routing and preserves the historical identity subject to retention policy. Deletion, export and revocation must have explicit semantics.

## 4. Communication fabric

Extend existing managed sessions, the initial peer consultation implementation and [Duplex MCP](DUPLEX_MCP.md). Do not create a parallel transport or a second memory bank. The current `CONSULT` protocol is a bounded transitional interface; it is not the final cognition protocol.

A versioned message envelope should carry:
- Immutable message ID, schema version, authenticated sender specialist ID and actual invocation/session identity.
- Recipient specialist ID or bounded recruitment query, project/conversation/deliberation scope, correlation ID, reply-to ID and causal parent IDs.
- Intent: Inquiry, Observation, Hypothesis, Objection, Proposal, Request, Verdict, Resolution; delivery/control metadata is distinct from these semantic intents.
- Bounded human-readable content, typed references to artifacts/evidence, provenance and sensitivity classification, creation/expiry times.
- Delivery state and reason: queued, claimed, accepted by transport, acknowledged by receiver where available, rejected, expired, cancelled, or uncertain. These states do not assert that the receiver read or acted on the message.

The engine authenticates envelope metadata rather than trusting a model to identify itself. Validate schema and references before enqueueing. Keep redacted content, permission checks, durable IDs and state transitions. Do not parse arbitrary prose, hidden reasoning or untrusted tool results as executable messages. An explicit structured intent can request work; it cannot execute it.

Mailboxes persist independently of live sessions. Delivery is asynchronous, correlated, bounded and resumable. Define atomic claims, lease expiry, acknowledgements and replay handling; do not advertise exactly-once remote delivery without an idempotent receiver. Unknown outcomes are recorded, not blindly replayed. A sender continues useful work while waiting, or yields its slot. A response wakes a waiting specialist through the same admission policy as any new work. Expire stale requests and preserve cancellation, revocation, provenance and retention across restarts. Isolate tenants/projects/conversations. Cross-run exchange is a separately consented data flow, not an automatic consequence of matching a role name. Enforce recipient eligibility again at delivery, including current provider and permissions.

The current implementation is same-project/provider, advisory, repository-free, explicitly tool-free, and same-run by default; it persists claims before transport, retains accepted/rejected/uncertain outcomes during the 24-hour retention window, and does not guarantee delivery. A claim interrupted before a receipt is recorded remains claimed and is not replayed. Typed advisory intents, deterministic scoped message IDs, and reply ancestry validated against the exact receiving session are implemented; structured evidence references and independent specialist identity remain outstanding. The Keeper can authorize a directional route between exact existing runs and roles in one project on one provider under Projects. The PEER-RUN protocol requires that grant on enqueue, claim and immediately before transport. A revoked grant generation cannot authorize old queued notes after re-grant. Replies in the opposite direction need their own grant. Imports and data deletion discard affected local permissions and retained mail. Revocation cannot retract an already-started send. Cross-project/provider disclosure remains prohibited. Expanding scope requires policy and integration tests before enabling it.

## 5. Attention and recruitment

Attention is selective allocation of cognitive capacity, not a broadcast bus. Rank candidates by relevant expertise, task fit, permitted data access, relationship and prior collaboration, current load, budget, availability, source independence and reliability in the relevant domain. Reuse existing routing, role catalogs, genealogy and provider health. Explain routing decisions using inspectable signals. Unknown expertise and reliability are unknown, not zero or perfection.

A specialist can request a specific peer or describe an unmet expertise need. Eligible specialists can volunteer, decline, suggest another specialist, or form a temporary working group. The collective admits requests against consent, budget, deadline, concurrency and recursion limits. A deterministic fallback selects from permitted candidates when model routing fails. A specialist cannot recruit itself indefinitely, repeatedly wake an unavailable peer, bypass provider pins, or enroll an external agent automatically.

Recruitment produces a request or an authorized advisory inference, not an effectful task. More consequential work becomes an explicit workflow proposal. Existing workflow dependencies remain distinct from conversational causal links. Scheduler fairness, backpressure, expiry, loop detection, duplicate suppression, escalation and cancellation are required. The Keeper can pause the collective, revoke an assignment or set quiet/idle restrictions.

## 6. Evidence and collective deliberation

A deliberation is a bounded, attributable exchange, not a vote to establish facts.

1. Frame a question, alternatives, acceptance criteria, scope, budget and relevant evidence.
2. Obtain independent initial assessments before presenting a facilitator's favored answer or another participant's proposal.
3. Share assessments and references under permission checks; distinguish observed facts, source claims, hypotheses, recommendations and unresolved questions.
4. Permit challenges, requests for missing evidence and revised proposals. Tests and research use ordinary governed execution, not invented findings.
5. Evaluate alternatives using evidence quality, relevant expertise, verification and the existing genealogy graph. Correlated opinions are not independent corroboration; same-model answers, copied claims and common sources must be identified, not counted as a larger independent majority.
6. Record support, disagreement, reasons, uncertainty, missing evidence and the decision rule. An agreement about a plan is not a proof of truth or authorization.
7. Reach a justified advisory conclusion, a bounded revision, an explicit unresolved outcome, or Keeper escalation. Preserve substantive dissent even when a majority prefers another answer.
8. Only the execution policy can admit the resulting action. Existing unanimous two-party agreement gates, approval requirements and investigation limits remain intact.

Reuse the dispute runtime, [genealogy governance](GENEALOGY_GOVERNANCE.md), plan review and artifact provenance; extend them to multi-party questions. Missing participants or unpriced calls do not silently become agreement. Weighting must be domain-specific, evidence-backed, auditable and resistant to sybil identities and self-reinforcing reputation. Never allow popularity to override evidence, a hard policy constraint, or material dissent about safety. Define quorum, abstention, timeout, deadlock and escalation behavior; no implicit unanimous result from an incomplete response set.

The Queen may frame, synthesize, request clarification or facilitate revision. Leadership can shift to the specialist best qualified for the current problem; facilitation and verification should be separate when possible. The Queen may still coordinate execution through established policy paths. She cannot manufacture facts, override dissent, alter locked permissions or declare an external effect approved.

## 7. From thought to action

Advisory reasoning can finish with a direct answer and no workflow. When actions or verification are necessary, the collective proposes a plan using existing orchestration, validated DAGs, resource selection, provider routing and policy. Specialists can recommend changes or request assistance while execution is active. Amendments retain completed work and provenance, preserve existing approval boundaries, and undergo the existing graph/requirements validation. Do not silently replace a plan the Keeper approved or repeat an uncertain external dispatch.

A workflow may express dependent tasks, independent evidence collection, testing, nested workflows and explicit human decisions. Its lifecycle is independent of the collective's existence. A failed or completed run does not erase the specialists or their conversations. Outcomes inform subsequent reasoning through permitted memory and evidence pathways.

## 8. One external intelligence

The primary interaction is a durable conversation with The Aive. The Keeper's prompt becomes an objective/question, not necessarily a workflow launch. A conversation coordinator maintains turn ordering, scope, cancellation and unresolved matters; it is not a sovereign reasoning model. The collective chooses whether to answer directly, consult, investigate, deliberate or propose governed action.

The outward response synthesizes the collective's actual findings in a consistent voice. It should include the answer, important uncertainty or dissent, sources/artifact references where applicable, and actual work status. It must distinguish proposed, running, blocked, failed and verified-complete work. It must not pretend a silent specialist agreed or an action was performed. The Keeper can inspect the actual consultation and decision record without being required to read a committee transcript.

Use streaming for actual available output or progress, with explicit distinction between interim observations and final answer. Preserve conversational continuity and cancellation across reload, reconnect and platform changes. Human approval prompts must remain unmistakable and use existing Keeper surfaces. An ordinary conversational affirmative is not a bypass around a formal gate.

Expose The Aive as one authenticated model via an OpenAI-compatible API (initially a bounded chat/completions surface and model discovery). Support conversation mapping, streaming, cancellation, error and stop reasons, usage reporting, request limits and authorization. Reuse existing server/control and policy boundaries; no second execution engine. Remote requests remain externally sourced, cannot lift approvals or spend stops, and must be off by default where the existing server boundary requires it. Do not expose private model reasoning, unrelated memory or credentials. Compatibility claims require protocol tests with real clients.

## 9. Procedural intelligence and Hermes adoption

Adopt useful Hermes-Agent patterns as capabilities, not an imported second orchestration system. Evaluate upstream implementation/licensing and adapt through existing interfaces.

| Adoption target | Aive integration |
| --- | --- |
| Persistent specialist profiles and reusable skills | Specialist catalog, existing role configurations and procedural memory |
| Delegated independent subagents and background work | Governed advisory recruitment and scheduler; isolated sessions, cancellation, bounded budgets |
| Durable task/message collaboration | Mailbox, correlation, causal graph, work and evidence references; no task board as cognitive authority |
| Programmatic tool sequences | Existing tool contracts, script sandbox and validated procedural bindings; no arbitrary recalled code execution |
| Session search, context compression and recall | Existing memory association, context packer, source-backed summaries and retention controls |
| Scheduled responsibilities and consolidation | Existing schedules, explicit consent, idle admission and budgets |
| Isolated execution environments and worktrees | Existing workspace/executor isolation and approval policies |
| Unified messaging gateway | Existing managed session and duplex transports, with permission-aware identity and scope |

A procedure is remembered experience of how to do something. Repeated useful experiences become habits; low-cost, reliable, approved habits may replay through existing mechanisms. Keep the original distinction: formation through repetition/consolidation is not a permission promotion. Strength affects recall; policy determines what executes. Preserve version, provenance, inputs, expected outputs, limits, observed outcomes, failure modes and correction history. Only a Keeper-approved current version can execute through the existing habit replay policy; mutations remain ordinary gated steps. No autonomous privilege increase, self-modification, silent code installation or model training merely because a skill performed well.

A reflex is the cheapest bounded safe response to a recognized condition, not permission to act outside policy. Unexpected input, changed permissions, version drift, failure, disagreement or uncertainty return the task to deliberate reasoning. Tests must be independently derived, and learned behavior must be reversible. Reuse the existing [procedural memory](PROCEDURAL_MEMORY.md), [habit formation](HABITS_AS_MEMORY.md), and [self-improvement](SELF_IMPROVEMENT.md) contracts, including their superseding memory-model notes.

## 10. Idle cognition and consolidation

The collective may carry authorized ongoing responsibilities: review unresolved questions, surface relevant cues, consolidate experience, maintain skills and check scheduled conditions. This is a finite, event-driven maintenance loop, not permanent billable thought. A paused/offline system has no fictitious cognition. On unsupported platforms, state the limitation and resume only permitted work on return.

Use existing banking/attention rules. Memory clerks retain and organize observed experience, not newly invented beliefs; contradictions remain available. Active reasoning may resolve a contradiction and its resolution is banked with provenance like other experience. Preserve deletion/retention choices and isolation. No automatic external transmission during idle consolidation.

## 11. Swarm UI and observable reality

The world depicts the living collective, not an animated workflow masquerading as intelligence. Preserve [the ecology's binding rule](../Ecology-and-the-Swarm.md). Persisted specialist identities exist when not assigned. A conversation can show actual attention shifts, message arrivals, consultation, recruitment, deliberation, waiting, execution and verified results.

Cognitive links and workflow dependency edges must be visually and semantically distinct. A cognitive link is caused by actual communication/evidence events; a workflow dependency still describes execution order. Show queued vs delivered vs answered, uncertainty vs agreement, and waiting vs active inference. Provider thinking is not the same as deliberate peer speech. Respect private reasoning, retention and redaction. Do not fabricate events to keep creatures moving. Existing idle animation remains decorative only.

Semantic zoom: whole collective, active conversation/working group, specialist, message/evidence inspection, workflow execution. The Keeper can inspect and govern without operating a flowchart for ordinary interaction. Build mode still supports explicit workflow authoring and rehearsal; rehearsal must be identified as simulation, not cognition or actual work.

## 12. Implementation order and acceptance

The TODO is the canonical work queue; the following phases give dependency order and acceptance gates. Implement vertical slices through the existing production runtime. Do not count a class, interface, mock, prompt change or a TODO as delivery.

| Phase | Acceptance evidence |
| --- | --- |
| A. Identity and mailbox | Stable specialist after restart/provider replacement; authenticated correlated messages; queue, expiry, cancellation, delivery and privacy tests; no duplicate unsafe dispatch; migration of old role/run data |
| B. Attention and recruitment | Live specialist initiates a consultation, eligible peer receives it, replies, sender resumes; bounded wake and admission; absent/revoked peer and caps tested; no Queen-created DAG needed for a purely advisory exchange |
| C. Deliberation | Independent proposals precede facilitation; multi-party evidence/provenance and genealogy; unresolved dissent visible; missing replies cannot approve; effects still gated |
| D. Governed action | Collective-generated and revised plans pass existing validation; effect, tool, isolation, cancellation, restart and metering tests; real execution and verification distinguish results from claims |
| E. One voice | Keeper sends an ordinary prompt and receives a coherent answer with warranted uncertainty and actual work status; continuity, concurrent turns, streaming and approval behavior tested |
| F. Skills and continuity | Receipt-backed procedure/habit formation and versioned authorized replay; failure returns to reasoning; bounded idle maintenance respects memory rules and retention |
| G. UI and API | Actual events drive field/inspection; conversation and workflow views coexist; OpenAI-compatible client tests, authentication, usage, streaming, cancellation and external-input policy tests |
| H. Live acceptance | End-to-end supported-platform tests, interrupted delivery, revocation, budget exhaustion, conflicting evidence, correlated consensus, provider failure, recovery, and Keeper-visible verified results |

Add regression tests for every new data flow, maintain KDocs and architecture documents, preserve old workflows and stored data, and verify each platform through its supported runtime. The prior release scope is not silently changed by this document; explicitly decide release sequencing with the Keeper. A task may be marked complete only when its real caller and automated coverage exist. Record which acceptance evidence remains outstanding.

## 13. Current status

Initial peer consultation and durable scoped mailboxes are implemented in the managed-session path. Independent proposals are being added to live dispute facilitation. These are narrow transitional pieces, not a complete hive mind. Cross-run consent is implemented for exact directional routes, pending live acceptance. Independent specialist lifetime, broader routing, complete message protocol, multi-party deliberation, external voice, learning extensions, cognitive telemetry and API compatibility remain open until demonstrated. Keep status in TODO.md and update this section when verified.
