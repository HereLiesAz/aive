package com.hereliesaz.geministrator.vector

/** Exact cosine search over float vectors: every query scores every stored vector. */
class FlatVectorIndex(override val dimension: Int) : VectorIndex {
    private val vectors = LinkedHashMap<String, FloatArray>()

    override val size: Int get() = vectors.size

    override fun upsert(id: String, vector: FloatArray) {
        requireDimension(vector, dimension)
        vectors[id] = normalized(vector)
    }

    override fun remove(id: String): Boolean = vectors.remove(id) != null

    override fun contains(id: String): Boolean = id in vectors

    /** The stored (normalized) vector, for re-ranking candidates from an approximate index. */
    fun vector(id: String): FloatArray? = vectors[id]

    override fun search(query: FloatArray, k: Int, filter: (String) -> Boolean): List<VectorHit> {
        requireDimension(query, dimension)
        if (k <= 0) return emptyList()
        val unit = normalized(query)
        val top = TopK(k)
        for ((id, vector) in vectors) {
            if (filter(id)) top.offer(id, dot(unit, vector))
        }
        return top.result()
    }

    /** Exact scores for [ids] only; used to re-rank an approximate index's candidates. */
    internal fun rerank(query: FloatArray, ids: Collection<String>, k: Int): List<VectorHit> {
        val top = TopK(k)
        for (id in ids) {
            val vector = vectors[id] ?: continue
            top.offer(id, dot(query, vector))
        }
        return top.result()
    }
}
