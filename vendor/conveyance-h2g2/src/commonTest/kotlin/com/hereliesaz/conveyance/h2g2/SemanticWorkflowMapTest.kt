package com.hereliesaz.conveyance.h2g2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SemanticWorkflowMapTest {
    private val bands = listOf(
        H2g2WorkflowBand(
            listOf(
                node("a", H2g2WorkflowState.Complete),
                node("b", H2g2WorkflowState.Complete),
            ),
        ),
        H2g2WorkflowBand(
            listOf(
                node("c", H2g2WorkflowState.Active),
                node("d", H2g2WorkflowState.Pending),
            ),
        ),
        H2g2WorkflowBand(listOf(node("e", H2g2WorkflowState.Pending))),
        H2g2WorkflowBand(listOf(node("f", H2g2WorkflowState.Pending))),
    )

    private val edges = listOf(
        H2g2WorkflowEdge("a", "c"),
        H2g2WorkflowEdge("b", "d"),
        H2g2WorkflowEdge("c", "e"),
        H2g2WorkflowEdge("d", "e"),
        H2g2WorkflowEdge("e", "f"),
    )

    @Test
    fun overviewCollapsesEachBandIntoOneSummarySubject() {
        val view = buildSemanticWorkflowView(
            bands = bands,
            edges = edges,
            zoomLevel = H2g2WorkflowZoomLevel.Overview,
            focusedBandIndex = null,
            focusedNodeId = null,
        )

        assertEquals(4, view.bands.size)
        assertTrue(view.bands.all { it.nodes.size == 1 })
        assertEquals(listOf(0, 1, 2, 3), view.sourceBandIndices)
        assertNull(view.selectedId)
        assertEquals(3, view.edges.size)
        assertEquals(H2g2WorkflowState.Active, view.bands[1].nodes.single().state)
    }

    @Test
    fun bandZoomShowsFocusedBandWithOnlyImmediateContext() {
        val view = buildSemanticWorkflowView(
            bands = bands,
            edges = edges,
            zoomLevel = H2g2WorkflowZoomLevel.Band,
            focusedBandIndex = 2,
            focusedNodeId = null,
        )

        assertEquals(listOf(1, 2, 3), view.sourceBandIndices)
        assertEquals(setOf("c", "d", "e", "f"), view.bands.flatMap { it.nodes }.map { it.id }.toSet())
        assertTrue(view.edges.all { it.from != "a" && it.from != "b" })
        assertNull(view.selectedId)
    }

    @Test
    fun nodeZoomShowsOnlyDirectParentsAndChildren() {
        val view = buildSemanticWorkflowView(
            bands = bands,
            edges = edges,
            zoomLevel = H2g2WorkflowZoomLevel.Node,
            focusedBandIndex = 2,
            focusedNodeId = "e",
        )

        assertEquals(setOf("c", "d", "e", "f"), view.bands.flatMap { it.nodes }.map { it.id }.toSet())
        assertEquals("e", view.selectedId)
        assertEquals(setOf("c" to "e", "d" to "e", "e" to "f"), view.edges.map { it.from to it.to }.toSet())
    }

    @Test
    fun zoomOutWalksExactlyOneSemanticLevelAtATime() {
        val state = H2g2WorkflowViewportState()

        state.focusBand(1)
        state.focusNode("c", 1)
        assertEquals(H2g2WorkflowZoomLevel.Node, state.zoomLevel)

        assertTrue(state.zoomOut())
        assertEquals(H2g2WorkflowZoomLevel.Band, state.zoomLevel)
        assertEquals(1, state.focusedBandIndex)
        assertNull(state.focusedNodeId)

        assertTrue(state.zoomOut())
        assertEquals(H2g2WorkflowZoomLevel.Overview, state.zoomLevel)
        assertNull(state.focusedBandIndex)

        assertFalse(state.zoomOut())
    }

    private fun node(id: String, state: H2g2WorkflowState) = H2g2WorkflowNode(
        id = id,
        label = id.uppercase(),
        state = state,
    )
}
