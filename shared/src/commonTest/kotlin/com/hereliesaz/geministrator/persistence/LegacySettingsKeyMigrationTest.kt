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

    @Test
    fun anEmptyDefaultWrittenBeforeMigrationDoesNotShadowTheLegacyValue() {
        for (default in listOf("", "{}", "[]", "null")) {
            val settings = InMemorySettings()
            settings.putString("haive.z.v1", "keeper's real value")
            settings.putString("aive.z.v1", default)

            settings.migrateLegacyKey("aive.z.v1")

            assertEquals("keeper's real value", settings.getStringOrNull("aive.z.v1"), "default '$default'")
            assertFalse(settings.hasKey("haive.z.v1"), "no stale legacy copy is kept")
        }
    }

    @Test
    fun aRealCurrentValueIsNeverOverwrittenAndTheStaleLegacyCopyIsDropped() {
        val settings = InMemorySettings()
        settings.putString("haive.z.v1", "old")
        settings.putString("aive.z.v1", "{\"a\":1}")

        settings.migrateLegacyKey("aive.z.v1")

        assertEquals("{\"a\":1}", settings.getStringOrNull("aive.z.v1"))
        assertFalse(settings.hasKey("haive.z.v1"))
    }

    @Test
    fun everyKeyPrMovedFromTheRetiredNameIsMigratedByItsStore() {
        // Keys that PR #803 renamed from haive.* to aive.*; each store runs migrateLegacyKey(WithPrefix) when it opens.
        val renamed = listOf(
            "aive.workflows.authored.v1", "aive.azphalt.installed-workflows.v1", "aive.azphalt.publisher-pins.v1",
            "aive.distributed-compute.configuration.v1", "aive.distributed-compute.enrolment.v1",
            "aive.inference.fabric.v1", "aive.inference.genealogy.v1", "aive.memory.persistence.v1",
        )
        for (key in renamed) {
            val settings = InMemorySettings()
            settings.putString(legacySettingsKey(key)!!, "legacy")
            settings.migrateLegacyKey(key)
            assertEquals("legacy", settings.getStringOrNull(key), key)
            assertFalse(settings.hasKey(legacySettingsKey(key)!!), key)
        }
    }
}
