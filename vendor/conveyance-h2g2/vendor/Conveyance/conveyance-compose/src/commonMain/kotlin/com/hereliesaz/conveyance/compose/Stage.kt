package com.hereliesaz.conveyance.compose

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import com.hereliesaz.conveyance.Signature
import com.hereliesaz.conveyance.Verb
import com.hereliesaz.conveyance.Weight

/**
 * Where the grammar actually happens.
 *
 * Until this existed, [Signature] was a table nothing read and [com.hereliesaz.conveyance.Consequence]
 * named a target nothing travelled to — the framework declared a motion language and then rendered
 * none of it, leaving every behaviour to be hand-drawn by whatever app used it. That is the failure
 * this file exists to close: verbs are rendered here, by the framework, from the model.
 *
 * The stage is a full-window layer above the content. It has to be, because the whole point of the
 * grammar is that things travel *between* elements — from a control to its consequence, from a row
 * to the place it becomes — and anything clipped to one of those elements cannot make that journey.
 */
@Stable
class Stage internal constructor() {

    internal val flights = mutableStateListOf<Flight>()
    private var next = 0L

    /**
     * Send something across the window, according to its verb.
     *
     * The caller supplies the endpoints and the thing that travels; the signature decides what the
     * journey looks like. Nothing here takes a duration, and nothing takes a curve.
     */
    internal fun launch(
        signature: Signature,
        from: Rect,
        to: Rect,
        weight: Weight,
        token: @Composable () -> Unit,
    ) {
        flights += Flight(next++, signature, from, to, weight, token)
    }

    internal fun land(flight: Flight) {
        flights -= flight
    }
}

internal class Flight(
    val key: Long,
    val signature: Signature,
    val from: Rect,
    val to: Rect,
    val weight: Weight,
    val token: @Composable () -> Unit,
)

/**
 * One journey, in flight.
 *
 * Position and size are both interpolated, because a thing that travels without resizing has not
 * become the thing it arrived at — it has merely moved next to it. Continuity is the endpoint
 * matching, not the movement.
 */
@Composable
internal fun FlightLayer(stage: Stage, flight: Flight) {
    val progress = remember(flight.key) { Animatable(0f) }

    LaunchedEffect(flight.key) {
        progress.animateTo(1f, animationSpec = Motion.spec(flight.weight))
        stage.land(flight)
    }

    val t = progress.value
    val left = lerp(flight.from.left, flight.to.left, t)
    val top = lerp(flight.from.top, flight.to.top, t)
    val width = lerp(flight.from.width, flight.to.width, t)
    val height = lerp(flight.from.height, flight.to.height, t)

    // Send diminishes into its destination; the others arrive at full presence. This is the one
    // place a verb's ending differs, and it is why Send reads as "gone somewhere" rather than
    // "moved here".
    val fade = if (flight.signature.verb == Verb.Send) 1f - (t * t) else 1f

    Box(
        modifier = Modifier
            .layout { measurable, _ ->
                val placeable = measurable.measure(
                    Constraints.fixed(width.toInt().coerceAtLeast(1), height.toInt().coerceAtLeast(1)),
                )
                layout(placeable.width, placeable.height) {
                    placeable.place(left.toInt(), top.toInt())
                }
            }
            .graphicsLayer { this.alpha = fade },
    ) {
        flight.token()
    }
}

private fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t
