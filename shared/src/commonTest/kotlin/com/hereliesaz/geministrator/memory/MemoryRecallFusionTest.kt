package com.hereliesaz.geministrator.memory

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryRecallFusionTest {
    private fun node(id: String, text: String, salience: Float = 0.5f, createdAt: Long = 0L, episode: String? = null) = MemoryNode(
        id = MemoryNodeId(id),
        kind = MemoryNodeKind.Context,
        text = text,
        salience = salience,
        createdAtEpochMillis = createdAt,
        sourceEpisodeIds = setOfNotNull(episode?.let(::MemoryEpisodeId)),
    )

    private fun edge(from: String, to: String, weight: Float, metadata: Map<String, String>) = MemoryEdge(
        MemoryEdgeId("$from-$to-${metadata["basis"]}"), MemoryNodeId(from), MemoryNodeId(to), MemoryRelationKind.AssociatedWith, weight, 0L, metadata,
    )

    private fun episode(id: String, at: Long) = MemoryEpisode(
        id = MemoryEpisodeId(id),
        sourceSessionId = "session-$id",
        userPrompt = "prompt-$id",
        chunks = emptyList(),
        createdAtEpochMillis = at,
    )

    @Test
    fun scoresAreRelevanceOnlyAndSalienceOrdersOnlyAmongEquals() = runBlocking {
        val tool = GraphMemoryTool(
            InMemoryMemoryStore(
                MemorySnapshot(
                    nodes = listOf(
                        node("strong", "gradle cache warmup", salience = 0.1f),
                        node("weak", "gradle", salience = 1f),
                        node("twin", "gradle cache warmup", salience = 0.9f),
                    ),
                ),
            ),
        )
        val hits = tool.grip(MemoryQuery("gradle cache warmup", resolution = MemoryResolution.Context)).hits
        assertEquals(listOf("twin", "strong", "weak"), hits.map { it.node.id.value }, "salience breaks the tie, never outranks relevance")
        assertEquals(hits[0].score, hits[1].score, "salience does not add to the score")
        assertTrue(hits[0].score <= 1f)
    }

    @Test
    fun aSharedFeatureLinksEveryMemberThroughOneHubNotAChain() = runBlocking {
        val group = mapOf("basis" to "scope:session", EDGE_GROUP to "s1")
        val ids = listOf("a", "b", "c", "d", "e")
        val tool = GraphMemoryTool(
            InMemoryMemoryStore(
                MemorySnapshot(
                    nodes = ids.mapIndexed { i, id -> node(id, if (id == "a") "gradle cache" else "note $id", createdAt = i.toLong()) },
                    edges = ids.zipWithNext().map { (l, r) -> edge(l, r, 0.98f, group) },
                ),
            ),
        )
        val scores = tool.grip(MemoryQuery("gradle", resolution = MemoryResolution.Context, maxResults = 10)).hits
            .associate { it.node.id.value to it.score }
        assertEquals(scores.getValue("b"), scores.getValue("e"), 1e-6f, "the far end of the group is one hop away, like the near end")
    }

    @Test
    fun sequenceLinksFadeWithTheGapAndLongGapsStartANewEvent() {
        val hour = 60L * 60 * 1000
        val snapshot = MemorySnapshot(
            episodes = listOf(episode("e1", 0), episode("e2", 10 * 60 * 1000L), episode("e3", 13 * hour)),
            nodes = listOf(
                node("n1", "first", createdAt = 0, episode = "e1"),
                node("n2", "second", createdAt = 10 * 60 * 1000L, episode = "e2"),
                node("n3", "third", createdAt = 13 * hour, episode = "e3"),
            ),
        )
        val edges = snapshot.programmaticAssociationCandidates(nowEpochMillis = 14 * hour, limit = 500)
        fun sequence(a: String, b: String) = edges.single {
            it.metadata["basis"] == "sequence:adjacent-store" && setOf(it.from.value, it.to.value) == setOf(a, b)
        }.weight
        assertTrue(sequence("n1", "n2") > 0.66f, "ten minutes apart: nearly the full 0.68")
        assertTrue(sequence("n2", "n3") < 0.3f, "thirteen hours apart: faded toward the floor")
        val events = edges.filter { it.metadata["basis"] == "temporal:Event" }
        assertEquals(1, events.size, "e1 and e2 are one event; e3 starts another (alone)")
        assertEquals(setOf("n1", "n2"), setOf(events.single().from.value, events.single().to.value))
    }

    @Test
    fun aStandoutHitOpensTheGateWhereManyEqualOnesDoNot() = runBlocking {
        fun hits(vararg scores: Float) = scores.mapIndexed { i, s -> MemoryRecallHit(node("h$i-$s", "x"), s) }
        // Default dial 0.5: threshold 0.685.
        assertTrue(AttentionGatedRecall().select(hits(0.66f, 0.2f, 0.2f)).isNotEmpty())
        assertTrue(AttentionGatedRecall().select(hits(0.66f, 0.66f, 0.66f)).isEmpty())
    }

    @Test
    fun anOpenGateStaysOpenJustBelowTheThreshold() = runBlocking {
        val warmed = AttentionGatedRecall()
        assertTrue(warmed.select(listOf(MemoryRecallHit(node("first", "x"), 0.7f))).isNotEmpty())
        assertTrue(warmed.select(listOf(MemoryRecallHit(node("second", "x"), 0.66f))).isNotEmpty(), "open: 0.66 clears 0.685 - 0.05")
        assertTrue(AttentionGatedRecall().select(listOf(MemoryRecallHit(node("cold", "x"), 0.66f))).isEmpty(), "closed: 0.66 misses 0.685")
    }

    @Test
    fun cuesComeInSmallBurstsThenWaitForTokens() = runBlocking {
        val recall = AttentionGatedRecall()
        fun hit(id: String) = listOf(MemoryRecallHit(node(id, "x"), 0.95f))
        assertTrue(recall.select(hit("one")).isNotEmpty())
        assertTrue(recall.select(hit("two")).isNotEmpty(), "a second cue may follow at once")
        assertTrue(recall.select(hit("three")).isEmpty(), "the burst is spent")
        recall.consumeTokens(MemoryAttentionGate().cuePolicy(recall.currentState()).minimumIntervalTokens)
        assertTrue(recall.select(hit("four")).isNotEmpty(), "one interval of tokens refills one cue")
    }

    @Test
    fun memoriesRecalledTogetherAgainAndAgainGetLinkedAndNothingElseChanges() = runBlocking {
        val nodes = listOf(node("a", "alpha"), node("b", "beta"), node("c", "gamma"))
        val store = InMemoryMemoryStore(MemorySnapshot(nodes = nodes))
        val coRecall = MemoryCoRecall({ 5L })
        val ab = listOf(MemoryNodeId("a"), MemoryNodeId("b"))

        repeat(2) { assertTrue(coRecall.recalledTogether(store, ab).isEmpty()) }
        val first = coRecall.recalledTogether(store, ab)
        assertEquals(1, first.size, "the third time together links them")
        assertEquals("co-recall", first.single().metadata["basis"])
        repeat(30) { coRecall.recalledTogether(store, ab) }
        val snapshot = store.read()
        assertEquals(MemoryCoRecall.MAX_LINKS, snapshot.edges.size, "links stop at the cap")
        assertEquals(nodes, snapshot.nodes, "nodes are untouched")
        val strength = accumulateAssociationEvidence(snapshot.edges)
        assertTrue(strength > 0.7f && strength < 0.8f, "independent links accumulate: $strength")

        repeat(3) { coRecall.recalledTogether(store, listOf(MemoryNodeId("a"), MemoryNodeId("gone"))) }
        assertEquals(MemoryCoRecall.MAX_LINKS, store.read().edges.size, "no link to a memory that is not stored")
    }
}
