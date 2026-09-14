package com.hereliesaz.geministrator

import com.hereliesaz.conveyance.h2g2.H2g2WorkflowBand
import com.hereliesaz.conveyance.h2g2.H2g2WorkflowEdge
import com.hereliesaz.conveyance.h2g2.H2g2WorkflowMotion
import com.hereliesaz.conveyance.h2g2.H2g2WorkflowNode
import com.hereliesaz.conveyance.h2g2.H2g2WorkflowState
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.displayName
import com.hereliesaz.geministrator.domain.effectiveExecutor

data class LiveWorkflowPresentation(
    val project: Project,
    val definition: WorkflowDefinition,
    val run: WorkflowRun,
    val roles: Collection<RoleDefinition>,
)

internal data class WorkflowMindMapProjection(
    val bands: List<H2g2WorkflowBand>,
    val edges: List<H2g2WorkflowEdge>,
)

internal fun projectWorkflowMindMap(
    definition: WorkflowDefinition,
    run: WorkflowRun,
    roles: Collection<RoleDefinition>,
): WorkflowMindMapProjection {
    val tasksById = definition.tasks.associateBy { it.id }
    val rolesById = roles.associateBy { it.id }
    val depthByTask = mutableMapOf<TaskDefinitionId, Int>()

    fun depth(task: TaskDefinition): Int = depthByTask.getOrPut(task.id) {
        if (task.dependsOn.isEmpty()) 0 else 1 + task.dependsOn.maxOf { dependencyId ->
            tasksById[dependencyId]?.let(::depth) ?: 0
        }
    }

    val nodes = definition.tasks.map { task ->
        val taskRun = run.taskRuns[task.id]
        val executor = taskRun?.executor ?: task.effectiveExecutor()
        val role = taskRun?.assignedRoleId?.let(rolesById::get) ?: task.roleId?.let(rolesById::get)
        val identity = role?.name ?: executor.displayName()
        val motion = if (role != null) roleMotion(role.name) else executorMotion(executor)
        H2g2WorkflowNode(
            id = task.id.value,
            label = identity,
            subtitle = task.name,
            hueSeed = identity,
            motionSeed = "${executor.displayName()}:${identity}",
            motion = motion,
            state = taskRun?.status.toH2g2State(),
            progress = taskRun?.displayProgress(),
            detail = buildString {
                append(task.objective)
                append(" · Executor: ")
                append(executor.displayName())
                taskRun?.assignedProviderId?.let {
                    append(" · Provider: ")
                    append(it.value)
                }
                taskRun?.externalRunId?.takeIf(String::isNotBlank)?.let {
                    append(" · Run: ")
                    append(it)
                }
                taskRun?.progressMessage?.takeIf(String::isNotBlank)?.let {
                    append(" · ")
                    append(it)
                }
                taskRun?.blockingReason?.message?.takeIf(String::isNotBlank)?.let {
                    append(" · ")
                    append(it)
                }
                taskRun?.attempt?.takeIf { it > 1 }?.let {
                    append(" · Attempt ")
                    append(it)
                }
            },
        )
    }

    val nodeById = nodes.associateBy { it.id }
    val bands = definition.tasks
        .groupBy(::depth)
        .entries
        .sortedBy { it.key }
        .map { (_, tasks) -> H2g2WorkflowBand(tasks.mapNotNull { nodeById[it.id.value] }) }

    val edges = definition.tasks.flatMap { task ->
        task.dependsOn.map { dependency -> H2g2WorkflowEdge(from = dependency.value, to = task.id.value) }
    }

    return WorkflowMindMapProjection(bands = bands, edges = edges)
}

/**
 * Returns the subset of [taskIds] that are reachable ancestors of [root] (including [root]),
 * walking [dependsOn] edges in the definition.
 */
internal fun ancestorsOf(
    root: TaskDefinitionId,
    tasksById: Map<TaskDefinitionId, TaskDefinition>,
): Set<TaskDefinitionId> {
    val visited = mutableSetOf<TaskDefinitionId>()
    fun visit(id: TaskDefinitionId) {
        if (!visited.add(id)) return
        tasksById[id]?.dependsOn?.forEach(::visit)
    }
    visit(root)
    return visited
}

/**
 * Returns the subset of tasks that are reachable descendants of [root] (including [root]),
 * walking reverse edges in the definition.
 */
internal fun descendantsOf(
    root: TaskDefinitionId,
    tasksById: Map<TaskDefinitionId, TaskDefinition>,
): Set<TaskDefinitionId> {
    val reverseEdges: Map<TaskDefinitionId, List<TaskDefinitionId>> = buildMap<TaskDefinitionId, MutableList<TaskDefinitionId>> {
        tasksById.values.forEach { task ->
            task.dependsOn.forEach { dep ->
                getOrPut(dep) { mutableListOf() }.add(task.id)
            }
        }
    }
    val visited = mutableSetOf<TaskDefinitionId>()
    fun visit(id: TaskDefinitionId) {
        if (!visited.add(id)) return
        reverseEdges[id]?.forEach(::visit)
    }
    visit(root)
    return visited
}

/**
 * Returns a filtered projection containing only the subgraph reachable from [focusId]
 * (ancestors + descendants). Edges not connecting two retained nodes are dropped.
 * If [focusId] is null or not found, the full projection is returned unchanged.
 */
internal fun WorkflowMindMapProjection.focusOn(
    focusId: TaskDefinitionId?,
    definition: WorkflowDefinition,
): WorkflowMindMapProjection {
    if (focusId == null) return this
    val tasksById = definition.tasks.associateBy { it.id }
    if (focusId !in tasksById) return this
    val retained = ancestorsOf(focusId, tasksById) + descendantsOf(focusId, tasksById)
    val filteredBands = bands
        .map { band -> H2g2WorkflowBand(band.nodes.filter { TaskDefinitionId(it.id) in retained }) }
        .filter { it.nodes.isNotEmpty() }
    val filteredEdges = edges.filter {
        TaskDefinitionId(it.from) in retained && TaskDefinitionId(it.to) in retained
    }
    return copy(bands = filteredBands, edges = filteredEdges)
}

private fun TaskRunStatus?.toH2g2State(): H2g2WorkflowState = when (this) {
    null, TaskRunStatus.Created -> H2g2WorkflowState.Pending
    TaskRunStatus.Blocked -> H2g2WorkflowState.Blocked
    TaskRunStatus.Ready -> H2g2WorkflowState.Ready
    TaskRunStatus.AwaitingApproval, TaskRunStatus.Escalated -> H2g2WorkflowState.Gate
    TaskRunStatus.Planning, TaskRunStatus.Running, TaskRunStatus.Verifying, TaskRunStatus.Retrying -> H2g2WorkflowState.Active
    TaskRunStatus.Completed -> H2g2WorkflowState.Complete
    TaskRunStatus.Failed, TaskRunStatus.Cancelled -> H2g2WorkflowState.Failed
}

private fun TaskRun.displayProgress(): Float? = progress ?: when (status) {
    TaskRunStatus.Planning -> .14f
    TaskRunStatus.AwaitingApproval -> .24f
    TaskRunStatus.Running, TaskRunStatus.Retrying -> .52f
    TaskRunStatus.Verifying -> .82f
    TaskRunStatus.Completed -> 1f
    else -> null
}

internal fun executorMotion(executor: TaskExecutor): H2g2WorkflowMotion = when (executor) {
    is TaskExecutor.GitHubAction -> H2g2WorkflowMotion.Pulse
    is TaskExecutor.TestRunner -> H2g2WorkflowMotion.Skitter
    is TaskExecutor.Deployment -> H2g2WorkflowMotion.Float
    is TaskExecutor.RepositoryOperation -> H2g2WorkflowMotion.Tilt
    is TaskExecutor.HumanApproval -> H2g2WorkflowMotion.Nod
    is TaskExecutor.ExternalService -> H2g2WorkflowMotion.Hover
    is TaskExecutor.NestedWorkflow -> H2g2WorkflowMotion.Orbit
    is TaskExecutor.RoleAgent -> H2g2WorkflowMotion.Breathe
}

internal fun roleMotion(role: String): H2g2WorkflowMotion = when (role) {
    "Orchestrator" -> H2g2WorkflowMotion.Orbit
    "Product Manager" -> H2g2WorkflowMotion.Nod
    "Researcher" -> H2g2WorkflowMotion.Float
    "Architect" -> H2g2WorkflowMotion.Pendulum
    "EPA Representative" -> H2g2WorkflowMotion.Hover
    "UX Designer" -> H2g2WorkflowMotion.Sway
    "Implementation Engineer" -> H2g2WorkflowMotion.Scoot
    "Crash Test Dummy" -> H2g2WorkflowMotion.Wag
    "QA Engineer" -> H2g2WorkflowMotion.Skitter
    "Adversarial Reviewer" -> H2g2WorkflowMotion.Shimmy
    "Code Reviewer" -> H2g2WorkflowMotion.Tilt
    "Recovery Engineer" -> H2g2WorkflowMotion.Bob
    "Release Engineer" -> H2g2WorkflowMotion.Pulse
    else -> H2g2WorkflowMotion.Breathe
}
