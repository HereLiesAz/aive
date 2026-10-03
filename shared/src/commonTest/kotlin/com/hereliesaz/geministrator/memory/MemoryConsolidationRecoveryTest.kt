package com.hereliesaz.geministrator.memory

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A failing packet must not hold the queue forever; a declined cluster must not be offered again. */
class MemoryConsolidationRecoveryTest {
    private val policy = MemoryConsolidationPolicy(
        maxAttempts = 3,
        maxSimilarPerKind = MemoryConsolidationPolicy().maxSimilarPerKind + (MemoryNodeKind.Context to 2),
    )

    @Test
    fun permanentFailureParksAfterMaxAttemptsAndUnblocksTheQueue() = runBlocking {
        val broken = episode("broken", chunk = "always fails")
        val healthy = episode("healthy", chunk = "fine")
        val store = InMemoryMemoryStore()
        store.commit(
            0,
            MemoryStoreMutation(
                episodesToAdd = listOf(broken, healthy),
                queueUpserts = listOf(queued("q-broken", 1, broken.id), queued("q-healthy", 2, healthy.id)),
            ),
        )
        val manager = object : MemoryManagerAgent {
            override suspend fun process(packet: MemoryWorkPacket): MemoryMutationBatch {
                check(packet.episodeId != broken.id) { "model output unusable" }
                return MemoryMutationBatch()
            }
        }
        val consolidator = MemoryConsolidator(store, manager, policy)

        repeat(3) { assertIs<MemoryConsolidationResult.Failed>(consolidator.processNext(10L)) }
        // Parked: the next call reaches the healthy entry instead of retrying the broken one.
        var result = consolidator.processNext(10L)
        while (result !is MemoryConsolidationResult.Idle) {
            assertTrue(result !is MemoryConsolidationResult.Failed, "healthy entry failed: $result")
            result = consolidator.processNext(10L)
        }

        val queue = store.read().queue.associateBy { it.id.value }
        assertEquals(MemoryQueueStatus.Failed, queue.getValue("q-broken").status)
        assertEquals(3, queue.getValue("q-broken").attempt)
        assertEquals("model output unusable", queue.getValue("q-broken").lastError)
        assertEquals(MemoryQueueStatus.Complete, queue.getValue("q-healthy").status)
    }

    @Test
    fun declinedCondensationIsRecordedAndTheEntryCompletes() = runBlocking {
        val store = condensationStore()
        var calls = 0
        val manager = object : MemoryManagerAgent {
            override suspend fun process(packet: MemoryWorkPacket): MemoryMutationBatch {
                calls++
                return MemoryMutationBatch()
            }
        }
        val consolidator = MemoryConsolidator(store, manager, policy)

        assertIs<MemoryConsolidationResult.Declined>(consolidator.processNext(10L))
        assertIs<MemoryConsolidationResult.Completed>(consolidator.processNext(10L))
        assertEquals(1, calls)
        assertEquals(listOf("c-1|c-2|c-3"), store.read().declinedCondensations)
    }

    @Test
    fun condensationThatKeepsFailingIsDeclinedInsteadOfParked() = runBlocking {
        val store = condensationStore()
        val manager = object : MemoryManagerAgent {
            override suspend fun process(packet: MemoryWorkPacket): MemoryMutationBatch = error("garbled")
        }
        val consolidator = MemoryConsolidator(store, manager, policy)

        repeat(2) { assertIs<MemoryConsolidationResult.Failed>(consolidator.processNext(10L)) }
        assertIs<MemoryConsolidationResult.Declined>(consolidator.processNext(10L))
        assertIs<MemoryConsolidationResult.Completed>(consolidator.processNext(10L))
        assertEquals(1, store.read().declinedCondensations.size)
    }

    @Test
    fun structuredAgentRejectsOtherSchemasAndAcceptsDecline() = runBlocking {
        fun agent(role: MemoryMicroAgentRole, answer: String) = StructuredMemoryMicroAgent(
            role = role,
            model = MemoryMicroAgentModelSpec("portable-memory"),
            runtime = FixedRuntime(answer),
            nowEpochMillis = { 1L },
        )
        val packet = MemoryWorkPacket(
            queueId = MemoryQueueId("q"),
            episodeId = MemoryEpisodeId("e"),
            stage = MemoryConsolidationStage.Sectioning,
            packetKey = "s0",
            items = listOf(MemoryWorkItem("chunk", "chunk", "remember this")),
            instruction = "section",
        )
        // The epoch-8 training format used to decode to an empty proposal and silently drop the packet.
        val epoch8 = """{"mutations":[{"op":"segment","target_ref":"chunk","payload":{}}]}"""
        val failure = assertFailsWith<IllegalArgumentException> {
            agent(MemoryMicroAgentRole.Sectioner, epoch8).process(packet)
        }
        assertTrue("mutations" in failure.message.orEmpty())

        val declined = agent(MemoryMicroAgentRole.CondensationRewriter, "DO_NOT_CONDENSE")
            .process(packet.copy(stage = MemoryConsolidationStage.Condensation))
        assertEquals(0, declined.size)
    }

    @Test
    fun aClusterThatDisagreesOnAValueIsNeverOffered() = runBlocking {
        val store = condensationStore(texts = listOf("Max connections: 50.", "Max connections: 100.", "Max connections: 150."))
        var calls = 0
        val manager = object : MemoryManagerAgent {
            override suspend fun process(packet: MemoryWorkPacket): MemoryMutationBatch {
                calls++
                return MemoryMutationBatch()
            }
        }

        assertIs<MemoryConsolidationResult.Completed>(MemoryConsolidator(store, manager, policy).processNext(10L))

        assertEquals(0, calls, "no engine may be asked to merge memories that clash")
        assertTrue(store.read().edges.none { it.relation == MemoryRelationKind.Supersedes })
    }

    @Test
    fun aCondensationThatChangesAValueIsRejected() = runBlocking {
        val store = condensationStore()
        val manager = object : MemoryManagerAgent {
            override suspend fun process(packet: MemoryWorkPacket): MemoryMutationBatch {
                val merged = MemoryNode(
                    id = MemoryNodeId("merged"),
                    kind = MemoryNodeKind.Context,
                    text = "The connection pool is shared by 4 services.",
                    sourceEpisodeIds = setOf(MemoryEpisodeId("source")),
                    createdAtEpochMillis = 10L,
                )
                val edges = packet.items.flatMap { item ->
                    listOf(MemoryRelationKind.CondensedFrom, MemoryRelationKind.Supersedes).map { relation ->
                        MemoryEdge(MemoryEdgeId("${item.id}-$relation"), merged.id, MemoryNodeId(item.id), relation, createdAtEpochMillis = 10L)
                    }
                }
                return MemoryMutationBatch(nodesToAdd = listOf(merged), edgesToAdd = edges)
            }
        }

        val result = assertIs<MemoryConsolidationResult.Failed>(MemoryConsolidator(store, manager, policy).processNext(10L))

        assertEquals("Condensation must restate its sources' values unchanged", result.reason)
        assertTrue(store.read().edges.none { it.relation == MemoryRelationKind.Supersedes })
    }

    private suspend fun condensationStore(
        texts: List<String> = listOf("a", "b", "c").map { "The connection pool is shared, note $it." },
    ): InMemoryMemoryStore {
        val source = episode("source")
        val nodes = texts.mapIndexed { offset, text ->
            val index = offset + 1
            MemoryNode(
                id = MemoryNodeId("c-$index"),
                kind = MemoryNodeKind.Context,
                text = text,
                sourceEpisodeIds = setOf(source.id),
                createdAtEpochMillis = index.toLong(),
            )
        }
        val edges = nodes.zipWithNext().mapIndexed { index, (from, to) ->
            MemoryEdge(
                id = MemoryEdgeId("similar-$index"),
                from = from.id,
                to = to.id,
                relation = MemoryRelationKind.SimilarTo,
                weight = 1f,
                createdAtEpochMillis = 5L,
            )
        }
        return InMemoryMemoryStore().apply {
            commit(
                0,
                MemoryStoreMutation(
                    episodesToAdd = listOf(source),
                    nodesToAdd = nodes,
                    edgesToAdd = edges,
                    queueUpserts = listOf(
                        queued("q-condense", 1, source.id).copy(stage = MemoryConsolidationStage.Condensation),
                    ),
                ),
            )
        }
    }

    private fun queued(id: String, sequence: Long, episodeId: MemoryEpisodeId) = MemoryQueueEntry(
        id = MemoryQueueId(id),
        sequence = sequence,
        episodeId = episodeId,
        createdAtEpochMillis = sequence,
    )

    private fun episode(id: String, chunk: String? = null): MemoryEpisode {
        val episodeId = MemoryEpisodeId(id)
        return MemoryEpisode(
            id = episodeId,
            sourceSessionId = "session-$id",
            userPrompt = "prompt-$id",
            chunks = listOfNotNull(
                chunk?.let {
                    MemorySourceChunk(
                        id = MemoryChunkId("$id:chunk:0:0"),
                        episodeId = episodeId,
                        ordinal = 0,
                        kind = MemorySourceKind.UserPrompt,
                        label = "User prompt",
                        text = it,
                    )
                },
            ),
            createdAtEpochMillis = 1L,
        )
    }

    private class FixedRuntime(private val answer: String) : MemoryGenerativeInferenceRuntime {
        override val platform = MemoryMicroAgentPlatform.entries.first()
        override val computePreference = MemoryComputePreference.AUTO
        override val capabilityDetector = object : HardwareCapabilityDetector {
            override suspend fun discover(): List<MemoryComputeDevice> = listOf(
                MemoryComputeDevice("CPUExecutionProvider", "CPU", MemoryComputeDeviceType.CPU),
            )
        }

        override suspend fun isAvailable(model: MemoryMicroAgentModelSpec, artifact: MemoryMicroAgentArtifact) = true

        override suspend fun generate(request: MemoryGenerativeInferenceRequest) = MemoryGenerativeInferenceResult(answer)
    }
}
