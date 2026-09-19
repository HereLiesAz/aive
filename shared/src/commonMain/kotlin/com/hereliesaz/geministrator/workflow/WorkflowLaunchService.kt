package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.events.WorkflowCreated
import com.hereliesaz.geministrator.events.WorkflowEventSink
import com.hereliesaz.geministrator.persistence.WorkflowPersistence

class WorkflowLaunchService(
    private val preparer: WorkflowDefinitionPreparer,
    private val persistence: WorkflowPersistence,
    private val eventSink: WorkflowEventSink,
    private val roles: Collection<RoleDefinition>,
) {
    suspend fun launch(
        project: Project,
        definition: WorkflowDefinition,
        workflowRunId: WorkflowRunId,
        objective: String,
        nowEpochMillis: Long,
        taskRunIdFactory: (TaskDefinitionId) -> TaskRunId,
    ): Pair<WorkflowDefinition, WorkflowRuntimeState> {
        val prepared = preparer.prepare(definition, project.repository)
        val run = WorkflowRunFactory.create(
            definition = prepared,
            workflowRunId = workflowRunId,
            projectId = project.id,
            objective = objective,
            repository = project.repository,
            nowEpochMillis = nowEpochMillis,
            taskRunIdFactory = taskRunIdFactory,
        )

        persistence.projects.put(project)
        persistence.definitions.put(prepared)
        persistence.runs.put(run)
        roles.forEach { persistence.roles.put(it) }
        eventSink.append(
            WorkflowCreated(
                workflowRunId = run.id,
                projectId = run.projectId,
                workflowDefinitionId = run.workflowDefinitionId,
                objective = run.objective,
                occurredAtEpochMillis = nowEpochMillis,
            ),
        )

        return prepared to WorkflowRuntimeState(run)
    }
}
