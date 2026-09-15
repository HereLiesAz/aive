package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.ApprovalGateId
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.domain.activeRoles
import com.hereliesaz.geministrator.domain.resolveRoleCollection
import com.hereliesaz.geministrator.orchestration.OrchestrationAgentRuntime
import com.hereliesaz.geministrator.orchestration.OrchestrationFailure
import com.hereliesaz.geministrator.orchestration.OrchestrationPacket
import com.hereliesaz.geministrator.orchestration.OrchestrationPlanStep
import com.hereliesaz.geministrator.orchestration.OrchestrationRole
import com.hereliesaz.geministrator.persistence.RepositoryWorkflowEventSink
import com.hereliesaz.geministrator.workflow.ApprovalGateCoordinator
import com.hereliesaz.geministrator.workflow.OrchestratedWorkflowFactory
import com.hereliesaz.geministrator.workflow.WorkflowApprovalService
import com.hereliesaz.geministrator.workflow.WorkflowDefinitionPreparer
import com.hereliesaz.geministrator.workflow.WorkflowRunFactory
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
suspend fun ApplicationRuntime.repairFailureEscalation(
    taskDefinitionId: TaskDefinitionId,
    orchestrationRuntime: OrchestrationAgentRuntime,
    note: String = "Failure escalation approved; repaired plan scheduled",
) {
    val live = state.value as? ApplicationRuntimeState.Live
        ?: error("No active workflow is loaded")
    val presentation = live.presentation
    val failedTaskRun = requireNotNull(presentation.run.taskRuns[taskDefinitionId]) {
        "Task ${taskDefinitionId.value} has no runtime state"
    }
    require(failedTaskRun.status == TaskRunStatus.Escalated) {
        "Task ${taskDefinitionId.value} is not awaiting an escalation decision"
    }

    val roles = activeRoles(resolveRoleCollection(persistence.roles.all()))
    val completedTaskIds = presentation.run.taskRuns
        .filterValues { it.status == TaskRunStatus.Completed }
        .keys
        .mapTo(mutableSetOf()) { it.value }
    val packet = OrchestrationPacket(
        objective = presentation.run.objective,
        currentState = "failure-escalation",
        acceptanceCriteria = presentation.definition.tasks
            .flatMap { task -> task.acceptanceCriteria.map { it.description } }
            .distinct(),
        availableAgents = roles.map { role ->
            OrchestrationRole(
                id = role.id.value,
                name = role.name,
                authorities = role.authorities.map { it.name }.sorted(),
            )
        },
        artifacts = presentation.run.taskRuns.values
            .flatMap { it.artifacts }
            .map { it.id.value },
        previousSteps = presentation.run.taskRuns
            .filterValues { it.status == TaskRunStatus.Completed }
            .keys
            .map { it.value },
        currentPlan = presentation.definition.tasks.map { task ->
            OrchestrationPlanStep(
                id = task.id.value,
                name = task.name,
                objective = task.objective,
                roleId = requireNotNull(task.roleId) {
                    "Orchestration plan task ${task.id.value} has no role"
                }.value,
                dependsOn = task.dependsOn.map { it.value }.sorted(),
                requiresHumanApproval = task.approvalPolicy != com.hereliesaz.geministrator.domain.ApprovalPolicy.None,
            )
        },
        failures = listOf(
            OrchestrationFailure(
                stepId = taskDefinitionId.value,
                summary = failedTaskRun.progressMessage
                    ?: failedTaskRun.blockingReason?.message
                    ?: "Executor failed after retries",
                attempt = failedTaskRun.attempt,
            ),
        ),
        instruction = "Repair the failed remainder of the plan. Every completed step must remain in the plan unchanged by id so its existing artifacts remain valid. Revise only work that has not completed.",
    )

    val repairPlan = orchestrationRuntime.repair(packet)
    val repairedById = repairPlan.steps.associateBy { it.id }
    require(completedTaskIds.all(repairedById::containsKey)) {
        "Plan repair attempted to discard completed work"
    }
    packet.currentPlan
        .filter { it.id in completedTaskIds }
        .forEach { completed ->
            require(repairedById.getValue(completed.id) == completed) {
                "Plan repair attempted to rewrite completed step ${completed.id}"
            }
        }

    val repairedDefinition = OrchestratedWorkflowFactory.create(
        id = presentation.definition.id,
        objective = presentation.run.objective,
        plan = repairPlan,
        packet = packet,
        roles = roles,
    )
    val preparedDefinition = WorkflowDefinitionPreparer(providerRegistry, roles)
        .prepare(repairedDefinition, presentation.project.repository)

    val now = Clock.System.now().toEpochMilliseconds()
    val gateId = ApprovalGateId(
        "failure:${presentation.run.id.value}:${taskDefinitionId.value}:${failedTaskRun.attempt}",
    )
    val approvalService = WorkflowApprovalService(
        gateRepository = persistence.approvalGates,
        gateCoordinator = ApprovalGateCoordinator(
            repository = persistence.approvalGates,
            eventSink = RepositoryWorkflowEventSink(persistence.events),
        ),
        sessionGateway = sessionGateway,
        failureEscalationDecisionStore = persistence,
    )
    val approvedRun = approvalService.decideFailureEscalation(
        run = presentation.run,
        gateId = gateId,
        approved = true,
        decidedByRoleId = null,
        note = note,
        nowEpochMillis = now,
    )

    val freshRun = WorkflowRunFactory.create(
        definition = preparedDefinition,
        workflowRunId = approvedRun.id,
        projectId = approvedRun.projectId,
        objective = approvedRun.objective,
        nowEpochMillis = now,
        taskRunIdFactory = { taskId ->
            TaskRunId("${approvedRun.id.value}:${taskId.value}:repair-${failedTaskRun.attempt + 1}")
        },
    )
    val mergedTaskRuns = freshRun.taskRuns.mapValues { (taskId, freshTaskRun) ->
        val prior = approvedRun.taskRuns[taskId]
        when {
            prior?.status == TaskRunStatus.Completed -> prior
            taskId == taskDefinitionId -> freshTaskRun.copy(attempt = failedTaskRun.attempt + 1)
            prior != null -> freshTaskRun.copy(attempt = prior.attempt)
            else -> freshTaskRun
        }
    }
    val repairedRun = WorkflowRunFactory.refreshReadiness(
        definition = preparedDefinition,
        run = freshRun.copy(
            status = WorkflowRunStatus.Running,
            taskRuns = mergedTaskRuns,
            createdAtEpochMillis = approvedRun.createdAtEpochMillis,
            updatedAtEpochMillis = now,
        ),
        nowEpochMillis = now,
    ).copy(status = WorkflowRunStatus.Running)

    persistence.definitions.put(preparedDefinition)
    persistence.runs.put(repairedRun)
    refresh()
}
