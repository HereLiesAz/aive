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
