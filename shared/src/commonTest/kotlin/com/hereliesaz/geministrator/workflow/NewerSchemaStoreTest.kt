package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.persistence.ChunkedStringSettings
import com.hereliesaz.geministrator.persistence.PersistenceCorruptionException
import com.hereliesaz.geministrator.persistence.SettingsWorkflowPersistence
import com.hereliesaz.geministrator.persistence.UnsupportedPersistenceSchemaException

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Data a newer build saved is reported as such, never read as corruption, and never deleted. */
class NewerSchemaStoreTest {
    private val key = SettingsWorkflowPersistence.DEFAULT_STORAGE_KEY

    // A value this build does not know (a status a later build added) inside a snapshot that says so.
    private val newerSnapshot = """{"version":${SettingsWorkflowPersistence.CURRENT_SCHEMA_VERSION + 4},"runs":[{"id":"r","status":"Hibernating"}]}"""

    private fun storeHolding(text: String): Pair<MapSettings, SettingsWorkflowPersistence> {
        val settings = MapSettings()
        ChunkedStringSettings(settings, setOf(key)).putString(key, text)
        return settings to SettingsWorkflowPersistence(settings)
    }

    @Test
    fun aSnapshotFromANewerBuildIsReportedAsAnUnsupportedSchemaNotAsCorruption() = runBlocking<Unit> {
        val (_, store) = storeHolding(newerSnapshot)

        val failure = assertFailsWith<UnsupportedPersistenceSchemaException> { store.projects.all() }

        assertEquals(SettingsWorkflowPersistence.CURRENT_SCHEMA_VERSION + 4, failure.foundVersion)
        assertTrue("newer version" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun recoveringFromCorruptionNeverDeletesDataANewerBuildSaved() = runBlocking<Unit> {
        val (settings, store) = storeHolding(newerSnapshot)

        assertFailsWith<UnsupportedPersistenceSchemaException> { store.recoverFromCorruption() }

        // Before: the store was deleted, taking everything the newer build had saved with it.
        assertEquals(newerSnapshot, ChunkedStringSettings(settings, setOf(key)).getStringOrNull(key))
    }

    @Test
    fun aSnapshotThatIsDamagedAndSaysNothingNewerIsStillCorruption() = runBlocking<Unit> {
        val (_, store) = storeHolding("""{"version":1,"runs":[{"bogus":true}]}""")

        assertFailsWith<PersistenceCorruptionException> { store.projects.all() }
        assertNotNull(store.lastCorruptionError)
    }
}
