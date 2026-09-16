package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowFragmentId
import kotlinx.serialization.Serializable

/**
 * A named, reusable subgraph of tasks. Fragments are a composition primitive for
 * [WorkflowDefinition] authoring — they are expanded at definition time and do not
 * exist at runtime. A fragment can reference tasks inside itself via [TaskDefinition.dependsOn];
 * connections to tasks outside the fragment are established by the caller during expansion.
 */
@Serializable
data class WorkflowFragment(
    val id: WorkflowFragmentId,
    val name: String,
    val tasks: List<TaskDefinition>,
    val entryPoints: Set<TaskDefinitionId> = tasks.map { it.id }.toSet() -
        tasks.flatMap { t -> tasks.filter { dep -> t.id in dep.dependsOn }.map { it.id } }.toSet(),
    val exitPoints: Set<TaskDefinitionId> = tasks.map { it.id }.toSet() - tasks.flatMap { it.dependsOn }.toSet(),
)

/**
 * Returns a copy of this definition with [fragment] tasks appended.
 * [connectFrom] tasks in the existing definition become dependencies for each of the
 * fragment's [WorkflowFragment.entryPoints]. [connectTo] tasks receive each of the
 * fragment's [WorkflowFragment.exitPoints] as new dependencies.
 */
fun WorkflowDefinition.withFragment(
    fragment: WorkflowFragment,
    connectFrom: Set<TaskDefinitionId> = emptySet(),
    connectTo: Set<TaskDefinitionId> = emptySet(),
): WorkflowDefinition {
    val expandedFragmentTasks = fragment.tasks.map { task ->
        if (task.id in fragment.entryPoints && connectFrom.isNotEmpty()) {
            task.copy(dependsOn = task.dependsOn + connectFrom)
        } else {
            task
        }
    }
    val updatedExistingTasks = if (connectTo.isEmpty()) {
        tasks
    } else {
        val fragmentExitIds = fragment.exitPoints
        tasks.map { task ->
            if (task.id in connectTo) {
                task.copy(dependsOn = task.dependsOn + fragmentExitIds)
            } else {
                task
            }
        }
    }
    return copy(tasks = updatedExistingTasks + expandedFragmentTasks)
}
