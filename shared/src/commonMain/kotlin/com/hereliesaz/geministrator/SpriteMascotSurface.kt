package com.hereliesaz.geministrator

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.conveyance.h2g2.H2g2WorkflowState
import org.jetbrains.compose.resources.imageResource

/**
 * Draws a mascot using the pre-sliced sprite parts and [MascotPuppetRig].
 *
 * Parts are positioned and rotated by the rig so state-driven animation (bobbing, swaying,
 * waving limbs) comes through automatically. Only roles that have a sprite atlas are handled
 * here; call sites should fall back to the procedural renderer for the rest.
 *
 * Parts may reference a shared composite-sheet image with non-zero [MascotSpritePart.srcOffsetX]
 * / [MascotSpritePart.srcOffsetY]; the renderer always draws the declared sub-rectangle.
 *
 * Coordinate space: the rig operates in a 200×200 artboard. The canvas is assumed square; the
 * artboard is scaled uniformly to fit.
 */
@Composable
internal fun SpriteMascotSurface(
    role: NodeCreatureRoleKind,
    state: H2g2WorkflowState,
    motionPhase: Float,
    modifier: Modifier = Modifier,
) {
    val atlas = MascotSpriteAtlas.forRole(role) ?: return
    val pose = MascotPuppetRig.pose(
        role = role,
        state = state,
        phase = motionPhase,
        antennaCount = atlas.antennaCount,
    )

    // Load all bitmaps at composable scope (must not be inside draw lambda).
    val bodyBitmap = imageResource(atlas.body.resource)
    val propBitmap = imageResource(atlas.prop.resource)
    val legBitmap = imageResource(atlas.leg.resource)
    val antennaBitmap = imageResource(atlas.antenna.resource)

    Canvas(modifier = modifier) {
        val artboardToCanvas = size.minDimension / 200f

        fun drawPart(
            part: MascotSpritePart,
            boneId: String,
            mirrorX: Boolean = false,
        ) {
            if (!pose.skeleton.contains(boneId)) return
            val wb = pose.world(boneId)
            val displayW = part.designWidth * artboardToCanvas * wb.scaleX
            val displayH = displayW * (part.srcH.toFloat() / part.srcW.toFloat()) * wb.scaleY
            val pivotXPx = wb.pivotX * artboardToCanvas
            val pivotYPx = wb.pivotY * artboardToCanvas
            val bitmap: ImageBitmap = when (part) {
                atlas.body -> bodyBitmap
                atlas.prop -> propBitmap
                atlas.leg -> legBitmap
                atlas.antenna -> antennaBitmap
                else -> return
            }

            withTransform({
                translate(pivotXPx, pivotYPx)
                rotate(wb.rotationDegrees, Offset.Zero)
                if (mirrorX) scale(-1f, 1f, Offset.Zero)
                translate(-displayW * part.pivotNormX, -displayH * part.pivotNormY)
            }) {
                drawImage(
                    image = bitmap,
                    srcOffset = IntOffset(part.srcOffsetX, part.srcOffsetY),
                    srcSize = IntSize(part.srcW, part.srcH),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(
                        displayW.toInt().coerceAtLeast(1),
                        displayH.toInt().coerceAtLeast(1),
                    ),
                )
            }
        }

        // Antennae — drawn first (behind body)
        repeat(atlas.antennaCount) { i ->
            drawPart(atlas.antenna, MascotPuppetRig.antenna(i))
        }

        // Legs — drawn behind body
        drawPart(atlas.leg, MascotPuppetRig.LeftLegUpper, mirrorX = atlas.mirrorLeftLeg)
        drawPart(atlas.leg, MascotPuppetRig.LeftLegLower, mirrorX = atlas.mirrorLeftLeg)
        drawPart(atlas.leg, MascotPuppetRig.RightLegUpper)
        drawPart(atlas.leg, MascotPuppetRig.RightLegLower)

        // Body — main character sprite
        drawPart(atlas.body, MascotPuppetRig.Head)

        // Prop — drawn in front
        drawPart(atlas.prop, MascotPuppetRig.Prop)
    }
}
