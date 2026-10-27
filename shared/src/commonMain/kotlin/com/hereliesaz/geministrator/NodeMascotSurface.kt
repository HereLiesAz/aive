package com.hereliesaz.geministrator

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import com.hereliesaz.conveyance.h2g2.H2g2WorkflowState
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws a workflow node's creature.
 *
 * Every node is drawn from a puppet rig in `files/rigs/`: [CreatureRigMapping.slugFor] always
 * names one (core role, Store persona, executor, or a generic body for custom roles), so there is
 * no other renderer. While the rig loads the node shows a neutral soft placeholder; if the rig
 * fails to load it shows a quiet outlined placeholder instead, so the node stays visible and
 * selectable.
 */
@Composable
internal fun NodeMascotSurface(
    roleLabel: String,
    hueSeed: String,
    state: H2g2WorkflowState,
    /** The shared animation clock. Read in the draw scope (no per-frame recomposition). */
    motionPhase: () -> Float,
    modifier: Modifier = Modifier,
    /** Where its tendrils reach, in this box's px (RiggedCreatureSurface). */
    reach: () -> List<Offset> = { emptyList() },
) {
    val slug = remember(roleLabel) { CreatureRigMapping.slugFor(roleLabel) }
    val rig = rememberCreatureRig(slug)
    if (rig != null) {
        RiggedCreatureSurface(
            loaded = rig,
            state = state,
            phase = { creatureRigPhase(motionPhase(), hueSeed) },
            modifier = modifier,
            reach = reach,
        )
    } else {
        CreatureRigPlaceholder(
            failed = CreatureRigCache.hasFailed(slug),
            phase = { creatureRigPhase(motionPhase(), hueSeed) },
            modifier = modifier,
        )
    }
}

/**
 * Stand-in for a creature whose rig is loading ([failed] = false: a faint breathing blob) or could
 * not be loaded ([failed] = true: the same blob, outlined and still). Deliberately plain: it is
 * not a creature design, only a place-holder in the surface's own foreground colour.
 */
@Composable
internal fun CreatureRigPlaceholder(
    failed: Boolean,
    phase: () -> Float,
    modifier: Modifier = Modifier,
) {
    val ink = azphaltOnSurface()
    Canvas(modifier) {
        val side = min(size.width, size.height) * CREATURE_RIG_CONTENT_SCALE
        val breathe = if (failed) 0f else sin(phase() * 2f * PI.toFloat()) * 0.025f
        val radius = side * 0.32f * (1f + breathe)
        val center = Offset(size.width / 2f, size.height / 2f)
        drawCircle(ink.copy(alpha = if (failed) 0.05f else 0.08f), radius, center)
        if (failed) {
            drawCircle(ink.copy(alpha = 0.32f), radius, center, style = Stroke(width = side * 0.012f))
        }
    }
}
