package com.hereliesaz.geministrator

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.orchestration.PlanDecision
import com.hereliesaz.geministrator.orchestration.PlanVerdict
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.plan_decision_approved_after
import com.hereliesaz.geministrator.resources.plan_decision_approved_first
import com.hereliesaz.geministrator.resources.plan_decision_decline
import com.hereliesaz.geministrator.resources.plan_decision_needs_approval
import com.hereliesaz.geministrator.resources.plan_decision_not_approved
import com.hereliesaz.geministrator.resources.plan_decision_plan_heading
import com.hereliesaz.geministrator.resources.plan_decision_round_approved
import com.hereliesaz.geministrator.resources.plan_decision_round_changes
import com.hereliesaz.geministrator.resources.plan_decision_said_heading
import com.hereliesaz.geministrator.resources.plan_decision_start
import com.hereliesaz.geministrator.resources.plan_decision_step
import com.hereliesaz.geministrator.resources.plan_decision_title
import org.jetbrains.compose.resources.stringResource

/**
 * The keeper's final say on a reviewed plan (docs/Orchestration_layer.md, Plan review): the plan the
 * reviewer ended with and everything the reviewer said, whether it approved or the rounds ran out. The
 * run does not exist until the keeper starts it. Everything shown is model text, so it is plain text and
 * selectable, never rendered as markup.
 */
@Composable
internal fun PlanDecisionOverlay(
    decision: PlanDecision,
    onStart: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val outcome = decision.outcome
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            }
            .padding(horizontal = 20.dp, vertical = 28.dp),
        contentAlignment = Alignment.Center,
    ) {
        AzphaltThemedSurface(Azphalt.Ink) {
            Column(
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .heightIn(max = 720.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(Azphalt.Ink)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(stringResource(Res.string.plan_decision_title), style = AzphaltType.eyebrow, color = azphaltOnSurface())
                val rounds = outcome.rounds.size
                val status = when {
                    outcome.problem != null -> outcome.problem
                    outcome.approved && rounds == 1 -> stringResource(Res.string.plan_decision_approved_first, decision.reviewerName)
                    outcome.approved -> stringResource(Res.string.plan_decision_approved_after, decision.reviewerName, rounds)
                    else -> stringResource(Res.string.plan_decision_not_approved, decision.reviewerName, rounds)
                }
                Text(status, style = AzphaltType.lead, color = azphaltOnSurface())
                Column(
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(stringResource(Res.string.plan_decision_plan_heading), style = AzphaltType.eyebrow, color = azphaltOnSurface())
                            outcome.plan.steps.forEach { step ->
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    val role = decision.roleNames[step.roleId] ?: step.roleId
                                    Text(
                                        stringResource(Res.string.plan_decision_step, step.name, role) +
                                            if (step.requiresHumanApproval) " · " + stringResource(Res.string.plan_decision_needs_approval) else "",
                                        style = AzphaltType.body,
                                        color = azphaltOnSurface(),
                                    )
                                    Text(step.objective, style = AzphaltType.endCap, color = azphaltOnSurface())
                                }
                            }
                            if (outcome.rounds.any { it.verdict.reasons.isNotBlank() }) {
                                Text(
                                    stringResource(Res.string.plan_decision_said_heading),
                                    style = AzphaltType.eyebrow,
                                    color = azphaltOnSurface(),
                                    modifier = Modifier.padding(top = 6.dp),
                                )
                                outcome.rounds.forEachIndexed { index, round ->
                                    val heading = when (round.verdict) {
                                        is PlanVerdict.Approve -> Res.string.plan_decision_round_approved
                                        is PlanVerdict.Reject -> Res.string.plan_decision_round_changes
                                    }
                                    Text(stringResource(heading, index + 1), style = AzphaltType.eyebrow, color = azphaltOnSurface())
                                    if (round.verdict.reasons.isNotBlank()) {
                                        Text(round.verdict.reasons, style = AzphaltType.body, color = azphaltOnSurface())
                                    }
                                }
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    AzphaltPill(
                        label = stringResource(Res.string.plan_decision_start),
                        seed = "plan-decision-start",
                        tone = PillTone.Primary,
                        onClick = onStart,
                    )
                    AzphaltPill(
                        label = stringResource(Res.string.plan_decision_decline),
                        seed = "plan-decision-decline",
                        onClick = onDecline,
                    )
                }
            }
        }
    }
}
