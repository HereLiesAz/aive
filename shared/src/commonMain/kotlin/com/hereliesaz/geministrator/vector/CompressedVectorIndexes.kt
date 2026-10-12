package com.hereliesaz.geministrator.vector

import kotlin.math.roundToInt

/**
 * int8 scalar quantization: each component is stored as a byte, scaled per vector so its largest
 * magnitude maps to 127 (normalized high-dimension vectors have small components; a fixed -1..1
 * scale would waste most of the byte range). A quarter of the float size, plus one float scale. Candidates are scored on the bytes, then the best [rerankFactor]·k are
 * re-scored exactly when [keepFloats] holds the originals (it does by default; turn it off to save
 * memory where approximate scores are enough).
 */
class QuantizedVectorIndex(
    override val dimension: Int,
    private val rerankFactor: Int = 4,
    private val keepFloats: Boolean = true,
) : VectorIndex {
    private val codes = LinkedHashMap<String, Quantized>()
    private val exact = FlatVectorIndex(dimension)

    init {
        require(rerankFactor >= 1)
    }

    override val size: Int get() = codes.size

    override fun upsert(id: String, vector: FloatArray) {
        requireDimension(vector, dimension)
        val unit = normalized(vector)
        codes[id] = quantize(unit)
        if (keepFloats) exact.upsert(id, unit)
    }

    override fun remove(id: String): Boolean {
        exact.remove(id)
        return codes.remove(id) != null
    }

    override fun contains(id: String): Boolean = id in codes

    override fun search(query: FloatArray, k: Int, filter: (String) -> Boolean): List<VectorHit> {
        requireDimension(query, dimension)
        if (k <= 0) return emptyList()
        val unit = normalized(query)
        val q = quantize(unit)
        val candidates = TopK(if (keepFloats) k * rerankFactor else k)
        for ((id, code) in codes) {
            if (!filter(id)) continue
            var sum = 0
            for (i in q.bytes.indices) sum += q.bytes[i] * code.bytes[i]
            candidates.offer(id, sum * q.step * code.step)
        }
        val approximate = candidates.result()
        return if (keepFloats) exact.rerank(unit, approximate.map { it.id }, k) else approximate
    }

    /** [bytes] times [step] approximates the vector. */
    internal class Quantized(val bytes: ByteArray, val step: Float)

    internal companion object {
        const val LEVELS = 127f

        fun quantize(vector: FloatArray): Quantized {
            var largest = 0f
            for (value in vector) largest = maxOf(largest, kotlin.math.abs(value))
            if (largest == 0f) return Quantized(ByteArray(vector.size), 0f)
            val step = largest / LEVELS
            return Quantized(ByteArray(vector.size) { (vector[it] / step).roundToInt().toByte() }, step)
        }
    }
}

/**
 * Binary quantization: one sign bit per dimension, compared by Hamming distance, a thirty-second
 * of the float size. Coarse on its own, so it shortlists [rerankFactor]·k candidates and, with
 * [keepFloats], re-scores them exactly. Suits large banks where a first cut must be cheap.
 */
class BinaryVectorIndex(
    override val dimension: Int,
    private val rerankFactor: Int = 40,
    private val keepFloats: Boolean = true,
) : VectorIndex {
    private val words = (dimension + 63) / 64
    private val codes = LinkedHashMap<String, LongArray>()
    private val exact = FlatVectorIndex(dimension)

    init {
        require(rerankFactor >= 1)
    }

    override val size: Int get() = codes.size

    override fun upsert(id: String, vector: FloatArray) {
        requireDimension(vector, dimension)
        val unit = normalized(vector)
        codes[id] = signBits(unit)
        if (keepFloats) exact.upsert(id, unit)
    }

    override fun remove(id: String): Boolean {
        exact.remove(id)
        return codes.remove(id) != null
    }

    override fun contains(id: String): Boolean = id in codes

    override fun search(query: FloatArray, k: Int, filter: (String) -> Boolean): List<VectorHit> {
        requireDimension(query, dimension)
        if (k <= 0) return emptyList()
        val unit = normalized(query)
        val q = signBits(unit)
        val candidates = TopK(if (keepFloats) k * rerankFactor else k)
        for ((id, code) in codes) {
            if (!filter(id)) continue
            var differing = 0
            for (w in 0 until words) differing += (q[w] xor code[w]).countOneBits()
            // Map Hamming distance to a cosine-like score in -1..1.
            candidates.offer(id, 1f - 2f * differing / dimension)
        }
        val approximate = candidates.result()
        return if (keepFloats) exact.rerank(unit, approximate.map { it.id }, k) else approximate
    }

    private fun signBits(unit: FloatArray): LongArray {
        val bits = LongArray(words)
        for (i in unit.indices) {
            if (unit[i] > 0f) bits[i / 64] = bits[i / 64] or (1L shl (i % 64))
        }
        return bits
    }
}
