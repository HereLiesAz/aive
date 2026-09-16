package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.workflow.WorkflowGraphValidator
import com.hereliesaz.geministrator.workflow.humanReadable

internal sealed interface TerrariumDependencyEditResult {
    data class Applied(val definition: WorkflowDefinition) : TerrariumDependencyEditResult
    data object NoChange : TerrariumDependencyEditResult
    data class Rejected(val reason: String) : TerrariumDependencyEditResult
}

/**
 * Drop semantics: dropping [downstreamId] onto [upstreamId] means the dragged task now depends on
 * the target task. The operation is rejected rather than partially applied when it would make the
 * workflow invalid or cyclic.
 */
internal fun addTerrariumDependency(
    definition: WorkflowDefinition,
    downstreamId: TaskDefinitionId,
    upstreamId: TaskDefinitionId,
): TerrariumDependencyEditResult {
    if (downstreamId == upstreamId) {
        return TerrariumDependencyEditResult.Rejected("A task cannot depend on itself")
    }
    val downstream = definition.tasks.firstOrNull { it.id == downstreamId }
        ?: return TerrariumDependencyEditResult.Rejected("Unknown task '${downstreamId.value}'")
    if (definition.tasks.none { it.id == upstreamId }) {
        return TerrariumDependencyEditResult.Rejected("Unknown dependency '${upstreamId.value}'")
    }
    if (upstreamId in downstream.dependsOn) return TerrariumDependencyEditResult.NoChange

    val candidate = definition.copy(
        tasks = definition.tasks.map { task ->
            if (task.id == downstreamId) task.copy(dependsOn = task.dependsOn + upstreamId) else task
        },
    )
    val errors = WorkflowGraphValidator.validate(candidate)
    if (errors.isNotEmpty()) {
        return TerrariumDependencyEditResult.Rejected(
            errors.joinToString(separator = "\n") { it.humanReadable() },
        )
    }
    return TerrariumDependencyEditResult.Applied(candidate)
}

/**
 * Removing a dependency is explicit so authoring UIs can expose a deliberate disconnect gesture
 * later without overloading ordinary movement or accidental drops.
 */
internal fun removeTerrariumDependency(
    definition: WorkflowDefinition,
    downstreamId: TaskDefinitionId,
    upstreamId: TaskDefinitionId,
): TerrariumDependencyEditResult {
    val downstream = definition.tasks.firstOrNull { it.id == downstreamId }
        ?: return TerrariumDependencyEditResult.Rejected("Unknown task '${downstreamId.value}'")
    if (upstreamId !in downstream.dependsOn) return TerrariumDependencyEditResult.NoChange

    val candidate = definition.copy(
        tasks = definition.tasks.map { task ->
            if (task.id == downstreamId) task.copy(dependsOn = task.dependsOn - upstreamId) else task
        },
    )
    val errors = WorkflowGraphValidator.validate(candidate)
    if (errors.isNotEmpty()) {
        return TerrariumDependencyEditResult.Rejected(
            errors.joinToString(separator = "\n") { it.humanReadable() },
        )
    }
    return TerrariumDependencyEditResult.Applied(candidate)
}
