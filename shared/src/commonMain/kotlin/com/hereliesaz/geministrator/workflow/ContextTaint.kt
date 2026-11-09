package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.a2a.A2aAgentConnection
import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ApprovalGateId
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.CONTEXT_PROVENANCE_METADATA_KEY
import com.hereliesaz.geministrator.domain.ContextProvenance
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.isUntrusted
import com.hereliesaz.geministrator.domain.isUntrustedContext
import com.hereliesaz.geministrator.domain.labelledAtIntake
import com.hereliesaz.geministrator.domain.releasedDependencies

/*
 * Context taint (docs/THREAT_MODEL.md, "Context provenance"). Every artifact is labelled with where
 * it came from as the runtime takes it in. A task that can change things (write a repository, deploy,
 * call an external service, run a script) and whose inputs include external or unknown content is
 * held at a human approval gate, naming those inputs, before it runs. Keeper-written content and the
 * project's own repository never trigger the gate; anything made from untrusted content carries the
 * taint on. This only adds gates: the definition-level HumanApproval rule (invariant 4) still applies.
 */

/** [com.hereliesaz.geministrator.domain.BlockingReason.code] of a task held at its context-taint gate. */
const val CONTEXT_TAINT_BLOCKING_CODE: String = "context_taint"

/** Role capabilities that let an agent change things outside its own output. */
internal val MUTATING_CAPABILITIES: Set<AgentCapability> = setOf(
    AgentCapability.RepositoryWrite,
    AgentCapability.ShellExecution,
    AgentCapability.PullRequestCreation,
)

/** The artifacts [task] receives as context: those of its released dependencies. */
fun WorkflowDefinition.contextInputs(run: WorkflowRun, task: TaskDefinition): List<ArtifactRef> =
    releasedDependencies(task)
        .mapNotNull(run.taskRuns::get)
        .flatMap(TaskRun::artifacts)

/**
 * Everything [task] reads that the taint rule weighs: its dependencies' artifacts and, when the run's
 * objective did not come from the keeper's own typing (a remote agent's launch, a schedule fed
 * model-written evidence), the objective itself, which every task receives, a task with no dependencies
 * included.
 */
fun WorkflowDefinition.taintInputs(run: WorkflowRun, task: TaskDefinition): List<ArtifactRef> =
    listOfNotNull(run.objectiveAsInput()) + contextInputs(run, task)

/** The run's objective as an input, labelled with where it came from; null when the keeper wrote it. */
fun WorkflowRun.objectiveAsInput(): ArtifactRef? {
    if (!objectiveProvenance.isUntrusted()) return null
    return ArtifactRef(
        id = ArtifactId("objective:${id.value}"),
        kind = ArtifactKind.Requirement,
        taskRunId = TaskRunId(id.value),
        label = "Run objective",
        textContent = objective,
        metadata = mapOf(CONTEXT_PROVENANCE_METADATA_KEY to objectiveProvenance.name),
        createdAtEpochMillis = createdAtEpochMillis,
    )
}

/**
 * Whether [this] executor can change something: a repository, a deployment, an external service, or
 * the host through a script or test command. An agent can when its [role] may write the repository,
 * open pull requests or run shell commands; an unknown role is assumed to.
 */
fun TaskExecutor.canMutate(role: RoleDefinition?): Boolean = when (this) {
    is TaskExecutor.RoleAgent -> role == null || role.capabilitiesRequired.any { it in MUTATING_CAPABILITIES }
    is TaskExecutor.GitHubAction,
    is TaskExecutor.Script,
    is TaskExecutor.TestRunner,
    is TaskExecutor.Deployment,
    is TaskExecutor.ExternalService,
    -> true
    is TaskExecutor.RepositoryOperation -> isMutation()
    // A child run's own tasks are checked against their own inputs.
    is TaskExecutor.NestedWorkflow,
    is TaskExecutor.HumanApproval,
    -> false
    is TaskExecutor.Distributed -> delegate.canMutate(role)
}

/**
 * The label the runtime gives what [this] executor produces. A research agent's findings and anything
 * from an external service, deployment or another pool device are external; null keeps the labels a
 * nested child run already gave its artifacts.
 */
fun TaskExecutor.outputProvenance(role: RoleDefinition?): ContextProvenance? = when (this) {
    is TaskExecutor.RoleAgent ->
        if (role != null && AgentCapability.Research in role.capabilitiesRequired) {
            ContextProvenance.ExternalContent
        } else {
            ContextProvenance.AgentArtifact
        }
    is TaskExecutor.GitHubAction,
    is TaskExecutor.Script,
    is TaskExecutor.TestRunner,
    -> ContextProvenance.AgentArtifact
    is TaskExecutor.RepositoryOperation -> ContextProvenance.RepositoryContent
    is TaskExecutor.Deployment,
    is TaskExecutor.ExternalService,
    // Its result is self-attested by whichever pool member ran it.
    is TaskExecutor.Distributed,
    -> ContextProvenance.ExternalContent
    is TaskExecutor.HumanApproval -> ContextProvenance.KeeperWritten
    is TaskExecutor.NestedWorkflow -> null
}

/**
 * The label for what an agent produced on [providerId]: a remote A2A agent's output is
 * [ContextProvenance.ExternalContent] whatever its role says (docs/A2A.md), since it was made
 * outside the keeper's control. Any other provider keeps [assigned].
 */
fun outputProvenanceOnProvider(assigned: ContextProvenance?, providerId: AgentProviderId?): ContextProvenance? =
    if (providerId != null && providerId.value.startsWith(A2aAgentConnection.PROVIDER_ID_PREFIX)) {
        ContextProvenance.ExternalContent
    } else {
        assigned
    }

/** Labels [artifacts] made by [task] with [assigned], flagging them when the task read untrusted input. */
fun WorkflowDefinition.labelTaskOutputs(
    run: WorkflowRun,
    task: TaskDefinition,
    assigned: ContextProvenance?,
    artifacts: List<ArtifactRef>,
): List<ArtifactRef> {
    if (artifacts.isEmpty()) return artifacts
    val untrustedInput = taintInputs(run, task).any(ArtifactRef::isUntrustedContext)
    return artifacts.map { it.labelledAtIntake(assigned, untrustedInput) }
}

/**
 * The inputs that hold [task] at a context-taint gate before [executor] runs it: its untrusted inputs
 * when the executor can change something, else none.
 */
fun WorkflowDefinition.untrustedInputsForPrivilegedTask(
    run: WorkflowRun,
    task: TaskDefinition,
    executor: TaskExecutor,
    role: RoleDefinition?,
): List<ArtifactRef> {
    if (!executor.canMutate(role)) return emptyList()
    return taintInputs(run, task).filter(ArtifactRef::isUntrustedContext)
}

/** The gate id for [taskId]'s context-taint approval on [attempt]. */
fun contextTaintGateId(runId: WorkflowRunId, taskId: TaskDefinitionId, attempt: Int): ApprovalGateId =
    ApprovalGateId("taint:${runId.value}:${taskId.value}:$attempt")

/** The gate reason: which inputs are external. */
fun contextTaintReason(task: TaskDefinition, untrusted: List<ArtifactRef>): String =
    "'${task.name}' can change things and its inputs include external or unknown content: " +
        untrusted.joinToString { "${it.label} (${it.kind.name})" } +
        ". Approve only if that content is safe to act on."

/** Held at its context-taint gate: waiting for a person to approve acting on external inputs. */
fun TaskRun.isHeldAtContextTaintGate(): Boolean =
    status == TaskRunStatus.AwaitingApproval && blockingReason?.code == CONTEXT_TAINT_BLOCKING_CODE
