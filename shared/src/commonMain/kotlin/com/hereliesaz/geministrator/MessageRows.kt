package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.agentui.awaitsKeeperAnswer
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.displayName
import com.hereliesaz.geministrator.domain.effectiveExecutor
import com.hereliesaz.geministrator.workflow.isHeldForRoleBudget
import com.hereliesaz.geministrator.workflow.isTerminal

/** What one Messages entry waits on the keeper for; [inboxEntries] decides it as the badge's key does. */
internal enum class InboxEntryKind { PlanApproval, TaskApproval, NestedApproval, Escalation, Question, BudgetSpent }

/** One creature waiting on the keeper, as Messages lists it. Only what the run really holds. */
internal data class InboxEntry(
    /** Its read-log key ([inboxItemKeys]); also what the list remembers as selected. */
    val key: String,
    val taskId: String,
    val kind: InboxEntryKind,
    val title: String,
    /** The role it works as, else what does its work. */
    val role: String,
    val attempt: Int,
    val maxAttempts: Int,
)

/** Everything waiting on the keeper in [presentation], in plan order (the badge's own order). */
internal fun inboxEntries(presentation: LiveWorkflowPresentation): List<InboxEntry> {
    val run = presentation.run
    val roles = presentation.roles.associateBy { it.id }
    return presentation.definition.tasks.mapNotNull { task ->
        val taskRun = run.taskRuns[task.id]?.takeIf(::creatureNeedsYou) ?: return@mapNotNull null
        val key = inboxItemKeys(run, task.id.value).firstOrNull() ?: return@mapNotNull null
        val kind = when {
            taskRun.awaitsKeeperAnswer() && taskRun.keeperQuestion != null -> InboxEntryKind.Question
            taskRun.status == TaskRunStatus.AwaitingApproval ->
                if (taskRun.assignedProviderId != null) InboxEntryKind.PlanApproval else InboxEntryKind.TaskApproval
            taskRun.status == TaskRunStatus.Escalated -> InboxEntryKind.Escalation
            taskRun.isHeldForRoleBudget() -> InboxEntryKind.BudgetSpent
            else -> InboxEntryKind.NestedApproval
        }
        val roleId = taskRun.assignedRoleId ?: task.roleId
        InboxEntry(
            key = key,
            taskId = task.id.value,
            kind = kind,
            title = task.name,
            role = roleId?.let { roles[it]?.name } ?: (taskRun.executor ?: task.effectiveExecutor()).displayName(),
            attempt = taskRun.attempt,
            maxAttempts = task.retryPolicy.maxAttempts,
        )
    }
}

/**
 * Where a wait that has no button of its own is resolved: the creature's Needs for a question its agent asked,
 * its Mind for a spent budget. Null for the waits decided in Messages itself.
 */
internal fun inboxEntryLinkTab(kind: InboxEntryKind): CreatureTab? = when (kind) {
    InboxEntryKind.Question -> CreatureTab.Needs
    InboxEntryKind.BudgetSpent -> CreatureTab.Mind
    else -> null
}

/** The entry to show: the one remembered as selected while it still waits, else the first. */
internal fun selectedInboxEntry(entries: List<InboxEntry>, selectedKey: String?): InboxEntry? =
    entries.firstOrNull { it.key == selectedKey } ?: entries.firstOrNull()

/**
 * One row of Messages. There is one list for every width and every state of the run: the waits on the
 * keeper, the Nanny's freeze, a spend cap stop and a practice run are all rows of it, so there is one
 * page to draw and one place that decides what is listed.
 */
internal sealed interface MessageRow {
    /** Its read-log key; also what the list remembers as selected. */
    val key: String

    /** Whether it is something the keeper must decide, and so counts toward the Messages badge. */
    val countsToBadge: Boolean

    /** A creature waiting on the keeper. */
    data class Wait(val entry: InboxEntry) : MessageRow {
        override val key: String get() = entry.key
        override val countsToBadge: Boolean get() = true
    }

    /** The Nanny's freeze of the whole swarm, awaiting the keeper's review. */
    data class Freeze(override val key: String) : MessageRow {
        override val countsToBadge: Boolean get() = true
    }

    /** A spend cap has stopped the swarm; the keeper lifts the stop. */
    data class AllowanceStop(override val key: String) : MessageRow {
        override val countsToBadge: Boolean get() = true
    }

    /** The queen speaking on her own: derived from the run. Only a suggestion waiting on the keeper counts toward the badge. */
    data class Queen(val note: com.hereliesaz.geministrator.queen.QueenNote) : MessageRow {
        override val key: String get() = "queen|" + note.id
        override val countsToBadge: Boolean get() = note.countsToBadge
    }

    /** The run shown is an isolated practice run of one of the Nanny's solutions: progress only, no decision. */
    data class PracticeRun(override val key: String) : MessageRow {
        override val countsToBadge: Boolean get() = false
    }
}

/**
 * What Messages lists for [presentation], in order. A freeze or a spend stop speaks for the whole
 * swarm and is listed alone (as the badge's keys are: [inboxItemKeys]); otherwise the practice run
 * comes first, then each creature that waits, in plan order.
 */
internal fun messageRows(presentation: LiveWorkflowPresentation): List<MessageRow> {
    val run = presentation.run
    val stop = run.spendPause
    if (stop != null && !run.status.isTerminal()) return listOf(MessageRow.AllowanceStop(spendStopInboxKey(run, stop)))
    val pause = run.globalPause
    if (pause != null) return listOf(MessageRow.Freeze(pauseInboxKey(run, pause)))
    val waits = inboxEntries(presentation).map { MessageRow.Wait(it) }
    val queen = presentation.queen.filter { it.attachedTo == null }.map { MessageRow.Queen(it) }
    return if (isHallMonitorTrial(presentation)) listOf(MessageRow.PracticeRun("practice|${run.id.value}")) + waits + queen else waits + queen
}

/** The row to show: the one remembered as selected while it is listed, else the first. */
internal fun selectedMessageRow(rows: List<MessageRow>, selectedKey: String?): MessageRow? =
    rows.firstOrNull { it.key == selectedKey } ?: rows.firstOrNull()

/** What a Wait row's buttons do. */
internal enum class MessageAction { ApprovePlan, RejectPlan, ApproveTask, Approve, Retry, Stop, Answer, RaiseBudget }

/** The real actions for what a creature waits on: the same calls as the creature's Needs. */
internal fun messageActions(kind: InboxEntryKind): List<MessageAction> = when (kind) {
    InboxEntryKind.PlanApproval -> listOf(MessageAction.ApprovePlan, MessageAction.RejectPlan)
    InboxEntryKind.TaskApproval -> listOf(MessageAction.ApproveTask)
    InboxEntryKind.NestedApproval -> listOf(MessageAction.Approve)
    InboxEntryKind.Escalation -> listOf(MessageAction.Retry, MessageAction.Stop)
    // Answered or raised on the creature itself: the button takes the keeper there.
    InboxEntryKind.Question -> listOf(MessageAction.Answer)
    InboxEntryKind.BudgetSpent -> listOf(MessageAction.RaiseBudget)
}
