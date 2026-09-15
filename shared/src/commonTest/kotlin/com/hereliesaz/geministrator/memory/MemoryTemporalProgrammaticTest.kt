package com.hereliesaz.geministrator.memory

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryTemporalProgrammaticTest {
    @Test
    fun temporalIndexRollsOlderBucketsUpWithoutLosingEpisodeProvenance() {
        val quarterHour = MemoryTemporalLevel.FifteenMinutes.durationMillis
        val episodes = (0 until (14 * 24 * 4)).map { index ->
            episode(
                id = "episode-$index",
                timestamp = index * quarterHour,
                projectId = "project",
            )
        }

        val index = MemoryTemporalIndex.build(episodes)
        val byLevel = index.buckets.groupBy(MemoryTemporalBucket::level)

        assertTrue(byLevel[MemoryTemporalLevel.FifteenMinutes].orEmpty().size <= 8)
        assertTrue(byLevel[MemoryTemporalLevel.OneHour].orEmpty().size <= 6)
        assertTrue(byLevel[MemoryTemporalLevel.SixHours].orEmpty().size <= 3)
        assertTrue(byLevel[MemoryTemporalLevel.TwelveHours].orEmpty().size <= 2)
        assertTrue(byLevel[MemoryTemporalLevel.Day].orEmpty().size <= 7)
        assertTrue(byLevel[MemoryTemporalLevel.Week].orEmpty().isNotEmpty())

        val represented = index.buckets.flatMap { it.episodeIds }
        assertEquals(episodes.size, represented.size)
        assertEquals(episodes.mapTo(linkedSetOf(), MemoryEpisode::id), represented.toSet())
    }

    @Test
    fun eightQuarterHourBucketsRemainGranularAndNinthRollsOldestWindowUp() {
        val quarterHour = MemoryTemporalLevel.FifteenMinutes.durationMillis
        val eight = (0 until 8).map { index -> episode("e$index", index * quarterHour, "project") }
        val eightIndex = MemoryTemporalIndex.build(eight)
        assertEquals(8, eightIndex.buckets.count { it.level == MemoryTemporalLevel.FifteenMinutes })
        assertEquals(0, eightIndex.buckets.count { it.level == MemoryTemporalLevel.OneHour })

        val nine = eight + episode("e8", 8 * quarterHour, "project")
        val nineIndex = MemoryTemporalIndex.build(nine)
        assertTrue(nineIndex.buckets.count { it.level == MemoryTemporalLevel.FifteenMinutes } <= 8)
        assertTrue(nineIndex.buckets.any { it.level == MemoryTemporalLevel.OneHour })
        assertEquals(9, nineIndex.buckets.sumOf { it.episodeIds.size })
    }

    @Test
    fun programmaticAssociationsUseExactBookkeepingFactsWithoutModelInference() = runBlocking {
        val firstEpisode = episode(
            id = "first",
            timestamp = 1_000L,
            projectId = "haive",
            taskRunId = "task-run-42",
        )
        val secondEpisode = episode(
            id = "second",
            timestamp = 2_000L,
            projectId = "haive",
            taskRunId = "task-run-42",
        )
        val firstAnchor = MemoryNode(
            id = MemoryNodeId("first-category"),
            kind = MemoryNodeKind.Category,
            text = "router stability",
            sourceEpisodeIds = setOf(firstEpisode.id),
            createdAtEpochMillis = 1_000L,
        )
        val secondAnchor = MemoryNode(
            id = MemoryNodeId("second-category"),
            kind = MemoryNodeKind.Category,
            text = "database migration",
            sourceEpisodeIds = setOf(secondEpisode.id),
            createdAtEpochMillis = 2_000L,
        )
        val firstArtifact = MemoryNode(
            id = MemoryNodeId("first-artifact"),
            kind = MemoryNodeKind.Context,
            text = "Changed shared/src/commonMain/kotlin/example/Foo.kt",
            sourceEpisodeIds = setOf(firstEpisode.id),
            createdAtEpochMillis = 1_100L,
        )
        val secondArtifact = MemoryNode(
            id = MemoryNodeId("second-artifact"),
            kind = MemoryNodeKind.Context,
            text = "Revisited shared/src/commonMain/kotlin/example/Foo.kt for verification",
            sourceEpisodeIds = setOf(secondEpisode.id),
            createdAtEpochMillis = 2_100L,
        )
        val store = InMemoryMemoryStore(
            MemorySnapshot(
                episodes = listOf(firstEpisode, secondEpisode),
                nodes = listOf(firstAnchor, secondAnchor, firstArtifact, secondArtifact),
            ),
        )

        val added = MemoryProgrammaticAssociator(store).refresh(3_000L)
        assertTrue(added > 0)

        val deterministic = store.read().edges.filter { it.metadata["deterministic"] == "true" }
        assertTrue(deterministic.isNotEmpty())
        assertTrue(deterministic.all { it.relation == MemoryRelationKind.AssociatedWith })
        assertTrue(deterministic.any { it.metadata["basis"] == "scope:task-run" })
        assertTrue(deterministic.any {
            it.metadata["basis"] == "exact-identifier" &&
                it.metadata["detail"]?.contains("foo.kt", ignoreCase = true) == true
        })
    }

    private fun episode(
        id: String,
        timestamp: Long,
        projectId: String? = null,
        taskRunId: String? = null,
    ) = MemoryEpisode(
        id = MemoryEpisodeId(id),
        sourceSessionId = "session-$id",
        projectId = projectId,
        taskRunId = taskRunId,
        userPrompt = "prompt-$id",
        chunks = emptyList(),
        createdAtEpochMillis = timestamp,
    )
}
