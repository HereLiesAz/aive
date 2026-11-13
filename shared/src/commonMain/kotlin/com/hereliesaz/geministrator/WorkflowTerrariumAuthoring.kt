package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.toTaskExecutor
import com.hereliesaz.geministrator.workflow.WorkflowGraphValidator
import com.hereliesaz.geministrator.workflow.isEffectfulStep
import com.hereliesaz.geministrator.workflow.humanReadable
import com.hereliesaz.geministrator.distributed.withComputeDelegation

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

/** Detach (docs/Swarm-OS.md, keeper tools): cut every strand into and out of [taskId]. */
internal fun detachTerrariumCreature(definition: WorkflowDefinition, taskId: TaskDefinitionId): TerrariumDependencyEditResult {
    if (definition.tasks.none { it.id == taskId }) return TerrariumDependencyEditResult.Rejected("Unknown task '${taskId.value}'")
    val candidate = definition.copy(
        tasks = definition.tasks.map { task ->
            when {
                task.id == taskId -> task.copy(dependsOn = emptySet(), condition = com.hereliesaz.geministrator.domain.TaskCondition.Always)
                taskId in task.dependsOn -> task.copy(
                    dependsOn = task.dependsOn - taskId,
                    condition = task.condition.takeUnless { it.refersTo(taskId) } ?: com.hereliesaz.geministrator.domain.TaskCondition.Always,
                )
                else -> task
            }
        },
    )
    if (candidate == definition) return TerrariumDependencyEditResult.NoChange
    val errors = WorkflowGraphValidator.validate(candidate)
    if (errors.isNotEmpty()) return TerrariumDependencyEditResult.Rejected(errors.joinToString("\n") { it.humanReadable() })
    return TerrariumDependencyEditResult.Applied(candidate)
}

/**
 * Remove (docs/Keepers-Room.md): take [taskIds] out of the hive in one edit. Each creature's task goes,
 * and so does every strand touching it, as feeder or as fed; a knot that named a removed creature
 * reverts to the plain one. A strand that ran THROUGH a removed creature is not bridged: what it fed
 * is left without that feeder. The whole edit is one step on the tide, so one Undo brings back the
 * creatures and their strands. The result is validated like every other edit; for example, taking out
 * the approval ahead of a repository change is refused, never applied.
 */
internal fun removeTerrariumCreatures(definition: WorkflowDefinition, taskIds: Set<TaskDefinitionId>): TerrariumDependencyEditResult {
    if (taskIds.isEmpty()) return TerrariumDependencyEditResult.NoChange
    val known = definition.tasks.mapTo(mutableSetOf()) { it.id }
    taskIds.firstOrNull { it !in known }?.let {
        return TerrariumDependencyEditResult.Rejected("Unknown task '${it.value}'")
    }
    val candidate = definition.copy(
        tasks = definition.tasks.filterNot { it.id in taskIds }.map { task ->
            if (task.dependsOn.none { it in taskIds } && !task.condition.refersToAny(taskIds)) {
                task
            } else {
                task.copy(
                    dependsOn = task.dependsOn - taskIds,
                    condition = task.condition.takeUnless { it.refersToAny(taskIds) }
                        ?: com.hereliesaz.geministrator.domain.TaskCondition.Always,
                )
            }
        },
    )
    val errors = WorkflowGraphValidator.validate(candidate)
    if (errors.isNotEmpty()) return TerrariumDependencyEditResult.Rejected(errors.joinToString("\n") { it.humanReadable() })
    return TerrariumDependencyEditResult.Applied(candidate)
}

/** Remove is for a hive at rest: while a run of it is live, the wiring is not to be reshaped. */
internal fun terrariumRemoveBlocked(run: com.hereliesaz.geministrator.domain.WorkflowRun): Boolean =
    run.status == com.hereliesaz.geministrator.domain.WorkflowRunStatus.Running ||
        run.status == com.hereliesaz.geministrator.domain.WorkflowRunStatus.AwaitingHuman

private fun com.hereliesaz.geministrator.domain.TaskCondition.refersToAny(taskIds: Set<TaskDefinitionId>): Boolean = when (this) {
    is com.hereliesaz.geministrator.domain.TaskCondition.OnAnyOutcome -> ofTask in taskIds
    is com.hereliesaz.geministrator.domain.TaskCondition.OnFailure -> ofTask in taskIds
    com.hereliesaz.geministrator.domain.TaskCondition.Always -> false
}

private fun com.hereliesaz.geministrator.domain.TaskCondition.refersTo(taskId: TaskDefinitionId): Boolean = when (this) {
    is com.hereliesaz.geministrator.domain.TaskCondition.OnAnyOutcome -> ofTask == taskId
    is com.hereliesaz.geministrator.domain.TaskCondition.OnFailure -> ofTask == taskId
    com.hereliesaz.geministrator.domain.TaskCondition.Always -> false
}

/**
 * Duplicate by budding: a second creature of the same config with its own task, fed by the same
 * strands. Nothing depends on the bud until it is wired. That's a clone, and the needs panel says so.
 */
internal fun budTerrariumCreature(definition: WorkflowDefinition, taskId: TaskDefinitionId): TerrariumDependencyEditResult {
    val source = definition.tasks.firstOrNull { it.id == taskId }
        ?: return TerrariumDependencyEditResult.Rejected("Unknown task '${taskId.value}'")
    val taken = definition.tasks.mapTo(mutableSetOf()) { it.id.value }
    val budId = generateSequence(2) { it + 1 }.map { "${taskId.value}-$it" }.first { it !in taken }
    val bud = source.copy(id = TaskDefinitionId(budId), name = "${source.name} (bud)")
    val candidate = definition.copy(tasks = definition.tasks + bud)
    val errors = WorkflowGraphValidator.validate(candidate)
    if (errors.isNotEmpty()) return TerrariumDependencyEditResult.Rejected(errors.joinToString("\n") { it.humanReadable() })
    return TerrariumDependencyEditResult.Applied(candidate)
}

/**
 * A creature that can join a hive in Build (docs/Swarm-OS.md: add and release creatures): a role
 * from the swarm, or a programmatic step that wears the neuron. Only steps that run as they are, with
 * nothing to configure, are offered; a repository change is not, since alone it would have no
 * approval ahead of it (THREAT_MODEL.md, invariant 4).
 */
internal sealed interface TerrariumNewcomer {
    data class FromRole(val role: com.hereliesaz.geministrator.domain.RoleDefinition) : TerrariumNewcomer
    data class Step(val step: TerrariumStep) : TerrariumNewcomer

    /** A step template, made at the version [base] resolves to, so the step pins it. [name] is its title. */
    data class FromTemplate(
        val template: com.hereliesaz.geministrator.template.StepTemplate,
        val base: com.hereliesaz.geministrator.template.TemplateBase,
        val name: String,
    ) : TerrariumNewcomer
}

/** The programmatic steps Add creature offers, each a ready [com.hereliesaz.geministrator.domain.TaskExecutor]. */
internal enum class TerrariumStep(
    val idStem: String,
    val taskName: String,
    val objective: String,
    val executor: com.hereliesaz.geministrator.domain.TaskExecutor,
) {
    Tests("tests", "Run tests", "Run the project's tests.", com.hereliesaz.geministrator.domain.TaskExecutor.TestRunner()),
    RepositoryStatus("repo-status", "Repository status", "Read the repository's status.", com.hereliesaz.geministrator.domain.TaskExecutor.RepositoryOperation("status")),
    RepositoryFetch("repo-fetch", "Fetch repository", "Fetch the repository's latest changes.", com.hereliesaz.geministrator.domain.TaskExecutor.RepositoryOperation("fetch")),
}

/** The roles a keeper can add from: those switched on in the swarm, by name. */
internal fun terrariumAddableRoles(
    roles: Collection<com.hereliesaz.geministrator.domain.RoleDefinition>,
): List<com.hereliesaz.geministrator.domain.RoleDefinition> = roles.filter { it.enabled }.sortedBy { it.name.lowercase() }

/** The new task's id: [stem] itself, else the first free `stem-2`, `stem-3`, ... */
private fun freshTerrariumTaskId(definition: WorkflowDefinition, stem: String): TaskDefinitionId {
    val taken = definition.tasks.mapTo(mutableSetOf()) { it.id.value }
    val base = stem.ifBlank { "creature" }
    if (base !in taken) return TaskDefinitionId(base)
    return TaskDefinitionId(generateSequence(2) { it + 1 }.map { "$base-$it" }.first { it !in taken })
}

/**
 * Add creature: [newcomer] joins the hive as a new task, unwired. Nothing feeds it and nothing
 * depends on it until the keeper wires it. A role's task runs on the role's own execution source,
 * so a role that runs a script or an action wears the neuron too. The result is validated like
 * every other edit and is rejected rather than partially applied.
 */
internal fun addTerrariumCreature(definition: WorkflowDefinition, newcomer: TerrariumNewcomer): TerrariumDependencyEditResult {
    val task = when (newcomer) {
        is TerrariumNewcomer.FromRole -> {
            val role = newcomer.role
            if (!role.enabled) return TerrariumDependencyEditResult.Rejected("${role.name} is switched off in the swarm")
            com.hereliesaz.geministrator.domain.TaskDefinition(
                id = freshTerrariumTaskId(definition, role.id.value),
                name = role.name,
                objective = role.description.ifBlank { role.name },
                roleId = role.id,
                executor = role.executionSource.toTaskExecutor(role.id),
            )
        }
        is TerrariumNewcomer.Step -> com.hereliesaz.geministrator.domain.TaskDefinition(
            id = freshTerrariumTaskId(definition, newcomer.step.idStem),
            name = newcomer.step.taskName,
            objective = newcomer.step.objective,
            roleId = null,
            executor = newcomer.step.executor,
        )
        is TerrariumNewcomer.FromTemplate -> com.hereliesaz.geministrator.template.StepInstantiation.task(
            template = newcomer.template,
            base = newcomer.base,
            id = freshTerrariumTaskId(definition, newcomer.template.id),
            name = newcomer.name,
            inputs = emptyMap(),
        )
    }
    val candidate = definition.copy(tasks = definition.tasks + task)
    val errors = WorkflowGraphValidator.validate(candidate)
    if (errors.isNotEmpty()) return TerrariumDependencyEditResult.Rejected(errors.joinToString("\n") { it.humanReadable() })
    return TerrariumDependencyEditResult.Applied(candidate)
}

/**
 * A step's input: [value] is a literal, or `@<taskId>.<port>` to take it from another step (which it then waits for).
 * A blank value clears it. Rejected when the step has no such port.
 */
internal fun setTerrariumContractInput(
    definition: WorkflowDefinition,
    taskId: TaskDefinitionId,
    port: String,
    value: String,
): TerrariumDependencyEditResult {
    val found = definition.tasks.firstOrNull { it.id == taskId }
        ?: return TerrariumDependencyEditResult.Rejected("Unknown task '${taskId.value}'")
    val contract = found.contract ?: return TerrariumDependencyEditResult.Rejected("This creature has no step inputs")
    val template = com.hereliesaz.geministrator.template.BuiltInTemplates.byId[contract.template.id]
    if (template == null || template.inputs.none { it.name == port }) {
        return TerrariumDependencyEditResult.Rejected("This step has no '$port' input")
    }
    val trimmed = value.trim()
    val inputs = if (trimmed.isEmpty()) contract.inputs - port else contract.inputs + (port to trimmed)
    if (inputs == contract.inputs) return TerrariumDependencyEditResult.NoChange
    val step = com.hereliesaz.geministrator.template.StepInstantiation
    val fed = inputs.values.mapNotNull(step::boundTask).toSet()
    val wasFed = contract.inputs.values.mapNotNull(step::boundTask).toSet()
    val next = found.copy(
        objective = step.objectiveWith(step.wordingOf(found.objective), inputs),
        dependsOn = (found.dependsOn - wasFed) + fed,
        contract = contract.copy(inputs = inputs),
    )
    val candidate = definition.copy(tasks = definition.tasks.map { if (it.id == taskId) next else it })
    val errors = WorkflowGraphValidator.validate(candidate)
    if (errors.isNotEmpty()) return TerrariumDependencyEditResult.Rejected(errors.joinToString("\n") { it.humanReadable() })
    return TerrariumDependencyEditResult.Applied(candidate)
}

/** The one tool a step that takes one may use (by its offer name); blank clears it, so the step has none. */
internal fun setTerrariumContractTool(
    definition: WorkflowDefinition,
    taskId: TaskDefinitionId,
    tool: String,
): TerrariumDependencyEditResult {
    val found = definition.tasks.firstOrNull { it.id == taskId }
        ?: return TerrariumDependencyEditResult.Rejected("Unknown task '${taskId.value}'")
    val contract = found.contract ?: return TerrariumDependencyEditResult.Rejected("This creature has no step tool")
    val template = com.hereliesaz.geministrator.template.BuiltInTemplates.byId[contract.template.id]
    if (template == null || template.toolPick == com.hereliesaz.geministrator.template.ToolPick.None) {
        return TerrariumDependencyEditResult.Rejected("This step takes no tool")
    }
    val tools = listOfNotNull(tool.trim().takeIf(String::isNotEmpty))
    if (tools == contract.allowedTools) return TerrariumDependencyEditResult.NoChange
    val next = found.copy(contract = contract.copy(allowedTools = tools))
    return TerrariumDependencyEditResult.Applied(definition.copy(tasks = definition.tasks.map { if (it.id == taskId) next else it }))
}

/** Hold output: withhold the creature's artifacts from every consumer, or release them again. */
internal fun setTerrariumHold(definition: WorkflowDefinition, taskId: TaskDefinitionId, hold: Boolean): TerrariumDependencyEditResult {
    val task = definition.tasks.firstOrNull { it.id == taskId }
        ?: return TerrariumDependencyEditResult.Rejected("Unknown task '${taskId.value}'")
    if (task.holdOutput == hold) return TerrariumDependencyEditResult.NoChange
    return TerrariumDependencyEditResult.Applied(
        definition.copy(tasks = definition.tasks.map { if (it.id == taskId) it.copy(holdOutput = hold) else it }),
    )
}

/** A circuit fold: the parent with one nested-workflow creature in place of the group, and the group itself. */
internal sealed interface TerrariumFoldResult {
    data class Folded(val parent: WorkflowDefinition, val circuit: WorkflowDefinition) : TerrariumFoldResult
    data class Rejected(val reason: String) : TerrariumFoldResult
}

/**
 * Groups (docs/Swarm-OS.md): fold [members] into a circuit, a nested workflow that runs as one
 * creature. Strands inside the group stay inside; strands into it feed the circuit; strands out of
 * it come from the circuit; knots naming a member now name the circuit.
 */
internal fun foldTerrariumCircuit(definition: WorkflowDefinition, members: Set<TaskDefinitionId>): TerrariumFoldResult {
    if (members.size < 2) return TerrariumFoldResult.Rejected("A circuit needs at least two creatures")
    val inside = definition.tasks.filter { it.id in members }
    if (inside.size != members.size) return TerrariumFoldResult.Rejected("Unknown task in the group")
    val taken = definition.tasks.mapTo(mutableSetOf()) { it.id.value }
    val circuitTaskId = TaskDefinitionId(generateSequence(1) { it + 1 }.map { "circuit-$it" }.first { it !in taken })
    val circuitId = com.hereliesaz.geministrator.domain.WorkflowDefinitionId("${definition.id.value}--${circuitTaskId.value}")
    val always = com.hereliesaz.geministrator.domain.TaskCondition.Always

    val circuit = WorkflowDefinition(
        id = circuitId,
        name = "${definition.name}: ${inside.joinToString(", ") { it.name }}",
        tasks = inside.map { task ->
            task.copy(
                dependsOn = task.dependsOn.filterTo(mutableSetOf()) { it in members },
                condition = if (task.condition.refersOutside(members)) always else task.condition,
            )
        },
    )
    val circuitTask = com.hereliesaz.geministrator.domain.TaskDefinition(
        id = circuitTaskId,
        name = "Circuit: ${inside.joinToString(", ") { it.name }}",
        objective = inside.joinToString("\n") { it.objective },
        roleId = null,
        dependsOn = inside.flatMap { it.dependsOn }.filterTo(mutableSetOf()) { it !in members },
        executor = com.hereliesaz.geministrator.domain.TaskExecutor.NestedWorkflow(circuitId),
    )
    fun TaskDefinitionId.outer() = if (this in members) circuitTaskId else this
    val parent = definition.copy(
        tasks = definition.tasks.filterNot { it.id in members }.map { task ->
            task.copy(
                dependsOn = task.dependsOn.mapTo(mutableSetOf()) { it.outer() },
                condition = when (val c = task.condition) {
                    is com.hereliesaz.geministrator.domain.TaskCondition.OnAnyOutcome -> c.copy(ofTask = c.ofTask.outer())
                    is com.hereliesaz.geministrator.domain.TaskCondition.OnFailure -> c.copy(ofTask = c.ofTask.outer())
                    com.hereliesaz.geministrator.domain.TaskCondition.Always -> c
                },
            )
        } + circuitTask,
    )
    val errors = WorkflowGraphValidator.validate(parent) + WorkflowGraphValidator.validate(circuit)
    if (errors.isNotEmpty()) return TerrariumFoldResult.Rejected(errors.joinToString("\n") { it.humanReadable() })
    return TerrariumFoldResult.Folded(parent, circuit)
}

private fun com.hereliesaz.geministrator.domain.TaskCondition.refersOutside(members: Set<TaskDefinitionId>): Boolean = when (this) {
    is com.hereliesaz.geministrator.domain.TaskCondition.OnAnyOutcome -> ofTask !in members
    is com.hereliesaz.geministrator.domain.TaskCondition.OnFailure -> ofTask !in members
    com.hereliesaz.geministrator.domain.TaskCondition.Always -> false
}

/** A creature's job (H4): the task's own fields, edited in place. Only what is given changes. */
internal fun setTerrariumJob(
    definition: WorkflowDefinition,
    taskId: TaskDefinitionId,
    objective: String? = null,
    criteria: List<String>? = null,
    humanApproval: Boolean? = null,
    maxAttempts: Int? = null,
    /** Where it runs: this device, any paired device, or one chosen device (the Compute place's targets). */
    runsOn: com.hereliesaz.geministrator.distributed.ComputeDelegationTarget? = null,
    /** How long it may work before the queen says it is slow (Auto, the keeper's minutes, or no limit). */
    timeLimit: com.hereliesaz.geministrator.domain.TimeLimit? = null,
): TerrariumDependencyEditResult {
    val found = definition.tasks.firstOrNull { it.id == taskId }
        ?: return TerrariumDependencyEditResult.Rejected("Unknown task '${taskId.value}'")
    if (objective != null && objective.isBlank()) return TerrariumDependencyEditResult.Rejected("A job needs an objective")
    // A step that changes something always asks first and tries once (THREAT_MODEL invariant 4a).
    if (found.isEffectfulStep()) {
        if (humanApproval == false) return TerrariumDependencyEditResult.Rejected("This step changes something, so it always asks you first")
        if (maxAttempts != null && maxAttempts != 1) return TerrariumDependencyEditResult.Rejected("This step changes something, so it tries once")
    }
    // A person's approval step never leaves this device; withComputeDelegation leaves it as it is.
    val task = runsOn?.let(found::withComputeDelegation) ?: found
    val next = task.copy(
        objective = objective?.trim() ?: task.objective,
        acceptanceCriteria = criteria?.map(String::trim)?.filter(String::isNotEmpty)
            ?.map { com.hereliesaz.geministrator.domain.AcceptanceCriterion(it) } ?: task.acceptanceCriteria,
        approvalPolicy = when (humanApproval) {
            null -> task.approvalPolicy
            true -> com.hereliesaz.geministrator.domain.ApprovalPolicy.HumanApproval
            false -> com.hereliesaz.geministrator.domain.ApprovalPolicy.None
        },
        retryPolicy = maxAttempts?.let { task.retryPolicy.copy(maxAttempts = it.coerceIn(1, 10)) } ?: task.retryPolicy,
        timeLimit = when (timeLimit) {
            is com.hereliesaz.geministrator.domain.TimeLimit.Minutes ->
                com.hereliesaz.geministrator.domain.TimeLimit.minutes(timeLimit.value)
            null -> task.timeLimit
            else -> timeLimit
        },
    )
    if (next == found) return TerrariumDependencyEditResult.NoChange
    return TerrariumDependencyEditResult.Applied(definition.copy(tasks = definition.tasks.map { if (it.id == taskId) next else it }))
}
