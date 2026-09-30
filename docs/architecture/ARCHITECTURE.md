# The Aive architecture

## Product definition

The Aive is a Compose Multiplatform control room for governed software-development workflows.

> The Aive is not an agent. It is the swarm that coordinates agents.

Its job is to turn an objective into explicit work, establish dependency and verification structure, assign that work to appropriate executors, observe the run, and surface only the human decisions that genuinely require a person.

The Aive is not an IDE. Source editing, terminals, and generic filesystem tooling are outside the product boundary.

## First principles

1. A task describes **what must happen**.
2. A role describes **responsibility and authority**.
3. An executor describes **who or what performs the work**.
4. A provider is one possible execution mechanism, not workflow semantics.
5. Dependencies are explicit edges in a DAG.
6. Parallelism is derived from the graph and policy.
7. The same worker should not define, execute, and certify its own work when independent verification is available.
8. Human intervention happens through explicit gates and decisions.
9. Provider or executor failure must not corrupt durable workflow state.
10. The UI projects runtime truth; it does not invent a second source of execution state.

## Targets

- Android — namespace `com.hereliesaz.aive`; application ID `com.hereliesaz.aive` for the `play` flavor and `com.hereliesaz.haive` for the `github` release flavor, kept permanently so GitHub-release installs upgrade in place
- Desktop JVM
- Web JavaScript
- WebAssembly

Shared code must remain portable across all four targets.

## Active modules

~~~text
shared/       domain, workflow engine, policies, persistence, shared Compose UI
providers/    provider adapters: Jules, and llm (native and OpenAI-compatible hosted adapters)
computeRelay/ distributed compute relay server
androidApp/   Android launcher
desktopApp/   Desktop launcher
webApp/       browser launcher
~~~

The root Gradle settings are the authoritative active build graph.

## Work, roles, and executors

A workflow node is a unit of work, not a synonym for an agent.

`TaskDefinition.roleId` is optional responsibility/authority metadata. `TaskDefinition.executor` identifies who or what performs the task. Existing role-backed definitions without an explicit executor resolve through `effectiveExecutor()` to `TaskExecutor.RoleAgent`, preserving compatibility while keeping execution semantics separate from responsibility.

The current executor model represents:

- role-backed provider agents
- GitHub Actions
- scripts
- test runners
- deployments
- repository operations
- human approvals
- external services
- nested Aive workflows
- distributed placement of another executor on a remote compute node (`TaskExecutor.Distributed`; see [Distributed compute](DISTRIBUTED_COMPUTE.md))

Non-agent execution does not require a fabricated employee role. A GitHub Action, deployment, or repository operation may legitimately have no `roleId` at all. A human approval may still carry a responsibility role while remaining a human executor rather than an agent.

`TaskRun` persists the resolved executor independently from `assignedRoleId`, `assignedProviderId`, `providerRunId`, and `externalRunId`. The workflow engine dispatches provider-backed work through the provider/session boundary, moves human executors into explicit approval state, and exposes `completeTask` for externally driven system executors to report artifacts, external run IDs, and completion without pretending to be agent sessions.

## Swarm model

Built-in responsibilities include:

- Orchestrator
- Product Manager
- Researcher
- Architect
- EPA Representative
- UX Designer
- Implementation Engineer
- Crash Test Dummy
- QA Engineer
- Adversarial Reviewer
- Code Reviewer
- Recovery Engineer
- Release Engineer

Roles are data. They carry responsibility, instructions, capabilities, and authority. A role can be staffed by different providers without changing its meaning.

## Workflow definition and run state

`WorkflowDefinition` is immutable execution structure: tasks, dependencies, acceptance requirements, policies, gates, responsibility, and executor declarations.

`WorkflowRun` and `TaskRun` are durable state. They carry status, attempts, resolved executor, responsibility assignment, provider/external run identifiers, artifacts, blocking reasons, and progress.

The engine owns:

- DAG validation, including the requirement that every task has an executable path
- readiness and blocking
- bounded parallel dispatch
- executor/provider selection for role-backed work
- explicit human approval transitions
- externally reported system-executor completion
- plan approval
- progress reconciliation
- artifact collection
- independent verification
- retries and escalation
- durable state transitions
- resume after process death or restart

## Progress

Progress belongs to `TaskRun`, not to agents.

An executor may provide an exact fraction. When it does, the runtime preserves that value. Some executors, including the currently exposed Jules activity model, provide only qualitative progress. In that case The Aive may present lifecycle progress such as planning, running, and verifying without pretending it is an exact percentage.

This makes the same UI capable of representing both a provider activity like “Writing tests” and an automated executor that knows “7 of 11 steps complete.”

## Events

Workflow events are executor-neutral. `ExecutorAssigned` records the concrete executor plus optional responsibility role for every task. Role-backed agent work may additionally emit the legacy/specialized `AgentAssigned` event where provider-agent history is useful, but agent assignment is no longer the universal workflow concept.

Task start, completion, failure, retry, escalation, approval, artifact, and workflow terminal events remain independent of executor type.

## Provider boundary

Provider adapters translate external APIs into neutral runtime contracts.

Registered agent providers are built from the configured credentials:

- OpenCode on GitHub Actions (`OpenCodeActionsAgentProvider`) — the automatic coding agent for linked GitHub repositories; it needs only the linked GitHub token.
- Text LLM providers from `providers/llm` — native OpenAI, Anthropic, Gemini, and xAI adapters plus OpenAI-compatible hosted providers.
- GitLab workspace agent (`providers/llm`) for linked GitLab repositories, and the Desktop-only local workspace agent for linked Local Git projects. Both return validated file changes and commit them to a branch; neither executes repository code.
- Jules — explicit-only (`AgentProvider.explicitOnly`). The registry never selects it automatically; it runs only as a role's preferred provider or when a task requires it by ID. Jules source IDs, session IDs, request payloads, credentials, and activity schemas remain inside the Jules adapter.

New providers implement the same neutral contracts without changing workflow semantics. Provider selection is only relevant to role-agent executors; system executors do not pass through the agent-provider registry.

## Artifacts and evidence

Workers and systems communicate through explicit artifacts and events rather than implicit shared transcript inheritance.

Examples include requirements, research, architecture, plans, code changes, test results, reviews, verification evidence, failure analysis, pull requests, and release outputs.

Verification and integration decisions should rely on concrete evidence rather than a worker merely claiming completion.

## The workflow mindmap

The primary execution surface is the animated H2G2 workflow mindmap.

Each visible node is projected from real workflow state, including:

- graph position and dependencies
- task identity
- responsibility/role identity when present
- concrete executor type
- status
- provider or external-run assignment
- retry attempt
- blocking reason
- artifacts
- progress and progress message

Role-backed nodes keep role personality motion. Non-agent executor nodes derive their own H2G2 motion identity from executor type instead of borrowing a fake employee identity. A node also inherits diminishing motion from its workflow ancestry, so branches behave like related physical systems rather than disconnected animated widgets.

Active nodes can express work through motion and by filling the existing node with progress instead of attaching a conventional progress bar.

The technical inspector likewise shows executor, provider, provider-run ID, external-run ID, attempts, progress, blocking reason, and artifact count from live run state.

## Persistence compatibility

The current persistence schema is `3`. Schema `2` introduced executor-neutral task definitions and task runs (schema `1` role-backed tasks/runs are mapped to `TaskExecutor.RoleAgent`); schema `3` rewrites legacy provider and GitHub artifact IDs to include the task attempt so retries cannot collide. Migrations preserve workflow IDs, task-run IDs, statuses, provider IDs, artifacts, and progress, and are written back immediately.

Snapshots live under `geministrator.workflow.persistence.v2`; the older `geministrator.workflow.persistence.v1` key is a read fallback that is migrated forward and then retired. See [Persistence](PERSISTENCE.md) for detail.

## Human attention

The Inbox is for unresolved decisions and gates, not general conversation. Human attention is treated as scarce and should be requested only when policy or judgment actually requires it.

## Security boundary

Workflow persistence must never contain provider credentials, OAuth tokens, private signing material, or secret values. The runtime may persist references to required secret names, but secret values belong in platform-secure credential handling or an external service boundary.

See the [privacy policy](../PRIVACY.md) for user-facing data handling.

## Delivery

`.github/workflows/multiplatform.yml` is the canonical CI/CD workflow.

Pushes to `main`:

- run shared workflow tests
- test and compile provider targets
- build Android release artifacts
- build a Desktop distribution
- build JS and Wasm web targets
- upload Android and Desktop artifacts
- derive the four-part public build version through the centralized `HereLiesAz/workflows` version action
- publish exact-build assets into the patch-grouped GitHub Release through the centralized release action
- deploy the JS production bundle to GitHub Pages after a successful build

The composite build currently runs on JDK 21 because the pinned H2G2 renderer is compiled with a Java 21 toolchain. Android-facing Aive bytecode may still target JVM 17. The Aive and the pinned renderer also use the same Android Gradle Plugin version because Gradle does not permit incompatible AGP versions inside one composite Android build.

Web packaging uses isolated Maven publications because Kotlin/JS package generation cannot
resolve the nested H2G2 composite build from within itself. CI publishes the pinned source
checkouts with their native coordinates and enables `haive.useMavenLocalH2g2` to substitute
the exact pinned dependencies with those local publications. Publication files are not
renamed or rewritten: Maven and embedded JS package versions must stay consistent, or
Yarn can try to download a local library from the npm registry. Rebuild the pinned
publications before using this option locally; their native version alone does not identify
the source revision.

Android signing is used when signing secrets are available. Play publishing is a separate delivery
step and uses the repository Play service-account secret when enabled.

The checked-in release line remains four-part (`MAJOR.MINOR.PATCH.BUILD`). Exact builds keep
immutable four-part Git tags, while GitHub Release objects are grouped by patch: all `0.9.6.x`
artifacts, for example, live under `0.9.6`. Asset filenames retain the exact build number so
multiple builds coexist safely. The version calculation, patch grouping, legacy-release migration,
and collision policy live in `HereLiesAz/workflows`; Aive's workflow only produces Aive-specific
artifacts and delegates those semantics.
