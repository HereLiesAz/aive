package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.ApprovalGateId
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.persistence.RepositoryWorkflowEventSink
import com.hereliesaz.geministrator.workflow.ApprovalGateCoordinator
import com.hereliesaz.geministrator.workflow.WorkflowApprovalService
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Resolves a durable failure-escalation gate from the application boundary and refreshes the
 * published runtime from persistence afterwards. Gate decision, run transition, and audit event
 * are committed atomically by WorkflowPersistence.
 */
suspend fun ApplicationRuntime.decideFailureEscalation(
    gateId: ApprovalGateId,
    approved: Boolean,
    decidedByRoleId: RoleDefinitionId? = null,
    note: String? = null,
    nowEpochMillis: Long,
) {
    val live = state.value as? ApplicationRuntimeState.Live
        ?: error("No active workflow is loaded")
    val presentation = live.presentation
    val gate = requireNotNull(persistence.approvalGates.get(gateId)) {
        "Approval gate ${gateId.value} does not exist"
    }
    require(gate.workflowRunId == presentation.run.id) {
        "Approval gate ${gateId.value} does not belong to the active workflow"
    }

    val service = WorkflowApprovalService(
        gateRepository = persistence.approvalGates,
        gateCoordinator = ApprovalGateCoordinator(
            repository = persistence.approvalGates,
            eventSink = RepositoryWorkflowEventSink(persistence.events),
        ),
        sessionGateway = sessionGateway,
        failureEscalationDecisionStore = persistence,
    )
    service.decideFailureEscalation(
        run = presentation.run,
        gateId = gateId,
        approved = approved,
        decidedByRoleId = decidedByRoleId,
        note = note,
        nowEpochMillis = nowEpochMillis,
    )
    refresh()
}

@OptIn(ExperimentalTime::class)
suspend fun ApplicationRuntime.decideFailureEscalation(
    taskDefinitionId: TaskDefinitionId,
    approved: Boolean,
    note: String? = null,
) {
    parseHallMonitorTrialActionId(taskDefinitionId.value)?.let {
        error(
            "Hall Monitor solution testing requires the orchestration model runtime on this platform. " +
                "The paused workflow and recommendation were not changed.",
        )
    }

    val live = state.value as? ApplicationRuntimeState.Live
        ?: error("No active workflow is loaded")
    val taskRun = requireNotNull(live.presentation.run.taskRuns[taskDefinitionId]) {
        "Task ${taskDefinitionId.value} has no runtime state"
    }
    require(taskRun.status == TaskRunStatus.Escalated) {
        "Task ${taskDefinitionId.value} is not awaiting an escalation decision"
    }
    decideFailureEscalation(
        gateId = ApprovalGateId(
            "failure:${live.presentation.run.id.value}:${taskDefinitionId.value}:${taskRun.attempt}",
        ),
        approved = approved,
        note = note,
        nowEpochMillis = Clock.System.now().toEpochMilliseconds(),
    )
}
