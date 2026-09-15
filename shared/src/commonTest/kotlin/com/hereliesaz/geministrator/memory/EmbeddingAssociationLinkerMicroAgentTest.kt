package com.hereliesaz.geministrator.memory

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EmbeddingAssociationLinkerMicroAgentTest {
    @Test
    fun emitsOnlySemanticSimilarityForRelatedVisibleNodes() = runBlocking {
        val runtime = FakeEmbeddingRuntime()
        val agent = EmbeddingAssociationLinkerMicroAgent(
            model = MemoryMicroAgentModelSpec(
                modelId = "minilm-association",
                requirements = MemoryModelRequirements.embeddings(),
            ),
            runtime = runtime,
            minimumSimilarity = 0.8f,
            nowEpochMillis = { 1L },
        )
        val batch = agent.process(
            MemoryWorkPacket(
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
            ),
        )

        assertEquals(1, batch.edgesToAdd.size)
        val edge = batch.edgesToAdd.single()
        assertEquals(MemoryRelationKind.SimilarTo, edge.relation)
        assertEquals(setOf("a", "b"), setOf(edge.from.value, edge.to.value))
        assertTrue(edge.weight >= 0.8f)
    }

    private class FakeEmbeddingRuntime : MemoryEmbeddingInferenceRuntime {
        override val platform = MemoryMicroAgentPlatform.Linux
        override val computePreference = MemoryComputePreference.AUTO
        override val capabilityDetector = object : HardwareCapabilityDetector {
            override suspend fun discover(): List<MemoryComputeDevice> = listOf(
                MemoryComputeDevice("CPUExecutionProvider", "CPU", MemoryComputeDeviceType.CPU),
            )
        }

        override suspend fun isAvailable(
            model: MemoryMicroAgentModelSpec,
            artifact: MemoryMicroAgentArtifact,
        ): Boolean = artifact.platform == platform

        override suspend fun embed(request: MemoryEmbeddingInferenceRequest): MemoryEmbeddingInferenceResult =
            MemoryEmbeddingInferenceResult(
                vectors = listOf(
                    listOf(1f, 0f),
                    listOf(0.99f, 0.01f),
                    listOf(0f, 1f),
                ),
            )
    }
}
