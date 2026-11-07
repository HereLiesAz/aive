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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.orchestration.MAX_PLAN_REVIEW_ROUNDS
import com.hereliesaz.geministrator.orchestration.PlanDecision
import com.hereliesaz.geministrator.orchestration.PlanningPhase
import com.hereliesaz.geministrator.orchestration.PlanningStage
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.planning_awaiting
import com.hereliesaz.geministrator.resources.planning_drafting
import com.hereliesaz.geministrator.resources.planning_reviewing
import com.hereliesaz.geministrator.resources.planning_reworking
import com.hereliesaz.geministrator.resources.planning_started
import com.hereliesaz.geministrator.resources.planning_title
import org.jetbrains.compose.resources.stringResource

/**
 * The launch while its plan is made (docs/Node-Creatures.md, Planning): the planner's egg growing, the
 * reviewer hatching from it, and the first step's egg, gestated by both, that cannot hatch until the review
 * is over and the keeper has started the run. Under the field, the launch's own lines while the work goes on,
 * and the keeper's decision once it is theirs. The field is the same one a running hive is drawn on.
 */
@Composable
internal fun PlanningOverlay(
    stage: PlanningStage,
    decision: PlanDecision?,
    onStart: () -> Unit,
    onDecline: () -> Unit,
    stepRows: List<LiveStepRow>,
    maxRows: Int,
    modifier: Modifier = Modifier,
) {
    val scene = remember(stage) { planningScene(stage) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) awaitPointerEvent().changes.forEach { it.consume() }
                }
            }
            .padding(horizontal = 16.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        AzphaltThemedSurface(Azphalt.Ink) {
            Column(
                modifier = Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(24.dp))
                    .background(Azphalt.Ink)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(Res.string.planning_title), style = AzphaltType.eyebrow, color = azphaltOnSurface())
                Text(planningCaption(stage), style = AzphaltType.lead, color = azphaltOnSurface())
                Box(Modifier.fillMaxWidth().heightIn(min = 240.dp, max = 340.dp).clip(RoundedCornerShape(16.dp))) {
                    GeministratorWorkflowTerrarium(
                        definition = scene.definition,
                        liveRun = scene.run,
                        roles = scene.roles,
                        selectedTaskId = null,
                        onTaskSelected = {},
                        readOnly = true,
                        compact = true,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (decision != null) {
                    PlanDecisionContent(
                        decision = decision,
                        onStart = onStart,
                        onDecline = onDecline,
                        modifier = Modifier.heightIn(max = 340.dp),
                    )
                } else {
                    LiveStepsFeed(rows = stepRows, maxRows = maxRows)
                }
            }
        }
    }
}

/** What the launch is doing, in a line, for the phase it has reached. */
@Composable
private fun planningCaption(stage: PlanningStage): String = when (stage.phase) {
    PlanningPhase.Planning -> stringResource(Res.string.planning_drafting, stage.planner.name)
    PlanningPhase.Reviewing -> stringResource(Res.string.planning_reviewing, stage.reviewer.name, stage.round, MAX_PLAN_REVIEW_ROUNDS)
    PlanningPhase.Reworking -> stringResource(Res.string.planning_reworking, stage.planner.name, stage.round, MAX_PLAN_REVIEW_ROUNDS)
    PlanningPhase.AwaitingKeeper -> stringResource(Res.string.planning_awaiting)
    PlanningPhase.Started -> stringResource(Res.string.planning_started)
}
