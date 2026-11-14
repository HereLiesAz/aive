package com.hereliesaz.geministrator.intro

import com.hereliesaz.geministrator.queen.QueenLevel
import com.hereliesaz.geministrator.tour.CheckInReply
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The queen's decision about the keeper's progress: signals in, one of four out, no model call. */
class TutorialAttentionTest {
    private fun decide(s: AttentionSignals) = tutorialAttention(s)

    @Test
    fun calmSignalsSayNothing() {
        assertEquals(TutorialAttention.Nothing, decide(AttentionSignals(stepsPassed = 2)))
    }

    @Test
    fun aLostAnswerSlowsHerDownBeforeAnythingElse() {
        val lost = AttentionSignals(stepsPassed = 3, unhandledAnswer = CheckInReply.Lost, checkInOpen = true, missedCheckpoint = true)
        assertEquals(TutorialAttention.SlowDown, decide(lost))
    }

    @Test
    fun otherAnswersDoNotSlowHerDown() {
        for (reply in listOf(CheckInReply.Yes, CheckInReply.SortOf, CheckInReply.ShowMe)) {
            assertEquals(TutorialAttention.Nothing, decide(AttentionSignals(stepsPassed = 1, unhandledAnswer = reply)))
        }
    }

    @Test
    fun aMissedCheckpointAsks() {
        assertEquals(TutorialAttention.Ask, decide(AttentionSignals(stepsPassed = 1, missedCheckpoint = true)))
    }

    @Test
    fun repeatedBackNavigationAsksButOneBackStepDoesNot() {
        assertEquals(TutorialAttention.Nothing, decide(AttentionSignals(backNavigations = AttentionLimits.BACK_NAVIGATIONS_LOST - 1)))
        assertEquals(TutorialAttention.Ask, decide(AttentionSignals(backNavigations = AttentionLimits.BACK_NAVIGATIONS_LOST)))
    }

    @Test
    fun aLongDwellAsks() {
        assertEquals(TutorialAttention.Nothing, decide(AttentionSignals(dwellMillis = AttentionLimits.LONG_DWELL_MILLIS - 1)))
        assertEquals(TutorialAttention.Ask, decide(AttentionSignals(dwellMillis = AttentionLimits.LONG_DWELL_MILLIS)))
    }

    @Test
    fun aLessonBoundaryThePaceMarksAsks() {
        assertEquals(TutorialAttention.Ask, decide(AttentionSignals(stepsPassed = 4, boundaryDue = true)))
    }

    @Test
    fun neverStacksAQuestionOnAnOpenOne() {
        val open = AttentionSignals(checkInOpen = true, missedCheckpoint = true, boundaryDue = true, level = QueenLevel.Helpful, stepsPassed = 9, stepsSinceUpdate = 9)
        assertEquals(TutorialAttention.Nothing, decide(open))
    }

    @Test
    fun asksOnlyOncePerStepForTheLostSigns() {
        assertEquals(TutorialAttention.Nothing, decide(AttentionSignals(missedCheckpoint = true, askedForThisStep = true)))
    }

    @Test
    fun aMixedSignalIsAskedNotGuessedAt() {
        // Said yes a moment ago, yet the checkpoint was missed: the answer settles it, with no model call.
        val mixed = AttentionSignals(stepsPassed = 2, missedCheckpoint = true, unhandledAnswer = CheckInReply.Yes)
        assertEquals(TutorialAttention.Ask, decide(mixed))
        assertTrue(keeperMayBeLost(mixed))
    }

    @Test
    fun updatesAreHelpfulsAndEveryFewSteps() {
        val due = AttentionSignals(stepsPassed = 3, stepsSinceUpdate = AttentionLimits.UPDATE_EVERY_STEPS)
        assertEquals(TutorialAttention.Update, decide(due.copy(level = QueenLevel.Helpful)))
        assertEquals(TutorialAttention.Nothing, decide(due.copy(level = QueenLevel.Quiet)))
        assertEquals(TutorialAttention.Nothing, decide(due.copy(level = QueenLevel.Helpful, stepsSinceUpdate = AttentionLimits.UPDATE_EVERY_STEPS - 1)))
        assertEquals(TutorialAttention.Nothing, decide(due.copy(level = QueenLevel.Helpful, stepsPassed = 0)))
    }

    @Test
    fun aQuestionOutranksAnUpdate() {
        val both = AttentionSignals(stepsPassed = 3, stepsSinceUpdate = 3, missedCheckpoint = true, level = QueenLevel.Helpful)
        assertEquals(TutorialAttention.Ask, decide(both))
    }

    @Test
    fun theSameSignalsGiveTheSameDecision() {
        val s = AttentionSignals(stepsPassed = 5, backNavigations = 2, level = QueenLevel.Helpful)
        assertEquals(decide(s), decide(s))
        assertFalse(keeperMayBeLost(AttentionSignals()))
    }
}
