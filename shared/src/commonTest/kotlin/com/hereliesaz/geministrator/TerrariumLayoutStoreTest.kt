package com.hereliesaz.geministrator

import com.hereliesaz.conveyance.h2g2.H2g2TerrariumPosition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.testing.PreferencesLimitedSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TerrariumLayoutStoreTest {
    @Test
    fun longDefinitionAndSubjectIdsStayWithinThePreferencesKeyLimitAndRoundTrip() {
        val settings = PreferencesLimitedSettings()
        val store = SettingsTerrariumLayoutStore(settings)
        val definition = WorkflowDefinitionId("workflow-with-a-rather-long-identifier-0123456789")
        val subject = "task-with-an-even-longer-identifier-than-the-workflow-0123456789"
        store.put(definition, subject, H2g2TerrariumPosition(0.25f, 0.5f))

        assertEquals(setOf(subject), store.load(definition).keys)
        assertTrue(settings.keys.all { it.length <= PreferencesLimitedSettings.MAX_KEY_LENGTH })
        store.clear(definition)
        assertTrue(store.load(definition).isEmpty())
    }
}
