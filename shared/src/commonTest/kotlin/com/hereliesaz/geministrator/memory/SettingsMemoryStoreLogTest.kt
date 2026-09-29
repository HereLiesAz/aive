package com.hereliesaz.geministrator.memory

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsMemoryStoreLogTest {
    @Test
    fun commitsAppendToTheLogAndReplayOnLoad() = runBlocking {
        val settings = MapSettings()
        val store = SettingsMemoryStore(settings, compactEvery = 100)
        repeat(5) { index -> assertTrue(store.commit(index.toLong(), addEpisode(index))) }

        assertNull(settings.getStringOrNull(SettingsMemoryStore.DEFAULT_STORAGE_KEY), "nothing compacted yet")
        assertEquals(5, settings.getInt("${SettingsMemoryStore.DEFAULT_STORAGE_KEY}.log.count", 0))

        val reopened = SettingsMemoryStore(settings, compactEvery = 100).read()
        assertEquals(5L, reopened.revision)
        assertEquals((0 until 5).map { "e$it" }, reopened.episodes.map { it.id.value })
    }

    @Test
    fun compactionFoldsTheLogIntoTheSnapshot() = runBlocking {
        val settings = MapSettings()
        val store = SettingsMemoryStore(settings, compactEvery = 3)
        repeat(7) { index -> assertTrue(store.commit(index.toLong(), addEpisode(index))) }

        assertEquals(1, settings.getInt("${SettingsMemoryStore.DEFAULT_STORAGE_KEY}.log.count", 0))
        val reopened = SettingsMemoryStore(settings, compactEvery = 3).read()
        assertEquals(7L, reopened.revision)
        assertEquals(7, reopened.episodes.size)
    }

    @Test
    fun logEntriesAlreadyInTheSnapshotAreNotReapplied() = runBlocking {
        val settings = MapSettings()
        val store = SettingsMemoryStore(settings, compactEvery = 100)
        repeat(3) { index -> assertTrue(store.commit(index.toLong(), addEpisode(index))) }
        // Simulate a crash after writing a compacted snapshot but before the log was cleared.
        val compacted = store.read()
        settings.putString(
            SettingsMemoryStore.DEFAULT_STORAGE_KEY,
            SettingsMemoryStore.defaultJson.encodeToString(MemorySnapshot.serializer(), compacted),
        )

        val reopened = SettingsMemoryStore(settings, compactEvery = 100).read()
        assertEquals(3L, reopened.revision)
        assertEquals(3, reopened.episodes.size)
    }

    @Test
    fun staleRevisionIsRejectedWithoutWriting() = runBlocking {
        val settings = MapSettings()
        val store = SettingsMemoryStore(settings)
        assertTrue(store.commit(0, addEpisode(0)))
        assertEquals(false, store.commit(0, addEpisode(1)))
        assertEquals(1, SettingsMemoryStore(settings).read().episodes.size)
    }

    private fun addEpisode(index: Int) = MemoryStoreMutation(
        episodesToAdd = listOf(
            MemoryEpisode(
                id = MemoryEpisodeId("e$index"),
                sourceSessionId = "s$index",
                userPrompt = "prompt $index",
                chunks = emptyList(),
                createdAtEpochMillis = index.toLong(),
            ),
        ),
    )
}
