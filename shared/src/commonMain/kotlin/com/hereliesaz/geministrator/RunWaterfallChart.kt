package com.hereliesaz.geministrator

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.waterfall_bar_label
import com.hereliesaz.geministrator.resources.waterfall_cost
import com.hereliesaz.geministrator.resources.waterfall_empty
import com.hereliesaz.geministrator.resources.waterfall_hint
import com.hereliesaz.geministrator.resources.waterfall_no_usage
import com.hereliesaz.geministrator.resources.waterfall_outcome_cancelled
import com.hereliesaz.geministrator.resources.waterfall_outcome_completed
import com.hereliesaz.geministrator.resources.waterfall_outcome_ended
import com.hereliesaz.geministrator.resources.waterfall_outcome_failed
import com.hereliesaz.geministrator.resources.waterfall_outcome_ongoing
import com.hereliesaz.geministrator.resources.waterfall_phase_planning
import com.hereliesaz.geministrator.resources.waterfall_phase_retrying
import com.hereliesaz.geministrator.resources.waterfall_phase_running
import com.hereliesaz.geministrator.resources.waterfall_phase_verifying
import com.hereliesaz.geministrator.resources.waterfall_phase_waiting
import com.hereliesaz.geministrator.resources.waterfall_span
import com.hereliesaz.geministrator.resources.waterfall_usage
import org.jetbrains.compose.resources.stringResource

/**
 * The colour of a phase. Bars sit on Ink, and every phase clears 3:1 against it (non-text
 * contrast, tested); colour is never the only signal: a gate is drawn as a thin pause line, and
 * each bar's label names its phases.
 */
internal fun waterfallPhaseColor(phase: WaterfallPhase): Color = when (phase) {
    WaterfallPhase.Planning -> Azphalt.hues[10]
    WaterfallPhase.WaitingOnYou -> Azphalt.Yellow
    WaterfallPhase.Running -> Azphalt.hues[3]
    WaterfallPhase.Verifying -> Azphalt.hues[1]
    WaterfallPhase.Retrying -> Azphalt.hues[5]
}

@Composable
private fun WaterfallPhase.label(): String = stringResource(
    when (this) {
        WaterfallPhase.Planning -> Res.string.waterfall_phase_planning
        WaterfallPhase.WaitingOnYou -> Res.string.waterfall_phase_waiting
        WaterfallPhase.Running -> Res.string.waterfall_phase_running
        WaterfallPhase.Verifying -> Res.string.waterfall_phase_verifying
        WaterfallPhase.Retrying -> Res.string.waterfall_phase_retrying
    },
)

@Composable
private fun WaterfallBar.stateLabel(): String = stringResource(
    when (outcome) {
        WaterfallOutcome.Completed -> Res.string.waterfall_outcome_completed
        WaterfallOutcome.Failed -> Res.string.waterfall_outcome_failed
        WaterfallOutcome.Cancelled -> Res.string.waterfall_outcome_cancelled
        null -> if (ongoing) Res.string.waterfall_outcome_ongoing else Res.string.waterfall_outcome_ended
    },
)

/** Each phase with the time spent in it, in the order they first happened: "Running 2m; Waiting on you 30s". */
@Composable
private fun WaterfallBar.phaseSummary(): String {
    val totals = LinkedHashMap<WaterfallPhase, Long>()
    segments.forEach { totals[it.phase] = (totals[it.phase] ?: 0L) + it.durationMillis }
    return totals.entries.map { (phase, millis) -> "${phase.label()} ${formatWaterfallDuration(millis)}" }.joinToString("; ")
}

@Composable
private fun WaterfallBar.usageSummary(): String {
    val reported = usage ?: return stringResource(Res.string.waterfall_no_usage)
    val tokens = stringResource(Res.string.waterfall_usage, reported.inputTokens.toString(), reported.outputTokens.toString())
    val cost = reported.costUsd?.let { stringResource(Res.string.waterfall_cost, formatWaterfallCost(it)) }
    return if (cost == null) tokens else "$tokens, $cost"
}

/**
 * The run as a waterfall (docs/Keepers-Room.md, The hive's timeline): one row per creature, one
 * bar per attempt on a shared time axis, so parallel work overlaps. Hovering or tapping a bar shows
 * its phases and usage; tapping also selects its creature. Bars unroll once on arrival, and not at
 * all with reduced motion.
 */
@Composable
internal fun RunWaterfallChart(
    waterfall: RunWaterfall,
    taskName: (TaskDefinitionId) -> String,
    selectedTaskId: String?,
    onTaskSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val panel = Azphalt.Ink
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(panel).padding(18.dp)) {
        AzphaltSurface(panel) {
            val content = azphaltOnSurface()
            Column(verticalArrangement = Arrangement.spacedBy(AzphaltSpacing.s)) {
                if (waterfall.isEmpty) {
                    Text(stringResource(Res.string.waterfall_empty), style = AzphaltType.body, color = content)
                    return@Column
                }
                WaterfallLegend()
                Text(
                    stringResource(Res.string.waterfall_span, formatWaterfallDuration(waterfall.spanMillis)),
                    style = AzphaltType.eyebrow,
                    color = azphaltMutedOnSurface(),
                )
                val reduceMotion = AzphaltMotion.reduceMotion
                val reveal = remember { Animatable(if (reduceMotion) 1f else 0f) }
                LaunchedEffect(reduceMotion) {
                    if (reduceMotion) reveal.snapTo(1f) else reveal.animateTo(1f, tween(durationMillis = 420))
                }
                var hovered by remember { mutableStateOf<String?>(null) }
                var tapped by remember { mutableStateOf<String?>(null) }
                waterfall.rows.forEach { row ->
                    val name = taskName(row.taskId)
                    Text(name.uppercase(), style = AzphaltType.eyebrow, color = content)
                    WaterfallTrack(
                        row = row,
                        waterfall = waterfall,
                        name = name,
                        selected = row.taskId.value == selectedTaskId,
                        reveal = { reveal.value },
                        onHover = { key, isHovered ->
                            if (isHovered) hovered = key else if (hovered == key) hovered = null
                        },
                        onTap = { bar ->
                            tapped = bar.key
                            if (bar.taskId.value != selectedTaskId) onTaskSelected(bar.taskId.value)
                        },
                    )
                }
                val focusKey = hovered ?: tapped
                val focus = focusKey?.let { key -> waterfall.bars.firstOrNull { it.key == key } }
                Box(Modifier.fillMaxWidth().azphaltLiveRegion()) {
                    if (focus == null) {
                        Text(stringResource(Res.string.waterfall_hint), style = AzphaltType.body, color = azphaltMutedOnSurface())
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(AzphaltSpacing.xs)) {
                            Text(barLabel(focus, taskName(focus.taskId)), style = AzphaltType.lead, color = content)
                            Text(focus.phaseSummary(), style = AzphaltType.body, color = content)
                            Text(focus.usageSummary(), style = AzphaltType.body, color = content)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun barLabel(bar: WaterfallBar, name: String): String =
    stringResource(Res.string.waterfall_bar_label, name, bar.attempt, formatWaterfallDuration(bar.durationMillis), bar.stateLabel())

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WaterfallLegend() {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(AzphaltSpacing.m), verticalArrangement = Arrangement.spacedBy(AzphaltSpacing.xs)) {
        WaterfallPhase.entries.forEach { phase ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Canvas(Modifier.size(width = 18.dp, height = 12.dp)) {
                    drawPhase(phase, Offset.Zero, size.width, size.height)
                }
                Text(phase.label(), style = AzphaltType.body, color = azphaltOnSurface())
            }
        }
    }
}

private val BarHeight = 20.dp
private val PauseHeight = 4.dp
private val MinBarWidth = 4.dp

@Composable
private fun WaterfallTrack(
    row: WaterfallRow,
    waterfall: RunWaterfall,
    name: String,
    selected: Boolean,
    reveal: () -> Float,
    onHover: (String, Boolean) -> Unit,
    onTap: (WaterfallBar) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(AzphaltMinTouchTarget)) {
        val trackWidth = maxWidth
        // The shared axis: a faint rail the bars sit on.
        Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(2.dp).background(AzphaltLiftedInk))
        row.bars.forEach { bar ->
            val barWidth = maxOf(trackWidth * (bar.durationMillis.toFloat() / waterfall.spanMillis), MinBarWidth)
                .coerceAtMost(trackWidth)
            // A bar widened to its minimum at the axis's end stays on the track.
            val x = minOf(trackWidth * ((bar.startMillis - waterfall.startMillis).toFloat() / waterfall.spanMillis), trackWidth - barWidth)
            // The touch target is at least 48dp wide, kept inside the track; the bar is drawn within it.
            val hitWidth = maxOf(barWidth, AzphaltMinTouchTarget).coerceAtMost(trackWidth)
            val hitX = minOf(x, trackWidth - hitWidth).coerceAtLeast(0.dp)
            WaterfallBarView(
                bar = bar,
                label = barLabel(bar, name) + ". " + bar.phaseSummary() + ". " + bar.usageSummary(),
                selected = selected,
                barOffset = (x - hitX).coerceAtLeast(0.dp),
                barWidth = barWidth.coerceAtMost(hitWidth),
                modifier = Modifier.offset(x = hitX).width(hitWidth).fillMaxHeight(),
                reveal = reveal,
                onHover = { onHover(bar.key, it) },
                onTap = { onTap(bar) },
            )
        }
    }
}

@Composable
private fun WaterfallBarView(
    bar: WaterfallBar,
    label: String,
    selected: Boolean,
    barOffset: Dp,
    barWidth: Dp,
    modifier: Modifier,
    reveal: () -> Float,
    onHover: (Boolean) -> Unit,
    onTap: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val isHovered by interaction.collectIsHoveredAsState()
    LaunchedEffect(isHovered) { onHover(isHovered) }
    val outline = azphaltOnSurface()
    Box(
        modifier
            .semantics(mergeDescendants = true) {
                role = Role.Button
                this.selected = selected
                contentDescription = label
            }
            .hoverable(interaction)
            .clickable(onClick = onTap),
    ) {
        Canvas(Modifier.offset(x = barOffset).width(barWidth).fillMaxHeight()) {
            val height = BarHeight.toPx()
            val top = (size.height - height) / 2f
            val shown = size.width * reveal().coerceIn(0f, 1f)
            val span = bar.durationMillis.toFloat()
            bar.segments.forEach { segment ->
                val from = if (span <= 0f) 0f else (segment.startMillis - bar.startMillis) / span * size.width
                val to = if (span <= 0f) size.width else (segment.endMillis - bar.startMillis) / span * size.width
                val left = from.coerceAtMost(shown)
                val right = to.coerceAtMost(shown)
                if (right > left) drawPhase(segment.phase, Offset(left, top), right - left, height)
            }
            // How it ended, at its end: red for a failure, a white tick when done; nothing while going.
            val cap = when (bar.outcome) {
                WaterfallOutcome.Failed -> AzphaltDanger
                WaterfallOutcome.Completed -> Color.White
                WaterfallOutcome.Cancelled -> Azphalt.hues[10]
                null -> null
            }
            if (cap != null && shown >= size.width) {
                val capWidth = 3.dp.toPx().coerceAtMost(size.width)
                drawRect(cap, Offset(size.width - capWidth, top - 3.dp.toPx()), Size(capWidth, height + 6.dp.toPx()))
            }
            if (selected) {
                drawRoundRect(
                    color = outline,
                    topLeft = Offset(0f, top - 4.dp.toPx()),
                    size = Size(size.width, height + 8.dp.toPx()),
                    cornerRadius = CornerRadius(6.dp.toPx()),
                    style = Stroke(width = 2.dp.toPx()),
                )
            }
        }
    }
}

/** A phase's stretch: a full block, or for a gate, a thin pause line through the middle. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPhase(phase: WaterfallPhase, topLeft: Offset, width: Float, height: Float) {
    val color = waterfallPhaseColor(phase)
    if (phase == WaterfallPhase.WaitingOnYou) {
        val line = PauseHeight.toPx().coerceAtMost(height)
        drawRoundRect(color, Offset(topLeft.x, topLeft.y + (height - line) / 2f), Size(width, line), CornerRadius(line / 2f))
    } else {
        drawRoundRect(color, topLeft, Size(width, height), CornerRadius(3.dp.toPx()))
    }
}
