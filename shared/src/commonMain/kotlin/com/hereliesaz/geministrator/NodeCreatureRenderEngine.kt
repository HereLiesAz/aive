package com.hereliesaz.geministrator

internal enum class NodeCreatureActivity(val abiCode: Int) {
    Queued(0),
    Ready(1),
    Active(2),
    Blocked(3),
    Failed(4),
    Complete(5),
    Gate(6),
}

internal data class NodeCreatureCamera(
    val yaw: Float = -0.18f,
    val pitch: Float = 0.10f,
    val zoom: Float = 0.33f,
)

internal data class NodeCreatureRenderRequest(
    val roleLabel: String,
    val identitySeed: String,
    val activity: NodeCreatureActivity,
    val timeSeconds: Float,
    val camera: NodeCreatureCamera = NodeCreatureCamera(),
)

/**
 * Platform bridge into the Rust node-creature renderer.
 *
 * Implementations return the exact versioned HNCR packet emitted by Rust. The shared UI decodes and
 * paints that packet but never recreates creature anatomy, animation, projection or lighting.
 */
internal fun interface NodeCreatureRenderEngine {
    fun render(request: NodeCreatureRenderRequest): ByteArray
}
