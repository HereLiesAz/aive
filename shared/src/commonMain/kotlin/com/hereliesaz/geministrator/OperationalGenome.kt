package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.RoleExecutionSource
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.effectiveTokenBudget
import com.hereliesaz.geministrator.events.ProviderUsageRecorded

/*
 * Functional genetics (docs/Node-Creature-Genome.md, "The genome"): the operational traits are observed from what
 * each role actually did, never inferred from artwork. Model tier, tolerance, resilience and signal efficiency are
 * read from the journal and ranked in thirds across the roster, so no absolute threshold is invented; synaptic
 * capacity comes from the effective token allowance. Everything here is a pure projection of retained run snapshots
 * and their journals, idempotent per run, and a trait with no evidence is null (unreported), never a default.
 */

/** What one role has been observed to do, summed over the settled runs seen. */
internal data class RoleProfile(
    /** Tokens and dollars over the sessions that were fully priced; cost per token needs both. */
    val pricedTokens: Long = 0,
    val costUsd: Double = 0.0,
    /** Output tokens and the time the provider took over the sessions that reported both. */
    val outputTokens: Long = 0,
    val timedMillis: Long = 0,
    /** Tasks that needed a retry, and how many of them still completed. */
    val retriedTasks: Int = 0,
    val recoveredTasks: Int = 0,
    /** Distinct "provider|model" pairs a task of this role completed on. */
    val succeededOn: Set<String> = emptySet(),
)

/** Core: the model tier, by observed cost per token. Dark is the cheapest third. */
internal enum class ModelTier { Dark, Dusk, Bright }

/** Spectrum: how many provider and model pairs it has succeeded on. */
internal enum class Tolerance { Narrow, Moderate, Wide }

/** Pattern: how often a task that needed a retry still completed. */
internal enum class Resilience { Fragile, Steady, Tough }

/** Skin: output tokens per second the provider delivered. */
internal enum class SignalEfficiency { Slow, Even, Fast }

internal data class OperationalTraits(
    val tier: ModelTier?, val tolerance: Tolerance?, val resilience: Resilience?, val efficiency: SignalEfficiency?,
)

/** Settled runs only advance this, so a live task's transient retry never counts. Replaying a run is a no-op. */
internal data class OperationalHistory(
    val profiles: Map<RoleDefinitionId, RoleProfile> = emptyMap(),
    private val seen: Set<WorkflowRunId> = emptySet(),
) {
    fun advance(run: WorkflowRun, reports: List<ProviderUsageRecorded>): OperationalHistory {
        if (run.id in seen) return this
        val roles = run.roleSnapshot.associateBy { it.id }
        if (roles.isEmpty()) return this
        val models = reportedSessionModels(run, reports)
        val next = profiles.toMutableMap()
        for ((taskId, task) in run.taskRuns) {
            val role = task.assignedRoleId?.let(roles::get)?.takeIf { it.executionSource == RoleExecutionSource.Agent }
                ?: continue
            var profile = next[role.id] ?: RoleProfile()
            val terminal = task.status == TaskRunStatus.Completed || task.status == TaskRunStatus.Failed ||
                task.status == TaskRunStatus.Escalated
            if (terminal && task.attempt > 1) {
                profile = profile.copy(retriedTasks = profile.retriedTasks + 1,
                    recoveredTasks = profile.recoveredTasks + if (task.status == TaskRunStatus.Completed) 1 else 0)
            }
            val provider = task.assignedProviderId?.value
            val model = models[taskId]
            if (task.status == TaskRunStatus.Completed && provider != null && model != null) {
                profile = profile.copy(succeededOn = profile.succeededOn + "$provider|$model")
            }
            // The task's own current session only; replayed work (tokens avoided) cost and took nothing.
            val own = reports.filter { event ->
                event.workflowRunId == run.id && event.taskDefinitionId == taskId &&
                    event.providerId == task.assignedProviderId && event.providerRunId == task.providerRunId &&
                    event.tokensAvoided == 0L
            }
            for (event in own) {
                val input = event.inputTokens
                val output = event.outputTokens
                val cost = event.costUsd
                if (input != null && output != null && cost != null && !event.costUnknown && input + output > 0) {
                    profile = profile.copy(pricedTokens = profile.pricedTokens + input + output, costUsd = profile.costUsd + cost)
                }
                val latency = event.latencyMillis
                if (output != null && output > 0 && latency != null && latency > 0) {
                    profile = profile.copy(outputTokens = profile.outputTokens + output, timedMillis = profile.timedMillis + latency)
                }
            }
            next[role.id] = profile
        }
        return OperationalHistory(next, seen + run.id)
    }
}

/**
 * Rank [values] in thirds: 0 the lowest, 2 the highest. Equal values share a third. Fewer than two distinct values
 * rank nothing, since there is nothing to rank against.
 */
internal fun <K> thirds(values: Map<K, Double>): Map<K, Int> {
    val distinct = values.values.distinct().sorted()
    if (distinct.size < 2) return emptyMap()
    return values.mapValues { (_, v) ->
        val p = distinct.indexOf(v).toDouble() / (distinct.size - 1)
        if (p < 1.0 / 3) 0 else if (p < 2.0 / 3) 1 else 2
    }
}

/** The four observed traits of every role with evidence for them. A role absent from [profiles] has none. */
internal fun operationalTraits(profiles: Map<RoleDefinitionId, RoleProfile>): Map<RoleDefinitionId, OperationalTraits> {
    val cost = thirds(profiles.filterValues { it.pricedTokens > 0 }.mapValues { (_, p) -> p.costUsd * 1000.0 / p.pricedTokens })
    val speed = thirds(profiles.filterValues { it.timedMillis > 0 && it.outputTokens > 0 }
        .mapValues { (_, p) -> p.outputTokens * 1000.0 / p.timedMillis })
    return profiles.mapValues { (id, p) ->
        OperationalTraits(
            tier = cost[id]?.let { ModelTier.entries[it] },
            tolerance = when (p.succeededOn.size) { 0 -> null; 1 -> Tolerance.Narrow; 2 -> Tolerance.Moderate; else -> Tolerance.Wide },
            resilience = if (p.retriedTasks == 0) null else when {
                p.recoveredTasks * 3 >= p.retriedTasks * 2 -> Resilience.Tough
                p.recoveredTasks * 3 >= p.retriedTasks -> Resilience.Steady
                else -> Resilience.Fragile
            },
            efficiency = speed[id]?.let { SignalEfficiency.entries[it] },
        )
    }
}

/**
 * How many synapses (distinct partner tasks) each agent role can hold, 2, 3 or 4, from its effective token allowance:
 * an unlimited allowance holds 4; the finite allowances are ranked in thirds across the roster (the smallest hold 2,
 * the largest 4), and a roster with a single finite allowance holds 3. A programmatic role has no capacity. This is
 * what a creature can sustain, shown and charged for in the Needs tab; it never refuses a workflow.
 */
internal fun synapseCapacities(roles: Collection<RoleDefinition>): Map<RoleDefinitionId, Int> {
    val agents = roles.filter { it.executionSource == RoleExecutionSource.Agent }
    val finite = agents.mapNotNull { role -> role.effectiveTokenBudget()?.let { role.id to it.toDouble() } }.toMap()
    val ranked = thirds(finite)
    return agents.associate { role ->
        role.id to when {
            role.id !in finite -> 4
            ranked.isEmpty() -> 3
            else -> 2 + ranked.getValue(role.id)
        }
    }
}
