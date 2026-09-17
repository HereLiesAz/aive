package com.hereliesaz.geministrator.inference

import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal actual fun platformBitcosRuntimeOrNull(): BitcosRuntime? =
    JvmBitcosRuntime.create()

private class JvmBitcosRuntime private constructor(
    override val info: BitcosRuntimeInfo,
) : BitcosRuntime {
    override fun decode(
        elementCount: Long,
        presence: ByteArray,
        signs: ByteArray,
    ): ByteArray =
        requireNotNull(BitcosNativeBridge.decodePayload(elementCount, presence, signs)) {
            "Rust BITCOS decoder rejected the payload."
        }

    companion object {
        fun create(): BitcosRuntime? = runCatching {
            loadBitcosNativeLibrary()
            val version = BitcosNativeBridge.abiVersion()
            check(version == BITCOS_ABI_VERSION) {
                "Native BITCOS ABI $version does not match shared ABI $BITCOS_ABI_VERSION."
            }
            JvmBitcosRuntime(
                BitcosRuntimeInfo(
                    abiVersion = version,
                    decodeBackend = BitcosDecodeBackend.fromAbiCode(
                        BitcosNativeBridge.bestDecodeBackend(),
                    ),
                ),
            )
        }.getOrNull()
    }
}

private fun loadBitcosNativeLibrary() {
    runCatching {
        System.loadLibrary("haive_bitcos")
    }.onSuccess {
        return
    }

    val mappedName = System.mapLibraryName("haive_bitcos")
    val resourcePath = "bitcos/$mappedName"
    val stream = BitcosNativeBridge::class.java.classLoader
        .getResourceAsStream(resourcePath)
        ?: error("Bundled Rust BITCOS library $resourcePath was not found.")

    stream.use { input ->
        val directory = Files.createTempDirectory("haive-bitcos-")
        val library = directory.resolve(mappedName)
        Files.copy(input, library, StandardCopyOption.REPLACE_EXISTING)
        library.toFile().deleteOnExit()
        directory.toFile().deleteOnExit()
        System.load(library.toAbsolutePath().toString())
    }
}

internal object BitcosNativeBridge {
    @JvmStatic
    external fun abiVersion(): Int

    @JvmStatic
    external fun bestDecodeBackend(): Int

    @JvmStatic
    external fun decodePayload(
        elementCount: Long,
        presence: ByteArray,
        signs: ByteArray,
    ): ByteArray?
}
