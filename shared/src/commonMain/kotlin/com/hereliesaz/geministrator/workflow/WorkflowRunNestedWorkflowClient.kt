package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.domain.effectiveExecutor
import com.hereliesaz.geministrator.events.WorkflowCreated
import com.hereliesaz.geministrator.persistence.WorkflowPersistence
import com.hereliesaz.geministrator.providers.ProviderArtifact
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Runs a [TaskExecutor.NestedWorkflow] node as a real child [WorkflowRun] on the same engine and
 * persistence as its parent.
 *
 * The child run ID is derived from the parent task run and attempt, so a repeated dispatch after a
 * crash and every reconcile after a restart reattach to the same child run instead of starting a
 * duplicate. Each parent reconcile advances the child by one cycle through a coordinator owned by
 * that child (coordinator cycles are not reentrant). Human gates inside the child stay pending until
 * a person decides them; see [approvePendingHumanApproval].
 */
class WorkflowRunNestedWorkflowClient(
    private val persistence: WorkflowPersistence,
    private val engine: WorkflowEngine,
    private val coordinatorFactory: () -> WorkflowRuntimeCoordinator,
    private val nowEpochMillis: () -> Long,
    private val maxDepth: Int = DEFAULT_MAX_DEPTH,
) : NestedWorkflowClient {
    private data class ChildRuntime(
        val coordinator: WorkflowRuntimeCoordinator,
        val state: WorkflowRuntimeState,
    )

    private val children = mutableMapOf<WorkflowRunId, ChildRuntime>()
    private val childrenMutex = Mutex()

    override suspend fun start(
        project: Project,
        workflowDefinitionId: WorkflowDefinitionId,
        targetProjectId: ProjectId?,
    ): ExternalExecutionRun = throw IllegalStateException("A nested workflow needs its parent task context to start")

    override suspend fun start(
        context: TaskExecutorContext,
        workflowDefinitionId: WorkflowDefinitionId,
        targetProjectId: ProjectId?,
    ): ExternalExecutionRun {
        val depth = nestingDepth(context.run.id) + 1
        val childRunId = WorkflowRunId(
            "$CHILD_RUN_PREFIX$depth-${context.taskRun.id.value}-a${context.taskRun.attempt}",
        )
        persistence.runs.get(childRunId)?.let { return it.toExternalRun() }

        check(depth <= maxDepth) { "Nested workflow depth $depth exceeds the limit of $maxDepth" }
        val definition = checkNotNull(persistence.definitions.get(workflowDefinitionId)) {
            "Nested workflow definition ${workflowDefinitionId.value} was not found"
        }
        nestingCycle(context.definition.id, definition)?.let { cycle ->
            error("Nested workflow cycle refused: ${cycle.joinToString(" -> ") { it.value }}")
        }
        val childProject = targetProjectId
            ?.takeIf { it != context.project.id }
            ?.let { id -> checkNotNull(persistence.projects.get(id)) { "Nested workflow project ${id.value} was not found" } }
            ?: context.project

        val now = nowEpochMillis()
        val run = WorkflowRunFactory.create(
            definition = definition,
            workflowRunId = childRunId,
            projectId = childProject.id,
            objective = context.task.objective,
            nowEpochMillis = now,
            taskRunIdFactory = { id -> TaskRunId("${childRunId.value}-${id.value}") },
            repository = childProject.repository,
            roles = context.run.roleSnapshot,
        )
        persistence.runs.put(run)
        persistence.events.append(
            WorkflowCreated(
                workflowRunId = run.id,
                projectId = run.projectId,
                workflowDefinitionId = run.workflowDefinitionId,
                objective = run.objective,
                occurredAtEpochMillis = now,
            ),
        )
        return run.toExternalRun()
    }

    override suspend fun getRun(project: Project, runId: String): ExternalExecutionRun {
        val id = WorkflowRunId(runId)
        val stored = persistence.runs.get(id)
            ?: return ExternalExecutionRun(runId, ExternalExecutionStatus.Failed, message = "Nested workflow run $runId was not found")
        if (stored.status.isTerminal()) {
            childrenMutex.withLock { children.remove(id) }
            return stored.toExternalRun()
        }
        return try {
            val child = childrenMutex.withLock { children[id] } ?: coordinatorFactory().let { coordinator ->
                ChildRuntime(coordinator, coordinator.resume(id))
            }
            val childProject = checkNotNull(persistence.projects.get(stored.projectId)) {
                "Project ${stored.projectId.value} was not found"
            }
            val definition = checkNotNull(persistence.definitions.get(stored.workflowDefinitionId)) {
                "Workflow definition ${stored.workflowDefinitionId.value} was not found"
            }
            val next = child.coordinator.cycle(childProject, definition, child.state, nowEpochMillis(), ::artifactId)
            childrenMutex.withLock {
                if (next.run.status.isTerminal()) children.remove(id) else children[id] = child.copy(state = next)
            }
            next.run.toExternalRun()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            // Transient failures (for example a disconnected provider) must not fail the parent node;
            // the child is resumed from persistence on the next reconcile.
            childrenMutex.withLock { children.remove(id) }
            ExternalExecutionRun(
                id = runId,
                status = ExternalExecutionStatus.Running,
                message = "Nested workflow run $runId could not be advanced: ${failure.message ?: failure::class.simpleName}",
            )
        }
    }

    /**
     * Approve the first pending [TaskExecutor.HumanApproval] task in [childRunId] or, failing that,
     * in its own running nested children. This is the explicit human decision for a gate the parent
     * node surfaces; nothing in this client approves a gate on its own.
     */
    suspend fun approvePendingHumanApproval(childRunId: String): Boolean {
        val id = WorkflowRunId(childRunId)
        val run = persistence.runs.get(id) ?: return false
        if (run.status.isTerminal()) return false
        val definition = persistence.definitions.get(run.workflowDefinitionId) ?: return false
        val tasks = definition.tasks.associateBy { it.id }
        fun TaskRun.resolvedExecutor(): TaskExecutor? = executor ?: tasks[taskDefinitionId]?.effectiveExecutor()

        val gate = run.taskRuns.values.firstOrNull {
            it.status == TaskRunStatus.AwaitingApproval && it.resolvedExecutor() is TaskExecutor.HumanApproval
        }
        if (gate != null) {
            val approved = engine.completeHumanApprovalTask(definition, run, gate.taskDefinitionId, nowEpochMillis())
            persistence.runs.put(approved)
            childrenMutex.withLock {
                children[id]?.let { children[id] = it.copy(state = it.state.copy(run = approved)) }
            }
            return true
        }
        return run.taskRuns.values
            .filter { it.status == TaskRunStatus.Running && it.resolvedExecutor() is TaskExecutor.NestedWorkflow }
            .mapNotNull(TaskRun::externalRunId)
            .any { approvePendingHumanApproval(it) }
    }

    /**
     * Return the definition path that closes a nesting cycle when [child] runs under [parentId], or
     * null. A cycle through any further ancestor also passes through the parent, so checking the
     * parent plus every cycle reachable from the child covers the whole ancestry chain.
     */
    private suspend fun nestingCycle(
        parentId: WorkflowDefinitionId,
        child: WorkflowDefinition,
    ): List<WorkflowDefinitionId>? {
        val explored = mutableSetOf<WorkflowDefinitionId>()
        suspend fun visit(definition: WorkflowDefinition, path: List<WorkflowDefinitionId>): List<WorkflowDefinitionId>? {
            val nextPath = path + definition.id
            if (definition.id in path) return nextPath
            if (!explored.add(definition.id)) return null
            for (nestedId in definition.nestedDefinitionIds()) {
                if (nestedId in nextPath) return nextPath + nestedId
                val nested = persistence.definitions.get(nestedId) ?: continue
                visit(nested, nextPath)?.let { return it }
            }
            return null
        }
        return visit(child, listOf(parentId))
    }

    private fun WorkflowRun.toExternalRun(): ExternalExecutionRun {
        val total = taskRuns.size
        val done = taskRuns.values.count { it.status == TaskRunStatus.Completed || it.status == TaskRunStatus.Cancelled }
        val progress = if (total == 0) null else done.toFloat() / total
        return when (status) {
            WorkflowRunStatus.Completed ->
                ExternalExecutionRun(id.value, ExternalExecutionStatus.Completed, progress = 1f, message = "Nested workflow run ${id.value} completed")
            WorkflowRunStatus.Failed ->
                ExternalExecutionRun(id.value, ExternalExecutionStatus.Failed, progress = progress, message = "Nested workflow run ${id.value} failed")
            WorkflowRunStatus.Cancelled ->
                ExternalExecutionRun(id.value, ExternalExecutionStatus.Failed, progress = progress, message = "Nested workflow run ${id.value} was cancelled")
            else -> ExternalExecutionRun(
                id = id.value,
                status = ExternalExecutionStatus.Running,
                progress = progress,
                message = pendingDecisionMessage() ?: "Nested workflow run ${id.value}: $done/$total tasks complete",
            )
        }
    }

    private fun WorkflowRun.pendingDecisionMessage(): String? {
        taskRuns.values.firstOrNull { it.status == TaskRunStatus.AwaitingApproval || it.status == TaskRunStatus.Escalated }
            ?.let { pending ->
                val humanApproval = pending.executor as? TaskExecutor.HumanApproval
                return when {
                    humanApproval != null -> "$NESTED_HUMAN_APPROVAL_PREFIX ${id.value}: ${humanApproval.label}"
                    pending.status == TaskRunStatus.Escalated ->
                        "Awaiting a failure-escalation decision in nested workflow run ${id.value} (${pending.taskDefinitionId.value})"
                    else -> "Awaiting plan approval in nested workflow run ${id.value} (${pending.taskDefinitionId.value})"
                }
            }
        // Surface a gate from a deeper nested run through every ancestor node.
        return taskRuns.values.firstNotNullOfOrNull { taskRun ->
            taskRun.progressMessage?.takeIf {
                taskRun.executor is TaskExecutor.NestedWorkflow && it.startsWith(NESTED_DECISION_PREFIX)
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun artifactId(taskRun: TaskRun, artifact: ProviderArtifact, index: Int) =
        ArtifactId("${taskRun.id.value}:${artifact.kind}:${taskRun.attempt}:$index")

    companion object {
        const val DEFAULT_MAX_DEPTH: Int = 4
        const val NESTED_HUMAN_APPROVAL_PREFIX: String = "Awaiting human approval in nested workflow run"
        private const val NESTED_DECISION_PREFIX: String = "Awaiting "
        private const val CHILD_RUN_PREFIX: String = "nested-d"
        private val CHILD_RUN_ID = Regex("^nested-d(\\d+)-.*")

        /** 0 for a top-level run; otherwise how many nested-workflow boundaries sit above [runId]. */
        fun nestingDepth(runId: WorkflowRunId): Int =
            CHILD_RUN_ID.matchEntire(runId.value)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }
}

/** True when a nested-workflow task is waiting on a HumanApproval gate inside its child run. */
fun TaskRun.awaitsNestedHumanApproval(): Boolean =
    status == TaskRunStatus.Running &&
        executor is TaskExecutor.NestedWorkflow &&
        progressMessage?.startsWith(WorkflowRunNestedWorkflowClient.NESTED_HUMAN_APPROVAL_PREFIX) == true

private fun WorkflowDefinition.nestedDefinitionIds(): List<WorkflowDefinitionId> = tasks.mapNotNull { task ->
    when (val executor = task.executor) {
        is TaskExecutor.NestedWorkflow -> executor.workflowDefinitionId
        is TaskExecutor.Distributed -> (executor.delegate as? TaskExecutor.NestedWorkflow)?.workflowDefinitionId
        else -> null
    }
}
