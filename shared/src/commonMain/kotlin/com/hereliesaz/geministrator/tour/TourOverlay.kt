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
    if (TourController.active && event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
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
    val visible = state != null && !state.finished && state.phase != TourPhase.Dismissed
    if (state == null || !visible) return
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
    val step = state.current ?: return
    TourLayer(state, step)
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
    val windowBounds: Rect? = targetId?.let { TourTargetRegistry.boundsOf(it) }
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
                drawPath(dim, Color(0xB31E1A17))
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
            if (state.explainOpen) Text(plain(step.explain), style = AzphaltType.body, color = azphaltOnSurface())
            if (state.missedShown) Text(plain(step.ifMissed), style = AzphaltType.body, color = azphaltOnSurface())
            state.checkInLine?.let { line ->
                Text(plain(line), style = AzphaltType.body, color = azphaltOnSurface())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReplyPill(stringResource(Res.string.tour_reply_yes), "tour-yes", CheckInReply.Yes)
                    ReplyPill(stringResource(Res.string.tour_reply_sort_of), "tour-sort-of", CheckInReply.SortOf)
                    ReplyPill(stringResource(Res.string.tour_reply_lost), "tour-lost", CheckInReply.Lost)
                    ReplyPill(stringResource(Res.string.tour_reply_show), "tour-show", CheckInReply.ShowMe)
                }
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
                AzphaltPill(stringResource(Res.string.tour_know_this), "tour-know-this", onClick = { TourController.dispatch(TourAction.KnowThis) })
                AzphaltPill(stringResource(Res.string.tour_pause), "tour-pause", onClick = { TourController.dispatch(TourAction.Pause) })
                AzphaltPill(stringResource(Res.string.tour_stop), "tour-stop", onClick = { TourController.escape() })
            }
        }
    }
}

@Composable
private fun ReplyPill(label: String, seed: String, reply: CheckInReply) {
    AzphaltPill(label = label, seed = seed, onClick = { TourController.reply(reply) })
}

/** Control characters out and a length cap, so a model's text can never break the card; it is shown as literal text. */
internal fun plain(text: String, maxChars: Int = TeachingLimits.PAYLOAD_MAX_CHARS): String =
    text.filter { it == '\n' || !it.isISOControl() }.take(maxChars)
