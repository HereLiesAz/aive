package com.hereliesaz.geministrator.queen

import com.hereliesaz.geministrator.CreatureTab
import com.hereliesaz.geministrator.creatureNeedsYou
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.isTerminal
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.workflow.STEP_ALLOWANCE_EXCEEDED
import com.hereliesaz.geministrator.workflow.isHeldForRoleBudget
import com.hereliesaz.geministrator.workflow.roleTokensSpent
import com.hereliesaz.geministrator.workflow.stepTokenAllowance
import com.hereliesaz.geministrator.workflow.stepTokensUsed

/** Something the queen can count in the run, with no model: the basis of her "I noticed". */
sealed interface QueenSignal {
    val taskId: String

    /** A step failed [failedAttempts] times and is not done. */
    data class RepeatedFailure(override val taskId: String, val failedAttempts: Int) : QueenSignal

    /** A step moved to another Source after its own failed, and is not done. */
    data class FailedOver(override val taskId: String) : QueenSignal

    /** A role used [percent] percent of its token budget and has steps left. */
    data class BudgetBurn(override val taskId: String, val roleName: String, val percent: Int) : QueenSignal

    /** Nothing in the swarm has moved for [minutes], nothing waits on the keeper, and no creature is working. [taskId] is the first open step. */
    data class Stalled(override val taskId: String, val minutes: Int) : QueenSignal

    /** [steps] steps are blocked for the same reason [code]; [taskId] is the first of them. */
    data class RepeatedBlock(override val taskId: String, val code: String, val steps: Int) : QueenSignal
}

/** What the queen saw of a run's progress, and when she first saw it: kept in the ledger so a restart does not reset the clock. */
@kotlinx.serialization.Serializable
data class QueenSighting(val runId: String, val fingerprint: String, val firstSeenAtEpochMillis: Long)

/** Idle this long, with nothing to wait on, is a stall. Generous: a creature's own allowance has its own note. */
const val STALL_MILLIS: Long = 10L * 60_000L

/** The run's progress as a short id: each step's status and attempt. The same progress gives the same id. */
fun progressFingerprint(definition: WorkflowDefinition, run: WorkflowRun): String =
    queenNoteId(run.id.value, definition.tasks.joinToString(",") { t -> run.taskRuns[t.id]?.let { "${it.status.name}${it.attempt}" } ?: "-" })

/** [sightings] with this run's progress noted: a new first-seen time when the progress changed, else unchanged. Keeps one per run. */
fun observeProgress(sightings: List<QueenSighting>, definition: WorkflowDefinition, run: WorkflowRun, now: Long): List<QueenSighting> {
    val fingerprint = progressFingerprint(definition, run)
    val mine = sightings.firstOrNull { it.runId == run.id.value }
    if (mine != null && mine.fingerprint == fingerprint) return sightings
    return (sightings.filter { it.runId != run.id.value } + QueenSighting(run.id.value, fingerprint, now)).takeLast(64)
}

/**
 * Whole minutes the run has been unchanged, or null when it is not stalled: under way, not rested or frozen or stopped,
 * nothing waits on the keeper, no creature is working, and it has been the same since the queen first saw it for [STALL_MILLIS].
 */
fun stalledMinutes(definition: WorkflowDefinition, run: WorkflowRun, sightings: List<QueenSighting>, now: Long): Int? {
    if (run.status != com.hereliesaz.geministrator.domain.WorkflowRunStatus.Running) return null
    if (run.globalPause != null || run.spendPause != null || run.keeperPause != null) return null
    val runs = run.taskRuns.values
    if (runs.any(::creatureNeedsYou)) return null
    val working = setOf(TaskRunStatus.Running, TaskRunStatus.Planning, TaskRunStatus.Verifying)
    if (runs.any { it.status in working }) return null
    val seen = sightings.firstOrNull { it.runId == run.id.value && it.fingerprint == progressFingerprint(definition, run) } ?: return null
    val idle = now - seen.firstSeenAtEpochMillis
    return if (idle >= STALL_MILLIS) (idle / 60_000L).toInt() else null
}

private const val BUDGET_BURN_PERCENT = 80

/** Attempts that failed: those that were not a failover or a spend hold, less the one still under way. */
internal fun failedAttempts(taskRun: TaskRun): Int {
    val used = (taskRun.attempt - taskRun.failedProviders.size - taskRun.heldAttempts).coerceAtLeast(1)
    val current = when (taskRun.status) {
        TaskRunStatus.Escalated, TaskRunStatus.Failed -> 0
        else -> 1
    }
    return (used - current).coerceAtLeast(0)
}

/**
 * The signals in [run], in plan order. Pure: the same state gives the same signals. A step that
 * already waits on the keeper (it has its own row) raises none, and held attempts are not failures.
 */
fun queenSignals(
    definition: WorkflowDefinition,
    run: WorkflowRun,
    roles: Collection<RoleDefinition>,
    /** From [stalledMinutes], when the caller keeps sightings. */
    stalledForMinutes: Int? = null,
): List<QueenSignal> {
    val rolesById = roles.associateBy { it.id }
    val signals = mutableListOf<QueenSignal>()
    val burned = mutableSetOf<String>()
    for (task in definition.tasks) {
        val taskRun = run.taskRuns[task.id] ?: continue
        if (taskRun.status == TaskRunStatus.Completed || taskRun.status == TaskRunStatus.Cancelled) continue
        if (creatureNeedsYou(taskRun) || taskRun.isHeldForRoleBudget()) continue
        val failed = failedAttempts(taskRun)
        if (failed >= 2) signals += QueenSignal.RepeatedFailure(task.id.value, failed)
        if (taskRun.failedProviders.isNotEmpty()) signals += QueenSignal.FailedOver(task.id.value)
        val roleId = taskRun.assignedRoleId ?: task.roleId
        val role = roleId?.let { rolesById[it] }
        val budget = role?.tokenBudget
        if (role != null && budget != null && budget > 0 && burned.add(role.id.value)) {
            val percent = (run.roleTokensSpent(role.id) * 100 / budget).toInt()
            if (percent >= BUDGET_BURN_PERCENT) signals += QueenSignal.BudgetBurn(task.id.value, role.name, percent.coerceAtMost(999))
        }
    }
    // The same reason blocking two or more steps: one fix may free them all.
    val blocked = definition.tasks.mapNotNull { t ->
        val reason = run.taskRuns[t.id]?.takeIf { it.status == TaskRunStatus.Blocked }?.blockingReason ?: return@mapNotNull null
        t.id.value to reason.code
    }
    blocked.groupBy({ it.second }, { it.first }).entries.firstOrNull { it.key.isNotBlank() && it.value.size >= 2 }
        ?.let { signals += QueenSignal.RepeatedBlock(it.value.first(), it.key, it.value.size) }
    if (stalledForMinutes != null) {
        val open = definition.tasks.firstOrNull { run.taskRuns[it.id]?.status.let { s -> s != TaskRunStatus.Completed && s != TaskRunStatus.Cancelled } }
        signals += QueenSignal.Stalled(open?.id?.value.orEmpty(), stalledForMinutes)
    }
    return signals
}

/**
 * One note per step that was stopped because it used its whole token allowance (a step made from a template): what it used,
 * what it was allowed, and that it was not tried again. Said at every level, since it explains why a step did not finish.
 */
internal fun stepAllowanceNotes(definition: WorkflowDefinition, run: WorkflowRun): List<QueenNote> =
    definition.tasks.mapNotNull { task ->
        val taskRun = run.taskRuns[task.id] ?: return@mapNotNull null
        if (taskRun.blockingReason?.code != STEP_ALLOWANCE_EXCEEDED) return@mapNotNull null
        if (taskRun.status != TaskRunStatus.Failed && taskRun.status != TaskRunStatus.Escalated) return@mapNotNull null
        val allowance = stepTokenAllowance(task) ?: return@mapNotNull null
        QueenNote(
            queenNoteId(run.id.value, "watch", "stepallowance", task.id.value, taskRun.attempt.toString()),
            QueenVoice.StepAllowanceStopped,
            listOf((definition.tasks.indexOf(task) + 1).toString(), queenSlot(task.name), stepTokensUsed(taskRun).toString(), allowance.toString()),
            QueenLink.Creature(task.id.value, CreatureTab.Work),
        )
    }

/**
 * One note per creature that has passed the time the queen allowed and is still working (Helpful only): she is
 * still waiting, and the link opens the creature's Work tab, where the keeper can give it more time.
 */
internal fun slowNotes(definition: WorkflowDefinition, run: WorkflowRun): List<QueenNote> =
    definition.tasks.mapNotNull { task ->
        val taskRun = run.taskRuns[task.id] ?: return@mapNotNull null
        if (taskRun.timedOutAttempts > 0 && !taskRun.status.isTerminal() && taskRun.status != TaskRunStatus.Escalated) {
            // Stopped for taking too long, and going again (an Escalation has its own row and sentence).
            return@mapNotNull QueenNote(
                queenNoteId(run.id.value, "watch", "timedout", task.id.value, taskRun.timedOutAttempts.toString()),
                QueenVoice.WatchTimedOut,
                listOf((definition.tasks.indexOf(task) + 1).toString(), queenSlot(task.name), taskRun.timedOutAttempts.toString()),
                QueenLink.Creature(task.id.value, CreatureTab.Work),
            )
        }
        val allowed = com.hereliesaz.geministrator.workflow.slowCreatureAllowedMinutes(task, taskRun) ?: return@mapNotNull null
        val step = definition.tasks.indexOf(task) + 1
        QueenNote(
            queenNoteId(run.id.value, "watch", "slow", task.id.value, taskRun.attempt.toString(), allowed.toString()),
            QueenVoice.WatchSlow,
            listOf(step.toString(), queenSlot(task.name), allowed.toString()),
            QueenLink.Creature(task.id.value, CreatureTab.Work),
        )
    }

/**
 * The queen's own "I noticed": at most one note, the most serious signal first. Said only for a
 * swarm with no Nanny; with one, the Nanny's relay speaks and the queen does not claim her findings.
 */
internal fun watchRelay(definition: WorkflowDefinition, run: WorkflowRun, signals: List<QueenSignal>): QueenNote? {
    val signal = signals.firstOrNull { it is QueenSignal.RepeatedFailure }
        ?: signals.firstOrNull { it is QueenSignal.RepeatedBlock }
        ?: signals.firstOrNull { it is QueenSignal.Stalled }
        ?: signals.firstOrNull { it is QueenSignal.BudgetBurn }
        ?: signals.firstOrNull { it is QueenSignal.FailedOver }
        ?: return null
    val step = definition.tasks.indexOfFirst { it.id.value == signal.taskId } + 1
    val name = queenSlot(definition.tasks.getOrNull(step - 1)?.name.orEmpty())
    return when (signal) {
        is QueenSignal.RepeatedFailure -> QueenNote(
            queenNoteId(run.id.value, "watch", "failure", signal.taskId, signal.failedAttempts.toString()),
            QueenVoice.WatchRepeatedFailure,
            listOf(step.toString(), name, signal.failedAttempts.toString()),
            QueenLink.Creature(signal.taskId, CreatureTab.Work),
        )
        is QueenSignal.BudgetBurn -> QueenNote(
            queenNoteId(run.id.value, "watch", "budget", signal.roleName),
            QueenVoice.WatchBudget,
            listOf(queenSlot(signal.roleName), signal.percent.toString()),
            QueenLink.Creature(signal.taskId, CreatureTab.Mind),
        )
        is QueenSignal.RepeatedBlock -> QueenNote(
            queenNoteId(run.id.value, "watch", "block", signal.code, signal.steps.toString()),
            QueenVoice.WatchRepeatedBlock,
            listOf(signal.steps.toString(), queenSlot(signal.code), step.toString()),
            QueenLink.Creature(signal.taskId, CreatureTab.Needs),
        )
        is QueenSignal.Stalled -> QueenNote(
            queenNoteId(run.id.value, "watch", "stalled", signal.minutes.toString()),
            QueenVoice.WatchStalled,
            listOf(signal.minutes.toString()),
            QueenLink.Story,
        )
        is QueenSignal.FailedOver -> QueenNote(
            queenNoteId(run.id.value, "watch", "failover", signal.taskId),
            QueenVoice.WatchFailedOver,
            listOf(step.toString(), name),
            QueenLink.Creature(signal.taskId, CreatureTab.Mind),
        )
    }
}
