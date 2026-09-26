# Recursive Workflow Composition

The Aive treats roles and workflows as points on the same composition continuum.

## Core rule

A role is the smallest valid workflow: one role, one task, one output contract. A workflow is a directed task graph. Any role-task or workflow node may be replaced by another workflow graph, recursively.

This is not an Azphalt-only feature. Store-installed workflows, Store-installed roles, built-in roles, user-created roles, and user-created workflows use the same composition machinery.

## Shared libraries

Installing an Azphalt workflow publishes:

- its workflow definitions to the installed workflow library;
- its bundled roles to the shared role repository;
- its exported fragments to the composition palette.

Installing a standalone role package publishes the same kind of reusable role. The package type describes what the store listing primarily sells; it does not create a second species of role.

Role ids are stable global ids. Multiple packages may reference the same role id only when the reusable role definition is equivalent. A conflicting definition with the same id is rejected rather than overwritten.

## Role as workflow

`WorkflowComposer.roleAsWorkflow()` materializes any role as a one-node `WorkflowDefinition` using `TaskExecutor.RoleAgent`.

That implicit workflow can be used anywhere another workflow can be used. It may remain a single-agent operation or later be expanded into an orchestrated event.

## Two workflow-composition modes

### Nested

`WorkflowComposer.nest()` adds a child workflow as a single `TaskExecutor.NestedWorkflow` node.

Use nesting when the child should preserve its own run boundary, lifecycle, artifacts, or implementation details. Nested workflows may themselves contain nested workflows.

#### Running a nested node

`ApplicationRuntime` registers `WorkflowRunNestedWorkflowClient` through `NestedWorkflowExecutorIntegration` on every platform. A nested node:

- starts a child `WorkflowRun` of the referenced definition, in the node's `projectId` when set and otherwise in the parent's project. The child uses the stored definition as-is and the parent run's role snapshot;
- stores the child run ID as the task run's `externalRunId`. The ID is derived from the parent task run and attempt (`nested-d<depth>-<taskRunId>-a<attempt>`), so a repeated dispatch or a reconcile after restart reattaches to the same child run and never starts a duplicate;
- advances the child one cycle on each parent reconcile, through a coordinator owned by that child, and reports the fraction of child tasks done as progress;
- completes when the child completes and fails when the child fails or is cancelled. The parent task's own retry and escalation policy then applies.

Safeguards:

- **Depth limit.** A child deeper than `WorkflowRunNestedWorkflowClient.DEFAULT_MAX_DEPTH` (4) is refused.
- **Cycle refusal.** Before starting, the client walks the nesting graph from the child definition, including nodes wrapped in `TaskExecutor.Distributed`. It refuses the start if the graph reaches the parent definition or contains any cycle. Every ancestor reaches the parent through nesting, so this also covers cycles through ancestors further up.
- **Approval gates.** `HumanApproval` tasks inside the child are ordinary gates and are never approved automatically. While one is pending, the parent node stays `Running` and its message names the gate; the inspector shows **Approve nested gate**, which approves that gate in the child (or grandchild) run. Plan approvals and failure escalations inside a child are named on the parent node but cannot yet be decided from it.

Refusals are not retried. Child artifacts stay on the child run and are not copied to the parent task.

### Inline

`WorkflowComposer.inline()` expands a child definition into the parent graph.

Inline composition:

- namespaces child task ids;
- remaps internal dependencies and branch conditions;
- connects selected parent predecessors to child entry points;
- connects child exit points to selected parent successors;
- optionally remaps child roles to any other installed roles.

Use inline composition when the user wants one editable graph, cross-workflow role substitution, or direct control of dependencies between the two workflows.

## Expanding a role into an orchestrated event

`WorkflowComposer.expandRoleTask()` replaces one role-agent node with a workflow.

The operation preserves:

- prerequisites of the original role node;
- the original node's branch condition;
- downstream dependency edges;
- downstream branch-condition references when the replacement has one unambiguous exit.

The replacement workflow may contain any number of roles and may itself contain nested or expanded workflows.

Example:

~~~text
Intake -> Researcher -> Publish
~~~

can become:

~~~text
Intake -> [Collect -> Corroborate -> Adversarial Review] -> Publish
~~~

without changing the surrounding workflow's contract.

## Composition palette

`AzphaltCompositionLibrary` exposes one authoring palette containing:

1. installed workflow definitions;
2. exported workflow fragments;
3. every persisted role represented by its implicit one-node workflow.

Generated project workflows are not mislabeled as installed Store workflows. They may still be edited or deliberately saved as reusable workflows through the authoring surface.

## Required Workflows UI behavior

The Workflows surface must:

- search and filter installed workflows separately from generated run history;
- load an installed workflow as a base definition;
- show reusable roles and workflow fragments as components;
- add another workflow either nested or inline;
- allow any role assignment to be replaced by any installed role;
- allow a role-task to be expanded into another workflow;
- namespace inline additions automatically and show collisions before committing them;
- validate the resulting graph before save or launch;
- preserve provenance so users can see which package originally supplied a workflow, fragment, or role.

## Runtime rule

The runtime role registry must be refreshed after package installation before a newly installed role is dispatched. Installation must not require an application restart to make a role usable.

The composed `WorkflowDefinition` is the durable execution contract. Package boundaries are provenance and update boundaries, not execution silos.
