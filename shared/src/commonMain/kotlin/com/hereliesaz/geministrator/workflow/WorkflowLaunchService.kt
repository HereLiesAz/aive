package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ContextProvenance
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
    private val rolesToPersist: Collection<RoleDefinition> = roles,
    /**
     * The tasks the keeper's approved executor habits propose for an objective. They are added to the launched
     * workflow the way the Habits panel adds them by hand, each behind its own human-approval task, so nothing
     * a habit proposes runs until a person approves that step. Empty unless memory proposes some.
     */
    private val habitSteps: suspend (String) -> List<com.hereliesaz.geministrator.domain.TaskDefinition> =
        { objective -> com.hereliesaz.geministrator.memory.MemoryRuntimeBridge.habitStepProposer?.invoke(objective).orEmpty() },
) {
    suspend fun launch(
        project: Project,
        definition: WorkflowDefinition,
        workflowRunId: WorkflowRunId,
        objective: String,
        nowEpochMillis: Long,
        taskRunIdFactory: (TaskDefinitionId) -> TaskRunId,
        /** Runs the new run continues (one) or merges (several); empty for a root run. */
        parentWorkflowRunIds: List<WorkflowRunId> = emptyList(),
        /** Where [objective] came from (see [WorkflowRun.objectiveProvenance]). */
        objectiveProvenance: ContextProvenance = ContextProvenance.KeeperWritten,
    ): Pair<WorkflowDefinition, WorkflowRuntimeState> {
        val prepared = preparer.prepare(withApprovedHabitSteps(definition, objective), project.repository)
        // A hive that nests itself, directly or through the hives it nests, is refused now, not when the nesting
        // task starts after everything upstream of it has already run and spent.
        persistence.nestingCycle(emptyList(), prepared)?.let { cycle ->
            throw IllegalArgumentException("Nested workflow cycle refused: ${cycle.joinToString(" -> ") { it.value }}")
        }
        val run = WorkflowRunFactory.create(
            definition = prepared,
            workflowRunId = workflowRunId,
            projectId = project.id,
            objective = objective,
            nowEpochMillis = nowEpochMillis,
            taskRunIdFactory = taskRunIdFactory,
            repository = project.repository,
            roles = roles,
            parentWorkflowRunIds = parentWorkflowRunIds,
            objectiveProvenance = objectiveProvenance,
        )

        persistence.projects.put(project)
        persistence.definitions.put(prepared)
        persistence.runs.put(run)
        rolesToPersist.forEach { persistence.roles.put(it) }
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

    /** [definition] with the approved habit's steps added after its last task, or unchanged when there are none or they do not fit. */
    private suspend fun withApprovedHabitSteps(definition: WorkflowDefinition, objective: String): WorkflowDefinition {
        val composition = WorkflowCompositionService(persistence)
        return try {
            val proposed = habitSteps(objective)
            if (proposed.isEmpty()) {
                definition
            } else {
                val fresh = composition.withFreshIds(definition, proposed)
                val tails = definition.tasks.map { it.id }.toSet() - definition.tasks.flatMap { it.dependsOn }.toSet()
                composition.addProposedTasks(definition, fresh, connectFrom = tails)
            }
        } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            definition
        }
    }
}
