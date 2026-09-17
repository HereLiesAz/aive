package com.hereliesaz.geministrator

import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
            loadNodeCreatureNativeLibrary()
            check(NodeCreatureNativeBridge.packetVersion() == NODE_CREATURE_PACKET_VERSION) {
                "Native node-creature renderer packet version does not match the shared decoder."
            }
            JvmNodeCreatureRenderEngine()
        }.getOrNull()
    }
}

private fun loadNodeCreatureNativeLibrary() {
    runCatching {
        System.loadLibrary("haive_node_creatures")
    }.onSuccess {
        return
    }

    val mappedName = System.mapLibraryName("haive_node_creatures")
    val resourcePath = "node-creatures/$mappedName"
    val stream = NodeCreatureNativeBridge::class.java.classLoader
        .getResourceAsStream(resourcePath)
        ?: error("Bundled Rust node-creature library $resourcePath was not found.")

    stream.use { input ->
        val directory = Files.createTempDirectory("haive-node-creatures-")
        val library = directory.resolve(mappedName)
        Files.copy(input, library, StandardCopyOption.REPLACE_EXISTING)
        library.toFile().deleteOnExit()
        directory.toFile().deleteOnExit()
        System.load(library.toAbsolutePath().toString())
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
