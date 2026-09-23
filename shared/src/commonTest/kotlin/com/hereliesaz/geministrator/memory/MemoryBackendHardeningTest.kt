package com.hereliesaz.geministrator.memory

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class MemoryBackendHardeningTest {
    @Test
    fun gripKeepsProjectAsHardBoundaryAndUsesRunTaskRoleAsAffinity() = runBlocking {
        val local = episode(
            id = "local",
            projectId = "project",
            workflowRunId = "workflow-run",
            workflowDefinitionId = "workflow-definition",
            taskRunId = "task-run",
            taskDefinitionId = "task-definition",
            roleId = "role",
        )
        val distractors = listOf(
            local.copy(id = MemoryEpisodeId("wrong-project"), projectId = "other-project"),
            local.copy(id = MemoryEpisodeId("wrong-workflow-run"), workflowRunId = "other-workflow-run"),
            local.copy(id = MemoryEpisodeId("wrong-workflow-definition"), workflowDefinitionId = "other-workflow-definition"),
            local.copy(id = MemoryEpisodeId("wrong-task-run"), taskRunId = "other-task-run"),
            local.copy(id = MemoryEpisodeId("wrong-task-definition"), taskDefinitionId = "other-task-definition"),
            local.copy(id = MemoryEpisodeId("wrong-role"), roleId = "other-role"),
        )
        val allEpisodes = listOf(local) + distractors
        val tags = allEpisodes.map { source ->
            MemoryNode(
                id = MemoryNodeId("tag-${source.id.value}"),
                kind = MemoryNodeKind.Category,
                text = "build verification",
                sourceEpisodeIds = setOf(source.id),
                createdAtEpochMillis = 2L,
            )
        }
        val store = InMemoryMemoryStore()
        assertTrue(
            store.commit(
                0,
                MemoryStoreMutation(
                    episodesToAdd = allEpisodes,
                    nodesToAdd = tags,
                ),
            ),
        )

        val recalled = GraphMemoryTool(store).grip(
            MemoryTagQuery(
                tags = listOf("build verification"),
                scope = MemoryBankScope(
                    projectId = "project",
                    workflowRunId = "workflow-run",
                    workflowDefinitionId = "workflow-definition",
                    taskRunId = "task-run",
                    taskDefinitionId = "task-definition",
                    roleId = "role",
                ),
            ),
        )

        val recalledIds = recalled.hits.map { it.node.id }
        assertEquals(MemoryNodeId("tag-local"), recalledIds.first())
        assertFalse(MemoryNodeId("tag-wrong-project") in recalledIds)
        assertTrue(MemoryNodeId("tag-wrong-workflow-run") in recalledIds)
        assertTrue(MemoryNodeId("tag-wrong-workflow-definition") in recalledIds)
        assertTrue(MemoryNodeId("tag-wrong-task-run") in recalledIds)
        assertTrue(MemoryNodeId("tag-wrong-task-definition") in recalledIds)
        assertTrue(MemoryNodeId("tag-wrong-role") in recalledIds)
    }

    @Test
    fun attentionBaselineIncreaseAppliesImmediatelyOnlyWhenUnsuppressed() {
        val gate = MemoryAttentionGate()
        val resting = MemoryAttentionState(baselineLevel = 0.3f)
        val raised = gate.setBaseline(resting, 0.8f)
        assertEquals(0.8f, raised.baselineLevel)
        assertEquals(0.8f, raised.effectiveLevel)
        assertEquals(0.8f, raised.suppressedFromLevel)

        val suppressed = gate.suppress(MemoryAttentionState(baselineLevel = 0.5f), 0.1f)
        val raisedWhileSuppressed = gate.setBaseline(suppressed, 0.8f)
        assertEquals(0.8f, raisedWhileSuppressed.baselineLevel)
        assertEquals(0.1f, raisedWhileSuppressed.effectiveLevel)

        val loweredWhileSuppressed = gate.setBaseline(raisedWhileSuppressed, 0.05f)
        assertEquals(0.05f, loweredWhileSuppressed.baselineLevel)
        assertEquals(0.05f, loweredWhileSuppressed.effectiveLevel)
    }

    @Test
    fun attentionTokenCountersSaturateInsteadOfOverflowing() {
        val gate = MemoryAttentionGate()
        val nearLimit = MemoryAttentionState(
            baselineLevel = 0.9f,
            effectiveLevel = 0.2f,
            suppressedFromLevel = 0.2f,
            tokensSinceSuppression = Long.MAX_VALUE - 2,
            tokensSinceCue = Long.MAX_VALUE - 2,
        )

        val recovered = gate.consumeTokens(nearLimit, 100)

        assertEquals(Long.MAX_VALUE, recovered.tokensSinceSuppression)
        assertEquals(Long.MAX_VALUE, recovered.tokensSinceCue)
        assertEquals(gate.recoveryRestLevel(nearLimit), recovered.effectiveLevel)
        assertTrue(recovered.effectiveLevel > nearLimit.baselineLevel)
    }

    @Test
    fun identicalSameMillisecondBanksRemainDistinctEvents() = runBlocking {
        val store = InMemoryMemoryStore()
        val queue = MemoryConsolidationQueue(store)
        val first = queue.enqueueBank(
            MemoryBankRequest(
                sourceSessionId = "agent",
                text = "Return to the Wasm test.",
                scope = MemoryBankScope(taskRunId = "task-one"),
                bankedAtEpochMillis = 42L,
            ),
        )
        val second = queue.enqueueBank(
            MemoryBankRequest(
                sourceSessionId = "agent",
                text = "Return to the Wasm test.",
                scope = MemoryBankScope(taskRunId = "task-two"),
                bankedAtEpochMillis = 42L,
            ),
        )

        assertNotEquals(first.id, second.id)
        assertNotEquals(first.episodeId, second.episodeId)
        val snapshot = store.read()
        assertEquals(2, snapshot.episodes.size)
        assertEquals(setOf("task-one", "task-two"), snapshot.episodes.mapNotNull { it.taskRunId }.toSet())
    }

    @Test
    fun priorityCondensationCannotSupersedeNodesFromPausedEpisode() = runBlocking {
        val pausedEpisode = episode(id = "paused", projectId = "project")
        val priorityEpisode = episode(id = "priority", projectId = "project")
        val pausedNodes = contextCluster("paused", pausedEpisode.id, 10L)
        val priorityNodes = contextCluster("priority", priorityEpisode.id, 20L)
        val allNodes = pausedNodes + priorityNodes
        val similarEdges = clusterEdges("paused", pausedNodes, 30L) + clusterEdges("priority", priorityNodes, 40L)
        val pausedEntry = MemoryQueueEntry(
            id = MemoryQueueId("queue-paused"),
            sequence = 1L,
            episodeId = pausedEpisode.id,
            stage = MemoryConsolidationStage.Associations,
            cursor = 1,
            status = MemoryQueueStatus.Pending,
            priority = MemoryQueuePriority.Normal,
            createdAtEpochMillis = 10L,
        )
        val priorityEntry = MemoryQueueEntry(
            id = MemoryQueueId("queue-priority"),
            sequence = 2L,
            episodeId = priorityEpisode.id,
            stage = MemoryConsolidationStage.Condensation,
            status = MemoryQueueStatus.Pending,
            priority = MemoryQueuePriority.Next,
            createdAtEpochMillis = 20L,
        )
        val store = InMemoryMemoryStore()
        assertTrue(
            store.commit(
                0,
                MemoryStoreMutation(
                    episodesToAdd = listOf(pausedEpisode, priorityEpisode),
                    nodesToAdd = allNodes,
                    edgesToAdd = similarEdges,
                    queueUpserts = listOf(pausedEntry, priorityEntry),
                ),
            ),
        )

        val manager = object : MemoryManagerAgent {
            override suspend fun process(packet: MemoryWorkPacket): MemoryMutationBatch {
                assertEquals(priorityEntry.id, packet.queueId)
                assertTrue(packet.items.all { it.id.startsWith("priority-") })
                val generalized = MemoryNode(
                    id = MemoryNodeId("priority-generalized"),
                    kind = MemoryNodeKind.Context,
                    text = "priority generalized context",
                    sourceEpisodeIds = setOf(priorityEpisode.id),
                    createdAtEpochMillis = 50L,
                )
                return MemoryMutationBatch(
                    nodesToAdd = listOf(generalized),
                    edgesToAdd = packet.items.flatMapIndexed { index, item ->
                        val source = MemoryNodeId(item.id)
                        listOf(
                            MemoryEdge(
                                id = MemoryEdgeId("condensed-$index"),
                                from = generalized.id,
                                to = source,
                                relation = MemoryRelationKind.CondensedFrom,
                                createdAtEpochMillis = 50L,
                            ),
                            MemoryEdge(
                                id = MemoryEdgeId("supersedes-$index"),
                                from = generalized.id,
                                to = source,
                                relation = MemoryRelationKind.Supersedes,
                                createdAtEpochMillis = 50L,
                            ),
                        )
                    },
                )
            }
        }
        val policy = MemoryConsolidationPolicy(
            maxPacketItems = 4,
            maxPacketChars = 4_000,
            condensationBatchSize = 2,
            maxSimilarPerKind = mapOf(MemoryNodeKind.Context to 2),
        )

        val result = MemoryConsolidator(store, manager, policy).processNext(60L)
        assertIs<MemoryConsolidationResult.Applied>(result)
        assertEquals(priorityEntry.id, result.queueId)

        val superseded = store.read().edges
            .filter { it.relation == MemoryRelationKind.Supersedes }
            .mapTo(hashSetOf()) { it.to }
        assertTrue(priorityNodes.any { it.id in superseded })
        assertFalse(pausedNodes.any { it.id in superseded })
    }

    @Test
    fun associationStageRejectsEpistemicConflictEdges() = runBlocking {
        val source = episode(id = "association", projectId = "project")
        val nodes = listOf(
            MemoryNode(
                id = MemoryNodeId("association-one"),
                kind = MemoryNodeKind.Context,
                text = "same topic one",
                sourceEpisodeIds = setOf(source.id),
                createdAtEpochMillis = 1L,
            ),
            MemoryNode(
                id = MemoryNodeId("association-two"),
                kind = MemoryNodeKind.Context,
                text = "same topic two",
                sourceEpisodeIds = setOf(source.id),
                createdAtEpochMillis = 2L,
            ),
        )
        val entry = MemoryQueueEntry(
            id = MemoryQueueId("queue-association"),
            sequence = 1L,
            episodeId = source.id,
            stage = MemoryConsolidationStage.Associations,
            createdAtEpochMillis = 2L,
        )
        val store = InMemoryMemoryStore()
        assertTrue(
            store.commit(
                0,
                MemoryStoreMutation(
                    episodesToAdd = listOf(source),
                    nodesToAdd = nodes,
                    queueUpserts = listOf(entry),
                ),
            ),
        )
        val manager = object : MemoryManagerAgent {
            override suspend fun process(packet: MemoryWorkPacket): MemoryMutationBatch = MemoryMutationBatch(
                edgesToAdd = listOf(
                    MemoryEdge(
                        id = MemoryEdgeId("forbidden-conflict"),
                        from = nodes[0].id,
                        to = nodes[1].id,
                        relation = MemoryRelationKind.ConflictsWith,
                        createdAtEpochMillis = 3L,
                    ),
                ),
            )
        }

        val result = MemoryConsolidator(store, manager).processNext(4L)
        assertIs<MemoryConsolidationResult.Failed>(result)
        Unit
    }

    private fun episode(
        id: String,
        projectId: String? = null,
        workflowRunId: String? = null,
        workflowDefinitionId: String? = null,
        taskRunId: String? = null,
        taskDefinitionId: String? = null,
        roleId: String? = null,
    ) = MemoryEpisode(
        id = MemoryEpisodeId(id),
        sourceSessionId = "session-$id",
        projectId = projectId,
        workflowRunId = workflowRunId,
        workflowDefinitionId = workflowDefinitionId,
        taskRunId = taskRunId,
        taskDefinitionId = taskDefinitionId,
        roleId = roleId,
        userPrompt = "prompt-$id",
        chunks = emptyList(),
        createdAtEpochMillis = 1L,
    )

    private fun contextCluster(prefix: String, episodeId: MemoryEpisodeId, createdAt: Long): List<MemoryNode> =
        (1..3).map { index ->
            MemoryNode(
                id = MemoryNodeId("$prefix-$index"),
                kind = MemoryNodeKind.Context,
                text = "$prefix context $index",
                sourceEpisodeIds = setOf(episodeId),
                createdAtEpochMillis = createdAt + index,
            )
        }

    private fun clusterEdges(prefix: String, nodes: List<MemoryNode>, createdAt: Long): List<MemoryEdge> = listOf(
        MemoryEdge(
            id = MemoryEdgeId("$prefix-similar-1"),
            from = nodes[0].id,
            to = nodes[1].id,
            relation = MemoryRelationKind.SimilarTo,
            weight = 1f,
            createdAtEpochMillis = createdAt,
        ),
        MemoryEdge(
            id = MemoryEdgeId("$prefix-similar-2"),
            from = nodes[1].id,
            to = nodes[2].id,
            relation = MemoryRelationKind.SimilarTo,
            weight = 1f,
            createdAtEpochMillis = createdAt + 1,
        ),
    )
}
