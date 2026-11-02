package com.hereliesaz.geministrator

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CreaturePanelTraceTest {
    @Test
    fun tappingTheSetAsideCreatureReopensItsPanel() {
        assertTrue(reopensSetAsidePanel("build", setAsideTaskId = "build"))
        assertFalse(reopensSetAsidePanel("review", setAsideTaskId = "build"))
        assertFalse(reopensSetAsidePanel("build", setAsideTaskId = null))
    }

    @Test
    fun theSetAsidePanelLastsOnlyWhileItsCreatureStaysSelected() {
        assertEquals("build", setAsideAfterSelection(selectedTaskId = "build", setAsideTaskId = "build"))
        assertNull(setAsideAfterSelection(selectedTaskId = "review", setAsideTaskId = "build"))
        assertNull(setAsideAfterSelection(selectedTaskId = null, setAsideTaskId = "build"))
        assertNull(setAsideAfterSelection(selectedTaskId = "build", setAsideTaskId = null))
    }
}
