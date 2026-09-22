package com.hereliesaz.geministrator

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.hereliesaz.conveyance.h2g2.H2g2WorkflowState
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.legal_counsel_rig_atlas
import org.jetbrains.compose.resources.painterResource

/**
 * Legal Counsel's rig sheet: body, eyes/lids, a single reusable arm (tube + hand cap) puppeted
 * four times, a single reusable leg puppeted twice, and four held/worn props (scale, law book,
 * pen, certificate). The scale rides the head socket; the book and pen are carried by the two
 * lower arms; the certificate floats beside the lower-right leg, undriven by any bone.
 */
@Composable
internal fun LegalCounselSpritePuppetSurface(
    state: H2g2WorkflowState,
    motionPhase: Float,
    modifier: Modifier = Modifier,
) {
    val puppet = MascotPuppetRig.pose(
        role = NodeCreatureRoleKind.LegalCounsel,
        state = state,
        phase = motionPhase,
        antennaCount = 4,
    )
    val blink = puppet.world(MascotPuppetRig.LeftEye).scaleY
    val lidScale = 0.52f + (1f - blink) * 0.48f
    val headRotation = puppet.world(MascotPuppetRig.Head).rotationDegrees
    val scaleRotation = puppet.world(MascotPuppetRig.Prop).rotationDegrees * 0.4f

    BoxWithConstraints(modifier) {
        fun x(fraction: Float): Dp = maxWidth * fraction
        fun y(fraction: Float): Dp = maxHeight * fraction
        fun d(fraction: Float): Dp = (if (maxWidth < maxHeight) maxWidth else maxHeight) * fraction

        Box(Modifier.matchParentSize()) {
            // Legs, drawn first (behind the body).
            LegalCounselLimb(
                offsetX = x(0.32f),
                offsetY = y(0.56f),
                width = d(0.135f),
                height = d(0.378f),
                rotationDegrees = puppet.world(MascotPuppetRig.LeftLegUpper).rotationDegrees,
                zIndex = 0f,
            )
            LegalCounselLimb(
                offsetX = x(0.545f),
                offsetY = y(0.56f),
                width = d(0.135f),
                height = d(0.378f),
                rotationDegrees = puppet.world(MascotPuppetRig.RightLegUpper).rotationDegrees,
                mirror = true,
                zIndex = 0f,
            )

            // The two arms nearest the scale — bare hands, nothing carried.
            LegalCounselArm(
                offsetX = x(0.30f),
                offsetY = y(0.30f),
                width = d(0.10f),
                height = d(0.30f),
                baseRotation = -132f,
                boneRotation = puppet.world(MascotPuppetRig.antennaBase(0)).rotationDegrees,
                zIndex = 0f,
            )
            LegalCounselArm(
                offsetX = x(0.60f),
                offsetY = y(0.30f),
                width = d(0.10f),
                height = d(0.30f),
                baseRotation = 132f,
                boneRotation = puppet.world(MascotPuppetRig.antennaBase(1)).rotationDegrees,
                mirror = true,
                zIndex = 0f,
            )

            // Book-carrying arm.
            LegalCounselArm(
                offsetX = x(0.24f),
                offsetY = y(0.46f),
                width = d(0.10f),
                height = d(0.30f),
                baseRotation = -172f,
                boneRotation = puppet.world(MascotPuppetRig.antennaBase(2)).rotationDegrees,
                zIndex = 0f,
                heldProp = LegalCounselAtlasRect(952, 4, 241, 302),
                propWidth = d(0.155f),
                propOffsetX = d(-0.11f),
                propOffsetY = d(0.20f),
            )

            // Pen-carrying arm.
            LegalCounselArm(
                offsetX = x(0.66f),
                offsetY = y(0.46f),
                width = d(0.10f),
                height = d(0.30f),
                baseRotation = 172f,
                boneRotation = puppet.world(MascotPuppetRig.antennaBase(3)).rotationDegrees,
                mirror = true,
                zIndex = 0f,
                heldProp = LegalCounselAtlasRect(1197, 4, 76, 281),
                propWidth = d(0.06f),
                propOffsetX = d(0.06f),
                propOffsetY = d(0.15f),
            )

            // Body.
            LegalCounselRigAtlasPart(
                source = LegalCounselAtlasRect(4, 4, 479, 472),
                modifier = Modifier
                    .offset(x(0.28f), y(0.26f))
                    .size(d(0.44f))
                    .rotate(headRotation)
                    .zIndex(1f),
            )

            // Eyes.
            LegalCounselRigAtlasPart(
                source = LegalCounselAtlasRect(527, 480, 100, 135),
                modifier = Modifier
                    .offset(x(0.385f), y(0.42f))
                    .size(d(0.075f), d(0.10f))
                    .zIndex(2f),
            )
            LegalCounselRigAtlasPart(
                source = LegalCounselAtlasRect(527, 480, 100, 135),
                modifier = Modifier
                    .offset(x(0.505f), y(0.42f))
                    .size(d(0.075f), d(0.10f))
                    .zIndex(2f),
            )
            LegalCounselRigAtlasPart(
                source = LegalCounselAtlasRect(769, 480, 89, 116),
                modifier = Modifier
                    .offset(x(0.400f), y(0.445f))
                    .size(d(0.052f), d(0.068f))
                    .zIndex(3f),
            )
            LegalCounselRigAtlasPart(
                source = LegalCounselAtlasRect(769, 480, 89, 116),
                modifier = Modifier
                    .offset(x(0.520f), y(0.445f))
                    .size(d(0.052f), d(0.068f))
                    .zIndex(3f),
            )
            LegalCounselRigAtlasPart(
                source = LegalCounselAtlasRect(862, 480, 122, 73),
                modifier = Modifier
                    .offset(x(0.382f), y(0.408f))
                    .size(d(0.082f), d(0.049f))
                    .graphicsLayer {
                        scaleY = lidScale
                        transformOrigin = TransformOrigin(0.5f, 0f)
                    }
                    .zIndex(4f),
            )
            LegalCounselRigAtlasPart(
                source = LegalCounselAtlasRect(862, 480, 122, 73),
                modifier = Modifier
                    .offset(x(0.502f), y(0.408f))
                    .size(d(0.082f), d(0.049f))
                    .graphicsLayer {
                        scaleY = lidScale
                        transformOrigin = TransformOrigin(0.5f, 0f)
                    }
                    .zIndex(4f),
            )
            LegalCounselRigAtlasPart(
                source = LegalCounselAtlasRect(988, 480, 117, 62),
                modifier = Modifier
                    .offset(x(0.382f), y(0.462f))
                    .size(d(0.082f), d(0.043f))
                    .graphicsLayer {
                        scaleY = lidScale
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                    .zIndex(4f),
            )
            LegalCounselRigAtlasPart(
                source = LegalCounselAtlasRect(988, 480, 117, 62),
                modifier = Modifier
                    .offset(x(0.502f), y(0.462f))
                    .size(d(0.082f), d(0.043f))
                    .graphicsLayer {
                        scaleY = lidScale
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                    .zIndex(4f),
            )

            // Scale, worn on the head socket.
            LegalCounselRigAtlasPart(
                source = LegalCounselAtlasRect(173, 480, 350, 262),
                modifier = Modifier
                    .offset(x(0.335f), y(0.02f))
                    .size(d(0.27f), d(0.20f))
                    .graphicsLayer {
                        rotationZ = scaleRotation
                        transformOrigin = TransformOrigin(0.5f, 1f)
                    }
                    .zIndex(5f),
            )

            // Certificate, floating beside the lower-right leg — undriven by any bone.
            LegalCounselRigAtlasPart(
                source = LegalCounselAtlasRect(652, 4, 296, 328),
                modifier = Modifier
                    .offset(x(0.63f), y(0.60f))
                    .size(d(0.22f), d(0.24f))
                    .zIndex(0f),
            )
        }
    }
}

private data class LegalCounselAtlasRect(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
)

/** A single-joint arm: tube + hand cap, optionally carrying a held prop, all rotated as one unit. */
@Composable
private fun LegalCounselArm(
    offsetX: Dp,
    offsetY: Dp,
    width: Dp,
    height: Dp,
    baseRotation: Float,
    boneRotation: Float,
    zIndex: Float,
    mirror: Boolean = false,
    heldProp: LegalCounselAtlasRect? = null,
    propWidth: Dp = 0.dp,
    propOffsetX: Dp = 0.dp,
    propOffsetY: Dp = 0.dp,
) {
    Box(
        Modifier
            .offset(offsetX, offsetY)
            .size(width, height)
            .graphicsLayer {
                rotationZ = baseRotation + boneRotation
                if (mirror) scaleX = -1f
                transformOrigin = TransformOrigin(0.5f, 0.05f)
            }
            .zIndex(zIndex),
    ) {
        LegalCounselRigAtlasPart(
            source = LegalCounselAtlasRect(4, 480, 165, 269),
            modifier = Modifier
                .offset(width * 0.18f, height * 0.28f)
                .size(width * 0.64f, height * 0.72f),
        )
        LegalCounselRigAtlasPart(
            source = LegalCounselAtlasRect(631, 480, 134, 132),
            modifier = Modifier
                .offset(width * 0.05f, 0.dp)
                .size(width * 0.90f, width * 0.90f),
        )
        heldProp?.let { prop ->
            LegalCounselRigAtlasPart(
                source = prop,
                modifier = Modifier
                    .offset(width * 0.5f + propOffsetX, height * 0.55f + propOffsetY)
                    .size(propWidth, propWidth * (prop.height.toFloat() / prop.width.toFloat())),
            )
        }
    }
}

/** A single-joint leg: the three-segment tube+foot art, reassembled as one image and rotated. */
@Composable
private fun LegalCounselLimb(
    offsetX: Dp,
    offsetY: Dp,
    width: Dp,
    height: Dp,
    rotationDegrees: Float,
    zIndex: Float,
    mirror: Boolean = false,
) {
    LegalCounselRigAtlasPart(
        source = LegalCounselAtlasRect(487, 4, 161, 451),
        modifier = Modifier
            .offset(offsetX, offsetY)
            .size(width, height)
            .graphicsLayer {
                rotationZ = rotationDegrees
                if (mirror) scaleX = -1f
                transformOrigin = TransformOrigin(0.5f, 0.05f)
            }
            .zIndex(zIndex),
    )
}

@Composable
private fun LegalCounselRigAtlasPart(
    source: LegalCounselAtlasRect,
    modifier: Modifier,
) {
    val atlas = painterResource(Res.drawable.legal_counsel_rig_atlas)
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
                            width = 1300f * scaleX,
                            height = 753f * scaleY,
                        ),
                    )
                }
            }
        }
    }
}
