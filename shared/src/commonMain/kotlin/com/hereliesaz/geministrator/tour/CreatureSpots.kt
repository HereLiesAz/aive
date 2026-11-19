package com.hereliesaz.geministrator.tour

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

/**
 * Where each creature (or egg) of the hive on screen is, by its task id, and which one the tour is looking at. A creature is not a
 * fixed control, so it is not a [TourTargetId]: the hive's tissue is, and while a tour points there the ring goes to [focus] when it is on screen.
 */
object CreatureSpots {
    private val bounds = mutableStateMapOf<String, Rect>()

    /** The creature the hive just acted on (hatched, finished, needs the keeper), or null. */
    var focus: String? by mutableStateOf(null)

    fun boundsOf(taskId: String): Rect? = bounds[taskId]

    internal fun register(taskId: String, rect: Rect) {
        if (bounds[taskId] != rect) bounds[taskId] = rect
    }

    internal fun unregister(taskId: String) {
        bounds.remove(taskId)
    }
}

/** Reports where one creature or egg is, for the tour's ring. Invisible: it draws nothing, changes no layout and takes no touch. */
fun Modifier.tourCreature(taskId: String): Modifier = composed {
    DisposableEffect(taskId) { onDispose { CreatureSpots.unregister(taskId) } }
    Modifier.onGloballyPositioned { CreatureSpots.register(taskId, it.boundsInWindow()) }
}

private val acted = setOf(
    CreatureStage.Working, CreatureStage.Checking, CreatureStage.Retrying, CreatureStage.NeedsYou, CreatureStage.Done, CreatureStage.Failed,
)

/**
 * The creature to look at after the hive moved from [before] to [after] (pure): the first one whose stage changed into something it
 * did or needs, else [current] while it is still listed, else the first one working.
 */
fun spotlightFor(before: List<CreatureLine>, after: List<CreatureLine>, current: String?): String? {
    val was = before.associateBy { it.id }
    after.firstOrNull { line -> line.stage in acted && was[line.id]?.stage != line.stage }?.let { return it.id }
    if (current != null && after.any { it.id == current }) return current
    return after.firstOrNull { it.stage == CreatureStage.Working }?.id
}
