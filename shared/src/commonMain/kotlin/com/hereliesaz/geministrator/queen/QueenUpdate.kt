package com.hereliesaz.geministrator.queen

import com.hereliesaz.geministrator.CreatureTab
import com.hereliesaz.geministrator.HallMonitorPauseReviewState
import com.hereliesaz.geministrator.InboxEntryKind
import com.hereliesaz.geministrator.creatureNeedsYou
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.SpendCapKind
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.inboxEntries
import com.hereliesaz.geministrator.inboxItemKeys
import com.hereliesaz.geministrator.spendStopInboxKey
import com.hereliesaz.geministrator.workflow.formatUsd
import com.hereliesaz.geministrator.workflow.hasUnpricedUsage
import com.hereliesaz.geministrator.workflow.isHeldForRoleBudget
import com.hereliesaz.geministrator.workflow.knownCostUsd
import com.hereliesaz.geministrator.workflow.roleTokensSpent

private fun WorkflowRunStatus.finished() =
    this == WorkflowRunStatus.Completed || this == WorkflowRunStatus.Failed || this == WorkflowRunStatus.Cancelled

private fun step(definition: WorkflowDefinition, taskId: String) = definition.tasks.indexOfFirst { it.id.value == taskId } + 1

/**
 * Everything the queen says about [run] at [level]: pure, from what the run holds, so the same state
 * gives the same notes (it is derived on every publish and stored nowhere). Order: sentences on
 * existing rows, then the Nanny's relay, the queen's own "I noticed", the digest of waits, and the
 * one living status line last. A finished run gets its one finish note.
 */
fun queenUpdate(
    definition: WorkflowDefinition,
    run: WorkflowRun,
    roles: Collection<RoleDefinition>,
    level: QueenLevel,
    review: HallMonitorPauseReviewState? = null,
    /** What she consulted before planning this run, if anything. */
    research: com.hereliesaz.geministrator.research.ResearchRecord? = null,
    /** What she found missing when this run was launched (see [missingForPlanning]); said once, with where to fix it. */
    missing: List<Missing> = emptyList(),
    /** What she says about disputes she could not settle herself (see [disputeNotes]); always said. */
    disputes: List<QueenNote> = emptyList(),
    /** Her suggestions still waiting on the keeper ([queenSuggestionNotes]); never while frozen, stopped or finished. */
    suggestions: List<QueenNote> = emptyList(),
    /** How long the swarm has stood still, from [stalledMinutes]; null when it has not. */
    stalledForMinutes: Int? = null,
): List<QueenNote> {
    if (run.status.finished()) return listOf(finishNote(definition, run))
    val lead = missingNotes(run.id.value, missing, level) + disputes
    val stop = run.spendPause
    if (stop != null) return lead + allowanceNotes(run)
    if (run.globalPause != null) return lead + nannyRelay(definition, run, review)
    val notes = mutableListOf<QueenNote>()
    notes += lead
    researchNote(run, research, level)?.let { notes += it }
    notes += waitSentences(definition, run, roles)
    notes += nannyRelay(definition, run, review)
    notes += suggestions
    // A suggestion already says what she noticed, so her "I noticed" line does not say it twice.
    if (suggestions.isEmpty() && nannyPhase(definition, run) == NannyPhase.None) {
        watchRelay(definition, run, queenSignals(definition, run, roles, stalledForMinutes))?.let { notes += it }
    }
    if (level == QueenLevel.Helpful) {
        notes += slowNotes(definition, run)
        waitsDigest(definition, run)?.let { notes += it }
        notes += statusNote(definition, run, roles)
    }
    return notes
}

/** The finish note: all done (with its cost only when every cost is known), could not finish, or stopped. */
internal fun finishNote(definition: WorkflowDefinition, run: WorkflowRun): QueenNote {
    val id = run.id.value
    val done = run.taskRuns.values.count { it.status == TaskRunStatus.Completed }
    return when (run.status) {
        WorkflowRunStatus.Completed -> {
            val cost = run.knownCostUsd()
            if (cost > 0.0 && !run.hasUnpricedUsage()) {
                QueenNote(queenNoteId(id, "finish", "cost"), QueenVoice.FinishedAllCost, listOf(formatUsd(cost)), QueenLink.Story)
            } else {
                QueenNote(queenNoteId(id, "finish", "all"), QueenVoice.FinishedAll, link = QueenLink.Story)
            }
        }
        WorkflowRunStatus.Failed -> {
            val failed = definition.tasks.firstOrNull {
                run.taskRuns[it.id]?.status.let { s -> s == TaskRunStatus.Failed || s == TaskRunStatus.Escalated }
            }
            if (failed == null) {
                QueenNote(queenNoteId(id, "finish", "failed"), QueenVoice.FinishedFailedNoStep, link = QueenLink.Story)
            } else {
                QueenNote(
                    queenNoteId(id, "finish", "failed", failed.id.value),
                    QueenVoice.FinishedFailed,
                    listOf(step(definition, failed.id.value).toString(), queenSlot(failed.name), (run.taskRuns[failed.id]?.attempt ?: 1).toString()),
                    QueenLink.Creature(failed.id.value, CreatureTab.Needs),
                )
            }
        }
        else -> QueenNote(
            queenNoteId(id, "finish", "stopped"),
            QueenVoice.FinishedStopped,
            listOf(done.toString(), definition.tasks.size.toString()),
            QueenLink.Story,
        )
    }
}

/** The living status line (Helpful): how far the swarm has got, who is working, and whether anything waits. */
internal fun statusNote(definition: WorkflowDefinition, run: WorkflowRun, roles: Collection<RoleDefinition>): QueenNote {
    val done = run.taskRuns.values.count { it.status == TaskRunStatus.Completed }
    val total = definition.tasks.size
    val clear = run.taskRuns.values.none(::creatureNeedsYou)
    val working = definition.tasks.firstOrNull {
        run.taskRuns[it.id]?.status.let { s -> s == TaskRunStatus.Running || s == TaskRunStatus.Planning || s == TaskRunStatus.Verifying }
    }
    val extra = if (nannyPhase(definition, run) == NannyPhase.Checking) QueenVoice.NannyChecking else null
    // Neither the names of steps nor of roles are the keeper's own words: slots are bounded and stripped.
    val (voice, args) = if (working != null) {
        (if (clear) QueenVoice.StatusWorkingClear else QueenVoice.StatusWorking) to
            listOf(done.toString(), total.toString(), step(definition, working.id.value).toString(), queenSlot(working.name))
    } else {
        (if (clear) QueenVoice.StatusIdleClear else QueenVoice.StatusIdle) to listOf(done.toString(), total.toString())
    }
    return QueenNote(queenNoteId(run.id.value, "status", voice.name, args.joinToString(","), extra?.name.orEmpty()), voice, args, extra = extra)
}

/**
 * What waits on the keeper, as one line: the count is the badge's own count ([inboxItemKeys]), and
 * it leads to the first wait. Null when nothing waits.
 */
internal fun waitsDigest(definition: WorkflowDefinition, run: WorkflowRun): QueenNote? {
    val count = inboxItemKeys(run).size
    if (count == 0) return null
    val first = definition.tasks.firstOrNull { run.taskRuns[it.id]?.let(::creatureNeedsYou) == true } ?: return null
    val key = inboxItemKeys(run, first.id.value).firstOrNull() ?: return null
    val kind = inboxEntries(com.hereliesaz.geministrator.LiveWorkflowPresentation(
        project = com.hereliesaz.geministrator.domain.Project(run.projectId, "", createdAtEpochMillis = 0, updatedAtEpochMillis = 0),
        definition = definition, run = run, roles = emptyList(),
    )).firstOrNull { it.taskId == first.id.value }?.kind ?: InboxEntryKind.TaskApproval
    return QueenNote(
        queenNoteId(run.id.value, "waits", count.toString(), key),
        if (count == 1) QueenVoice.WaitsOne else QueenVoice.WaitsMany,
        listOf(count.toString(), kind.name, step(definition, first.id.value).toString()),
        QueenLink.Message(key),
    )
}

/** The queen's sentence on a wait that is a failure or a spent budget: what the keeper's button will do. */
internal fun waitSentences(definition: WorkflowDefinition, run: WorkflowRun, roles: Collection<RoleDefinition>): List<QueenNote> {
    val rolesById = roles.associateBy { it.id }
    return definition.tasks.mapNotNull { task ->
        val taskRun = run.taskRuns[task.id] ?: return@mapNotNull null
        val key = inboxItemKeys(run, task.id.value).firstOrNull() ?: return@mapNotNull null
        when {
            taskRun.status == TaskRunStatus.Escalated -> QueenNote(
                queenNoteId(run.id.value, "wait", "escalation", task.id.value, taskRun.attempt.toString()),
                QueenVoice.WaitEscalation,
                listOf(step(definition, task.id.value).toString(), queenSlot(task.name), taskRun.attempt.toString()),
                attachedTo = key,
            )
            taskRun.isHeldForRoleBudget() -> {
                val roleId = taskRun.assignedRoleId ?: task.roleId
                QueenNote(
                    queenNoteId(run.id.value, "wait", "budget", task.id.value, taskRun.attempt.toString()),
                    QueenVoice.WaitBudget,
                    listOf(queenSlot(roleId?.let { rolesById[it]?.name } ?: task.name), (roleId?.let { run.roleTokensSpent(it) } ?: 0L).toString()),
                    attachedTo = key,
                )
            }
            else -> null
        }
    }
}

/** The queen's sentence on the spend stop row. */
internal fun allowanceNotes(run: WorkflowRun): List<QueenNote> {
    val stop = run.spendPause ?: return emptyList()
    val key = spendStopInboxKey(run, stop)
    val voice = when {
        stop.unknownCost -> QueenVoice.AllowanceStopUnpriced
        stop.kind == SpendCapKind.HourlySpend -> QueenVoice.AllowanceStopHour
        else -> QueenVoice.AllowanceStopRun
    }
    val args = if (voice == QueenVoice.AllowanceStopUnpriced) listOf(formatUsd(stop.capUsd)) else listOf(formatUsd(stop.capUsd), formatUsd(stop.spentUsd))
    return listOf(
        QueenNote(
            queenNoteId(run.id.value, "allowance", stop.kind.name, stop.pausedAtEpochMillis.toString()),
            voice,
            args,
            link = QueenLink.Limits,
            attachedTo = key,
        ),
    )
}

/**
 * One plain line on what she consulted before planning. Quiet shows it only when something relevant was skipped;
 * Helpful always shows it. It carries counts only, never text from a lookup.
 */
internal fun researchNote(run: WorkflowRun, research: com.hereliesaz.geministrator.research.ResearchRecord?, level: QueenLevel): QueenNote? {
    if (research == null || research.isEmpty) return null
    val skipped = research.skipped.count { it.reason != com.hereliesaz.geministrator.research.ResearchSkipReason.NoQuery }
    if (skipped == 0 && level != QueenLevel.Helpful) return null
    val looked = research.consulted.size.toString()
    return if (skipped > 0) {
        QueenNote(queenNoteId(run.id.value, "research", "skipped"), QueenVoice.ResearchSkipped, listOf(looked, skipped.toString()), QueenLink.Story)
    } else {
        QueenNote(queenNoteId(run.id.value, "research", "consulted"), QueenVoice.ResearchConsulted, listOf(looked, research.leads.toString()), QueenLink.Story)
    }
}
