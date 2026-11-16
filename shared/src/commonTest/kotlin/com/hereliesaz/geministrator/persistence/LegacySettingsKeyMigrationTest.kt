package com.hereliesaz.geministrator.persistence

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class LegacySettingsKeyMigrationTest {
    @Test
    fun legacyValueMovesToCurrentKeyAndLegacyKeyIsRemoved() {
        val settings = InMemorySettings()
        settings.putString("haive.workflows.authored.v1", "wf-1")

        settings.migrateLegacyKey("aive.workflows.authored.v1")

        assertEquals("wf-1", settings.getStringOrNull("aive.workflows.authored.v1"))
        assertFalse(settings.hasKey("haive.workflows.authored.v1"))
    }

    @Test
    fun existingCurrentValueWinsOverLegacyValue() {
        val settings = InMemorySettings()
        settings.putString("haive.x.v1", "old")
        settings.putString("aive.x.v1", "new")

        settings.migrateLegacyKey("aive.x.v1")

        assertEquals("new", settings.getStringOrNull("aive.x.v1"))
        assertFalse(settings.hasKey("haive.x.v1"))
    }

    @Test
    fun migrationIsIdempotentAndIgnoresNonProductKeys() {
        val settings = InMemorySettings()
        settings.putString("haive.y.v1", "v")
        settings.migrateLegacyKey("aive.y.v1")
        settings.migrateLegacyKey("aive.y.v1")
        settings.migrateLegacyKey("other.y.v1")

        assertEquals("v", settings.getStringOrNull("aive.y.v1"))
        assertNull(settings.getStringOrNull("other.y.v1"))
    }

    @Test
    fun prefixMigrationCarriesDerivedStringAndIntKeys() {
        val settings = InMemorySettings()
        settings.putString("haive.memory.persistence.v1", "snapshot")
        settings.putString("haive.memory.persistence.v1.log.0", "entry")
        settings.putInt("haive.memory.persistence.v1.log.count", 1)

        settings.migrateLegacyKeysWithPrefix("aive.memory.persistence.v1")

        assertEquals("snapshot", settings.getStringOrNull("aive.memory.persistence.v1"))
        assertEquals("entry", settings.getStringOrNull("aive.memory.persistence.v1.log.0"))
        assertEquals(1, settings.getInt("aive.memory.persistence.v1.log.count", 0))
        assertEquals(emptySet<String>(), settings.keys.filter { it.startsWith("haive.") }.toSet())
    }
}
