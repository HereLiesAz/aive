package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.events.AgentAssigned
import com.hereliesaz.geministrator.events.TaskRerunRequested
import com.hereliesaz.geministrator.events.PolicyDecisionRecorded
import com.hereliesaz.geministrator.events.ExternalRunCancelFailed
import com.hereliesaz.geministrator.events.ApprovalDecisionReceived
import com.hereliesaz.geministrator.events.ApprovalRequired
import com.hereliesaz.geministrator.events.ArtifactCreated
import com.hereliesaz.geministrator.events.ExecutorAssigned
import com.hereliesaz.geministrator.events.HumanDecisionRequired
import com.hereliesaz.geministrator.events.ProviderUsageRecorded
import com.hereliesaz.geministrator.events.RetryScheduled
import com.hereliesaz.geministrator.events.TaskBecameReady
import com.hereliesaz.geministrator.events.TaskCancelled
import com.hereliesaz.geministrator.events.TaskCompleted
import com.hereliesaz.geministrator.events.TaskEscalated
import com.hereliesaz.geministrator.events.TaskFailed
import com.hereliesaz.geministrator.events.TaskStarted
import com.hereliesaz.geministrator.events.VerificationFailed
import com.hereliesaz.geministrator.events.WorkflowCancelled
import com.hereliesaz.geministrator.events.WorkflowCompleted
import com.hereliesaz.geministrator.events.WorkflowCreated
import com.hereliesaz.geministrator.events.WorkflowEvent
import com.hereliesaz.geministrator.events.WorkflowFailed
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.seconds

/** What a creature was doing during one stretch of an attempt. */
internal enum class WaterfallPhase {
    /** Before it started, or before an attempt's first gate: drafting its plan. */
    Planning,
    /** Held at a gate (a plan or task to approve, an escalation): the run pauses on you. */
    WaitingOnYou,
    Running,
    /** It produced artifacts and they are being checked. */
    Verifying,
    /** Its check or attempt failed and the next attempt is scheduled. */
    Retrying,
}

/** How an attempt ended; null while it is still going or when the run ended around it. */
internal enum class WaterfallOutcome { Completed, Failed, Cancelled }

internal data class WaterfallSegment(val phase: WaterfallPhase, val startMillis: Long, val endMillis: Long) {
    val durationMillis: Long get() = endMillis - startMillis
}

/** Provider usage summed over an attempt; [costUsd] is null when no provider reported a cost. */
internal data class WaterfallUsage(val inputTokens: Long = 0, val outputTokens: Long = 0, val costUsd: Double? = null) {
    val totalTokens: Long get() = inputTokens + outputTokens
}

/** One attempt of one task, from its first event to its last, on the run's time axis. */
internal data class WaterfallBar(
    val taskId: TaskDefinitionId,
    val attempt: Int,
    val startMillis: Long,
    val endMillis: Long,
    val segments: List<WaterfallSegment>,
    val outcome: WaterfallOutcome?,
    /** Still going: it ends at "now", and grows while the run lives. */
    val ongoing: Boolean,
    val usage: WaterfallUsage?,
) {
    val durationMillis: Long get() = endMillis - startMillis
    val key: String get() = "${taskId.value}#$attempt#$startMillis"

    /** Time held at gates in this attempt. */
    val waitingMillis: Long get() = segments.filter { it.phase == WaterfallPhase.WaitingOnYou }.sumOf { it.durationMillis }
}

/** One creature's row: its attempts, in order; they never overlap each other. */
internal data class WaterfallRow(val taskId: TaskDefinitionId, val bars: List<WaterfallBar>)

/** A run as a waterfall: every attempt on one shared time axis, so parallel work overlaps. */
internal data class RunWaterfall(
    val startMillis: Long,
    val endMillis: Long,
    val rows: List<WaterfallRow>,
    /** True when the run has not finished: unfinished bars end at the "now" it was built with. */
    val live: Boolean,
) {
    val spanMillis: Long get() = (endMillis - startMillis).coerceAtLeast(1)
    val bars: List<WaterfallBar> get() = rows.flatMap { it.bars }.sortedWith(compareBy({ it.startMillis }, { it.taskId.value }))
    val isEmpty: Boolean get() = rows.isEmpty()

    companion object {
        val Empty = RunWaterfall(0, 0, emptyList(), live = false)
    }
}

/**
 * The run's waterfall, derived only from its event journal ([events], in any order; ties keep
 * journal order). Each task attempt is a bar from its first event to its last:
 *
 * - [TaskStarted] begins an attempt (Running). Events for a task before it starts (assignment, an
 *   engine-held plan gate) open the attempt early in Planning; within an attempt, work before its
 *   first gate is relabelled Planning, because a plan gate is what follows planning.
 * - [ApprovalRequired] and task [HumanDecisionRequired] hold it WaitingOnYou until the decision.
 * - [ArtifactCreated] moves it to Verifying; [VerificationFailed] and [RetryScheduled] to Retrying.
 * - [TaskCompleted], [TaskFailed] and [TaskCancelled] end it. A retry or escalation recorded at the
 *   instant of a failure continues the failed attempt's bar until the next attempt starts; an
 *   attempt still retrying when the next one starts counts as failed.
 * - The run's own end closes every open bar; otherwise open bars run to [nowEpochMillis].
 * - [ProviderUsageRecorded] adds tokens and cost to the attempt it fell in.
 */
internal fun runWaterfall(events: List<WorkflowEvent>, nowEpochMillis: Long): RunWaterfall {
    if (events.isEmpty()) return RunWaterfall.Empty
    val sorted = events.sortedBy { it.occurredAtEpochMillis }
    val builders = mutableListOf<BarBuilder>()
    val open = mutableMapOf<TaskDefinitionId, BarBuilder>()
    val latest = mutableMapOf<TaskDefinitionId, BarBuilder>()
    val scheduledAttempt = mutableMapOf<TaskDefinitionId, Int>()
    var runEnd: Long? = null

    fun begin(task: TaskDefinitionId, attempt: Int, at: Long, phase: WaterfallPhase, started: Boolean): BarBuilder =
        BarBuilder(task, attempt, at, started).also {
            it.mark(phase, at)
            builders += it
            open[task] = it
            latest[task] = it
        }

    fun preStart(task: TaskDefinitionId, at: Long): BarBuilder =
        begin(task, scheduledAttempt.remove(task) ?: ((latest[task]?.attempt ?: 0) + 1), at, WaterfallPhase.Planning, started = false)

    /** The attempt an event belongs to; [continueFailure] reopens an attempt that failed at this instant. */
    fun active(task: TaskDefinitionId, at: Long, continueFailure: Boolean): BarBuilder {
        open[task]?.let { bar ->
            // A failed attempt's tail holds only what was recorded with its failure; anything later
            // belongs to the next attempt.
            if (bar.outcome != WaterfallOutcome.Failed || at <= bar.reopenedAt) return bar
            open.remove(task)
            bar.end = at
            return preStart(task, at)
        }
        val last = latest[task]
        if (continueFailure && last != null && last.outcome == WaterfallOutcome.Failed && last.end == at && runEnd == null) {
            last.end = null
            last.reopenedAt = at
            open[task] = last
            return last
        }
        return preStart(task, at)
    }

    fun close(task: TaskDefinitionId, at: Long, outcome: WaterfallOutcome?) {
        val bar = open.remove(task) ?: latest[task]?.takeIf { it.end == null } ?: preStart(task, at).also { open.remove(task) }
        bar.end = at
        if (outcome != null) bar.outcome = outcome
    }

    fun gate(task: TaskDefinitionId, at: Long) {
        val bar = active(task, at, continueFailure = true)
        if (!bar.gated && bar.started && bar.outcome == null) bar.relabelSinceStart(WaterfallPhase.Running, WaterfallPhase.Planning)
        bar.gated = true
        if (bar.phase != WaterfallPhase.WaitingOnYou) bar.beforeGate = bar.phase
        bar.mark(WaterfallPhase.WaitingOnYou, at)
    }

    for (event in sorted) {
        val at = event.occurredAtEpochMillis
        when (event) {
            is WorkflowCreated -> Unit
            is ExternalRunCancelFailed, is PolicyDecisionRecorded, is TaskRerunRequested -> Unit
            is TaskBecameReady -> if (open[event.taskDefinitionId] == null) preStart(event.taskDefinitionId, at)
            is AgentAssigned -> if (open[event.taskDefinitionId] == null) preStart(event.taskDefinitionId, at)
            is ExecutorAssigned -> if (open[event.taskDefinitionId] == null) preStart(event.taskDefinitionId, at)
            is TaskStarted -> {
                val task = event.taskDefinitionId
                val current = open[task]
                if (current != null && !current.started && current.outcome == null) {
                    current.attempt = event.attempt
                    current.started = true
                    current.mark(WaterfallPhase.Running, at)
                } else {
                    // An attempt still retrying when the next one starts did not succeed.
                    if (current != null) close(task, at, if (current.phase == WaterfallPhase.Retrying) WaterfallOutcome.Failed else null)
                    scheduledAttempt.remove(task)
                    begin(task, event.attempt, at, WaterfallPhase.Running, started = true)
                }
            }
            is ApprovalRequired -> gate(event.taskDefinitionId, at)
            is HumanDecisionRequired -> event.taskDefinitionId?.let { gate(it, at) }
            is ApprovalDecisionReceived -> event.taskDefinitionId?.let { task ->
                val bar = open[task] ?: return@let
                if (bar.phase == WaterfallPhase.WaitingOnYou) {
                    val resumed = when {
                        bar.outcome == WaterfallOutcome.Failed -> WaterfallPhase.Retrying
                        !event.approved -> WaterfallPhase.Planning
                        bar.started -> bar.beforeGate.takeUnless { it == WaterfallPhase.Planning } ?: WaterfallPhase.Running
                        else -> WaterfallPhase.Planning
                    }
                    bar.mark(resumed, at)
                }
            }
            is ArtifactCreated -> {
                val bar = active(event.taskDefinitionId, at, continueFailure = false)
                bar.mark(WaterfallPhase.Verifying, at)
            }
            is VerificationFailed -> active(event.taskDefinitionId, at, continueFailure = true).mark(WaterfallPhase.Retrying, at)
            is RetryScheduled -> {
                active(event.taskDefinitionId, at, continueFailure = true).mark(WaterfallPhase.Retrying, at)
                scheduledAttempt[event.taskDefinitionId] = event.nextAttempt
            }
            is TaskEscalated -> {
                val bar = active(event.taskDefinitionId, at, continueFailure = true)
                if (event.reassignedRoleId != null) bar.mark(WaterfallPhase.Retrying, at) else gate(event.taskDefinitionId, at)
            }
            is TaskCompleted -> close(event.taskDefinitionId, at, WaterfallOutcome.Completed)
            is TaskFailed -> close(event.taskDefinitionId, at, WaterfallOutcome.Failed)
            is TaskCancelled -> close(event.taskDefinitionId, at, WaterfallOutcome.Cancelled)
            is WorkflowCompleted, is WorkflowFailed, is WorkflowCancelled -> {
                val cancelled = event is WorkflowCancelled
                open.keys.toList().forEach { close(it, at, if (cancelled) WaterfallOutcome.Cancelled else null) }
                runEnd = at
            }
            is ProviderUsageRecorded -> (open[event.taskDefinitionId] ?: latest[event.taskDefinitionId])?.add(event)
        }
    }

    val lastEvent = sorted.last().occurredAtEpochMillis
    val live = runEnd == null
    val openEnd = runEnd ?: maxOf(nowEpochMillis, lastEvent)
    val bars = builders.map { it.build(it.end ?: openEnd, ongoing = it.end == null && live) }
    val start = sorted.first().occurredAtEpochMillis
    val end = maxOf(bars.maxOfOrNull { it.endMillis } ?: start, if (live) openEnd else lastEvent)
    val rows = bars.groupBy { it.taskId }
        .map { (task, attempts) -> WaterfallRow(task, attempts.sortedBy { it.startMillis }) }
        .sortedWith(compareBy({ it.bars.first().startMillis }, { it.taskId.value }))
    return RunWaterfall(start, end, rows, live)
}

private class BarBuilder(val taskId: TaskDefinitionId, var attempt: Int, val start: Long, var started: Boolean) {
    private val marks = mutableListOf<Pair<WaterfallPhase, Long>>()
    var end: Long? = null
    var outcome: WaterfallOutcome? = null
    var gated = false
    var reopenedAt = Long.MIN_VALUE
    var beforeGate: WaterfallPhase = WaterfallPhase.Running
    private var input = 0L
    private var output = 0L
    private var cost: Double? = null
    private var usageSeen = false

    val phase: WaterfallPhase get() = marks.last().first

    fun mark(phase: WaterfallPhase, at: Long) {
        val last = marks.lastOrNull()
        when {
            last == null -> marks += phase to at
            last.first == phase -> Unit
            // A change at the same instant replaces the stretch that never had time to happen.
            last.second >= at -> {
                marks[marks.lastIndex] = phase to last.second
                if (marks.size >= 2 && marks[marks.lastIndex - 1].first == phase) marks.removeAt(marks.lastIndex)
            }
            else -> marks += phase to at
        }
    }

    /** Relabels every stretch in [from] as [into] (work before a plan gate), unless anything else happened. */
    fun relabelSinceStart(from: WaterfallPhase, into: WaterfallPhase) {
        if (marks.any { it.first != from && it.first != WaterfallPhase.Planning }) return
        for (i in marks.indices) if (marks[i].first == from) marks[i] = Pair(into, marks[i].second)
        // Merge neighbours that are now the same phase.
        var i = 1
        while (i < marks.size) {
            if (marks[i].first == marks[i - 1].first) marks.removeAt(i) else i++
        }
    }

    fun add(usage: ProviderUsageRecorded) {
        usageSeen = true
        input += usage.inputTokens ?: 0
        output += usage.outputTokens ?: 0
        usage.costUsd?.let { cost = (cost ?: 0.0) + it }
    }

    fun build(endMillis: Long, ongoing: Boolean): WaterfallBar {
        val finish = maxOf(endMillis, start)
        val segments = marks.mapIndexedNotNull { index, (phase, from) ->
            val to = minOf(marks.getOrNull(index + 1)?.second ?: finish, finish)
            if (to > from) WaterfallSegment(phase, from, to) else null
        }.ifEmpty { listOf(WaterfallSegment(phase, start, finish)) }
        return WaterfallBar(
            taskId = taskId,
            attempt = attempt,
            startMillis = start,
            endMillis = finish,
            segments = segments,
            outcome = outcome,
            ongoing = ongoing,
            usage = if (usageSeen) WaterfallUsage(input, output, cost) else null,
        )
    }
}

/** "$0.04"; "<$0.01" for a reported cost under a cent. */
internal fun formatWaterfallCost(costUsd: Double): String {
    if (costUsd > 0.0 && costUsd < 0.005) return "<\$0.01"
    val cents = (costUsd * 100).roundToLong()
    return "\$" + (cents / 100) + "." + (cents % 100).toString().padStart(2, '0')
}

/** Whole seconds, as "3m 12s"; under a second reads "0s". */
internal fun formatWaterfallDuration(millis: Long): String = (millis.coerceAtLeast(0) / 1000).seconds.toString()
