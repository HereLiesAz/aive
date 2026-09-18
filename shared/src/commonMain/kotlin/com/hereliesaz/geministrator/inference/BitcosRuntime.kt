package com.hereliesaz.geministrator.inference

const val BITCOS_ABI_VERSION: Int = 1

enum class BitcosDecodeBackend(val abiCode: Int) {
    Scalar(0),
    Neon(1),
    Bmi2(2),
    Avx2Bmi2(3),
    Avx512Bmi2(4);

    companion object {
        fun fromAbiCode(code: Int): BitcosDecodeBackend =
            entries.firstOrNull { it.abiCode == code }
                ?: error("Unsupported BITCOS decode backend code $code")
    }
}

data class BitcosRuntimeInfo(
    val abiVersion: Int,
    val decodeBackend: BitcosDecodeBackend,
)

interface BitcosRuntime {
    val info: BitcosRuntimeInfo

    /**
     * Losslessly expands one BITCOS symbol payload into signed i8 ternary weights.
     *
     * Presence bits and compact sign bits are LSB-first. A sign bit of 1 means negative.
     */
    fun decode(
        elementCount: Long,
        presence: ByteArray,
        signs: ByteArray,
    ): ByteArray
}

internal expect fun platformBitcosRuntimeOrNull(): BitcosRuntime?

object BitcosRuntimeProvider {
    fun currentOrNull(): BitcosRuntime? = platformBitcosRuntimeOrNull()
}

/**
 * Adds BITCOS only when a concrete native runtime loaded successfully.
 *
 * Weight-encoding capability is intentionally explicit. Merely supporting a ternary model format
 * does not authorize a runtime to select a BITCOS artifact.
 */
fun LocalModelRuntimeCapabilities.withBitcosWeightSupport(
    runtime: BitcosRuntime? = BitcosRuntimeProvider.currentOrNull(),
): LocalModelRuntimeCapabilities =
    if (runtime == null || runtime.info.abiVersion != BITCOS_ABI_VERSION) {
        this
    } else {
        copy(
            supportedPrecisions = supportedPrecisions + TERNARY_PRECISION,
            supportedWeightEncodings = supportedWeightEncodings + BITCOS_WEIGHT_ENCODING,
        )
    }
