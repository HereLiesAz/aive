The Aive Orchestration Models — Kaggle Training Sequence

This training series begins after the Aive Memory Clerk model family is substantially complete.

The Memory Clerks organize historical information.

The models in this series help The Aive decide:

- what context is needed
- what memory to retrieve
- which worker should act
- how work should be handed off
- whether a task requires escalation
- whether explicit acceptance criteria have been met
- how a larger objective should be decomposed
- how a failed plan should be revised

Unlike Memory Clerks, some orchestration models ARE permitted to perform limited or full reasoning.

The amount of reasoning authority must be explicit for every role.

---

Model Classes

Use three conceptual tiers.

Tier 1 — Orchestration Utilities

Presumptive base:

"Qwen/Qwen2.5-0.5B-Instruct"

These perform tightly bounded decisions such as:

- retrieval query composition
- context selection
- agent routing
- tool routing
- handoff construction
- escalation detection

They should behave more like trained control functions than general agents.

They must NOT solve the user's underlying problem.

---

Tier 2 — Coordinator / Planner

Benchmark compact models in approximately the:

1.5B–3B class

Use a Qwen-family instruct model as the presumptive starting point unless evidence favors another compact model.

These models ARE allowed to reason about:

- task decomposition
- dependencies
- execution ordering
- resource allocation
- failed steps
- changing state
- explicit contradictions relevant to accomplishing the objective
- plan revision

They are coordinators, not merely clerks.

---

Tier 3 — Working / Conscious Reasoning Agents

Do NOT attempt to replace The Aive's strongest available general-purpose reasoning models merely for architectural purity.

These agents perform substantive work such as:

- coding
- debugging
- research
- architecture
- comparison
- interpretation
- contradiction recognition
- reconciliation
- strategic decisions
- difficult planning

The orchestration system should be able to ESCALATE to these models.

---

Prompt 0 — Build the Shared Orchestration Training Framework

Build a reusable Kaggle framework for Aive orchestration models.

Do not train individual roles yet.

Core architectural distinction

The Aive has:

Memory Clerks

Store and organize prior experience.

Orchestration Utilities

Perform narrow control-plane decisions.

Planner / Coordinator

Performs deliberate multi-step reasoning about execution.

Working Agents

Perform substantive domain work.

Do not blur these responsibilities during training.

---

Common orchestration packet

Design a canonical input schema containing fields such as:

{
  "objective": "",
  "currentState": {},
  "acceptanceCriteria": [],
  "availableAgents": [],
  "availableTools": [],
  "availableModels": [],
  "resourceConstraints": {},
  "retrievedMemory": [],
  "artifacts": [],
  "previousSteps": [],
  "currentPlan": [],
  "failures": [],
  "instruction": ""
}

Not every role receives every field.

Each model must receive only what its job requires.

---

Common orchestration output principles

Outputs should be structured and machine-validated.

Prefer declarative outputs over prose.

Examples:

{
  "selectedAgent": "...",
  "reasonCode": "...",
  "confidence": 0.84
}

or:

{
  "queries": []
}

or:

{
  "nextSteps": []
}

Do not permit unrestricted free-form output where a bounded schema can represent the decision.

---

Training requirements

Implement shared support for:

- LoRA/PEFT
- QLoRA where useful
- deterministic splitting
- checkpoint recovery
- structured JSON validation
- hallucinated-ID detection
- tool/agent reference validation
- context-budget enforcement
- exact-match metrics
- ranking metrics
- calibration metrics
- refusal/escalation metrics
- latency benchmarking
- quantization
- ONNX/runtime export where practical

Split datasets by PROJECT / REPOSITORY / TASK FAMILY so closely related workflows do not leak between train and test.

---

Critical evaluation philosophy

An orchestration utility should be rewarded for saying:

"I do not have enough information; escalate."

when appropriate.

Do not train tiny models to fabricate confidence.

The goal is not maximizing the percentage of tasks handled locally.

The goal is maximizing:

correct inexpensive decisions + correct escalation.

Produce the reusable framework and stop.

---

Prompt 1 — Train the Memory Query Composer

Train the MEMORY QUERY COMPOSER.

Presumptive base:

"Qwen/Qwen2.5-0.5B-Instruct"

This should be one of the first orchestration models trained.

Purpose

Translate the current task/state into effective queries against The Aive's hierarchical memory system.

Memory resolution levels include roughly:

- category
- summary
- phrase
- noun/entity tags
- verb/action tags
- granular context/evidence

The Query Composer does NOT answer the task.

It decides what to look for.

---

Example

Current task:

"Fix the regression in AR ball tracking introduced after the navigation changes."

Useful queries might target:

- project/entity: AR ball tracking
- action: regression/fix/change
- component: navigation
- previous implementation decisions
- recent changes
- prior successful AR implementation
- relevant code symbols

It may first request broad summaries, then produce narrower queries based on returned hits.

---

Train it to decide

- what concepts to search
- which semantic resolution to use
- when multiple searches are warranted
- when to broaden
- when to narrow
- when exact entities/symbols should be searched
- when code actions matter
- when chronological context matters
- when enough memory has already been retrieved

---

Do NOT train it to

- answer the problem itself
- reconcile remembered conflicts
- determine which memory is correct
- rewrite memory
- invent missing facts

Its job ends at:

retrieve the most useful evidence for the next reasoning agent.

---

Dataset

Create examples from:

- software development sessions
- debugging
- long-term projects
- user preferences
- prior decisions
- changing requirements
- code symbols
- errors
- build failures
- architectural discussions
- tool usage

Include multi-hop retrieval examples.

---

Metrics

Measure:

- relevant-memory recall
- irrelevant-memory rate
- query count
- unnecessary context retrieved
- correct resolution selection
- entity/symbol retrieval
- action retrieval
- downstream task improvement
- total tokens retrieved

Optimize not merely for recall, but for:

useful evidence per retrieved token.

Train and export.

---

Prompt 2 — Train the Context Packer

Train the CONTEXT PACKER.

Presumptive base:

"Qwen/Qwen2.5-0.5B-Instruct"

Purpose

Given:

- objective
- selected worker
- retrieved memories
- relevant files/artifacts
- prior outputs
- current state
- explicit limits

select the smallest context packet that allows the next agent to do its work correctly.

The Context Packer does NOT solve the task.

It packages evidence.

---

It may decide

- include/exclude
- order
- priority
- whether raw evidence or summary is appropriate
- when granular context is necessary
- whether a file/code excerpt is required
- whether an older result is irrelevant

---

It must NOT

- rewrite evidence into a new conclusion
- silently reconcile conflicting context
- decide which substantive statement is true
- modify source artifacts
- solve the task

When uncertain between two substantively relevant memories, preserve both.

---

Train around hard context budgets

Examples:

- 2k
- 4k
- 8k
- larger limits where appropriate

The model should understand the assigned worker's context limit.

---

Objective

Maximize:

task-relevant information per context token

while minimizing:

- redundancy
- irrelevant history
- oversized context
- missing critical evidence

---

Evaluate downstream

A packed context is good if the receiving agent performs well.

Metrics:

- downstream task score
- critical evidence recall
- irrelevant-token percentage
- compression ratio
- context overflow rate
- conflicting-evidence preservation
- hallucinated-content rate

Train and export.

---

Prompt 3 — Train the Agent Router

Train the AGENT ROUTER.

Presumptive base:

"Qwen/Qwen2.5-0.5B-Instruct"

Input

- task/objective
- known state
- available agent definitions
- capabilities
- model strengths
- tool access
- estimated cost
- context limits
- platform availability

Output

Select:

- worker/agent
- optional fallback
- reason code
- required context type

Do not produce a solution to the task.

---

Examples

Tasks may need:

- coding agent
- research agent
- browser agent
- file-analysis agent
- planner
- simple local utility
- image model
- human clarification
- stronger reasoning model

---

Train it to understand capability boundaries

Examples:

A 0.5B utility should NOT be selected for:

- complex architectural debugging
- ambiguous multi-step planning
- contradiction reconciliation
- deep codebase reasoning

A heavyweight reasoning model should NOT be selected for:

- trivial classification
- mechanical query construction
- simple structured transformation

---

Metrics

- route accuracy
- unnecessary escalation
- failure to escalate
- cost-adjusted correctness
- downstream completion rate
- latency-adjusted correctness

False under-escalation should be penalized more heavily than modest over-escalation.

Train and export.

---

Prompt 4 — Train the Tool Router

Train a TOOL ROUTER.

Presumptive base:

"Qwen/Qwen2.5-0.5B-Instruct"

This is separate from agent selection.

Its job is to determine what external capability is needed.

Possible categories include:

- GitHub
- web research
- filesystem
- connected documents
- email
- calendar
- code execution
- image generation
- database
- build/test
- no tool

The actual available tools must be supplied dynamically.

Never train hard-coded assumptions that a particular tool is always present.

---

Output

Structured plan such as:

{
  "tool": "github",
  "operationClass": "read_repository",
  "requiredInputs": ["repository", "branch"],
  "reasonCode": "SOURCE_OF_TRUTH"
}

The Tool Router should NOT invent actual tool arguments when it has not been given them.

---

Important

Train explicit "NO_TOOL" behavior.

Not every task needs a tool call.

Train explicit "UNAVAILABLE_CAPABILITY" behavior.

Do not hallucinate unsupported tools.

---

Metrics

- tool-class accuracy
- unnecessary tool calls
- missing necessary calls
- unavailable-tool hallucination
- tool sequence accuracy
- downstream success

Train and export.

---

Prompt 5 — Train the Handoff Composer

Train the HANDOFF COMPOSER.

Presumptive base:

"Qwen/Qwen2.5-0.5B-Instruct"

This model converts completed/partial work from one agent into a compact packet for the next agent.

Handoff packet should contain

- objective
- completed work
- current state
- relevant decisions
- produced artifacts
- important evidence
- unresolved items
- failures
- next expected action
- acceptance criteria
- provenance/references

---

It should remove

- conversational filler
- redundant reasoning
- repeated narration
- irrelevant history

---

It must NOT

- alter completed results
- claim work was completed when it was not
- hide failures
- invent decisions
- independently resolve ambiguities

---

Example output

{
  "objective": "...",
  "completed": [],
  "artifacts": [],
  "state": {},
  "unresolved": [],
  "failures": [],
  "nextAction": "",
  "acceptanceCriteria": []
}

---

Train on

- successful handoffs
- partial completion
- agent failure
- tool failure
- code generation
- research
- review cycles
- plan changes
- interrupted workflows

Metrics:

- state preservation
- artifact reference accuracy
- unresolved-item recall
- hallucination rate
- handoff compression
- downstream continuation success

Train and export.

---

Prompt 6 — Train the Escalation Gate

Train the ESCALATION / CAPABILITY GATE.

Presumptive base:

"Qwen/Qwen2.5-0.5B-Instruct"

This is a critical safety and efficiency model.

Its task is NOT:

"Can I say something plausible?"

Its task is:

"Can the assigned local model reliably perform this task within its capability and context limits?"

Possible output:

{
  "decision": "LOCAL | ESCALATE | NEED_MORE_CONTEXT | NEED_TOOL",
  "reasonCodes": [],
  "recommendedTier": ""
}

---

Train escalation triggers

- insufficient context
- excessive task complexity
- need for multi-step reasoning
- codebase-wide reasoning
- ambiguous objective
- unsupported tool requirement
- high uncertainty
- contradiction reconciliation
- architectural decisions
- planning beyond utility-model ability
- malformed input

---

Reward calibrated humility

A tiny model should frequently escalate when the task exceeds its design.

Do not train "always answer."

---

Metrics

Most important:

- dangerous under-escalation
- appropriate escalation
- needless escalation
- calibration
- downstream success after routing

Assign a higher penalty to false LOCAL decisions than false ESCALATE decisions.

Train and export.

---

Prompt 7 — Train the Acceptance / Completion Gate

Train the COMPLETION GATE.

Begin by benchmarking the 0.5B shared model.

Escalate to a somewhat larger model if testing shows it cannot reliably understand complex acceptance criteria.

Job

Given:

- original objective
- explicit acceptance criteria
- completed actions
- produced artifacts
- test results
- current state

determine:

- COMPLETE
- INCOMPLETE
- BLOCKED
- FAILED
- NEEDS_VERIFICATION

This model judges whether stated criteria were satisfied.

It is NOT judging whether the user's objective itself was wise.

---

Example

Requirement:

"All tests pass on Android, Windows, macOS, Linux, Web JS, and Web Wasm."

Observed:

Android PASS
Windows PASS
macOS PASS
Linux PASS
Web JS PASS
Web Wasm NOT RUN

Correct:

"INCOMPLETE"

Do not infer Web Wasm success.

---

Train rigorous evidence requirements

No:

- "probably done"
- "looks complete"
- inferred success

Require explicit evidence.

---

Metrics

- false-complete rate
- false-incomplete rate
- missing-evidence detection
- acceptance-criterion coverage
- blocked-state accuracy

The false-complete rate should be extremely low.

Train and export.

---

Prompt 8 — Train the Execution State Summarizer

Train a small EXECUTION STATE model.

Presumptive base:

"Qwen/Qwen2.5-0.5B-Instruct"

This differs from the Memory Summary Clerk.

It summarizes the LIVE workflow state for orchestration.

Input

- current plan
- completed steps
- failures
- active artifacts
- worker outputs
- tool results

Output

A structured current-state object.

Example:

{
  "completedSteps": [],
  "activeStep": "",
  "blockedSteps": [],
  "failedSteps": [],
  "artifacts": [],
  "knownConstraints": [],
  "openQuestions": []
}

This is ephemeral orchestration state.

It does not write long-term memory.

---

Restrictions

Do not:

- invent task completion
- resolve open questions
- reinterpret failures
- change the plan

Metrics:

- state precision
- state recall
- stale-state rate
- hallucination
- artifact/reference fidelity

Train and export.

---

Prompt 9 — Train the Task Decomposer / Planner

Now train a genuine reasoning model.

Do NOT automatically use the 0.5B utility model.

Benchmark suitable instruct models approximately in the 1.5B–3B class, including a Qwen-family candidate.

Choose based on measured planning performance and deployment feasibility.

Role

Given a high-level objective, construct a coherent executable plan.

This model IS permitted to reason.

It may:

- infer dependencies
- order steps
- identify prerequisites
- divide work among agents
- identify verification steps
- recognize ambiguity
- plan tool usage
- recognize incompatible requirements
- request necessary evidence
- revise assumptions

It still should not perform all domain work itself.

---

Output plan structure

Use explicit DAG-compatible structures.

Example:

{
  "objective": "",
  "assumptions": [],
  "steps": [
    {
      "id": "step-1",
      "objective": "",
      "dependencies": [],
      "preferredAgentType": "",
      "requiredCapabilities": [],
      "acceptanceCriteria": []
    }
  ],
  "finalAcceptanceCriteria": []
}

---

Training data

Use sophisticated multi-step tasks involving:

- coding projects
- debugging
- migrations
- release preparation
- research
- data analysis
- repository maintenance
- cross-platform builds
- multi-agent workflows

Generate plans with meaningful parallelism where tasks are independent.

Avoid artificially serial plans.

---

Metrics

- dependency correctness
- completeness
- unnecessary-step count
- executable-step rate
- parallelism quality
- agent assignment suitability
- final downstream success

The best metric is actual execution success.

Train and export.

---

Prompt 10 — Train the Plan Repair Model

Train a PLAN REPAIR / REPLANNING specialist.

This may share the same foundation as the Planner and could begin as a separate adapter.

Input

- original objective
- current plan
- completed steps
- failed step
- failure evidence
- changed world state
- available capabilities

Job

Modify only what must change.

Avoid rebuilding the entire plan without reason.

It may:

- retry differently
- change agent
- change tool
- insert prerequisite
- remove invalid future steps
- reorder dependencies
- escalate
- declare blocked

---

Important

Do not treat every failure as a reason to retry.

Train distinctions such as:

- transient failure
- malformed plan
- unavailable capability
- bad assumption
- external blocker
- insufficient evidence
- failed implementation
- acceptance-test failure

---

Output

{
  "diagnosis": "",
  "preservedSteps": [],
  "removedSteps": [],
  "modifiedSteps": [],
  "newSteps": [],
  "decision": "CONTINUE | ESCALATE | BLOCKED"
}

---

Metrics

- recovery success
- unnecessary replanning
- preserved valid work
- repeated-failure rate
- escalation accuracy
- downstream completion

Train and export.

---

Prompt 11 — Train the Verification Planner

Train a VERIFICATION PLANNER.

Likely foundation:

same family as the Planner, though benchmark whether 0.5B suffices for simpler cases.

Job

Given an objective and produced result, determine what evidence is required before declaring success.

Examples:

For code:

- compile
- unit tests
- integration tests
- lint
- target-platform build

For deployment:

- health check
- endpoint response
- artifact existence
- version verification

For research:

- source verification
- cross-check
- date validation

For file editing:

- expected content
- format validity
- references intact

---

Critical principle

Verification must be based on the user's acceptance criteria, not generic ritual.

Do not always request every conceivable test.

---

Metrics

- missing critical verification
- unnecessary verification cost
- false confidence
- downstream defect detection
- coverage of acceptance criteria

Train and export.

---

Prompt 12 — Train the Conscious Memory Reconciliation Evaluation Scenario

Do NOT make this another Memory Clerk.

Use the Planner/Coordinator or an ordinary reasoning agent.

This experiment validates the architectural boundary.

Scenario

Memory retrieval returns:

Memory A:
"The API uses a 30-second timeout."

Memory B:
"The API uses a 60-second timeout."

The Memory Layer has associated them because they share:

- API
- timeout
- configuration

No contradiction was recorded by Memory Clerks.

Now give both to a conscious reasoning agent in the context of a task where the timeout matters.

The ordinary agent is allowed to say:

"These remembered values disagree. I need to determine which currently applies."

It may then:

- inspect code
- inspect configuration
- inspect history
- reason about chronology
- ask for clarification
- determine the current state

Its reasoning is part of the agent session.

When that agent completes, its session will later enter the normal Memory Clerk pipeline.

Build training/evaluation examples proving this boundary.

The purpose is NOT to train contradiction detection into Memory Clerks.

It is to ensure the orchestration/reasoning layer knows when explicit reconciliation is necessary.

---

Prompt 13 — End-to-End Orchestration Simulation

Connect:

- Memory Query Composer
- Context Packer
- Agent Router
- Tool Router
- Handoff Composer
- Escalation Gate
- Completion Gate
- Execution State model
- Planner
- Plan Repair
- Verification Planner
- Memory Clerks
- ordinary working agents

Run complete simulated tasks.

Test:

1. user submits objective
2. planner creates DAG
3. query composer retrieves relevant memory
4. context packer prepares worker packet
5. router selects agent
6. tool router selects required capability
7. worker executes
8. handoff records result
9. execution state updates
10. completion gate checks step
11. failure triggers plan repair when appropriate
12. final verification occurs
13. completed agent sessions enter memory consolidation

---

Test classes

- simple tasks
- multi-step coding
- repository refactor
- regression debugging
- research
- tasks requiring prior memory
- changing requirements
- tool failure
- worker failure
- bad plans
- unavailable capabilities
- contradictory remembered information
- partial completion
- cross-platform release

---

Measure

- total task completion
- cost
- latency
- number of model calls
- escalation rate
- false escalation
- failed under-escalation
- context tokens
- unnecessary context
- tool-call accuracy
- plan revisions
- repeated failures
- memory retrieval usefulness

Compare against a baseline where one large model performs all orchestration itself.

The trained orchestration system should demonstrate measurable benefit in either:

- reliability
- latency
- cost
- context efficiency

preferably several.

---

Prompt 14 — Adversarial Orchestration Evaluation

Construct a hard evaluation suite.

Include cases designed to fool small orchestration models.

Examples:

- deceptively simple task requiring deep reasoning
- very long task with trivial next action
- irrelevant memory with high lexical overlap
- relevant memory with weak lexical overlap
- similarly named tools
- unavailable tool
- stale result
- incomplete test matrix
- worker claims success without evidence
- agent output contradicts artifact
- missing artifact
- partial platform failure
- cyclic dependencies
- impossible acceptance criteria
- ambiguous user request
- context-budget exhaustion
- repeated tool failure
- plan that cannot possibly meet objective

Test whether the orchestration system:

- escalates correctly
- retrieves correctly
- packs context correctly
- detects incomplete work
- repairs the plan
- avoids loops
- avoids fabricated success

Produce a per-model failure matrix.

---

Prompt 15 — Quantization and Deployment

Export the orchestration utility family.

For the 0.5B-class models, test:

- shared Qwen base + adapters
- merged role-specific models

For Planner-class models, evaluate appropriate quantized deployment independently.

Required local targets where feasible:

- Android
- Windows
- macOS
- Linux
- Web

Do NOT require every high-reasoning planner to run efficiently on every low-resource device if measurements show this is unrealistic.

Instead, explicitly support escalation.

Possible architecture:

local utility
→ local planner if capable
→ stronger local/remote reasoning agent

Benchmark:

- startup
- RAM
- latency
- adapter switching
- quantization loss
- task accuracy
- routing accuracy
- planning accuracy

---

Prompt 16 — Determine the Optimal Local Reasoning Ceiling

Run an empirical experiment to determine how much orchestration reasoning The Aive should perform locally.

Compare:

Configuration A

0.5B utilities only
all planning escalated

Configuration B

0.5B utilities + ~1.5B local planner

Configuration C

0.5B utilities + ~3B local planner

Configuration D

large general model performs orchestration

Measure:

- success rate
- latency
- RAM
- battery/CPU where measurable
- cost
- escalation frequency
- planning errors
- recovery rate

Determine the smallest local planner that produces a worthwhile reduction in expensive reasoning calls.

Do not optimize for model size alone.

Determine the Pareto frontier.

---

Prompt 17 — Final Orchestration Release Gate

An orchestration model may ship only if:

Query Composer

Retrieves useful memory without flooding context.

Context Packer

Preserves critical evidence inside budget.

Agent Router

Selects capable workers and escalates appropriately.

Tool Router

Uses actual available tools and never invents capabilities.

Handoff Composer

Preserves state accurately.

Escalation Gate

Prefers escalation over unsupported guessing.

Completion Gate

Does not declare success without evidence.

State Model

Accurately reflects live execution.

Planner

Creates executable dependency-correct plans.

Plan Repair

Recovers without throwing away valid work unnecessarily.

Verification Planner

Requests enough evidence to substantiate completion.

---

Prompt 18 — Package the Aive Orchestration Model Family

Produce:

"haive-orchestration-models-manifest.json"

For each model include:

{
  "role": "",
  "reasoningTier": "",
  "baseModel": "",
  "adapterId": "",
  "artifacts": [],
  "quantization": "",
  "contextTokens": 0,
  "inputSchema": "",
  "outputSchema": "",
  "platforms": [],
  "latency": {},
  "memoryUsage": {},
  "qualityMetrics": {},
  "escalationMetrics": {},
  "hashes": {}
}

Package:

- base models
- adapters
- merged artifacts where needed
- tokenizers
- quantized models
- ONNX/runtime artifacts
- evaluation suites
- training configurations
- dataset manifests
- model cards
- hashes
- licenses
- integration documentation

Produce an integration guide for The Aive's orchestration layer.

---

Final Architectural Principle

The Aive model hierarchy should behave approximately like this:

Memory Clerks

Organize experience.

↓

Memory Query Composer

Decides what experience to look for.

↓

Context Packer

Builds the evidence packet.

↓

Agent + Tool Routers

Choose the worker and capabilities.

↓

Escalation Gate

Determines whether local intelligence is enough.

↓

Planner / Coordinator

Reasons about how work should proceed.

↓

Working Agent

Performs substantive conscious work.

↓

Verification + Completion Gate

Determine whether explicit objectives were actually satisfied.

↓

Handoff / Execution State

Carry state forward.

↓

Completed session returns to:

Memory Clerks

---

The desired intelligence distribution is:

Use tiny models for frequent predictable control decisions.

Use medium models for planning when they demonstrably suffice.

Wake up expensive reasoning only when actual reasoning is required.
