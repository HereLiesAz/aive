# Aive procedural memory: reflexes, habits, and contextual-memory integration

Status: **Architecture proposed; initial non-executable procedure definitions, in-memory catalog and contextual provenance filtering implemented.** Execution, durable SQL persistence, promotion, automatic recall and duplex procedure exchange are not implemented. This design extends the normative [Memory-layer](../Memory-layer.md), [Memory Banking and Attention](MEMORY_BANKING_AND_ATTENTION.md), [GRIP](GRIP.md), [Role Execution Sources](ROLE_EXECUTION_SOURCES.md), and [Duplex MCP](DUPLEX_MCP.md) contracts. It does not change their existing authority boundaries.

## 1. The four pillars

| Pillar | Responsibility | Output |
| --- | --- | --- |
| Recollection | Find relevant episodic/contextual evidence and knowledge by cues, GRIP, and lineage. | Recalled experience and provenance. |
| Consolidation | Organize, associate, summarize, and condense evidence without independent judgment. | Durable contextual memories and evidence graph. |
| Reflexes | Reuse one small, deterministic, verified operation with bounded parameters. | A fast, auditable procedure invocation. |
| Habits | Reuse a versioned sequence/graph of reflexes and governed executor steps with checkpoints. | A resumable, monitored procedure run. |

Reflexes and habits are both **procedural memory**. They are learned *from* episodes and *retrieved with* contextual memory, but only the task/executor layer performs them. An agent can choose a procedure or intentionally fall back to ordinary reasoning. No remembered procedure becomes an instruction or permission by virtue of being recalled.

## 2. Target architecture

```text
             Provider agents / workflow tasks / executors
                     |                  ^
       execution receipts + outcomes     | compact procedure references
                     v                  |
             Procedural trace capture   |
                     |                  |
  existing episodes --+--> Contextual memory (GRIP, cues, provenance,
                     |     attention, workflow lineage, consolidation)
                     v                  |
          Candidate reflex / habit extraction
                     |
          Typed normalization and parameterization
                     |
          Independent validation / approval / version promotion
                     |
        Procedural library + immutable blobs + run journal
                     |                       ^
       matching + eligibility               | result / verification
                     v                       |
         Procedure offer --> Policy gate --> Existing task executor(s)
                     ^                       |
                     |                       |
             Aive duplex MCP adapter <------+
                     ^
           Agent-neutral MCPserved broker
                 /mcp/a     /mcp/b
```

The duplex server is a **two-party transport**, not a memory store, trainer, or delegated tool executor. Aive attaches one equal peer per explicitly configured pairing. Both peers may propose procedures, challenge assumptions, request independent verification, and share outcomes without making either peer a privileged controller.

## 3. Capture: structured, evidence-bearing traces

Do **not** infer runnable procedures from arbitrary chat, prompts, model thinking, or a summary alone. Capture structured receipts at the actual executor/tool boundary:

- invocation ID, workflow/project/task/role/session identities, executor type and version;
- tool/operation ID, structured arguments after secret redaction, source artifact digest;
- environment fingerprint: platform, tool version, repository/branch constraints, relevant feature capabilities;
- start, result, exit/terminal status, error classification, time, retry policy, side-effect class;
- assertions and postconditions actually checked, acceptance criteria, artifact hashes;
- provenance: episode IDs, source artifacts, task run, reviewer, and any human approval.

Use the existing `MemorySessionObserver` to retain the contextual narrative. Add a separate `ProceduralTraceObserver` around `TaskExecutorIntegration.dispatch/reconcile`, executor results, and explicit provider-tool receipts where supported. `AgentEvent.ArtifactProduced` is supplemental evidence; it is **not** a reliable stand-in for all tool calls or successful verification. Do not claim full provider-tool capture where the provider does not expose structured tool events.

Capture is off whenever memory is disabled, subject to privacy, opt-in, data limits, and retention. Secrets, tokens, personal data, stdout with credentials, and unbounded artifacts must not enter macro definitions. Derive only allowed parameter schemas from redacted structured invocations.

## 4. Storage and provenance

Implement a **separate, versioned procedural sidecar store per workflow bank** alongside the existing `MemoryStore` graph. Do not append executable payloads as free-form `MemoryNode` text or widen `MODEL_WRITABLE_RELATIONS`. Sidecar entities:

- `ProcedureDefinition`: immutable `id`, `version`, `kind = Reflex | Habit`, name, short search summary, provenance, content digest, declarative parameter schema, applicability constraints, allowed executor kinds, required capabilities, side effects, steps, verification contract, rollback/compensation reference when applicable.
- `ProcedureStep`: stable step ID, typed existing-executor operation reference, parameter bindings, declared inputs/outputs, bounded branch/loop policy, pre/postconditions, retry/idempotency and checkpoint rules.
- `ProcedureEvidence`: source episode/artifact/run IDs, environment, observed status, verification receipts, independently asserted facts, reviewer/approval records.
- `ProcedureVersionEvent`: append-only `Candidate`, `Validated`, `Active`, `Deprecated`, `Quarantined`, `Revoked` transitions and reasons; no silent code mutation.
- `ProcedureInvocation`: chosen definition digest, bound inputs (redacted), runtime-scoped authorization, task/run context, journaled step status, outputs and measured token/cost/latency results.
- `ProcedureReference`: searchable lightweight catalog entry pointing to the immutable definition in local storage. The model sees its ID, summary, constraints, and estimated value; full scripts do not need to occupy its context.

All executable content is **content-addressed and pinned by digest**. The search index and contextual memory contain addresses and provenance, not a second executable copy. A version is immutable; revisions create new versions. Replay and imports validate schema, integrity, provenance, scopes, and policy *before* becoming selectable.

Scope must mirror `MemoryBanks`/`MemoryLineage`: a workflow writes only its own bank and may refer to ancestor banks. Other project workflows are read-only recollection sources and **cannot silently become executable trusted templates**. Cross-project or remote procedures are imported as untrusted proposals until locally validated and explicitly enabled. Forgetting a source episode invalidates or quarantines dependent procedures; export/import, clear, privacy retention, and merge must include compatible sidecar lifecycle semantics without leaving runnable orphan code.

## 5. Learning and generalization

State machine:

```text
Captured trace(s) -> Candidate -> Structurally valid -> Tested -> Active
                                          |                |
                                          v                v
                                       Rejected         Quarantined
                                          ^                |
                                          +-- failure / drift / revoked authority
Active --new evidence--> New version candidate (old version remains addressable)
Active --owner decision--> Deprecated/Revoked
```

A **reflex** is exactly one bounded typed operation, usually read-only or otherwise explicitly authorized. A **habit** is an acyclic or bounded-step directed plan composed of reflexes and other governed executor operations. It may branch on typed observable results, not on unconstrained natural-language commands. Unknown cases return control to an ordinary agent. No recursive/unbounded macros, undisclosed shell interpolation, unsandboxed eval, autonomous privilege expansion, or blind retries of mutating steps.

Generalization separates varying arguments from invariants. Multiple successful traces can suggest parameter slots, but similarity or repetition is *not proof* of safety, correctness, or general applicability. The memory clerks may organize and summarize evidence and suggest candidate patterns within their existing no-judgment contract. A normal agent may propose a procedure; deterministic validators, existing task verification, and human/agent approvals establish whether that particular candidate is promoted. A failed validation remains evidence, never silently rewritten as success.

Promotion policy: explicit evidence and version digest, bounded schema, platform compatibility, verified postconditions, current scope/grants, operation classification, reviewer decision (and human approval when the operation requires it). Low-risk reflexes may be eligible for *explicitly configured* auto-invocation; default is **offer-only**. Mutations, remote work, deployments, payments, approvals and permission changes always retain the same approval gate as the underlying task.

## 6. Contextual recall integration

The user objective, task cues, code symbols and contextual evidence continue through `MemoryLayerController`, `MemoryPromptContextProvider`, GRIP and the Context Packer. Add a separate `ProceduralRecallProvider`:

1. Compute lexical/semantic candidates from procedure summaries and source-tag associations, inside the workflow lineage.
2. Check deterministic eligibility: version active, environment/tool version, allowed scope, executor available, parameters bindable, approvals/grants accessible, freshness, side-effect class and revocation state.
3. Rank qualified suggestions by match, verified success in comparable environments, freshness, measured net token saving, and failure risk. Never promote a version solely because an untrusted peer claims success.
4. Offer at most a few compact references to the active agent, e.g. `proc:android.verify-build@3`, an input schema and a one-sentence verified-use summary. Expand the full definition **only at trusted invocation time**, not when composing the model prompt.
5. The agent chooses `invokeProcedure(ref, arguments, taskContext)` or conventional reasoning. The procedure service resolves the immutable digest, re-evaluates authorization and preconditions, then delegates each step to existing executor integrations.

This is a **parallel recall lane**, not a replacement for the Attention Deficit Dial or contextual memory: contextual recall can surface conflicts/provenance; procedural recall answers *how might I perform this class of task?*. No new permanent prompt preamble, no hidden programmatic reminder behavior, and no automatic macro dispatch from a GRIP result. A short catalog is budgeted against the same overall provider context budget.

## 7. Duplex MCP as a procedural collaboration bus

Reuse MCPServed's current `duplex_send/receive/ack/status` and SSE notifications via Aive's `DuplexMcpPeer` and task-scoped attachment. Treat `body` as a versioned **application payload**, never raw instructions implicitly handled by `DuplexManagedSessionBridge`:

```json
{
  "schema": "aive.procedure.v1",
  "type": "procedure.review.requested",
  "messageId": "local-durable-id",
  "conversationId": "review-123",
  "macroRef": "proc:android.verify-build@3",
  "digest": "sha256:...",
  "workflowRunId": "source-workflow",
  "taskRunId": "source-task",
  "payload": {
    "summary": "Review applicability and verified checks",
    "evidenceRefs": ["artifact:..."]
  }
}
```

Initial event types: `procedure.candidate.proposed`, `procedure.review.requested`, `procedure.review.result`, `procedure.invoke.proposed`, `procedure.run.started`, `procedure.run.checkpoint`, `procedure.run.finished`, `procedure.failure.reported`, `procedure.version.revoked`. Request/response is for **short-lived proposal acceptance**, not a promise a multi-step run finished. Completion and progress arrive as events with `conversationId`/`messageId` correlation. Both peers may initiate any proposal.

Routing MUST distinguish an ordinary peer chat message from a typed `aive.procedure.v1` payload. The latter is parsed into a restricted procedural inbox before any provider message forwarding. An MCP peer can propose, review and report; **only the locally authorized procedure service may execute or promote**. Peer labels `a`/`b` and bearer tokens establish transport access, not model identity or task authority; identities/roles are bound out of band by the host.

Current MCPServed duplex limits: **exactly two peers, 32,768 UTF-8-byte bodies, 128 queued envelopes per peer, 256 outstanding requests, approximately 10-minute request TTL, and volatile queues**. SSE is a wake-up signal. Therefore:
- keep full procedure bodies and evidence in durable local artifact stores; exchange short summaries and content-addressed refs over MCP;
- maintain durable, bounded local outbound event/receipt journal and per-peer deduplication keys; acknowledge MCP ingress **after durable validation/journaling**, never on mere socket read;
- retry on reconnect, deduplicate by pairing identity + `messageId` + content digest, and make step execution independently idempotent (at-least-once delivery, not exactly-once effects);
- support cancellation of *a proposal* independently from authorized workflow cancellation; timeouts, broker restarts, disconnections and backpressure fail closed without losing task semantics;
- fan-out to more than two agents requires multiple explicit pairings or a future broker protocol, **not** an assumed multi-party room.

Remote procedure evidence is treated as untrusted until independently verified. No source code, secrets, private memory, raw chain-of-thought, or privileged workflow payloads are broadcast by default. Existing MCPServed device-control/ADB capability and Aive approval grants are never inherited.

## 8. Invocation and feedback

```text
GRIP + procedure match -> lightweight offer -> agent selection
 -> schema binding -> scope/authority/precondition gate
 -> idempotency journal -> existing task executor(s)
 -> step receipt/checkpoint -> verifier
 -> contextual episode + procedural feedback + optional duplex event
```

The procedural scheduler owns step order, budget, bounded retry and resumption. The workflow engine remains the only owner of task status, approval, cancellation and artifacts. An invocation neither overwrites task state nor bypasses its executor adapter. On uncertainty, divergent environment, invalid verification or unavailable tool, pause/reject and return control to the agent.

Treat each invocation outcome as **new experience**: contextual memory banks the narrative and provenance; procedural memory records structured results and eligible performance evidence. Success strengthens ranking within comparable scopes; repeated failure or drift suspends auto-suggestion/automatic eligibility and triggers a new validation, not auto-editing the executable body.

Token accounting uses *actual* provider token usage and run traces when available. Report gross prompt saved, additional retrieval/verification/repair tokens, net tokens, latency, cost, successful reuse rate, and fallback rate, against matched same-model/context baseline tasks. Savings are a measured optimization, not a reason to weaken verification.

## 9. Non-negotiable invariants

1. Memory clerks organize; they never independently adjudicate correctness, approve execution, invent trust, or grant permissions.
2. Contextual memories remain append-only and provenance-rich; procedures are separate versioned, addressable artifacts with explicit lifecycle.
3. No executable content from untrusted text or a remote peer may run merely because it entered recall or an MCP inbox.
4. Every procedure invocation rechecks the *current* task/role/executor authority, approvals, environment and parameter schema.
5. Structured evidence is required for promotion; summaries, duplicated claims and model confidence alone are insufficient.
6. A macro's full source never needs to be in the model context just to discover or invoke a stable reference.
7. Forgetting, consent withdrawal, revocation and scope changes invalidate affected procedures and cached suggestions.
8. A failed procedure cannot silently complete a task or mutate its workflow history; failures and partial effects are auditable.
9. Transport failure, memory outage and procedural-memory failure leave ordinary workflows functional.
10. The duplex link never becomes a backdoor to MCPServed phone control or an unrestricted tool executor.

See [Procedural Memory Implementation Plan](PROCEDURAL_MEMORY_IMPLEMENTATION_PLAN.md) for tasks, exact code integration points, tests, rollout gates, and dependencies.
