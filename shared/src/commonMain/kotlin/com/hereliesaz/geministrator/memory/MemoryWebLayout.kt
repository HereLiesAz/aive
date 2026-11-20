package com.hereliesaz.geministrator.memory

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
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
    /** [iterations] passes of the simulation; more settles larger webs more fully, and costs time linearly. */
    fun layout(graph: WebGraph, iterations: Int = 160): WebLayout {
        val n = graph.nodes.size
        if (n == 0) return WebLayout(emptyMap(), 0f, 0f)
        val index = HashMap<String, Int>(n * 2)
        graph.nodes.forEachIndexed { i, node -> index[node.id] = i }
        val x = FloatArray(n)
        val y = FloatArray(n)
        // A sunflower spiral: spread evenly and the same every time, so no randomness decides the picture.
        val golden = (PI * (3.0 - sqrt(5.0))).toFloat()
        val spread = 40f * sqrt(n.toFloat())
        for (i in 0 until n) {
            val r = spread * sqrt((i + 0.5f) / n)
            x[i] = r * cos(i * golden)
            y[i] = r * sin(i * golden)
        }
        val from = IntArray(graph.edges.size)
        val to = IntArray(graph.edges.size)
        val strength = FloatArray(graph.edges.size)
        var edgeCount = 0
        graph.edges.forEach { edge ->
            val a = index[edge.from] ?: return@forEach
            val b = index[edge.to] ?: return@forEach
            if (a == b) return@forEach
            from[edgeCount] = a
            to[edgeCount] = b
            strength[edgeCount] = 0.25f + 0.75f * edge.weight.coerceIn(0f, 1f)
            edgeCount += 1
        }
        val radiusOf = FloatArray(n) { 8f + 26f * sqrt(graph.nodes[it].size.coerceIn(0.05f, 1.5f)) }
        val ideal = 120f
        val cell = ideal * 2f
        val dx = FloatArray(n)
        val dy = FloatArray(n)
        var temperature = ideal * 2f

        repeat(iterations) {
            dx.fill(0f)
            dy.fill(0f)
            // Repulsion between neighbours in a grid of cells.
            val grid = HashMap<Long, MutableList<Int>>(n)
            for (i in 0 until n) grid.getOrPut(cellKey(x[i], y[i], cell)) { mutableListOf() } += i
            for (i in 0 until n) {
                val cx = cellOf(x[i], cell)
                val cy = cellOf(y[i], cell)
                for (gx in cx - 1..cx + 1) for (gy in cy - 1..cy + 1) {
                    val bucket = grid[key(gx, gy)] ?: continue
                    for (j in bucket) {
                        if (j <= i) continue
                        var ddx = x[i] - x[j]
                        var ddy = y[i] - y[j]
                        var d2 = ddx * ddx + ddy * ddy
                        if (d2 < 0.01f) {
                            // Exactly stacked: part them along a fixed direction by index.
                            ddx = 0.1f * ((i - j) % 7 + 1)
                            ddy = 0.1f * ((j + i) % 5 + 1)
                            d2 = ddx * ddx + ddy * ddy
                        }
                        val d = sqrt(d2)
                        val minimum = radiusOf[i] + radiusOf[j] + 6f
                        val force = (ideal * ideal) / d + if (d < minimum) (minimum - d) * 4f else 0f
                        val fx = ddx / d * force
                        val fy = ddy / d * force
                        dx[i] += fx
                        dy[i] += fy
                        dx[j] -= fx
                        dy[j] -= fy
                    }
                }
            }
            // Attraction along connections; a stronger connection pulls harder.
            for (e in 0 until edgeCount) {
                val a = from[e]
                val b = to[e]
                val ddx = x[a] - x[b]
                val ddy = y[a] - y[b]
                val d = max(0.01f, sqrt(ddx * ddx + ddy * ddy))
                val force = d * d / ideal * strength[e]
                val fx = ddx / d * force
                val fy = ddy / d * force
                dx[a] -= fx
                dy[a] -= fy
                dx[b] += fx
                dy[b] += fy
            }
            // Gravity keeps unconnected clusters on the canvas.
            for (i in 0 until n) {
                dx[i] -= x[i] * 0.4f
                dy[i] -= y[i] * 0.4f
            }
            for (i in 0 until n) {
                val d = max(0.01f, sqrt(dx[i] * dx[i] + dy[i] * dy[i]))
                val step = min(d, temperature)
                x[i] += dx[i] / d * step
                y[i] += dy[i] / d * step
            }
            temperature = max(1f, temperature * 0.97f)
        }
        var radius = 0f
        val positions = HashMap<String, WebPoint>(n * 2)
        for (i in 0 until n) {
            positions[graph.nodes[i].id] = WebPoint(x[i], y[i])
            radius = max(radius, sqrt(x[i] * x[i] + y[i] * y[i]) + radiusOf[i])
        }
        val distances = FloatArray(n) { sqrt(x[it] * x[it] + y[it] * y[it]) }.also { it.sort() }
        val fit = min(radius, distances[min(n - 1, (n * 0.96f).toInt())] + 40f)
        return WebLayout(positions, radius, fit)
    }

    private fun cellKey(px: Float, py: Float, cell: Float): Long =
        key(cellOf(px, cell), cellOf(py, cell))

    private fun cellOf(p: Float, cell: Float): Int = floor(p / cell).toInt()

    private fun key(gx: Int, gy: Int): Long = (gx.toLong() shl 32) xor (gy.toLong() and 0xFFFFFFFFL)
}
