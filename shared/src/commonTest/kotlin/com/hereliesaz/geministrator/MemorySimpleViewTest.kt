package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.memory.MemoryActivity
import com.hereliesaz.geministrator.memory.MemoryConsolidationStage
import com.hereliesaz.geministrator.memory.MemoryEdge
import com.hereliesaz.geministrator.memory.MemoryEdgeId
import com.hereliesaz.geministrator.memory.MemoryEpisode
import com.hereliesaz.geministrator.memory.MemoryEpisodeId
import com.hereliesaz.geministrator.memory.MemoryNode
import com.hereliesaz.geministrator.memory.MemoryNodeId
import com.hereliesaz.geministrator.memory.MemoryNodeKind
import com.hereliesaz.geministrator.memory.MemoryQueueEntry
import com.hereliesaz.geministrator.memory.MemoryQueueId
import com.hereliesaz.geministrator.memory.MemoryQueueStatus
import com.hereliesaz.geministrator.memory.MemoryRelationKind
import com.hereliesaz.geministrator.memory.MemorySection
import com.hereliesaz.geministrator.memory.MemorySectionId
import com.hereliesaz.geministrator.memory.MemorySnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MemorySimpleViewTest {
    @Test
    fun simpleIsTheDefaultAndUnknownStoredViewsFallBackToIt() {
        assertEquals(MemoryView.Simple, MemoryView.fromStored(null))
        assertEquals(MemoryView.Simple, MemoryView.fromStored("Fancy"))
        assertEquals(MemoryView.Technical, MemoryView.fromStored(MemoryView.Technical.name))
    }

    @Test
    fun theFourStagesCoverEveryRealStageOnceAndObserveAndRecallNone() {
        val covered = MemorySimpleStage.entries.flatMap { it.stages }
        assertEquals(MemoryConsolidationStage.entries.toSet() - MemoryConsolidationStage.Complete, covered.toSet())
        assertEquals(covered.size, covered.toSet().size)
        assertEquals(setOf(MemoryConsolidationStage.Sectioning), MemorySimpleStage.Section.stages)
        assertEquals(emptySet(), MemorySimpleStage.Observe.stages + MemorySimpleStage.Recall.stages)
        assertEquals(MemorySimpleStage.Consolidate, MemorySimpleStage.of(MemoryConsolidationStage.Condensation))
        assertNull(MemorySimpleStage.of(MemoryConsolidationStage.Complete))
        assertNull(MemorySimpleStage.of(null))
    }

    @Test
    fun figuresAreCountedFromTheSnapshot() {
        val e1 = MemoryEpisode(MemoryEpisodeId("e1"), "s1", userPrompt = "p", chunks = emptyList(), createdAtEpochMillis = 1)
        val e2 = MemoryEpisode(MemoryEpisodeId("e2"), "s2", userPrompt = "p", chunks = emptyList(), createdAtEpochMillis = 2)
        fun node(id: String, kind: MemoryNodeKind, at: Long, vararg episodes: MemoryEpisodeId) =
            MemoryNode(MemoryNodeId(id), kind, "text $id", sourceEpisodeIds = episodes.toSet(), createdAtEpochMillis = at)
        fun edge(id: String, from: String, to: String, relation: MemoryRelationKind) =
            MemoryEdge(MemoryEdgeId(id), MemoryNodeId(from), MemoryNodeId(to), relation, createdAtEpochMillis = 5)
        val snapshot = MemorySnapshot(
            episodes = listOf(e1, e2),
            sections = listOf(MemorySection(MemorySectionId("s"), e1.id, emptySet(), 0, "section")),
            nodes = listOf(
                node("old", MemoryNodeKind.Summary, 10, e1.id),
                node("tag", MemoryNodeKind.NounTag, 40, e1.id),
                node("new", MemoryNodeKind.Summary, 20, e1.id, e2.id),
                node("newest", MemoryNodeKind.Summary, 30, e2.id),
            ),
            edges = listOf(
                edge("sup", "new", "newest", MemoryRelationKind.Supersedes),
                edge("r1", "tag", "new", MemoryRelationKind.Recalled),
                edge("r2", "tag", "old", MemoryRelationKind.Recalled),
            ),
            queue = listOf(
                MemoryQueueEntry(MemoryQueueId("q1"), 1, e1.id, stage = MemoryConsolidationStage.Tags, createdAtEpochMillis = 1),
                MemoryQueueEntry(MemoryQueueId("q2"), 2, e2.id, stage = MemoryConsolidationStage.Tags, status = MemoryQueueStatus.Complete, createdAtEpochMillis = 1),
            ),
        )
        val field = memorySimpleField(snapshot, MemoryActivity(working = MemoryConsolidationStage.Sectioning))
        assertEquals(2, field.sessionsBanked)
        assertEquals(1, field.sections)
        assertEquals(4, field.memories)
        assertEquals(1, field.links)
        assertEquals(2, field.recalls)
        assertEquals(1, field.waiting)
        assertEquals(MemorySimpleStage.Section, field.working)
        // "newest" is superseded and tags are not shown: the latest standing summary is "new".
        assertEquals("new", field.latestMemory?.id?.value)
    }

    @Test
    fun anEmptyBankHasNoLatestMemoryAndNothingWorking() {
        val field = memorySimpleField(MemorySnapshot(), MemoryActivity())
        assertNull(field.latestMemory)
        assertNull(field.working)
        assertEquals(0, field.sessionsBanked + field.sections + field.memories + field.links + field.recalls + field.waiting)
    }
}
