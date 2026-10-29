package com.hereliesaz.geministrator.puppet

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Data model for the `aive-puppet-rig` format produced by `tools/puppet-rig` (the rig PWA).
 *
 * The normative description lives in `docs/architecture/PUPPET_RIG_FORMAT.md`. Summary:
 * - all positions and sizes are in **artboard pixels** (`canvas.width` x `canvas.height`),
 *   origin top-left, +x right, +y down; rotations are degrees, positive = clockwise on screen;
 * - pivots and attachment points are **normalized** (0..1) to the part's drawn box;
 * - atlas rects are integer **atlas-image pixels**.
 */
@Serializable
data class PuppetRig(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val role: String = "",
    val canvas: PuppetCanvas,
    val atlas: PuppetAtlas,
    val parts: List<PuppetPart>,
    val attachments: List<PuppetAttachment> = emptyList(),
    /** State name (exactly an `H2g2WorkflowState` name, e.g. `Active`) to its motion. */
    val states: Map<String, PuppetStateMotion> = emptyMap(),
    /** State used when the requested state has no entry. Null = rest pose. */
    val fallbackState: String? = null,
) {
    companion object {
        const val FORMAT = "aive-puppet-rig"
        const val VERSION = 1

        /** The workflow state names a rig may define, matching `H2g2WorkflowState`. */
        val STATE_NAMES = listOf("Pending", "Ready", "Active", "Gate", "Blocked", "Complete", "Failed")

        private val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

        /** Parses and validates a rig document. Throws [IllegalArgumentException] when invalid. */
        fun parse(text: String): PuppetRig {
            val rig = json.decodeFromString(serializer(), text)
            rig.validate()
            return rig
        }

        fun encode(rig: PuppetRig): String = json.encodeToString(serializer(), rig)
    }

    /**
     * Structural checks, as the editor's `validateRig` (tools/puppet-rig/rig-core.js): format and
     * version, unique ids, resolvable parents, no cycles, rects inside the atlas, finite numbers,
     * and motion only for parts, channels and states that exist.
     */
    fun validate() {
        require(format == FORMAT) { "Not an aive puppet rig (format=$format)." }
        require(version == VERSION) { "Unsupported puppet rig version $version." }
        require(canvas.width.isFinite() && canvas.height.isFinite() && canvas.width > 0f && canvas.height > 0f) {
            "Canvas must have a positive size."
        }
        require(atlas.width > 0 && atlas.height > 0) { "Atlas must have a positive size." }
        val ids = parts.map { it.id }
        require(ids.toSet().size == ids.size) { "Duplicate part ids." }
        val byId = parts.associateBy { it.id }
        val attachmentsById = attachments.associateBy { it.id }
        require(attachmentsById.size == attachments.size) { "Duplicate attachment ids." }
        parts.forEach { part ->
            require(part.kind in setOf("body", "tail", "tendril", "other")) {
                "Part ${part.id} has an unknown kind."
            }
            part.rect?.let { r ->
                require(r.x >= 0 && r.y >= 0 && r.w > 0 && r.h > 0) { "Part ${part.id} rect must be positive integers." }
                require(r.x + r.w <= atlas.width && r.y + r.h <= atlas.height) { "Part ${part.id} rect lies outside the atlas." }
            }
            val rest = part.rest
            require(
                listOf(rest.x, rest.y, rest.rotation, rest.scaleX, rest.scaleY, rest.alpha, part.z, part.pivot.x, part.pivot.y)
                    .all(Float::isFinite) && part.size.let { it == null || (it.w.isFinite() && it.h.isFinite()) },
            ) { "Part ${part.id} has a non-finite rest, pivot, size or z." }
            require(part.rigidity.isFinite() && part.rigidity in 0f..1f) {
                "Part ${part.id} rigidity must be between 0 and 1."
            }
            part.stretch?.let {
                require(it.axis in setOf("x", "y") && it.length.isFinite() && it.length > 0f) {
                    "Part ${part.id} stretch must have a valid axis and positive length."
                }
            }
            require(part.connectTo == null ||
                (part.parent != null && attachmentsById[part.connectTo]?.part == part.parent)) {
                "Part ${part.id} connection must be an attachment on its parent."
            }
            require(part.parent == null || byId.containsKey(part.parent)) {
                "Part ${part.id} references missing parent ${part.parent}."
            }
            var cursor = part.parent
            var hops = 0
            while (cursor != null) {
                require(cursor != part.id && hops <= parts.size) { "Part ${part.id} is in a parent cycle." }
                cursor = byId[cursor]?.parent
                hops++
            }
        }
        attachments.forEach {
            require(byId.containsKey(it.part)) { "Attachment ${it.id} references missing part ${it.part}." }
            require(it.x.isFinite() && it.y.isFinite()) { "Attachment ${it.id} must have finite x/y." }
        }
        states.forEach { (state, motion) ->
            require(state in STATE_NAMES) { "State \"$state\" is not an H2g2WorkflowState name." }
            motion.parts.forEach { (partId, partMotion) ->
                require(byId.containsKey(partId)) { "State $state animates missing part $partId." }
                partMotion.keys.forEach { (channel, keys) ->
                    require(channel in PuppetChannel.ALL) { "State $state/$partId: unknown channel $channel." }
                    require(keys.all { it.t.isFinite() && it.t in 0f..1f && it.v.isFinite() }) {
                        "State $state/$partId/$channel: keys need t in [0,1] and a finite v."
                    }
                }
                partMotion.sine.forEach { (channel, sine) ->
                    require(channel in PuppetChannel.ALL) { "State $state/$partId: unknown sine channel $channel." }
                    require(sine.amplitude.isFinite() && sine.frequency.isFinite() && sine.phase.isFinite()) {
                        "State $state/$partId/$channel: bad sine."
                    }
                }
            }
        }
        require(fallbackState == null || fallbackState in STATE_NAMES) { "fallbackState must be a state name." }
    }
}

@Serializable
data class PuppetCanvas(val width: Float, val height: Float)

@Serializable
data class PuppetAtlas(
    /** File name of the atlas image, resolved next to the rig (or as a drawable). */
    val image: String,
    val width: Int,
    val height: Int,
)

@Serializable
data class PuppetRect(val x: Int, val y: Int, val w: Int, val h: Int)

@Serializable
data class PuppetSize(val w: Float, val h: Float)

@Serializable
data class PuppetPoint(val x: Float, val y: Float)

/** Rest (bind) transform of a part relative to its parent's local frame. */
@Serializable
data class PuppetTransform(
    val x: Float = 0f,
    val y: Float = 0f,
    val rotation: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val alpha: Float = 1f,
)

@Serializable
data class PuppetStretch(
    /** Axis along which a tail or tendril is stretched, in part-local coordinates. */
    val axis: String,
    /** Drawn length in artboard px (without changing the source atlas crop). */
    val length: Float,
)

@Serializable
data class PuppetPart(
    val id: String,
    val parent: String? = null,
    val z: Float = 0f,
    /** Source rect in the atlas image. Null = an invisible bone (pure transform node). */
    val rect: PuppetRect? = null,
    /** Drawn size in artboard px. Defaults to the rect size. */
    val size: PuppetSize? = null,
    /** Pivot, normalized to the drawn box (0,0 = top-left, 1,1 = bottom-right). */
    val pivot: PuppetPoint = PuppetPoint(0.5f, 0.5f),
    /** Rest transform; (x, y) is where the pivot sits in the parent's local frame. */
    val rest: PuppetTransform = PuppetTransform(),
    /** Visual role of this part; existing rigs default to other. */
    val kind: String = "other",
    /** 0 = full local animation; 1 = rigid against local position/rotation/scale animation. */
    val rigidity: Float = 0f,
    /** Optional explicit length for tail/tendril geometry. */
    val stretch: PuppetStretch? = null,
    /** Optional attachment id on the immediate parent; offsets in rest remain editable. */
    val connectTo: String? = null,
) {
    val drawnWidth: Float get() =
        stretch?.takeIf { (kind == "tail" || kind == "tendril") && it.axis == "x" }?.length
            ?: size?.w ?: rect?.w?.toFloat() ?: 0f
    val drawnHeight: Float get() =
        stretch?.takeIf { (kind == "tail" || kind == "tendril") && it.axis == "y" }?.length
            ?: size?.h ?: rect?.h?.toFloat() ?: 0f
}

@Serializable
data class PuppetAttachment(
    val id: String,
    val part: String,
    /** Normalized to the part's drawn box, like [PuppetPart.pivot]. */
    val x: Float,
    val y: Float,
    /** Free-form category, e.g. `arm-socket`, `prop-slot`. */
    val kind: String = "",
)

@Serializable
data class PuppetStateMotion(
    /** Part id to that part's animated channels in this state. */
    val parts: Map<String, PuppetPartMotion> = emptyMap(),
)

@Serializable
data class PuppetPartMotion(
    /** Channel name (see [PuppetChannel]) to keyframes over phase 0..1 (looping). */
    val keys: Map<String, List<PuppetKeyframe>> = emptyMap(),
    /** Channel name to an additive sine oscillator. */
    val sine: Map<String, PuppetSine> = emptyMap(),
)

@Serializable
data class PuppetKeyframe(
    /** Normalized phase 0..1. */
    val t: Float,
    val v: Float,
    /** Easing used from this key to the next one. */
    val ease: PuppetEase = PuppetEase.Linear,
)

@Serializable
enum class PuppetEase {
    @SerialName("linear") Linear,
    @SerialName("step") Step,
    @SerialName("easeIn") EaseIn,
    @SerialName("easeOut") EaseOut,
    @SerialName("easeInOut") EaseInOut,
}

/** `amplitude * sin(2π * (frequency * phase + phase0))`; use an integer frequency for seamless loops. */
@Serializable
data class PuppetSine(
    val amplitude: Float,
    val frequency: Float = 1f,
    /** Phase offset in cycles (1.0 = one full period). */
    val phase: Float = 0f,
)

/** Animated channels. Offsets/rotation are additive deltas; scale/alpha are multiplicative factors. */
object PuppetChannel {
    const val X = "x"
    const val Y = "y"
    const val Rotation = "rotation"
    const val ScaleX = "scaleX"
    const val ScaleY = "scaleY"
    const val Alpha = "alpha"
    val ALL = listOf(X, Y, Rotation, ScaleX, ScaleY, Alpha)

    fun identity(channel: String): Float = when (channel) {
        ScaleX, ScaleY, Alpha -> 1f
        else -> 0f
    }
}
