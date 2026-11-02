# Policy Decision Point

The Aive answers "may this happen?" in one place: an in-process policy decision point (PDP) in `shared/src/commonMain/kotlin/com/hereliesaz/geministrator/policy/`. The request shape follows OpenID AuthZEN (subject, action, resource, context), but the PDP is a pure function inside the app. There is no external PDP service: no downstream system would verify its answers, and the decisions depend on run state that only the device holds.

## Request and decision

~~~kotlin
val decision = InProcessPolicyDecisionPoint.decide(
    PolicyRequest(
        subject = PolicySubject.System,                         // Human, System, Role, Agent, Addon, RemotePeer
        action = PolicyAction.RunRepositoryOperation("push"),   // what is asked
        resource = PolicyResource.task(taskId, repository),     // repository, path, service, tool, gate, task
        context = PolicyContext(                                // run, gate state, provenance
            workflowRunId = run.id,
            taskDefinitionId = taskId,
            approval = ApprovalEvidence.Granted,
            provenance = PolicyProvenance.Local,
        ),
    ),
)
~~~

A decision is `Allow`, `Deny` or `RequireApproval` (with `requiresHuman` or a `requiredAuthority`), always with a `code` and a `reason`. The PDP only decides. The caller gathers the facts (for example whether a human-approval ancestor completed) and enforces the answer.

## Rules

Each rule reproduces the check it replaced, outcome and message. `PolicyDecisionPointTest` holds the former checks as oracles in a decision table.

| Action | Rule | Enforced at |
|---|---|---|
| `DecideApprovalGate` | Allowed when the gate names no role, or the decider is that role. Otherwise denied: `Role <id or <human>> is not authorized for gate <id>`. | `ApprovalGateCoordinator.requireDecider`, called by `WorkflowApprovalService` (plan and failure escalation) and `PlanRejectionService` |
| `ProceedPastPlan` | `ApprovalPolicy.None` is allowed, unless the provider already stopped for approval, which needs a person. `HumanApproval` needs a person. `RoleApproval(a)` needs authority `a`. | `ProviderTaskRequestFactory` (`requirePlanApproval`), `WorkflowRuntimeCoordinator.ensurePlanApprovalGates` (`requiresHuman`) |
| `RunRepositoryOperation` | THREAT_MODEL invariant 4. `status`/`fetch` are allowed. A mutation from a remote lease is denied. A local mutation is allowed when its approval was granted, needs approval when it is only declared in the graph, and is denied with no approval ancestor. | `WorkflowGraphValidator` (declared), `WorkflowRunFactory.refreshReadiness` (granted), `SystemExecutorDistributedWorkloadRunner` (remote lease) |
| `UseHostCapability` | Allowed when the add-on was granted the `HostPermission`. Otherwise denied: `Add-on <id> lacks permission <p>`. | `HaiveAddonMediator.checkPermission` |
| `ExerciseAuthority` | Allowed when the role holds the `RoleAuthority`. | Verifier selection in the MoA, Skeleton-of-Thought and resource-aware expanders |
| `ReasonOnly` | Denied when the role holds `Implement` (checked first) or requires `RepositoryWrite`. | Proposer and skeleton stages in the same expanders |
| `CallTool`, `Delegate` | Hooks, see below. | Not called yet |

The eligibility filters that pick a role for a stage (for example `authority in role.authorities` in `StarterWorkflowFactory`) choose among candidates. They do not refuse anything, so they stay outside the PDP.

## Audit

Deny and require-approval decisions made with a workflow run in their context are appended to the run's event log as `PolicyDecisionRecorded` (effect, code, action, subject, resource, reason) and appear on the run timeline. Allow decisions are not recorded. The following are recorded:

- a gate decider refused (`ApprovalGateCoordinator.requireDecider`);
- a plan gate opened (`RequireApproval`, alongside the existing `ApprovalRequired`);
- a repository mutation cancelled for want of a granted approval (`WorkflowEngine.refreshReadiness`, used by the engine and the runtime coordinator; plan repair records the same event itself).

Decisions with no run in their context return their reason to the caller and are not recorded. These are graph validation, add-on calls, workflow preparation and a remote worker's lease refusal, which returns the reason in the lease result.

## Hook points

New checks should call the same PDP rather than add another inline condition:

- **MCP tool calls**: `PolicyAction.CallTool(tool, mutating)` with `PolicyResource(type = "tool", tool = ...)`. A mutating call proposed from non-local context needs a person.
- **Context taint**: set `PolicyContext.provenance = PolicyProvenance.Untrusted` when the action was proposed from unlabelled artifacts, repository files or research output (THREAT_MODEL open item on injection scanning).
- **Delegation**: `PolicyAction.Delegate(target)`. Delegation proposed from non-local context needs a person.

Hook rules only ever add a gate. A new rule must not turn an existing `Deny` or `RequireApproval` into `Allow`, and must keep every THREAT_MODEL invariant. `PolicyDecisionPoint` is a `fun interface`, so a stricter implementation can be passed where a call site accepts one (for example `ApprovalGateCoordinator.requireDecider(policy = ...)`).

## Decision log

- **In-process, AuthZEN-shaped.** An external PDP was rejected (Workspace Report gap analysis: "Externalized policy engine"). The AuthZEN shape keeps the request portable if one is ever needed.
- **Behaviour-preserving.** Routing the existing checks through the PDP changed no outcome and no error message. Invariant 4 is still enforced in all three places: the static graph check, the runtime readiness check, and the remote-lease refusal.
- **Facts gathered by the caller.** The PDP does not read persistence. Graph walks (approval ancestors) stay with the code that owns the graph, which keeps the PDP pure and testable.
- **Only non-trivial decisions are audited.** Recording every allow would flood the timeline. Denials and gates are the decisions a reviewer needs.
