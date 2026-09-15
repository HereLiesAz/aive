package com.hereliesaz.geministrator.memory

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class MemoryProgrammaticScopeTest {
    @Test
    fun exactTagsScopeIdsAndTimeDoNotBridgeDifferentProjects() = runBlocking {
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
            createdAtEpochMillis = 1_000L,
        )
        val store = InMemoryMemoryStore(
            MemorySnapshot(
                episodes = listOf(firstEpisode, secondEpisode),
                nodes = listOf(firstNode, secondNode),
            ),
        )

        MemoryProgrammaticAssociator(store).refresh(2_000L)

        assertTrue(store.read().edges.isEmpty())
    }

    private fun episode(id: String, projectId: String) = MemoryEpisode(
        id = MemoryEpisodeId(id),
        sourceSessionId = "same-session",
        projectId = projectId,
        workflowRunId = "same-workflow-run",
        taskRunId = "same-task-run",
        roleId = "same-role",
        userPrompt = "same prompt",
        chunks = emptyList(),
        createdAtEpochMillis = 1_000L,
    )
}
