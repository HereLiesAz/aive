package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.persistence.WorkflowPersistence

/**
 * Transactional authoring façade around [WorkflowComposer]. UI code chooses components and edges;
 * this service performs graph mutations, validates the result, and persists only valid definitions.
 */
class WorkflowCompositionService(
    private val persistence: WorkflowPersistence,
) {
    suspend fun reassignRole(
        definition: WorkflowDefinition,
        taskId: TaskDefinitionId,
        roleId: RoleDefinitionId,
    ): WorkflowDefinition {
        requireNotNull(persistence.roles.get(roleId)) { "Role ${roleId.value} is not installed" }
        val found = definition.tasks.any { it.id == taskId }
        require(found) { "Task ${taskId.value} is not defined" }
        return validated(
            definition.copy(
                tasks = definition.tasks.map { task ->
                    if (task.id == taskId) {
                        task.copy(roleId = roleId, executor = TaskExecutor.RoleAgent(roleId))
                    } else {
                        task
                    }
                },
            ),
        )
    }

    suspend fun addNestedWorkflow(
        definition: WorkflowDefinition,
        childId: WorkflowDefinitionId,
        taskId: TaskDefinitionId,
        dependsOn: Set<TaskDefinitionId> = emptySet(),
    ): WorkflowDefinition {
        val child = requireNotNull(persistence.definitions.get(childId)) {
            "Workflow ${childId.value} is not installed"
        }
        return validated(
            WorkflowComposer.nest(
                parent = definition,
                child = child,
                taskId = taskId,
                dependsOn = dependsOn,
            ),
        )
    }

    suspend fun inlineWorkflow(
        definition: WorkflowDefinition,
        childId: WorkflowDefinitionId,
        namespace: String,
        connectFrom: Set<TaskDefinitionId> = emptySet(),
        connectTo: Set<TaskDefinitionId> = emptySet(),
        roleOverrides: Map<RoleDefinitionId, RoleDefinitionId> = emptyMap(),
    ): WorkflowDefinition {
        requireInstalledRoles(roleOverrides.values)
        val child = requireNotNull(persistence.definitions.get(childId)) {
            "Workflow ${childId.value} is not installed"
        }
        return validated(
            WorkflowComposer.inline(
                parent = definition,
                child = child,
                namespace = namespace,
                connectFrom = connectFrom,
                connectTo = connectTo,
                roleOverrides = roleOverrides,
            ),
        )
    }

    suspend fun expandRoleTask(
        definition: WorkflowDefinition,
        taskId: TaskDefinitionId,
        replacementId: WorkflowDefinitionId,
        namespace: String = taskId.value,
        roleOverrides: Map<RoleDefinitionId, RoleDefinitionId> = emptyMap(),
    ): WorkflowDefinition {
        requireInstalledRoles(roleOverrides.values)
        val replacement = requireNotNull(persistence.definitions.get(replacementId)) {
            "Workflow ${replacementId.value} is not installed"
        }
        return validated(
            WorkflowComposer.expandRoleTask(
                definition = definition,
                taskId = taskId,
                replacement = replacement,
                namespace = namespace,
                roleOverrides = roleOverrides,
            ),
        )
    }

    /**
     * Adds tasks a habit proposed (see HabitInvocation) to [definition]: the first of them may wait on
     * [connectFrom], the rest keep the dependencies they came with. Task ids must be new. The result is
     * validated and returned, not saved, like every other method here; the proposal's own approval
     * policy is untouched, so each added task still asks a person before it runs.
     */
    fun addProposedTasks(
        definition: WorkflowDefinition,
        tasks: List<TaskDefinition>,
        connectFrom: Set<TaskDefinitionId> = emptySet(),
    ): WorkflowDefinition {
        require(tasks.isNotEmpty()) { "There is nothing to add." }
        val taken = definition.tasks.map { it.id }.toSet()
        val clashes = tasks.map { it.id }.filter { it in taken }
        require(clashes.isEmpty()) { "Task ids already in use: ${clashes.joinToString { it.value }}" }
        require(tasks.map { it.id }.distinct().size == tasks.size) { "Proposed task ids repeat." }
        val missing = connectFrom.filter { it !in taken }
        require(missing.isEmpty()) { "Cannot follow undefined tasks: ${missing.joinToString { it.value }}" }
        val added = tasks.mapIndexed { index, task ->
            if (index == 0) task.copy(dependsOn = task.dependsOn + connectFrom) else task
        }
        return validated(definition.copy(tasks = definition.tasks + added))
    }

    suspend fun saveAs(
        definition: WorkflowDefinition,
        id: WorkflowDefinitionId,
        name: String = definition.name,
        description: String? = definition.description,
    ): WorkflowDefinition {
        val saved = validated(definition.copy(id = id, name = name, description = description))
        persistence.definitions.put(saved)
        return saved
    }

    suspend fun save(definition: WorkflowDefinition): WorkflowDefinition {
        val saved = validated(definition)
        persistence.definitions.put(saved)
        return saved
    }

    fun validated(definition: WorkflowDefinition): WorkflowDefinition {
        val errors = WorkflowGraphValidator.validate(definition)
        require(errors.isEmpty()) { "Invalid composed workflow: ${errors.joinToString("; ")}" }
        return definition
    }

    private suspend fun requireInstalledRoles(roleIds: Collection<RoleDefinitionId>) {
        roleIds.distinct().forEach { roleId ->
            requireNotNull(persistence.roles.get(roleId)) { "Role ${roleId.value} is not installed" }
        }
    }
}
