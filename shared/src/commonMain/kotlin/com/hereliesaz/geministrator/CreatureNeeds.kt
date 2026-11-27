package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleExecutionSource
import com.hereliesaz.geministrator.domain.effectiveExecutor
import com.hereliesaz.geministrator.domain.effectiveTokenBudget
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.events.ProviderUsageRecorded
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Swarm OS step 2 (docs/Swarm-OS.md): a creature's four needs, read from the run and nothing else
 * (docs/Ecology-and-the-Swarm.md, the binding rule). A need with no data behind it is null and shown
 * as not reported, never estimated.
 *
 * Echo exposure is a graph proxy using recorded session model evidence. Actual per-input
 * and memory-read provenance is not available here. It must never certify contamination, sepsis,
 * independence or a completed review.
 */
internal data class CreatureNeeds(
    /** 0..100 of comparable active input/review paths with near-identical declared configuration. */
    val echo: Int,
    val echoState: EchoState,
    /** 0..100: its reliability in this run; retries and failures cost it. */
    val integrity: Int,
    /** 0..100 of its agent's token budget left in this run; null when the user set no budget. */
    val budget: Int?,
    /** Tokens its provider has reported spending on it; null when none were reported. */
    val tokensSpent: Long?,
    /** 0..100: how much of its downstream has taken its output; null when nothing depends on it. */
    val outputUsed: Int?,
    /** Another task with high configuration overlap and matching reported provider/model. */
    val nearClone: String?,
    /** Comparable active reviewer paths with high configuration overlap; not proof of a review. */
    val selfReviews: Int,
    val reviews: Int,
    /** Number of comparable active paths. Zero means Echo exposure is not reported. */
    val echoComparablePaths: Int = 0,
)

internal enum class EchoState { Grounded, SelfAbsorbed, Fevered, Insane }

internal fun echoStateOf(echo: Int): EchoState = when {
    echo >= 85 -> EchoState.Insane
    echo >= 60 -> EchoState.Fevered
    echo >= 30 -> EchoState.SelfAbsorbed
    else -> EchoState.Grounded
}

/** The kinds whose work is reviewing others' work: Echo counts them, and the Swarm filters on them. */
internal val REVIEWER_KINDS = setOf(
    NodeCreatureRoleKind.QaEngineer,
    NodeCreatureRoleKind.AdversarialReviewer,
    NodeCreatureRoleKind.CodeReviewer,
    NodeCreatureRoleKind.CrashTestDummy,
    NodeCreatureRoleKind.Antagonist,
    NodeCreatureRoleKind.HallMonitor,
    NodeCreatureRoleKind.ComplianceOfficer,
)

/** Whether a role, by its name, is a reviewer (see [REVIEWER_KINDS]). */
internal fun isReviewerRoleName(roleName: String): Boolean = classifyNodeCreatureRole(roleName) in REVIEWER_KINDS

internal val TAKEN_UP = setOf(
    TaskRunStatus.Planning,
    TaskRunStatus.AwaitingApproval,
    TaskRunStatus.Running,
    TaskRunStatus.Verifying,
    TaskRunStatus.Retrying,
    TaskRunStatus.Completed,
)

/**
 * Resolve only consistent model reports bound to the run's exact current task session.
 * Old, missing, mixed and replayed reports cannot establish a single model. This is
 * provider-reported identity, not independent attestation or an exhaustive inference trace.
 */
internal fun reportedSessionModels(run: WorkflowRun, reports: List<ProviderUsageRecorded>): Map<TaskDefinitionId,String> {
    val grouped=reports.filter { event ->
        val task=run.taskRuns[event.taskDefinitionId]
        event.workflowRunId==run.id && task!=null &&
            event.providerId==task.assignedProviderId && event.providerRunId==task.providerRunId
    }.groupBy { it.taskDefinitionId }
    return grouped.mapNotNull { (id, events) ->
        if(events.any { it.modelConsistent!=true || it.model.isNullOrBlank() || it.tokensAvoided!=0L }) null
        else events.mapNotNull { it.model }.distinct().singleOrNull()?.let { id to it }
    }.toMap()
}

/** Shared run-correlated journal projection for the inspector and tissue, never the GPU. */
internal val LocalModelUsageReports = staticCompositionLocalOf<List<ProviderUsageRecorded>> { emptyList() }

/**
 * Provisional overlap: provider 20 and model 30 retain the previous provider-half weight;
 * exact instructions 30 and declared capability/authority/tool/surface contract 20.
 * Missing model/provider evidence is unknown, not a clone or a stranger.
 */
internal fun configurationKinship(
    a: RoleDefinition, providerA: String?, b: RoleDefinition, providerB: String?,
    modelA: String?, modelB: String?,
): Int? {
    if(a.executionSource!=RoleExecutionSource.Agent || b.executionSource!=RoleExecutionSource.Agent ||
        providerA.isNullOrBlank() || providerB.isNullOrBlank() ||
        modelA.isNullOrBlank() || modelB.isNullOrBlank()) return null
    val provider=if(providerA==providerB) 20 else 0
    val model=if(modelA==modelB) 30 else 0
    // Exact instruction text avoids treating a negated safety constraint as equivalent.
    val instructions=if(a.instructions==b.instructions && a.keeperInstructions==b.keeperInstructions) 30 else 0
    val contract=if(a.capabilitiesRequired==b.capabilitiesRequired &&
        a.authorities==b.authorities && a.toolCeiling.toSet()==b.toolCeiling.toSet() &&
        a.surfaces==b.surfaces) 20 else 0
    return provider+model+instructions+contract
}

/** Near-clone threshold from Node-Creature-Genome.md; applied only to comparable configurations. */
internal const val NEAR_CLONE_KINSHIP = 95

internal fun creatureNeeds(
    taskId: TaskDefinitionId,
    definition: WorkflowDefinition,
    run: WorkflowRun,
    roles: Collection<RoleDefinition>,
    modelReports: List<ProviderUsageRecorded> = emptyList(),
): CreatureNeeds? {
    val tasks = definition.tasks.associateBy(TaskDefinition::id)
    val task = tasks[taskId] ?: return null
    // Historical runs keep their own configurations; later roster edits cannot rewrite their ancestry.
    val rolesById = if(run.roleSnapshot.isNotEmpty()) run.roleSnapshot.associateBy(RoleDefinition::id)
        else roles.associateBy(RoleDefinition::id)

    fun role(id: TaskDefinitionId): RoleDefinition? =
        (run.taskRuns[id]?.assignedRoleId ?: tasks[id]?.roleId)?.let(rolesById::get)

    fun agent(id: TaskDefinitionId): Boolean {
        val t=tasks[id] ?: return false
        return (run.taskRuns[id]?.executor ?: t.effectiveExecutor()).isAgent
    }

    val models=reportedSessionModels(run,modelReports)
    fun kinship(other: TaskDefinitionId): Int? {
        if(!agent(taskId) || !agent(other)) return null
        val own=role(taskId) ?: return null
        val peer=role(other) ?: return null
        return configurationKinship(own,run.taskRuns[taskId]?.assignedProviderId?.value,
            peer,run.taskRuns[other]?.assignedProviderId?.value,models[taskId],models[other])
    }

    fun isReviewer(id: TaskDefinitionId): Boolean =
        agent(id) && role(id)?.let { isReviewerRoleName(it.name) } == true

    val dependents = definition.tasks.filter { taskId in it.dependsOn }.map(TaskDefinition::id)
    // These are graph paths eligible to carry voices, not recorded reads or verdicts.
    // Ignore future paths and unknown agents rather than counting their absence as diversity.
    val active = run.taskRuns[taskId]?.status in TAKEN_UP
    val inputs = if(active) task.dependsOn.filter { run.taskRuns[it]?.status == TaskRunStatus.Completed } else emptyList()
    val reviewers = if(run.taskRuns[taskId]?.status == TaskRunStatus.Completed) {
        dependents.filter { isReviewer(it) && run.taskRuns[it]?.status in TAKEN_UP }
    } else emptyList()
    fun exposure(id: TaskDefinitionId): Int? {
        if(!agent(taskId)) return null
        // A completed programmatic dependency is a distinct source, not a model clone.
        return if(!agent(id)) 0 else kinship(id)
    }
    val voices=(inputs+reviewers).distinct().mapNotNull(::exposure)
    val reviewScores=reviewers.mapNotNull(::kinship)
    val selfReviews=reviewScores.count { it>=NEAR_CLONE_KINSHIP }
    val echo=if(voices.isEmpty()) 0 else voices.count { it>=NEAR_CLONE_KINSHIP }*100/voices.size

    val taskRun = run.taskRuns[taskId]
    val attempt = taskRun?.attempt ?: 1
    val failed = taskRun?.status == TaskRunStatus.Failed || taskRun?.status == TaskRunStatus.Escalated
    val integrity = (100 - 15 * (attempt - 1) - if (failed) 40 else 0).coerceIn(0, 100)

    // The budget belongs to the agent (its role) and covers everything it spends in this run.
    val roleId = taskRun?.assignedRoleId ?: task.roleId
    val budgetLeft = roleId?.let(rolesById::get)?.effectiveTokenBudget()?.let { cap ->
        val spent = run.taskRuns.values.filter { it.assignedRoleId == roleId }.sumOf { it.usage?.totalTokens ?: 0 }
        (100 - spent * 100 / cap).coerceIn(0, 100).toInt()
    }

    val outputUsed = if (dependents.isEmpty()) {
        null
    } else {
        dependents.count { run.taskRuns[it]?.status in TAKEN_UP } * 100 / dependents.size
    }
    val nearClone = definition.tasks.asSequence()
        .filter { it.id != taskId }
        .mapNotNull { peer -> kinship(peer.id)?.let { peer to it } }
        .filter { (_, score) -> score >= NEAR_CLONE_KINSHIP }
        .sortedWith(compareByDescending<Pair<TaskDefinition,Int>> { it.second }.thenBy { it.first.id.value })
        .firstOrNull()?.first?.name

    return CreatureNeeds(
        echo = echo,
        echoState = echoStateOf(echo),
        integrity = integrity,
        budget = budgetLeft,
        tokensSpent = taskRun?.usage?.totalTokens,
        outputUsed = outputUsed,
        nearClone = nearClone,
        selfReviews = selfReviews,
        reviews = reviewScores.size,
        echoComparablePaths = voices.size,
    )
}
