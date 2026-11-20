package com.hereliesaz.geministrator.tour

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.Azphalt
import com.hereliesaz.geministrator.AzphaltMotion
import com.hereliesaz.geministrator.AzphaltPill
import com.hereliesaz.geministrator.AzphaltSurface
import com.hereliesaz.geministrator.AzphaltType
import com.hereliesaz.geministrator.PillTone
import com.hereliesaz.geministrator.PlatformBackHandler
import com.hereliesaz.geministrator.azphaltLiveRegion
import com.hereliesaz.geministrator.azphaltOnSurface
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.tour_back
import com.hereliesaz.geministrator.resources.tour_stage_withered
import com.hereliesaz.geministrator.resources.tour_stage_waiting
import com.hereliesaz.geministrator.resources.tour_stage_working
import com.hereliesaz.geministrator.resources.tour_stage_checking
import com.hereliesaz.geministrator.resources.tour_stage_retrying
import com.hereliesaz.geministrator.resources.tour_stage_needs_you
import com.hereliesaz.geministrator.resources.tour_stage_done
import com.hereliesaz.geministrator.resources.tour_stage_failed
import com.hereliesaz.geministrator.resources.tour_stage_cancelled
import com.hereliesaz.geministrator.resources.tour_hive_egg_growth
import com.hereliesaz.geministrator.resources.tour_hive_more
import com.hereliesaz.geministrator.resources.tour_failed_line
import com.hereliesaz.geministrator.resources.tour_hide
import com.hereliesaz.geministrator.resources.tour_preparing_line
import com.hereliesaz.geministrator.resources.tour_got_it
import com.hereliesaz.geministrator.resources.tour_know_this
import com.hereliesaz.geministrator.resources.tour_less
import com.hereliesaz.geministrator.resources.tour_more
import com.hereliesaz.geministrator.resources.tour_pause
import com.hereliesaz.geministrator.resources.tour_paused_line
import com.hereliesaz.geministrator.resources.tour_reply_lost
import com.hereliesaz.geministrator.resources.tour_reply_show
import com.hereliesaz.geministrator.resources.tour_reply_sort_of
import com.hereliesaz.geministrator.resources.tour_reply_yes
import com.hereliesaz.geministrator.resources.tour_resume
import com.hereliesaz.geministrator.resources.tour_state_description
import com.hereliesaz.geministrator.resources.tour_step_count
import com.hereliesaz.geministrator.resources.tour_stop
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * Escape hides the tour layer on desktop and the web. Applied to the shell's root so it works wherever focus is; it
 * only consumes Escape while a tour is showing, and never traps focus.
 */
fun Modifier.tourEscapeKey(): Modifier = onPreviewKeyEvent { event ->
    if ((TourController.active || TourController.closingOpen) && event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
        TourController.escape()
        true
    } else false
}

/**
 * The tutorial layer, drawn last at the shell root. It dims the real screen, rings one registered control, and shows
 * one plain sentence with fixed controls. It is a hint layer, not a modal: touches pass through the dimming to the real
 * controls, focus is never trapped (the card is ordinary focusable controls beside the app), and the layer never taps,
 * types or navigates for the keeper and never reads a field's value. With no tour running it draws nothing.
 */
@Composable
fun TourOverlayHost() {
    val state = TourController.state
    val preparation = TourController.preparation
    // No lesson yet: say what is happening (writing the tour, or that it could not be written) instead of drawing nothing.
    if (preparation != TourPreparation.None && (state == null || (state.lessons.isEmpty() && state.phase != TourPhase.Dismissed))) {
        PreparationLine(failed = preparation == TourPreparation.Failed)
        return
    }
    if (state == null || state.phase == TourPhase.Dismissed) return
    // After the last step the layer is gone, except while the closing question (do you feel you know what you are doing now) is open.
    val closing = state.finished && state.checkInLine != null
    if (state.finished && !closing) return
    PlatformBackHandler(enabled = true) { TourController.escape() }
    // The checkpoint allowance: after it, the "if missed" line shows once.
    LaunchedEffect(state.index, state.phase) {
        if (state.phase == TourPhase.Showing && state.current != null) {
            delay(TeachingLimits.CHECKPOINT_WAIT_MILLIS)
            TourController.dispatch(TourAction.WaitElapsed)
        }
    }
    if (state.phase == TourPhase.Paused) {
        PausedLine()
        return
    }
    if (closing) {
        ClosingCard(state.checkInLine.orEmpty())
        return
    }
    val step = state.current ?: return
    TourLayer(state, step)
}

/** The closing question on its own card: the same check-in line and the same four quick replies, with Stop to hide it. */
@Composable
private fun ClosingCard(line: String) {
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
        AzphaltSurface(Azphalt.Ink) {
            Column(
                Modifier
                    .widthIn(max = 520.dp)
                    .clip(RoundedCornerShape(19.dp))
                    .background(Azphalt.Ink)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(plain(line), style = AzphaltType.body, color = azphaltOnSurface())
                CheckInReplies()
                AzphaltPill(stringResource(Res.string.tour_stop), "tour-stop", onClick = { TourController.escape() })
            }
        }
    }
}

/**
 * One line on the step's own card about the swarm the tour sits beside: the creature the tour is on, else the first still going
 * on, its stage, and how many more there are. It rides the card instead of floating over the page, so it never covers the
 * breadcrumb, the creature count, a creature's sheet or the keeper's messages. Null when there are no creatures yet.
 */
@Composable
private fun hiveLineText(lines: List<CreatureLine>, focus: String?): String? {
    if (lines.isEmpty()) return null
    val finished = setOf(CreatureStage.Done, CreatureStage.Cancelled, CreatureStage.Withered)
    val line = lines.firstOrNull { it.id == focus } ?: lines.firstOrNull { it.stage !in finished } ?: lines.first()
    val stage = when (line.stage) {
        CreatureStage.Egg -> stringResource(Res.string.tour_hive_egg_growth, ((line.growth ?: 0f) * 100).toInt())
        CreatureStage.Withered -> stringResource(Res.string.tour_stage_withered)
        CreatureStage.Waiting -> stringResource(Res.string.tour_stage_waiting)
        CreatureStage.Working -> stringResource(Res.string.tour_stage_working)
        CreatureStage.Checking -> stringResource(Res.string.tour_stage_checking)
        CreatureStage.Retrying -> stringResource(Res.string.tour_stage_retrying)
        CreatureStage.NeedsYou -> stringResource(Res.string.tour_stage_needs_you)
        CreatureStage.Done -> stringResource(Res.string.tour_stage_done)
        CreatureStage.Failed -> stringResource(Res.string.tour_stage_failed)
        CreatureStage.Cancelled -> stringResource(Res.string.tour_stage_cancelled)
    }
    val more = if (lines.size > 1) ", " + stringResource(Res.string.tour_hive_more, lines.size - 1) else ""
    return "${plain(line.name)} · $stage$more"
}

@Composable
private fun PreparationLine(failed: Boolean) {
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomEnd) {
        AzphaltSurface(Azphalt.Ink) {
            Row(
                Modifier.clip(RoundedCornerShape(999.dp)).background(Azphalt.Ink).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    stringResource(if (failed) Res.string.tour_failed_line else Res.string.tour_preparing_line),
                    style = AzphaltType.eyebrow,
                    color = azphaltOnSurface(),
                )
                AzphaltPill(
                    label = stringResource(if (failed) Res.string.tour_hide else Res.string.tour_stop),
                    seed = "tour-prep-hide",
                    onClick = {
                        TourController.preparation = TourPreparation.None
                        if (!failed) TourController.escape()
                    },
                    tone = PillTone.Quiet,
                )
            }
        }
    }
}

@Composable
private fun PausedLine() {
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomEnd) {
        AzphaltSurface(Azphalt.Ink) {
            Row(
                Modifier.clip(RoundedCornerShape(999.dp)).background(Azphalt.Ink).padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(Res.string.tour_paused_line), style = AzphaltType.eyebrow, color = azphaltOnSurface())
                AzphaltPill(
                    label = stringResource(Res.string.tour_resume),
                    seed = "tour-resume",
                    onClick = { TourController.dispatch(TourAction.Resume) },
                    tone = PillTone.Primary,
                )
            }
        }
    }
}

@Composable
private fun TourLayer(state: TourState, step: LessonPayload) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    val targetId = TourTargetId.fromId(step.target)
    // Pointed at the hive, the ring goes to the creature the hive just acted on when it is on screen.
    val creatureBounds: Rect? = if (targetId == TourTargetId.HIVE_CREATURES) CreatureSpots.focus?.let { CreatureSpots.boundsOf(it) } else null
    val windowBounds: Rect? = creatureBounds ?: targetId?.let { TourTargetRegistry.boundsOf(it) }
    val hole = windowBounds?.translate(-origin.x, -origin.y)?.inflate(6f)
    var height by remember { mutableStateOf(0f) }
    val pulse = pulseAlpha()
    val ringColor = Azphalt.Yellow
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                origin = it.positionInWindow()
                height = it.size.height.toFloat()
            }
            // Drawing only: no pointer handling, so every touch reaches the real screen.
            .drawBehind {
                val dim = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addRect(Rect(Offset.Zero, size))
                    if (hole != null) addRoundRect(RoundRect(hole, CornerRadius(14f, 14f)))
                }
                // Nothing to point at (the target is off screen): no scrim, so the screen stays readable under the caption.
                if (hole != null) drawPath(dim, Color(0xB31E1A17))
                if (hole != null) {
                    drawRoundRect(
                        color = ringColor.copy(alpha = pulse),
                        topLeft = hole.topLeft,
                        size = Size(hole.width, hole.height),
                        cornerRadius = CornerRadius(14f, 14f),
                        style = Stroke(width = 4f),
                    )
                }
            },
        // The card sits on the half of the screen away from the ring, so the control stays clear.
        contentAlignment = if (hole != null && hole.center.y > height / 2f) Alignment.TopCenter else Alignment.BottomCenter,
    ) {
        TourCard(state, step)
    }
}

/** A still ring under reduce motion; otherwise a slow pulse. */
@Composable
private fun pulseAlpha(): Float {
    if (AzphaltMotion.reduceMotion) return 1f
    val clock = rememberInfiniteTransition(label = "tour-ring")
    val alpha by clock.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse),
        label = "tour-ring-alpha",
    )
    return alpha
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TourCard(state: TourState, step: LessonPayload) {
    val count = stringResource(Res.string.tour_step_count, state.stepNumber, state.stepTotal)
    val stateText = stringResource(Res.string.tour_state_description, state.stepNumber, state.stepTotal)
    AzphaltSurface(Azphalt.Ink) {
        Column(
            Modifier
                .padding(16.dp)
                .widthIn(max = 520.dp)
                .clip(RoundedCornerShape(19.dp))
                .background(Azphalt.Ink)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // The step is announced on its own; the buttons below stay separate, reachable controls.
            Column(
                Modifier.azphaltLiveRegion().semantics { stateDescription = stateText },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(count, style = AzphaltType.eyebrow, color = azphaltOnSurface())
                // Generated text is untrusted: plain Text only, never parsed or linked.
                Text(plain(state.note ?: step.sentence), style = AzphaltType.body, color = azphaltOnSurface())
            }
            hiveLineText(TourController.hive, CreatureSpots.focus)?.let { line ->
                Text(line, style = AzphaltType.eyebrow, color = azphaltOnSurface(), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (state.explainOpen) Text(plain(step.explain), style = AzphaltType.body, color = azphaltOnSurface())
            if (state.missedShown) Text(plain(step.ifMissed), style = AzphaltType.body, color = azphaltOnSurface())
            state.checkInLine?.let { line ->
                Text(plain(line), style = AzphaltType.body, color = azphaltOnSurface())
                CheckInReplies()
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.index > 0) {
                    AzphaltPill(stringResource(Res.string.tour_back), "tour-back", onClick = { TourController.dispatch(TourAction.Back) })
                }
                AzphaltPill(
                    label = stringResource(if (state.explainOpen) Res.string.tour_less else Res.string.tour_more),
                    seed = "tour-more",
                    onClick = { TourController.dispatch(TourAction.ToggleExplain) },
                )
                AzphaltPill(
                    label = stringResource(Res.string.tour_got_it),
                    seed = "tour-got-it",
                    onClick = { TourController.dispatch(TourAction.Next) },
                    tone = PillTone.Primary,
                )
                // Pause and "I know this" are secondary: shown with the longer explanation, so a step has three buttons, not six.
                if (state.explainOpen) {
                    AzphaltPill(stringResource(Res.string.tour_know_this), "tour-know-this", onClick = { TourController.dispatch(TourAction.KnowThis) })
                    AzphaltPill(stringResource(Res.string.tour_pause), "tour-pause", onClick = { TourController.dispatch(TourAction.Pause) })
                }
                AzphaltPill(stringResource(Res.string.tour_stop), "tour-stop", onClick = { TourController.escape() })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CheckInReplies() {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ReplyPill(stringResource(Res.string.tour_reply_yes), "tour-yes", CheckInReply.Yes)
        ReplyPill(stringResource(Res.string.tour_reply_sort_of), "tour-sort-of", CheckInReply.SortOf)
        ReplyPill(stringResource(Res.string.tour_reply_lost), "tour-lost", CheckInReply.Lost)
        ReplyPill(stringResource(Res.string.tour_reply_show), "tour-show", CheckInReply.ShowMe)
    }
}

@Composable
private fun ReplyPill(label: String, seed: String, reply: CheckInReply) {
    AzphaltPill(label = label, seed = seed, onClick = { TourController.reply(reply) })
}

/** Control characters out and a length cap, so a model's text can never break the card; it is shown as literal text. */
internal fun plain(text: String, maxChars: Int = TeachingLimits.PAYLOAD_MAX_CHARS): String =
    text.filter { it == '\n' || !it.isISOControl() }.take(maxChars)
