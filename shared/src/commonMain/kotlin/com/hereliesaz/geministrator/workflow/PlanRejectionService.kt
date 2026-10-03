package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ApprovalGateId
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.providers.ProviderActionResult

class PlanRejectionService(
    private val gateRepository: ApprovalGateRepository,
    private val gateCoordinator: ApprovalGateCoordinator,
    private val sessionGateway: ManagedSessionGateway,
) {
    /** Rejects a plan gate; [handle] is null for an engine-held plan, which has no session to cancel. */
    suspend fun rejectPlan(
        gateId: ApprovalGateId,
        handle: ManagedSessionHandle?,
        decidedByRoleId: RoleDefinitionId? = null,
        note: String? = null,
        nowEpochMillis: Long,
    ): ApprovalGate {
        val gate = requireNotNull(gateRepository.get(gateId)) {
            "Approval gate ${gateId.value} does not exist"
        }
        require(gate.kind == ApprovalGateKind.PlanApproval) {
            "Approval gate ${gateId.value} is not a plan gate"
        }
        require(gate.requiredRoleId == null || gate.requiredRoleId == decidedByRoleId) {
            "Role ${decidedByRoleId?.value ?: "<human>"} is not authorized for gate ${gateId.value}"
        }
        require(gate.status == ApprovalGateStatus.Pending) {
            "Approval gate ${gateId.value} cannot be rejected while ${gate.status}"
        }

        val rejectionNote = note ?: "Plan rejected in application"
        gateCoordinator.claimPlanApproval(
            id = gateId,
            intent = ApprovalDecisionIntent.Reject,
            decidedByRoleId = decidedByRoleId,
            note = rejectionNote,
        )

        when (val cancellation = handle?.let { sessionGateway.cancel(it) } ?: ProviderActionResult.Accepted) {
            ProviderActionResult.Accepted -> Unit
            is ProviderActionResult.Rejected -> error(
                "Provider session ${handle?.providerRunId?.value} could not be cancelled after plan rejection: " +
                    cancellation.reason.ifBlank { "provider rejected cancellation" },
            )
        }

        return gateCoordinator.decide(
            id = gateId,
            approved = false,
            decidedByRoleId = decidedByRoleId,
            note = rejectionNote,
            nowEpochMillis = nowEpochMillis,
        )
    }
}
