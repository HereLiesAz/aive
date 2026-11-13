package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.ContextProvenance
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.activeRoles
import com.hereliesaz.geministrator.domain.normalized
import com.hereliesaz.geministrator.domain.resolveRoleCollection
import com.hereliesaz.geministrator.orchestration.OrchestrationAgentRuntime
import com.hereliesaz.geministrator.orchestration.PlanDecision
import com.hereliesaz.geministrator.orchestration.PlanReviewer
import com.hereliesaz.geministrator.orchestration.ReviewedPlanStore
import com.hereliesaz.geministrator.persistence.RepositoryWorkflowEventSink
import com.hereliesaz.geministrator.workflow.WorkflowDefinitionPreparer
import com.hereliesaz.geministrator.workflow.WorkflowLaunchService
import com.hereliesaz.geministrator.workflow.uniqueLaunchStamp
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
    /** Runs the new run continues (one) or merges (several); empty starts a new root lineage. */
    continuesWorkflowRunIds: List<WorkflowRunId> = emptyList(),
    /**
     * With all four, a saved hive that holds an Orchestrator (a rough design: see SavedHivePlanning.kt) is planned,
     * reviewed and decided by the keeper before any run exists, as for [launchOrchestratedWorkflow]. Without them,
     * and for every other saved hive, it launches as drawn. A remote interface passes none: it cannot be the keeper.
     */
    planner: OrchestrationAgentRuntime? = null,
    planReviewer: PlanReviewer? = null,
    confirmPlan: (suspend (PlanDecision) -> Boolean)? = null,
    planStore: ReviewedPlanStore? = null,
    /** Where [objective] came from; a remote agent's or a model-fed schedule's objective is not the keeper's own typing. */
    objectiveProvenance: ContextProvenance = ContextProvenance.KeeperWritten,
) {
    if (planner != null && planReviewer != null && confirmPlan != null) {
        val planned = launchPlannedSavedWorkflow(
            definition = definition,
            projectName = projectName,
            objective = objective,
            repository = repository,
            existingProject = existingProject,
            supplementalRoles = supplementalRoles.toList(),
            continuesWorkflowRunIds = continuesWorkflowRunIds,
            orchestrationRuntime = planner,
            planReviewer = planReviewer,
            confirmPlan = confirmPlan,
            planStore = planStore,
            objectiveProvenance = objectiveProvenance,
        )
        if (planned) return
    }
    val parents = requireKnownParentRuns(continuesWorkflowRunIds)
    val cleanProjectName = projectName.trim()
    val cleanObjective = objective.trim()
    require(cleanProjectName.isNotEmpty()) { "Project name is required" }
    require(cleanObjective.isNotEmpty()) { "Objective is required" }

    val now = Clock.System.now().toEpochMilliseconds()
    val stamp = persistence.uniqueLaunchStamp(now, newProject = existingProject == null)
    val project = existingProject?.copy(
        name = cleanProjectName,
        repository = repository?.normalized() ?: existingProject.repository,
        updatedAtEpochMillis = now,
    ) ?: Project(
        id = ProjectId("project-$stamp"),
        name = cleanProjectName,
        repository = repository?.normalized(),
        createdAtEpochMillis = now,
        updatedAtEpochMillis = now,
    )

    val globalLaunchRoles = com.hereliesaz.geministrator.template.LaunchRoles.resolve(activeRoles(resolveRoleCollection(persistence.roles.all())))
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
        workflowRunId = WorkflowRunId("run-$stamp"),
        objective = cleanObjective,
        nowEpochMillis = now,
        taskRunIdFactory = { id -> TaskRunId("run-$stamp-${id.value}") },
        parentWorkflowRunIds = parents,
        objectiveProvenance = objectiveProvenance,
    )
    rememberMissing("run-$stamp", staffingMissing(com.hereliesaz.geministrator.queen.staffQueen(launchRoles), providerRegistry.linkedSourceIds()))
    loadLatest()
}
