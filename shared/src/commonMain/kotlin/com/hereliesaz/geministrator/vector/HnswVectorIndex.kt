package com.hereliesaz.geministrator.vector

import kotlin.math.floor
import kotlin.math.ln
import kotlin.random.Random

/**
 * Hierarchical Navigable Small World graph (Malkov & Yashunin, 2016): approximate cosine search in
 * roughly logarithmic time. Each vector joins layers 0..L (L drawn geometrically); a query descends
 * greedily from the top layer and searches layer 0 with a beam of [efSearch].
 *
 * - [m]: links per node per layer (layer 0 keeps 2·m). Higher is more accurate and larger.
 * - [efConstruction]: beam width while inserting. Higher builds a better graph, slower.
 * - [efSearch]: beam width while querying, raised to k when k is larger. The accuracy dial.
 * - [seed]: makes the graph, and so results, reproducible for the same insertion order.
 *
 * Removal marks a node deleted: it still routes searches but is never returned. Replacing an id
 * deletes the old node and inserts a new one. [compact] rebuilds without deleted nodes once they
 * pile up (callers can watch [deletedCount]).
 */
class HnswVectorIndex(
    override val dimension: Int,
    private val m: Int = 16,
    private val efConstruction: Int = 200,
    var efSearch: Int = 64,
    private val seed: Long = 42L,
) : VectorIndex {
    private val random = Random(seed)
    private val levelFactor = 1.0 / ln(m.toDouble())

    private val vectors = ArrayList<FloatArray>()
    private val ids = ArrayList<String>()
    private val levels = ArrayList<Int>()

    /** links[node][layer] = neighbour nodes. */
    private val links = ArrayList<Array<MutableList<Int>>>()
    private val deleted = HashSet<Int>()
    private val nodeById = HashMap<String, Int>()

    private var entryPoint = -1
    private var topLevel = -1

    init {
        require(m >= 2) { "m must be at least 2" }
        require(efConstruction >= m) { "efConstruction must be at least m" }
        require(efSearch >= 1)
    }

    override val size: Int get() = nodeById.size

    val deletedCount: Int get() = deleted.size

    override fun contains(id: String): Boolean = id in nodeById

    override fun upsert(id: String, vector: FloatArray) {
        requireDimension(vector, dimension)
        nodeById[id]?.let { deleted += it }
        val node = vectors.size
        val unit = normalized(vector)
        val level = floor(-ln(1.0 - random.nextDouble()) * levelFactor).toInt()
        vectors += unit
        ids += id
        levels += level
        links += Array(level + 1) { ArrayList<Int>() }
        nodeById[id] = node

        if (entryPoint < 0) {
            entryPoint = node
            topLevel = level
            return
        }

        var current = entryPoint
        for (layer in topLevel downTo level + 1) {
            current = greedyClosest(unit, current, layer)
        }
        for (layer in minOf(level, topLevel) downTo 0) {
            val candidates = searchLayer(unit, listOf(current), efConstruction, layer)
            val capacity = capacity(layer)
            val selected = selectNeighbours(unit, candidates, capacity)
            links[node][layer].addAll(selected.map { it.node })
            for (neighbour in selected) {
                val theirs = links[neighbour.node][layer]
                theirs += node
                if (theirs.size > capacity) prune(neighbour.node, layer, capacity)
            }
            current = candidates.first().node
        }
        if (level > topLevel) {
            topLevel = level
            entryPoint = node
        }
    }

    override fun remove(id: String): Boolean {
        val node = nodeById.remove(id) ?: return false
        deleted += node
        return true
    }

    override fun search(query: FloatArray, k: Int, filter: (String) -> Boolean): List<VectorHit> {
        requireDimension(query, dimension)
        if (k <= 0 || entryPoint < 0) return emptyList()
        val unit = normalized(query)
        var current = entryPoint
        for (layer in topLevel downTo 1) {
            current = greedyClosest(unit, current, layer)
        }
        val beam = maxOf(efSearch, k)
        val top = TopK(k)
        for (candidate in searchLayer(unit, listOf(current), beam, 0)) {
            if (candidate.node in deleted) continue
            val id = ids[candidate.node]
            if (filter(id)) top.offer(id, candidate.score)
        }
        return top.result()
    }

    /** Rebuilds the graph from live vectors only, dropping deleted nodes. Same seed, same order. */
    fun compact(): HnswVectorIndex {
        val rebuilt = HnswVectorIndex(dimension, m, efConstruction, efSearch, seed)
        for ((id, node) in nodeById.entries.sortedBy { it.value }) rebuilt.upsert(id, vectors[node])
        return rebuilt
    }

    private fun capacity(layer: Int): Int = if (layer == 0) 2 * m else m

    private fun greedyClosest(query: FloatArray, start: Int, layer: Int): Int {
        var best = start
        var bestScore = dot(query, vectors[start])
        var improved = true
        while (improved) {
            improved = false
            for (neighbour in linksAt(best, layer)) {
                val score = dot(query, vectors[neighbour])
                if (score > bestScore) {
                    best = neighbour
                    bestScore = score
                    improved = true
                }
            }
        }
        return best
    }

    /** Beam search on one layer; returns up to [ef] nodes, most similar first. */
    private fun searchLayer(query: FloatArray, entries: List<Int>, ef: Int, layer: Int): List<Scored> {
        val visited = HashSet<Int>()
        val frontier = ScoredHeap(maxFirst = true)
        val found = ScoredHeap(maxFirst = false)
        for (entry in entries) {
            visited += entry
            val scored = Scored(entry, dot(query, vectors[entry]))
            frontier.push(scored)
            found.push(scored)
        }
        while (frontier.isNotEmpty()) {
            val nearest = frontier.pop()
            if (found.size >= ef && nearest.score < found.peek().score) break
            for (neighbour in linksAt(nearest.node, layer)) {
                if (!visited.add(neighbour)) continue
                val score = dot(query, vectors[neighbour])
                if (found.size < ef || score > found.peek().score) {
                    val scored = Scored(neighbour, score)
                    frontier.push(scored)
                    found.push(scored)
                    if (found.size > ef) found.pop()
                }
            }
        }
        return found.drain().sortedByDescending { it.score }
    }

    /**
     * The neighbour-selection heuristic: keep a candidate only if it is closer to the new node than
     * to any neighbour already kept, so links spread in different directions; fill up with the
     * nearest leftovers.
     */
    private fun selectNeighbours(query: FloatArray, candidates: List<Scored>, capacity: Int): List<Scored> {
        val kept = ArrayList<Scored>(capacity)
        val skipped = ArrayList<Scored>()
        for (candidate in candidates) {
            if (kept.size >= capacity) break
            val diverse = kept.all { dot(vectors[candidate.node], vectors[it.node]) < candidate.score }
            if (diverse) kept += candidate else skipped += candidate
        }
        for (candidate in skipped) {
            if (kept.size >= capacity) break
            kept += candidate
        }
        return kept
    }

    private fun prune(node: Int, layer: Int, capacity: Int) {
        val own = vectors[node]
        val scored = links[node][layer].map { Scored(it, dot(own, vectors[it])) }.sortedByDescending { it.score }
        val kept = selectNeighbours(own, scored, capacity)
        links[node][layer] = kept.mapTo(ArrayList()) { it.node }
    }

    private fun linksAt(node: Int, layer: Int): List<Int> =
        if (layer < links[node].size) links[node][layer] else emptyList()

    private data class Scored(val node: Int, val score: Float)

    /** Binary heap on score: the highest first when [maxFirst], else the lowest first. */
    private class ScoredHeap(private val maxFirst: Boolean) {
        private val items = ArrayList<Scored>()

        val size: Int get() = items.size

        fun isNotEmpty(): Boolean = items.isNotEmpty()

        fun peek(): Scored = items[0]

        fun push(item: Scored) {
            items += item
            var child = items.size - 1
            while (child > 0) {
                val parent = (child - 1) / 2
                if (!before(items[child], items[parent])) break
                swap(child, parent)
                child = parent
            }
        }

        fun pop(): Scored {
            val top = items[0]
            val last = items.removeAt(items.size - 1)
            if (items.isNotEmpty()) {
                items[0] = last
                var parent = 0
                while (true) {
                    val left = 2 * parent + 1
                    val right = left + 1
                    var first = parent
                    if (left < items.size && before(items[left], items[first])) first = left
                    if (right < items.size && before(items[right], items[first])) first = right
                    if (first == parent) break
                    swap(parent, first)
                    parent = first
                }
            }
            return top
        }

        fun drain(): List<Scored> = items.toList()

        private fun before(a: Scored, b: Scored): Boolean = if (maxFirst) a.score > b.score else a.score < b.score

        private fun swap(i: Int, j: Int) {
            val held = items[i]
            items[i] = items[j]
            items[j] = held
        }
    }
}
