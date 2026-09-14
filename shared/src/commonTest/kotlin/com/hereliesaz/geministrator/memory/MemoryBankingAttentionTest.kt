package com.hereliesaz.geministrator.memory

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MemoryBankingAttentionTest {
    @Test
    fun deliberateBankJumpsAheadOfOrdinaryBacklog() = runBlocking {
        val store = InMemoryMemoryStore()
        val queue = MemoryConsolidationQueue(store)
        val first = queue.enqueueSession(session("ordinary-one", 100L))
        queue.enqueueSession(session("ordinary-two", 200L))

        val banked = queue.enqueueBank(
            MemoryBankRequest(
                sourceSessionId = "active-agent",
                text = "Note to self: after fixing the router, return to the failing Web Wasm test.",
                scope = MemoryBankScope(projectId = "project", taskRunId = "current-task"),
                bankedAtEpochMillis = 300L,
            ),
        )

        assertEquals(MemoryQueuePriority.Normal, first.priority)
        assertEquals(MemoryQueuePriority.Next, banked.priority)
        assertTrue(banked.sequence > first.sequence)

        val manager = object : MemoryManagerAgent {
            override suspend fun process(packet: MemoryWorkPacket): MemoryMutationBatch = MemoryMutationBatch()
        }
        val result = MemoryConsolidator(store, manager).processNext(400L)

        assertIs<MemoryConsolidationResult.Applied>(result)
        assertEquals(banked.id, result.queueId)
    }

    @Test
    fun memoryToolBanksIntoSamePriorityQueue() = runBlocking {
        val store = InMemoryMemoryStore()
        val queue = MemoryConsolidationQueue(store)
        val tool = GraphMemoryTool(store, queue)

        val entry = tool.bank(
            MemoryBankRequest(
                sourceSessionId = "agent",
                text = "Keep the exact-head CI check in mind.",
                sourceKind = MemorySourceKind.AgentNote,
                scope = MemoryBankScope(projectId = "haive", workflowRunId = "run"),
                bankedAtEpochMillis = 10L,
            ),
        )

        assertEquals(MemoryQueuePriority.Next, entry.priority)
        val snapshot = store.read()
        val episode = snapshot.episodes.single { it.id == entry.episodeId }
        assertEquals("haive", episode.projectId)
        assertEquals("run", episode.workflowRunId)
        assertEquals(MemorySourceKind.AgentNote, episode.chunks.single().kind)
        assertEquals("Keep the exact-head CI check in mind.", episode.chunks.single().text)
    }

    @Test
    fun ambientGripDefaultsToEntityActionAndCategorySubjectTagsAndCoTRTagsCanDrillDown() = runBlocking {
        val episode = MemoryEpisode(
            id = MemoryEpisodeId("episode"),
            sourceSessionId = "session",
            projectId = "project",
            userPrompt = "work",
            chunks = emptyList(),
            createdAtEpochMillis = 1L,
        )
        val context = MemoryNode(
            id = MemoryNodeId("context"),
            kind = MemoryNodeKind.Context,
            text = "Return after the router fix and continue the pending verification.",
            sourceEpisodeIds = setOf(episode.id),
            createdAtEpochMillis = 1L,
        )
        val wasmTag = MemoryNode(
            id = MemoryNodeId("wasm-tag"),
            kind = MemoryNodeKind.NounTag,
            text = "Web Wasm",
            sourceEpisodeIds = setOf(episode.id),
            createdAtEpochMillis = 2L,
        )
        val testTag = MemoryNode(
            id = MemoryNodeId("test-tag"),
            kind = MemoryNodeKind.VerbTag,
            text = "test",
            sourceEpisodeIds = setOf(episode.id),
            createdAtEpochMillis = 2L,
        )
        val subjectTag = MemoryNode(
            id = MemoryNodeId("verification-subject"),
            kind = MemoryNodeKind.Category,
            text = "build verification",
            sourceEpisodeIds = setOf(episode.id),
            createdAtEpochMillis = 3L,
        )
        val store = InMemoryMemoryStore()
        assertTrue(
            store.commit(
                0,
                MemoryStoreMutation(
                    episodesToAdd = listOf(episode),
                    nodesToAdd = listOf(context, wasmTag, testTag, subjectTag),
                    edgesToAdd = listOf(
                        MemoryEdge(
                            MemoryEdgeId("index-wasm"),
                            wasmTag.id,
                            context.id,
                            MemoryRelationKind.Indexes,
                            createdAtEpochMillis = 2L,
                        ),
                        MemoryEdge(
                            MemoryEdgeId("index-test"),
                            testTag.id,
                            context.id,
                            MemoryRelationKind.Indexes,
                            createdAtEpochMillis = 2L,
                        ),
                        MemoryEdge(
                            MemoryEdgeId("associate-subject"),
                            subjectTag.id,
                            context.id,
                            MemoryRelationKind.AssociatedWith,
                            createdAtEpochMillis = 3L,
                        ),
                    ),
                ),
            ),
        )
        val tool = GraphMemoryTool(store)

        val ambient = tool.grip(MemoryQuery(text = "Web Wasm", projectId = "project"))
        assertTrue(ambient.hits.isNotEmpty())
        assertTrue(ambient.hits.all {
            it.node.kind == MemoryNodeKind.NounTag ||
                it.node.kind == MemoryNodeKind.VerbTag ||
                it.node.kind == MemoryNodeKind.Category
        })
        assertEquals(wasmTag.id, ambient.hits.first().node.id)

        val drilledFromEntityAndAction = tool.grip(
            MemoryTagQuery(
                tags = listOf("Web Wasm", "test"),
                resolution = MemoryResolution.Context,
                scope = MemoryBankScope(projectId = "project"),
            ),
        )
        assertTrue(drilledFromEntityAndAction.hits.any { it.node.id == context.id })
        assertTrue(drilledFromEntityAndAction.hits.all { it.conflicts.isEmpty() })

        val drilledFromSubject = tool.grip(
            MemoryTagQuery(
                tags = listOf("build verification"),
                resolution = MemoryResolution.Context,
                scope = MemoryBankScope(projectId = "project"),
            ),
        )
        assertTrue(drilledFromSubject.hits.any { it.node.id == context.id })
    }

    @Test
    fun attentionSuppressionRecoversWithTokenUse() {
        val gate = MemoryAttentionGate(MemoryAttentionPolicy(recoveryWindowTokens = 4_000))
        val baseline = MemoryAttentionState(baselineLevel = 0.7f)
        val suppressed = gate.suppress(baseline, 0.1f)
        val halfway = gate.consumeTokens(suppressed, 2_000)
        val recovered = gate.consumeTokens(halfway, 2_000)

        assertEquals(0.1f, suppressed.effectiveLevel)
        assertTrue(halfway.effectiveLevel > suppressed.effectiveLevel)
        assertTrue(halfway.effectiveLevel < baseline.baselineLevel)
        assertEquals(baseline.baselineLevel, recovered.effectiveLevel)
    }

    @Test
    fun lowerAttentionRequiresStrongerLessFrequentAssociations() {
        val gate = MemoryAttentionGate()
        val focused = MemoryAttentionState(
            baselineLevel = 0.8f,
            effectiveLevel = 0.05f,
            suppressedFromLevel = 0.05f,
            tokensSinceCue = 300L,
        )
        val intrusive = focused.copy(effectiveLevel = 0.95f, tokensSinceCue = 300L)

        val focusedPolicy = gate.cuePolicy(focused)
        val intrusivePolicy = gate.cuePolicy(intrusive)
        assertTrue(focusedPolicy.minimumSimilarity > intrusivePolicy.minimumSimilarity)
        assertTrue(focusedPolicy.minimumIntervalTokens > intrusivePolicy.minimumIntervalTokens)
        assertTrue(focusedPolicy.maxTags < intrusivePolicy.maxTags)
        assertFalse(gate.shouldSurfaceTags(focused, strongestAssociationScore = 0.8f))
        assertTrue(gate.shouldSurfaceTags(intrusive, strongestAssociationScore = 0.8f))
    }

    private fun session(id: String, closedAt: Long) = MemorySessionEnvelope(
        sourceSessionId = id,
        projectId = "project",
        userPrompt = "Remember $id",
        parts = listOf(MemorySessionPart(MemorySourceKind.Message, "Agent message", id)),
        closedAtEpochMillis = closedAt,
    )
}
