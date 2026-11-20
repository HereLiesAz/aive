package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.toTaskExecutor

/*
 * Every swarm has a queen (keeper decision D16): the built-in Orchestrator, one creature of the swarm.
 * A queen added here is placed the way Add creature places any role (WorkflowTerrariumAuthoring.kt,
 * addTerrariumCreature): a new creature, unwired, with nothing feeding her and nothing waiting on her.
 * Pure: nothing here saves or launches.
 */

/** Whether [definition] has the queen: a creature whose role is the Orchestrator, named by its role or its executor. */
internal fun hasQueen(definition: WorkflowDefinition): Boolean = definition.tasks.any { task ->
    task.roleId == BuiltInRoles.Orchestrator.id ||
        (task.executor as? TaskExecutor.RoleAgent)?.roleId == BuiltInRoles.Orchestrator.id
}

/**
 * [definition] with the queen added when it has none, otherwise [definition] itself. She is added unwired, so
 * every existing dependency and condition stays exactly as it was.
 */
internal fun ensureQueen(definition: WorkflowDefinition): WorkflowDefinition {
    if (hasQueen(definition)) return definition
    val role = BuiltInRoles.Orchestrator
    val taken = definition.tasks.mapTo(mutableSetOf()) { it.id.value }
    val stem = role.id.value
    val id = if (stem !in taken) {
        TaskDefinitionId(stem)
    } else {
        TaskDefinitionId(generateSequence(2) { it + 1 }.map { "$stem-$it" }.first { it !in taken })
    }
    val queen = TaskDefinition(
        id = id,
        name = role.name,
        objective = role.description.ifBlank { role.name },
        roleId = role.id,
        executor = role.executionSource.toTaskExecutor(role.id),
    )
    return definition.copy(tasks = definition.tasks + queen)
}
