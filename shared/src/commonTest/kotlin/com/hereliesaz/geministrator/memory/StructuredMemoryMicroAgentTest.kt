package com.hereliesaz.geministrator.memory

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class StructuredMemoryMicroAgentTest {
    @Test
    fun selectsLocalArtifactOnEveryHaivePlatform() = runBlocking {
        MemoryMicroAgentPlatform.entries.forEach { platform ->
            val runtime = FakeRuntime(platform)
            val agent = StructuredMemoryMicroAgent(
                role = MemoryMicroAgentRole.Sectioner,
                model = MemoryMicroAgentModelSpec("portable-memory"),
                runtime = runtime,
                nowEpochMillis = { 1L },
            )
            agent.process(
                MemoryWorkPacket(
                    queueId = MemoryQueueId("q"),
                    episodeId = MemoryEpisodeId("e"),
                    stage = MemoryConsolidationStage.Sectioning,
                    packetKey = "s0",
                    items = listOf(MemoryWorkItem("chunk", "chunk", "remember this")),
                    instruction = "section",
                ),
            )
            assertEquals(platform, runtime.lastRequest?.artifact?.platform)
        }
    }

    private class FakeRuntime(
        override val platform: MemoryMicroAgentPlatform,
    ) : MemoryGenerativeInferenceRuntime {
        override val computePreference = MemoryComputePreference.AUTO
        override val capabilityDetector = object : HardwareCapabilityDetector {
            override suspend fun discover(): List<MemoryComputeDevice> = listOf(
                MemoryComputeDevice("CPUExecutionProvider", "CPU", MemoryComputeDeviceType.CPU),
            )
        }
        var lastRequest: MemoryGenerativeInferenceRequest? = null

        override suspend fun isAvailable(
            model: MemoryMicroAgentModelSpec,
            artifact: MemoryMicroAgentArtifact,
        ): Boolean = artifact.platform == platform

        override suspend fun generate(request: MemoryGenerativeInferenceRequest): MemoryGenerativeInferenceResult {
            lastRequest = request
            return MemoryGenerativeInferenceResult("{\"sections\":[],\"nodes\":[],\"links\":[]}")
        }
    }
}
