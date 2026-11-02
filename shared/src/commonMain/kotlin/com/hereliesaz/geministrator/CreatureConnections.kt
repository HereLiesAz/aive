package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun

/** Another creature wired to this one, named by its role (else its task's name). */
internal data class CreatureLink(
    val taskId: TaskDefinitionId,
    val name: String,
    /** For a creature it feeds: whether that creature has taken its output (the same reading as Output used). */
    val tookOutput: Boolean,
)

/** A creature's Connections (docs/Swarm-OS.md, Accessibility): who feeds it, and whom it feeds. */
internal data class CreatureConnections(val fedBy: List<CreatureLink>, val feeds: List<CreatureLink>)

/**
 * The creatures [taskId] reads from (its `dependsOn`) and the ones that read from it, in plan order,
 * each fed creature marked by whether its run has taken the output ([TAKEN_UP]). Read from the run
 * only: a creature with no run yet has not taken it.
 */
internal fun creatureConnections(
    taskId: TaskDefinitionId,
    definition: WorkflowDefinition,
    run: WorkflowRun,
    roles: Collection<RoleDefinition>,
): CreatureConnections {
    val task = definition.tasks.firstOrNull { it.id == taskId }
        ?: return CreatureConnections(emptyList(), emptyList())
    val roleNames = roles.associate { it.id to it.name }
    fun link(other: TaskDefinition) = CreatureLink(
        taskId = other.id,
        name = (run.taskRuns[other.id]?.assignedRoleId ?: other.roleId)?.let(roleNames::get) ?: other.name,
        tookOutput = run.taskRuns[other.id]?.status in TAKEN_UP,
    )
    return CreatureConnections(
        fedBy = definition.tasks.filter { it.id in task.dependsOn }.map(::link),
        feeds = definition.tasks.filter { taskId in it.dependsOn }.map(::link),
    )
}
