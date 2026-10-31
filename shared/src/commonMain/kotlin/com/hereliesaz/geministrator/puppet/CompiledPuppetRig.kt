package com.hereliesaz.geministrator.puppet

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot
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
    private val flexibility = FloatArray(partCount) { 1f - rig.parts[it].rigidity }
    /**
     * A part's tip, from its pivot in local px: the pivot reflected through the sprite's centre,
     * so a tendril pinned at its base reaches out to the far end.
     */
    private val tipX = FloatArray(partCount) { (1f - 2f * rig.parts[it].pivot.x) * rig.parts[it].drawnWidth }
    private val tipY = FloatArray(partCount) { (1f - 2f * rig.parts[it].pivot.y) * rig.parts[it].drawnHeight }

    /** Parts named `tendril…`: the limbs creatures join by (docs/Node-Creature-Genome.md, Wiring). */
    val tendrilParts: IntArray = rig.parts.indices.filter { rig.parts[it].id.startsWith("tendril") }.toIntArray()

    /** Tendrils and tails: they bend and stretch, never rotate ([isLimb]). */
    private val limb = BooleanArray(partCount) { rig.parts[it].isLimb }
    private val limbLength = FloatArray(partCount) { hypot(tipX[it], tipY[it]) }
    private val axisX = FloatArray(partCount) { if (limbLength[it] > 0f) tipX[it] / limbLength[it] else 1f }
    private val axisY = FloatArray(partCount) { if (limbLength[it] > 0f) tipY[it] / limbLength[it] else 0f }
    private val bent = FloatArray(3)
    private val reached = FloatArray(2)

    /** Tendril [part]'s base and tip in artboard px, from an evaluated [pose]. */
    fun tendrilBase(part: Int, pose: PuppetPoseBuffer): Offset = Offset(pose.world[part * 6 + 4], pose.world[part * 6 + 5])

    fun tendrilTip(part: Int, pose: PuppetPoseBuffer): Offset = along(part, 1f, pose)

    /** The point [at] (0 base, 1 tip, across every segment) along limb [part]'s curve, artboard px. */
    fun along(part: Int, at: Float, pose: PuppetPoseBuffer): Offset {
        val o = part * 6
        val w = pose.world
        val n = pose.segments[part]
        val length = limbLength[part]
        val total = at * n
        val copy = kotlin.math.floor(total).coerceIn(0f, (n - 1).toFloat())
        LimbBend.place(
            (total - copy) * length, 0f, axisX[part], axisY[part], length,
            if (limb[part]) pose.bend[part] else 0f, pose.stretch[part], n, copy, bent,
        )
        return Offset(w[o] * bent[0] + w[o + 2] * bent[1] + w[o + 4], w[o + 1] * bent[0] + w[o + 3] * bent[1] + w[o + 5])
    }

    private val anchorX = FloatArray(partCount)
    private val anchorY = FloatArray(partCount)

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
        val attachmentsById = rig.attachments.associateBy { it.id }
        rig.parts.forEachIndexed { i, part ->
            val parent = parentIndex[i].takeIf { it >= 0 }?.let { rig.parts[it] }
            val socket = part.connectTo?.let(attachmentsById::get)
            if (parent != null && socket?.part == parent.id) {
                anchorX[i] = (socket.x - parent.pivot.x) * parent.drawnWidth
                anchorY[i] = (socket.y - parent.pivot.y) * parent.drawnHeight
            }
        }
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
            val f = flexibility[i]
            var x = anchorX[i] + restX[i] + sample(ch, CH_X, cycle, 0f) * f
            var y = anchorY[i] + restY[i] + sample(ch, CH_Y, cycle, 0f) * f
            // A limb never rotates: its rotation channel is its bend.
            val turn = sample(ch, CH_ROT, cycle, 0f) * f
            var deg = restRot[i] + if (limb[i]) 0f else turn
            out.bend[i] = if (limb[i]) LimbBend.bendOf(turn) else 0f
            out.stretch[i] = 1f
            // On a bent limb, a child rides the curve: placed on it, turned with it.
            val p = parentIndex[i]
            if (p >= 0 && limb[p]) {
                // The parent's whole drawn arc, every segment of a reach included, as its mesh and attachments.
                LimbBend.place(
                    x * axisX[p] + y * axisY[p], -x * axisY[p] + y * axisX[p], axisX[p], axisY[p], limbLength[p],
                    out.bend[p], out.stretch[p], out.segments[p], 0f, bent,
                )
                x = bent[0]
                y = bent[1]
                deg += bent[2] / DEG
            }
            val sx = restSx[i] * (1f + (sample(ch, CH_SX, cycle, 1f) - 1f) * f)
            val sy = restSy[i] * (1f + (sample(ch, CH_SY, cycle, 1f) - 1f) * f)
            val al = restAlpha[i] * sample(ch, CH_ALPHA, cycle, 1f)
            compose(i, deg, sx, sy, x, y, al, out)
            // A reaching tendril bends from its base, which keeps its direction, until its tip meets
            // its target, and stretches along the way; too far for one sprite, it grows segments.
            val tx = out.reachX[i]
            if (!tx.isNaN() && limbLength[i] > 0f) {
                val o = i * 6
                val a = w[o]; val b = w[o + 1]; val c = w[o + 2]; val d = w[o + 3]
                val det = a * d - b * c
                if (det != 0f) {
                    val dx = tx - w[o + 4]
                    val dy = out.reachY[i] - w[o + 5]
                    val lx = (d * dx - c * dy) / det
                    val ly = (-b * dx + a * dy) / det
                    LimbBend.reach(lx, ly, axisX[i], axisY[i], reached)
                    val need = reached[1] / limbLength[i]
                    val segments = kotlin.math.ceil(need / out.maxReach).toInt().coerceIn(1, out.maxSegments)
                    out.segments[i] = segments
                    out.stretch[i] = (need / segments).coerceIn(MIN_REACH, out.maxReach)
                    out.bend[i] = reached[0]
                }
            }
        }
        for (k in 0 until attachmentCount) {
            val part = attPart[k]
            val o = part * 6
            var lx = attLocalX[k]
            var ly = attLocalY[k]
            if (limb[part]) {
                LimbBend.place(
                    lx * axisX[part] + ly * axisY[part], -lx * axisY[part] + ly * axisX[part], axisX[part], axisY[part],
                    limbLength[part], out.bend[part], out.stretch[part], out.segments[part], 0f, bent,
                )
                lx = bent[0]
                ly = bent[1]
            }
            out.attachX[k] = w[o] * lx + w[o + 2] * ly + w[o + 4]
            out.attachY[k] = w[o + 1] * lx + w[o + 3] * ly + w[o + 5]
        }
    }

    private fun compose(i: Int, deg: Float, sx: Float, sy: Float, x: Float, y: Float, al: Float, out: PuppetPoseBuffer) {
        val w = out.world
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
        /**
         * A part drawn with another rig's sprite (a bred creature's tendrils or tail, inherited from
         * another parent): the donor's atlas and part, placed where this rig's part sits. Null keeps
         * this rig's own sprite.
         */
        donor: ((Int) -> Pair<ImageBitmap, PuppetPart>?)? = null,
    ) {
        for (i in drawOrder) {
            val (sheet, sprite) = donor?.invoke(i) ?: (atlas to rig.parts[i])
            if (limb[i]) {
                for (j in pose.segments[i] - 1 downTo 0) drawLimb(scope, sheet, pose, fit, scratch, i, j, sprite)
            } else {
                drawPart(scope, sheet, pose, fit, scratch, i, 0, sprite)
            }
        }
    }

    /**
     * The tail limbs ([isLimb] of kind `tail`, or named `tail…` in rigs from before kinds): the
     * limb that carries the egg. The egg and its pods (`tail.egg`, `tail.pod.N`) are not limbs, so
     * an inherited tail never replaces them.
     */
    val tailParts: IntArray = rig.parts.indices.filter {
        val part = rig.parts[it]
        part.isLimb && (part.kind == "tail" || part.id.startsWith("tail"))
    }.toIntArray()

    /**
     * A slice of light travelling a tendril: [at] runs 0 (base) to 1 (tip, across every segment).
     * Masked to the tendril's own pixels, so it lights the limb and nothing else, with a soft glow
     * around it.
     */
    fun drawPulse(
        scope: DrawScope,
        atlas: ImageBitmap,
        pose: PuppetPoseBuffer,
        fit: PuppetFit,
        scratch: Matrix,
        part: Int,
        at: Float,
        color: Color,
        /** The tendril's sprite when it is another rig's (an inherited limb). */
        sprite: Pair<ImageBitmap, PuppetPart>? = null,
    ) = with(scope) {
        val o = part * 6
        val w = pose.world
        val n = pose.segments[part]
        val stepX = w[o] * tipX[part] + w[o + 2] * tipY[part]
        val stepY = w[o + 1] * tipX[part] + w[o + 3] * tipY[part]
        val point = along(part, at, pose)
        val centre = Offset(fit.offsetX + point.x * fit.scale, fit.offsetY + point.y * fit.scale)
        val radius = hypot(stepX, stepY) * pose.stretch[part] * fit.scale * 0.32f
        // Glow first, under the limb's lit slice.
        drawCircle(
            Brush.radialGradient(listOf(color.copy(alpha = 0.45f), color.copy(alpha = 0f)), centre, radius * 2.2f),
            radius * 2.2f,
            centre,
        )
        drawContext.canvas.saveLayer(Rect(Offset.Zero, size), Paint())
        for (j in 0 until n) {
            val sheet = sprite?.first ?: atlas
            val drawn = sprite?.second ?: rig.parts[part]
            if (limb[part]) drawLimb(this, sheet, pose, fit, scratch, part, j, drawn) else drawPart(this, sheet, pose, fit, scratch, part, j, drawn)
        }
        drawCircle(
            // An energy pulse: white-hot at its core, burning out through the knot's colour.
            Brush.radialGradient(
                0f to Color.White,
                0.3f to color,
                1f to color.copy(alpha = 0f),
                center = centre,
                radius = radius,
            ),
            radius,
            centre,
            blendMode = BlendMode.SrcIn,
        )
        drawContext.canvas.restore()
    }

    /**
     * One shader per atlas drawn: this rig's, and a bred creature's donors'. Bounded, since the
     * donors change with whichever creature wears this rig.
     */
    private val shaders = HashMap<ImageBitmap, androidx.compose.ui.graphics.Shader>()
    private val meshScratch = LimbMeshScratch()

    /**
     * Copy [j] of limb [i], bent and stretched along its curve ([drawLimbMesh]); a limb with no axis
     * to bend along (a centred pivot) is drawn whole, like any other part, rather than not at all.
     */
    private fun drawLimb(
        scope: DrawScope,
        atlas: ImageBitmap,
        pose: PuppetPoseBuffer,
        fit: PuppetFit,
        scratch: Matrix,
        i: Int,
        j: Int,
        sprite: PuppetPart,
    ) {
        if (atlas !in shaders && shaders.size >= MAX_SHADERS) shaders.clear()
        val shader = shaders.getOrPut(atlas) { androidx.compose.ui.graphics.ImageShader(atlas) }
        val drawn = scope.drawLimbMesh(
            atlas = atlas,
            sprite = sprite,
            part = rig.parts[i],
            worldArray = pose.world,
            offset = i * 6,
            fit = fit,
            alpha = pose.alpha[i],
            bendDeg = pose.bend[i],
            stretch = pose.stretch[i],
            segments = pose.segments[i],
            shift = j.toFloat(),
            shader = shader,
            scratch = meshScratch,
        )
        if (!drawn) drawPart(scope, atlas, pose, fit, scratch, i, j, sprite)
    }

    private fun drawPart(
        scope: DrawScope,
        atlas: ImageBitmap,
        pose: PuppetPoseBuffer,
        fit: PuppetFit,
        scratch: Matrix,
        i: Int,
        j: Int,
        /** The sprite to draw at part [i]'s place; another rig's for an inherited limb. */
        part: PuppetPart = rig.parts[i],
    ) = with(scope) {
        val w = pose.world
        val rect = part.rect ?: return@with
        val alpha = pose.alpha[i]
        if (alpha <= 0f || rect.w <= 0 || rect.h <= 0) return@with
        val o = i * 6
        // Segment j is the same sprite moved out along the tendril by j tip-lengths.
        val stepX = w[o] * tipX[i] + w[o + 2] * tipY[i]
        val stepY = w[o + 1] * tipX[i] + w[o + 3] * tipY[i]
        scratch.reset()
        val v = scratch.values
        v[Matrix.ScaleX] = w[o]
        v[Matrix.SkewY] = w[o + 1]
        v[Matrix.SkewX] = w[o + 2]
        v[Matrix.ScaleY] = w[o + 3]
        v[Matrix.TranslateX] = w[o + 4] + stepX * j
        v[Matrix.TranslateY] = w[o + 5] + stepY * j
        // An inherited limb points the way this rig's own limb did: turn it from its own reach onto ours.
        val turn = if (part === rig.parts[i]) {
            0f
        } else {
            val theirs = atan2((1f - 2f * part.pivot.y) * part.drawnHeight, (1f - 2f * part.pivot.x) * part.drawnWidth)
            (atan2(tipY[i], tipX[i]) - theirs) / DEG
        }
        withTransform({
            translate(fit.offsetX, fit.offsetY)
            scale(fit.scale, fit.scale, pivot = Offset.Zero)
            transform(scratch)
            if (turn != 0f) rotate(turn, pivot = Offset.Zero)
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

    companion object {
        private const val DEG = (PI / 180.0).toFloat()
        /** A tendril curls shorter than its sprite down to this, no further. */
        private const val MIN_REACH = 0.7f
        /** Atlases a rig keeps shaders for: its own and a bred creature's two donors, with room. */
        private const val MAX_SHADERS = 4
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

    /** Per part, an artboard point a tendril reaches for; NaN leaves the part to its motion. */
    val reachX = FloatArray(partCount) { Float.NaN }
    val reachY = FloatArray(partCount) { Float.NaN }

    /** How far each tendril segment may stretch past its sprite: a little, not much. */
    var maxReach = 1.25f

    /** Most segments a tendril grows to reach. */
    var maxSegments = 3

    /** Per part, how many copies of the sprite it draws end to end; 1 unless reaching far. */
    val segments = IntArray(partCount) { 1 }

    /** Per limb, how far it curves base to tip (degrees, positive clockwise); 0 for other parts. */
    val bend = FloatArray(partCount)

    /** Per limb, how much longer than its sprite each segment is drawn along its curve. */
    val stretch = FloatArray(partCount) { 1f }

    fun clearReach() {
        reachX.fill(Float.NaN)
        reachY.fill(Float.NaN)
        segments.fill(1)
    }
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
