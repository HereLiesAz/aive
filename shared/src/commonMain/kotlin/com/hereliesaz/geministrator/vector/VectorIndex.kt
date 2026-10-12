package com.hereliesaz.geministrator.vector

import kotlin.math.sqrt

/**
 * On-device nearest-neighbour search over embedding vectors, in pure Kotlin so every target
 * (Android, desktop, JS, Wasm) runs the same code. Implementations trade exactness for speed and
 * memory:
 *
 * - [FlatVectorIndex]: exact cosine over float vectors. The reference; fine to tens of thousands.
 * - [QuantizedVectorIndex]: int8 scalar quantization, 4x smaller, with an exact float re-rank.
 * - [BinaryVectorIndex]: one bit per dimension and Hamming distance, 32x smaller, with re-rank.
 * - [HnswVectorIndex]: approximate graph search, sub-linear in the number of vectors.
 *
 * Vectors are L2-normalized on insert, so cosine similarity is a dot product. Ids are caller
 * strings (memory node ids, artifact ids, role ids). Indexes are in memory and rebuilt from
 * stored vectors ([VectorCodec]); none of them is thread-safe, so callers serialize access.
 */
interface VectorIndex {
    val dimension: Int
    val size: Int

    /** Adds or replaces [id]'s vector. */
    fun upsert(id: String, vector: FloatArray)

    fun remove(id: String): Boolean

    operator fun contains(id: String): Boolean

    /**
     * The [k] stored vectors most similar to [query], best first, scored by cosine similarity.
     * [filter] limits the candidates (a bank, a project, a kind) without a second index.
     */
    fun search(query: FloatArray, k: Int, filter: (String) -> Boolean = { true }): List<VectorHit>
}

data class VectorHit(val id: String, val score: Float)

/** Returns a unit-length copy of [vector]; a zero vector stays zero (it matches nothing). */
fun normalized(vector: FloatArray): FloatArray {
    var sum = 0.0
    for (value in vector) sum += value * value
    if (sum == 0.0) return vector.copyOf()
    val scale = (1.0 / sqrt(sum)).toFloat()
    return FloatArray(vector.size) { vector[it] * scale }
}

internal fun dot(a: FloatArray, b: FloatArray): Float {
    var sum = 0f
    for (i in a.indices) sum += a[i] * b[i]
    return sum
}

internal fun requireDimension(vector: FloatArray, dimension: Int) {
    require(vector.size == dimension) { "Expected a $dimension-dimension vector, got ${vector.size}" }
}

/** Keeps the [k] best hits seen so far; ties keep the earlier id for deterministic results. */
internal class TopK(private val k: Int) {
    private val hits = ArrayList<VectorHit>(k + 1)

    val worstScore: Float get() = if (hits.size < k) Float.NEGATIVE_INFINITY else hits.last().score

    fun offer(id: String, score: Float) {
        if (hits.size == k && score <= hits.last().score) return
        var index = hits.size
        while (index > 0 && hits[index - 1].score < score) index--
        hits.add(index, VectorHit(id, score))
        if (hits.size > k) hits.removeAt(hits.size - 1)
    }

    fun result(): List<VectorHit> = hits.toList()
}
