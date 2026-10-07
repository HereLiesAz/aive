package com.hereliesaz.geministrator.puppet

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.hereliesaz.geministrator.resources.Res
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.jetbrains.compose.resources.decodeToImageBitmap
import kotlin.math.min

/**
 * Draws a [PuppetRig] from its sprite [atlas] at the given workflow [state] name
 * (an `H2g2WorkflowState.name`) and looping [phase].
 *
 * The rig's artboard is scaled uniformly to fit the surface and centered.
 */
@Composable
fun RiggedPuppetSurface(
    rig: PuppetRig,
    atlas: ImageBitmap,
    state: String,
    phase: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val pose = PuppetRigEvaluator.evaluate(rig, state, phase)
        val fit = min(size.width / rig.canvas.width, size.height / rig.canvas.height)
        val offsetX = (size.width - rig.canvas.width * fit) / 2f
        val offsetY = (size.height - rig.canvas.height * fit) / 2f
        pose.parts.forEach { partPose ->
            val part = partPose.part
            val rect = part.rect ?: return@forEach
            if (partPose.alpha <= 0f || rect.w <= 0 || rect.h <= 0) return@forEach
            val t = partPose.transform
            val matrix = Matrix().apply {
                values[Matrix.ScaleX] = t.a
                values[Matrix.SkewY] = t.b
                values[Matrix.SkewX] = t.c
                values[Matrix.ScaleY] = t.d
                values[Matrix.TranslateX] = t.tx
                values[Matrix.TranslateY] = t.ty
            }
            withTransform({
                translate(offsetX, offsetY)
                scale(fit, fit, pivot = androidx.compose.ui.geometry.Offset.Zero)
                transform(matrix)
                translate(partPose.boxLeft, partPose.boxTop)
                scale(part.drawnWidth / rect.w, part.drawnHeight / rect.h, pivot = androidx.compose.ui.geometry.Offset.Zero)
            }) {
                drawImage(
                    image = atlas,
                    srcOffset = IntOffset(rect.x, rect.y),
                    srcSize = IntSize(rect.w, rect.h),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(rect.w, rect.h),
                    alpha = partPose.alpha,
                )
            }
        }
    }
}

/** A rig plus its decoded atlas, loaded from compose resources. */
class LoadedPuppetRig(val rig: PuppetRig, val atlas: ImageBitmap)

/**
 * Loads `files/rigs/<role>.rig.json` and the atlas it names (`atlas.image`, resolved as
 * `files/rigs/<atlas.image>`) from compose resources. See `docs/architecture/PUPPET_RIG_FORMAT.md`.
 */
object PuppetRigResources {
    const val DIRECTORY = "files/rigs"

    @OptIn(ExperimentalResourceApi::class)
    suspend fun load(role: String): LoadedPuppetRig {
        val rig = PuppetRig.parse(Res.readBytes("$DIRECTORY/$role.rig.json").decodeToString())
        val atlas = Res.readBytes("$DIRECTORY/${rig.atlas.image}").decodeToImageBitmap()
        return LoadedPuppetRig(rig, atlas)
    }
}

/** Loads a rig from resources; null while loading or if it is missing/invalid. */
@Composable
fun rememberPuppetRig(role: String): LoadedPuppetRig? {
    var loaded by remember(role) { mutableStateOf<LoadedPuppetRig?>(null) }
    LaunchedEffect(role) {
        loaded = runCatching { PuppetRigResources.load(role) }.getOrNull()
    }
    return loaded
}
