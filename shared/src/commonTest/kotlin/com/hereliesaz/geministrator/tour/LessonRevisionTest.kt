package com.hereliesaz.geministrator.tour

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The queen revises lessons not yet played, from feelings and events: pure decisions, and the reducer that applies the marks. */
class LessonRevisionTest {
    private fun lesson(target: String, kind: ExpectKind = ExpectKind.Tapped) = LessonPayload(
        target = target,
        sentence = "s",
        expect = TourExpect(kind, target),
        explain = "e",
        ifMissed = "m",
        checkIn = "c",
        anotherWay = "a",
        smaller = listOf("small one", "small two"),
        stepId = "step-$target",
    )

    private fun arrived(index: Int, target: String, skippable: Boolean = true, revised: Boolean = false) =
        RemainingLesson(stepId = "step-$target", index = index, target = target, skippable = skippable, revised = revised)

    private val stalled = RemainingLesson(stepId = "late", stalled = true)

    private fun plan(
        replies: List<CheckInReply> = emptyList(),
        outcomes: Map<Int, StepOutcome> = emptyMap(),
        remaining: List<RemainingLesson> = listOf(arrived(2, "RAIL_HIVE")),
        comfortable: Set<String> = emptySet(),
        used: Int = 0,
    ) = LessonRevision.plan(replies, outcomes, remaining, comfortable, used)

    @Test
    fun withNothingToChangeShePlanKeepsThePlan() {
        assertEquals(LessonRevisionDecision.Keep, plan())
        assertEquals(LessonRevisionDecision.Keep, plan(listOf(CheckInReply.Yes), remaining = emptyList()))
    }

    @Test
    fun lostSlowsTheLessonsStillToCome() {
        assertEquals(LessonRevisionDecision.Smaller, plan(listOf(CheckInReply.Yes, CheckInReply.Lost)))
    }

    @Test
    fun twoUnsureOfThreeSlowsDownToo() {
        assertEquals(LessonRevisionDecision.Smaller, plan(listOf(CheckInReply.SortOf, CheckInReply.Yes, CheckInReply.SortOf)))
        assertEquals(LessonRevisionDecision.Keep, plan(listOf(CheckInReply.SortOf, CheckInReply.Yes, CheckInReply.Yes)))
    }

    @Test
    fun yesPassesOverTheTargetsTheKeeperIsComfortableWith() {
        val remaining = listOf(arrived(2, "RAIL_HIVE"), arrived(3, "MESSAGES_BUTTON"), arrived(4, "GOAL_FIELD", skippable = false))
        val decision = plan(listOf(CheckInReply.Yes), remaining = remaining, comfortable = setOf("MESSAGES_BUTTON", "GOAL_FIELD"))
        assertEquals(LessonRevisionDecision.SkipKnown(setOf("MESSAGES_BUTTON")), decision)
        assertEquals(LessonRevisionDecision.Keep, plan(listOf(CheckInReply.Yes), remaining = remaining, comfortable = setOf("OPTIONS_PILL")))
    }

    @Test
    fun aKeeperWhoIsLostIsNeverSkippedPast() {
        val remaining = listOf(arrived(2, "MESSAGES_BUTTON"))
        assertEquals(LessonRevisionDecision.Smaller, plan(listOf(CheckInReply.Lost), remaining = remaining, comfortable = setOf("MESSAGES_BUTTON")))
    }

    @Test
    fun aStoppedStepIsRebuiltWhileTheKeeperStruggles() {
        val remaining = listOf(arrived(2, "RAIL_HIVE"), stalled)
        assertEquals(LessonRevisionDecision.Redo("late", RedoReason.KeeperLost), plan(listOf(CheckInReply.Lost), remaining = remaining))
        assertEquals(
            LessonRevisionDecision.Redo("late", RedoReason.HiveStalled),
            plan(listOf(CheckInReply.Yes), outcomes = mapOf(0 to StepOutcome.Met, 1 to StepOutcome.Missed), remaining = remaining),
        )
    }

    @Test
    fun aStoppedStepWaitsWhenTheKeeperIsWell() {
        assertEquals(
            LessonRevisionDecision.Keep,
            plan(listOf(CheckInReply.Yes), outcomes = mapOf(0 to StepOutcome.Met), remaining = listOf(stalled)),
        )
    }

    @Test
    fun aLessonIsRevisedAtMostOnce() {
        assertEquals(LessonRevisionDecision.Keep, plan(listOf(CheckInReply.Lost), remaining = listOf(arrived(2, "RAIL_HIVE", revised = true))))
        assertEquals(LessonRevisionDecision.Keep, plan(listOf(CheckInReply.Lost), remaining = listOf(stalled.copy(revised = true))))
    }

    @Test
    fun theCountOfRevisionsIsBoundedPerRun() {
        val lost = listOf(CheckInReply.Lost)
        assertIs<LessonRevisionDecision.Smaller>(plan(lost, used = LessonRevision.MAX_PER_RUN - 1))
        assertEquals(LessonRevisionDecision.Keep, plan(lost, used = LessonRevision.MAX_PER_RUN))
    }

    @Test
    fun theReasonIsAClosedSentenceWithNothingFromTheKeeperOrTheRun() {
        assertTrue(RedoReason.entries.all { it.note.isNotBlank() })
    }

    @Test
    fun remainingListsOnlyLessonsAfterTheCurrentOneAndStoppedSteps() {
        val state = TourState(
            lessons = listOf(lesson("MESSAGES_BUTTON"), lesson("RAIL_HIVE"), lesson("HIVE_CREATURES", ExpectKind.RunHatched), lesson("GOAL_FIELD")),
            total = 4,
            index = 1,
        )
        val remaining = LessonRevision.remainingOf(state, stalledSteps = setOf("late"), revisedSteps = emptySet())
        assertEquals(listOf(2, 3, null), remaining.map { it.index })
        // A lesson that waits on the hive, and the last lesson, are never passed over.
        assertEquals(listOf(false, false, false), remaining.map { it.skippable })
        val roomy = state.copy(lessons = state.lessons + lesson("OPTIONS_PILL"), total = 5)
        assertTrue(LessonRevision.remainingOf(roomy, emptySet(), emptySet()).first { it.index == 3 }.skippable)
    }

    @Test
    fun smallerOpensTheMarkedLessonOnItsSmallSteps() {
        val state = TourState(lessons = listOf(lesson("A1"), lesson("A2"), lesson("A3")), total = 3)
        val remaining = LessonRevision.remainingOf(state, emptySet(), emptySet())
        val marked = reduceTour(state, LessonRevision.actionFor(LessonRevisionDecision.Smaller, remaining)!!)
        assertEquals(setOf(1, 2), marked.slower)
        assertEquals(setOf(1, 2), marked.revised)
        val moved = reduceTour(marked, TourAction.Next)
        assertEquals(1, moved.index)
        assertEquals("small one", moved.note)
        assertEquals(listOf("small two"), moved.smallerQueue)
        // The lesson already shown was not touched.
        assertNull(marked.note)
    }

    @Test
    fun skipKnownPassesOverTheLessonAndRecordsItSkipped() {
        val state = TourState(lessons = listOf(lesson("A1"), lesson("A2"), lesson("A3")), total = 4)
        val remaining = LessonRevision.remainingOf(state, emptySet(), emptySet())
        val action = LessonRevision.actionFor(LessonRevisionDecision.SkipKnown(setOf("A2")), remaining)!!
        val marked = reduceTour(state, action)
        val moved = reduceTour(marked, TourAction.Next)
        assertEquals(2, moved.index)
        assertEquals(StepOutcome.Skipped, moved.outcomes[1])
        assertEquals(StepOutcome.Next, moved.outcomes[0])
    }

    @Test
    fun aRevisionNeverReachesAPlayedOrTheCurrentLessonOrOneAlreadyRevised() {
        val state = TourState(lessons = listOf(lesson("A1"), lesson("A2"), lesson("A3")), total = 3, index = 1, revised = setOf(2))
        val after = reduceTour(state, TourAction.Revise(known = setOf(0, 1, 2), slower = setOf(0, 1, 2, 9)))
        assertEquals(state.known, after.known)
        assertEquals(state.slower, after.slower)
        assertEquals(state.revised, after.revised)
    }

    @Test
    fun repliesAreRecordedOldestFirstAndBounded() {
        var state = TourState(total = 3)
        repeat(10) { state = reduceTour(state, TourAction.Replied(if (it == 9) CheckInReply.Lost else CheckInReply.Yes)) }
        assertEquals(8, state.replies.size)
        assertEquals(CheckInReply.Lost, state.replies.last())
    }

    @Test
    fun actionForOtherDecisionsIsNone() {
        assertNull(LessonRevision.actionFor(LessonRevisionDecision.Keep, emptyList()))
        assertNull(LessonRevision.actionFor(LessonRevisionDecision.Redo("x", RedoReason.KeeperLost), emptyList()))
    }
}
