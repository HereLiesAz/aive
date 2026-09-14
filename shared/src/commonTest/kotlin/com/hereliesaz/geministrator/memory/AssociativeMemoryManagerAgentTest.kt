package com.hereliesaz.geministrator.memory

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFailsWith

class AssociativeMemoryManagerAgentTest {
    @Test
    fun curatorCannotClassifyTwoMemoriesAsConflicting() = runBlocking {
        val delegate = object : MemoryManagerAgent {
            override suspend fun process(packet: MemoryWorkPacket): MemoryMutationBatch = MemoryMutationBatch(
                edgesToAdd = listOf(
                    MemoryEdge(
                        id = MemoryEdgeId("edge"),
                        from = MemoryNodeId("left"),
                        to = MemoryNodeId("right"),
                        relation = MemoryRelationKind.ConflictsWith,
                        createdAtEpochMillis = 1,
                    ),
                ),
            )
        }
        val guarded = AssociativeMemoryManagerAgent(delegate)

        assertFailsWith<IllegalArgumentException> {
            guarded.process(
                MemoryWorkPacket(
                    queueId = MemoryQueueId("queue"),
                    episodeId = MemoryEpisodeId("episode"),
                    stage = MemoryConsolidationStage.Associations,
                    packetKey = "associations-0",
                    items = emptyList(),
                    instruction = "associate related memories",
                ),
            )
        }
    }

    @Test
    fun curatorMayAssociateRelatedMemoriesWithoutJudgingTruth() = runBlocking {
        val expected = MemoryMutationBatch(
            edgesToAdd = listOf(
                MemoryEdge(
                    id = MemoryEdgeId("edge"),
                    from = MemoryNodeId("left"),
                    to = MemoryNodeId("right"),
                    relation = MemoryRelationKind.AssociatedWith,
                    createdAtEpochMillis = 1,
                ),
            ),
        )
        val delegate = object : MemoryManagerAgent {
            override suspend fun process(packet: MemoryWorkPacket): MemoryMutationBatch = expected
        }

        AssociativeMemoryManagerAgent(delegate).process(
            MemoryWorkPacket(
                queueId = MemoryQueueId("queue"),
                episodeId = MemoryEpisodeId("episode"),
                stage = MemoryConsolidationStage.Associations,
                packetKey = "associations-0",
                items = emptyList(),
                instruction = "associate related memories",
            ),
        )
    }
}
