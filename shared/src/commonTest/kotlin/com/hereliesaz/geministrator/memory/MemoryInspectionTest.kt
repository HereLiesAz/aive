package com.hereliesaz.geministrator.memory

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the Memory screen reads: summary trees, pair summaries, summarizer metrics, project controls. */
class MemoryInspectionTest {
    private val episode = MemoryEpisodeId("e1")

    private fun node(id: String, text: String, kind: MemoryNodeKind = MemoryNodeKind.Context, metadata: Map<String, String> = emptyMap()) =
        MemoryNode(MemoryNodeId(id), kind, text, sourceEpisodeIds = setOf(episode), createdAtEpochMillis = 1L, metadata = metadata)

    private fun outline(path: String, text: String, leaf: Boolean) = node(
        "outline:e1:$path", text, MemoryNodeKind.Outline,
        mapOf(OUTLINE_PATH to path, OUTLINE_LEVEL to path.count { it == '.' }.toString(), OUTLINE_LEAF to leaf.toString()),
    )

    private fun edge(id: String, from: String, to: String, relation: MemoryRelationKind) =
        MemoryEdge(MemoryEdgeId(id), MemoryNodeId(from), MemoryNodeId(to), relation, createdAtEpochMillis = 1L)

    @Test
    fun outlineListsTheTreeDepthFirstInPathOrderWithLevels() {
        val snapshot = MemorySnapshot(
            nodes = listOf(outline("0", "Root", false), outline("0.10", "Second", true), outline("0.2", "First", false), outline("0.2.1", "Leaf", true)),
            edges = listOf(
                edge("o1", "outline:e1:0", "outline:e1:0.10", MemoryRelationKind.Outlines),
                edge("o2", "outline:e1:0", "outline:e1:0.2", MemoryRelationKind.Outlines),
                edge("o3", "outline:e1:0.2", "outline:e1:0.2.1", MemoryRelationKind.Outlines),
            ),
        )
        val rows = MemoryInspection.outline(snapshot, episode)
        assertEquals(listOf("Root", "First", "Leaf", "Second"), rows.map { it.node.text })
        assertEquals(listOf(0, 1, 2, 1), rows.map { it.level })
        assertEquals(listOf(false, false, true, true), rows.map { it.leaf })
        assertTrue(MemoryInspection.outline(snapshot, MemoryEpisodeId("other")).isEmpty())
    }

    @Test
    fun pairsShowEachLinkWithItsSummaryOrItsAbsence() {
        val snapshot = MemorySnapshot(
            nodes = listOf(
                node("a", "Gradle cache moved."), node("b", "CI restores the cache."), node("c", "Release signing."),
                node("pair:ab", "A: cache moved\nB: CI restores it", MemoryNodeKind.PairSummary, mapOf(PAIR_OF to "ab")),
            ),
            edges = listOf(
                edge("ab", "a", "b", MemoryRelationKind.AssociatedWith),
                edge("ac", "a", "c", MemoryRelationKind.SimilarTo),
            ),
        )
        val pairs = MemoryInspection.pairs(snapshot, MemoryNodeId("a")).associateBy { it.edge.id.value }
        assertEquals("CI restores the cache.", pairs.getValue("ab").partner?.text)
        assertEquals("A: cache moved\nB: CI restores it", pairs.getValue("ab").summary?.text)
        assertNull(pairs.getValue("ac").summary, "a covered link without a summary yet is listed as pending")
        assertEquals(listOf("a", "b", "c"), MemoryInspection.inspectable(snapshot).map { it.id.value }.sorted(), "pair summaries are not listed as memories")
    }

    @Test
    fun controllerPublishesMetricsLineageAndPerProjectControls() = runBlocking<Unit> {
        val memory = MemoryLayerController(
            banks = MemoryBanks.inMemory(),
            settingsStore = MemoryLayerSettingsStore(MapSettings()),
            engineProvider = object : MemoryEngineProvider {},
            scope = CoroutineScope(Dispatchers.Unconfined),
            nowEpochMillis = { 1_000L },
        )
        memory.registerWorkflow("wf-a", "P")
        memory.registerWorkflow("wf-b", "Q")
        memory.selectBank("wf-a")
        assertEquals(0, memory.summaryMetrics.value.pairSummaryViolations)
        assertEquals(setOf("P", "Q"), memory.lineageLog.value.workflows.mapNotNull { it.projectId }.toSet())

        memory.expandProject("P", "Q", by = "user", reason = "shared release tooling")
        assertEquals(listOf("Q"), memory.lineageLog.value.expansions.filter { it.projectId == "P" }.map { it.incorporatesProjectId })

        val capped = MemoryRawRetention(MemoryRawRetention.Mode.CapByAge, maxAgeMillis = 10L)
        memory.setProjectRawRetention("P", capped)
        assertEquals(capped, memory.settings.value.rawRetentionByProject["P"])
        memory.setProjectRawRetention("P", null)
        assertTrue("P" !in memory.settings.value.rawRetentionByProject)
    }
}
