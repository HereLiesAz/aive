package com.hereliesaz.geministrator.memory

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MemoryLayerTest {
    @Test
    fun consolidationProcessesOneBoundedSessionAtATime() = runBlocking {
        val store = InMemoryMemoryStore()
        val queue = MemoryConsolidationQueue(store, maxChunkChars = 18)
        val first = queue.enqueueSession(
            MemorySessionEnvelope(
                sourceSessionId = "session-one",
                projectId = "project",
                workflowRunId = "run-one",
                taskRunId = "task-one",
                userPrompt = "Remember the first session",
                parts = listOf(
                    MemorySessionPart(
                        MemorySourceKind.Message,
                        "Agent message",
                        "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda",
                    ),
                ),
                closedAtEpochMillis = 100,
            ),
        )
        val second = queue.enqueueSession(
            MemorySessionEnvelope(
                sourceSessionId = "session-two",
                projectId = "project",
                workflowRunId = "run-two",
                taskRunId = "task-two",
                userPrompt = "Remember the second session",
                parts = listOf(MemorySessionPart(MemorySourceKind.Message, "Agent message", "second memory")),
                closedAtEpochMillis = 200,
            ),
        )

        val seenPackets = mutableListOf<MemoryWorkPacket>()
        val manager = object : MemoryManagerAgent {
            override suspend fun process(packet: MemoryWorkPacket): MemoryMutationBatch {
                seenPackets += packet
                return if (packet.stage == MemoryConsolidationStage.Sectioning) {
                    MemoryMutationBatch(
                        sectionsToAdd = packet.items.mapIndexed { index, item ->
                            MemorySection(
                                id = MemorySectionId("${packet.queueId.value}:test-section:$index:${item.id}"),
                                episodeId = packet.episodeId,
                                sourceChunkIds = setOf(MemoryChunkId(item.id)),
                                ordinal = item.metadata["ordinal"]?.toIntOrNull() ?: index,
                                text = item.text,
                            )
                        },
                    )
                } else {
                    MemoryMutationBatch()
                }
            }
        }
        val consolidator = MemoryConsolidator(
            store = store,
            manager = manager,
            policy = MemoryConsolidationPolicy(maxPacketItems = 2, maxPacketChars = 36),
        )

        var firstCompleted = false
        var iteration = 0
        while (!firstCompleted && iteration < 20) {
            val result = consolidator.processNext(300 + iteration)
            firstCompleted = result is MemoryConsolidationResult.Completed && result.queueId == first.id
            iteration += 1
        }
        assertTrue(firstCompleted)
        assertTrue(seenPackets.isNotEmpty())
        assertTrue(seenPackets.all { packet ->
            packet.items.size <= 2 &&
                packet.items.sumOf { it.text.length } + packet.neighborhood.sumOf { it.text.length } <= 36
        })
        assertTrue(seenPackets.all { it.queueId == first.id })

        val next = consolidator.processNext(500)
        assertIs<MemoryConsolidationResult.Applied>(next)
        assertEquals(second.id, next.queueId)
    }

    @Test
    fun grepChoosesResolutionAndReturnsAssociativeConflicts() = runBlocking {
        val episode = MemoryEpisode(
            id = MemoryEpisodeId("episode"),
            sourceSessionId = "session",
            projectId = "project",
            userPrompt = "Deploy the app",
            chunks = emptyList(),
            createdAtEpochMillis = 1,
        )
        val context = MemoryNode(
            id = MemoryNodeId("context"),
            kind = MemoryNodeKind.Context,
            text = "The deployment uses the production workflow.",
            sourceEpisodeIds = setOf(episode.id),
            createdAtEpochMillis = 1,
        )
        val phrase = MemoryNode(
            id = MemoryNodeId("phrase"),
            kind = MemoryNodeKind.Phrase,
            text = "deploy through production workflow",
            sourceEpisodeIds = setOf(episode.id),
            createdAtEpochMillis = 2,
        )
        val summary = MemoryNode(
            id = MemoryNodeId("summary"),
            kind = MemoryNodeKind.Summary,
            text = "Production deployment is performed through the release workflow.",
            sourceEpisodeIds = setOf(episode.id),
            createdAtEpochMillis = 3,
        )
        val conflicting = MemoryNode(
            id = MemoryNodeId("conflicting"),
            kind = MemoryNodeKind.Summary,
            text = "Production deployment must be performed manually.",
            sourceEpisodeIds = setOf(episode.id),
            createdAtEpochMillis = 4,
        )
        val category = MemoryNode(
            id = MemoryNodeId("category"),
            kind = MemoryNodeKind.Category,
            text = "deployment workflow",
            sourceEpisodeIds = setOf(episode.id),
            createdAtEpochMillis = 5,
        )
        val edges = listOf(
            MemoryEdge(MemoryEdgeId("e1"), phrase.id, context.id, MemoryRelationKind.Composes, createdAtEpochMillis = 2),
            MemoryEdge(MemoryEdgeId("e2"), summary.id, phrase.id, MemoryRelationKind.Summarizes, createdAtEpochMillis = 3),
            MemoryEdge(MemoryEdgeId("e3"), category.id, summary.id, MemoryRelationKind.Categorizes, createdAtEpochMillis = 5),
            MemoryEdge(MemoryEdgeId("e4"), summary.id, conflicting.id, MemoryRelationKind.ConflictsWith, createdAtEpochMillis = 6),
        )
        val store = InMemoryMemoryStore()
        assertTrue(
            store.commit(
                0,
                MemoryStoreMutation(
                    episodesToAdd = listOf(episode),
                    nodesToAdd = listOf(context, phrase, summary, conflicting, category),
                    edgesToAdd = edges,
                ),
            ),
        )
        val tool = GraphMemoryTool(store)

        val recalled = tool.grep(
            MemoryQuery(
                text = "production deployment workflow",
                resolution = MemoryResolution.Summary,
                projectId = "project",
            ),
        )
        assertTrue(recalled.hits.isNotEmpty())
        assertEquals(MemoryNodeKind.Summary, recalled.hits.first().node.kind)
        assertTrue(recalled.hits.first().conflicts.any { it.id == conflicting.id })

        val evidence = tool.expand(summary.id, MemoryResolution.Context)
        assertTrue(evidence.any { it.node.id == context.id })
    }

    @Test
    fun staleStoreRevisionCannotOverwriteNewMemory() = runBlocking {
        val store = InMemoryMemoryStore()
        val first = MemoryEpisode(
            id = MemoryEpisodeId("first"),
            sourceSessionId = "one",
            userPrompt = "one",
            chunks = emptyList(),
            createdAtEpochMillis = 1,
        )
        val second = first.copy(id = MemoryEpisodeId("second"), sourceSessionId = "two", userPrompt = "two")
        assertTrue(store.commit(0, MemoryStoreMutation(episodesToAdd = listOf(first))))
        assertEquals(false, store.commit(0, MemoryStoreMutation(episodesToAdd = listOf(second))))
        assertEquals(listOf(first.id), store.read().episodes.map(MemoryEpisode::id))
    }
}
