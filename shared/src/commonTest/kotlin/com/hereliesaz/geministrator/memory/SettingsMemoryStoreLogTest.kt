package com.hereliesaz.geministrator.memory

import com.russhwolf.settings.MapSettings
import com.russhwolf.settings.Settings
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    @Test
    fun failedWriteDoesNotPoisonTheIdIndex() = runBlocking {
        val backing = MapSettings()
        var failWrites = false
        val settings = object : Settings by backing {
            override fun putString(key: String, value: String) {
                if (failWrites) error("quota exceeded")
                backing.putString(key, value)
            }
        }
        val store = SettingsMemoryStore(settings, compactEvery = 100)
        assertTrue(store.commit(0, addEpisode(0)))

        failWrites = true
        assertFailsWith<IllegalStateException> { store.commit(1, addEpisode(1)) }
        failWrites = false

        // The retry must not be refused as "episode e1 already exists".
        assertTrue(store.commit(1, addEpisode(1)))
        assertEquals(listOf("e0", "e1"), SettingsMemoryStore(backing, compactEvery = 100).read().episodes.map { it.id.value })
    }

    @Test
    fun importedGraphMustBeWhole() {
        val episode = addEpisode(0).episodesToAdd.single()
        val dangling = MemorySnapshot(
            episodes = listOf(episode),
            edges = listOf(
                MemoryEdge(
                    id = MemoryEdgeId("edge"),
                    from = MemoryNodeId("a"),
                    to = MemoryNodeId("b"),
                    relation = MemoryRelationKind.entries.first(),
                    createdAtEpochMillis = 0,
                ),
            ),
        )
        assertFailsWith<IllegalArgumentException> { dangling.requireConsistentGraph() }
        assertFailsWith<IllegalArgumentException> {
            MemorySnapshot(episodes = listOf(episode, episode)).requireConsistentGraph()
        }
        MemorySnapshot(episodes = listOf(episode)).requireConsistentGraph()
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
