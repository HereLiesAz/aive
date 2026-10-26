package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun

/**
 * Swarm OS step 2 (docs/Swarm-OS.md): a creature's four needs, read from the run and nothing else
 * (docs/Ecology-and-the-Swarm.md, the binding rule). A need with no data behind it is null and shown
 * as not reported, never estimated.
 *
 * A creature's config here is its role plus its provider: two tasks with the same pair are
 * near-clones.
 */
internal data class CreatureNeeds(
    /** 0..100: how much of what it hears is its own config (inputs, and reviews of its work). */
    val echo: Int,
    val echoState: EchoState,
    /** 0..100: its reliability in this run; retries and failures cost it. */
    val integrity: Int,
    /** 0..100, or null when no token usage is reported for the task. */
    val budget: Int?,
    /** 0..100: how much of its downstream has taken its output; null when nothing depends on it. */
    val outputUsed: Int?,
    /** The other task with the same config, if any. */
    val nearClone: String?,
    /** How many of the reviews of its work came from its own config. */
    val selfReviews: Int,
    val reviews: Int,
)

internal enum class EchoState { Grounded, SelfAbsorbed, Fevered, Insane }

internal fun echoStateOf(echo: Int): EchoState = when {
    echo >= 85 -> EchoState.Insane
    echo >= 60 -> EchoState.Fevered
    echo >= 30 -> EchoState.SelfAbsorbed
    else -> EchoState.Grounded
}

private val REVIEWER_KINDS = setOf(
    NodeCreatureRoleKind.QaEngineer,
    NodeCreatureRoleKind.AdversarialReviewer,
    NodeCreatureRoleKind.CodeReviewer,
    NodeCreatureRoleKind.CrashTestDummy,
    NodeCreatureRoleKind.Antagonist,
    NodeCreatureRoleKind.HallMonitor,
    NodeCreatureRoleKind.ComplianceOfficer,
)

private val TAKEN_UP = setOf(
    TaskRunStatus.Planning,
    TaskRunStatus.AwaitingApproval,
    TaskRunStatus.Running,
    TaskRunStatus.Verifying,
    TaskRunStatus.Retrying,
    TaskRunStatus.Completed,
)

internal fun creatureNeeds(
    taskId: TaskDefinitionId,
    definition: WorkflowDefinition,
    run: WorkflowRun,
    roles: Collection<RoleDefinition>,
): CreatureNeeds? {
    val tasks = definition.tasks.associateBy(TaskDefinition::id)
    val task = tasks[taskId] ?: return null
    val rolesById = roles.associateBy(RoleDefinition::id)

    fun config(id: TaskDefinitionId): Pair<String?, String?> {
        val taskRun = run.taskRuns[id]
        val role = taskRun?.assignedRoleId?.value ?: tasks[id]?.roleId?.value
        return role to taskRun?.assignedProviderId?.value
    }
    fun isReviewer(id: TaskDefinitionId): Boolean {
        val t = tasks[id] ?: return false
        val roleName = (run.taskRuns[id]?.assignedRoleId ?: t.roleId)?.let { rolesById[it]?.name ?: it.value }
        return roleName != null && classifyNodeCreatureRole(roleName) in REVIEWER_KINDS
    }

    val own = config(taskId)
    val dependents = definition.tasks.filter { taskId in it.dependsOn }.map(TaskDefinition::id)
    val reviewers = dependents.filter(::isReviewer)
    // Everything it hears: the inputs it reads, and the reviews of its work.
    val voices = task.dependsOn.toList() + reviewers
    val ownVoices = voices.count { config(it) == own }
    val selfReviews = reviewers.count { config(it) == own }
    val echo = if (voices.isEmpty()) 0 else ownVoices * 100 / voices.size

    val taskRun = run.taskRuns[taskId]
    val attempt = taskRun?.attempt ?: 1
    val failed = taskRun?.status == TaskRunStatus.Failed || taskRun?.status == TaskRunStatus.Escalated
    val integrity = (100 - 15 * (attempt - 1) - if (failed) 40 else 0).coerceIn(0, 100)

    val outputUsed = if (dependents.isEmpty()) {
        null
    } else {
        dependents.count { run.taskRuns[it]?.status in TAKEN_UP } * 100 / dependents.size
    }
    val nearClone = definition.tasks
        .firstOrNull { it.id != taskId && own.first != null && config(it.id) == own }
        ?.name

    return CreatureNeeds(
        echo = echo,
        echoState = echoStateOf(echo),
        integrity = integrity,
        budget = null,
        outputUsed = outputUsed,
        nearClone = nearClone,
        selfReviews = selfReviews,
        reviews = reviewers.size,
    )
}
