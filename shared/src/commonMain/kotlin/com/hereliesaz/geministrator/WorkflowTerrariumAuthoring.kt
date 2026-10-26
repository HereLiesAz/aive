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

/**
 * The tide (docs/Swarm-OS.md): every build edit to a workflow's wiring, undoable and redoable.
 * It holds definitions, not diffs, so undo restores exactly what was there. Run history is not in it.
 */
internal data class TerrariumTide(
    val past: List<WorkflowDefinition> = emptyList(),
    val present: WorkflowDefinition,
    val future: List<WorkflowDefinition> = emptyList(),
) {
    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    /** Records [next] as the new present; a fresh edit clears what could have been redone. */
    fun record(next: WorkflowDefinition): TerrariumTide =
        if (next == present) this else TerrariumTide(past + present, next, emptyList())

    fun undo(): TerrariumTide =
        if (!canUndo) this else TerrariumTide(past.dropLast(1), past.last(), listOf(present) + future)

    fun redo(): TerrariumTide =
        if (!canRedo) this else TerrariumTide(past + present, future.first(), future.drop(1))
}

/** A tether's knot (docs/Bowl-OS.md): when the fed task runs, relative to this feeder. */
internal enum class TerrariumKnot { OnSuccess, OnAnyOutcome, OnFailure }

/** The knot on the tether from [upstreamId] into [downstreamId], read from the task's condition. */
internal fun terrariumKnot(definition: WorkflowDefinition, downstreamId: TaskDefinitionId, upstreamId: TaskDefinitionId): TerrariumKnot {
    val condition = definition.tasks.firstOrNull { it.id == downstreamId }?.condition
    return when {
        condition is com.hereliesaz.geministrator.domain.TaskCondition.OnAnyOutcome && condition.ofTask == upstreamId -> TerrariumKnot.OnAnyOutcome
        condition is com.hereliesaz.geministrator.domain.TaskCondition.OnFailure && condition.ofTask == upstreamId -> TerrariumKnot.OnFailure
        else -> TerrariumKnot.OnSuccess
    }
}

/**
 * Ties [knot] on an existing tether. A task carries one condition, so knotting a second feeder
 * replaces the first feeder's knot; tying OnSuccess clears the condition only if this feeder held it.
 */
internal fun setTerrariumKnot(
    definition: WorkflowDefinition,
    downstreamId: TaskDefinitionId,
    upstreamId: TaskDefinitionId,
    knot: TerrariumKnot,
): TerrariumDependencyEditResult {
    val downstream = definition.tasks.firstOrNull { it.id == downstreamId }
        ?: return TerrariumDependencyEditResult.Rejected("Unknown task '${downstreamId.value}'")
    if (upstreamId !in downstream.dependsOn) {
        return TerrariumDependencyEditResult.Rejected("'${upstreamId.value}' does not feed '${downstreamId.value}'")
    }
    if (terrariumKnot(definition, downstreamId, upstreamId) == knot) return TerrariumDependencyEditResult.NoChange
    val condition = when (knot) {
        TerrariumKnot.OnSuccess -> com.hereliesaz.geministrator.domain.TaskCondition.Always
        TerrariumKnot.OnAnyOutcome -> com.hereliesaz.geministrator.domain.TaskCondition.OnAnyOutcome(upstreamId)
        TerrariumKnot.OnFailure -> com.hereliesaz.geministrator.domain.TaskCondition.OnFailure(upstreamId)
    }
    val candidate = definition.copy(
        tasks = definition.tasks.map { if (it.id == downstreamId) it.copy(condition = condition) else it },
    )
    val errors = WorkflowGraphValidator.validate(candidate)
    if (errors.isNotEmpty()) {
        return TerrariumDependencyEditResult.Rejected(errors.joinToString(separator = "\n") { it.humanReadable() })
    }
    return TerrariumDependencyEditResult.Applied(candidate)
}
