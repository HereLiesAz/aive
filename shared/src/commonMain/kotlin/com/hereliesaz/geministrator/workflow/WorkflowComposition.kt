package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AcceptanceCriterion
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskCondition
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId

/**
 * Composition primitives shared by Store workflows, authored workflows, and roles.
 *
 * A role is the degenerate one-node workflow. A workflow can be nested as one node or expanded
 * inline as a namespaced task subgraph. Expanding a role-task replaces that node with any workflow,
 * preserving incoming and outgoing graph edges.
 */
object WorkflowComposer {
    fun roleAsWorkflow(
        role: RoleDefinition,
        workflowId: WorkflowDefinitionId = WorkflowDefinitionId("role-${role.id.value}"),
        taskId: TaskDefinitionId = TaskDefinitionId("perform-${role.id.value}"),
        objective: String = role.description,
        acceptanceCriteria: List<AcceptanceCriterion> = emptyList(),
    ): WorkflowDefinition = WorkflowDefinition(
        id = workflowId,
        name = role.name,
        description = "Single-role workflow for ${role.name}.",
        tasks = listOf(
            TaskDefinition(
                id = taskId,
                name = role.name,
                objective = objective,
                roleId = role.id,
                acceptanceCriteria = acceptanceCriteria,
                executor = TaskExecutor.RoleAgent(role.id),
            ),
        ),
    )

    /** Add another workflow as a single recursively executable node. */
    fun nest(
        parent: WorkflowDefinition,
        child: WorkflowDefinition,
        taskId: TaskDefinitionId,
        name: String = child.name,
        objective: String = child.description ?: child.name,
        dependsOn: Set<TaskDefinitionId> = emptySet(),
    ): WorkflowDefinition {
        require(parent.tasks.none { it.id == taskId }) { "Task id ${taskId.value} already exists" }
        require(dependsOn.all { dependency -> parent.tasks.any { it.id == dependency } }) {
            "Nested workflow dependencies must reference tasks in the parent workflow"
        }
        return parent.copy(
            tasks = parent.tasks + TaskDefinition(
                id = taskId,
                name = name,
                objective = objective,
                roleId = null,
                dependsOn = dependsOn,
                executor = TaskExecutor.NestedWorkflow(child.id),
            ),
        )
    }

    /**
     * Inline [child] into [parent]. Child task ids are prefixed with [namespace], all internal
     * dependencies/conditions are remapped, parent [connectFrom] tasks feed child entry points,
     * and child exit points feed parent [connectTo] tasks.
     */
    fun inline(
        parent: WorkflowDefinition,
        child: WorkflowDefinition,
        namespace: String,
        connectFrom: Set<TaskDefinitionId> = emptySet(),
        connectTo: Set<TaskDefinitionId> = emptySet(),
        roleOverrides: Map<RoleDefinitionId, RoleDefinitionId> = emptyMap(),
    ): WorkflowDefinition {
        val cleanNamespace = requireNamespace(namespace)
        require(connectFrom.all { dependency -> parent.tasks.any { it.id == dependency } }) {
            "connectFrom contains a task that is not in the parent workflow"
        }
        require(connectTo.all { target -> parent.tasks.any { it.id == target } }) {
            "connectTo contains a task that is not in the parent workflow"
        }

        val remap = child.tasks.associate { task ->
            task.id to TaskDefinitionId("$cleanNamespace.${task.id.value}")
        }
        val newIds = remap.values.toSet()
        val parentIds = parent.tasks.mapTo(mutableSetOf()) { it.id }
        require(newIds.none { it in parentIds }) { "Namespaced child task id collides with parent task id" }

        val entries = entryPoints(child)
        val exits = exitPoints(child)
        val expanded = child.tasks.map { task ->
            val remappedRole = task.roleId?.let { roleOverrides[it] ?: it }
            val remappedExecutor = when (val executor = task.executor) {
                is TaskExecutor.RoleAgent -> TaskExecutor.RoleAgent(roleOverrides[executor.roleId] ?: executor.roleId)
                else -> executor
            }
            task.copy(
                id = remap.getValue(task.id),
                roleId = remappedRole,
                dependsOn = task.dependsOn.mapTo(linkedSetOf()) { remap.getValue(it) } +
                    if (task.id in entries) connectFrom else emptySet(),
                condition = remapCondition(task.condition, remap),
                executor = remappedExecutor,
            )
        }
        val remappedExits = exits.mapTo(linkedSetOf()) { remap.getValue(it) }
        val updatedParent = parent.tasks.map { task ->
            if (task.id in connectTo) task.copy(dependsOn = task.dependsOn + remappedExits) else task
        }
        return parent.copy(tasks = updatedParent + expanded)
    }

    /**
     * Replace one role-agent task with an orchestrated workflow. The replacement inherits the
     * original task's prerequisites, and every former consumer waits for all replacement exits.
     * This is the role -> orchestrated-event expansion operation.
     */
    fun expandRoleTask(
        definition: WorkflowDefinition,
        taskId: TaskDefinitionId,
        replacement: WorkflowDefinition,
        namespace: String = taskId.value,
        roleOverrides: Map<RoleDefinitionId, RoleDefinitionId> = emptyMap(),
    ): WorkflowDefinition {
        val original = requireNotNull(definition.tasks.firstOrNull { it.id == taskId }) {
            "Task ${taskId.value} is not defined"
        }
        require(original.executor is TaskExecutor.RoleAgent || original.roleId != null) {
            "Task ${taskId.value} is not a role task and cannot be expanded as a role"
        }

        val consumers = definition.tasks.filter { taskId in it.dependsOn }.mapTo(linkedSetOf()) { it.id }
        val withoutOriginal = definition.copy(
            tasks = definition.tasks
                .filterNot { it.id == taskId }
                .map { task ->
                    if (taskId in task.dependsOn) task.copy(dependsOn = task.dependsOn - taskId) else task
                },
        )
        return inline(
            parent = withoutOriginal,
            child = replacement,
            namespace = namespace,
            connectFrom = original.dependsOn,
            connectTo = consumers,
            roleOverrides = roleOverrides,
        )
    }

    fun entryPoints(definition: WorkflowDefinition): Set<TaskDefinitionId> =
        definition.tasks.filter { it.dependsOn.isEmpty() }.mapTo(linkedSetOf()) { it.id }

    fun exitPoints(definition: WorkflowDefinition): Set<TaskDefinitionId> {
        val dependedUpon = definition.tasks.flatMapTo(mutableSetOf()) { it.dependsOn }
        return definition.tasks.filter { it.id !in dependedUpon }.mapTo(linkedSetOf()) { it.id }
    }

    private fun remapCondition(
        condition: TaskCondition,
        remap: Map<TaskDefinitionId, TaskDefinitionId>,
    ): TaskCondition = when (condition) {
        TaskCondition.Always -> condition
        is TaskCondition.OnAnyOutcome -> TaskCondition.OnAnyOutcome(remap.getValue(condition.ofTask))
        is TaskCondition.OnFailure -> TaskCondition.OnFailure(remap.getValue(condition.ofTask))
    }

    private fun requireNamespace(raw: String): String {
        val clean = raw.trim().trim('.')
        require(clean.isNotEmpty()) { "Workflow composition namespace must not be blank" }
        require(clean.none(Char::isWhitespace)) { "Workflow composition namespace must not contain whitespace" }
        return clean
    }
}
