package com.hereliesaz.geministrator.events

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ApprovalGateId
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.policy.PolicyEffect
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface WorkflowEvent {
    val workflowRunId: WorkflowRunId
    val occurredAtEpochMillis: Long
}

@Serializable
data class WorkflowCreated(
    override val workflowRunId: WorkflowRunId,
    val projectId: ProjectId? = null,
    val workflowDefinitionId: WorkflowDefinitionId? = null,
    val objective: String? = null,
    override val occurredAtEpochMillis: Long,
) : WorkflowEvent

@Serializable
data class TaskBecameReady(
    override val workflowRunId: WorkflowRunId,
    val taskDefinitionId: TaskDefinitionId,
    override val occurredAtEpochMillis: Long,
) : WorkflowEvent

@Serializable
data class ExecutorAssigned(
    override val workflowRunId: WorkflowRunId,
    val taskDefinitionId: TaskDefinitionId,
    val executor: TaskExecutor,
    val responsibilityRoleId: RoleDefinitionId? = null,
    override val occurredAtEpochMillis: Long,
) : WorkflowEvent

@Serializable
data class AgentAssigned(
    override val workflowRunId: WorkflowRunId,
    val taskDefinitionId: TaskDefinitionId,
    val roleId: RoleDefinitionId,
    override val occurredAtEpochMillis: Long,
) : WorkflowEvent

@Serializable
data class TaskStarted(
    override val workflowRunId: WorkflowRunId,
    val taskDefinitionId: TaskDefinitionId,
    val attempt: Int,
    override val occurredAtEpochMillis: Long,
) : WorkflowEvent

@ConsistentCopyVisibility
@Serializable
data class ApprovalRequired private constructor(
    override val workflowRunId: WorkflowRunId,
    val taskDefinitionId: TaskDefinitionId,
    val reason: String,
    override val occurredAtEpochMillis: Long,
    @SerialName("gateId") private val serializedGateId: ApprovalGateId? = null,
) : WorkflowEvent {
    constructor(
        workflowRunId: WorkflowRunId,
        taskDefinitionId: TaskDefinitionId,
        gateId: ApprovalGateId,
        reason: String,
        occurredAtEpochMillis: Long,
    ) : this(
        workflowRunId = workflowRunId,
        taskDefinitionId = taskDefinitionId,
        reason = reason,
        occurredAtEpochMillis = occurredAtEpochMillis,
        serializedGateId = gateId,
    )

    val gateId: ApprovalGateId
        get() = requireNotNull(serializedGateId) {
            "Legacy ApprovalRequired event for ${workflowRunId.value}/${taskDefinitionId.value} has no gateId"
        }

    val hasGateId: Boolean
        get() = serializedGateId != null
}

@Serializable
data class ApprovalDecisionReceived(
    override val workflowRunId: WorkflowRunId,
    val taskDefinitionId: TaskDefinitionId?,
    val gateId: ApprovalGateId,
    val approved: Boolean,
    val decidedByRoleId: RoleDefinitionId?,
    override val occurredAtEpochMillis: Long,
    /** Approved without asking, because the keeper delegated this gate (workflow/ProgressiveDelegation.kt). */
    val byDelegation: Boolean = false,
) : WorkflowEvent

@Serializable
data class ArtifactCreated(
    override val workflowRunId: WorkflowRunId,
    val taskDefinitionId: TaskDefinitionId,
    val artifact: ArtifactRef,
    override val occurredAtEpochMillis: Long,
) : WorkflowEvent

@Serializable
data class VerificationFailed(
    override val workflowRunId: WorkflowRunId,
    val taskDefinitionId: TaskDefinitionId,
    val reason: String,
    override val occurredAtEpochMillis: Long,
) : WorkflowEvent

@Serializable
data class RetryScheduled(
    override val workflowRunId: WorkflowRunId,
    val taskDefinitionId: TaskDefinitionId,
    val nextAttempt: Int,
    val reason: String,
    override val occurredAtEpochMillis: Long,
) : WorkflowEvent

@Serializable
data class TaskEscalated(
    override val workflowRunId: WorkflowRunId,
    val taskDefinitionId: TaskDefinitionId,
    val reason: String,
    val reassignedRoleId: RoleDefinitionId? = null,
    override val occurredAtEpochMillis: Long,
) : WorkflowEvent

@Serializable
data class TaskCompleted(
    override val workflowRunId: WorkflowRunId,
    val taskDefinitionId: TaskDefinitionId,
    override val occurredAtEpochMillis: Long,
) : WorkflowEvent

@Serializable
data class TaskFailed(
    override val workflowRunId: WorkflowRunId,
    val taskDefinitionId: TaskDefinitionId,
    val reason: String,
    override val occurredAtEpochMillis: Long,
) : WorkflowEvent

@Serializable
data class HumanDecisionRequired(
    override val workflowRunId: WorkflowRunId,
    val taskDefinitionId: TaskDefinitionId?,
    val reason: String,
    override val occurredAtEpochMillis: Long,
) : WorkflowEvent

@Serializable
data class WorkflowCompleted(
    override val workflowRunId: WorkflowRunId,
    override val occurredAtEpochMillis: Long,
) : WorkflowEvent

@Serializable
data class WorkflowFailed(
    override val workflowRunId: WorkflowRunId,
    val reason: String,
    override val occurredAtEpochMillis: Long,
) : WorkflowEvent

@Serializable
data class WorkflowCancelled(
    override val workflowRunId: WorkflowRunId,
    override val occurredAtEpochMillis: Long,
) : WorkflowEvent

@Serializable
data class TaskCancelled(
    override val workflowRunId: WorkflowRunId,
    val taskDefinitionId: TaskDefinitionId,
    override val occurredAtEpochMillis: Long,
) : WorkflowEvent

/**
 * Cancelling the remote work behind a task (a GitHub Actions run, a nested child run, a
 * distributed lease, an external service run) failed while its workflow run was being cancelled.
 * The local cancellation still stands; the remote work may keep running until it ends on its own.
 */
@Serializable
data class ExternalRunCancelFailed(
    override val workflowRunId: WorkflowRunId,
    val taskDefinitionId: TaskDefinitionId,
    val externalRunId: String,
    val reason: String,
    override val occurredAtEpochMillis: Long,
) : WorkflowEvent

/**
 * The keeper ran a finished run again from one of its tasks ([taskDefinitionId]). Recorded on the
 * new run, which continues [fromWorkflowRunId]: [rerunTaskIds] start fresh there, every other task
 * keeps the result it had. [editedObjective] is the task's new objective when the keeper changed it;
 * [outputReplaced] means the task itself kept its place but its output text is the keeper's.
 */
@Serializable
data class TaskRerunRequested(
    override val workflowRunId: WorkflowRunId,
    val taskDefinitionId: TaskDefinitionId,
    val fromWorkflowRunId: WorkflowRunId,
    val rerunTaskIds: List<TaskDefinitionId>,
    val editedObjective: String? = null,
    val outputReplaced: Boolean = false,
    override val occurredAtEpochMillis: Long,
) : WorkflowEvent

/** Opportunistic telemetry from providers that expose usage data. Never affects run state. */
@Serializable
data class ProviderUsageRecorded(
    override val workflowRunId: WorkflowRunId,
    val taskDefinitionId: TaskDefinitionId,
    val providerId: AgentProviderId,
    val providerRunId: ProviderRunId,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val costUsd: Double? = null,
    val cacheHitFraction: Float? = null,
    val latencyMillis: Long? = null,
    override val occurredAtEpochMillis: Long,
) : WorkflowEvent

/**
 * A non-trivial decision (deny or require-approval) from the in-process policy decision point,
 * kept on the run timeline for audit. Fields are plain values so the record outlives rule changes;
 * see docs/architecture/POLICY.md.
 */
@Serializable
data class PolicyDecisionRecorded(
    override val workflowRunId: WorkflowRunId,
    val taskDefinitionId: TaskDefinitionId? = null,
    val effect: PolicyEffect,
    val code: String,
    val action: String,
    val subjectType: String,
    val subjectId: String? = null,
    val resourceType: String,
    val resourceId: String? = null,
    val reason: String,
    override val occurredAtEpochMillis: Long,
) : WorkflowEvent
