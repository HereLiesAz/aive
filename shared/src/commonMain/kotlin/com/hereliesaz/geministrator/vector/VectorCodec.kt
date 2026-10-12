package com.hereliesaz.geministrator.vector

/**
 * Vectors as SQLite BLOBs: a one-byte format tag, then the components little-endian. Indexes
 * live in memory and are rebuilt from these rows, so the stored form is the source of truth.
 *
 * - [Format.Float32]: 4 bytes per dimension, exact.
 * - [Format.Int8]: a float scale, then 1 byte per dimension: the same per-vector scalar
 *   quantization [QuantizedVectorIndex] uses; decodes to an approximation of the normalized vector.
 */
object VectorCodec {
    /** Tag 2 was a fixed-scale int8 layout that was never persisted; it stays unassigned so no blob is misread. */
    enum class Format(val tag: Byte) { Float32(1), Int8(3) }

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
        Format.Int8 -> QuantizedVectorIndex.quantize(normalized(vector)).let { q ->
            val bits = q.step.toRawBits()
            byteArrayOf(format.tag, bits.toByte(), (bits ushr 8).toByte(), (bits ushr 16).toByte(), (bits ushr 24).toByte()) + q.bytes
        }
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
            Format.Int8.tag -> {
                require(bytes.size >= 5) { "Truncated int8 vector blob" }
                val step = Float.fromBits(
                    (bytes[1].toInt() and 0xff) or ((bytes[2].toInt() and 0xff) shl 8) or
                        ((bytes[3].toInt() and 0xff) shl 16) or ((bytes[4].toInt() and 0xff) shl 24),
                )
                FloatArray(bytes.size - 5) { bytes[it + 5] * step }
            }
            else -> throw IllegalArgumentException("Unknown vector blob format ${bytes[0]}")
        }
    }
}
