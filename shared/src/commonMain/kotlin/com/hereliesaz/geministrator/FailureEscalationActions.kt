package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.ApprovalGateId
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowRun
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
    // Decided under the runtime lock against the active run: a decision built from a published
    // snapshot could commit over progress a cycle persisted after that snapshot.
    withActiveRun { run -> decideFailureEscalationLocked(run, gateId, approved, decidedByRoleId, note, nowEpochMillis) }
    refresh()
}

private suspend fun ApplicationRuntime.decideFailureEscalationLocked(
    run: WorkflowRun,
    gateId: ApprovalGateId,
    approved: Boolean,
    decidedByRoleId: RoleDefinitionId?,
    note: String?,
    nowEpochMillis: Long,
) {
    val gate = requireNotNull(persistence.approvalGates.get(gateId)) {
        "Approval gate ${gateId.value} does not exist"
    }
    require(gate.workflowRunId == run.id) {
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
        run = run,
        gateId = gateId,
        approved = approved,
        decidedByRoleId = decidedByRoleId,
        note = note,
        nowEpochMillis = nowEpochMillis,
    )
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

    withActiveRun { run ->
        val taskRun = requireNotNull(run.taskRuns[taskDefinitionId]) {
            "Task ${taskDefinitionId.value} has no runtime state"
        }
        require(taskRun.status == TaskRunStatus.Escalated) {
            "Task ${taskDefinitionId.value} is not awaiting an escalation decision"
        }
        decideFailureEscalationLocked(
            run = run,
            gateId = ApprovalGateId("failure:${run.id.value}:${taskDefinitionId.value}:${taskRun.attempt}"),
            approved = approved,
            decidedByRoleId = null,
            note = note,
            nowEpochMillis = Clock.System.now().toEpochMilliseconds(),
        )
    }
    refresh()
}
