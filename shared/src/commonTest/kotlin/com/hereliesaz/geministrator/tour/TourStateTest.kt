package com.hereliesaz.geministrator.tour

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TourStateTest {
    private fun lesson(target: String = "MESSAGES_BUTTON", kind: ExpectKind = ExpectKind.Tapped) = LessonPayload(
        target = target,
        sentence = "s",
        expect = TourExpect(kind, target),
        explain = "e",
        ifMissed = "m",
        checkIn = "c",
        anotherWay = "a",
        smaller = listOf("x", "y"),
    )

    private fun state(vararg lessons: LessonPayload, total: Int = lessons.size) =
        lessons.fold(TourState(total = total)) { s, l -> reduceTour(s, TourAction.LessonArrived(l)) }

    @Test
    fun progressIsHonestBeforeAllLessonsExist() {
        val s = state(lesson(), total = 14)
        assertEquals(1, s.stepNumber)
        assertEquals(14, s.stepTotal)
        assertFalse(s.finished)
    }

    @Test
    fun theExpectedTapAdvancesAndIsRecordedAsMet() {
        val s = reduceTour(state(lesson(), lesson("KEEPERS_ROOM_GEAR")), TourAction.Observed(TourEvent.Tapped(TourTargetId.MESSAGES_BUTTON)))
        assertEquals(1, s.index)
        assertEquals(StepOutcome.Met, s.outcomes[0])
    }

    @Test
    fun aTapOnAnotherControlOrAnotherKindDoesNothing() {
        val s = state(lesson(), lesson("KEEPERS_ROOM_GEAR"))
        assertEquals(s, reduceTour(s, TourAction.Observed(TourEvent.Tapped(TourTargetId.KEEPERS_ROOM_GEAR))))
        assertEquals(s, reduceTour(s, TourAction.Observed(TourEvent.Opened(TourTargetId.MESSAGES_BUTTON))))
    }

    @Test
    fun anEventWhilePausedOrDismissedDoesNotAdvance() {
        val tap = TourAction.Observed(TourEvent.Tapped(TourTargetId.MESSAGES_BUTTON))
        val paused = reduceTour(state(lesson()), TourAction.Pause)
        assertEquals(0, reduceTour(paused, tap).index)
        val dismissed = reduceTour(state(lesson()), TourAction.Escape)
        assertEquals(0, reduceTour(dismissed, tap).index)
    }

    @Test
    fun iKnowThisSkipsAndNextAdvancesWithoutTheEvent() {
        val s = state(lesson(), lesson(), lesson())
        val skipped = reduceTour(s, TourAction.KnowThis)
        assertEquals(StepOutcome.Skipped, skipped.outcomes[0])
        val nexted = reduceTour(skipped, TourAction.Next)
        assertEquals(StepOutcome.Next, nexted.outcomes[1])
        assertEquals(2, nexted.index)
    }

    @Test
    fun nothingAdvancesWhileTheNextLessonIsNotWrittenYet() {
        val s = state(lesson(), total = 3)
        val onLast = reduceTour(s, TourAction.Next)
        assertNull(onLast.current)
        assertEquals(onLast, reduceTour(onLast, TourAction.Next))
        assertEquals(onLast, reduceTour(onLast, TourAction.KnowThis))
    }

    @Test
    fun backReturnsToThePreviousStepAndNotBelowZero() {
        val s = reduceTour(state(lesson(), lesson()), TourAction.Next)
        assertEquals(0, reduceTour(s, TourAction.Back).index)
        assertEquals(0, reduceTour(reduceTour(s, TourAction.Back), TourAction.Back).index)
    }

    @Test
    fun pauseResumeAndEscapeAreResumable() {
        val s = state(lesson())
        val paused = reduceTour(s, TourAction.Pause)
        assertEquals(TourPhase.Paused, paused.phase)
        assertEquals(TourPhase.Showing, reduceTour(paused, TourAction.Resume).phase)
        val dismissed = reduceTour(s, TourAction.Escape)
        assertEquals(TourPhase.Dismissed, dismissed.phase)
        assertEquals(TourPhase.Showing, reduceTour(dismissed, TourAction.Resume).phase)
        assertEquals(s, reduceTour(s, TourAction.Resume))
    }

    @Test
    fun aMissedCheckpointShowsTheIfMissedLineOnceAndKeepsSkipAvailable() {
        val s = state(lesson(), lesson())
        val missed = reduceTour(s, TourAction.WaitElapsed)
        assertTrue(missed.missedShown)
        assertEquals(StepOutcome.Missed, missed.outcomes[0])
        assertEquals(missed, reduceTour(missed, TourAction.WaitElapsed))
        // The keeper can still do it, which overwrites the outcome, or skip.
        val met = reduceTour(missed, TourAction.Observed(TourEvent.Tapped(TourTargetId.MESSAGES_BUTTON)))
        assertEquals(StepOutcome.Met, met.outcomes[0])
        assertFalse(met.missedShown)
    }

    @Test
    fun theLastStepFinishesTheTour() {
        val s = reduceTour(state(lesson()), TourAction.Next)
        assertTrue(s.finished)
    }

    @Test
    fun progressCodecRoundTripsAndRejectsGarbage() {
        val s = reduceTour(reduceTour(state(lesson(), lesson(), lesson()), TourAction.Next), TourAction.KnowThis)
        val decoded = TourProgressCodec.decode(TourProgressCodec.encode(s))
        assertEquals(2, decoded?.first)
        assertEquals(mapOf(0 to StepOutcome.Next, 1 to StepOutcome.Skipped), decoded?.second)
        assertNull(TourProgressCodec.decode(null))
        assertNull(TourProgressCodec.decode("abc"))
        assertNull(TourProgressCodec.decode("2;M"))
        assertNull(TourProgressCodec.decode("1;Q"))
        assertNull(TourProgressCodec.decode("99;" + "M".repeat(99)))
    }

    @Test
    fun plainKeepsMarkupLiteralButStripsControlCharactersAndCaps() {
        assertEquals("<b>hi</b> [x](y)", plain("<b>hi</b>\u0007 [x](y)"))
        assertEquals(10, plain("a".repeat(50), maxChars = 10).length)
    }
}
