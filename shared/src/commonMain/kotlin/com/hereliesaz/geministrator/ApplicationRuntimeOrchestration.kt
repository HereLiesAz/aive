package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.activeRoles
import com.hereliesaz.geministrator.domain.normalized
import com.hereliesaz.geministrator.domain.resolveRoleCollection
import com.hereliesaz.geministrator.orchestration.OrchestrationAgentRuntime
import com.hereliesaz.geministrator.orchestration.OrchestrationPacket
import com.hereliesaz.geministrator.orchestration.OrchestrationRole
import com.hereliesaz.geministrator.persistence.RepositoryWorkflowEventSink
import com.hereliesaz.geministrator.workflow.OrchestratedWorkflowFactory
import com.hereliesaz.geministrator.workflow.WorkflowDefinitionPreparer
import com.hereliesaz.geministrator.workflow.WorkflowLaunchService
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
suspend fun ApplicationRuntime.launchOrchestratedWorkflow(
    projectName: String,
    objective: String,
    orchestrationRuntime: OrchestrationAgentRuntime,
    repository: RepositoryRef? = null,
    existingProject: Project? = null,
) {
    val cleanProjectName = projectName.trim()
    val cleanObjective = objective.trim()
    require(cleanProjectName.isNotEmpty()) { "Project name is required" }
    require(cleanObjective.isNotEmpty()) { "Objective is required" }

    val normalizedRepository = repository?.normalized()
    val now = Clock.System.now().toEpochMilliseconds()
    val project = existingProject?.copy(
        name = cleanProjectName,
        repository = normalizedRepository ?: existingProject.repository,
        updatedAtEpochMillis = now,
    ) ?: Project(
        id = ProjectId("project-$now"),
        name = cleanProjectName,
        repository = normalizedRepository,
        createdAtEpochMillis = now,
        updatedAtEpochMillis = now,
    )

    val launchRoles = activeRoles(resolveRoleCollection(persistence.roles.all()))
    val packet = OrchestrationPacket(
        objective = cleanObjective,
        currentState = "new-workflow",
        availableAgents = launchRoles.map { role ->
            OrchestrationRole(
                id = role.id.value,
                name = role.name,
                authorities = role.authorities.map { it.name }.sorted(),
            )
        },
        instruction = "Create the minimal executable dependency-correct workflow DAG needed to satisfy the objective.",
    )
    val plan = orchestrationRuntime.plan(packet)
    val definition = OrchestratedWorkflowFactory.create(
        id = WorkflowDefinitionId("workflow-$now"),
        objective = cleanObjective,
        plan = plan,
        packet = packet,
        roles = launchRoles,
    )
    val launchService = WorkflowLaunchService(
        preparer = WorkflowDefinitionPreparer(providerRegistry, launchRoles),
        persistence = persistence,
        eventSink = RepositoryWorkflowEventSink(persistence.events),
        roles = launchRoles,
    )
    launchService.launch(
        project = project,
        definition = definition,
        workflowRunId = WorkflowRunId("run-$now"),
        objective = cleanObjective,
        nowEpochMillis = now,
        taskRunIdFactory = { id -> TaskRunId("run-$now-${id.value}") },
    )
    loadLatest()
}
