package com.hereliesaz.geministrator

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.flowchart_action_fix_connections
import com.hereliesaz.geministrator.resources.flowchart_action_open_compute
import com.hereliesaz.geministrator.resources.flowchart_action_open_creature
import com.hereliesaz.geministrator.resources.flowchart_action_open_inbox
import com.hereliesaz.geministrator.resources.flowchart_action_open_limits
import com.hereliesaz.geministrator.resources.flowchart_action_show_step
import com.hereliesaz.geministrator.resources.flowchart_detail_blocked
import com.hereliesaz.geministrator.resources.flowchart_detail_blocked_with
import com.hereliesaz.geministrator.resources.flowchart_detail_budget_spent
import com.hereliesaz.geministrator.resources.flowchart_detail_budget_spent_unnamed
import com.hereliesaz.geministrator.resources.flowchart_detail_compute_unavailable
import com.hereliesaz.geministrator.resources.flowchart_detail_done
import com.hereliesaz.geministrator.resources.flowchart_detail_escalated
import com.hereliesaz.geministrator.resources.flowchart_detail_failed
import com.hereliesaz.geministrator.resources.flowchart_detail_failed_with
import com.hereliesaz.geministrator.resources.flowchart_detail_gate_turned_back
import com.hereliesaz.geministrator.resources.flowchart_detail_hall_monitor
import com.hereliesaz.geministrator.resources.flowchart_detail_integration_unavailable
import com.hereliesaz.geministrator.resources.flowchart_detail_keeper_paused
import com.hereliesaz.geministrator.resources.flowchart_detail_no_provider
import com.hereliesaz.geministrator.resources.flowchart_detail_not_started
import com.hereliesaz.geministrator.resources.flowchart_detail_provider_failed
import com.hereliesaz.geministrator.resources.flowchart_detail_provider_failed_plain
import com.hereliesaz.geministrator.resources.flowchart_detail_question
import com.hereliesaz.geministrator.resources.flowchart_detail_skipped
import com.hereliesaz.geministrator.resources.flowchart_detail_spend_stopped
import com.hereliesaz.geministrator.resources.flowchart_detail_title
import com.hereliesaz.geministrator.resources.flowchart_detail_waiting_approval
import com.hereliesaz.geministrator.resources.flowchart_detail_waiting_on
import com.hereliesaz.geministrator.resources.flowchart_detail_working
import com.hereliesaz.geministrator.resources.inspector_raise_budget
import org.jetbrains.compose.resources.stringResource

/**
 * The shell's existing ways to a place, handed to the Flowchart's detail panel. Each is the same lambda the
 * creature panel and Inbox already use; nothing here navigates on its own. Null (a preview, a screenshot):
 * the panel still reads the card and only offers the step link, which needs no shell.
 */
internal class FlowchartLinks(
    /** The creature's Needs (a decision waits there); null opens the Inbox itself. */
    val openInbox: (taskId: String?) -> Unit,
    val openLimits: () -> Unit,
    /** Null where the Compute place is not offered. */
    val openCompute: (() -> Unit)?,
    val openConnections: () -> Unit,
    val reconfigureProvider: (String) -> Unit,
    /** Leaves Flowchart for Live and opens the creature's panel on [CreatureTab]. */
    val openCreature: (taskId: String, tab: CreatureTab) -> Unit,
)

/** The sentences whose [FlowchartCardDetail.detail] is text a provider or the runtime wrote. */
private val RAW_TEXT_SENTENCES = setOf(FlowchartSentenceKind.ProviderFailed, FlowchartSentenceKind.Failed)

internal val LocalFlowchartLinks = staticCompositionLocalOf<FlowchartLinks?> { null }

/**
 * Under the Flowchart: for the selected card, one sentence on what is active or what holds it, and one
 * button to where that is unblocked. It decides nothing ([flowchartCardDetail] does) and offers no
 * pickers: it links.
 */
@Composable
internal fun FlowchartCardDetailPanel(
    taskId: String,
    definition: WorkflowDefinition,
    run: WorkflowRun,
    roles: Collection<RoleDefinition>,
    onShowStep: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val links = LocalFlowchartLinks.current
    val detail = flowchartCardDetail(
        TaskDefinitionId(taskId), definition, run, roles, computeAvailable = links?.openCompute != null,
    ) ?: return
    val name = definition.tasks.firstOrNull { it.id.value == taskId }?.name ?: taskId
    val sentence = flowchartSentenceText(detail)
    val onClick: (() -> Unit)? = when (val action = detail.action) {
        is FlowchartCardAction.ShowStep -> ({ onShowStep(action.taskId) })
        is FlowchartCardAction.OpenInbox -> links?.let { l -> { l.openInbox(action.taskId) } }
        FlowchartCardAction.OpenLimits -> links?.let { l -> { l.openLimits() } }
        FlowchartCardAction.OpenCompute -> links?.openCompute
        FlowchartCardAction.OpenConnections -> links?.let { l -> { l.openConnections() } }
        is FlowchartCardAction.FixProvider -> links?.let { l -> { l.reconfigureProvider(action.providerId) } }
        is FlowchartCardAction.OpenCreature -> links?.let { l -> { l.openCreature(action.taskId, action.tab) } }
    }
    AzphaltThemedSurface(Azphalt.Ink) {
        Column(
            modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Azphalt.Ink)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(Res.string.flowchart_detail_title, name),
                style = AzphaltType.endCap,
                color = Azphalt.Ink.mutedOnOpposite(),
            )
            Text(sentence, style = AzphaltType.body, color = Azphalt.Ink.onOpposite())
            // What a provider or the runtime said, word for word, stays one tap away.
            if (detail.sentence in RAW_TEXT_SENTENCES && !isPlainReason(detail.detail)) {
                ErrorDetailsToggle(raw = detail.detail, seed = "flowchart-$taskId")
            }
            if (onClick != null) {
                AzphaltPill(
                    label = flowchartActionLabel(detail.action),
                    seed = "flowchart-detail-$taskId",
                    tone = PillTone.Primary,
                    onClick = onClick,
                )
            }
        }
    }
}

@Composable
private fun flowchartSentenceText(detail: FlowchartCardDetail): String = when (detail.sentence) {
    FlowchartSentenceKind.Working -> stringResource(Res.string.flowchart_detail_working, detail.subject.orEmpty())
    FlowchartSentenceKind.WaitingForApproval -> stringResource(Res.string.flowchart_detail_waiting_approval)
    FlowchartSentenceKind.Question -> stringResource(Res.string.flowchart_detail_question)
    FlowchartSentenceKind.Escalated -> stringResource(Res.string.flowchart_detail_escalated)
    FlowchartSentenceKind.SpendStopped -> stringResource(Res.string.flowchart_detail_spend_stopped)
    FlowchartSentenceKind.BudgetSpent -> detail.subject
        ?.let { stringResource(Res.string.flowchart_detail_budget_spent, it) }
        ?: stringResource(Res.string.flowchart_detail_budget_spent_unnamed)
    FlowchartSentenceKind.ComputeUnavailable -> stringResource(Res.string.flowchart_detail_compute_unavailable)
    FlowchartSentenceKind.IntegrationUnavailable -> stringResource(Res.string.flowchart_detail_integration_unavailable)
    FlowchartSentenceKind.HallMonitorPaused -> stringResource(Res.string.flowchart_detail_hall_monitor)
    FlowchartSentenceKind.ProviderFailed -> if (isPlainReason(detail.detail)) {
        stringResource(Res.string.flowchart_detail_provider_failed, detail.subject.orEmpty(), detail.detail.orEmpty())
    } else {
        stringResource(Res.string.flowchart_detail_provider_failed_plain, detail.subject.orEmpty()) + " " + friendlyErrorMessage(detail.detail)
    }
    FlowchartSentenceKind.NoProvider -> stringResource(Res.string.flowchart_detail_no_provider)
    FlowchartSentenceKind.GateTurnedBack -> stringResource(Res.string.flowchart_detail_gate_turned_back)
    FlowchartSentenceKind.WaitingOnStep -> stringResource(Res.string.flowchart_detail_waiting_on, detail.subject.orEmpty())
    FlowchartSentenceKind.KeeperPaused -> stringResource(Res.string.flowchart_detail_keeper_paused)
    FlowchartSentenceKind.Failed -> when {
        detail.detail == null -> stringResource(Res.string.flowchart_detail_failed)
        isPlainReason(detail.detail) -> stringResource(Res.string.flowchart_detail_failed_with, detail.detail)
        else -> stringResource(Res.string.flowchart_detail_failed) + " " + friendlyErrorMessage(detail.detail)
    }
    // A hold's reason is the runtime's own wording; only an error from a provider is re-said.
    FlowchartSentenceKind.Blocked -> when {
        detail.detail == null -> stringResource(Res.string.flowchart_detail_blocked)
        classifyError(detail.detail) == ErrorKind.Other -> stringResource(Res.string.flowchart_detail_blocked_with, detail.detail)
        else -> stringResource(Res.string.flowchart_detail_blocked) + " " + friendlyErrorMessage(detail.detail)
    }
    FlowchartSentenceKind.NotStarted -> stringResource(Res.string.flowchart_detail_not_started)
    FlowchartSentenceKind.Done -> stringResource(Res.string.flowchart_detail_done)
    FlowchartSentenceKind.Skipped -> stringResource(Res.string.flowchart_detail_skipped)
}

@Composable
private fun flowchartActionLabel(action: FlowchartCardAction): String = when (action) {
    is FlowchartCardAction.OpenInbox -> stringResource(Res.string.flowchart_action_open_inbox)
    FlowchartCardAction.OpenLimits -> stringResource(Res.string.flowchart_action_open_limits)
    FlowchartCardAction.OpenCompute -> stringResource(Res.string.flowchart_action_open_compute)
    FlowchartCardAction.OpenConnections, is FlowchartCardAction.FixProvider ->
        stringResource(Res.string.flowchart_action_fix_connections)
    is FlowchartCardAction.OpenCreature ->
        if (action.tab == CreatureTab.Mind) {
            stringResource(Res.string.inspector_raise_budget)
        } else {
            stringResource(Res.string.flowchart_action_open_creature)
        }
    is FlowchartCardAction.ShowStep -> stringResource(Res.string.flowchart_action_show_step, action.name)
}
