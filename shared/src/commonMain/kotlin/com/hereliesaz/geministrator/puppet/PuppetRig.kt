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

    /** Structural checks: format/version, unique ids, resolvable parents, no cycles. */
    fun validate() {
        require(format == FORMAT) { "Not an aive puppet rig (format=$format)." }
        require(version == VERSION) { "Unsupported puppet rig version $version." }
        require(canvas.width > 0f && canvas.height > 0f) { "Canvas must have a positive size." }
        val ids = parts.map { it.id }
        require(ids.toSet().size == ids.size) { "Duplicate part ids." }
        val byId = parts.associateBy { it.id }
        parts.forEach { part ->
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
        attachments.forEach { require(byId.containsKey(it.part)) { "Attachment ${it.id} references missing part ${it.part}." } }
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
) {
    val drawnWidth: Float get() = size?.w ?: rect?.w?.toFloat() ?: 0f
    val drawnHeight: Float get() = size?.h ?: rect?.h?.toFloat() ?: 0f
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
