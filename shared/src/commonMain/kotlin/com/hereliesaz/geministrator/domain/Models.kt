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
    @Serializable data class GitHubAction(val workflow: String, val ref: String? = null) : TaskExecutor
    @Serializable data class TestRunner(val command: String? = null) : TaskExecutor
    @Serializable data class Deployment(val environment: String) : TaskExecutor
    @Serializable data class RepositoryOperation(val operation: String) : TaskExecutor
    @Serializable data class HumanApproval(val label: String = "Human approval") : TaskExecutor
    @Serializable data class ExternalService(val service: String, val operation: String? = null) : TaskExecutor
    @Serializable data class NestedWorkflow(
        val workflowDefinitionId: WorkflowDefinitionId,
        val projectId: ProjectId? = null,
    ) : TaskExecutor
}

fun TaskDefinition.effectiveExecutor(): TaskExecutor = executor
    ?: roleId?.let(TaskExecutor::RoleAgent)
    ?: error("Task ${id.value} has neither an executor nor a responsible role")

fun TaskExecutor.displayName(): String = when (this) {
    is TaskExecutor.RoleAgent -> "Agent"
    is TaskExecutor.GitHubAction -> "GitHub Action"
    is TaskExecutor.TestRunner -> "Test Runner"
    is TaskExecutor.Deployment -> "Deployment"
    is TaskExecutor.RepositoryOperation -> "Repository Operation"
    is TaskExecutor.HumanApproval -> label
    is TaskExecutor.ExternalService -> service
    is TaskExecutor.NestedWorkflow -> "Nested Workflow"
}

/**
 * Condition under which a task is eligible to become Ready after its dependencies complete.
 * [Always] is the default: run whenever all [TaskDefinition.dependsOn] tasks are Completed.
 * [OnAnyOutcome] makes the task run regardless of whether a specific dependency succeeded or failed
 * (useful for cleanup/notification branches). [OnFailure] is the mirror of the default — only
 * eligible when the named dependency reached a Failed/Escalated terminal state.
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
    val executor: TaskExecutor? = null,
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
    val executor: TaskExecutor? = null,
    val externalRunId: String? = null,
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
    val globalPause: WorkflowGlobalPause? = null,
) {
    init {
        taskRuns.forEach { (key, taskRun) ->
            require(key == taskRun.taskDefinitionId) {
                "taskRuns map key ${key.value} does not match TaskRun.taskDefinitionId ${taskRun.taskDefinitionId.value}"
            }
        }
    }
}
