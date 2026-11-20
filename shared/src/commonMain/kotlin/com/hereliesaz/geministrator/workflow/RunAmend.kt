package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.isTerminal
import com.hereliesaz.geministrator.events.TaskBecameReady
import com.hereliesaz.geministrator.events.WorkflowEvent
import com.hereliesaz.geministrator.policy.auditEvent

/** What [RunAmend.amend] made of a request: the new definition, run and events to save together, or why nothing changes. */
sealed interface RunAmendment {
    data class Amended(
        val definition: WorkflowDefinition,
        val run: WorkflowRun,
        val events: List<WorkflowEvent>,
        val addedTaskIds: Set<TaskDefinitionId>,
    ) : RunAmendment

    data class Refused(val reason: String) : RunAmendment
}

/**
 * The engine's amendment of a run that has already started (docs/Orchestration_layer.md). Pure: it takes the run's
 * definition, the run and the wanted task list, and answers with the new pair to save, or a refusal that changes nothing.
 *
 * Rules, all code: the run is not finished; no task is removed; a task that is running or already settled is never
 * touched; a task that has not started may be changed only so it waits for at least what it waited for, with the same
 * role, executor, approval, condition and effects; an added step that can change something outside its own output carries
 * the plan gate (an injected step never bypasses an approval) and none may be a repository change; the whole is checked
 * by [WorkflowGraphValidator]. Added tasks get their `TaskRun`, then [WorkflowRunFactory.refreshReadiness] releases what
 * is ready. Nothing here approves anything, and nothing remote reaches it (docs/THREAT_MODEL.md, invariants 4a and 6).
 */
object RunAmend {
    fun amend(
        definition: WorkflowDefinition,
        run: WorkflowRun,
        tasks: List<TaskDefinition>,
        roles: Collection<RoleDefinition>,
        nowEpochMillis: Long,
    ): RunAmendment {
        if (run.status.isTerminal()) return RunAmendment.Refused("The swarm is finished, so it cannot be changed.")
        if (tasks.map { it.id }.toSet().size != tasks.size) return RunAmendment.Refused("A step id is used twice.")
        val before = definition.tasks.associateBy { it.id }
        val after = tasks.associateBy { it.id }
        before.keys.firstOrNull { it !in after }?.let {
            return RunAmendment.Refused("Step ${it.value} cannot be taken away from a swarm that has started.")
        }
        val added = tasks.filter { it.id !in before }
        for (task in tasks) {
            val old = before[task.id] ?: continue
            if (old == task) continue
            val status = run.taskRuns[task.id]?.status
            if (status != TaskRunStatus.Created && status != TaskRunStatus.Blocked && status != TaskRunStatus.Ready) {
                return RunAmendment.Refused("Step ${task.id.value} has started, so it is not changed.")
            }
            // A step that has not started may only wait for more: every other field of it stays exactly as it was.
            if (!task.dependsOn.containsAll(old.dependsOn) || old.copy(dependsOn = task.dependsOn) != task) {
                return RunAmendment.Refused("Step ${task.id.value} can only be given more to wait for; nothing else about it changes.")
            }
        }
        for (task in added) {
            if (task.executor != null && task.executor.withoutPlacement() !is TaskExecutor.RoleAgent) {
                return RunAmendment.Refused("An added step is done by a creature, not by a script, an action or a service.")
            }
            val operation = task.executor.withoutPlacement() as? TaskExecutor.RepositoryOperation
            if (operation != null && operation.isMutation()) {
                return RunAmendment.Refused("An added step cannot change a repository.")
            }
            if (task.isEffectfulStep() && !task.hasPlanGate()) {
                return RunAmendment.Refused("An added step that changes something needs its approval first.")
            }
        }
        val candidate = definition.copy(tasks = tasks)
        val errors = WorkflowGraphValidator.validate(candidate)
        if (errors.isNotEmpty()) return RunAmendment.Refused(errors.joinToString(" ") { it.humanReadable() })

        val fresh = WorkflowRunFactory.create(
            definition = candidate,
            workflowRunId = run.id,
            projectId = run.projectId,
            objective = run.objective,
            nowEpochMillis = nowEpochMillis,
            taskRunIdFactory = { id -> TaskRunId("${run.id.value}:${id.value}:amend") },
            roles = roles,
            parentWorkflowRunIds = run.parentWorkflowRunIds,
            objectiveProvenance = run.objectiveProvenance,
        )
        val addedIds = added.map { it.id }.toSet()
        // Every task already in the run keeps its own state; only the added ones take a new one.
        val taskRuns: Map<TaskDefinitionId, TaskRun> = fresh.taskRuns.mapValues { (id, new) -> run.taskRuns[id] ?: new }
        val events = mutableListOf<WorkflowEvent>()
        val refreshed = WorkflowRunFactory.refreshReadiness(
            definition = candidate,
            run = run.copy(taskRuns = taskRuns, definitionSnapshot = candidate, updatedAtEpochMillis = nowEpochMillis),
            nowEpochMillis = nowEpochMillis,
            onPolicyDecision = { request, decision -> request.auditEvent(decision, nowEpochMillis)?.let(events::add) },
        ).copy(status = run.status)
        addedIds.forEach { id ->
            if (refreshed.taskRuns[id]?.status == TaskRunStatus.Ready) events += TaskBecameReady(run.id, id, nowEpochMillis)
        }
        return RunAmendment.Amended(candidate, refreshed, events, addedIds)
    }
}
