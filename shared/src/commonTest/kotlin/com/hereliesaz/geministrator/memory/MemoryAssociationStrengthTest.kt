package com.hereliesaz.geministrator.memory

import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryAssociationStrengthTest {
    @Test
    fun repeatedEvidenceUsesSaturatingExponentialAccumulation() {
        val one = accumulateAssociationStrength(listOf(0.50f))
        val two = accumulateAssociationStrength(listOf(0.50f, 0.50f))
        val three = accumulateAssociationStrength(listOf(0.50f, 0.50f, 0.50f))
        val four = accumulateAssociationStrength(listOf(0.50f, 0.50f, 0.50f, 0.50f))
        val twenty = accumulateAssociationStrength(List(20) { 0.50f })

        assertClose(0.50f, one)
        assertClose(0.75f, two)
        assertClose(0.875f, three)
        assertClose(0.9375f, four)
        assertTrue(two - one > three - two)
        assertTrue(three - two > four - three)
        assertTrue(twenty > four)
        assertTrue(twenty < 1f)
    }

    @Test
    fun gripTraversalPreservesWeakAndStrongEdgeWeights() = runBlocking {
        val episode = episode("weighted", "project")
        val seed = node("seed", MemoryNodeKind.NounTag, "router", episode.id)
        val strong = node("strong", MemoryNodeKind.Summary, "strong relationship", episode.id)
        val weak = node("weak", MemoryNodeKind.Summary, "weak relationship", episode.id)
        val store = InMemoryMemoryStore(
            MemorySnapshot(
                episodes = listOf(episode),
                nodes = listOf(seed, strong, weak),
                edges = listOf(
                    edge("strong-edge", seed.id, strong.id, 1f),
                    edge("weak-edge", seed.id, weak.id, 0.50f),
                ),
            ),
        )

        val hits = GraphMemoryTool(store).expand(seed.id, MemoryResolution.Summary, maxResults = 2)

        assertEquals(listOf(strong.id, weak.id), hits.map { it.node.id })
        assertTrue(hits[0].score > hits[1].score)
        assertClose(weightedTraversalScore(1f, 1), hits[0].score)
        assertClose(weightedTraversalScore(0.50f, 1), hits[1].score)
    }

    @Test
    fun parallelAssociationsAccumulateBeforeTraversal() = runBlocking {
        val episode = episode("parallel", "project")
        val seed = node("seed", MemoryNodeKind.NounTag, "wasm", episode.id)
        val target = node("target", MemoryNodeKind.Summary, "verification result", episode.id)
        val store = InMemoryMemoryStore(
            MemorySnapshot(
                episodes = listOf(episode),
                nodes = listOf(seed, target),
                edges = listOf(
                    edge("parallel-one", seed.id, target.id, 0.50f),
                    edge("parallel-two", seed.id, target.id, 0.50f),
                ),
            ),
        )

        val hit = GraphMemoryTool(store)
            .expand(seed.id, MemoryResolution.Summary, maxResults = 1)
            .single()

        assertClose(weightedTraversalScore(0.75f, 1), hit.score)
    }

    @Test
    fun condensationStrengthensOnlyAssociationsSharedByMultipleSources() {
        val episode = episode("condense", "project")
        val sourceA = node("source-a", MemoryNodeKind.Summary, "specific A", episode.id)
        val sourceB = node("source-b", MemoryNodeKind.Summary, "specific B", episode.id)
        val generalized = node("generalized", MemoryNodeKind.Summary, "generalized", episode.id)
        val sharedTarget = node("shared-target", MemoryNodeKind.Category, "shared target", episode.id)
        val uniqueTarget = node("unique-target", MemoryNodeKind.Category, "unique target", episode.id)
        val snapshot = MemorySnapshot(
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
                edge("a-shared", sourceA.id, sharedTarget.id, 0.50f),
                edge("b-shared", sourceB.id, sharedTarget.id, 0.50f),
                edge("a-unique", sourceA.id, uniqueTarget.id, 0.50f),
            ),
        )

        val candidates = snapshot.programmaticAssociationCandidates(nowEpochMillis = 20L, limit = 100)
        val overlap = candidates.filter { it.metadata["basis"] == "condensation:overlap" }
        val shared = overlap.filter { edge ->
            setOf(edge.from, edge.to) == setOf(generalized.id, sharedTarget.id)
        }
        val unique = overlap.filter { edge ->
            setOf(edge.from, edge.to) == setOf(generalized.id, uniqueTarget.id)
        }

        assertEquals(2, shared.size)
        assertEquals(setOf(sourceA.id.value, sourceB.id.value), shared.mapNotNull { it.metadata["supportSourceId"] }.toSet())
        assertClose(0.75f, accumulateAssociationStrength(shared.map { it.weight }))
        assertTrue(shared.all { it.metadata["supportCount"] == "2" })
        assertTrue(unique.isEmpty())
    }

    @Test
    fun projectRelativeIdentifiersDoNotCrossProjectNamespacesOrFoldCase() {
        val projectA = episode("project-a", "a")
        val projectB = episode("project-b", "b")
        val sameTextA = node("same-a", MemoryNodeKind.Context, "Changed src/Foo.kt", projectA.id)
        val sameTextB = node("same-b", MemoryNodeKind.Context, "Changed src/Foo.kt", projectB.id)
        val crossProject = MemorySnapshot(
            episodes = listOf(projectA, projectB),
            nodes = listOf(sameTextA, sameTextB),
        ).programmaticAssociationCandidates(10L, 100)

        assertFalse(crossProject.any { it.metadata["basis"] == "exact-identifier" })

        val lowerCase = node("lower", MemoryNodeKind.Context, "Changed src/foo.kt", projectA.id)
        val caseSensitive = MemorySnapshot(
            episodes = listOf(projectA),
            nodes = listOf(sameTextA, lowerCase),
        ).programmaticAssociationCandidates(10L, 100)

        assertFalse(caseSensitive.any { it.metadata["basis"] == "exact-identifier" })

        val samePathAgain = node("same-a-2", MemoryNodeKind.Context, "Verified src/Foo.kt", projectA.id)
        val exact = MemorySnapshot(
            episodes = listOf(projectA),
            nodes = listOf(sameTextA, samePathAgain),
        ).programmaticAssociationCandidates(10L, 100)

        assertTrue(exact.any { it.metadata["basis"] == "exact-identifier" })
    }

    @Test
    fun taskDefinitionIdentityRequiresTheWorkflowDefinitionNamespace() {
        val first = episode("wf-a", "project").copy(
            workflowDefinitionId = "workflow-a",
            taskDefinitionId = "build",
        )
        val second = episode("wf-b", "project").copy(
            workflowDefinitionId = "workflow-b",
            taskDefinitionId = "build",
        )
        val firstNode = node("wf-a-node", MemoryNodeKind.Summary, "first workflow", first.id)
        val secondNode = node("wf-b-node", MemoryNodeKind.Summary, "second workflow", second.id)

        val candidates = MemorySnapshot(
            episodes = listOf(first, second),
            nodes = listOf(firstNode, secondNode),
        ).programmaticAssociationCandidates(10L, 100)

        assertFalse(candidates.any { it.metadata["basis"] == "scope:task-definition" })
    }

    private fun episode(id: String, projectId: String) = MemoryEpisode(
        id = MemoryEpisodeId(id),
        sourceSessionId = "session-$id",
        projectId = projectId,
        userPrompt = "prompt-$id",
        chunks = emptyList(),
        createdAtEpochMillis = 1L,
    )

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

    private fun edge(
        id: String,
        from: MemoryNodeId,
        to: MemoryNodeId,
        weight: Float,
    ) = MemoryEdge(
        id = MemoryEdgeId(id),
        from = from,
        to = to,
        relation = MemoryRelationKind.AssociatedWith,
        weight = weight,
        createdAtEpochMillis = 3L,
    )

    private fun assertClose(expected: Float, actual: Float, tolerance: Float = 0.0001f) {
        assertTrue(abs(expected - actual) <= tolerance, "Expected $expected, got $actual")
    }
}
