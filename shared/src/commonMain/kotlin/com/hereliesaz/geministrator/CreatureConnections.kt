package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.events.ProviderUsageRecorded

/** Another creature wired to this one, named by its role (else its task's name). */
internal data class CreatureLink(
    val taskId: TaskDefinitionId,
    val name: String,
    /** Whether delivery was reported and matched; null means unknown. */
    val tookOutput: Boolean?,
)

/** A creature's Connections (docs/Swarm-OS.md, Accessibility): who feeds it, and whom it feeds. */
internal data class CreatureConnections(val fedBy: List<CreatureLink>, val feeds: List<CreatureLink>)

/**
 * The creatures [taskId] reads from (its `dependsOn`) and the ones that read from it, in plan order,
 * each link annotated with current-session artifact delivery evidence.
 */
internal fun creatureConnections(
    taskId: TaskDefinitionId,
    definition: WorkflowDefinition,
    run: WorkflowRun,
    roles: Collection<RoleDefinition>,
    reports: List<ProviderUsageRecorded> = emptyList(),
): CreatureConnections {
    val task = definition.tasks.firstOrNull { it.id == taskId }
        ?: return CreatureConnections(emptyList(), emptyList())
    val roleNames = roles.associate { it.id to it.name }
    val deliveries = reportedArtifactDeliveries(run, reports)
    fun link(other: TaskDefinition, fedBy: Boolean) = CreatureLink(
        taskId = other.id,
        name = (run.taskRuns[other.id]?.assignedRoleId ?: other.roleId)?.let(roleNames::get) ?: other.name,
        tookOutput = if (fedBy) artifactDelivered(run, other.id, taskId, deliveries)
            else artifactDelivered(run, taskId, other.id, deliveries),
    )
    return CreatureConnections(
        fedBy = definition.tasks.filter { it.id in task.dependsOn }.map { link(it, true) },
        feeds = definition.tasks.filter { taskId in it.dependsOn }.map { link(it, false) },
    )
}
