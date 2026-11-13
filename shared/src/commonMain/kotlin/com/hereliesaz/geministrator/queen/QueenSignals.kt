package com.hereliesaz.geministrator.queen

import com.hereliesaz.geministrator.CreatureTab
import com.hereliesaz.geministrator.creatureNeedsYou
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.isTerminal
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.workflow.isHeldForRoleBudget
import com.hereliesaz.geministrator.workflow.roleTokensSpent

/** Something the queen can count in the run, with no model: the basis of her "I noticed". */
sealed interface QueenSignal {
    val taskId: String

    /** A step failed [failedAttempts] times and is not done. */
    data class RepeatedFailure(override val taskId: String, val failedAttempts: Int) : QueenSignal

    /** A step moved to another Source after its own failed, and is not done. */
    data class FailedOver(override val taskId: String) : QueenSignal

    /** A role used [percent] percent of its token budget and has steps left. */
    data class BudgetBurn(override val taskId: String, val roleName: String, val percent: Int) : QueenSignal
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
fun queenSignals(definition: WorkflowDefinition, run: WorkflowRun, roles: Collection<RoleDefinition>): List<QueenSignal> {
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
    return signals
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
        is QueenSignal.FailedOver -> QueenNote(
            queenNoteId(run.id.value, "watch", "failover", signal.taskId),
            QueenVoice.WatchFailedOver,
            listOf(step.toString(), name),
            QueenLink.Creature(signal.taskId, CreatureTab.Mind),
        )
    }
}
