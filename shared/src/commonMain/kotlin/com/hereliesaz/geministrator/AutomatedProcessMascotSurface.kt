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
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.mascot_automated_process_body
import com.hereliesaz.geministrator.resources.mascot_automated_process_limb_1
import com.hereliesaz.geministrator.resources.mascot_automated_process_limb_2
import com.hereliesaz.geministrator.resources.mascot_automated_process_limb_3
import com.hereliesaz.geministrator.resources.mascot_automated_process_tendrils
import org.jetbrains.compose.resources.imageResource

/**
 * The node creature for workflow steps that run without an agent — a deterministic script, test
 * runner, repository operation, deployment or nested workflow. A neuron stands in for the agent
 * mascot family: the body and tendril cluster are pinned together at the root, and a 3-segment
 * "gestation limb" hangs off it, wagging with [MascotPuppetRig.Limb0]/[Limb1]/[Limb2] to signal
 * live automated activity without pretending a person is driving it.
 */
@Composable
internal fun AutomatedProcessMascotSurface(
    state: H2g2WorkflowState,
    motionPhase: Float,
    modifier: Modifier = Modifier,
) {
    val pose = MascotPuppetRig.pose(
        role = NodeCreatureRoleKind.AutomatedProcess,
        state = state,
        phase = motionPhase,
        antennaCount = 1,
    )

    val bodyBitmap = imageResource(Res.drawable.mascot_automated_process_body)
    val tendrilsBitmap = imageResource(Res.drawable.mascot_automated_process_tendrils)
    val limb1Bitmap = imageResource(Res.drawable.mascot_automated_process_limb_1)
    val limb2Bitmap = imageResource(Res.drawable.mascot_automated_process_limb_2)
    val limb3Bitmap = imageResource(Res.drawable.mascot_automated_process_limb_3)

    Canvas(modifier = modifier) {
        val artboardToCanvas = size.minDimension / 200f

        fun drawPart(
            bitmap: ImageBitmap,
            boneId: String,
            designWidth: Float,
            srcW: Int,
            srcH: Int,
            pivotNormX: Float,
            pivotNormY: Float,
        ) {
            val wb = pose.world(boneId)
            val displayW = designWidth * artboardToCanvas * wb.scaleX
            val displayH = displayW * (srcH.toFloat() / srcW.toFloat()) * wb.scaleY
            val pivotXPx = wb.pivotX * artboardToCanvas
            val pivotYPx = wb.pivotY * artboardToCanvas

            withTransform({
                translate(pivotXPx, pivotYPx)
                rotate(wb.rotationDegrees, Offset.Zero)
                translate(-displayW * pivotNormX, -displayH * pivotNormY)
            }) {
                drawImage(
                    image = bitmap,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(srcW, srcH),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(
                        displayW.toInt().coerceAtLeast(1),
                        displayH.toInt().coerceAtLeast(1),
                    ),
                )
            }
        }

        // Gestation limb, proximal-to-distal so each later segment draws over the previous joint.
        drawPart(
            bitmap = limb1Bitmap,
            boneId = MascotPuppetRig.Limb0,
            designWidth = 35.6f,
            srcW = 191,
            srcH = 175,
            pivotNormX = 0.032f,
            pivotNormY = 0.052f,
        )
        drawPart(
            bitmap = limb2Bitmap,
            boneId = MascotPuppetRig.Limb1,
            designWidth = 23.4f,
            srcW = 126,
            srcH = 141,
            pivotNormX = 0.128f,
            pivotNormY = 0.114f,
        )
        drawPart(
            bitmap = limb3Bitmap,
            boneId = MascotPuppetRig.Limb2,
            designWidth = 63f,
            srcW = 337,
            srcH = 334,
            pivotNormX = 0.036f,
            pivotNormY = 0.306f,
        )

        // Tendril cluster sits behind the body, pinned to the same root pivot.
        drawPart(
            bitmap = tendrilsBitmap,
            boneId = MascotPuppetRig.Head,
            designWidth = 182.8f,
            srcW = 976,
            srcH = 968,
            pivotNormX = 0.484f,
            pivotNormY = 0.532f,
        )

        // Body — drawn last, on top of the limb root and tendril bases.
        drawPart(
            bitmap = bodyBitmap,
            boneId = MascotPuppetRig.Head,
            designWidth = 72f,
            srcW = 385,
            srcH = 415,
            pivotNormX = 0.520f,
            pivotNormY = 0.569f,
        )
    }
}
