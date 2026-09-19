package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.activeRoles
import com.hereliesaz.geministrator.domain.normalized
import com.hereliesaz.geministrator.domain.resolveRoleCollection
import com.hereliesaz.geministrator.persistence.RepositoryWorkflowEventSink
import com.hereliesaz.geministrator.workflow.WorkflowDefinitionPreparer
import com.hereliesaz.geministrator.workflow.WorkflowLaunchService
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
suspend fun ApplicationRuntime.launchSavedWorkflow(
    definition: WorkflowDefinition,
    existingProject: Project? = null,
    projectName: String = existingProject?.name ?: definition.name,
    objective: String = definition.description ?: definition.name,
    repository: RepositoryRef? = existingProject?.repository,
    supplementalRoles: Collection<com.hereliesaz.geministrator.domain.RoleDefinition> = emptyList(),
) {
    val cleanProjectName = projectName.trim()
    val cleanObjective = objective.trim()
    require(cleanProjectName.isNotEmpty()) { "Project name is required" }
    require(cleanObjective.isNotEmpty()) { "Objective is required" }

    val now = Clock.System.now().toEpochMilliseconds()
    val project = existingProject?.copy(
        name = cleanProjectName,
        repository = repository?.normalized() ?: existingProject.repository,
        updatedAtEpochMillis = now,
    ) ?: Project(
        id = ProjectId("project-$now"),
        name = cleanProjectName,
        repository = repository?.normalized(),
        createdAtEpochMillis = now,
        updatedAtEpochMillis = now,
    )

    val globalLaunchRoles = activeRoles(resolveRoleCollection(persistence.roles.all()))
    val launchRoles = (supplementalRoles + globalLaunchRoles)
        .filter(com.hereliesaz.geministrator.domain.RoleDefinition::enabled)
        .distinctBy(com.hereliesaz.geministrator.domain.RoleDefinition::id)
    val launchService = WorkflowLaunchService(
        preparer = WorkflowDefinitionPreparer(providerRegistry, launchRoles),
        persistence = persistence,
        eventSink = RepositoryWorkflowEventSink(persistence.events),
        roles = launchRoles,
        rolesToPersist = globalLaunchRoles,
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
