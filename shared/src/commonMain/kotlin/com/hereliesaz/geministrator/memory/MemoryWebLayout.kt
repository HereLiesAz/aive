package com.hereliesaz.geministrator.memory

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Where each node of the Memory Web sits (docs/Memory-Web.md): a force layout, deterministic for a given graph, so the same web
 * is drawn the same way every time. Related nodes pull together along their connections (stronger connections pull harder),
 * every pair pushes apart, and a gentle gravity keeps separate clusters on one canvas.
 *
 * Repulsion only looks at nodes in neighbouring cells of a grid, so a bank of thousands of memories costs a few passes of
 * near-linear work, not every pair. Pure and UI-free: it returns positions in a unit-less plane centred on the origin.
 */
class WebLayout(
    val positions: Map<String, WebPoint>,
    /** Everything fits inside this. */
    val radius: Float,
    /** What Fit frames: the web without its few far-flung outliers, which would otherwise shrink the rest. */
    val fitRadius: Float = radius,
)

data class WebPoint(val x: Float, val y: Float)

object MemoryWebLayout {
    /** A node's radius in layout units: small, so the web reads as clusters of dots and not as a field of blobs. */
    fun nodeRadius(size: Float): Float = 2f + 7f * sqrt(size.coerceIn(0.05f, 1.5f))

    /**
     * [iterations] ticks of a cooling simulation (the usual force-directed model): every pair repels with a force that falls with
     * distance, every link is a spring whose stiffness falls with how many links its ends have (so a hub does not drag its whole
     * neighbourhood into a line), a weak gravity keeps clusters on one canvas, and overlapping nodes are pushed apart. More ticks
     * settle a bigger web more fully, at a cost that grows linearly.
     */
    fun layout(graph: WebGraph, iterations: Int = 240): WebLayout {
        val n = graph.nodes.size
        if (n == 0) return WebLayout(emptyMap(), 0f, 0f)
        val index = HashMap<String, Int>(n * 2)
        graph.nodes.forEachIndexed { i, node -> index[node.id] = i }
        val x = FloatArray(n)
        val y = FloatArray(n)
        val vx = FloatArray(n)
        val vy = FloatArray(n)
        // A sunflower spiral: spread evenly and the same every time, so no randomness decides the picture.
        val golden = (PI * (3.0 - sqrt(5.0))).toFloat()
        for (i in 0 until n) {
            val r = 10f * sqrt(i + 0.5f)
            x[i] = r * cos(i * golden)
            y[i] = r * sin(i * golden)
        }
        val from = IntArray(graph.edges.size)
        val to = IntArray(graph.edges.size)
        val weight = FloatArray(graph.edges.size)
        val degree = IntArray(n)
        var edgeCount = 0
        graph.edges.forEach { edge ->
            val a = index[edge.from] ?: return@forEach
            val b = index[edge.to] ?: return@forEach
            if (a == b) return@forEach
            from[edgeCount] = a
            to[edgeCount] = b
            weight[edgeCount] = edge.weight.coerceIn(0f, 1f)
            degree[a] += 1
            degree[b] += 1
            edgeCount += 1
        }
        val radius = FloatArray(n) { nodeRadius(graph.nodes[it].size) }
        val cutoff = 150f
        val charge = 70f
        var alpha = 1f
        val alphaDecay = 1f - 0.001f.pow(1f / iterations.coerceAtLeast(1))

        repeat(iterations) {
            // Repulsion between nodes near each other, found through a grid of cells.
            val grid = HashMap<Long, MutableList<Int>>(n)
            for (i in 0 until n) grid.getOrPut(cellKey(x[i], y[i], cutoff)) { mutableListOf() } += i
            for (i in 0 until n) {
                val cx = cellOf(x[i], cutoff)
                val cy = cellOf(y[i], cutoff)
                for (gx in cx - 1..cx + 1) for (gy in cy - 1..cy + 1) {
                    val bucket = grid[key(gx, gy)] ?: continue
                    for (j in bucket) {
                        if (j <= i) continue
                        var dx = x[i] - x[j]
                        var dy = y[i] - y[j]
                        var d2 = dx * dx + dy * dy
                        if (d2 < 0.01f) {
                            // Exactly stacked: part them along a fixed direction by index.
                            dx = 0.1f * ((i - j) % 7 + 1)
                            dy = 0.1f * ((j + i) % 5 + 1)
                            d2 = dx * dx + dy * dy
                        }
                        if (d2 > cutoff * cutoff) continue
                        val d = sqrt(d2)
                        val minimum = radius[i] + radius[j] + 2f
                        // Charge falls as 1/d; touching nodes also push apart outright.
                        val push = charge * alpha / d + if (d < minimum) (minimum - d) * 0.5f else 0f
                        val fx = dx / d * push
                        val fy = dy / d * push
                        vx[i] += fx
                        vy[i] += fy
                        vx[j] -= fx
                        vy[j] -= fy
                    }
                }
            }
            // Springs along links: a stronger link wants to be shorter, a hub's links are each softer.
            for (e in 0 until edgeCount) {
                val a = from[e]
                val b = to[e]
                val dx = (x[b] + vx[b]) - (x[a] + vx[a])
                val dy = (y[b] + vy[b]) - (y[a] + vy[a])
                val l = max(0.01f, sqrt(dx * dx + dy * dy))
                val rest = 22f + 40f * (1f - weight[e])
                val stiffness = 1f / min(degree[a], degree[b]).coerceAtLeast(1)
                val k = (l - rest) / l * alpha * stiffness
                val fx = dx * k
                val fy = dy * k
                val bias = degree[a].toFloat() / (degree[a] + degree[b]).coerceAtLeast(1)
                vx[b] -= fx * bias
                vy[b] -= fy * bias
                vx[a] += fx * (1f - bias)
                vy[a] += fy * (1f - bias)
            }
            for (i in 0 until n) {
                // A weak gravity keeps unconnected clusters on the canvas.
                vx[i] -= x[i] * 0.01f * alpha
                vy[i] -= y[i] * 0.01f * alpha
                vx[i] *= 0.6f
                vy[i] *= 0.6f
                x[i] += vx[i]
                y[i] += vy[i]
            }
            alpha -= alpha * alphaDecay
        }
        var maxRadius = 0f
        val positions = HashMap<String, WebPoint>(n * 2)
        for (i in 0 until n) {
            positions[graph.nodes[i].id] = WebPoint(x[i], y[i])
            maxRadius = max(maxRadius, sqrt(x[i] * x[i] + y[i] * y[i]) + radius[i])
        }
        val distances = FloatArray(n) { sqrt(x[it] * x[it] + y[it] * y[it]) }.also { it.sort() }
        val fit = min(maxRadius, distances[min(n - 1, (n * 0.96f).toInt())] + 20f)
        return WebLayout(positions, maxRadius, fit)
    }

    private fun cellKey(px: Float, py: Float, cell: Float): Long = key(cellOf(px, cell), cellOf(py, cell))

    private fun cellOf(p: Float, cell: Float): Int = floor(p / cell).toInt()

    private fun key(gx: Int, gy: Int): Long = (gx.toLong() shl 32) xor (gy.toLong() and 0xFFFFFFFFL)
}
