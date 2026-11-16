package com.hereliesaz.geministrator.prompts

import com.hereliesaz.geministrator.DurableUiStateStore
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromptHistoryStoreTest {
    private val settings = MapSettings()
    private var now = 100L
    private fun store() = PromptHistoryStore(DurableUiStateStore(settings), clock = { now++ })

    @Test
    fun recordsAndReadsBack() {
        val s = store()
        s.record(PromptKind.Objective, "fix the build")
        assertEquals(listOf("fix the build"), s.entries(PromptKind.Objective).map { it.text })
        assertTrue(s.entries(PromptKind.Repository).isEmpty())
    }

    @Test
    fun keyFitsPreferencesKeyLimit() {
        PromptKind.entries.forEach { kind ->
            val full = "${DurableUiStateStore.ROOT_KEY}.${PromptHistoryStore.keyOf(kind)}.__chunk.99.999"
            assertTrue(full.length < 80, full)
        }
        assertTrue("${DurableUiStateStore.ROOT_KEY}.${PromptHistoryStore.ENABLED_KEY}".length < 80)
    }

    @Test
    fun disabledStoreRecordsNothingAndClearsExisting() {
        val s = store()
        s.record(PromptKind.Objective, "fix the build")
        s.setEnabled(false)
        assertFalse(s.enabled())
        assertTrue(s.entries(PromptKind.Objective).isEmpty())
        s.record(PromptKind.Objective, "another goal")
        assertTrue(settings.keys.none { it.contains("prompts.v1.objective") })
        s.setEnabled(true)
        assertTrue(s.entries(PromptKind.Objective).isEmpty())
    }

    @Test
    fun clearRemovesEveryPromptKeyButKeepsTheSwitch() {
        val s = store()
        PromptKind.entries.forEach { s.record(it, "something for ${it.key}") }
        s.clearAll()
        PromptKind.entries.forEach { assertTrue(s.entries(it).isEmpty()) }
        assertTrue(settings.keys.none { it.contains("prompts.v1.") && !it.endsWith("prompts.v1.enabled") })
    }

    @Test
    fun aKeyIsNeverStored() {
        val s = store()
        s.record(PromptKind.Objective, "use sk-abcdefghijklmnopqrstuvwxyz123456 for it")
        assertTrue(s.entries(PromptKind.Objective).isEmpty())
        assertEquals(emptyList(), settings.keys.filter { it.contains("prompts.v1.objective") })
    }

    @Test
    fun historyIsUnderTheUiStatePrefixThatSetupFilesExclude() {
        val s = store()
        s.record(PromptKind.Objective, "fix the build")
        assertTrue(settings.keys.filter { "prompts.v1" in it }.all { it.startsWith(DurableUiStateStore.ROOT_KEY + ".") })
    }
}
