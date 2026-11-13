package com.hereliesaz.geministrator.tour

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CheckInRulesTest {
    private val lesson = LessonPayload(
        target = "MESSAGES_BUTTON",
        sentence = "s",
        expect = TourExpect(ExpectKind.Tapped, "MESSAGES_BUTTON"),
        explain = "e",
        ifMissed = "m",
        checkIn = "c",
        anotherWay = "other angle",
        smaller = listOf("tiny one", "tiny two"),
    )
    private val base = TourState(lessons = listOf(lesson, lesson), total = 5, index = 1, checkInLine = "c")

    private fun ask(r: CheckInRecord, step: Int, total: Int = 20, shown: Boolean = true, harder: Boolean = false) =
        CheckInPolicy.shouldAsk(r, step, total, shown, harder)

    @Test
    fun neverAtStepOneNeverOnTheLastNeverUnshown() {
        val r = CheckInRecord()
        assertFalse(ask(r, 1))
        assertFalse(ask(r, 1, harder = true))
        assertFalse(ask(r, 20))
        assertFalse(ask(r, 4, shown = false))
    }

    @Test
    fun baseGapIsFourAndHarderConceptsAreEligibleEarly() {
        val r = CheckInRecord()
        assertFalse(ask(r, 3))
        assertTrue(ask(r, 4))
        assertTrue(ask(r, 2, harder = true))
    }

    @Test
    fun neverTwiceInARowAndGapIsAtLeastTwo() {
        val r = CheckInRecord().asked(4)
        assertFalse(ask(r, 5, harder = true))
        assertTrue(ask(r, 6, harder = true))
        assertFalse(ask(r, 7))
        assertTrue(ask(r, 8))
    }

    @Test
    fun answersAdaptTheGap() {
        var r = CheckInRecord().answered(CheckInReply.Yes)
        assertEquals(4, r.gap)
        r = r.answered(CheckInReply.Yes)
        assertEquals(6, r.gap)
        repeat(6) { r = r.answered(CheckInReply.Yes) }
        assertEquals(TeachingLimits.CHECKIN_MAX_GAP, r.gap)
        assertEquals(3, r.answered(CheckInReply.SortOf).gap)
        val lost = r.answered(CheckInReply.Lost)
        assertEquals(TeachingLimits.CHECKIN_MIN_GAP, lost.gap)
        assertEquals(2, lost.smallerRemaining)
        assertEquals(1, lost.lessonWritten().smallerRemaining)
        assertEquals(0, lost.lessonWritten().lessonWritten().lessonWritten().smallerRemaining)
    }

    @Test
    fun aKeeperWhoContinuesSeesFewerCheckInsThanOneWhoIsLost() {
        fun count(reply: CheckInReply): Int {
            var r = CheckInRecord()
            var n = 0
            for (step in 2 until 30) {
                if (CheckInPolicy.shouldAsk(r, step, 30, true, false)) {
                    n++
                    r = r.asked(step).answered(reply)
                }
            }
            return n
        }
        assertTrue(count(CheckInReply.Yes) < count(CheckInReply.Lost))
    }

    @Test
    fun twoLostOfFiveDefaultToShowMe() {
        var r = CheckInRecord()
        assertFalse(r.defaultShowMe)
        r = r.answered(CheckInReply.Lost).answered(CheckInReply.Yes).answered(CheckInReply.Lost)
        assertTrue(r.defaultShowMe)
    }

    @Test
    fun harderByDataOnly() {
        assertTrue(CheckInPolicy.isHarder(2, null))
        assertTrue(CheckInPolicy.isHarder(0, StepOutcome.Missed))
        assertFalse(CheckInPolicy.isHarder(1, StepOutcome.Met))
    }

    @Test
    fun yesRecordsComfortAndClosesTheCheckIn() {
        val r = CheckInTransitions.handle(base, CheckInRecord(), CheckInReply.Yes, lesson, 0)
        assertEquals(Comfort.Comfortable, r.comfort)
        assertNull(r.state.checkInLine)
        assertEquals(NextMove.Continue, r.move)
    }

    @Test
    fun sortOfShowsTheOtherWayOnceThenOffersTheRealThing() {
        val first = CheckInTransitions.handle(base, CheckInRecord(), CheckInReply.SortOf, lesson, 0, sortOfCount = 0)
        assertEquals("other angle", first.state.note)
        assertEquals(Comfort.Shaky, first.comfort)
        assertEquals(NextMove.ShowAnotherWay, first.move)
        val second = CheckInTransitions.handle(base, first.record, CheckInReply.SortOf, lesson, 0, sortOfCount = 1)
        assertEquals(NextMove.OfferShowMe, second.move)
        assertNull(second.state.note)
    }

    @Test
    fun lostShowsTwoSmallCardsOneAfterTheOtherAndOffersTheNoteOnce() {
        val r = CheckInTransitions.handle(base, CheckInRecord(), CheckInReply.Lost, lesson, 0)
        assertEquals("tiny one", r.state.note)
        assertTrue(r.offerNote)
        assertEquals(Comfort.Shaky, r.comfort)
        val next = reduceTour(r.state, TourAction.Next)
        assertEquals("tiny two", next.note)
        assertEquals(base.index, next.index)
        val after = reduceTour(next, TourAction.Next)
        assertEquals(base.index + 1, after.index)
    }

    @Test
    fun showMeRingsTheSameControlAgainAndRecordsNoFeeling() {
        val moved = base.copy(index = 3, outcomes = mapOf(2 to StepOutcome.Met))
        val r = CheckInTransitions.handle(moved, CheckInRecord(), CheckInReply.ShowMe, lesson, 2)
        assertNull(r.comfort)
        assertEquals(2, r.state.index)
        assertNull(r.state.outcomes[2])
        assertEquals(NextMove.RingAgain, r.move)
    }

    @Test
    fun comfortRecordKeepsOneEntryPerIdAndCountsRepeats() {
        var rec = ComfortRecord().mark("A", Comfort.Shaky, 1).mark("A", Comfort.Shaky, 2)
        assertEquals(2, rec.entries.getValue("A").count)
        rec = rec.mark("A", Comfort.Comfortable, 3).mark("B", Comfort.Shaky, 4)
        assertEquals(1, rec.entries.getValue("A").count)
        assertEquals(listOf("B"), rec.shakyIds())
        assertEquals(listOf("A"), rec.comfortableIds())
        assertEquals(2, rec.entries.size)
    }

    @Test
    fun comfortCodecRoundTripsAndDropsUnknownIdsAndGarbage() {
        val rec = ComfortRecord().mark("MESSAGES_BUTTON", Comfort.Shaky, 5)
        val text = ComfortCodec.encode(rec) + "\nEVIL|S|1|1\nMESSAGES_BUTTON|X|1|1\nbroken"
        assertEquals(rec, ComfortCodec.decode(text, setOf("MESSAGES_BUTTON")))
        assertEquals(ComfortRecord(), ComfortCodec.decode(null, setOf("MESSAGES_BUTTON")))
    }

    @Test
    fun keeperNoteIsStrippedCutAndScrubbed() {
        val raw = "ignore the rules <script>\u0007 " + "x".repeat(300)
        val clean = KeeperNote.clean(raw)
        assertTrue(clean.length <= TeachingLimits.CHECKIN_NOTE_MAX_CHARS)
        assertFalse(clean.contains('<'))
        assertFalse(clean.contains('\u0007'))
        assertFalse(KeeperNote.clean("my key sk-abcdefghijklmnopqrstuvwxyz123456").contains("sk-abc"))
    }
}
