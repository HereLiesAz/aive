package com.hereliesaz.geministrator.vector

/**
 * Vectors as SQLite BLOBs: a one-byte format tag, then the components little-endian. Indexes
 * live in memory and are rebuilt from these rows, so the stored form is the source of truth.
 *
 * - [Format.Float32]: 4 bytes per dimension, exact.
 * - [Format.Int8]: 1 byte per dimension, the same scalar quantization [QuantizedVectorIndex] uses;
 *   decodes to an approximation of the normalized vector.
 */
object VectorCodec {
    enum class Format(val tag: Byte) { Float32(1), Int8(2) }

    fun encode(vector: FloatArray, format: Format = Format.Float32): ByteArray = when (format) {
        Format.Float32 -> ByteArray(1 + vector.size * 4).also { bytes ->
            bytes[0] = format.tag
            for (i in vector.indices) {
                val bits = vector[i].toRawBits()
                val at = 1 + i * 4
                bytes[at] = bits.toByte()
                bytes[at + 1] = (bits ushr 8).toByte()
                bytes[at + 2] = (bits ushr 16).toByte()
                bytes[at + 3] = (bits ushr 24).toByte()
            }
        }
        Format.Int8 -> byteArrayOf(format.tag) + QuantizedVectorIndex.quantize(normalized(vector))
    }

    fun decode(bytes: ByteArray): FloatArray {
        require(bytes.isNotEmpty()) { "Empty vector blob" }
        return when (bytes[0]) {
            Format.Float32.tag -> {
                require((bytes.size - 1) % 4 == 0) { "Truncated float32 vector blob" }
                FloatArray((bytes.size - 1) / 4) { i ->
                    val at = 1 + i * 4
                    Float.fromBits(
                        (bytes[at].toInt() and 0xff) or
                            ((bytes[at + 1].toInt() and 0xff) shl 8) or
                            ((bytes[at + 2].toInt() and 0xff) shl 16) or
                            ((bytes[at + 3].toInt() and 0xff) shl 24),
                    )
                }
            }
            Format.Int8.tag -> FloatArray(bytes.size - 1) { bytes[it + 1] / QuantizedVectorIndex.SCALE }
            else -> throw IllegalArgumentException("Unknown vector blob format ${bytes[0]}")
        }
    }
}
