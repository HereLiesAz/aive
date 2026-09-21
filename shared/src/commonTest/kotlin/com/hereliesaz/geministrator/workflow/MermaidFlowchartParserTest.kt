package com.hereliesaz.geministrator.workflow

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MermaidFlowchartParserTest {
    @Test
    fun parsesNativeFlowchartNodesEdgesDirectionAndLabels() {
        val graph = MermaidFlowchartParser.parse(
            """
            flowchart LR
                start([Start]) --> choice{Approved?}
                choice -->|yes| ship[Ship]
                choice -.-> retry[Retry]
            """.trimIndent(),
        )

        assertEquals("LR", graph.direction)
        assertEquals(setOf("start", "choice", "ship", "retry"), graph.nodes.map { it.id }.toSet())
        assertEquals(3, graph.edges.size)
        assertEquals("yes", graph.edges.single { it.to == "ship" }.label)
        assertEquals("dotted", graph.edges.single { it.to == "retry" }.style)
    }

    @Test
    fun missingHeaderUsesTdAndProducesWarning() {
        val graph = MermaidFlowchartParser.parse("A[One] --> B[Two]")

        assertEquals("TD", graph.direction)
        assertTrue(graph.warnings.isNotEmpty())
    }
}
