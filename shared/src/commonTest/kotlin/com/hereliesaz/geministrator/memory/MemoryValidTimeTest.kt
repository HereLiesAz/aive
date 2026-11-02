package com.hereliesaz.geministrator.memory

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Valid time is kept apart from ingest time, ended only by deterministic coverage supersession. */
class MemoryValidTimeTest {
    private val policy = MemoryConsolidationPolicy(
        maxAttempts = 3,
        maxSimilarPerKind = MemoryConsolidationPolicy().maxSimilarPerKind + (MemoryNodeKind.Context to 2),
    )

    @Test
    fun validFromDefaultsToCreatedAt() {
        val node = MemoryNode(MemoryNodeId("n"), MemoryNodeKind.Context, "The pool is shared.", createdAtEpochMillis = 42L)
        assertEquals(42L, node.validFromEpochMillis)
        assertNull(MemorySnapshot(nodes = listOf(node)).validUntilOf(node.id))
        val dated = node.copy(validFromEpochMillis = 7L)
        assertEquals(7L, dated.validFromEpochMillis)
        assertEquals(42L, dated.createdAtEpochMillis)
    }

    @Test
    fun legacyRowsLoadWithValidFromAtCreatedAt() {
        val json = SettingsMemoryStore.defaultJson
        // Rows written before valid time existed: no validFromEpochMillis, no occurredAtEpochMillis.
        val node = json.decodeFromString(
            MemoryNode.serializer(),
            """{"id":{"value":"legacy"},"kind":"Context","text":"The pool is shared.","createdAtEpochMillis":1234}""",
        )
        assertEquals(1234L, node.validFromEpochMillis)
        val episode = json.decodeFromString(
            MemoryEpisode.serializer(),
            """{"id":{"value":"e"},"sourceSessionId":"s","userPrompt":"p","chunks":[],"createdAtEpochMillis":5}""",
        )
        assertNull(episode.occurredAtEpochMillis)
        val edge = json.decodeFromString(
            MemoryEdge.serializer(),
            """{"id":{"value":"x"},"from":{"value":"v"},"to":{"value":"legacy"},"relation":"Supersedes","createdAtEpochMillis":9}""",
        )
        // A legacy coverage edge (no basis, no recorded end) ends validity at its version's valid-from.
        val version = MemoryNode(MemoryNodeId("v"), MemoryNodeKind.Context, "The pool is shared.", createdAtEpochMillis = 2000L)
        assertEquals(2000L, MemorySnapshot(nodes = listOf(node, version), edges = listOf(edge)).validUntilOf(node.id))

        val dated = node.copy(validFromEpochMillis = 99L)
        assertEquals(dated, json.decodeFromString(MemoryNode.serializer(), json.encodeToString(MemoryNode.serializer(), dated)))
    }

    @Test
    fun extractionTakesTheSourceTimeWhenTheSourceCarriesOne() = runBlocking {
        val store = InMemoryMemoryStore()
        val entry = MemoryConsolidationQueue(store).enqueueBank(
            MemoryBankRequest(
                sourceSessionId = "commit-hook",
                text = "Switched the build to Gradle 9.",
                bankedAtEpochMillis = 900L,
                occurredAtEpochMillis = 500L,
            ),
        )
        val dated = store.read().episodes.single { it.id == entry.episodeId }
        assertEquals(500L, dated.occurredAtEpochMillis)
        assertEquals(900L, dated.createdAtEpochMillis)
        val undated = episode("undated", createdAt = 950L)

        val snapshot = MemorySnapshot(episodes = listOf(dated, undated))
        val stamped = MemoryValidTime.stampExtracted(
            snapshot,
            listOf(context("from-dated", "Gradle 9 builds it.", dated.id, 1000L), context("from-undated", "Something else.", undated.id, 1000L)),
        ).associateBy { it.id.value }

        assertEquals(500L, stamped.getValue("from-dated").validFromEpochMillis)
        assertEquals(1000L, stamped.getValue("from-dated").createdAtEpochMillis)
        assertEquals(1000L, stamped.getValue("from-undated").validFromEpochMillis)
    }

    @Test
    fun condensationSupersessionSetsValidUntilAndAsOfRecallFollowsIt() = runBlocking {
        val source = episode("source", createdAt = 1L)
        val text = "The connection pool is shared."
        val nodes = listOf(100L, 200L, 300L).mapIndexed { offset, validFrom ->
            context("c-${offset + 1}", text, source.id, createdAt = offset + 1L, validFrom = validFrom)
        }
        val store = InMemoryMemoryStore().apply {
            commit(
                0,
                MemoryStoreMutation(
                    episodesToAdd = listOf(source),
                    nodesToAdd = nodes,
                    edgesToAdd = nodes.zipWithNext().mapIndexed { index, (from, to) ->
                        MemoryEdge(MemoryEdgeId("similar-$index"), from.id, to.id, MemoryRelationKind.SimilarTo, 1f, createdAtEpochMillis = 5L)
                    },
                    queueUpserts = listOf(
                        MemoryQueueEntry(
                            id = MemoryQueueId("q-condense"),
                            sequence = 1,
                            episodeId = source.id,
                            stage = MemoryConsolidationStage.Condensation,
                            createdAtEpochMillis = 1L,
                        ),
                    ),
                ),
            )
        }
        val manager = object : MemoryManagerAgent {
            override suspend fun process(packet: MemoryWorkPacket): MemoryMutationBatch {
                // The clerk sets no valid time; the engine does.
                val merged = MemoryNode(MemoryNodeId("merged"), MemoryNodeKind.Context, text, sourceEpisodeIds = setOf(source.id), createdAtEpochMillis = 10L)
                return MemoryMutationBatch(
                    nodesToAdd = listOf(merged),
                    edgesToAdd = packet.items.map { item ->
                        MemoryEdge(MemoryEdgeId("merged-from-${item.id}"), merged.id, MemoryNodeId(item.id), MemoryRelationKind.CondensedFrom, createdAtEpochMillis = 10L)
                    },
                )
            }
        }

        assertIs<MemoryConsolidationResult.Applied>(MemoryConsolidator(store, manager, policy).processNext(10L))

        val snapshot = store.read()
        val merged = snapshot.nodes.single { it.id.value == "merged" }
        val supersedes = snapshot.edges.filter { it.relation == MemoryRelationKind.Supersedes && it.from == merged.id }
        assertEquals(setOf("c-1", "c-2", "c-3"), supersedes.map { it.to.value }.toSet())
        // The version holds once every source does; each source stops then.
        assertEquals(300L, merged.validFromEpochMillis)
        assertEquals(10L, merged.createdAtEpochMillis)
        supersedes.forEach { assertEquals("300", it.metadata[MEMORY_VALID_UNTIL]) }
        assertEquals(300L, snapshot.validUntilOf(MemoryNodeId("c-1")))
        assertNull(snapshot.validUntilOf(merged.id))

        val tool = GraphMemoryTool(store)
        suspend fun recalled(at: Long?) = tool
            .grip(MemoryQuery("connection pool", MemoryResolution.Context, validAtEpochMillis = at))
            .hits.map { it.node.id.value }.toSet()

        assertEquals(setOf("merged"), recalled(null))
        assertEquals(emptySet<String>(), recalled(50L), "nothing was true yet")
        assertEquals(setOf("c-1"), recalled(150L), "c-2 was not yet true")
        assertEquals(setOf("c-1", "c-2"), recalled(250L))
        assertEquals(setOf("merged"), recalled(400L), "the sources were superseded at 300")
        val past = tool.grip(MemoryQuery("connection pool", MemoryResolution.Context, validAtEpochMillis = 150L)).hits.single()
        assertEquals(300L, past.validUntilEpochMillis)
    }

    @Test
    fun aDeliberationsChoiceEndsNoValidTime() {
        val episode = episode("e", createdAt = 1L)
        val notChosen = context("not-chosen", "Max connections: 50.", episode.id, createdAt = 100L)
        val chosen = context("chosen", "Max connections: 100.", episode.id, createdAt = 200L)
        val version = context("resolved:d", "Max connections: 100.", episode.id, createdAt = 900L, validFrom = 200L)
        val edges = listOf(notChosen, chosen).map { old ->
            MemoryEdge(
                MemoryEdgeId("resolved:d:supersedes:${old.id.value}"), version.id, old.id, MemoryRelationKind.Supersedes,
                createdAtEpochMillis = 900L,
                metadata = mapOf("basis" to "deliberation", "deliberation" to "d"),
            )
        }
        val snapshot = MemorySnapshot(episodes = listOf(episode), nodes = listOf(notChosen, chosen, version), edges = edges)

        assertNull(snapshot.validUntilOf(notChosen.id))
        assertNull(snapshot.validUntilOf(chosen.id))
        // As of any time, the replaced memories stay out; the version stands for them from the chosen one's valid-from.
        assertEquals(emptyList<String>(), MemoryValidTime.validAt(snapshot, 150L).map { it.id.value })
        assertEquals(listOf("resolved:d"), MemoryValidTime.validAt(snapshot, 250L).map { it.id.value })
    }

    @Test
    fun temporalIndexBucketsByValidTimeWhenAsked() {
        val hour = MemoryTemporalLevel.OneHour.durationMillis
        val late = episode("late", createdAt = 0L).copy(occurredAtEpochMillis = hour)
        val undated = episode("undated", createdAt = 0L)

        val ingest = MemoryTemporalIndex.build(listOf(late, undated))
        assertEquals(1, ingest.buckets.size)
        assertEquals(0L, ingest.bucketsForEpisode(late.id).single().startEpochMillis)

        val valid = MemoryTemporalIndex.build(listOf(late, undated), MemoryTimeAxis.Valid)
        assertEquals(hour, valid.bucketsForEpisode(late.id).single().startEpochMillis)
        assertEquals(0L, valid.bucketsForEpisode(undated.id).single().startEpochMillis)
        assertTrue(valid.buckets.all { it.level == MemoryTemporalLevel.FifteenMinutes })
    }

    private fun context(id: String, text: String, episodeId: MemoryEpisodeId, createdAt: Long, validFrom: Long = createdAt) = MemoryNode(
        id = MemoryNodeId(id),
        kind = MemoryNodeKind.Context,
        text = text,
        sourceEpisodeIds = setOf(episodeId),
        createdAtEpochMillis = createdAt,
        validFromEpochMillis = validFrom,
    )

    private fun episode(id: String, createdAt: Long) = MemoryEpisode(
        id = MemoryEpisodeId(id),
        sourceSessionId = "session-$id",
        userPrompt = "prompt-$id",
        chunks = emptyList(),
        createdAtEpochMillis = createdAt,
    )
}
