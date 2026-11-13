package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.TimeLimit

/** What a step does, which sets the order of magnitude of a reasonable wait. */
enum class WorkKind {
    HostedModel, ToolLoopModel, OnDeviceModel, CliAgent, ExternalAgent, LongCreative, Repository, TestRun,
    Script, Deployment, ExternalService, GitHubAction, Human, Nested,
}

/** What the engine already knows about the step's request. All integers, so the result is exact. */
data class StepSize(val contextTokens: Int = 0, val upstreamArtifacts: Int = 0)

/** What the Source adds: keyless gateways are shared and paced. [paceSpacingMillis] is window / requests. */
data class SourceTrait(val keyless: Boolean = false, val paceSpacingMillis: Long? = null)

enum class Basis { Baseline, History, Keeper, None }

/** [millis] is the allowance (the soft point), [hardMillis] the stop; 0 means no limit. */
data class Allowance(val millis: Long, val hardMillis: Long, val basis: Basis) {
    val unlimited: Boolean get() = millis <= 0L

    companion object {
        val None = Allowance(0, 0, Basis.None)
    }
}

/**
 * The queen's policy for how long a creature may work (docs/Keepers-Room.md). Pure: no clock, no I/O, no model.
 * Every number is a named constant with where it came from: the kind sets the order of magnitude, size and
 * Source adjust it, history refines it within bounds, and the keeper's own figure wins over all of it.
 */
object DeadlinePolicy {
    private const val MINUTE = 60_000L

    /** base, per 10k context tokens, per upstream artifact, min, max, all in milliseconds. */
    private class Shape(val base: Long, val perTenK: Long, val perUpstream: Long, val min: Long, val max: Long)

    private fun shape(kind: WorkKind): Shape? = when (kind) {
        // One request is 90 s, or 15 min streaming (LlmRestApis, StreamingTextGeneration).
        WorkKind.HostedModel -> Shape(2 * MINUTE, 30_000, 15_000, 2 * MINUTE, 20 * MINUTE)
        // Up to 8 rounds of requests (ToolCalling).
        WorkKind.ToolLoopModel -> Shape(5 * MINUTE, 30_000, 20_000, 5 * MINUTE, 30 * MINUTE)
        // No backstop of its own; slow by nature.
        WorkKind.OnDeviceModel -> Shape(5 * MINUTE, 2 * MINUTE, 30_000, 5 * MINUTE, 60 * MINUTE)
        // The CLI process backstop is 30 minutes; it edits files.
        WorkKind.CliAgent -> Shape(10 * MINUTE, MINUTE, 30_000, 10 * MINUTE, 60 * MINUTE)
        // A2A observe backstop is 2 hours.
        WorkKind.ExternalAgent -> Shape(20 * MINUTE, MINUTE, MINUTE, 20 * MINUTE, 120 * MINUTE)
        // Moyai backstop is 6 hours; ffmpeg 15 minutes each.
        WorkKind.LongCreative -> Shape(30 * MINUTE, 0, 0, 30 * MINUTE, 360 * MINUTE)
        // Per-git backstop is 120 s.
        WorkKind.Repository -> Shape(2 * MINUTE, 0, 0, MINUTE, 10 * MINUTE)
        // The test runner backstop is 20 minutes.
        WorkKind.TestRun -> Shape(10 * MINUTE, 0, 0, 5 * MINUTE, 30 * MINUTE)
        // No backstop today (StandardExecutorIntegrations).
        WorkKind.Script -> Shape(5 * MINUTE, 0, 0, 2 * MINUTE, 30 * MINUTE)
        WorkKind.Deployment -> Shape(15 * MINUTE, 0, 0, 2 * MINUTE, 30 * MINUTE)
        WorkKind.ExternalService -> Shape(5 * MINUTE, 0, 0, 2 * MINUTE, 30 * MINUTE)
        // Includes the runner queue.
        WorkKind.GitHubAction -> Shape(20 * MINUTE, 0, 0, 10 * MINUTE, 90 * MINUTE)
        // The keeper is the clock; a child run's creatures carry their own.
        WorkKind.Human, WorkKind.Nested -> null
    }

    /** What a step does: its executor, and for a role's agent the kind its Source reports (an ordinary hosted tool loop if none). */
    fun kindOf(executor: com.hereliesaz.geministrator.domain.TaskExecutor?, providerKind: WorkKind?): WorkKind = when (executor) {
        null, is com.hereliesaz.geministrator.domain.TaskExecutor.RoleAgent -> providerKind ?: WorkKind.ToolLoopModel
        is com.hereliesaz.geministrator.domain.TaskExecutor.GitHubAction -> WorkKind.GitHubAction
        is com.hereliesaz.geministrator.domain.TaskExecutor.Script -> WorkKind.Script
        is com.hereliesaz.geministrator.domain.TaskExecutor.TestRunner -> WorkKind.TestRun
        is com.hereliesaz.geministrator.domain.TaskExecutor.Deployment -> WorkKind.Deployment
        is com.hereliesaz.geministrator.domain.TaskExecutor.RepositoryOperation -> WorkKind.Repository
        is com.hereliesaz.geministrator.domain.TaskExecutor.HumanApproval -> WorkKind.Human
        is com.hereliesaz.geministrator.domain.TaskExecutor.ExternalService -> WorkKind.ExternalService
        is com.hereliesaz.geministrator.domain.TaskExecutor.NestedWorkflow -> WorkKind.Nested
        is com.hereliesaz.geministrator.domain.TaskExecutor.Distributed -> kindOf(executor.delegate, providerKind)
    }

    /** Whether creatures of this kind are timed at all. */
    fun isTimed(kind: WorkKind): Boolean = shape(kind) != null

    /** Role refines kind by a fine adjustment, in percent, bounded so it can never change the order of magnitude. */
    const val ROLE_PERCENT_MIN = 75
    const val ROLE_PERCENT_MAX = 150

    /** The deterministic baseline in milliseconds; 0 for a kind that is not timed. */
    fun baseline(kind: WorkKind, size: StepSize, source: SourceTrait, rolePercent: Int = 100): Long {
        val s = shape(kind) ?: return 0L
        var millis = s.base +
            s.perTenK * (size.contextTokens.coerceAtLeast(0) / 10_000L) +
            s.perUpstream * size.upstreamArtifacts.coerceAtLeast(0)
        // The pacer spaces the calls of a tool loop too; one expected request for an answer, four for a loop.
        val requests = when (kind) {
            WorkKind.HostedModel -> 1L
            WorkKind.ToolLoopModel -> 4L
            else -> 0L
        }
        if (source.keyless) {
            millis = millis * 3 / 2 + 2 * MINUTE
            source.paceSpacingMillis?.let { millis += it.coerceAtLeast(0) * requests }
        }
        millis = millis * rolePercent.coerceIn(ROLE_PERCENT_MIN, ROLE_PERCENT_MAX) / 100
        return millis.coerceIn(s.min, s.max)
    }

    private const val MIN_SAMPLES = 5
    private const val FULL_WEIGHT_SAMPLES = 20
    private const val SLACK_PERCENT = 150

    /**
     * Refines [baseline] by what this app's own completed attempts took ([samples], active milliseconds, with
     * expired attempts recorded at their stop point). Fewer than five samples change nothing; the blend grows to
     * full weight at twenty; the result stays within half to triple the baseline and the kind's own bounds.
     */
    fun adapt(baseline: Long, samples: List<Long>, kind: WorkKind): Long {
        val s = shape(kind) ?: return 0L
        if (samples.size < MIN_SAMPLES) return baseline
        val sorted = samples.sorted()
        val p90 = sorted[((sorted.size * 9 + 9) / 10 - 1).coerceIn(0, sorted.lastIndex)]
        val adapted = p90 * SLACK_PERCENT / 100
        val weight = minOf(samples.size, FULL_WEIGHT_SAMPLES).toLong()
        val blended = (baseline * (FULL_WEIGHT_SAMPLES - weight) + adapted * weight) / FULL_WEIGHT_SAMPLES
        return blended.coerceIn(baseline / 2, baseline * 3).coerceIn(s.min, s.max)
    }

    /** The hard stop for an allowance: twice it, or five minutes past it, whichever is later. */
    fun hardFor(allowanceMillis: Long): Long =
        if (allowanceMillis <= 0L) 0L else maxOf(allowanceMillis * 2, allowanceMillis + 5 * MINUTE)

    /** The queen's own figure for an attempt, from the kind, size, Source and history. */
    fun resolveAuto(
        kind: WorkKind,
        size: StepSize,
        source: SourceTrait,
        history: List<Long> = emptyList(),
        rolePercent: Int = 100,
    ): Allowance {
        if (!isTimed(kind)) return Allowance.None
        val base = baseline(kind, size, source, rolePercent)
        val adapted = adapt(base, history, kind)
        return Allowance(adapted, hardFor(adapted), if (adapted != base) Basis.History else Basis.Baseline)
    }

    /**
     * What counts now: the keeper's [limit] over the queen's [auto] figure. A keeper's minutes above a provider's
     * own backstop pass through (the provider's backstop still ends the work first and reads as timed out).
     */
    fun effective(limit: TimeLimit, auto: Allowance): Allowance = when (limit) {
        TimeLimit.Auto -> auto
        TimeLimit.Off -> Allowance.None
        is TimeLimit.Minutes -> (limit.value * MINUTE).let { Allowance(it, hardFor(it), Basis.Keeper) }
    }
}
