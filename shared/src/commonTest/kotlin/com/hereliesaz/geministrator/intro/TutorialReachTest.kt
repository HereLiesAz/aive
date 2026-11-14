package com.hereliesaz.geministrator.intro

import com.hereliesaz.geministrator.tour.CheckInReply
import com.hereliesaz.geministrator.tour.StepOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** "Reached" is judged from the keeper's answers and what the app saw; it is never put to the keeper as a question. */
class TutorialReachTest {
    private val met = StepOutcome.Met
    private val next = StepOutcome.Next

    @Test
    fun aYesWithEveryStepPassedIsEnough() {
        assertTrue(TutorialReach.reached(listOf(next, next, next), listOf(CheckInReply.Yes)))
    }

    @Test
    fun theLatestFeelingDecides() {
        val outcomes = listOf(met, met, met)
        assertFalse(TutorialReach.reached(outcomes, listOf(CheckInReply.Yes, CheckInReply.SortOf)))
        assertFalse(TutorialReach.reached(outcomes, listOf(CheckInReply.Yes, CheckInReply.Lost)))
        assertTrue(TutorialReach.reached(outcomes, listOf(CheckInReply.Lost, CheckInReply.Yes)))
    }

    @Test
    fun aRequestToBeShownIsNotAFeeling() {
        assertEquals(CheckInReply.Yes, TutorialReach.feeling(listOf(CheckInReply.Yes, CheckInReply.ShowMe)))
        assertNull(TutorialReach.feeling(listOf(CheckInReply.ShowMe)))
    }

    @Test
    fun withNoAnswerOnlyWhatTheAppSawCounts() {
        assertTrue(TutorialReach.reached(listOf(met, next), emptyList()))
        assertFalse(TutorialReach.reached(listOf(next, next, met), emptyList()))
    }

    @Test
    fun aMissedOrUnvisitedStepHoldsItWhateverTheKeeperSaid() {
        assertFalse(TutorialReach.reached(listOf(met, StepOutcome.Missed), listOf(CheckInReply.Yes)))
        assertFalse(TutorialReach.reached(listOf(met, null), listOf(CheckInReply.Yes)))
        assertFalse(TutorialReach.reached(emptyList(), listOf(CheckInReply.Yes)))
    }

    @Test
    fun aSkippedStepIsTheKeeperSayingTheyKnowIt() {
        assertTrue(TutorialReach.reached(listOf(StepOutcome.Skipped, met), listOf(CheckInReply.Yes)))
    }
}
