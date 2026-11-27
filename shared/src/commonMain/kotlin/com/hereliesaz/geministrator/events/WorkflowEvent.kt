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

@Serializable
data class ApprovalRequired(
    override val workflowRunId: WorkflowRunId,
    val taskDefinitionId: TaskDefinitionId,
    val gateId: ApprovalGateId,
    val reason: String,
    override val occurredAtEpochMillis: Long,
) : WorkflowEvent

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
    /** How long the retry waits before it starts (a provider failure backs off); 0 for an immediate retry. */
    val delayMillis: Long = 0L,
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
    /** Billed but unpriced: [costUsd] does not cover this usage ([com.hereliesaz.geministrator.domain.TaskUsage.costUnknown]). */
    val costUnknown: Boolean = false,
    /** Tokens a replayed habit saved on this report (0 for a model run). */
    val tokensAvoided: Long = 0,
    /**
     * The model id the provider says it sent the request to; null when the provider does not expose one
     * (a CLI agent, an A2A peer, an on-device run, a replayed habit). Events written before this field
     * existed decode with null.
     */
    val model: String? = null,
    /** SHA-256 (hex) of the request text as sent to the provider; never the text. Null where the app cannot see that text. */
    val requestSha256: String? = null,
    /** Length in characters of that request text. Null with [requestSha256]. */
    val requestChars: Int? = null,
    override val occurredAtEpochMillis: Long,
    /** True if every report in this batch names the same model; null for legacy events. */
    val modelConsistent: Boolean? = null,
    /** Successful generation input artifact IDs, never content; null for legacy or unreported evidence. */
    val deliveredArtifactIds: Set<String>? = null,
    /** Stored memory node addresses carried by a successful generation's request, never content; null for legacy or unreported. */
    val deliveredMemoryAddresses: Set<String>? = null,
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
