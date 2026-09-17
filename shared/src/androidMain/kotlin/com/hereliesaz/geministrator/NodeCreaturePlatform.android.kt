package com.hereliesaz.geministrator

internal actual fun platformNodeCreatureRenderEngine(): NodeCreatureRenderEngine? =
    JvmNodeCreatureRenderEngine.create()

private class JvmNodeCreatureRenderEngine private constructor() : NodeCreatureRenderEngine {
    override fun render(request: NodeCreatureRenderRequest): ByteArray =
        requireNotNull(
            NodeCreatureNativeBridge.renderPacket(
                request.roleLabel,
                request.identitySeed,
                request.activity.abiCode,
                request.timeSeconds,
                request.camera.yaw,
                request.camera.pitch,
                request.camera.zoom,
            ),
        ) { "Rust node-creature renderer returned no packet." }

    companion object {
        fun create(): NodeCreatureRenderEngine? = runCatching {
            System.loadLibrary("haive_node_creatures")
            check(NodeCreatureNativeBridge.packetVersion() == NODE_CREATURE_PACKET_VERSION) {
                "Native node-creature renderer packet version does not match the shared decoder."
            }
            JvmNodeCreatureRenderEngine()
        }.getOrNull()
    }
}

internal object NodeCreatureNativeBridge {
    @JvmStatic
    external fun packetVersion(): Int

    @JvmStatic
    external fun renderPacket(
        roleLabel: String,
        identitySeed: String,
        activityCode: Int,
        timeSeconds: Float,
        cameraYaw: Float,
        cameraPitch: Float,
        cameraZoom: Float,
    ): ByteArray?
}
