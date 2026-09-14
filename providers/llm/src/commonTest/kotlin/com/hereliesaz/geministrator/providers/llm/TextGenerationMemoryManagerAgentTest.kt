package com.hereliesaz.geministrator.providers.llm

import com.hereliesaz.geministrator.memory.MemoryConsolidationStage
import com.hereliesaz.geministrator.memory.MemoryEpisodeId
import com.hereliesaz.geministrator.memory.MemoryNodeId
import com.hereliesaz.geministrator.memory.MemoryNodeKind
import com.hereliesaz.geministrator.memory.MemoryQueueId
import com.hereliesaz.geministrator.memory.MemoryRelationKind
import com.hereliesaz.geministrator.memory.MemorySectionId
import com.hereliesaz.geministrator.memory.MemoryWorkItem
import com.hereliesaz.geministrator.memory.MemoryWorkPacket
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TextGenerationMemoryManagerAgentTest {
    @Test
    fun generatedNodeInheritsPacketProvenanceAndUsesPacketScopedIds() = runBlocking {
        val api = object : TextGenerationApi {
            override suspend fun generate(prompt: String): TextGenerationResult = TextGenerationResult(
                text = """
                    {
                      "nodes": [
                        {
                          "key": "deployment",
                          "kind": "NounTag",
                          "text": "deployment",
                          "sourceIds": ["context-node"],
                          "salience": 0.8,
                          "confidence": 0.9
                        }
                      ],
                      "links": [
                        {
                          "from": "deployment",
                          "to": "context-node",
                          "relation": "Indexes",
                          "weight": 1.0
                        }
                      ]
                    }
                """.trimIndent(),
            )
        }
        val agent = TextGenerationMemoryManagerAgent(api, nowEpochMillis = { 123L })
        val packet = MemoryWorkPacket(
            queueId = MemoryQueueId("queue"),
            episodeId = MemoryEpisodeId("current-episode"),
            stage = MemoryConsolidationStage.Tags,
            packetKey = "tags-48",
            items = listOf(
                MemoryWorkItem(
                    id = "context-node",
                    kind = "node:Context",
                    text = "Deploy through the production workflow.",
                    metadata = mapOf(
                        "sourceEpisodeIds" to "older-episode",
                        "sourceSectionIds" to "section-1",
                    ),
                ),
            ),
            instruction = "Extract tags.",
        )

        val mutation = agent.process(packet)

        assertEquals(1, mutation.nodesToAdd.size)
        val node = mutation.nodesToAdd.single()
        assertEquals(MemoryNodeKind.NounTag, node.kind)
        assertTrue(node.id.value.contains("tags-48"))
        assertTrue(MemoryEpisodeId("current-episode") in node.sourceEpisodeIds)
        assertTrue(MemoryEpisodeId("older-episode") in node.sourceEpisodeIds)
        assertTrue(MemorySectionId("section-1") in node.sourceSectionIds)
        assertEquals(1, mutation.edgesToAdd.size)
        assertEquals(MemoryRelationKind.Indexes, mutation.edgesToAdd.single().relation)
        assertEquals(MemoryNodeId("context-node"), mutation.edgesToAdd.single().to)
    }

    @Test
    fun modelCannotClaimProvenanceOutsideItsPacket() = runBlocking {
        val api = object : TextGenerationApi {
            override suspend fun generate(prompt: String): TextGenerationResult = TextGenerationResult(
                text = """
                    {
                      "nodes": [
                        {
                          "key": "bad",
                          "kind": "Summary",
                          "text": "invented provenance",
                          "sourceIds": ["not-in-packet"]
                        }
                      ]
                    }
                """.trimIndent(),
            )
        }
        val agent = TextGenerationMemoryManagerAgent(api, nowEpochMillis = { 123L })
        val packet = MemoryWorkPacket(
            queueId = MemoryQueueId("queue"),
            episodeId = MemoryEpisodeId("episode"),
            stage = MemoryConsolidationStage.Summaries,
            packetKey = "summaries-0",
            items = listOf(MemoryWorkItem("phrase", "node:Phrase", "known phrase")),
            instruction = "Summarize.",
        )

        assertFailsWith<IllegalArgumentException> { agent.process(packet) }
    }
}
