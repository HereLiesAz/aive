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
import com.hereliesaz.geministrator.resources.orchestrator_rig_atlas
import org.jetbrains.compose.resources.painterResource
import kotlin.math.sin
import kotlin.math.PI

/**
 * First production sprite-puppet cut from the approved rigging sheet.
 *
 * The atlas contains independently cut artwork for the soma, eye whites, pupils, upper/lower lids,
 * four tendril pieces and the optional role prop. This surface deliberately keeps the pieces as
 * separate render layers so the puppet rig can animate them without redrawing the character.
 */
@Composable
internal fun OrchestratorSpritePuppetSurface(
    state: H2g2WorkflowState,
    motionPhase: Float,
    modifier: Modifier = Modifier,
) {
    val puppet = MascotPuppetRig.pose(
        role = NodeCreatureRoleKind.Orchestrator,
        state = state,
        phase = motionPhase,
        antennaCount = 4,
    )
    val wave = sin(motionPhase * 2f * PI.toFloat())
    val blink = puppet.world(MascotPuppetRig.LeftEye).scaleY
    val lidScale = 0.52f + (1f - blink) * 0.48f
    val headRotation = puppet.world(MascotPuppetRig.Head).rotationDegrees
    val propRotation = puppet.world(MascotPuppetRig.Prop).rotationDegrees

    BoxWithConstraints(modifier) {
        val unit = if (maxWidth < maxHeight) maxWidth else maxHeight

        fun x(fraction: Float): Dp = maxWidth * fraction
        fun y(fraction: Float): Dp = maxHeight * fraction
        fun d(fraction: Float): Dp = unit * fraction

        Box(Modifier.matchParentSize()) {
            // Tendrils sit behind the soma and each receives its own bone rotation.
            RigAtlasPart(
                source = AtlasRect(0, 64, 48, 96),
                modifier = Modifier
                    .offset(x(0.22f), y(0.17f))
                    .size(d(0.21f), d(0.42f))
                    .graphicsLayer {
                        rotationZ = -48f + puppet.world(MascotPuppetRig.antennaBase(0)).rotationDegrees
                        transformOrigin = TransformOrigin(0.5f, 0.88f)
                    }
                    .zIndex(0f),
            )
            RigAtlasPart(
                source = AtlasRect(48, 64, 48, 96),
                modifier = Modifier
                    .offset(x(0.55f), y(0.14f))
                    .size(d(0.19f), d(0.41f))
                    .graphicsLayer {
                        rotationZ = 42f + puppet.world(MascotPuppetRig.antennaBase(1)).rotationDegrees
                        transformOrigin = TransformOrigin(0.5f, 0.88f)
                    }
                    .zIndex(0f),
            )
            RigAtlasPart(
                source = AtlasRect(96, 64, 48, 96),
                modifier = Modifier
                    .offset(x(0.17f), y(0.48f))
                    .size(d(0.19f), d(0.40f))
                    .graphicsLayer {
                        rotationZ = -118f + puppet.world(MascotPuppetRig.antennaBase(2)).rotationDegrees
                        transformOrigin = TransformOrigin(0.5f, 0.88f)
                    }
                    .zIndex(0f),
            )
            RigAtlasPart(
                source = AtlasRect(144, 64, 48, 96),
                modifier = Modifier
                    .offset(x(0.62f), y(0.47f))
                    .size(d(0.18f), d(0.40f))
                    .graphicsLayer {
                        rotationZ = 116f + puppet.world(MascotPuppetRig.antennaBase(3)).rotationDegrees
                        transformOrigin = TransformOrigin(0.5f, 0.88f)
                    }
                    .zIndex(0f),
            )

            // Soma.
            RigAtlasPart(
                source = AtlasRect(0, 0, 64, 64),
                modifier = Modifier
                    .offset(x(0.32f), y(0.31f))
                    .size(d(0.37f))
                    .rotate(headRotation)
                    .zIndex(1f),
            )

            // Circular eye bases, with pupils on their own layers.
            RigAtlasPart(
                source = AtlasRect(64, 0, 32, 32),
                modifier = Modifier
                    .offset(x(0.385f), y(0.405f))
                    .size(d(0.105f))
                    .zIndex(2f),
            )
            RigAtlasPart(
                source = AtlasRect(96, 0, 32, 32),
                modifier = Modifier
                    .offset(x(0.505f), y(0.405f))
                    .size(d(0.105f))
                    .zIndex(2f),
            )
            RigAtlasPart(
                source = AtlasRect(128, 0, 32, 32),
                modifier = Modifier
                    .offset(x(0.405f + wave * 0.008f), y(0.425f))
                    .size(d(0.070f))
                    .zIndex(3f),
            )
            RigAtlasPart(
                source = AtlasRect(160, 0, 32, 32),
                modifier = Modifier
                    .offset(x(0.525f + wave * 0.008f), y(0.425f))
                    .size(d(0.070f))
                    .zIndex(3f),
            )

            // Independent upper and lower eyelid layers. They remain visible even when fully open.
            RigAtlasPart(
                source = AtlasRect(192, 0, 32, 32),
                modifier = Modifier
                    .offset(x(0.382f), y(0.392f))
                    .size(d(0.112f), d(0.070f))
                    .graphicsLayer {
                        scaleY = lidScale
                        transformOrigin = TransformOrigin(0.5f, 0f)
                    }
                    .zIndex(4f),
            )
            RigAtlasPart(
                source = AtlasRect(192, 0, 32, 32),
                modifier = Modifier
                    .offset(x(0.502f), y(0.392f))
                    .size(d(0.112f), d(0.070f))
                    .graphicsLayer {
                        scaleY = lidScale
                        transformOrigin = TransformOrigin(0.5f, 0f)
                    }
                    .zIndex(4f),
            )
            RigAtlasPart(
                source = AtlasRect(224, 0, 32, 32),
                modifier = Modifier
                    .offset(x(0.382f), y(0.455f))
                    .size(d(0.112f), d(0.064f))
                    .graphicsLayer {
                        scaleY = lidScale
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                    .zIndex(4f),
            )
            RigAtlasPart(
                source = AtlasRect(224, 0, 32, 32),
                modifier = Modifier
                    .offset(x(0.502f), y(0.455f))
                    .size(d(0.112f), d(0.064f))
                    .graphicsLayer {
                        scaleY = lidScale
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                    .zIndex(4f),
            )

            // Optional decorative role prop. It is deliberately not part of graph topology.
            RigAtlasPart(
                source = AtlasRect(192, 64, 64, 96),
                modifier = Modifier
                    .offset(x(0.70f), y(0.36f))
                    .size(d(0.16f), d(0.24f))
                    .rotate(propRotation)
                    .alpha(if (state == H2g2WorkflowState.Blocked || state == H2g2WorkflowState.Failed) 0.55f else 1f)
                    .zIndex(5f),
            )
        }
    }
}

private data class AtlasRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

@Composable
private fun RigAtlasPart(
    source: AtlasRect,
    modifier: Modifier,
) {
    val atlas = painterResource(Res.drawable.orchestrator_rig_atlas)
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
