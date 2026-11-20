package com.hereliesaz.geministrator.orchestration

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlanSizingTest {
    private fun step(id: String, role: String = "writer", deps: List<String> = emptyList(), approval: Boolean = false) =
        OrchestrationPlanStep(id = id, name = id, objective = "do $id", roleId = role, dependsOn = deps, requiresHumanApproval = approval)

    private fun plan(vararg steps: OrchestrationPlanStep) = OrchestrationPlan(steps.toList())

    @Test
    fun oneStepIsOneCreatureWithNoReason() {
        val size = PlanSizing.measure("Fix the typo in the readme", plan(step("a")))
        assertEquals(1, size.creatures)
        assertTrue(size.single)
        assertNull(size.reason)
        assertEquals(PlanSizeChoice.One, size.choice)
        assertFalse(size.bigForSmallGoal)
    }

    @Test
    fun reasonsFollowThePreferredOrder() {
        assertEquals(SwarmReason.NeedsApproval, PlanSizing.reasonFor(listOf(step("a"), step("b", "x", listOf("a"), approval = true))))
        assertEquals(SwarmReason.DifferentKinds, PlanSizing.reasonFor(listOf(step("a"), step("b", "reviewer", listOf("a")))))
        assertEquals(SwarmReason.SideBySide, PlanSizing.reasonFor(listOf(step("a"), step("b"))))
        assertEquals(SwarmReason.Separate, PlanSizing.reasonFor(listOf(step("a"), step("b", deps = listOf("a")))))
    }

    @Test
    fun aSmallGoalWithMoreThanThreeCreaturesIsFlagged() {
        val four = plan(step("a"), step("b", deps = listOf("a")), step("c", deps = listOf("b")), step("d", deps = listOf("c")))
        assertTrue(PlanSizing.measure("Write a short poem", four).bigForSmallGoal)
        assertFalse(PlanSizing.measure("Write a short poem", plan(step("a"), step("b"), step("c"))).bigForSmallGoal)
        val long = "Write a detailed report about the history of the bicycle with sources and a summary"
        assertFalse(PlanSizing.measure(long, four).bigForSmallGoal)
    }

    @Test
    fun theWordLimitIsExactlyTwelve() {
        val eleven = List(11) { "w" }.joinToString(" ")
        val twelve = List(12) { "w" }.joinToString(" ")
        assertTrue(PlanSizing.bigForSmallGoal(eleven, 4))
        assertFalse(PlanSizing.bigForSmallGoal(twelve, 4))
        assertEquals(0, PlanSizing.goalWords("   "))
    }

    @Test
    fun aGoalToBeTaughtTheAppIsShortAndWideAndNeverFlaggedBig() {
        assertFalse(PlanSizing.bigForSmallGoal("Teach me how to use the Aive", 7))
        assertTrue(PlanSizing.bigForSmallGoal("Fix the login bug", 7))
    }

    @Test
    fun overrideAndStoryText() {
        assertEquals(PlanSizeChoice.Swarm, PlanSizeChoice.One.opposite)
        assertTrue("ONE creature" in PlanSizing.overrideInstruction(PlanSizeChoice.One))
        assertTrue("swarm" in PlanSizing.overrideInstruction(PlanSizeChoice.Swarm))
        assertEquals("Size: the queen proposed 4 creatures", PlanSizing.storyLine(4, null, 4, false))
        val line = PlanSizing.storyLine(4, PlanSizeChoice.One, 1, true)
        assertTrue("flagged" in line && "asked for one creature" in line && "one creature" in line.substringAfter("again with "), line)
    }
}
