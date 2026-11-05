package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.RoleExecutionSource
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.orchestration.OrchestrationPacket
import com.hereliesaz.geministrator.orchestration.OrchestrationPlan
import com.hereliesaz.geministrator.orchestration.OrchestrationRole
import com.hereliesaz.geministrator.workflow.WorkflowGraphValidator
import kotlinx.serialization.SerializationException

/*
 * Drafting a swarm from a prompt (docs/Keepers-Room.md, New hive). A keeper describes the work; the
 * planner proposes creatures, strands and a job for each. This file is the pure layer between the
 * planner's output and the hive: it checks the proposal, turns it into tasks, and never launches
 * anything, never touches a repository and never removes an approval. A draft is only a proposal
 * the keeper then edits in Build like any other hive.
 */

/** The most creatures a draft may hold; a bigger proposal is refused rather than cut, which would break strands. */
internal const val SWARM_DRAFT_MAX_CREATURES = 12

/** The longest prompt sent to the planner; the rest is dropped. */
internal const val SWARM_DRAFT_MAX_PROMPT_CHARS = 2_000

private const val NAME_MAX = 60
private const val OBJECTIVE_MAX = 1_000
private const val HIVE_NAME_MAX = 40

/** Why a proposal could not be used; the screen turns each into a plain sentence. */
internal enum class SwarmDraftProblem { NoPrompt, NoRoles, Empty, TooMany, UnknownRole, Cycle, Unreadable }

internal sealed interface SwarmDraftResult {
    /** [tasks] are the proposed creatures, wired; [suggestedName] names a hive that has no name yet. */
    data class Ready(val tasks: List<TaskDefinition>, val suggestedName: String) : SwarmDraftResult

    data class Problem(val problem: SwarmDraftProblem) : SwarmDraftResult

    /** The linked provider failed; [raw] goes through [classifyError] to say what to do next. */
    data class ProviderFailed(val raw: String?) : SwarmDraftResult

    /** No planner is linked on this device. */
    data object Unavailable : SwarmDraftResult
}

/** The prompt as the planner sees it: trimmed and bounded. */
internal fun swarmDraftPrompt(prompt: String): String = prompt.trim().take(SWARM_DRAFT_MAX_PROMPT_CHARS)

/**
 * Roles the planner may staff a draft with: switched on and run by an agent. A role that runs a
 * script or an action is never drafted; the keeper brings those in by hand.
 */
internal fun swarmDraftRoles(roles: Collection<RoleDefinition>): List<RoleDefinition> =
    roles.filter { it.enabled && it.executionSource == RoleExecutionSource.Agent }.distinctBy { it.id }

/** The planning packet for [prompt]; it carries no repository and no state to continue. */
internal fun swarmDraftPacket(prompt: String, roles: Collection<RoleDefinition>): OrchestrationPacket =
    OrchestrationPacket(
        objective = swarmDraftPrompt(prompt),
        currentState = "draft-swarm",
        availableAgents = swarmDraftRoles(roles).map { role ->
            OrchestrationRole(id = role.id.value, name = role.name, authorities = role.authorities.map { it.name }.sorted())
        },
        instruction = "Propose the smallest dependency-correct swarm of creatures that can do this work, " +
            "at most $SWARM_DRAFT_MAX_CREATURES, each with one bounded job. This is a proposal for a person to edit; nothing runs.",
    )

/**
 * Checks the planner's [plan] against [roles] and turns it into tasks. Every step becomes an
 * agent task (never a repository change), a step the planner marked for approval keeps its
 * approval, and ids are made safe and unique. Anything malformed is a [SwarmDraftResult.Problem].
 */
internal fun swarmDraftFromPlan(prompt: String, plan: OrchestrationPlan, roles: Collection<RoleDefinition>): SwarmDraftResult {
    if (swarmDraftPrompt(prompt).isEmpty()) return SwarmDraftResult.Problem(SwarmDraftProblem.NoPrompt)
    val staff = swarmDraftRoles(roles).associateBy { it.id.value }
    if (staff.isEmpty()) return SwarmDraftResult.Problem(SwarmDraftProblem.NoRoles)
    val steps = plan.steps
    if (steps.isEmpty()) return SwarmDraftResult.Problem(SwarmDraftProblem.Empty)
    if (steps.size > SWARM_DRAFT_MAX_CREATURES) return SwarmDraftResult.Problem(SwarmDraftProblem.TooMany)
    if (steps.any { it.roleId !in staff }) return SwarmDraftResult.Problem(SwarmDraftProblem.UnknownRole)

    val rawIds = steps.map { it.id.trim() }
    if (rawIds.any(String::isEmpty) || rawIds.toSet().size != rawIds.size) return SwarmDraftResult.Problem(SwarmDraftProblem.Unreadable)
    val known = rawIds.toSet()
    steps.forEachIndexed { index, step ->
        val deps = step.dependsOn.map(String::trim)
        if (deps.any { it !in known }) return SwarmDraftResult.Problem(SwarmDraftProblem.Unreadable)
        if (rawIds[index] in deps) return SwarmDraftResult.Problem(SwarmDraftProblem.Cycle)
    }
    if (hasCycle(rawIds, steps.map { step -> step.dependsOn.map(String::trim) })) return SwarmDraftResult.Problem(SwarmDraftProblem.Cycle)

    // Safe, unique task ids; strands follow the renaming.
    val taken = mutableSetOf<String>()
    val idFor = rawIds.associateWith { raw ->
        val stem = raw.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "creature" }.take(40)
        val id = generateSequence(1) { it + 1 }.map { if (it == 1) stem else "$stem-$it" }.first { it !in taken }
        taken += id
        TaskDefinitionId(id)
    }
    val tasks = steps.mapIndexed { index, step ->
        val role = staff.getValue(step.roleId)
        TaskDefinition(
            id = idFor.getValue(rawIds[index]),
            name = step.name.trim().ifEmpty { role.name }.take(NAME_MAX),
            objective = step.objective.trim().ifEmpty { role.description.ifBlank { role.name } }.take(OBJECTIVE_MAX),
            roleId = RoleDefinitionId(role.id.value),
            executor = TaskExecutor.RoleAgent(role.id),
            dependsOn = step.dependsOn.mapTo(mutableSetOf()) { idFor.getValue(it.trim()) },
            approvalPolicy = if (step.requiresHumanApproval) ApprovalPolicy.HumanApproval else ApprovalPolicy.None,
        )
    }
    val candidate = WorkflowDefinition(id = com.hereliesaz.geministrator.domain.WorkflowDefinitionId("draft"), name = "draft", tasks = tasks)
    if (WorkflowGraphValidator.validate(candidate).isNotEmpty()) return SwarmDraftResult.Problem(SwarmDraftProblem.Unreadable)
    return SwarmDraftResult.Ready(tasks, swarmDraftName(prompt))
}

private fun hasCycle(ids: List<String>, deps: List<List<String>>): Boolean {
    val edges = ids.indices.associate { ids[it] to deps[it] }
    val done = mutableSetOf<String>()
    val path = mutableSetOf<String>()
    fun visit(id: String): Boolean {
        if (id in done) return false
        if (!path.add(id)) return true
        if (edges.getValue(id).any(::visit)) return true
        path.remove(id)
        done += id
        return false
    }
    return ids.any(::visit)
}

/** A hive name taken from the prompt's first line, cut at a word. */
internal fun swarmDraftName(prompt: String): String {
    val line = prompt.trim().lineSequence().firstOrNull().orEmpty().trim().trimEnd('.', '!', '?', ':', ';', ',')
    if (line.length <= HIVE_NAME_MAX) return line
    val cut = line.take(HIVE_NAME_MAX)
    return cut.substringBeforeLast(' ', cut).trimEnd().ifEmpty { cut }
}

/**
 * A failure thrown while planning, as a result. A provider's own failure (out of credit, rate
 * limited, offline, a rejected key) keeps its message for [classifyError]; output that could not be
 * read is a [SwarmDraftProblem.Unreadable]. It never rethrows, so a bad answer cannot crash the screen.
 */
internal fun swarmDraftFailure(failure: Throwable): SwarmDraftResult {
    val message = failure.message
    return when {
        failure is SerializationException -> SwarmDraftResult.Problem(SwarmDraftProblem.Unreadable)
        message != null && "unavailable on this platform" in message -> SwarmDraftResult.Unavailable
        classifyError(message) != ErrorKind.Other -> SwarmDraftResult.ProviderFailed(message)
        failure is IllegalArgumentException -> SwarmDraftResult.Problem(SwarmDraftProblem.Unreadable)
        else -> SwarmDraftResult.ProviderFailed(message)
    }
}

/**
 * [current] with the draft's creatures in place of its own. Name, description and policies stay;
 * only the layout changes, so one undo step on the tide restores [current] exactly.
 */
internal fun redraftedHive(current: WorkflowDefinition, draft: SwarmDraftResult.Ready): WorkflowDefinition =
    current.copy(tasks = draft.tasks)

/** How many of the keeper's creatures a redraft would replace: above zero, ask before replacing. */
internal fun redraftReplaces(current: WorkflowDefinition): Int = current.tasks.size
