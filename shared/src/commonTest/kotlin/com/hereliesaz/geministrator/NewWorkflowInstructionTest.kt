package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.RepositorySource
import com.hereliesaz.geministrator.orchestration.SMALLEST_PLAN_INSTRUCTION
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NewWorkflowInstructionTest {
    private val repo = RepositoryRef(owner = "", name = "aive", source = RepositorySource.Local, localPath = "/x/aive")
    private val base = "Create the minimal executable dependency-correct workflow DAG needed to satisfy the objective."

    @Test
    fun anObjectiveWithNoRepositoryAndNoLessonKeepsTheOriginalInstruction() {
        assertEquals(base + SMALLEST_PLAN_INSTRUCTION, newWorkflowInstruction("Fix the login bug", null))
    }

    @Test
    fun aLinkedRepositoryIsNamedWithWhatAStepSeesOfIt() {
        val text = newWorkflowInstruction("Fix the login bug", repo)
        assertTrue("(aive)" in text && "short excerpts" in text, text)
        assertFalse("teach" in text, text)
    }

    @Test
    fun anObjectiveToTeachGetsTheShapeOfALesson() {
        for (objective in listOf("Teach me the ins and outs of this app", "Explain how the planner works", "I want to learn Kotlin")) {
            val text = newWorkflowInstruction(objective, repo)
            assertTrue("curriculum" in text && "require no approval" in text, "$objective: $text")
        }
    }

    @Test
    fun theSmallestSufficientPlanRuleIsInTheInstructionExactlyOnce() {
        for (text in listOf(newWorkflowInstruction("Fix the login bug", null), newWorkflowInstruction("Teach me Kotlin", repo))) {
            assertEquals(1, Regex("Prefer the smallest sufficient plan").findAll(text).count(), text)
            assertTrue("ONE creature" in text && "parallel" in text, text)
        }
    }
}
