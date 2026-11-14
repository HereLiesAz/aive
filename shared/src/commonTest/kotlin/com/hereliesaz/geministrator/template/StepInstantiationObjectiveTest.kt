package com.hereliesaz.geministrator.template

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StepInstantiationObjectiveTest {
    @Test
    fun aBoundPortNamesTheStepThatFillsIt() {
        val objective = StepInstantiation.objectiveWith("Check it.", mapOf("work" to "@t1.out", "requirements" to "@t2.out"))
        assertTrue("- work: the output of step t1" in objective)
        assertTrue("- requirements: the output of step t2" in objective)
    }

    @Test
    fun swappingTheBindingsChangesTheObjective() {
        val one = StepInstantiation.objectiveWith("Check it.", mapOf("work" to "@a", "requirements" to "@b"))
        val two = StepInstantiation.objectiveWith("Check it.", mapOf("work" to "@b", "requirements" to "@a"))
        assertNotEquals(one, two)
    }

    @Test
    fun theWordingComesBackWithoutTheInputsBlock() {
        val objective = StepInstantiation.objectiveWith("Check it.", mapOf("work" to "@a", "note" to "be kind"))
        assertEquals("Check it.", StepInstantiation.wordingOf(objective))
        assertTrue("- note: be kind" in objective)
    }
}
