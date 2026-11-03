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
import com.hereliesaz.geministrator.agentui.awaitsKeeperAnswer
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.displayName
import com.hereliesaz.geministrator.domain.effectiveExecutor
import com.hereliesaz.geministrator.workflow.isHeldForRoleBudget
import com.hereliesaz.geministrator.workflow.isTerminal
import com.hereliesaz.geministrator.workflow.roleTokensSpent
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.common_approve
import com.hereliesaz.geministrator.resources.confirm_cancel
import com.hereliesaz.geministrator.resources.confirm_reject_plan_action
import com.hereliesaz.geministrator.resources.confirm_reject_plan_body
import com.hereliesaz.geministrator.resources.confirm_reject_plan_title
import com.hereliesaz.geministrator.resources.confirm_stop_action
import com.hereliesaz.geministrator.resources.confirm_stop_body
import com.hereliesaz.geministrator.resources.confirm_stop_title
import com.hereliesaz.geministrator.resources.inbox_artifact_waiting
import com.hereliesaz.geministrator.resources.inbox_budget_where
import com.hereliesaz.geministrator.resources.inbox_kind_budget
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
import com.hereliesaz.geministrator.resources.inspector_reject_plan
import com.hereliesaz.geministrator.resources.inspector_retry_task
import com.hereliesaz.geministrator.resources.inspector_stop_workflow
import com.hereliesaz.geministrator.resources.runs_attempt_of
import com.hereliesaz.geministrator.resources.runs_needs_you
import com.hereliesaz.geministrator.resources.runs_no_pending_decisions
import com.hereliesaz.geministrator.resources.runs_task_failed
import com.russhwolf.settings.Settings
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun LiveInboxScreen(
    runtimeState: ApplicationRuntimeState,
    onApproveTask: (String) -> Unit,
    onRejectPlan: (String) -> Unit,
    onResolveEscalation: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onLiftSpendStop: () -> Unit = {},
) {
    if (runtimeState is ApplicationRuntimeState.Live) {
        val presentation = runtimeState.presentation
        when {
            presentation.run.spendPause != null && !presentation.run.status.isTerminal() -> SpendStopInboxScreen(
                presentation = presentation,
                onLiftSpendStop = onLiftSpendStop,
                modifier = modifier,
            )

            presentation.run.globalPause != null -> HallMonitorPauseInboxScreen(
                presentation = presentation,
                onTestSolution = { findingId, solutionIndex ->
                    onResolveEscalation(hallMonitorTrialActionId(findingId, solutionIndex), true)
                },
                modifier = modifier,
            )

            isHallMonitorTrial(presentation) -> HallMonitorTrialInboxScreen(
                presentation = presentation,
                onApproveTask = onApproveTask,
                onRejectPlan = onRejectPlan,
                onResolveEscalation = onResolveEscalation,
                modifier = modifier,
            )

            // Wide (desktop, ControlRoomBreakpoints.Wide and up): everything waiting, listed beside the
            // open one. A phone keeps its single column of records.
            else -> BoxWithConstraints(modifier) {
                if (maxWidth >= ControlRoomBreakpoints.Wide) {
                    WideInboxPage(
                        presentation = presentation,
                        onApproveTask = onApproveTask,
                        onRejectPlan = onRejectPlan,
                        onResolveEscalation = onResolveEscalation,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    InboxScreen(
                        runtimeState = runtimeState,
                        onApproveTask = onApproveTask,
                        onRejectPlan = onRejectPlan,
                        onResolveEscalation = onResolveEscalation,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
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
                is ApplicationRuntimeState.Disconnected -> stringResource(Res.string.inbox_runtime_disconnected, runtimeState.message)
                is ApplicationRuntimeState.ResumeFailed -> stringResource(Res.string.inbox_run_unavailable, runtimeState.message)
                is ApplicationRuntimeState.Live -> stringResource(Res.string.inbox_no_pending_decisions)
            },
            style = AzphaltType.body,
            color = azphaltOnSurface(),
        )
    }
}

/** What one Inbox entry waits on the keeper for; [inboxEntries] decides it as the badge's key does. */
internal enum class InboxEntryKind { PlanApproval, TaskApproval, NestedApproval, Escalation, Question, BudgetSpent }

/** One creature waiting on the keeper, as the wide Inbox lists it. Only what the run really holds. */
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

/** The entry to show: the one remembered as selected while it still waits, else the first. */
internal fun selectedInboxEntry(entries: List<InboxEntry>, selectedKey: String?): InboxEntry? =
    entries.firstOrNull { it.key == selectedKey } ?: entries.firstOrNull()

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
 * The Inbox at wide widths (docs/Keepers-Room.md, Inbox): the list of everything waiting, kind,
 * title and role, beside the open one with its creature, what it says, its evidence and the real
 * actions. Approving calls the run's own approve, which records the keeper's decision on the gate,
 * so every `HumanApproval` still waits for a person (docs/THREAT_MODEL.md, invariant 4).
 */
@Composable
internal fun WideInboxPage(
    presentation: LiveWorkflowPresentation,
    onApproveTask: (String) -> Unit,
    onRejectPlan: (String) -> Unit,
    onResolveEscalation: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    readLog: InboxReadLog = remember { InboxReadLog(Settings()) },
) {
    val entries = remember(presentation) { inboxEntries(presentation) }
    var selectedKey by rememberDurableStringState("inbox.selected")
    val selected = selectedInboxEntry(entries, selectedKey)
    // Reject plan / Stop workflow are irreversible, so each asks first: (task id, is stop).
    var confirming by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    confirming?.let { (taskId, isStop) ->
        AzphaltAlertDialog(
            title = stringResource(if (isStop) Res.string.confirm_stop_title else Res.string.confirm_reject_plan_title),
            text = stringResource(if (isStop) Res.string.confirm_stop_body else Res.string.confirm_reject_plan_body),
            confirmLabel = stringResource(if (isStop) Res.string.confirm_stop_action else Res.string.confirm_reject_plan_action),
            onConfirm = {
                confirming = null
                if (isStop) onResolveEscalation(taskId, false) else onRejectPlan(taskId)
            },
            onDismissRequest = { confirming = null },
            dismissLabel = stringResource(Res.string.confirm_cancel),
        )
    }
    Column(modifier.padding(start = 28.dp, top = 27.dp, end = 28.dp, bottom = 40.dp)) {
        WidePageTitle(
            eyebrow = stringResource(Res.string.inbox_page_eyebrow),
            title = stringResource(Res.string.inbox_page_title),
            line = stringResource(Res.string.inbox_page_line),
            action = {
                // Every item the run lists, whether or not it is on screen, is marked seen.
                InkPagePill(stringResource(Res.string.inbox_mark_all_read)) {
                    readLog.markRead(inboxItemKeys(presentation.run))
                }
            },
        )
        Spacer(Modifier.height(12.dp))
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InboxList(
                entries = entries,
                selected = selected,
                onSelect = { selectedKey = it.key },
                modifier = Modifier.weight(0.8f).widthIn(min = 330.dp).fillMaxHeight(),
            )
            InboxDetail(
                entry = selected,
                presentation = presentation,
                onApprove = onApproveTask,
                onRejectPlan = { confirming = it to false },
                onRetry = { onResolveEscalation(it, true) },
                onStop = { confirming = it to true },
                modifier = Modifier.weight(1.2f).widthIn(min = 430.dp).fillMaxHeight(),
            )
        }
    }
}

@Composable
private fun InboxList(
    entries: List<InboxEntry>,
    selected: InboxEntry?,
    onSelect: (InboxEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ink = Azphalt.Ink
    Box(modifier.clip(RoundedCornerShape(19.dp)).background(ink)) {
        AzphaltSurface(ink) {
            Column(Modifier.fillMaxSize().padding(13.dp)) {
                val strong = azphaltStrong()
                val count = stringResource(Res.string.inbox_needs_you_count, entries.size)
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
                        Text(entries.size.toString(), style = AzphaltType.endCap, color = strong.onOpposite())
                    }
                }
                Column(
                    Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).selectableGroup(),
                ) {
                    if (entries.isEmpty()) {
                        Text(
                            stringResource(Res.string.runs_no_pending_decisions),
                            style = AzphaltType.body,
                            color = azphaltMutedOnSurface(),
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 12.dp),
                        )
                    }
                    entries.forEach { entry -> InboxListRow(entry, entry == selected) { onSelect(entry) } }
                }
            }
        }
    }
}

@Composable
private fun InboxListRow(entry: InboxEntry, isSelected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(11.dp)
    val strong = azphaltStrong()
    val muted = azphaltMutedOnSurface()
    val divider = muted.copy(alpha = 0.35f)
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
        Box(Modifier.padding(top = 4.dp).size(9.dp).clip(CircleShape).background(inboxKindTone(entry)))
        Column(Modifier.weight(1f)) {
            Text(inboxKindLine(entry), style = AzphaltType.endCap, color = muted)
            Spacer(Modifier.height(6.dp))
            Text(entry.title, style = AzphaltType.lead, color = azphaltOnSurface())
            Spacer(Modifier.height(5.dp))
            Text(entry.role, style = AzphaltType.body, color = muted)
        }
        InboxArrow(muted, Modifier.padding(top = 28.dp))
    }
}

/** The dot beside an entry: Yellow for approvals, red for a failure, the creature's own hue otherwise. */
@Composable
private fun inboxKindTone(entry: InboxEntry): Color = when (entry.kind) {
    InboxEntryKind.PlanApproval, InboxEntryKind.TaskApproval, InboxEntryKind.NestedApproval -> azphaltStrong()
    InboxEntryKind.Escalation -> AzphaltDanger
    InboxEntryKind.Question, InboxEntryKind.BudgetSpent -> Azphalt.hue(entry.taskId)
}

@Composable
private fun inboxKindLabel(kind: InboxEntryKind): String = stringResource(
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

@Composable
private fun InboxDetail(
    entry: InboxEntry?,
    presentation: LiveWorkflowPresentation,
    onApprove: (String) -> Unit,
    onRejectPlan: (String) -> Unit,
    onRetry: (String) -> Unit,
    onStop: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ink = Azphalt.Ink
    Box(modifier.clip(RoundedCornerShape(19.dp)).background(ink)) {
        AzphaltSurface(ink) {
            if (entry == null) {
                Text(
                    stringResource(Res.string.runs_no_pending_decisions),
                    style = AzphaltType.lead,
                    color = azphaltOnSurface(),
                    modifier = Modifier.padding(30.dp),
                )
                return@AzphaltSurface
            }
            val task = presentation.definition.tasks.first { it.id.value == entry.taskId }
            val taskRun = presentation.run.taskRuns.getValue(task.id)
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
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(30.dp).azphaltLiveRegion()) {
                val strong = azphaltStrong()
                Text("${inboxKindLabel(entry.kind)} · ${entry.role}".uppercase(), style = AzphaltType.eyebrow, color = strong)
                Spacer(Modifier.height(10.dp))
                Text(
                    entry.title,
                    style = AzphaltType.section,
                    color = azphaltOnSurface(),
                    modifier = Modifier.widthIn(max = 480.dp).semantics { heading() },
                )
                Spacer(Modifier.height(35.dp))
                Text(
                    inboxDetailCopy(entry, task, taskRun, presentation),
                    style = AzphaltType.body,
                    color = azphaltMutedOnSurface(),
                    modifier = Modifier.widthIn(max = 530.dp),
                )
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
                Spacer(Modifier.height(11.dp))
                InboxDetailActions(entry, onApprove, onRejectPlan, onRetry, onStop)
            }
        }
    }
}

@Composable
private fun inboxDetailCopy(
    entry: InboxEntry,
    task: com.hereliesaz.geministrator.domain.TaskDefinition,
    taskRun: com.hereliesaz.geministrator.domain.TaskRun,
    presentation: LiveWorkflowPresentation,
): String = when (entry.kind) {
    InboxEntryKind.PlanApproval, InboxEntryKind.TaskApproval -> buildString {
        append(taskRun.progressMessage?.takeIf(String::isNotBlank) ?: task.objective)
        append("\n\n")
        append(stringResource(if (entry.kind == InboxEntryKind.PlanApproval) Res.string.inspector_hint_approve_plan else Res.string.inspector_hint_approve_task))
    }
    InboxEntryKind.NestedApproval -> stringResource(Res.string.inspector_hint_approve_nested)
    InboxEntryKind.Escalation -> buildString {
        append(taskRun.blockingReason?.let { "${it.code} · ${it.message}" } ?: stringResource(Res.string.runs_task_failed))
        append("\n")
        append(stringResource(Res.string.runs_attempt_of, taskRun.attempt, entry.maxAttempts).trim())
    }
    InboxEntryKind.Question -> stringResource(Res.string.inbox_question_where)
    InboxEntryKind.BudgetSpent -> {
        val spent = taskRun.assignedRoleId?.let { presentation.run.roleTokensSpent(it) } ?: 0L
        stringResource(Res.string.inspector_budget_spent, spent.toString()) + "\n\n" + stringResource(Res.string.inbox_budget_where)
    }
}

/** The real actions for what it waits on: the same calls as the creature's Needs. */
@Composable
private fun InboxDetailActions(
    entry: InboxEntry,
    onApprove: (String) -> Unit,
    onRejectPlan: (String) -> Unit,
    onRetry: (String) -> Unit,
    onStop: (String) -> Unit,
) {
    val id = entry.taskId
    val actions: List<Triple<String, Boolean, () -> Unit>> = when (entry.kind) {
        InboxEntryKind.PlanApproval -> listOf(
            Triple(stringResource(Res.string.inspector_approve_plan), true) { onApprove(id) },
            Triple(stringResource(Res.string.inspector_reject_plan), false) { onRejectPlan(id) },
        )
        InboxEntryKind.TaskApproval -> listOf(Triple(stringResource(Res.string.inspector_approve_task), true) { onApprove(id) })
        InboxEntryKind.NestedApproval -> listOf(Triple(stringResource(Res.string.common_approve), true) { onApprove(id) })
        InboxEntryKind.Escalation -> listOf(
            Triple(stringResource(Res.string.inspector_retry_task), true) { onRetry(id) },
            Triple(stringResource(Res.string.inspector_stop_workflow), false) { onStop(id) },
        )
        InboxEntryKind.Question, InboxEntryKind.BudgetSpent -> emptyList()
    }
    if (actions.isEmpty()) return
    Row(Modifier.widthIn(max = 440.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        actions.forEach { (label, primary, onClick) ->
            InboxAction(label, primary, onClick, Modifier.weight(1f))
        }
        if (actions.size == 1) Spacer(Modifier.weight(1f))
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
