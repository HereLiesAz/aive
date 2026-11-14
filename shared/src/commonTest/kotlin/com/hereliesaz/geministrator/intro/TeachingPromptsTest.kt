package com.hereliesaz.geministrator.intro

import com.hereliesaz.geministrator.tour.ExpectKind
import com.hereliesaz.geministrator.tour.TargetRow
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TeachingPromptsTest {
    private val row = TargetRow("A", "Alpha", emptySet(), true, setOf(ExpectKind.Tapped))
    private val step = TeachingStep("A", ExpectKind.Tapped, "Learn it")

    private fun lesson(last: Boolean) = TeachingPrompts.lessonTask(
        step, row, "somewhere", emptyList(), "goal", first = false, smaller = false, shakyBefore = false,
        previousSentence = null, problems = emptyList(), last = last,
    )

    @Test
    fun onlyTheLastLessonAsksWhetherTheKeeperKnowsWhatTheyAreDoingNow() {
        assertTrue("know what you are doing" in lesson(last = true))
        assertFalse("know what you are doing" in lesson(last = false))
    }

    @Test
    fun noPromptAsksTheKeeperToChooseOrConfirmWhatReachedMeans() {
        val plan = TeachingPrompts.plannerTask("goal", "A | Alpha | - | tapped | always on screen", emptyList(), emptyList(), emptyList(), TutorialScope.Focused, emptyList(), emptyList())
        val review = TeachingPrompts.reviewerTask("goal", listOf(step), listOf(row))
        for (text in listOf(plan, review, lesson(last = false), lesson(last = true))) {
            val lower = text.lowercase()
            assertFalse("confirm the goal" in lower || "choose the goal" in lower || "what reached means" in lower, text)
        }
    }
}
