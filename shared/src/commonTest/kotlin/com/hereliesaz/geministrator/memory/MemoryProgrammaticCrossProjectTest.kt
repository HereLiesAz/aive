package com.hereliesaz.geministrator.memory

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class MemoryProgrammaticCrossProjectTest {
    @Test
    fun oneStoreMayAssociateDifferentProjectsWhenOrchestrationOrTimeConnectsThem() = runBlocking {
        val firstEpisode = episode("one", "project-one")
        val secondEpisode = episode("two", "project-two")
        val firstNode = MemoryNode(
            id = MemoryNodeId("first-tag"),
            kind = MemoryNodeKind.Category,
            text = "build verification",
            sourceEpisodeIds = setOf(firstEpisode.id),
            createdAtEpochMillis = 1_000L,
        )
        val secondNode = MemoryNode(
            id = MemoryNodeId("second-tag"),
            kind = MemoryNodeKind.Category,
            text = "build verification",
            sourceEpisodeIds = setOf(secondEpisode.id),
            createdAtEpochMillis = 1_500L,
        )
        val store = InMemoryMemoryStore(
            MemorySnapshot(
                episodes = listOf(firstEpisode, secondEpisode),
                nodes = listOf(firstNode, secondNode),
            ),
        )

        val added = MemoryProgrammaticAssociator(store).refresh(2_000L)
        val deterministic = store.read().edges.filter { it.metadata["deterministic"] == "true" }

        assertTrue(added > 0)
        assertTrue(deterministic.isNotEmpty())
        assertTrue(deterministic.any {
            it.metadata["basis"] == "scope:task-run" ||
                it.metadata["basis"] == "scope:workflow-run" ||
                it.metadata["basis"] == "exact-cue" ||
                it.metadata["basis"] == "sequence:adjacent-store" ||
                it.metadata["basis"] == "temporal:FifteenMinutes"
        })
    }

    private fun episode(id: String, projectId: String) = MemoryEpisode(
        id = MemoryEpisodeId(id),
        sourceSessionId = "shared-orchestration-session",
        projectId = projectId,
        workflowRunId = "shared-workflow-run",
        taskRunId = "shared-task-run",
        roleId = "worker",
        userPrompt = "same work universe",
        chunks = emptyList(),
        createdAtEpochMillis = if (id == "one") 1_000L else 1_500L,
    )
}
