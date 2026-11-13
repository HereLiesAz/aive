package com.hereliesaz.geministrator.intro

import com.hereliesaz.geministrator.tour.ExpectKind
import com.hereliesaz.geministrator.tour.TargetRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TeachingPlanTest {
    private val rows = listOf(
        TargetRow("A", "Alpha", emptySet(), true, setOf(ExpectKind.Tapped, ExpectKind.Opened)),
        TargetRow("B", "Beta", setOf("A"), false, setOf(ExpectKind.Tapped)),
        TargetRow("C", "Gamma", setOf("A", "B"), false, setOf(ExpectKind.Tapped, ExpectKind.StoryView)),
    )

    @Test
    fun parsesStepLinesAndIgnoresTheRest() {
        val steps = parseTeachingPlan("Plan\n1. Learn one. Target: A. Checkpoint: tapped.\n- Learn two. Target: b. Checkpoint: Opened\nnotes")
        assertEquals(listOf("A", "B"), steps.map { it.target })
        assertEquals(listOf(ExpectKind.Tapped, ExpectKind.Opened), steps.map { it.kind })
        assertEquals("Learn one", steps[0].learns)
    }

    @Test
    fun aGoodPlanPassesTheFloor() {
        val steps = parseTeachingPlan("x. Target: A. Checkpoint: tapped.\ny. Target: B. Checkpoint: tapped.")
        assertEquals(emptyList(), TeachingPlanFloor.check(steps, rows, TutorialScope.Focused))
    }

    @Test
    fun theFloorNamesEachBrokenRule() {
        fun rules(plan: String, scope: TutorialScope = TutorialScope.Focused) =
            TeachingPlanFloor.check(parseTeachingPlan(plan), rows, scope).map { it.rule }.toSet()
        assertTrue("T1" in rules(""))
        assertTrue("T1" in rules("x. Target: A. Checkpoint: tapped.", TutorialScope.Broad))
        assertTrue("T2" in rules("x. Target: NOPE. Checkpoint: tapped."))
        assertTrue("T2" in rules("x. Target: C. Checkpoint: storyView."))
        assertTrue("T3" in rules("x. Target: A. Checkpoint: tapped.\ny. Target: A. Checkpoint: opened."))
        assertTrue("T4" in rules("x. Target: B. Checkpoint: tapped."))
        assertTrue("T5" in rules("x. Target: A. Checkpoint: tapped.\ny. Target: C. Checkpoint: tapped."))
        assertTrue("T6" in rules(List(30) { "w" }.joinToString(" ") + ". Target: A. Checkpoint: tapped."))
    }

    @Test
    fun readsVerdicts() {
        assertEquals(PlanVerdict.Approve, parsePlanVerdict("VERDICT: approve"))
        val revise = parsePlanVerdict("**VERDICT: revise**\n- step 2 is two things\n- step 4 repeats step 1")
        assertIs<PlanVerdict.Revise>(revise)
        assertEquals(2, revise.findings.size)
        assertEquals(PlanVerdict.Unreadable, parsePlanVerdict("looks fine to me"))
        assertEquals(1, (parsePlanVerdict("VERDICT: revise") as PlanVerdict.Revise).findings.size)
    }
}
