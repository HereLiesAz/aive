package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ApprovalGateId
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.events.ApprovalDecisionReceived
import com.hereliesaz.geministrator.providers.ProviderActionResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val planApprovalMutex = Mutex()

class WorkflowApprovalService(
    private val gateRepository: ApprovalGateRepository,
    private val gateCoordinator: ApprovalGateCoordinator,
    private val sessionGateway: ManagedSessionGateway,
    private val failureEscalationDecisionStore: FailureEscalationDecisionStore? = null,
) {
    suspend fun ensurePlanGate(
        run: WorkflowRun,
        taskDefinitionId: TaskDefinitionId,
        gateIdFactory: (TaskDefinitionId) -> ApprovalGateId,
        nowEpochMillis: Long,
    ): ApprovalGate {
        val existing = gateRepository.unresolved(run.id).firstOrNull {
            it.taskDefinitionId == taskDefinitionId && it.kind == ApprovalGateKind.PlanApproval
        }
        if (existing != null) return existing

        return gateCoordinator.open(
            id = gateIdFactory(taskDefinitionId),
            workflowRunId = run.id,
            taskDefinitionId = taskDefinitionId,
            kind = ApprovalGateKind.PlanApproval,
            reason = "Provider plan requires independent approval before execution.",
            requiredRoleId = BuiltInRoles.Architect.id,
            nowEpochMillis = nowEpochMillis,
        )
    }

    /**
     * Approves a plan gate. [handle] is the provider session that drafted the plan, or null for an
     * engine-held plan (see EnginePlanGate.kt): nothing runs yet, so the decision is only recorded and
     * the next cycle makes the task dispatchable.
     */
    suspend fun approvePlan(
        gateId: ApprovalGateId,
        handle: ManagedSessionHandle?,
        decidedByRoleId: RoleDefinitionId?,
        note: String?,
        nowEpochMillis: Long,
    ): ApprovalGate = planApprovalMutex.withLock {
        val gate = requireNotNull(gateRepository.get(gateId)) {
            "Approval gate ${gateId.value} does not exist"
        }
        require(gate.kind == ApprovalGateKind.PlanApproval) {
            "Approval gate ${gateId.value} is not a plan gate"
        }
        require(gate.requiredRoleId == null || gate.requiredRoleId == decidedByRoleId) {
            "Role ${decidedByRoleId?.value ?: "<human>"} is not authorized for gate ${gateId.value}"
        }

        if (handle == null) {
            require(gate.status == ApprovalGateStatus.Pending || gate.status == ApprovalGateStatus.Applying) {
                "Approval gate ${gateId.value} is already resolved"
            }
            return@withLock gateCoordinator.decide(
                id = gateId,
                approved = true,
                decidedByRoleId = decidedByRoleId,
                note = note,
                nowEpochMillis = nowEpochMillis,
            )
        }
        if (gate.status == ApprovalGateStatus.Applying) {
            return@withLock recoverApplyingPlanGate(
                gate = gate,
                handle = handle,
                nowEpochMillis = nowEpochMillis,
            )
        }
        require(gate.status == ApprovalGateStatus.Pending) {
            "Approval gate ${gateId.value} is already resolved"
        }

        gateCoordinator.claimPlanApproval(
            id = gateId,
            intent = ApprovalDecisionIntent.Approve,
            decidedByRoleId = decidedByRoleId,
            note = note,
        )

        when (val providerResult = sessionGateway.approvePlan(handle)) {
            ProviderActionResult.Accepted -> gateCoordinator.decide(
                id = gateId,
                approved = true,
                decidedByRoleId = decidedByRoleId,
                note = note,
                nowEpochMillis = nowEpochMillis,
            )

            is ProviderActionResult.Rejected -> {
                cancelRejectedPlanSession(handle, providerResult.reason)
                gateCoordinator.decide(
                    id = gateId,
                    approved = false,
                    decidedByRoleId = decidedByRoleId,
                    note = providerResult.reason,
                    nowEpochMillis = nowEpochMillis,
                )
            }
        }
    }

    private suspend fun recoverApplyingPlanGate(
        gate: ApprovalGate,
        handle: ManagedSessionHandle,
        nowEpochMillis: Long,
    ): ApprovalGate = when (sessionGateway.status(handle)) {
        ManagedSessionStatus.Running,
        ManagedSessionStatus.Completed,
        -> gateCoordinator.decide(
            id = gate.id,
            approved = true,
            decidedByRoleId = gate.decidedByRoleId,
            note = gate.decisionNote,
            nowEpochMillis = nowEpochMillis,
        )

        ManagedSessionStatus.Failed -> gateCoordinator.decide(
            id = gate.id,
            approved = false,
            decidedByRoleId = gate.decidedByRoleId,
            note = "Provider failed while applying plan approval",
            nowEpochMillis = nowEpochMillis,
        )

        ManagedSessionStatus.Planning,
        ManagedSessionStatus.AwaitingApproval,
        -> {
            cancelRejectedPlanSession(
                handle = handle,
                reason = "Plan approval remained unconfirmed during recovery",
            )
            gateCoordinator.decide(
                id = gate.id,
                approved = false,
                decidedByRoleId = gate.decidedByRoleId,
                note = "Plan approval was not applied; provider session cancelled during recovery",
                nowEpochMillis = nowEpochMillis,
            )
        }

        ManagedSessionStatus.Unknown -> error(
            "Approval gate ${gate.id.value} has an in-flight provider decision that cannot yet be reconciled",
        )
    }

    private suspend fun cancelRejectedPlanSession(
        handle: ManagedSessionHandle,
        reason: String,
    ) {
        when (val cancellation = sessionGateway.cancel(handle)) {
            ProviderActionResult.Accepted -> Unit
            is ProviderActionResult.Rejected -> error(
                "Provider session ${handle.providerRunId.value} could not be cancelled after plan rejection: " +
                    "${cancellation.reason.ifBlank { reason }}",
            )
        }
    }

    suspend fun decideFailureEscalation(
        run: WorkflowRun,
        gateId: ApprovalGateId,
        approved: Boolean,
        decidedByRoleId: RoleDefinitionId?,
        note: String?,
        nowEpochMillis: Long,
    ): WorkflowRun {
        require(!run.status.isTerminal()) {
            "Workflow ${run.id.value} is already ${run.status}"
        }
        val gate = requireNotNull(gateRepository.get(gateId)) {
            "Approval gate ${gateId.value} does not exist"
        }
        require(gate.workflowRunId == run.id) {
            "Approval gate ${gateId.value} belongs to another workflow"
        }
        require(gate.kind == ApprovalGateKind.FailureEscalation) {
            "Approval gate ${gateId.value} is not a failure escalation gate"
        }
        require(gate.status == ApprovalGateStatus.Pending) {
            "Approval gate ${gateId.value} is already resolved"
        }
        require(gate.requiredRoleId == null || gate.requiredRoleId == decidedByRoleId) {
            "Role ${decidedByRoleId?.value ?: "<human>"} is not authorized for gate ${gateId.value}"
        }

        val taskDefinitionId = requireNotNull(gate.taskDefinitionId) {
            "Failure escalation gate ${gateId.value} has no task"
        }
        val taskRun = requireNotNull(run.taskRuns[taskDefinitionId]) {
            "Task run ${taskDefinitionId.value} is missing"
        }
        require(taskRun.status == TaskRunStatus.Escalated) {
            "Task ${taskDefinitionId.value} is ${taskRun.status}, not Escalated"
        }

        val nextRun = if (approved) {
            TaskRunTransitions.requireAllowed(TaskRunStatus.Escalated, TaskRunStatus.Retrying)
            run.copy(
                status = WorkflowRunStatus.Running,
                taskRuns = run.taskRuns + (
                    taskDefinitionId to taskRun.copy(
                        status = TaskRunStatus.Retrying,
                        attempt = taskRun.attempt + 1,
                        assignedProviderId = null,
                        providerRunId = null,
                        externalRunId = null,
                        artifacts = emptyList(),
                        blockingReason = null,
                        progress = null,
                        progressMessage = note ?: "Failure escalation approved; retry scheduled",
                    )
                ),
                updatedAtEpochMillis = nowEpochMillis,
            )
        } else {
            TaskRunTransitions.requireAllowed(TaskRunStatus.Escalated, TaskRunStatus.Cancelled)
            run.copy(
                status = WorkflowRunStatus.Failed,
                taskRuns = run.taskRuns + (
                    taskDefinitionId to taskRun.copy(
                        status = TaskRunStatus.Cancelled,
                        blockingReason = null,
                        progressMessage = note ?: "Failure escalation rejected",
                    )
                ),
                updatedAtEpochMillis = nowEpochMillis,
            )
        }

        val decidedGate = if (approved) {
            gate.approve(decidedByRoleId, note, nowEpochMillis)
        } else {
            gate.reject(decidedByRoleId, note, nowEpochMillis)
        }
        val decisionEvent = ApprovalDecisionReceived(
            workflowRunId = run.id,
            taskDefinitionId = taskDefinitionId,
            gateId = gateId,
            approved = approved,
            decidedByRoleId = decidedByRoleId,
            occurredAtEpochMillis = nowEpochMillis,
        )

        val decisionStore = failureEscalationDecisionStore
        if (decisionStore != null) {
            val committed = decisionStore.commitFailureEscalationDecision(
                FailureEscalationDecisionCommit(
                    expectedGateId = gateId,
                    decidedGate = decidedGate,
                    nextRun = nextRun,
                    decisionEvent = decisionEvent,
                ),
            )
            require(committed) {
                "Approval gate ${gateId.value} was resolved concurrently"
            }
        } else {
            gateCoordinator.decide(
                id = gateId,
                approved = approved,
                decidedByRoleId = decidedByRoleId,
                note = note,
                nowEpochMillis = nowEpochMillis,
            )
        }

        return nextRun
    }

    private fun WorkflowRunStatus.isTerminal(): Boolean = this in setOf(
        WorkflowRunStatus.Completed,
        WorkflowRunStatus.Failed,
        WorkflowRunStatus.Cancelled,
    )
}
