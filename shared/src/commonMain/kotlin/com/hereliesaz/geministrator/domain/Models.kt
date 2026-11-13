package com.hereliesaz.geministrator.domain

import kotlinx.serialization.Serializable

@Serializable
enum class RepositorySource {
    GitHub,
    GitLab,
    Local,
}

@Serializable
data class RepositoryRef(
    val owner: String,
    val name: String,
    val defaultBranch: String? = null,
    val source: RepositorySource = RepositorySource.GitHub,
    val remoteUrl: String? = null,
    val localPath: String? = null,
)

@Serializable
data class Project(
    val id: ProjectId,
    val name: String,
    val repository: RepositoryRef? = null,
    val defaultWorkflowTemplateId: WorkflowTemplateId? = null,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

@Serializable
data class AcceptanceCriterion(
    val description: String,
)

@Serializable
sealed interface TaskExecutor {
    @Serializable data class RoleAgent(val roleId: RoleDefinitionId) : TaskExecutor
    @Serializable data class GitHubAction(
        val workflow: String,
        val ref: String? = null,
        val contextInput: String? = "aive_context",
        val inputs: Map<String, String> = emptyMap(),
    ) : TaskExecutor
    @Serializable data class Script(
        val language: ScriptLanguage,
        val source: String,
        val runner: ScriptRunner,
    ) : TaskExecutor
    @Serializable data class TestRunner(val command: String? = null) : TaskExecutor
    @Serializable data class Deployment(val environment: String) : TaskExecutor
    @Serializable data class RepositoryOperation(val operation: String) : TaskExecutor
    @Serializable data class HumanApproval(val label: String = "Human approval") : TaskExecutor
    @Serializable data class ExternalService(val service: String, val operation: String? = null) : TaskExecutor
    @Serializable data class NestedWorkflow(
        val workflowDefinitionId: WorkflowDefinitionId,
        val projectId: ProjectId? = null,
    ) : TaskExecutor

    /**
     * Execute [delegate] on another authenticated Haive compute node.
     *
     * Distribution changes placement only; the delegated executor remains the semantic source of
     * what the task actually does.
     */
    @Serializable data class Distributed(
        val delegate: TaskExecutor,
        val requirements: DistributedComputeRequirements = DistributedComputeRequirements(),
        val label: String? = null,
    ) : TaskExecutor {
        init {
            require(delegate !is Distributed) { "Distributed executors may not wrap another Distributed executor" }
            require(label == null || label.isNotBlank()) { "Distributed executor label must not be blank" }
        }
    }
}

fun RoleExecutionSource.toTaskExecutor(roleId: RoleDefinitionId): TaskExecutor = when (this) {
    RoleExecutionSource.Agent -> TaskExecutor.RoleAgent(roleId)
    is RoleExecutionSource.GitHubAction -> TaskExecutor.GitHubAction(
        workflow = workflow,
        ref = ref,
        contextInput = contextInput,
    )
    is RoleExecutionSource.Script -> TaskExecutor.Script(
        language = language,
        source = source,
        runner = runner,
    )
}

fun TaskDefinition.effectiveExecutor(role: RoleDefinition?): TaskExecutor = executor
    ?: role?.executionSource?.toTaskExecutor(role.id)
    ?: roleId?.let(TaskExecutor::RoleAgent)
    ?: error("Task ${id.value} has neither an executor nor a responsible role")

fun TaskDefinition.effectiveExecutor(): TaskExecutor = effectiveExecutor(null)

fun TaskExecutor.displayName(): String = when (this) {
    is TaskExecutor.RoleAgent -> "Agent"
    is TaskExecutor.GitHubAction -> "GitHub Action"
    is TaskExecutor.Script -> when (language) {
        ScriptLanguage.JavaScript -> "JavaScript"
        ScriptLanguage.Python -> "Python"
    }
    is TaskExecutor.TestRunner -> "Test Runner"
    is TaskExecutor.Deployment -> "Deployment"
    is TaskExecutor.RepositoryOperation -> "Repository Operation"
    is TaskExecutor.HumanApproval -> label
    is TaskExecutor.ExternalService -> service
    is TaskExecutor.NestedWorkflow -> "Nested Workflow"
    is TaskExecutor.Distributed -> label ?: "Distributed ${delegate.displayName()}"
}

/**
 * Condition under which a task is eligible to become Ready after its dependencies complete.
 * [Always] is the default: run whenever all [TaskDefinition.dependsOn] tasks are Completed.
 * [OnAnyOutcome] makes the task run regardless of whether a specific referenced task succeeded or
 * failed (useful for cleanup/notification branches). [OnFailure] is the mirror of the default —
 * only eligible when the named task reached a Failed/Escalated/Cancelled terminal state.
 *
 * The task named by a non-[Always] condition is itself a scheduling edge. It need not also appear
 * in [TaskDefinition.dependsOn], and readiness must not resolve the condition until that task is terminal.
 */
@Serializable
sealed interface TaskCondition {
    @Serializable data object Always : TaskCondition
    @Serializable data class OnAnyOutcome(val ofTask: TaskDefinitionId) : TaskCondition
    @Serializable data class OnFailure(val ofTask: TaskDefinitionId) : TaskCondition
}

@Serializable
data class TaskDefinition(
    val id: TaskDefinitionId,
    val name: String,
    val objective: String,
    val roleId: RoleDefinitionId?,
    /**
     * The authority this task is exercising, when the workflow knows it explicitly. This prevents
     * broad/generalist roles from making every task look like implementation work.
     */
    val requiredRoleAuthority: RoleAuthority? = null,
    val dependsOn: Set<TaskDefinitionId> = emptySet(),
    val condition: TaskCondition = TaskCondition.Always,
    val acceptanceCriteria: List<AcceptanceCriterion> = emptyList(),
    val requiredArtifacts: Set<ArtifactKind> = emptySet(),
    val approvalPolicy: ApprovalPolicy = ApprovalPolicy.None,
    val verificationPolicy: VerificationPolicy = VerificationPolicy.None,
    val retryPolicy: RetryPolicy = RetryPolicy(),
    val escalationPolicy: EscalationPolicy = EscalationPolicy.FailWorkflow,
    val providerConstraints: ProviderConstraints = ProviderConstraints.None,
    val environmentPlanningPolicy: EnvironmentPlanningPolicy = EnvironmentPlanningPolicy.WhenProviderRequires,
    val compoundInferencePolicy: CompoundInferencePolicy = CompoundInferencePolicy.Single,
    val computePlacement: ComputePlacementPolicy = ComputePlacementPolicy.LocalOnly,
    val executor: TaskExecutor? = null,
    /** Hold output (docs/Swarm-OS.md, keeper tools): its artifacts are withheld from every consumer. */
    val holdOutput: Boolean = false,
    /** How long the creature may work before the queen says it is taking too long; Auto for every older definition. */
    val timeLimit: TimeLimit = TimeLimit.Auto,
    /** The step contract when this task was made from a step template (docs/Step-Templates.md); null for every other task. */
    val contract: TaskContract? = null,
    /**
     * An optional creature never ends the run when it fails: once its retries are used it is skipped, with its reason
     * kept on the step, and the run goes on (and can still complete). False for every older definition.
     */
    val optional: Boolean = false,
)

@Serializable
data class WorkflowDefinition(
    val id: WorkflowDefinitionId,
    val name: String,
    val description: String? = null,
    val tasks: List<TaskDefinition>,
    val integrationPolicy: IntegrationPolicy = IntegrationPolicy.PullRequest,
    val concurrencyPolicy: ConcurrencyPolicy = ConcurrencyPolicy(),
    val testDesignPolicy: TestDesignPolicy = TestDesignPolicy.BeforeAndAfterImplementation,
    val promptReusePolicy: PromptReusePolicy = PromptReusePolicy.PreferCache,
    val payloadRedactionPolicy: PayloadRedactionPolicy = PayloadRedactionPolicy(),
)

@Serializable
enum class WorkflowRunStatus { Created, Running, AwaitingHuman, Completed, Failed, Cancelled }

@Serializable
enum class TaskRunStatus {
    Created, Blocked, Ready, Planning, AwaitingApproval, Running, Verifying, Retrying, Completed, Failed, Escalated, Cancelled,
}

fun TaskRunStatus.isTerminal(): Boolean = this == TaskRunStatus.Completed ||
    this == TaskRunStatus.Failed ||
    this == TaskRunStatus.Escalated ||
    this == TaskRunStatus.Cancelled

@Serializable
data class BlockingReason(
    val code: String,
    val message: String,
)

/** Provider-reported usage for one task, accumulated across every report its sessions sent. */
@Serializable
data class TaskUsage(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val costUsd: Double? = null,
    /**
     * Some of this usage was billed but could not be priced (no cost reported, no price known for the
     * model), so [costUsd] is a floor and a dollar cap cannot trust it.
     */
    val costUnknown: Boolean = false,
) {
    val totalTokens: Long get() = inputTokens + outputTokens
}

@Serializable
data class TaskRun(
    val id: TaskRunId,
    val taskDefinitionId: TaskDefinitionId,
    val status: TaskRunStatus,
    val attempt: Int = 1,
    val assignedRoleId: RoleDefinitionId?,
    val assignedProviderId: AgentProviderId? = null,
    val providerRunId: ProviderRunId? = null,
    val artifacts: List<ArtifactRef> = emptyList(),
    val blockingReason: BlockingReason? = null,
    val progress: Float? = null,
    val progressMessage: String? = null,
    /** Durable provider plan/specification preview that was presented for approval. */
    val providerPlan: String? = null,
    val executor: TaskExecutor? = null,
    val externalRunId: String? = null,
    /** Tokens and cost the task's provider has reported so far, summed across reports; null if none. */
    val usage: TaskUsage? = null,
    /**
     * The message in which the task's agent asked the keeper a question it is waiting on
     * (docs/Agent-UI.md), or null. Read with `AgentUi.parseStored`; never executed or rendered as markup.
     */
    val keeperQuestion: String? = null,
    /** `AgentUi.questionKey` of the last question the keeper answered, so a replay of it stays answered. */
    val answeredKeeperQuestion: String? = null,
    /**
     * Providers this task already failed over from (their own failure: credit, key, rate limit,
     * offline, timeout, 5xx). The next attempt skips them, so each is tried once, and a failover
     * restart does not use up the task's retry budget.
     */
    val failedProviders: List<AgentProviderId> = emptyList(),
    /**
     * Attempts that only restarted work a spend limit had stopped (a role's token budget, a spend
     * cap): [attempt] still moves on for them, but they are not retries of failed work, so they do
     * not count against the task's retry budget ([com.hereliesaz.geministrator.workflow.FailurePolicyEvaluator]).
     */
    val heldAttempts: Int = 0,
    /** The active-time clock of the current attempt; null until the queen starts one (docs/Keepers-Room.md). */
    val clock: TaskClock? = null,
    /** Attempts stopped for taking longer than allowed; kept across attempts, reset by the keeper's Retry. */
    val timedOutAttempts: Int = 0,
    /** Why the last attempt was turned back (a failed output check), handed to the next attempt's prompt; null otherwise. */
    val lastFailure: String? = null,
) {
    init {
        require(progress == null || progress in 0f..1f) { "Task progress must be normalized 0f..1f" }
    }
}

@Serializable
data class WorkflowRun(
    val id: WorkflowRunId,
    val projectId: ProjectId,
    val workflowDefinitionId: WorkflowDefinitionId,
    val objective: String,
    val status: WorkflowRunStatus,
    val taskRuns: Map<TaskDefinitionId, TaskRun>,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    /** Repository identity captured when this run was created, for accurate historical views. */
    val repositorySnapshot: RepositoryRef? = null,
    /** Exact role definitions used by this run, including package-local roles. */
    val roleSnapshot: List<RoleDefinition> = emptyList(),
    /**
     * The hive this run follows: the library copy under the same id can be saved over while the run is
     * live, and a run resumes against what it started with (plus the job and criteria edits it took),
     * never against a later structural edit. Null on a run saved before this existed, which falls back
     * to the saved hive.
     */
    val definitionSnapshot: WorkflowDefinition? = null,
    val globalPause: WorkflowGlobalPause? = null,
    /**
     * Workflow runs this run continues: one parent for a continuation, several for a merge that
     * combines lineages. Empty for a root run. Memory derives its lineage banks from these.
     */
    val parentWorkflowRunIds: List<WorkflowRunId> = emptyList(),
    /** Set while a keeper's spend cap has stopped this run; the keeper lifts it. */
    val spendPause: WorkflowSpendPause? = null,
    /**
     * The keeper lifted a spend stop for usage that could not be priced: this run no longer stops for
     * spend a dollar cap cannot count (known cost still counts toward the caps).
     */
    val unpricedCostAccepted: Boolean = false,
    /** Set while the keeper has paused the hive: nothing new starts, what runs finishes. */
    val keeperPause: WorkflowKeeperPause? = null,
    /**
     * Where the objective came from. Every task reads it, so an objective that did not come from the
     * keeper's own typing (a remote agent's launch, a scheduled launch fed model-written evidence) counts as
     * an input to every task for the context-taint rule. A run saved before this existed reads as keeper-written.
     */
    val objectiveProvenance: ContextProvenance = ContextProvenance.KeeperWritten,
) {
    init {
        require(id !in parentWorkflowRunIds) { "A workflow run cannot continue itself" }
        taskRuns.forEach { (key, taskRun) ->
            require(key == taskRun.taskDefinitionId) {
                "taskRuns map key ${key.value} does not match TaskRun.taskDefinitionId ${taskRun.taskDefinitionId.value}"
            }
        }
    }
}

/** [task]'s dependencies whose output reaches it: everything it depends on, minus tasks holding their output. */
fun WorkflowDefinition.releasedDependencies(task: TaskDefinition): List<TaskDefinitionId> {
    val held = tasks.filter(TaskDefinition::holdOutput).mapTo(mutableSetOf(), TaskDefinition::id)
    return task.dependsOn.filterNot { it in held }
}
