package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.ApprovalGateId
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.persistence.RepositoryWorkflowEventSink
import com.hereliesaz.geministrator.workflow.ApprovalGateCoordinator
import com.hereliesaz.geministrator.workflow.ApprovalGateKind
import com.hereliesaz.geministrator.workflow.ManagedSessionHandle
import com.hereliesaz.geministrator.workflow.PlanRejectionService
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
suspend fun ApplicationRuntime.rejectPlan(taskDefinitionId: TaskDefinitionId) {
    val live = state.value as? ApplicationRuntimeState.Live
        ?: error("No active workflow is loaded")
    val presentation = live.presentation
    val task = requireNotNull(presentation.definition.tasks.firstOrNull { it.id == taskDefinitionId }) {
        "Task ${taskDefinitionId.value} is not defined"
    }
    val taskRun = requireNotNull(presentation.run.taskRuns[taskDefinitionId]) {
        "Task ${taskDefinitionId.value} has no runtime state"
    }
    require(taskRun.status == TaskRunStatus.AwaitingApproval) {
        "Task ${taskDefinitionId.value} is not awaiting approval"
    }

    val providerId = requireNotNull(taskRun.assignedProviderId) {
        "Task ${taskDefinitionId.value} is not a provider plan approval"
    }
    // An engine-held plan (no provider run yet) has no session to cancel.
    val handle = taskRun.providerRunId?.let { ManagedSessionHandle(taskRun.id, providerId, it) }
    val now = Clock.System.now().toEpochMilliseconds()
    val gateId = ApprovalGateId(
        "plan:${presentation.run.id.value}:${taskDefinitionId.value}:${taskRun.attempt}",
    )
    val gateCoordinator = ApprovalGateCoordinator(
        repository = persistence.approvalGates,
        eventSink = RepositoryWorkflowEventSink(persistence.events),
    )
    if (persistence.approvalGates.get(gateId) == null) {
        gateCoordinator.open(
            id = gateId,
            workflowRunId = presentation.run.id,
            taskDefinitionId = taskDefinitionId,
            kind = ApprovalGateKind.PlanApproval,
            reason = "Plan approval required for '${task.name}'",
            requiresHuman = true,
            nowEpochMillis = now,
        )
    }

    PlanRejectionService(
        gateRepository = persistence.approvalGates,
        gateCoordinator = gateCoordinator,
        sessionGateway = sessionGateway,
    ).rejectPlan(
        gateId = gateId,
        handle = handle,
        note = "Plan rejected in application",
        nowEpochMillis = now,
    )

    val resumed = coordinator.resume(presentation.run.id)
    coordinator.cycle(
        project = requireNotNull(persistence.projects.get(presentation.run.projectId)) {
            "Project ${presentation.run.projectId.value} was not found"
        },
        definition = presentation.definition,
        state = resumed.copy(handles = resumed.handles - taskDefinitionId),
        nowEpochMillis = now,
        artifactIdFactory = { run, artifact, index ->
            com.hereliesaz.geministrator.domain.ArtifactId(
                "${run.id.value}:${artifact.kind}:${run.attempt}:$index",
            )
        },
    )
    refresh()
}
