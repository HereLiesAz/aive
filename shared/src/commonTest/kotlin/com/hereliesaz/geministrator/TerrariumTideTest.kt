package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerrariumTideTest {
    private fun wf(name: String) = WorkflowDefinition(id = WorkflowDefinitionId("w"), name = name, tasks = emptyList())

    @Test
    fun undoAndRedoWalkTheEdits() {
        val tide = TerrariumTide(present = wf("a")).record(wf("b")).record(wf("c"))
        val back = tide.undo().undo()
        assertEquals("a", back.present.name)
        assertFalse(back.canUndo)
        assertEquals("c", back.redo().redo().present.name)
    }

    @Test
    fun aFreshEditClearsRedoAndARepeatIsNotAnEdit() {
        val tide = TerrariumTide(present = wf("a")).record(wf("b")).undo().record(wf("c"))
        assertFalse(tide.canRedo)
        assertTrue(tide.canUndo)
        assertEquals(tide, tide.record(wf("c")))
    }
}
