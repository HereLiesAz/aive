package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.TaskCondition
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.events.TaskRerunRequested

/** What the keeper asked for when running a finished run again from one of its tasks. */
data class TaskRerunRequest(
    val taskDefinitionId: TaskDefinitionId,
    /** The task's new objective; null (or the same text) keeps the one it had. */
    val editedObjective: String? = null,
    /**
     * The task's output text, written by the keeper. The task keeps its place, its text output is
     * replaced, and only what follows it runs again. Null runs the task itself again.
     */
    val replacementOutput: String? = null,
)

/**
 * A rerun ready to store: the definition the new run follows (a copy under a new id when an
 * objective changed, so earlier runs keep showing what they were given), the run, and its journal entry.
 */
data class TaskRerunFork(
    val definition: WorkflowDefinition,
    val run: WorkflowRun,
    val event: TaskRerunRequested,
)

/**
 * Time travel for a finished run (docs/architecture/ARCHITECTURE.md, Running again from a task).
 *
 * A finished run is never reopened: its tasks are terminal (`TaskRunTransitions`) and its journal
 * says it ended. Running again from a task forks a new run in the same lineage instead, continuing
 * the source run. The chosen task and everything downstream of it start fresh; every other task
 * that finished keeps its result, carried over with its artifacts. The source run, its attempts and
 * their artifacts stay untouched as history.
 */
object TaskRerunPlanner {
    /** Metadata on an artifact the keeper wrote in place of a task's output. */
    const val EDITED_BY_METADATA = "editedBy"
    const val EDITED_BY_KEEPER = "keeper"
    /** Metadata naming the artifacts a keeper's edit replaced, comma-separated. */
    const val REPLACES_METADATA = "replaces"
    /** Metadata naming the source run's artifact a carried artifact was copied from. */
    const val CARRIED_FROM_METADATA = "carriedFrom"

    fun fork(
        definition: WorkflowDefinition,
        source: WorkflowRun,
        request: TaskRerunRequest,
        workflowRunId: WorkflowRunId,
        nowEpochMillis: Long,
        taskRunIdFactory: (TaskDefinitionId) -> TaskRunId,
        /** The copied definition's id, used only when the objective changes. */
        editedDefinitionId: WorkflowDefinitionId,
    ): TaskRerunFork {
        require(source.workflowDefinitionId == definition.id) {
            "Run ${source.id.value} does not follow workflow ${definition.id.value}"
        }
        require(source.status.isTerminal()) { "Only a finished run can be run again from a task" }
        require(workflowRunId != source.id) { "A rerun is a new run" }
        val taskId = request.taskDefinitionId
        val task = requireNotNull(definition.tasks.firstOrNull { it.id == taskId }) {
            "Task ${taskId.value} is not part of this workflow"
        }
        require(request.editedObjective == null || request.replacementOutput == null) {
            "Change the objective or the output, not both"
        }
        val editedObjective = request.editedObjective?.trim()
            ?.also { require(it.isNotEmpty()) { "The objective can't be empty" } }
            ?.takeIf { it != task.objective }
        val replacement = request.replacementOutput
            ?.also { require(it.isNotBlank()) { "The output can't be empty" } }
        if (replacement != null) {
            require(task.executor !is TaskExecutor.HumanApproval) { "An approval has no output to replace" }
            val finished = requireNotNull(source.taskRuns[taskId]?.takeIf { it.status == TaskRunStatus.Completed }) {
                "Only finished work has output to replace"
            }
            require(finished.artifacts.any { it.textContent != null }) { "This task made no text to replace" }
        }

        val seeds = if (replacement == null) setOf(taskId) else directDependents(definition, taskId)
        val rerun = rerunSet(definition, seeds)
        if (replacement != null) {
            require(rerun.isNotEmpty()) { "Nothing follows this task, so there is nothing to run again" }
            require(taskId !in rerun) {
                "This task sits behind an approval for a repository change; run it again instead"
            }
        }
        val kept = keptTasks(definition, source, rerun)
        if (replacement != null) {
            require(taskId in kept) { "This task's own inputs must run again; run it again instead" }
        }

        val forkDefinition = if (editedObjective == null) {
            definition
        } else {
            definition.copy(
                id = editedDefinitionId,
                tasks = definition.tasks.map { if (it.id == taskId) it.copy(objective = editedObjective) else it },
            )
        }
        val fresh = WorkflowRunFactory.create(
            definition = forkDefinition,
            workflowRunId = workflowRunId,
            projectId = source.projectId,
            objective = source.objective,
            nowEpochMillis = nowEpochMillis,
            taskRunIdFactory = taskRunIdFactory,
            repository = source.repositorySnapshot,
            roles = source.roleSnapshot,
            parentWorkflowRunIds = listOf(source.id),
        )
        val taskRuns = fresh.taskRuns.mapValues { (id, freshTaskRun) ->
            val prior = source.taskRuns[id]
            if (id in kept && prior != null) {
                carry(prior, freshTaskRun.id, if (id == taskId) replacement else null, nowEpochMillis)
            } else {
                freshTaskRun
            }
        }
        val run = WorkflowRunFactory.refreshReadiness(
            definition = forkDefinition,
            run = fresh.copy(taskRuns = taskRuns),
            nowEpochMillis = nowEpochMillis,
        )
        return TaskRerunFork(
            definition = forkDefinition,
            run = run,
            event = TaskRerunRequested(
                workflowRunId = workflowRunId,
                taskDefinitionId = taskId,
                fromWorkflowRunId = source.id,
                rerunTaskIds = definition.tasks.map(TaskDefinition::id).filterNot { it in kept },
                editedObjective = editedObjective,
                outputReplaced = replacement != null,
                occurredAtEpochMillis = nowEpochMillis,
            ),
        )
    }

    /**
     * The tasks that run again when [seeds] do: the seeds and everything downstream of them. A
     * repository change among them that no fresh approval would gate pulls its approvals (and what
     * follows them) in too, so a change never runs on an approval given for different work.
     */
    fun rerunSet(definition: WorkflowDefinition, seeds: Set<TaskDefinitionId>): Set<TaskDefinitionId> {
        val tasksById = definition.tasks.associateBy(TaskDefinition::id)
        val rerun = downstream(definition, seeds).toMutableSet()
        do {
            val approvals = rerun
                .mapNotNull(tasksById::get)
                .filter { it.isRepositoryMutation() }
                .map { approvalAncestors(it.id, tasksById) }
                .filter { ancestors -> ancestors.none { it in rerun } }
                .flatten()
                .toSet()
            val grew = rerun.addAll(downstream(definition, approvals))
        } while (grew)
        return rerun
    }

    /** Tasks that keep their result: settled in [source], outside [rerun], and fed only by kept tasks. */
    private fun keptTasks(
        definition: WorkflowDefinition,
        source: WorkflowRun,
        rerun: Set<TaskDefinitionId>,
    ): Set<TaskDefinitionId> {
        val kept = definition.tasks
            .filter { it.id !in rerun && source.taskRuns[it.id]?.hasSettledResult() == true }
            .mapTo(mutableSetOf(), TaskDefinition::id)
        do {
            val unfed = definition.tasks
                .filter { it.id in kept && it.upstream().any { upstream -> upstream !in kept } }
                .map(TaskDefinition::id)
            val shrank = kept.removeAll(unfed.toSet())
        } while (shrank)
        return kept
    }

    private fun carry(prior: TaskRun, taskRunId: TaskRunId, replacement: String?, nowEpochMillis: Long): TaskRun {
        // Fresh ids under the new task run, so the source run's artifacts stay its own.
        fun carried(artifact: ArtifactRef) = artifact.copy(
            id = ArtifactId("${taskRunId.value}:${artifact.id.value}"),
            taskRunId = taskRunId,
            metadata = artifact.metadata + (CARRIED_FROM_METADATA to artifact.id.value),
        )
        val artifacts = if (replacement == null) {
            prior.artifacts.map(::carried)
        } else {
            val replaced = prior.artifacts.filter { it.textContent != null }
            val first = replaced.first()
            listOf(
                ArtifactRef(
                    id = ArtifactId("${taskRunId.value}:keeper-edit"),
                    kind = first.kind,
                    taskRunId = taskRunId,
                    label = first.label,
                    textContent = replacement,
                    mediaType = first.mediaType,
                    metadata = mapOf(
                        EDITED_BY_METADATA to EDITED_BY_KEEPER,
                        REPLACES_METADATA to replaced.joinToString(",") { it.id.value },
                    ),
                    createdAtEpochMillis = nowEpochMillis,
                ),
            ) + prior.artifacts.filter { it.textContent == null }.map(::carried)
        }
        return prior.copy(id = taskRunId, artifacts = artifacts)
    }

    private fun TaskRun.hasSettledResult(): Boolean =
        status == TaskRunStatus.Completed ||
            (status == TaskRunStatus.Cancelled && blockingReason?.code == WorkflowRunFactory.CONDITION_NOT_MET)

    private fun TaskDefinition.upstream(): Set<TaskDefinitionId> = dependsOn + listOfNotNull(conditionTarget())

    private fun TaskDefinition.conditionTarget(): TaskDefinitionId? = when (val c = condition) {
        is TaskCondition.Always -> null
        is TaskCondition.OnAnyOutcome -> c.ofTask
        is TaskCondition.OnFailure -> c.ofTask
    }

    private fun TaskDefinition.isRepositoryMutation(): Boolean =
        (executor.withoutPlacement() as? TaskExecutor.RepositoryOperation)?.isMutation() == true

    private fun directDependents(definition: WorkflowDefinition, taskId: TaskDefinitionId): Set<TaskDefinitionId> =
        definition.tasks.filter { taskId in it.upstream() }.mapTo(mutableSetOf(), TaskDefinition::id)

    private fun downstream(definition: WorkflowDefinition, seeds: Set<TaskDefinitionId>): Set<TaskDefinitionId> {
        val result = seeds.toMutableSet()
        var frontier = seeds
        while (frontier.isNotEmpty()) {
            frontier = definition.tasks
                .filter { it.id !in result && it.upstream().any(frontier::contains) }
                .mapTo(mutableSetOf(), TaskDefinition::id)
            result += frontier
        }
        return result
    }

    /** The unconditional human approvals upstream of [taskId] through dependsOn, as the validator counts them. */
    private fun approvalAncestors(
        taskId: TaskDefinitionId,
        tasksById: Map<TaskDefinitionId, TaskDefinition>,
    ): Set<TaskDefinitionId> {
        val visited = mutableSetOf<TaskDefinitionId>()
        val approvals = mutableSetOf<TaskDefinitionId>()
        fun visit(id: TaskDefinitionId) {
            if (!visited.add(id)) return
            tasksById[id]?.dependsOn?.forEach { dependencyId ->
                val dependency = tasksById[dependencyId] ?: return@forEach
                if (dependency.executor is TaskExecutor.HumanApproval && dependency.condition is TaskCondition.Always) {
                    approvals += dependencyId
                }
                visit(dependencyId)
            }
        }
        visit(taskId)
        return approvals
    }
}
