package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.domain.HallMonitorAction
import com.hereliesaz.geministrator.domain.HallMonitorScope
import com.hereliesaz.geministrator.queen.QueenLink
import com.hereliesaz.geministrator.queen.QueenNote
import com.hereliesaz.geministrator.queen.QueenQuoteLabel
import com.hereliesaz.geministrator.queen.QueenRelay
import com.hereliesaz.geministrator.queen.QueenVoice
import com.hereliesaz.geministrator.resources.*
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** The string for one of the queen's voices. Exhaustive: a new voice does not compile until it has words. */
private fun voiceResource(voice: QueenVoice): StringResource = when (voice) {
        QueenVoice.StatusWorking -> Res.string.queen_say_status_working
        QueenVoice.StatusWorkingClear -> Res.string.queen_say_status_working_clear
        QueenVoice.StatusIdle -> Res.string.queen_say_status_idle
        QueenVoice.StatusIdleClear -> Res.string.queen_say_status_idle_clear
        QueenVoice.NannyChecking -> Res.string.queen_say_nanny_checking
        QueenVoice.FinishedAll -> Res.string.queen_say_finished_all
        QueenVoice.FinishedAllCost -> Res.string.queen_say_finished_all_cost
        QueenVoice.FinishedFailed -> Res.string.queen_say_finished_failed
        QueenVoice.FinishedFailedNoStep -> Res.string.queen_say_finished_failed_no_step
        QueenVoice.FinishedStopped -> Res.string.queen_say_finished_stopped
        QueenVoice.WaitsOne -> Res.string.queen_say_waits_one
        QueenVoice.WaitsMany -> Res.string.queen_say_waits_many
        QueenVoice.WaitEscalation -> Res.string.queen_say_wait_escalation
        QueenVoice.WaitBudget -> Res.string.queen_say_wait_budget
        QueenVoice.WatchRepeatedFailure -> Res.string.queen_say_watch_repeated_failure
        QueenVoice.WatchBudget -> Res.string.queen_say_watch_budget
        QueenVoice.WatchFailedOver -> Res.string.queen_say_watch_failed_over
        QueenVoice.WatchSlow -> Res.string.queen_say_watch_slow
        QueenVoice.WatchTimedOut -> Res.string.queen_say_watch_timed_out
        QueenVoice.AllowanceStopRun -> Res.string.queen_say_allowance_stop_run
        QueenVoice.AllowanceStopHour -> Res.string.queen_say_allowance_stop_hour
        QueenVoice.AllowanceStopUnpriced -> Res.string.queen_say_allowance_stop_unpriced
        QueenVoice.NannyReport -> Res.string.queen_say_nanny_report
        QueenVoice.NannyRevise -> Res.string.queen_say_nanny_revise
        QueenVoice.NannyRejected -> Res.string.queen_say_nanny_rejected
        QueenVoice.NannyFroze -> Res.string.queen_say_nanny_froze
        QueenVoice.NannyMoreFindings -> Res.string.queen_say_nanny_more_findings
        QueenVoice.QueenReading -> Res.string.queen_say_queen_reading
        QueenVoice.QueenApproves -> Res.string.queen_say_queen_approves
        QueenVoice.QueenRejects -> Res.string.queen_say_queen_rejects
        QueenVoice.QueenCouldNotRead -> Res.string.queen_say_queen_could_not_read
        QueenVoice.NannyYouDecide -> Res.string.queen_say_nanny_you_decide
        QueenVoice.PracticeRunning -> Res.string.queen_say_practice_running
        QueenVoice.PracticeDone -> Res.string.queen_say_practice_done
        QueenVoice.PracticeEnded -> Res.string.queen_say_practice_ended
        QueenVoice.ResearchConsulted -> Res.string.queen_say_research_consulted
        QueenVoice.ResearchSkipped -> Res.string.queen_say_research_skipped
}

@Composable
private fun formatVoice(res: StringResource, args: List<String>): String = when (args.size) {
    0 -> stringResource(res)
    1 -> stringResource(res, args[0])
    2 -> stringResource(res, args[0], args[1])
    3 -> stringResource(res, args[0], args[1], args[2])
    else -> stringResource(res, args[0], args[1], args[2], args[3])
}

/** The queen's sentence for [note] (with its second sentence, when it has one). Slots only; text from the run is never in it. */
@Composable
internal fun queenSentence(note: QueenNote): String {
    val args = if (note.voice == QueenVoice.WaitsOne || note.voice == QueenVoice.WaitsMany) {
        // The wait's kind becomes a phrase in her words: [count, kind, step] -> [count, phrase].
        val phrase = stringResource(
            when (InboxEntryKind.entries.firstOrNull { it.name == note.args.getOrNull(1) } ?: InboxEntryKind.TaskApproval) {
                InboxEntryKind.PlanApproval -> Res.string.queen_phrase_plan_approval
                InboxEntryKind.TaskApproval -> Res.string.queen_phrase_task_approval
                InboxEntryKind.NestedApproval -> Res.string.queen_phrase_nested_approval
                InboxEntryKind.Escalation -> Res.string.queen_phrase_escalation
                InboxEntryKind.Question -> Res.string.queen_phrase_question
                InboxEntryKind.BudgetSpent -> Res.string.queen_phrase_budget_spent
            },
            note.args.getOrElse(2) { "" },
        )
        if (note.voice == QueenVoice.WaitsOne) listOf(phrase) else listOf(note.args[0], phrase)
    } else {
        note.args
    }
    val first = formatVoice(voiceResource(note.voice), args)
    return note.extra?.let { first + " " + stringResource(voiceResource(it)) } ?: first
}

/** The heading of a row of the queen's own. */
@Composable
internal fun queenTitle(note: QueenNote): String = stringResource(
    when (note.voice) {
        QueenVoice.StatusWorking, QueenVoice.StatusWorkingClear, QueenVoice.StatusIdle, QueenVoice.StatusIdleClear -> Res.string.queen_title_status
        QueenVoice.FinishedAll, QueenVoice.FinishedAllCost, QueenVoice.FinishedFailed,
        QueenVoice.FinishedFailedNoStep, QueenVoice.FinishedStopped -> Res.string.queen_title_finish
        else -> when (note.relay) {
            QueenRelay.Waits -> Res.string.queen_title_waits
            QueenRelay.Watch -> Res.string.queen_title_watch
            QueenRelay.Allowance -> Res.string.queen_title_allowance
            else -> Res.string.queen_title_nanny
        }
    },
)

@Composable
private fun linkLabel(link: QueenLink): String? = when (link) {
    is QueenLink.Creature -> stringResource(Res.string.queen_link_creature)
    QueenLink.Limits -> stringResource(Res.string.spend_stop_change_caps)
    QueenLink.Story -> stringResource(Res.string.queen_link_story)
    is QueenLink.Message -> stringResource(Res.string.queen_link_message)
    is QueenLink.Run -> stringResource(Res.string.queen_link_run)
}

/**
 * The queen's words, in her voice: each note a sentence, with text from the run (a finding, a reason,
 * a solution's title) on its own labeled, quoted line, and the existing place it leads to as a button.
 */
@Composable
internal fun QueenSays(
    notes: List<QueenNote>,
    actions: MessageActions,
    showLink: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (notes.isEmpty()) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        notes.forEach { note ->
            Text(queenSentence(note), style = AzphaltType.body, color = azphaltOnSurface(), modifier = Modifier.widthIn(max = 530.dp))
            note.quotes.forEach { quote ->
                Text(
                    stringResource(
                        when (quote.label) {
                            QueenQuoteLabel.NannyFinding -> Res.string.queen_quote_nanny_finding
                            QueenQuoteLabel.QueenReason -> Res.string.queen_quote_queen_reason
                            QueenQuoteLabel.PracticeSolution -> Res.string.queen_quote_practice_solution
                        },
                    ),
                    style = AzphaltType.eyebrow,
                    color = azphaltMutedOnSurface(),
                )
                Text("“${quote.text}”", style = AzphaltType.body, color = azphaltMutedOnSurface(), modifier = Modifier.widthIn(max = 530.dp))
            }
            val link = note.link
            val label = link?.let { linkLabel(it) }
            val go: (() -> Unit)? = when (link) {
                is QueenLink.Creature -> ({ actions.openCreature(link.taskId, link.tab) })
                QueenLink.Limits -> actions.openLimits
                QueenLink.Story -> actions.openStory
                is QueenLink.Message -> ({ onSelect(link.key) })
                is QueenLink.Run -> ({ actions.openRun(link.runId) })
                null -> null
            }
            if (showLink && label != null && go != null) {
                AzphaltPill(label, "queen-link-${note.id}", onClick = go)
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** The keeper's plain words for what a Nanny finding is about, never the raw enum name. */
@Composable
internal fun plainScope(scope: HallMonitorScope): String = stringResource(
    when (scope) {
        HallMonitorScope.Model -> Res.string.queen_scope_model
        HallMonitorScope.Role -> Res.string.queen_scope_role
        HallMonitorScope.Workflow -> Res.string.queen_scope_workflow
        HallMonitorScope.Swarm -> Res.string.queen_scope_swarm
        HallMonitorScope.OrchestrationLayer -> Res.string.queen_scope_orchestrationlayer
        HallMonitorScope.MemoryLayer -> Res.string.queen_scope_memorylayer
    },
)

/** The keeper's plain words for what a Nanny solution does, never the raw enum name. */
@Composable
internal fun plainAction(action: HallMonitorAction): String = stringResource(
    when (action) {
        HallMonitorAction.Retain -> Res.string.queen_action_retain
        HallMonitorAction.Remove -> Res.string.queen_action_remove
        HallMonitorAction.Rewrite -> Res.string.queen_action_rewrite
        HallMonitorAction.Replace -> Res.string.queen_action_replace
        HallMonitorAction.Split -> Res.string.queen_action_split
        HallMonitorAction.Merge -> Res.string.queen_action_merge
        HallMonitorAction.Reroute -> Res.string.queen_action_reroute
        HallMonitorAction.Restructure -> Res.string.queen_action_restructure
        HallMonitorAction.Benchmark -> Res.string.queen_action_benchmark
    },
)
