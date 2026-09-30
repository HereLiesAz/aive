package com.hereliesaz.geministrator.memory

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SqlMemoryStoreTest {
    private fun tempDb(): File = File(createTempDirectory("aive-memory").toFile(), "memory.db")

    @Test
    fun graphSurvivesReopenInOrder() = runBlocking {
        val file = tempDb()
        val store = desktopSqlMemoryStore(file)
        val layer = AgentMemoryLayer.createWithMicroAgents(store, ProgrammaticMemoryClerks.all { 5L })
        layer.queue.enqueueSession(
            MemorySessionEnvelope(
                sourceSessionId = "s",
                userPrompt = "Replace SettingsMemoryStore with SQLDelight because commits re-encode everything.",
                parts = listOf(MemorySessionPart(MemorySourceKind.Message, "Agent", "The gradle build passes after the migration.")),
                closedAtEpochMillis = 5L,
            ),
        )
        while (layer.consolidateOne(5L) != MemoryConsolidationResult.Idle) Unit
        val before = store.read()

        val reopened = desktopSqlMemoryStore(file).read()
        assertEquals(before, reopened)
        assertTrue(reopened.nodes.isNotEmpty() && reopened.edges.isNotEmpty())
        assertTrue(reopened.queue.single().status == MemoryQueueStatus.Complete)
    }

    @Test
    fun queueUpsertsAndDeclinesPersist() = runBlocking {
        val file = tempDb()
        val store = desktopSqlMemoryStore(file)
        val episode = MemoryEpisode(MemoryEpisodeId("e"), "s", userPrompt = "p", chunks = emptyList(), createdAtEpochMillis = 1)
        val entry = MemoryQueueEntry(MemoryQueueId("q"), 1, episode.id, createdAtEpochMillis = 1)
        assertTrue(store.commit(0, MemoryStoreMutation(episodesToAdd = listOf(episode), queueUpserts = listOf(entry))))
        assertTrue(
            store.commit(
                1,
                MemoryStoreMutation(
                    queueUpserts = listOf(entry.copy(status = MemoryQueueStatus.Failed, attempt = 3, lastError = "x")),
                    condensationDeclinesToAdd = listOf("a|b"),
                ),
            ),
        )
        val reopened = desktopSqlMemoryStore(file).read()
        assertEquals(2L, reopened.revision)
        assertEquals(MemoryQueueStatus.Failed, reopened.queue.single().status)
        assertEquals(listOf("a|b"), reopened.declinedCondensations)
    }

    @Test
    fun staleOrInvalidCommitsWriteNothing() = runBlocking {
        val file = tempDb()
        val store = desktopSqlMemoryStore(file)
        val episode = MemoryEpisode(MemoryEpisodeId("e"), "s", userPrompt = "p", chunks = emptyList(), createdAtEpochMillis = 1)
        assertTrue(store.commit(0, MemoryStoreMutation(episodesToAdd = listOf(episode))))
        assertFalse(store.commit(0, MemoryStoreMutation(episodesToAdd = listOf(episode.copy(id = MemoryEpisodeId("f"))))))
        runCatching { store.commit(1, MemoryStoreMutation(episodesToAdd = listOf(episode))) }
        assertEquals(listOf("e"), desktopSqlMemoryStore(file).read().episodes.map { it.id.value })
    }

    @Test
    fun legacySettingsStoreIsImportedOnce() = runBlocking {
        val settings = MapSettings()
        val legacy = SettingsMemoryStore(settings)
        val episode = MemoryEpisode(MemoryEpisodeId("old"), "s", userPrompt = "p", chunks = emptyList(), createdAtEpochMillis = 1)
        legacy.commit(0, MemoryStoreMutation(episodesToAdd = listOf(episode)))

        val store = desktopSqlMemoryStore(null)
        assertTrue(store.importLegacy(legacy))
        assertEquals(listOf("old"), store.read().episodes.map { it.id.value })
        assertEquals(0, SettingsMemoryStore(settings).read().episodes.size)
        assertFalse(store.importLegacy(legacy))
    }
}
