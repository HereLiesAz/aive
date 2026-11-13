package com.hereliesaz.geministrator

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hereliesaz.conveyance.h2g2.H2g2WorkflowState
import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.EscalationPolicy
import com.hereliesaz.geministrator.domain.SpendCapKind
import com.hereliesaz.geministrator.workflow.roleTokensSpent
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.common_approve
import com.hereliesaz.geministrator.resources.common_inbox
import com.hereliesaz.geministrator.resources.confirm_cancel
import com.hereliesaz.geministrator.resources.confirm_reject_plan_action
import com.hereliesaz.geministrator.resources.confirm_reject_plan_body
import com.hereliesaz.geministrator.resources.confirm_reject_plan_title
import com.hereliesaz.geministrator.resources.confirm_stop_action
import com.hereliesaz.geministrator.resources.confirm_stop_body
import com.hereliesaz.geministrator.resources.confirm_stop_title
import com.hereliesaz.geministrator.resources.hall_monitor_global_pause
import com.hereliesaz.geministrator.resources.hall_monitor_hall_monitor_review
import com.hereliesaz.geministrator.resources.hall_monitor_isolated_counterfactual
import com.hereliesaz.geministrator.resources.hall_monitor_paused
import com.hereliesaz.geministrator.resources.inbox_action_answer
import com.hereliesaz.geministrator.resources.inbox_artifact_waiting
import com.hereliesaz.geministrator.resources.inbox_budget_where
import com.hereliesaz.geministrator.resources.inbox_kind_budget
import com.hereliesaz.geministrator.resources.inbox_open_creature
import com.hereliesaz.geministrator.resources.inbox_kind_escalation
import com.hereliesaz.geministrator.resources.inbox_kind_nested
import com.hereliesaz.geministrator.resources.inbox_kind_plan
import com.hereliesaz.geministrator.resources.inbox_kind_question
import com.hereliesaz.geministrator.resources.inbox_kind_task
import com.hereliesaz.geministrator.resources.inbox_loading_runtime
import com.hereliesaz.geministrator.resources.inbox_mark_all_read
import com.hereliesaz.geministrator.resources.inbox_needs_you_count
import com.hereliesaz.geministrator.resources.inbox_no_active_run_there_are_no
import com.hereliesaz.geministrator.resources.inbox_no_pending_decisions
import com.hereliesaz.geministrator.resources.inbox_no_project_yet_there_are_no
import com.hereliesaz.geministrator.resources.inbox_page_eyebrow
import com.hereliesaz.geministrator.resources.inbox_page_line
import com.hereliesaz.geministrator.resources.inbox_page_title
import com.hereliesaz.geministrator.resources.inbox_question_where
import com.hereliesaz.geministrator.resources.inbox_run_unavailable
import com.hereliesaz.geministrator.resources.inbox_runtime_disconnected
import com.hereliesaz.geministrator.resources.inspector_approve_plan
import com.hereliesaz.geministrator.resources.inspector_approve_task
import com.hereliesaz.geministrator.resources.inspector_budget_spent
import com.hereliesaz.geministrator.resources.inspector_hint_approve_nested
import com.hereliesaz.geministrator.resources.inspector_hint_approve_plan
import com.hereliesaz.geministrator.resources.inspector_hint_approve_task
import com.hereliesaz.geministrator.resources.inspector_raise_budget
import com.hereliesaz.geministrator.resources.inspector_reject_plan
import com.hereliesaz.geministrator.resources.inspector_retry_task
import com.hereliesaz.geministrator.resources.inspector_stop_workflow
import com.hereliesaz.geministrator.resources.queen_row_eyebrow
import com.hereliesaz.geministrator.resources.runs_attempt_of
import com.hereliesaz.geministrator.resources.runs_authority
import com.hereliesaz.geministrator.resources.runs_human_decision_required
import com.hereliesaz.geministrator.resources.runs_needs_you
import com.hereliesaz.geministrator.resources.runs_no_pending_decisions
import com.hereliesaz.geministrator.resources.runs_reject_reassigns_to
import com.hereliesaz.geministrator.resources.runs_reject_stops_the_workflow
import com.hereliesaz.geministrator.resources.runs_task_failed
import com.hereliesaz.geministrator.resources.spend_stop_eyebrow
import com.hereliesaz.geministrator.resources.spend_stop_stopped
import com.hereliesaz.geministrator.resources.spend_stop_title_hourly
import com.hereliesaz.geministrator.resources.spend_stop_title_run
import com.russhwolf.settings.Settings
import org.jetbrains.compose.resources.stringResource

/**
 * Messages: the one page for everything the swarm tells or asks the keeper. A phone draws the rows
 * as a column of cards; a wide window draws the list beside the open one (docs/Keepers-Room.md,
 * Messages). Both are the same rows ([messageRows]), the same words ([messageCopy]) and the same
 * actions ([messageActions]).
 */
@Composable
internal fun LiveInboxScreen(
    runtimeState: ApplicationRuntimeState,
    onApproveTask: (String) -> Unit,
    onRejectPlan: (String) -> Unit,
    onResolveEscalation: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onLiftSpendStop: () -> Unit = {},
    onOpenLimits: (() -> Unit)? = null,
    /** Opens a creature's panel on a tab: where a question or a spent budget is resolved. */
    onOpenCreature: (taskId: String, tab: CreatureTab) -> Unit = { _, _ -> },
    compact: Boolean = false,
    /** The keeper's review of a frozen swarm: the recommendations decision, or resuming. */
    onNannyReview: (HallMonitorReviewStep, Boolean) -> Unit = { _, _ -> },
    /** Starts an isolated practice run of one of the Nanny's solutions: finding id, solution index. */
    onNannyTestSolution: (String, Int) -> Unit = { _, _ -> },
    /** The keeper accepts (true) or dismisses (false) one of the queen's suggestions, by its ledger id. */
    onQueenSuggestion: (String, Boolean) -> Unit = { _, _ -> },
    onOpenStory: (() -> Unit)? = null,
    onOpenRun: (String) -> Unit = {},
    /** Opens Connections: where a Source the queen found missing is connected. */
    onOpenConnections: (() -> Unit)? = null,
    /** Opens the Roster: where a role the queen found missing is switched on or edited. */
    onOpenRoster: (() -> Unit)? = null,
) {
    if (runtimeState is ApplicationRuntimeState.Live) {
        MessagesPage(
            presentation = runtimeState.presentation,
            actions = MessageActions(
                approveTask = onApproveTask,
                rejectPlan = onRejectPlan,
                resolveEscalation = onResolveEscalation,
                liftSpendStop = onLiftSpendStop,
                openLimits = onOpenLimits,
                openCreature = onOpenCreature,
                nannyReview = onNannyReview,
                nannyTestSolution = onNannyTestSolution,
                decideSuggestion = onQueenSuggestion,
                openStory = onOpenStory,
                openRun = onOpenRun,
                openConnections = onOpenConnections,
                openRoster = onOpenRoster,
            ),
            compact = compact,
            modifier = modifier,
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(26.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            when (runtimeState) {
                ApplicationRuntimeState.Loading -> stringResource(Res.string.inbox_loading_runtime)
                is ApplicationRuntimeState.NoProject -> stringResource(Res.string.inbox_no_project_yet_there_are_no)
                is ApplicationRuntimeState.NoRun -> stringResource(Res.string.inbox_no_active_run_there_are_no)
                is ApplicationRuntimeState.Disconnected -> stringResource(Res.string.inbox_runtime_disconnected, friendlyErrorMessage(runtimeState.message))
                is ApplicationRuntimeState.ResumeFailed -> stringResource(Res.string.inbox_run_unavailable, friendlyErrorMessage(runtimeState.message))
                is ApplicationRuntimeState.Live -> stringResource(Res.string.inbox_no_pending_decisions)
            },
            style = AzphaltType.body,
            color = azphaltOnSurface(),
        )
    }
}

/** Everything a Messages row can do, as one value. Each is the run's own call: nothing here decides for the keeper. */
internal class MessageActions(
    val approveTask: (String) -> Unit = {},
    val rejectPlan: (String) -> Unit = {},
    /** Retry (true) or stop (false) an escalated creature. */
    val resolveEscalation: (String, Boolean) -> Unit = { _, _ -> },
    val liftSpendStop: () -> Unit = {},
    val openLimits: (() -> Unit)? = null,
    val openCreature: (taskId: String, tab: CreatureTab) -> Unit = { _, _ -> },
    val nannyReview: (HallMonitorReviewStep, Boolean) -> Unit = { _, _ -> },
    val nannyTestSolution: (String, Int) -> Unit = { _, _ -> },
    /** The keeper accepts (true) or dismisses (false) one of the queen's suggestions, by its ledger id. */
    val decideSuggestion: (String, Boolean) -> Unit = { _, _ -> },
    /** Opens the Story (the run's timeline). */
    val openStory: (() -> Unit)? = null,
    /** Opens another run, such as a practice run. */
    val openRun: (String) -> Unit = {},
    /** Opens Connections, where a Source is connected. */
    val openConnections: (() -> Unit)? = null,
    /** Opens the Roster, where a role is switched on or edited. */
    val openRoster: (() -> Unit)? = null,
)

/** Why an escalated step stopped, in one plain line (no internal code); null when the run recorded no reason. */
@Composable
internal fun escalationReason(message: String?): String? = message?.trim()?.takeIf(String::isNotEmpty)?.let { plainFailureLine(it) }

/**
 * The page title on wide pages: an eyebrow, a big title, one line, and an optional action on the
 * right. (The shell's own `SectionTitle` takes this over once it lands.)
 */
@Composable
internal fun WidePageTitle(eyebrow: String, title: String, line: String, action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 91.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }) {
            Text(eyebrow.uppercase(), style = AzphaltType.eyebrow, color = azphaltOnSurface())
            Spacer(Modifier.height(7.dp))
            Text(title, style = AzphaltType.hero, color = azphaltOnSurface())
            Spacer(Modifier.height(8.dp))
            Text(line, style = AzphaltType.body, color = azphaltOnSurface())
        }
        action?.invoke()
    }
}

/** A filled Ink pill on the page: the page title's action. */
@Composable
internal fun InkPagePill(label: String, onClick: () -> Unit) {
    val fill = Azphalt.Ink
    Box(
        Modifier
            .defaultMinSize(minHeight = AzphaltMinTouchTarget)
            .clip(RoundedCornerShape(999.dp))
            .background(fill)
            .semantics { role = Role.Button }
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label.uppercase(), style = AzphaltType.endCap, color = fill.onOpposite())
    }
}

/**
 * Messages (docs/Keepers-Room.md): wide, the list of everything beside the open row with its
 * creature, what it says, its evidence and the real actions; on a phone, the same rows one under the
 * other. Approving calls the run's own approve, which records the keeper's decision on the gate, so
 * every `HumanApproval` still waits for a person (docs/THREAT_MODEL.md, invariant 4).
 */
@Composable
internal fun MessagesPage(
    presentation: LiveWorkflowPresentation,
    actions: MessageActions,
    compact: Boolean,
    modifier: Modifier = Modifier,
    readLog: InboxReadLog = remember { InboxReadLog(Settings()) },
) {
    val rows = remember(presentation) { messageRows(presentation) }
    var selectedKey by rememberDurableStringState("inbox.selected")
    // Reject plan / Stop workflow are irreversible, so each asks first: (task id, is stop).
    var confirming by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    confirming?.let { (taskId, isStop) ->
        AzphaltAlertDialog(
            title = stringResource(if (isStop) Res.string.confirm_stop_title else Res.string.confirm_reject_plan_title),
            text = stringResource(if (isStop) Res.string.confirm_stop_body else Res.string.confirm_reject_plan_body),
            confirmLabel = stringResource(if (isStop) Res.string.confirm_stop_action else Res.string.confirm_reject_plan_action),
            onConfirm = {
                confirming = null
                if (isStop) actions.resolveEscalation(taskId, false) else actions.rejectPlan(taskId)
            },
            onDismissRequest = { confirming = null },
            dismissLabel = stringResource(Res.string.confirm_cancel),
        )
    }
    BoxWithConstraints(modifier) {
        if (maxWidth >= ControlRoomBreakpoints.Wide) {
            Column(Modifier.fillMaxSize().padding(start = 28.dp, top = 27.dp, end = 28.dp, bottom = 40.dp)) {
                WidePageTitle(
                    eyebrow = stringResource(Res.string.inbox_page_eyebrow),
                    title = stringResource(Res.string.inbox_page_title),
                    line = stringResource(Res.string.inbox_page_line),
                    action = {
                        // Every item the run lists, whether or not it is on screen, is marked seen.
                        InkPagePill(stringResource(Res.string.inbox_mark_all_read)) {
                            readLog.markRead(inboxItemKeys(presentation.run) + queenSuggestionKeys(presentation))
                        }
                    },
                )
                Spacer(Modifier.height(12.dp))
                val selected = selectedMessageRow(rows, selectedKey)
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MessageList(
                        rows = rows,
                        presentation = presentation,
                        selected = selected,
                        onSelect = { selectedKey = it.key },
                        modifier = Modifier.weight(0.8f).widthIn(min = 330.dp).fillMaxHeight(),
                    )
                    MessageDetail(
                        row = selected,
                        presentation = presentation,
                        actions = actions,
                        onConfirm = { confirming = it },
                        compact = compact,
                        wide = true,
                        onSelect = { selectedKey = it },
                        modifier = Modifier.weight(1.2f).widthIn(min = 430.dp).fillMaxHeight(),
                    )
                }
            }
        } else {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(26.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(Res.string.common_inbox), style = AzphaltType.hero, color = azphaltOnSurface())
                if (rows.isEmpty()) {
                    Text(stringResource(Res.string.runs_no_pending_decisions), style = AzphaltType.body, color = azphaltOnSurface())
                }
                rows.forEach { row ->
                    MessageDetail(
                        row = row,
                        presentation = presentation,
                        actions = actions,
                        onConfirm = { confirming = it },
                        compact = compact,
                        wide = false,
                        onSelect = { selectedKey = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageList(
    rows: List<MessageRow>,
    presentation: LiveWorkflowPresentation,
    selected: MessageRow?,
    onSelect: (MessageRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ink = Azphalt.Ink
    Box(modifier.clip(RoundedCornerShape(19.dp)).background(ink)) {
        AzphaltSurface(ink) {
            Column(Modifier.fillMaxSize().padding(13.dp)) {
                val strong = azphaltStrong()
                val needYou = rows.count { it.countsToBadge }
                val count = stringResource(Res.string.inbox_needs_you_count, needYou)
                Row(
                    Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 7.dp)
                        .semantics(mergeDescendants = true) { heading(); contentDescription = count },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(Res.string.runs_needs_you).uppercase(),
                        style = AzphaltType.endCap,
                        color = strong,
                        modifier = Modifier.weight(1f),
                    )
                    Box(
                        Modifier.defaultMinSize(minWidth = 23.dp, minHeight = 23.dp).clip(CircleShape).background(strong)
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(needYou.toString(), style = AzphaltType.endCap, color = strong.onOpposite())
                    }
                }
                Column(
                    Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).selectableGroup(),
                ) {
                    if (rows.isEmpty()) {
                        Text(
                            stringResource(Res.string.runs_no_pending_decisions),
                            style = AzphaltType.body,
                            color = azphaltMutedOnSurface(),
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 12.dp),
                        )
                    }
                    rows.forEach { row -> MessageListRow(row, presentation, row == selected) { onSelect(row) } }
                }
            }
        }
    }
}

/** The three lines a list row shows: what it is, what it is about, and who or what it is for. */
private class MessageListText(val line: String, val title: String, val sub: String?)

@Composable
private fun messageListText(row: MessageRow, presentation: LiveWorkflowPresentation): MessageListText = when (row) {
    is MessageRow.Wait -> MessageListText(inboxKindLine(row.entry), row.entry.title, row.entry.role)
    is MessageRow.Freeze -> MessageListText(
        stringResource(Res.string.hall_monitor_global_pause).uppercase(),
        stringResource(Res.string.hall_monitor_hall_monitor_review),
        stringResource(Res.string.hall_monitor_paused),
    )
    is MessageRow.AllowanceStop -> MessageListText(
        stringResource(Res.string.spend_stop_eyebrow).uppercase(),
        stringResource(
            when (presentation.run.spendPause?.kind) {
                SpendCapKind.HourlySpend -> Res.string.spend_stop_title_hourly
                else -> Res.string.spend_stop_title_run
            },
        ),
        stringResource(Res.string.spend_stop_stopped),
    )
    is MessageRow.Queen -> MessageListText(
        stringResource(Res.string.queen_row_eyebrow).uppercase(),
        queenTitle(row.note),
        null,
    )
    is MessageRow.PracticeRun -> MessageListText(
        stringResource(Res.string.hall_monitor_isolated_counterfactual),
        presentation.definition.name,
        null,
    )
}

@Composable
private fun MessageListRow(row: MessageRow, presentation: LiveWorkflowPresentation, isSelected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(11.dp)
    val strong = azphaltStrong()
    val muted = azphaltMutedOnSurface()
    val divider = muted.copy(alpha = 0.35f)
    val text = messageListText(row, presentation)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 102.dp)
            .then(if (isSelected) Modifier else Modifier.drawBehind {
                drawLine(divider, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), strokeWidth = 1f)
            })
            .clip(shape)
            .then(if (isSelected) Modifier.background(AzphaltLiftedInk).border(1.dp, strong, shape) else Modifier)
            .selectable(selected = isSelected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.padding(top = 4.dp).size(9.dp).clip(CircleShape).background(messageRowTone(row)))
        Column(Modifier.weight(1f)) {
            Text(text.line, style = AzphaltType.endCap, color = muted)
            Spacer(Modifier.height(6.dp))
            Text(text.title, style = AzphaltType.lead, color = azphaltOnSurface())
            text.sub?.let {
                Spacer(Modifier.height(5.dp))
                Text(it, style = AzphaltType.body, color = muted)
            }
        }
        InboxArrow(muted, Modifier.padding(top = 28.dp))
    }
}

/** The dot beside a row: Yellow for approvals, red for a failure or a stop, the creature's own hue otherwise. */
@Composable
private fun messageRowTone(row: MessageRow): Color = when (row) {
    is MessageRow.Wait -> when (row.entry.kind) {
        InboxEntryKind.PlanApproval, InboxEntryKind.TaskApproval, InboxEntryKind.NestedApproval -> azphaltStrong()
        InboxEntryKind.Escalation -> AzphaltDanger
        InboxEntryKind.Question, InboxEntryKind.BudgetSpent -> Azphalt.hue(row.entry.taskId)
    }
    is MessageRow.AllowanceStop -> AzphaltDanger
    is MessageRow.Freeze, is MessageRow.PracticeRun -> azphaltStrong()
    is MessageRow.Queen -> azphaltMutedOnSurface()
}

@Composable
internal fun inboxKindLabel(kind: InboxEntryKind): String = stringResource(
    when (kind) {
        InboxEntryKind.PlanApproval -> Res.string.inbox_kind_plan
        InboxEntryKind.TaskApproval -> Res.string.inbox_kind_task
        InboxEntryKind.NestedApproval -> Res.string.inbox_kind_nested
        InboxEntryKind.Escalation -> Res.string.inbox_kind_escalation
        InboxEntryKind.Question -> Res.string.inbox_kind_question
        InboxEntryKind.BudgetSpent -> Res.string.inbox_kind_budget
    },
)

/** "ESCALATION · ATTEMPT 2 OF 3": the kind, and the attempt once it is not the first. No clock: the run keeps none per wait. */
@Composable
private fun inboxKindLine(entry: InboxEntry): String {
    val kind = inboxKindLabel(entry.kind)
    val line = if (entry.attempt > 1) "$kind · ${stringResource(Res.string.runs_attempt_of, entry.attempt, entry.maxAttempts).trim()}" else kind
    return line.uppercase()
}

@Composable
private fun InboxArrow(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(16.dp)) {
        val u = size.width / 16f
        val stroke = Stroke(width = 1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        drawLine(color, Offset(3f * u, 8f * u), Offset(13f * u, 8f * u), strokeWidth = 1.8f * u, cap = StrokeCap.Round)
        drawPath(
            Path().apply {
                moveTo(9f * u, 4f * u)
                lineTo(13f * u, 8f * u)
                lineTo(9f * u, 12f * u)
            },
            color,
            style = stroke,
        )
    }
}

/**
 * The open row. A wait is an Ink pane with its creature, what it says, its evidence and its actions;
 * the freeze, the stop and the practice run are the pages they always were, as this row's detail.
 * [wide] puts the row in a pane of its own (it scrolls); a phone lets the page scroll.
 */
@Composable
private fun MessageDetail(
    row: MessageRow?,
    presentation: LiveWorkflowPresentation,
    actions: MessageActions,
    onConfirm: (Pair<String, Boolean>) -> Unit,
    compact: Boolean,
    wide: Boolean,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ink = Azphalt.Ink
    when (row) {
        null -> Box(modifier.clip(RoundedCornerShape(19.dp)).background(ink)) {
            AzphaltSurface(ink) {
                Text(
                    stringResource(Res.string.runs_no_pending_decisions),
                    style = AzphaltType.lead,
                    color = azphaltOnSurface(),
                    modifier = Modifier.padding(30.dp),
                )
            }
        }
        is MessageRow.Queen -> Box(modifier.clip(RoundedCornerShape(19.dp)).background(ink)) {
            AzphaltSurface(ink) {
                Column(
                    (if (wide) Modifier.fillMaxSize().verticalScroll(rememberScrollState()) else Modifier.fillMaxWidth())
                        .padding(if (wide) 30.dp else 20.dp),
                ) {
                    Text(stringResource(Res.string.queen_row_eyebrow).uppercase(), style = AzphaltType.eyebrow, color = azphaltStrong())
                    Spacer(Modifier.height(10.dp))
                    Text(
                        queenTitle(row.note),
                        style = AzphaltType.section,
                        color = azphaltOnSurface(),
                        modifier = Modifier.widthIn(max = 480.dp).semantics { heading() },
                    )
                    Spacer(Modifier.height(20.dp))
                    QueenSays(listOf(row.note), actions, showLink = true, onSelect = onSelect)
                }
            }
        }
        is MessageRow.Wait -> Box(modifier.clip(RoundedCornerShape(19.dp)).background(ink)) {
            AzphaltSurface(ink) {
                val entry = row.entry
                val task = presentation.definition.tasks.first { it.id.value == entry.taskId }
                val taskRun = presentation.run.taskRuns.getValue(task.id)
                if (wide) {
                    // The creature itself, faint behind the corner (decoration: the role is in the eyebrow).
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(top = 8.dp, end = 12.dp).size(170.dp).alpha(0.36f)
                            .clearAndSetSemantics {},
                    ) {
                        NodeMascotSurface(
                            roleLabel = entry.role,
                            hueSeed = entry.taskId,
                            state = H2g2WorkflowState.Gate,
                            motionPhase = { 0f },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Column(
                    (if (wide) Modifier.fillMaxSize().verticalScroll(rememberScrollState()) else Modifier.fillMaxWidth())
                        .padding(if (wide) 30.dp else 20.dp),
                ) {
                    WaitDetail(entry, presentation, actions, onConfirm, onSelect)
                }
            }
        }
        else -> Column(
            (if (wide) modifier.verticalScroll(rememberScrollState()) else modifier),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when (row) {
                is MessageRow.AllowanceStop -> SpendStopDetail(
                    presentation = presentation,
                    onLiftSpendStop = actions.liftSpendStop,
                    onOpenLimits = actions.openLimits,
                    compact = compact,
                    says = { QueenSays(presentation.queen.filter { it.attachedTo == row.key }, actions, showLink = false, onSelect = onSelect) },
                )
                is MessageRow.Freeze -> HallMonitorPauseDetail(
                    presentation = presentation,
                    onTestSolution = actions.nannyTestSolution,
                    onReview = actions.nannyReview,
                    says = { QueenSays(presentation.queen.filter { it.attachedTo == row.key }, actions, showLink = true, onSelect = onSelect) },
                )
                is MessageRow.PracticeRun -> HallMonitorTrialDetail(presentation)
                is MessageRow.Wait, is MessageRow.Queen -> Unit
            }
        }
    }
}

/** One waiting creature: kind and role, title, what it says, the evidence it waits with, and the real actions. */
@Composable
private fun WaitDetail(
    entry: InboxEntry,
    presentation: LiveWorkflowPresentation,
    actions: MessageActions,
    onConfirm: (Pair<String, Boolean>) -> Unit,
    onSelect: (String) -> Unit,
) {
    val task = presentation.definition.tasks.first { it.id.value == entry.taskId }
    val taskRun = presentation.run.taskRuns.getValue(task.id)
    val strong = azphaltStrong()
    // The decision announces itself as one message; its buttons stay outside the merge, or they
    // could not be reached on their own.
    Column(Modifier.azphaltLiveRegion()) {
        Text("${inboxKindLabel(entry.kind)} · ${entry.role}".uppercase(), style = AzphaltType.eyebrow, color = strong)
        Spacer(Modifier.height(10.dp))
        Text(
            entry.title,
            style = AzphaltType.section,
            color = azphaltOnSurface(),
            modifier = Modifier.widthIn(max = 480.dp).semantics { heading() },
        )
        Spacer(Modifier.height(24.dp))
        Text(
            messageCopy(entry, task, taskRun, presentation),
            style = AzphaltType.body,
            color = azphaltMutedOnSurface(),
            modifier = Modifier.widthIn(max = 530.dp),
        )
        // Every message points at the creature it is about, where it can be seen and changed.
        if (entry.kind != InboxEntryKind.Question) {
            LinkText(
                stringResource(Res.string.inbox_open_creature),
                onClick = { actions.openCreature(entry.taskId, CreatureTab.Needs) },
                modifier = Modifier.widthIn(max = 530.dp),
            )
        }
        QueenSays(presentation.queen.filter { it.attachedTo == entry.key }, actions, showLink = false, onSelect = onSelect)
        if (entry.kind == InboxEntryKind.Escalation && !isPlainReason(taskRun.blockingReason?.message)) {
            ErrorDetailsToggle(raw = taskRun.blockingReason?.message, seed = "inbox-${entry.taskId}", modifier = Modifier.widthIn(max = 530.dp))
        }
        Spacer(Modifier.height(20.dp))
        // The evidence it waits with, as the run holds it: never invented.
        taskRun.artifacts.forEach { artifact ->
            val ref = artifact.uri
                ?: artifact.textContent?.let { if (it.length > 120) it.take(120) + "…" else it }
            Column(
                Modifier.fillMaxWidth().padding(bottom = 9.dp).clip(RoundedCornerShape(13.dp))
                    .background(AzphaltLiftedInk).padding(17.dp)
                    .semantics(mergeDescendants = true) {},
            ) {
                Text(stringResource(Res.string.inbox_artifact_waiting).uppercase(), style = AzphaltType.endCap, color = strong)
                Spacer(Modifier.height(7.dp))
                Text(artifact.label, style = AzphaltType.lead, color = azphaltOnSurface())
                ref?.let {
                    Spacer(Modifier.height(5.dp))
                    Text(it, style = AzphaltType.body, color = azphaltMutedOnSurface())
                }
            }
        }
    }
    Spacer(Modifier.height(11.dp))
    MessageActionRow(entry, actions, onConfirm, taskRun.assignedRoleId?.value)
}

/**
 * What a wait says, in one place for every width: the task's own words or the reason it stopped, what
 * the keeper's answer does, and where it is resolved.
 */
@Composable
internal fun messageCopy(
    entry: InboxEntry,
    task: com.hereliesaz.geministrator.domain.TaskDefinition,
    taskRun: com.hereliesaz.geministrator.domain.TaskRun,
    presentation: LiveWorkflowPresentation,
): String = when (entry.kind) {
    InboxEntryKind.PlanApproval, InboxEntryKind.TaskApproval -> buildString {
        append(taskRun.progressMessage?.takeIf(String::isNotBlank) ?: task.objective)
        append("\n\n")
        append(stringResource(if (entry.kind == InboxEntryKind.PlanApproval) Res.string.inspector_hint_approve_plan else Res.string.inspector_hint_approve_task))
        (task.approvalPolicy as? ApprovalPolicy.RoleApproval)?.let {
            append("\n")
            append(stringResource(Res.string.runs_authority, it.authority.name).removePrefix(" · "))
        }
    }
    InboxEntryKind.NestedApproval -> stringResource(Res.string.inspector_hint_approve_nested)
    InboxEntryKind.Escalation -> buildString {
        append(escalationReason(taskRun.blockingReason?.message) ?: stringResource(Res.string.runs_task_failed))
        append("\n")
        append(stringResource(Res.string.runs_attempt_of, taskRun.attempt, entry.maxAttempts).trim())
        task.escalationPolicy?.let { policy ->
            append(
                when (policy) {
                    is EscalationPolicy.FailWorkflow -> stringResource(Res.string.runs_reject_stops_the_workflow)
                    is EscalationPolicy.RequireHumanDecision -> stringResource(Res.string.runs_human_decision_required)
                    is EscalationPolicy.Reassign -> stringResource(Res.string.runs_reject_reassigns_to, policy.roleId.value)
                },
            )
        }
    }
    InboxEntryKind.Question -> stringResource(Res.string.inbox_question_where)
    InboxEntryKind.BudgetSpent -> {
        val spent = taskRun.assignedRoleId?.let { presentation.run.roleTokensSpent(it) } ?: 0L
        stringResource(Res.string.inspector_budget_spent, spent.toString()) + "\n\n" + stringResource(Res.string.inbox_budget_where)
    }
}

@Composable
private fun messageActionLabel(action: MessageAction): String = stringResource(
    when (action) {
        MessageAction.ApprovePlan -> Res.string.inspector_approve_plan
        MessageAction.RejectPlan -> Res.string.inspector_reject_plan
        MessageAction.ApproveTask -> Res.string.inspector_approve_task
        MessageAction.Approve -> Res.string.common_approve
        MessageAction.Retry -> Res.string.inspector_retry_task
        MessageAction.Stop -> Res.string.inspector_stop_workflow
        MessageAction.Answer -> Res.string.inbox_action_answer
        MessageAction.RaiseBudget -> Res.string.inspector_raise_budget
    },
)

/** The real actions for what a creature waits on: the same calls as the creature's Needs. */
@Composable
private fun MessageActionRow(
    entry: InboxEntry,
    actions: MessageActions,
    onConfirm: (Pair<String, Boolean>) -> Unit,
    roleId: String? = null,
) {
    val id = entry.taskId
    val placeLinks = LocalPlaceLinks.current
    val buttons = messageActions(entry.kind)
    if (buttons.isEmpty()) return
    Row(Modifier.widthIn(max = 440.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        buttons.forEachIndexed { index, action ->
            val onClick: () -> Unit = when (action) {
                MessageAction.ApprovePlan, MessageAction.ApproveTask, MessageAction.Approve -> ({ actions.approveTask(id) })
                MessageAction.RejectPlan -> ({ onConfirm(id to false) })
                MessageAction.Retry -> ({ actions.resolveEscalation(id, true) })
                MessageAction.Stop -> ({ onConfirm(id to true) })
                MessageAction.Answer -> ({ actions.openCreature(id, CreatureTab.Needs) })
                // The budget is the role's: the button goes to where it is changed, the creature's Mind only when there is no Roster link.
                MessageAction.RaiseBudget -> ({
                    if (roleId == null || !placeLinks.openRoleAt(roleId, RoleField.TokenBudget)) {
                        actions.openCreature(id, CreatureTab.Mind)
                    }
                })
            }
            // The first button is the main one, filled; the other is outlined.
            InboxAction(messageActionLabel(action), index == 0, onClick, Modifier.weight(1f))
        }
        if (buttons.size == 1) Spacer(Modifier.weight(1f))
    }
}

/** A detail button: the main one filled in the surface's strong colour, the other outlined. */
@Composable
private fun InboxAction(label: String, primary: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(999.dp)
    val strong = azphaltStrong()
    val line = azphaltOnSurface()
    Box(
        modifier
            .defaultMinSize(minHeight = AzphaltMinTouchTarget)
            .clip(shape)
            .then(if (primary) Modifier.background(strong) else Modifier.border(2.dp, line, shape))
            .semantics { role = Role.Button }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label.uppercase(), style = AzphaltType.endCap, color = if (primary) strong.onOpposite() else line)
    }
}
