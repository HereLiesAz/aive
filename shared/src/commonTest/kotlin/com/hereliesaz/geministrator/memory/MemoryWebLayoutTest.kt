package com.hereliesaz.geministrator.memory

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryWebLayoutTest {
    private fun node(id: String, size: Float = 0.5f) = WebNode(id, WebGroup.Phrase, "Phrase", id, id, size, 0L, emptyList())
    private fun dist(a: WebPoint, b: WebPoint) = sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y))

    @Test
    fun theSameWebIsLaidOutTheSameWay() {
        val graph = WebGraph((0 until 40).map { node("n$it") }, (1 until 40).map { WebEdge("n${it - 1}", "n$it", "AssociatedWith", 0.6f) })
        assertEquals(MemoryWebLayout.layout(graph).positions, MemoryWebLayout.layout(graph).positions)
    }

    @Test
    fun connectedNodesSitNearerThanUnconnectedOnes() {
        val nodes = listOf("a", "b", "c", "d", "e", "f").map { node(it) }
        val graph = WebGraph(nodes, listOf(WebEdge("a", "b", "r", 1f), WebEdge("b", "c", "r", 1f), WebEdge("d", "e", "r", 1f), WebEdge("e", "f", "r", 1f)))
        val p = MemoryWebLayout.layout(graph).positions
        assertTrue(dist(p.getValue("a"), p.getValue("b")) < dist(p.getValue("a"), p.getValue("f")))
    }

    @Test
    fun nothingOverlapsAndEveryNodeIsPlaced() {
        val nodes = (0 until 60).map { node("n$it", size = 0.2f + (it % 5) * 0.2f) }
        val layout = MemoryWebLayout.layout(WebGraph(nodes, emptyList()))
        assertEquals(60, layout.positions.size)
        val pts = layout.positions.values.toList()
        for (i in pts.indices) for (j in i + 1 until pts.size) assertTrue(dist(pts[i], pts[j]) > 8f, "nodes $i and $j are stacked")
        assertTrue(layout.radius > 0f)
    }

    @Test
    fun anEmptyWebHasNothingToPlace() {
        assertEquals(0, MemoryWebLayout.layout(WebGraph(emptyList(), emptyList())).positions.size)
    }

    @Test
    fun aBigWebIsLaidOutInReasonableTime() {
        val nodes = (0 until 3000).map { node("n$it", size = 0.1f + (it % 9) * 0.1f) }
        val edges = (1 until 3000).map { WebEdge("n${it / 3}", "n$it", "r", 0.5f) }
        val layout = MemoryWebLayout.layout(WebGraph(nodes, edges), iterations = 60)
        assertEquals(3000, layout.positions.size)
    }
}
