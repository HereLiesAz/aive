package com.hereliesaz.geministrator.memory

import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryAssociationIntegrationTest {
    @Test
    fun unequalIndependentEvidenceUsesComplementaryExponentialCurve() {
        val combined = accumulateAssociationStrength(listOf(0.40f, 0.60f))

        // 1 - ((1 - .40) * (1 - .60)) = .76. This is reinforcement with diminishing returns,
        // not linear addition and not hard doubling.
        assertClose(0.76f, combined)
    }

    @Test
    fun temporalRebucketingReplacesItsOwnEvidenceButStillCombinesWithIndependentSupport() = runBlocking {
        val episode = MemoryEpisode(
            id = MemoryEpisodeId("temporal-evidence"),
            sourceSessionId = "session",
            projectId = "project",
            userPrompt = "remember temporal evidence",
            chunks = emptyList(),
            createdAtEpochMillis = 1L,
        )
        val seed = node("temporal-seed", MemoryNodeKind.NounTag, "temporal seed", episode.id)
        val target = node("temporal-target", MemoryNodeKind.Summary, "temporal target", episode.id)

        fun temporalEdge(id: String, basis: String, weight: Float, createdAt: Long) = MemoryEdge(
            id = MemoryEdgeId(id),
            from = seed.id,
            to = target.id,
            relation = MemoryRelationKind.AssociatedWith,
            weight = weight,
            createdAtEpochMillis = createdAt,
            // Deliberately omit evidenceFamily/evidencePolicy. The legacy-basis fallback must keep
            // already-persisted temporal edges correct after this backend upgrade.
            metadata = mapOf("basis" to basis, "deterministic" to "true"),
        )

        val rebucketedStore = InMemoryMemoryStore(
            MemorySnapshot(
                episodes = listOf(episode),
                nodes = listOf(seed, target),
                edges = listOf(
                    temporalEdge("fine", "temporal:FifteenMinutes", 0.88f, 10L),
                    temporalEdge("coarse", "temporal:Week", 0.50f, 20L),
                ),
            ),
        )

        val rebucketedHit = GraphMemoryTool(rebucketedStore)
            .expand(seed.id, MemoryResolution.Summary, maxResults = 1)
            .single()

        // The week edge is the newer representation of the same temporal fact. It replaces the old
        // fine-grained representation instead of reinforcing it to 0.94.
        assertClose(weightedTraversalScore(0.50f, 1), rebucketedHit.score)

        val reinforcedStore = InMemoryMemoryStore(
            rebucketedStore.read().copy(
                edges = rebucketedStore.read().edges + association(
                    id = "independent",
                    from = seed.id,
                    to = target.id,
                    weight = 0.50f,
                    createdAt = 30L,
                    metadata = mapOf("basis" to "scope:independent"),
                ),
            ),
        )

        val reinforcedHit = GraphMemoryTool(reinforcedStore)
            .expand(seed.id, MemoryResolution.Summary, maxResults = 1)
            .single()

        // The current temporal 0.50 and a genuinely independent 0.50 fact do reinforce: 0.75.
        assertClose(weightedTraversalScore(0.75f, 1), reinforcedHit.score)
    }

    @Test
    fun condensedMemoryStrengthensSharedAssociationsWithoutPromotingUniqueOnes() = runBlocking {
        val episode = MemoryEpisode(
            id = MemoryEpisodeId("condense-integration"),
            sourceSessionId = "session",
            projectId = "project",
            userPrompt = "remember the shared association",
            chunks = emptyList(),
            createdAtEpochMillis = 1L,
        )
        val sourceA = node("source-a", MemoryNodeKind.Summary, "specific memory A", episode.id)
        val sourceB = node("source-b", MemoryNodeKind.Summary, "specific memory B", episode.id)
        val generalized = node("generalized", MemoryNodeKind.Summary, "generalized memory", episode.id)
        val sharedTarget = node("shared-target", MemoryNodeKind.Category, "shared subject", episode.id)
        val uniqueTarget = node("unique-target", MemoryNodeKind.Category, "source A only", episode.id)

        val store = InMemoryMemoryStore(
            MemorySnapshot(
                episodes = listOf(episode),
                nodes = listOf(sourceA, sourceB, generalized, sharedTarget, uniqueTarget),
                edges = listOf(
                    MemoryEdge(
                        id = MemoryEdgeId("condensed-a"),
                        from = generalized.id,
                        to = sourceA.id,
                        relation = MemoryRelationKind.CondensedFrom,
                        createdAtEpochMillis = 10L,
                    ),
                    MemoryEdge(
                        id = MemoryEdgeId("condensed-b"),
                        from = generalized.id,
                        to = sourceB.id,
                        relation = MemoryRelationKind.CondensedFrom,
                        createdAtEpochMillis = 10L,
                    ),
                    MemoryEdge(
                        id = MemoryEdgeId("supersedes-a"),
                        from = generalized.id,
                        to = sourceA.id,
                        relation = MemoryRelationKind.Supersedes,
                        createdAtEpochMillis = 10L,
                    ),
                    MemoryEdge(
                        id = MemoryEdgeId("supersedes-b"),
                        from = generalized.id,
                        to = sourceB.id,
                        relation = MemoryRelationKind.Supersedes,
                        createdAtEpochMillis = 10L,
                    ),
                    association(
                        id = "a-old-temporal",
                        from = sourceA.id,
                        to = sharedTarget.id,
                        weight = 0.88f,
                        createdAt = 3L,
                        metadata = mapOf("basis" to "temporal:FifteenMinutes", "deterministic" to "true"),
                    ),
                    association(
                        id = "a-current-temporal",
                        from = sourceA.id,
                        to = sharedTarget.id,
                        weight = 0.50f,
                        createdAt = 4L,
                        metadata = mapOf("basis" to "temporal:Week", "deterministic" to "true"),
                    ),
                    association("b-shared", sourceB.id, sharedTarget.id, 0.50f),
                    association("a-unique", sourceA.id, uniqueTarget.id, 0.50f),
                ),
            ),
        )

        val added = MemoryProgrammaticAssociator(store).refresh(20L)
        assertTrue(added >= 2)

        val snapshot = store.read()
        val inheritedShared = snapshot.edges.filter { edge ->
            edge.metadata["basis"] == "condensation:overlap" &&
                setOf(edge.from, edge.to) == setOf(generalized.id, sharedTarget.id)
        }
        val inheritedUnique = snapshot.edges.filter { edge ->
            edge.metadata["basis"] == "condensation:overlap" &&
                setOf(edge.from, edge.to) == setOf(generalized.id, uniqueTarget.id)
        }

        // Source A contributes its current temporal representation (.50), not .88 + .50. Source B
        // independently contributes .50, so the generalized memory receives effective .75 support.
        assertEquals(2, inheritedShared.size)
        assertEquals(listOf(0.50f, 0.50f), inheritedShared.map { it.weight }.sorted())
        assertClose(0.75f, accumulateAssociationStrength(inheritedShared.map { it.weight }))
        assertTrue(inheritedUnique.isEmpty())

        val hits = GraphMemoryTool(store)
            .expand(generalized.id, MemoryResolution.Category, maxResults = 8)
            .associateBy { it.node.id }
        val sharedHit = checkNotNull(hits[sharedTarget.id])
        val uniqueHit = checkNotNull(hits[uniqueTarget.id])

        // The shared relationship is promoted directly at the accumulated strength. The unique
        // relationship remains recoverable through preserved provenance but is not promoted.
        assertClose(weightedTraversalScore(0.75f, 1), sharedHit.score)
        assertTrue(sharedHit.score > uniqueHit.score)
    }

    private fun node(
        id: String,
        kind: MemoryNodeKind,
        text: String,
        episodeId: MemoryEpisodeId,
    ) = MemoryNode(
        id = MemoryNodeId(id),
        kind = kind,
        text = text,
        sourceEpisodeIds = setOf(episodeId),
        createdAtEpochMillis = 2L,
    )

    private fun association(
        id: String,
        from: MemoryNodeId,
        to: MemoryNodeId,
        weight: Float,
        createdAt: Long = 3L,
        metadata: Map<String, String> = emptyMap(),
    ) = MemoryEdge(
        id = MemoryEdgeId(id),
        from = from,
        to = to,
        relation = MemoryRelationKind.AssociatedWith,
        weight = weight,
        createdAtEpochMillis = createdAt,
        metadata = metadata,
    )

    private fun assertClose(expected: Float, actual: Float, tolerance: Float = 0.0001f) {
        assertTrue(abs(expected - actual) <= tolerance, "Expected $expected, got $actual")
    }
}
