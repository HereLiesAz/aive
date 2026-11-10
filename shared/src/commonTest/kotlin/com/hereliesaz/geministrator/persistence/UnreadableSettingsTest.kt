package com.hereliesaz.geministrator.persistence

import com.hereliesaz.geministrator.mcp.McpServerConfig
import com.hereliesaz.geministrator.mcp.McpServerStore
import com.hereliesaz.geministrator.memory.SettingsMemoryBankRegistry
import com.hereliesaz.geministrator.workflow.improvement.SettingsImprovementStore
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UnreadableSettingsTest {
    private val garbage = """{"half":"written"""

    @Test
    fun mcpServerListThatCannotBeReadIsKeptAndNeverOverwritten() {
        val settings = MapSettings()
        settings.putString(McpServerStore.DEFAULT_STORAGE_KEY, garbage)
        val store = McpServerStore(settings)

        assertEquals(emptyList(), store.read())
        assertFailsWith<PersistenceCorruptionException> {
            store.write(listOf(McpServerConfig(id = "mcp-abcdefgh", name = "x", url = "https://example.com/mcp")))
        }
        assertEquals(garbage, settings.getString(McpServerStore.DEFAULT_STORAGE_KEY, ""))
        assertEquals(garbage, settings.getString(McpServerStore.DEFAULT_STORAGE_KEY + CORRUPT_SUFFIX, ""))
    }

    @Test
    fun improvementLedgerThatCannotBeReadIsKept() = runBlocking {
        val settings = MapSettings()
        settings.putString(SettingsImprovementStore.DEFAULT_KEY, garbage)
        val store = SettingsImprovementStore(settings)

        assertEquals(0, store.state().proposals.size)
        assertFailsWith<PersistenceCorruptionException> {
            store.merge {
                it.copy(cycles = listOf(com.hereliesaz.geministrator.workflow.improvement.CycleRecord("r", 1L, 0, 0)))
            }
        }
        assertEquals(garbage, settings.getString(SettingsImprovementStore.DEFAULT_KEY, ""))
    }

    @Test
    fun memoryBankRegistryThatCannotBeReadIsKept() = runBlocking {
        val settings = MapSettings()
        val registry = SettingsMemoryBankRegistry(settings, key = "banks")
        settings.putString("banks", garbage)

        assertEquals(emptySet(), registry.known())
        assertFailsWith<PersistenceCorruptionException> { registry.add("workflow-1") }
        assertEquals(garbage, settings.getString("banks", ""))
    }
}
