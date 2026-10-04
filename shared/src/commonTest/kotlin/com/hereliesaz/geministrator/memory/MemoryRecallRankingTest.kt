package com.hereliesaz.geministrator.memory

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryRecallRankingTest {
    private fun node(id: String, text: String, metadata: Map<String, String> = emptyMap()) =
        MemoryNode(MemoryNodeId(id), MemoryNodeKind.Context, text, createdAtEpochMillis = 0L, metadata = metadata)

    @Test
    fun rareTermsOutweighCommonOnesAndStopwordsMatchNothing() {
        val nodes = listOf(
            node("a", "the build uses the gradle cache"),
            node("b", "the build failed"),
            node("c", "the build passed"),
            node("d", "the sqldelight schema migration"),
        )
        val index = MemoryBm25Index(nodes)
        val gradle = mapOf("gradle" to 1.0, "build" to 1.0)
        assertTrue(index.score(MemoryNodeId("a"), gradle) > index.score(MemoryNodeId("b"), gradle))
        assertTrue(index.idf("gradle") > index.idf("build"))
        assertEquals(0f, index.score(MemoryNodeId("d"), gradle))
    }

    @Test
    fun aliasesAreSearchableButBookkeepingIsNot() {
        val index = MemoryBm25Index(
            listOf(
                node("tag", "remove", mapOf(TAG_ALIASES to "delete | drop", TAG_KEY to "v:remove", "microAgentRole" to "VerbTagger")),
                node("other", "keep"),
            ),
        )
        assertTrue(index.score(MemoryNodeId("tag"), mapOf("delete" to 1.0)) > 0f)
        assertEquals(0f, index.score(MemoryNodeId("tag"), mapOf("verbtagger" to 1.0)))
    }

    @Test
    fun correlatedEvidenceCountsOnceAndIndependentEvidenceAccumulates() {
        fun edge(id: String, weight: Float, metadata: Map<String, String>) = MemoryEdge(
            MemoryEdgeId(id), MemoryNodeId("x"), MemoryNodeId("y"), MemoryRelationKind.AssociatedWith, weight, 0L, metadata,
        )
        // One verb read three ways: the strongest reading, not 0.993.
        val oneVerb = listOf(
            edge("l", 0.70f, mapOf("basis" to "lexical:VerbLemma", "featureKind" to "VerbLemma")),
            edge("c", 0.72f, mapOf("basis" to "lexical:VerbClass", "featureKind" to "VerbClass")),
            edge("s", 0.92f, mapOf("basis" to "lexical:VerbSense", "featureKind" to "VerbSense")),
        )
        assertEquals(0.92f, accumulateAssociationEvidence(oneVerb), 1e-4f)
        // Nested scopes are one fact.
        val scopes = listOf(edge("s1", 0.98f, mapOf("basis" to "scope:session")), edge("s2", 0.92f, mapOf("basis" to "scope:workflow-run")))
        assertEquals(0.98f, accumulateAssociationEvidence(scopes), 1e-4f)
        // A noun and a verb are independent: they still accumulate.
        val both = oneVerb + edge("n", 0.58f, mapOf("basis" to "lexical:NounLemma", "featureKind" to "NounLemma"))
        assertTrue(accumulateAssociationEvidence(both) > 0.92f)
    }

    @Test
    fun surfacedMemoriesAreNotOfferedAgainWithinTheNoveltyWindow() = runBlocking {
        val recall = AttentionGatedRecall(MemoryAttentionPolicy())
        val first = listOf(MemoryRecallHit(node("a", "alpha"), 0.95f))
        val second = first + MemoryRecallHit(node("b", "beta"), 0.95f)
        assertEquals(first, recall.select(first))
        // Past the cue interval but inside two intervals: only the new memory surfaces.
        recall.consumeTokens(recall.let { MemoryAttentionGate().cuePolicy(it.currentState()).minimumIntervalTokens })
        assertEquals(listOf("b"), recall.select(second).map { it.node.id.value })
    }
}
