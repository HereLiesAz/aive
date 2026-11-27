package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.memory.WebEdge
import com.hereliesaz.geministrator.memory.WebGraph
import com.hereliesaz.geministrator.memory.WebGroup
import com.hereliesaz.geministrator.memory.WebNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SpatialHiveGeometryTest {
    @Test fun queenIsTheOriginAndEstablishedSitesSurviveReordering() {
        val saved = mapOf("worker" to SpatialPoint(3f, 2f, 1f))
        val result = spatialCreaturePositions("hive", listOf("new", "worker"), "queen", saved)
        assertEquals(SpatialPoint(0f, 0f, 0f), result["queen"])
        assertEquals(SpatialPoint(3f, 2f, 1f), result["worker"])
        assertEquals(result, spatialCreaturePositions("hive", listOf("worker", "new"), "queen", result))
        assertTrue(result.values.toSet().size == 3)
        for ((id, p) in result) for ((other, q) in result) if (id != other) {
            val dx = p.x - q.x
            val dy = p.y - q.y
            val dz = p.z - q.z
            assertTrue(dx * dx + dy * dy + dz * dz >= 2.89f - .0001f)
        }
    }

    @Test fun differentWorkflowsHaveDifferentInitialLayouts() {
        val ids = listOf("a", "b", "c")
        assertNotEquals(spatialCreaturePositions("hive-a", ids, "queen"), spatialCreaturePositions("hive-b", ids, "queen"))
    }

    @Test fun hyphaAttachesAtBothEndsAndBendsOutOfTheStraightLine() {
        val a = SpatialPoint(1f, 2f, 3f)
        val b = SpatialPoint(5f, 2f, 3f)
        val path = spatialHyphaPoints("association", a, b)
        assertEquals(a, path.first())
        assertEquals(b, path.last())
        assertTrue(path.drop(1).dropLast(1).any { it.y != 2f || it.z != 3f })
        assertTrue(path.all { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() })
        assertEquals(listOf(a, a), spatialHyphaPoints("zero", a, a))
    }

    @Test fun memoryProjectionPreservesOnlyRecordedLinks() {
        fun node(id: String) = WebNode(id, WebGroup.Phrase, "Phrase", id, "private body", .5f, 0L, emptyList())
        val graph = WebGraph(listOf(node("a"), node("b"), node("c")), listOf(
            WebEdge("a", "b", "AssociatedWith", .8f),
            WebEdge("a", "missing", "AssociatedWith", .9f),
            WebEdge("b", "b", "Self", 1f),
        ))
        val scene = projectSpatialMemory(graph)
        assertEquals(setOf("a", "b", "c"), scene.spores.map { it.id }.toSet())
        val link = scene.associations.single()
        assertEquals("a", link.from)
        assertEquals("b", link.to)
        assertEquals(.8f, link.weight)
        assertEquals("AssociatedWith", link.relation)
        assertEquals(scene.spores.single { it.id == "a" }.position, link.points.first())
        assertEquals(scene.spores.single { it.id == "b" }.position, link.points.last())
    }
}
