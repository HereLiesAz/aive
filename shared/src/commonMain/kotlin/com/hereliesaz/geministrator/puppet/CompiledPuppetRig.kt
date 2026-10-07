package com.hereliesaz.geministrator.puppet

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * A [PuppetRig] pre-processed for per-frame evaluation without allocation.
 *
 * Produces the same transforms as [PuppetRigEvaluator] (see `CompiledPuppetRigTest`), but resolves
 * parent order, draw order, state lookup and sorted keyframes once, and writes into a reusable
 * [PuppetPoseBuffer] instead of building maps and lists each frame. Use it for live rendering of
 * many nodes; keep [PuppetRigEvaluator] as the readable reference.
 */
class CompiledPuppetRig(val rig: PuppetRig) {
    val partCount: Int = rig.parts.size
    val attachmentCount: Int = rig.attachments.size

    /** Part indices with every parent before its children. */
    private val evalOrder: IntArray
    private val parentIndex: IntArray

    /** Part indices sorted by z, ties by declaration order (same as [PuppetPose.parts]). */
    val drawOrder: IntArray

    private val restX = FloatArray(partCount) { rig.parts[it].rest.x }
    private val restY = FloatArray(partCount) { rig.parts[it].rest.y }
    private val restRot = FloatArray(partCount) { rig.parts[it].rest.rotation }
    private val restSx = FloatArray(partCount) { rig.parts[it].rest.scaleX }
    private val restSy = FloatArray(partCount) { rig.parts[it].rest.scaleY }
    private val restAlpha = FloatArray(partCount) { rig.parts[it].rest.alpha }

    /** Per state (index into [stateNames]), per part, per channel ([CHANNELS] order): motion or null. */
    private val stateNames: List<String> = rig.states.keys.toList()
    private val motions: Array<Array<Array<CompiledChannel?>?>>
    private val fallbackIndex: Int

    private val attPart: IntArray
    private val attLocalX: FloatArray
    private val attLocalY: FloatArray
    val attachmentIds: List<String> = rig.attachments.map { it.id }
    val attachmentKinds: List<String> = rig.attachments.map { it.kind }

    init {
        val idToIndex = HashMap<String, Int>()
        rig.parts.forEachIndexed { i, p -> idToIndex[p.id] = i }
        parentIndex = IntArray(partCount) { i -> rig.parts[i].parent?.let { idToIndex[it] } ?: -1 }
        val order = ArrayList<Int>(partCount)
        val visited = BooleanArray(partCount)
        fun visit(i: Int) {
            if (visited[i]) return
            visited[i] = true
            val p = parentIndex[i]
            if (p >= 0) visit(p)
            order += i
        }
        for (i in 0 until partCount) visit(i)
        evalOrder = order.toIntArray()
        drawOrder = (0 until partCount).sortedWith(compareBy({ rig.parts[it].z }, { it })).toIntArray()

        motions = Array(stateNames.size) { s ->
            val motion = rig.states.getValue(stateNames[s])
            Array(partCount) { i ->
                val pm = motion.parts[rig.parts[i].id] ?: return@Array null
                Array(CHANNELS.size) { c -> CompiledChannel.of(pm, CHANNELS[c]) }
            }
        }
        fallbackIndex = rig.fallbackState?.let { stateNames.indexOf(it) } ?: -1

        attPart = IntArray(attachmentCount) { idToIndex.getValue(rig.attachments[it].part) }
        attLocalX = FloatArray(attachmentCount) {
            val a = rig.attachments[it]
            val p = rig.parts[attPart[it]]
            (a.x - p.pivot.x) * p.drawnWidth
        }
        attLocalY = FloatArray(attachmentCount) {
            val a = rig.attachments[it]
            val p = rig.parts[attPart[it]]
            (a.y - p.pivot.y) * p.drawnHeight
        }
    }

    /** Index of the state that will drive [state] (after fallback), or -1 for rest pose. */
    fun stateIndex(state: String): Int {
        val direct = stateNames.indexOf(state)
        return if (direct >= 0) direct else fallbackIndex
    }

    fun stateName(index: Int): String? = stateNames.getOrNull(index)

    fun newBuffer(): PuppetPoseBuffer = PuppetPoseBuffer(partCount, attachmentCount)

    /** Evaluates [stateIndex] (from [stateIndex]) at looping [phase] into [out]. Allocation-free. */
    fun evaluate(stateIndex: Int, phase: Float, out: PuppetPoseBuffer) {
        val cycle = phase - floor(phase)
        val motion = if (stateIndex >= 0) motions[stateIndex] else null
        val w = out.world
        for (i in evalOrder) {
            val ch = motion?.get(i)
            val x = restX[i] + sample(ch, CH_X, cycle, 0f)
            val y = restY[i] + sample(ch, CH_Y, cycle, 0f)
            val deg = restRot[i] + sample(ch, CH_ROT, cycle, 0f)
            val sx = restSx[i] * sample(ch, CH_SX, cycle, 1f)
            val sy = restSy[i] * sample(ch, CH_SY, cycle, 1f)
            val al = restAlpha[i] * sample(ch, CH_ALPHA, cycle, 1f)
            val r = deg * DEG
            val cs = cos(r)
            val sn = sin(r)
            // Local affine: T * R * S.
            val la = cs * sx
            val lb = sn * sx
            val lc = -sn * sy
            val ld = cs * sy
            val o = i * 6
            val p = parentIndex[i]
            if (p < 0) {
                w[o] = la; w[o + 1] = lb; w[o + 2] = lc; w[o + 3] = ld; w[o + 4] = x; w[o + 5] = y
                out.alpha[i] = al.coerceIn(0f, 1f)
            } else {
                val q = p * 6
                val pa = w[q]; val pb = w[q + 1]; val pc = w[q + 2]; val pd = w[q + 3]
                val ptx = w[q + 4]; val pty = w[q + 5]
                w[o] = pa * la + pc * lb
                w[o + 1] = pb * la + pd * lb
                w[o + 2] = pa * lc + pc * ld
                w[o + 3] = pb * lc + pd * ld
                w[o + 4] = pa * x + pc * y + ptx
                w[o + 5] = pb * x + pd * y + pty
                out.alpha[i] = (out.alpha[p] * al).coerceIn(0f, 1f)
            }
        }
        for (k in 0 until attachmentCount) {
            val o = attPart[k] * 6
            val lx = attLocalX[k]
            val ly = attLocalY[k]
            out.attachX[k] = w[o] * lx + w[o + 2] * ly + w[o + 4]
            out.attachY[k] = w[o + 1] * lx + w[o + 3] * ly + w[o + 5]
        }
    }

    private fun sample(ch: Array<CompiledChannel?>?, channel: Int, cycle: Float, identity: Float): Float {
        val c = ch?.get(channel) ?: return identity
        return c.value(cycle, identity)
    }

    /**
     * Draws the evaluated [pose], mapping artboard px into the draw scope with [fit].
     * [scratch] is a reusable matrix.
     */
    fun draw(
        scope: DrawScope,
        atlas: ImageBitmap,
        pose: PuppetPoseBuffer,
        fit: PuppetFit,
        scratch: Matrix,
    ) = with(scope) {
        val w = pose.world
        for (i in drawOrder) {
            val part = rig.parts[i]
            val rect = part.rect ?: continue
            val alpha = pose.alpha[i]
            if (alpha <= 0f || rect.w <= 0 || rect.h <= 0) continue
            val o = i * 6
            scratch.reset()
            val v = scratch.values
            v[Matrix.ScaleX] = w[o]
            v[Matrix.SkewY] = w[o + 1]
            v[Matrix.SkewX] = w[o + 2]
            v[Matrix.ScaleY] = w[o + 3]
            v[Matrix.TranslateX] = w[o + 4]
            v[Matrix.TranslateY] = w[o + 5]
            withTransform({
                translate(fit.offsetX, fit.offsetY)
                scale(fit.scale, fit.scale, pivot = Offset.Zero)
                transform(scratch)
                translate(-part.pivot.x * part.drawnWidth, -part.pivot.y * part.drawnHeight)
                scale(part.drawnWidth / rect.w, part.drawnHeight / rect.h, pivot = Offset.Zero)
            }) {
                drawImage(
                    image = atlas,
                    srcOffset = IntOffset(rect.x, rect.y),
                    srcSize = IntSize(rect.w, rect.h),
                    dstOffset = IntOffset.Zero,
                    dstSize = IntSize(rect.w, rect.h),
                    alpha = alpha,
                )
            }
        }
    }

    companion object {
        private const val DEG = (PI / 180.0).toFloat()
        private val CHANNELS = listOf(
            PuppetChannel.X, PuppetChannel.Y, PuppetChannel.Rotation,
            PuppetChannel.ScaleX, PuppetChannel.ScaleY, PuppetChannel.Alpha,
        )
        private const val CH_X = 0
        private const val CH_Y = 1
        private const val CH_ROT = 2
        private const val CH_SX = 3
        private const val CH_SY = 4
        private const val CH_ALPHA = 5
    }
}

/** Reusable output of [CompiledPuppetRig.evaluate]: world affines `[a b c d tx ty]` per part, alpha, attachments. */
class PuppetPoseBuffer(partCount: Int, attachmentCount: Int) {
    val world = FloatArray(partCount * 6)
    val alpha = FloatArray(partCount)
    val attachX = FloatArray(attachmentCount)
    val attachY = FloatArray(attachmentCount)
}

/**
 * Uniform fit of a rig artboard into a square-ish box: artboard point `p` lands at
 * `offset + p * scale` in box px. [contentScale] < 1 leaves margin around the creature.
 */
data class PuppetFit(val scale: Float, val offsetX: Float, val offsetY: Float) {
    fun mapX(x: Float): Float = offsetX + x * scale
    fun mapY(y: Float): Float = offsetY + y * scale

    companion object {
        fun of(canvas: PuppetCanvas, boxWidth: Float, boxHeight: Float, contentScale: Float = 1f): PuppetFit {
            val s = min(boxWidth / canvas.width, boxHeight / canvas.height) * contentScale
            return PuppetFit(s, (boxWidth - canvas.width * s) / 2f, (boxHeight - canvas.height * s) / 2f)
        }
    }
}

private class CompiledChannel(
    private val keyT: FloatArray,
    private val keyV: FloatArray,
    private val keyEase: Array<PuppetEase>,
    private val sine: PuppetSine?,
) {
    fun value(cycle: Float, identity: Float): Float {
        val keyed = if (keyT.isEmpty()) identity else sampleKeys(cycle)
        val s = sine ?: return keyed
        return keyed + s.amplitude * sin(2f * PI.toFloat() * (s.frequency * cycle + s.phase))
    }

    /** Same segment selection as [PuppetRigEvaluator.sampleKeys], on pre-sorted arrays. */
    private fun sampleKeys(cycle: Float): Float {
        val n = keyT.size
        if (n == 1) return keyV[0]
        var from = n - 1
        var to = 0
        var fromT = keyT[n - 1] - 1f
        var toT = keyT[0]
        for (i in 0 until n) {
            val nextT = if (i + 1 < n) keyT[i + 1] else keyT[0] + 1f
            if (cycle >= keyT[i] && cycle < nextT) {
                from = i; to = (i + 1) % n; fromT = keyT[i]; toT = nextT
                break
            }
        }
        val span = toT - fromT
        val u = if (span <= 0f) 0f else ((cycle - fromT) / span).coerceIn(0f, 1f)
        return keyV[from] + (keyV[to] - keyV[from]) * PuppetRigEvaluator.ease(keyEase[from], u)
    }

    companion object {
        fun of(motion: PuppetPartMotion, channel: String): CompiledChannel? {
            val keys = motion.keys[channel]?.takeIf { it.isNotEmpty() }?.sortedBy { it.t }
            val sine = motion.sine[channel]
            if (keys == null && sine == null) return null
            return CompiledChannel(
                keyT = FloatArray(keys?.size ?: 0) { keys!![it].t },
                keyV = FloatArray(keys?.size ?: 0) { keys!![it].v },
                keyEase = Array(keys?.size ?: 0) { keys!![it].ease },
                sine = sine,
            )
        }
    }
}
