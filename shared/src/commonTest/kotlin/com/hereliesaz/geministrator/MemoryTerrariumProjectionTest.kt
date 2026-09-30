package com.hereliesaz.geministrator

import com.hereliesaz.conveyance.h2g2.H2g2TerrariumRelationshipKind
import com.hereliesaz.conveyance.h2g2.H2g2WorkflowState
import com.hereliesaz.geministrator.memory.MemoryActivity
import com.hereliesaz.geministrator.memory.MemoryConsolidationStage
import com.hereliesaz.geministrator.memory.MemoryEngineProvider
import com.hereliesaz.geministrator.memory.MemoryEpisode
import com.hereliesaz.geministrator.memory.MemoryEpisodeId
import com.hereliesaz.geministrator.memory.MemoryLayerSettings
import com.hereliesaz.geministrator.memory.MemoryQueueEntry
import com.hereliesaz.geministrator.memory.MemoryQueueId
import com.hereliesaz.geministrator.memory.MemoryQueueStatus
import com.hereliesaz.geministrator.memory.MemorySnapshot
import com.hereliesaz.geministrator.memory.assembleAgents
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryTerrariumProjectionTest {
    private val settings = MemoryLayerSettings()
    private val engines = settings.assembleAgents(object : MemoryEngineProvider {})

    @Test
    fun everyStageGetsADistinctFittingMascot() {
        val kinds = MemoryTerrariumStage.entries.associateWith { classifyNodeCreatureRole(it.label) }
        assertTrue(kinds.values.none { it == NodeCreatureRoleKind.Generic }, "$kinds")
        assertEquals(NodeCreatureRoleKind.Connector, kinds[MemoryTerrariumStage.Associations])
        assertEquals(NodeCreatureRoleKind.KnowledgeKeeper, kinds[MemoryTerrariumStage.Tags])
    }

    @Test
    fun workingStageIsActiveAndItsInboundLinkCarries() {
        val episode = MemoryEpisode(MemoryEpisodeId("e"), "s", userPrompt = "p", chunks = emptyList(), createdAtEpochMillis = 1)
        val snapshot = MemorySnapshot(
            episodes = listOf(episode),
            queue = listOf(
                MemoryQueueEntry(MemoryQueueId("q"), 1, episode.id, stage = MemoryConsolidationStage.Tags, createdAtEpochMillis = 1),
                MemoryQueueEntry(
                    MemoryQueueId("p"), 2, episode.id, stage = MemoryConsolidationStage.Summaries,
                    status = MemoryQueueStatus.Failed, attempt = 3, createdAtEpochMillis = 1,
                ),
            ),
        )
        val projection = projectMemoryTerrarium(snapshot, settings, engines, MemoryActivity(working = MemoryConsolidationStage.Tags))
        val states = projection.subjects.associate { it.node.id to it.node.state }
        assertEquals(H2g2WorkflowState.Active, states[MemoryTerrariumStage.Tags.id])
        assertEquals(H2g2WorkflowState.Blocked, states[MemoryTerrariumStage.Summaries.id])
        val inbound = projection.relationships.single { it.to == MemoryTerrariumStage.Tags.id }
        assertTrue(inbound.active && inbound.kind == H2g2TerrariumRelationshipKind.Transfer)
        assertEquals(MemoryTerrariumStage.entries.size + 1, projection.subjects.size)
        assertTrue(projection.subjects.all { it.position == it.position.clamped() })
    }
}
