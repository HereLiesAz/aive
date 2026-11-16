package com.hereliesaz.geministrator

import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UiStateVersionGateTest {
    @Test
    fun majorMinorReadsTheFirstTwoParts() {
        assertEquals("0.10", UiStateVersionGate.majorMinor("0.10.0.779"))
        assertEquals("0.60", UiStateVersionGate.majorMinor("0.60.7.1"))
        assertNull(UiStateVersionGate.majorMinor("dev"))
        assertNull(UiStateVersionGate.majorMinor(null))
        assertNull(UiStateVersionGate.majorMinor("a.b.c"))
    }

    @Test
    fun resetsOnlyWhenMajorMinorDiffers() {
        assertFalse(UiStateVersionGate.shouldResetNavigation(null, "0.10.0.1"))
        assertFalse(UiStateVersionGate.shouldResetNavigation("0.10", "0.10.0.9"))
        assertFalse(UiStateVersionGate.shouldResetNavigation("0.10", "dev"))
        assertTrue(UiStateVersionGate.shouldResetNavigation("0.9", "0.10.0.1"))
    }

    @Test
    fun anUpgradeForgetsWhereIWasButKeepsDrafts() {
        val store = DurableUiStateStore(MapSettings())
        store.putString(UiStateVersionGate.VERSION_KEY, "0.9")
        store.putString("keeper.section", "Limits")
        store.putBoolean("keeper.open", true)
        store.putString("workflows.save-name", "my draft")
        UiStateVersionGate.apply(store, "0.10.0.5")
        assertNull(store.getString("keeper.section"))
        assertNull(store.getBoolean("keeper.open"))
        assertEquals("my draft", store.getString("workflows.save-name"))
        assertEquals("0.10", store.getString(UiStateVersionGate.VERSION_KEY))
    }

    @Test
    fun theSameVersionKeepsEverything() {
        val store = DurableUiStateStore(MapSettings())
        UiStateVersionGate.apply(store, "0.10.0.5")
        store.putString("keeper.section", "Limits")
        UiStateVersionGate.apply(store, "0.10.0.6")
        assertEquals("Limits", store.getString("keeper.section"))
    }

    @Test
    fun anUpgradeKeepsTheSelectedWorkflowSoADraftCanBeRecovered() {
        val store = DurableUiStateStore(MapSettings())
        store.putString(UiStateVersionGate.VERSION_KEY, "0.9")
        store.putString(WorkflowLibraryKeys.SELECTED_KEY, "authored:x")
        UiStateVersionGate.apply(store, "0.10.0.5")
        assertEquals("authored:x", store.getString(WorkflowLibraryKeys.SELECTED_KEY))
    }
}
