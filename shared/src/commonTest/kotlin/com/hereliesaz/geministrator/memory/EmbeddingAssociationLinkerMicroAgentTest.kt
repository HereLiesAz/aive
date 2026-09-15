package com.hereliesaz.geministrator.memory

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EmbeddingAssociationLinkerMicroAgentTest {
    @Test
    fun emitsOnlySemanticSimilarityForRelatedVisibleNodes() = runBlocking {
        val runtime = FakeEmbeddingRuntime()
        val agent = associationAgent(runtime)
        val batch = agent.process(associationPacket())

        assertEquals(1, batch.edgesToAdd.size)
        val edge = batch.edgesToAdd.single()
        assertEquals(MemoryRelationKind.SimilarTo, edge.relation)
        assertEquals(setOf("a", "b"), setOf(edge.from.value, edge.to.value))
        assertTrue(edge.weight >= 0.8f)
    }

    @Test
    fun reusesCachedEmbeddingsWhenNeighborhoodRepeats() = runBlocking {
        val runtime = FakeEmbeddingRuntime()
        val agent = associationAgent(runtime)

        agent.process(associationPacket())
        val second = agent.process(associationPacket().copy(packetKey = "a1"))

        assertEquals(1, runtime.embedCalls)
        assertTrue(second.edgesToAdd.all { it.metadata["embeddingCacheHits"] == "3" })
        assertTrue(second.edgesToAdd.all { it.metadata["embeddingCacheMisses"] == "0" })
    }

    @Test
    fun skipsModelEntirelyWhenEveryPairHasDecisiveLexicalEvidence() = runBlocking {
        val runtime = FakeEmbeddingRuntime()
        val agent = associationAgent(runtime)
        val packet = associationPacket().copy(
            items = listOf(
                MemoryWorkItem(
                    "a",
                    "node:Context",
                    "`UserRepository.saveUser()` writes /users.",
                ),
            ),
            neighborhood = listOf(
                MemoryWorkItem(
                    "b",
                    "node:Context",
                    "Update `UserRepository.saveUser()` before persisting /users.",
                ),
            ),
        )

        val batch = agent.process(packet)

        assertEquals(0, runtime.embedCalls)
        assertTrue(batch.edgesToAdd.isEmpty())
    }

    @Test
    fun shortAcronymOverlapDoesNotSuppressSemanticComparison() = runBlocking {
        val runtime = FakeEmbeddingRuntime()
        val agent = associationAgent(runtime)

        agent.process(associationPacket())

        assertEquals(1, runtime.embedCalls)
        assertTrue(runtime.lastTexts.any { "30 seconds" in it })
        assertTrue(runtime.lastTexts.any { "60 seconds" in it })
    }

    @Test
    fun embedsOnlyNodesParticipatingInUnexplainedPairs() = runBlocking {
        val runtime = FakeEmbeddingRuntime()
        val agent = associationAgent(runtime)
        val packet = MemoryWorkPacket(
            queueId = MemoryQueueId("q"),
            episodeId = MemoryEpisodeId("e"),
            stage = MemoryConsolidationStage.Associations,
            packetKey = "residue",
            items = listOf(
                MemoryWorkItem("a", "node:Context", "`UserRepository.saveUser()` writes /users."),
            ),
            neighborhood = listOf(
                MemoryWorkItem("b", "node:Context", "Refactor `UserRepository.saveUser()` for /users."),
                MemoryWorkItem("c", "node:Summary", "The weather turned colder overnight."),
            ),
            instruction = "associate",
        )

        agent.process(packet)

        assertEquals(1, runtime.embedCalls)
        assertEquals(2, runtime.lastTexts.size)
        assertFalse(runtime.lastTexts.any { it.startsWith("Refactor") })
        assertTrue(runtime.lastTexts.any { it.startsWith("`UserRepository") })
        assertTrue(runtime.lastTexts.any { it.startsWith("The weather") })
    }

    private fun associationAgent(runtime: FakeEmbeddingRuntime) = EmbeddingAssociationLinkerMicroAgent(
        model = MemoryMicroAgentModelSpec(
            modelId = "minilm-association",
            requirements = MemoryModelRequirements.embeddings(),
        ),
        runtime = runtime,
        minimumSimilarity = 0.8f,
        nowEpochMillis = { 1L },
    )

    private fun associationPacket(): MemoryWorkPacket = MemoryWorkPacket(
        queueId = MemoryQueueId("q"),
        episodeId = MemoryEpisodeId("e"),
        stage = MemoryConsolidationStage.Associations,
        packetKey = "a0",
        items = listOf(
            MemoryWorkItem("a", "node:Summary", "API timeout is configured for 30 seconds."),
        ),
        neighborhood = listOf(
            MemoryWorkItem("b", "node:Summary", "API timeout is configured for 60 seconds."),
            MemoryWorkItem("c", "node:Summary", "The UI uses a dark theme."),
        ),
        instruction = "associate",
    )

    private class FakeEmbeddingRuntime : MemoryEmbeddingInferenceRuntime {
        override val platform = MemoryMicroAgentPlatform.Linux
        override val computePreference = MemoryComputePreference.AUTO
        override val capabilityDetector = object : HardwareCapabilityDetector {
            override suspend fun discover(): List<MemoryComputeDevice> = listOf(
                MemoryComputeDevice("CPUExecutionProvider", "CPU", MemoryComputeDeviceType.CPU),
            )
        }
        var embedCalls: Int = 0
        var lastTexts: List<String> = emptyList()

        override suspend fun isAvailable(
            model: MemoryMicroAgentModelSpec,
            artifact: MemoryMicroAgentArtifact,
        ): Boolean = artifact.platform == platform

        override suspend fun embed(request: MemoryEmbeddingInferenceRequest): MemoryEmbeddingInferenceResult {
            embedCalls += 1
            lastTexts = request.texts
            val vectors = request.texts.map { text ->
                when {
                    "30 seconds" in text -> listOf(1f, 0f)
                    "60 seconds" in text -> listOf(0.99f, 0.01f)
                    "weather" in text.lowercase() -> listOf(0f, 1f)
                    else -> listOf(1f, 0f)
                }
            }
            return MemoryEmbeddingInferenceResult(vectors = vectors)
        }
    }
}
