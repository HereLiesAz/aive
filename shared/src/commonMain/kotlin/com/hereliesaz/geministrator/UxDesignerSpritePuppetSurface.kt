package com.hereliesaz.geministrator

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.zIndex
import com.hereliesaz.conveyance.h2g2.H2g2WorkflowState
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.ux_designer_rig_atlas
import org.jetbrains.compose.resources.painterResource
import kotlin.math.PI
import kotlin.math.sin

/**
 * Cut-and-rigged UX Designer artwork from the approved component sheet.
 *
 * Uses independently rendered soma, circular eye bases, pupils, upper/lower eyelids, tendrils,
 * and the optional heart prop. There is intentionally no mouth layer.
 */
@Composable
internal fun UxDesignerSpritePuppetSurface(
    state: H2g2WorkflowState,
    motionPhase: Float,
    modifier: Modifier = Modifier,
) {
    val puppet = MascotPuppetRig.pose(
        role = NodeCreatureRoleKind.UxDesigner,
        state = state,
        phase = motionPhase,
        antennaCount = 4,
    )
    val wave = sin(motionPhase * 2f * PI.toFloat())
    val blink = puppet.world(MascotPuppetRig.LeftEye).scaleY
    val lidScale = 0.50f + (1f - blink) * 0.50f
    val headRotation = puppet.world(MascotPuppetRig.Head).rotationDegrees
    val propRotation = puppet.world(MascotPuppetRig.Prop).rotationDegrees

    BoxWithConstraints(modifier) {
        val unit = if (maxWidth < maxHeight) maxWidth else maxHeight
        fun x(fraction: Float): Dp = maxWidth * fraction
        fun y(fraction: Float): Dp = maxHeight * fraction
        fun d(fraction: Float): Dp = unit * fraction

        Box(Modifier.matchParentSize()) {
            RigUxAtlasPart(
                source = UxAtlasRect(0, 64, 48, 96),
                modifier = Modifier
                    .offset(x(0.20f), y(0.17f))
                    .size(d(0.19f), d(0.40f))
                    .graphicsLayer {
                        rotationZ = -55f + puppet.world(MascotPuppetRig.antennaBase(0)).rotationDegrees
                        transformOrigin = TransformOrigin(0.5f, 0.88f)
                    }
                    .zIndex(0f),
            )
            RigUxAtlasPart(
                source = UxAtlasRect(48, 64, 48, 96),
                modifier = Modifier
                    .offset(x(0.58f), y(0.16f))
                    .size(d(0.18f), d(0.39f))
                    .graphicsLayer {
                        rotationZ = 50f + puppet.world(MascotPuppetRig.antennaBase(1)).rotationDegrees
                        transformOrigin = TransformOrigin(0.5f, 0.88f)
                    }
                    .zIndex(0f),
            )
            RigUxAtlasPart(
                source = UxAtlasRect(96, 64, 48, 96),
                modifier = Modifier
                    .offset(x(0.17f), y(0.48f))
                    .size(d(0.18f), d(0.40f))
                    .graphicsLayer {
                        rotationZ = -120f + puppet.world(MascotPuppetRig.antennaBase(2)).rotationDegrees
                        transformOrigin = TransformOrigin(0.5f, 0.88f)
                    }
                    .zIndex(0f),
            )
            RigUxAtlasPart(
                source = UxAtlasRect(144, 64, 48, 96),
                modifier = Modifier
                    .offset(x(0.64f), y(0.46f))
                    .size(d(0.18f), d(0.40f))
                    .graphicsLayer {
                        rotationZ = 120f + puppet.world(MascotPuppetRig.antennaBase(3)).rotationDegrees
                        transformOrigin = TransformOrigin(0.5f, 0.88f)
                    }
                    .zIndex(0f),
            )

            RigUxAtlasPart(
                source = UxAtlasRect(0, 0, 64, 64),
                modifier = Modifier
                    .offset(x(0.32f), y(0.31f))
                    .size(d(0.37f))
                    .rotate(headRotation)
                    .zIndex(1f),
            )

            RigUxAtlasPart(
                source = UxAtlasRect(64, 0, 32, 32),
                modifier = Modifier
                    .offset(x(0.385f), y(0.405f))
                    .size(d(0.105f))
                    .zIndex(2f),
            )
            RigUxAtlasPart(
                source = UxAtlasRect(96, 0, 32, 32),
                modifier = Modifier
                    .offset(x(0.505f), y(0.405f))
                    .size(d(0.105f))
                    .zIndex(2f),
            )
            RigUxAtlasPart(
                source = UxAtlasRect(128, 0, 32, 32),
                modifier = Modifier
                    .offset(x(0.405f + wave * 0.010f), y(0.425f))
                    .size(d(0.070f))
                    .zIndex(3f),
            )
            RigUxAtlasPart(
                source = UxAtlasRect(160, 0, 32, 32),
                modifier = Modifier
                    .offset(x(0.525f + wave * 0.010f), y(0.425f))
                    .size(d(0.070f))
                    .zIndex(3f),
            )

            RigUxAtlasPart(
                source = UxAtlasRect(192, 0, 32, 32),
                modifier = Modifier
                    .offset(x(0.382f), y(0.392f))
                    .size(d(0.112f), d(0.070f))
                    .graphicsLayer {
                        scaleY = lidScale
                        transformOrigin = TransformOrigin(0.5f, 0f)
                    }
                    .zIndex(4f),
            )
            RigUxAtlasPart(
                source = UxAtlasRect(192, 0, 32, 32),
                modifier = Modifier
                    .offset(x(0.502f), y(0.392f))
                    .size(d(0.112f), d(0.070f))
                    .graphicsLayer {
                        scaleY = lidScale
                        transformOrigin = TransformOrigin(0.5f, 0f)
                    }
                    .zIndex(4f),
            )
            RigUxAtlasPart(
                source = UxAtlasRect(224, 0, 32, 32),
                modifier = Modifier
                    .offset(x(0.382f), y(0.455f))
                    .size(d(0.112f), d(0.064f))
                    .graphicsLayer {
                        scaleY = lidScale
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                    .zIndex(4f),
            )
            RigUxAtlasPart(
                source = UxAtlasRect(224, 0, 32, 32),
                modifier = Modifier
                    .offset(x(0.502f), y(0.455f))
                    .size(d(0.112f), d(0.064f))
                    .graphicsLayer {
                        scaleY = lidScale
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                    .zIndex(4f),
            )

            RigUxAtlasPart(
                source = UxAtlasRect(192, 64, 64, 96),
                modifier = Modifier
                    .offset(x(0.70f), y(0.33f))
                    .size(d(0.17f), d(0.25f))
                    .rotate(propRotation + wave * 2f)
                    .alpha(if (state == H2g2WorkflowState.Blocked || state == H2g2WorkflowState.Failed) 0.55f else 1f)
                    .zIndex(5f),
            )
        }
    }
}

private data class UxAtlasRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

@Composable
private fun RigUxAtlasPart(
    source: UxAtlasRect,
    modifier: Modifier,
) {
    val atlas = painterResource(Res.drawable.ux_designer_rig_atlas)
    Canvas(modifier) {
        val scaleX = size.width / source.width.toFloat()
        val scaleY = size.height / source.height.toFloat()
        clipRect {
            withTransform({
                translate(
                    left = -source.x * scaleX,
                    top = -source.y * scaleY,
                )
            }) {
                with(atlas) {
                    draw(
                        size = androidx.compose.ui.geometry.Size(
                            width = 256f * scaleX,
                            height = 256f * scaleY,
                        ),
                    )
                }
            }
        }
    }
}
