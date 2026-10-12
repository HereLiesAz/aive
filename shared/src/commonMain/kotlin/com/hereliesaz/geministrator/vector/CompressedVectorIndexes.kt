package com.hereliesaz.geministrator.vector

import kotlin.math.roundToInt

/**
 * int8 scalar quantization: each normalized component (in -1..1) is stored as a byte, a quarter
 * of the float size. Candidates are scored on the bytes, then the best [rerankFactor]·k are
 * re-scored exactly when [keepFloats] holds the originals (it does by default; turn it off to save
 * memory where approximate scores are enough).
 */
class QuantizedVectorIndex(
    override val dimension: Int,
    private val rerankFactor: Int = 4,
    private val keepFloats: Boolean = true,
) : VectorIndex {
    private val codes = LinkedHashMap<String, ByteArray>()
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
            for (i in q.indices) sum += q[i] * code[i]
            candidates.offer(id, sum / (SCALE * SCALE))
        }
        val approximate = candidates.result()
        return if (keepFloats) exact.rerank(unit, approximate.map { it.id }, k) else approximate
    }

    internal companion object {
        const val SCALE = 127f

        fun quantize(unit: FloatArray): ByteArray =
            ByteArray(unit.size) { (unit[it].coerceIn(-1f, 1f) * SCALE).roundToInt().toByte() }
    }
}

/**
 * Binary quantization: one sign bit per dimension, compared by Hamming distance, a thirty-second
 * of the float size. Coarse on its own, so it shortlists [rerankFactor]·k candidates and, with
 * [keepFloats], re-scores them exactly. Suits large banks where a first cut must be cheap.
 */
class BinaryVectorIndex(
    override val dimension: Int,
    private val rerankFactor: Int = 10,
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
