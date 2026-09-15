package com.hereliesaz.geministrator.memory

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryLexicalSemanticsTest {
    @Test
    fun technicalNounAndVerbPacketsBypassFallbackModels() = runBlocking {
        val nounFallback = RecordingTagAgent(MemoryMicroAgentRole.NounTagger)
        val verbFallback = RecordingTagAgent(MemoryMicroAgentRole.VerbTagger)
        val packet = technicalTagPacket()

        val nounBatch = ProgrammaticSemanticTaggerMicroAgent(nounFallback) { 1_234L }.process(packet)
        val verbBatch = ProgrammaticSemanticTaggerMicroAgent(verbFallback) { 1_234L }.process(packet)

        assertEquals(0, nounFallback.calls)
        assertEquals(0, verbFallback.calls)
        assertTrue(nounBatch.nodesToAdd.isNotEmpty())
        assertTrue(nounBatch.nodesToAdd.all { it.kind == MemoryNodeKind.NounTag })
        assertTrue(nounBatch.nodesToAdd.all { it.metadata["modelBypassed"] == "true" })
        assertTrue(nounBatch.edgesToAdd.all { it.relation == MemoryRelationKind.Indexes })
        assertTrue(verbBatch.nodesToAdd.any { it.text == "persist" || it.text == "write" })
        assertTrue(verbBatch.nodesToAdd.all { it.kind == MemoryNodeKind.VerbTag })
        assertTrue(verbBatch.nodesToAdd.all { it.createdAtEpochMillis == 1_234L })
    }

    @Test
    fun ambiguousNaturalLanguageFallsBackToTrainedSpecialist() = runBlocking {
        val fallback = RecordingTagAgent(MemoryMicroAgentRole.VerbTagger)
        val packet = technicalTagPacket().copy(
            items = listOf(
                technicalTagPacket().items.single().copy(
                    text = "The mural felt different after the rain.",
                ),
            ),
        )

        ProgrammaticSemanticTaggerMicroAgent(fallback) { 1_234L }.process(packet)

        assertEquals(1, fallback.calls)
    }

    @Test
    fun lexicalAssociatorKeepsHeuristicEvidenceDistinctFromBookkeeping() = runBlocking {
        val episode = MemoryEpisodeId("episode")
        val store = InMemoryMemoryStore(
            MemorySnapshot(
                nodes = listOf(
                    MemoryNode(
                        id = MemoryNodeId("left"),
                        kind = MemoryNodeKind.Context,
                        text = "service writes cache",
                        sourceEpisodeIds = setOf(episode),
                        createdAtEpochMillis = 10L,
                    ),
                    MemoryNode(
                        id = MemoryNodeId("right"),
                        kind = MemoryNodeKind.Context,
                        text = "worker writes cache",
                        sourceEpisodeIds = setOf(episode),
                        createdAtEpochMillis = 20L,
                    ),
                ),
            ),
        )

        val added = MemoryLexicalAssociator(store).refresh(30L)
        val lexical = store.read().edges.filter { it.metadata["evidenceClass"] == "lexical-structural" }

        assertTrue(added > 0)
        assertTrue(lexical.isNotEmpty())
        assertTrue(lexical.all { it.relation == MemoryRelationKind.AssociatedWith })
        assertTrue(lexical.all { it.metadata["deterministic"] == "true" })
        assertTrue(lexical.all { it.metadata["heuristic"] == "true" })
        assertTrue(lexical.any { it.metadata["featureKind"] == MemoryLexicalFeatureKind.VerbObject.name })
    }

    @Test
    fun lexicalAnalysisProvidesConveyInspiredStructuralSignatures() {
        val analysis = memoryLexicalFeatures("service writes cache")

        assertTrue("write" in analysis.values(MemoryLexicalFeatureKind.VerbLemma))
        assertTrue("service|write" in analysis.values(MemoryLexicalFeatureKind.SubjectVerb))
        assertTrue("write|cache" in analysis.values(MemoryLexicalFeatureKind.VerbObject))
        assertTrue("service|write|cache" in analysis.values(MemoryLexicalFeatureKind.SubjectVerbObject))
        assertFalse(analysis.stronglyTechnical)
    }

    private fun technicalTagPacket(): MemoryWorkPacket = MemoryWorkPacket(
        queueId = MemoryQueueId("queue"),
        episodeId = MemoryEpisodeId("episode"),
        stage = MemoryConsolidationStage.Tags,
        packetKey = "tags-0",
        items = listOf(
            MemoryWorkItem(
                id = "context-1",
                kind = "node:${MemoryNodeKind.Context.name}",
                text = "`UserRepository.saveUser()` writes /users and persists user data.",
                metadata = mapOf(
                    "sourceEpisodeIds" to "episode",
                    "sourceSectionIds" to "section-1",
                    "salience" to "0.8",
                ),
            ),
        ),
        instruction = "tag",
    )

    private class RecordingTagAgent(
        override val role: MemoryMicroAgentRole,
    ) : MemoryMicroAgent {
        override val model = MemoryMicroAgentModelSpec(modelId = "epoch8-${role.name.lowercase()}")
        var calls: Int = 0

        override suspend fun process(packet: MemoryWorkPacket): MemoryMutationBatch {
            calls += 1
            return MemoryMutationBatch()
        }
    }
}
