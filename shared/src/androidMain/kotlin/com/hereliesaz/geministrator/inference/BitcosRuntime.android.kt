package com.hereliesaz.geministrator.inference

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
            System.loadLibrary("haive_bitcos")
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
