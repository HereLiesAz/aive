package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.creatureNeedsYou
import com.hereliesaz.geministrator.domain.TaskClock
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.TimeLimit
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun

/**
 * Whether a creature past its hard point is stopped. A patch release can switch it off without a data change;
 * while off, the hard point only marks the soft notice.
 */
const val HARD_DEADLINES_ENABLED: Boolean = true

sealed interface DeadlineVerdict {
    data object Ok : DeadlineVerdict
    data object Soft : DeadlineVerdict
    data class Hard(val why: Why) : DeadlineVerdict
}

enum class Why { Ceiling, Silent }

/**
 * The minutes the creature was allowed when it has passed that and is still working, else null. Pure over the
 * persisted clock (no `now`), so the status line needs no ticking composable and a keeper's edit shows at once.
 */
fun slowCreatureAllowedMinutes(task: TaskDefinition, taskRun: TaskRun): Int? {
    val clock = taskRun.clock?.takeIf { it.attempt == taskRun.attempt } ?: return null
    if (clock.softAtEpochMillis == null || creatureNeedsYou(taskRun)) return null
    if (taskRun.status != TaskRunStatus.Planning && taskRun.status != TaskRunStatus.Running &&
        taskRun.status != TaskRunStatus.Verifying
    ) {
        return null
    }
    val allowance = DeadlinePolicy.effective(task.timeLimit, ActiveClock.auto(clock))
    return if (allowance.unlimited) null else (allowance.millis / 60_000L).toInt().coerceAtLeast(1)
}

/** The run with every clock stepped, the soft marks set, and the hard-stopped creatures marked Failed. */
data class Reduction(
    val run: WorkflowRun,
    val hardStops: Map<TaskDefinitionId, Why> = emptyMap(),
    val softened: Set<TaskDefinitionId> = emptySet(),
)

/**
 * Decides, once per cycle, what a creature's clock means (docs/Keepers-Room.md). Pure and given `now`.
 * Soft at the allowance, once: a note, nothing stopped. Hard at the ceiling, or past the allowance and silent.
 */
object DeadlineReducer {
    /** Silence counts only past the allowance: a tenth of it, between two and ten minutes. */
    fun silenceWindowMillis(allowanceMillis: Long): Long =
        (allowanceMillis / 10).coerceIn(2 * 60_000L, 10 * 60_000L)

    /** The text a stopped creature carries; "timed out" is what the failover classifier reads. */
    fun timedOutMessage(activeMillis: Long, allowanceMillis: Long): String =
        "Timed out after ${minutes(activeMillis)} of work (allowed ${minutes(allowanceMillis)})"

    private fun minutes(millis: Long): String {
        val m = (millis / 60_000L).coerceAtLeast(1)
        return if (m == 1L) "1 minute" else "$m minutes"
    }

    /** [progressAt] is null for a creature whose Source shows no sign of life: it is judged by the ceiling alone. */
    fun verdict(
        clock: TaskClock,
        limit: TimeLimit,
        now: Long,
        counting: Boolean,
        progressAt: Long?,
    ): DeadlineVerdict {
        val allowance = DeadlinePolicy.effective(limit, ActiveClock.auto(clock))
        if (allowance.unlimited || !counting) return DeadlineVerdict.Ok
        val active = ActiveClock.active(clock, now)
        if (active >= allowance.hardMillis) return DeadlineVerdict.Hard(Why.Ceiling)
        if (active < allowance.millis) return DeadlineVerdict.Ok
        if (progressAt != null &&
            now - maxOf(progressAt, clock.progressAtEpochMillis) >= silenceWindowMillis(allowance.millis)
        ) {
            return DeadlineVerdict.Hard(Why.Silent)
        }
        return if (clock.softAtEpochMillis == null) DeadlineVerdict.Soft else DeadlineVerdict.Ok
    }

    /** Whether the creature is working now: not waiting on the keeper, and the run not stopped for the keeper. */
    private fun counting(run: WorkflowRun, taskRun: TaskRun): Boolean =
        !creatureNeedsYou(taskRun) && run.spendPause == null && run.globalPause == null

    private val working = setOf(TaskRunStatus.Planning, TaskRunStatus.Running, TaskRunStatus.Verifying)

    /**
     * [autoFor] gives the queen's own figure for a creature that has no clock for this attempt (a run from before
     * clocks existed starts one now with a full allowance; an executor started by the coordinator does too).
     */
    fun reduce(
        definition: WorkflowDefinition,
        run: WorkflowRun,
        now: Long,
        progressAt: (TaskDefinitionId) -> Long?,
        autoFor: (TaskDefinition, TaskRun) -> Allowance,
        enforceHard: Boolean = true,
    ): Reduction {
        var changed = false
        val stops = LinkedHashMap<TaskDefinitionId, Why>()
        val soft = LinkedHashSet<TaskDefinitionId>()
        val next = run.taskRuns.mapValues { (id, taskRun) ->
            if (taskRun.status !in working) return@mapValues taskRun
            val task = definition.tasks.firstOrNull { it.id == id } ?: return@mapValues taskRun
            val old = taskRun.clock?.takeIf { it.attempt == taskRun.attempt }
                ?: ActiveClock.begin(taskRun.attempt, now, autoFor(task, taskRun))
            val counting = counting(run, taskRun)
            val progress = progressAt(id)
            var clock = ActiveClock.step(old, now, counting, progress)
            var verdict = verdict(clock, task.timeLimit, now, counting, progress)
            if (verdict is DeadlineVerdict.Hard && !enforceHard) {
                verdict = if (clock.softAtEpochMillis == null) DeadlineVerdict.Soft else DeadlineVerdict.Ok
            }
            var out = taskRun
            when (verdict) {
                DeadlineVerdict.Ok -> Unit
                DeadlineVerdict.Soft -> {
                    clock = clock.copy(softAtEpochMillis = now)
                    soft += id
                }
                is DeadlineVerdict.Hard -> {
                    val allowed = DeadlinePolicy.effective(task.timeLimit, ActiveClock.auto(clock)).millis
                    val active = ActiveClock.active(clock, now)
                    clock = ActiveClock.finish(clock, now)
                    stops[id] = verdict.why
                    out = out.copy(
                        status = TaskRunStatus.Failed,
                        progressMessage = timedOutMessage(active, allowed),
                        timedOutAttempts = out.timedOutAttempts + 1,
                    )
                }
            }
            if (clock != taskRun.clock || out !== taskRun) changed = true
            out.copy(clock = clock)
        }
        return if (changed) Reduction(run.copy(taskRuns = next), stops, soft) else Reduction(run, stops, soft)
    }
}
