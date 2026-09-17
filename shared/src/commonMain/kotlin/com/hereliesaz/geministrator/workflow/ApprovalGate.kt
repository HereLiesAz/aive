package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ApprovalGateId
import com.hereliesaz.geministrator.domain.RoleAuthority
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRunId
import kotlinx.serialization.Serializable

@Serializable
enum class ApprovalGateKind {
    PlanApproval,
    ScopeChange,
    SecurityRisk,
    IntegrationApproval,
    ReleaseApproval,
    FailureEscalation,
    HallMonitorOrchestratorReview,
    HallMonitorHumanReview,
}

@Serializable
enum class ApprovalGateStatus {
    Pending,
    Applying,
    Approved,
    Rejected,
}

@Serializable
enum class ApprovalDecisionIntent {
    Approve,
    Reject,
}

@Serializable
data class ApprovalGate(
    val id: ApprovalGateId,
    val workflowRunId: WorkflowRunId,
    val taskDefinitionId: TaskDefinitionId?,
    val kind: ApprovalGateKind,
    val reason: String,
    val requiredAuthority: RoleAuthority? = null,
    val requiredRoleId: RoleDefinitionId? = null,
    val requiresHuman: Boolean = false,
    val status: ApprovalGateStatus = ApprovalGateStatus.Pending,
    val applyingIntent: ApprovalDecisionIntent? = null,
    val decidedByRoleId: RoleDefinitionId? = null,
    val decisionNote: String? = null,
    val createdAtEpochMillis: Long,
    val decidedAtEpochMillis: Long? = null,
) {
    fun applying(
        intent: ApprovalDecisionIntent,
        decidedByRoleId: RoleDefinitionId?,
        note: String?,
    ): ApprovalGate = copy(
        status = ApprovalGateStatus.Applying,
        applyingIntent = intent,
        decidedByRoleId = decidedByRoleId,
        decisionNote = note,
        decidedAtEpochMillis = null,
    )

    fun approve(decidedByRoleId: RoleDefinitionId?, note: String?, nowEpochMillis: Long): ApprovalGate = copy(
        status = ApprovalGateStatus.Approved,
        applyingIntent = null,
        decidedByRoleId = decidedByRoleId,
        decisionNote = note,
        decidedAtEpochMillis = nowEpochMillis,
    )

    fun reject(decidedByRoleId: RoleDefinitionId?, note: String?, nowEpochMillis: Long): ApprovalGate = copy(
        status = ApprovalGateStatus.Rejected,
        applyingIntent = null,
        decidedByRoleId = decidedByRoleId,
        decisionNote = note,
        decidedAtEpochMillis = nowEpochMillis,
    )
}

interface ApprovalGateRepository {
    suspend fun put(gate: ApprovalGate)
    suspend fun get(id: ApprovalGateId): ApprovalGate?
    suspend fun unresolved(workflowRunId: WorkflowRunId): List<ApprovalGate>
}
