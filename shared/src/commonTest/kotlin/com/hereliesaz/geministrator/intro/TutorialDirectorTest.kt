package com.hereliesaz.geministrator.intro

import com.hereliesaz.geministrator.queen.QueenLevel
import com.hereliesaz.geministrator.queen.QueenModel
import com.hereliesaz.geministrator.queen.TutorialSay
import com.hereliesaz.geministrator.tour.TeachingLimits
import com.hereliesaz.geministrator.tour.CheckInReply
import com.hereliesaz.geministrator.tour.ComfortRecord
import com.hereliesaz.geministrator.tour.ExpectKind
import com.hereliesaz.geministrator.tour.LessonPayload
import com.hereliesaz.geministrator.tour.TargetRow
import com.hereliesaz.geministrator.tour.TourAction
import com.hereliesaz.geministrator.tour.TourState
import com.hereliesaz.geministrator.tour.reduceTour
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TutorialDirectorTest {
    /** Fixture registry only: names and order here exist for this test and are never shipped. */
    private val ids = listOf("R1", "R2", "R3", "R4", "R5", "R6")
    private val rows = ids.mapIndexed { i, id ->
        TargetRow(id, "Name$id", if (i == 0) emptySet() else setOf(ids[i - 1]), i == 0, setOf(ExpectKind.Tapped))
    }

    private class FakeSurface : TutorialSurface {
        override var state: TourState? = null
        var sink: ((CheckInReply) -> Unit)? = null
        val lines = mutableListOf<String>()
        override fun start(runKey: String?, total: Int) { state = TourState(total = total) }
        override fun addLesson(lesson: LessonPayload) { state = state?.let { reduceTour(it, TourAction.LessonArrived(lesson)) } }
        override fun setTotal(total: Int) { state = state?.let { reduceTour(it, TourAction.SetTotal(total)) } }
        override fun openCheckIn(line: String) { lines += line; state = state?.copy(checkInLine = line) }
        override fun replace(state: TourState) { this.state = state }
        override fun onReply(sink: ((CheckInReply) -> Unit)?) { this.sink = sink }
    }

    private fun planOf(n: Int) = ids.take(n).joinToString("\n") { "Learn $it. Target: $it. Checkpoint: tapped." }

    private fun lessonJson(id: String, first: Boolean): String {
        val explain = if (first) "A short summary only, never goals or keys; free Sources may log it." else "Short."
        return """{"target":"$id","sentence":"Do it.","expect":{"type":"tapped","target":"$id"},"explain":"$explain","ifMissed":"Again.","checkIn":"Are you with me so far?","anotherWay":"Other way.","smaller":["one","two"]}"""
    }

    private val writer = QueenModel { prompt ->
        val id = Regex("""\(id (R\d)\)""").find(prompt)!!.groupValues[1]
        lessonJson(id, first = "This is the first step" in prompt)
    }
    private val approve = TutorialVoice("Reviewer", QueenModel { "VERDICT: approve" })

    private fun director(
        surface: FakeSurface,
        planner: QueenModel,
        reviewers: List<TutorialVoice> = listOf(approve),
        w: QueenModel = writer,
        save: (ComfortRecord) -> Unit = {},
        clock: () -> Long = { 0L },
        level: () -> QueenLevel = { QueenLevel.Quiet },
        say: (TutorialSay) -> Unit = {},
        opening: List<LessonPayload> = emptyList(),
        tick: suspend () -> Unit,
    ) = TutorialDirector(planner, reviewers, w, rows, { "somewhere" }, surface, saveComfort = save, nowMillis = clock, level = level, say = say, opening = opening, tick = tick)

    /** The keeper's side of a quick walk: answer any open question with [answer], else go on to the next step. */
    private fun walk(surface: FakeSurface, answer: CheckInReply = CheckInReply.Yes) {
        val st = surface.state!!
        if (st.checkInLine != null) surface.sink?.invoke(answer)
        else if (st.current != null) surface.replace(reduceTour(st, TourAction.Next))
    }

    @Test
    fun aPlannedChainBecomesLessonsOneAheadOfTheKeeperAndEnds() = runTest {
        val surface = FakeSurface()
        val outcome = director(surface, QueenModel { planOf(3) }) {
            val st = surface.state!!
            // The keeper never gets more than two lessons ahead of what is written.
            assertTrue(st.lessons.size - st.index <= 2)
            walk(surface)
        }.run("goal", TutorialScope.Focused, null)
        assertEquals(TutorialOutcome.Ended(written = 3, dropped = 0, finished = true, reached = true), outcome)
        assertEquals(listOf("R1", "R2", "R3"), surface.state!!.lessons.map { it.target })
    }

    private fun openingLesson(id: String) = LessonPayload(
        target = id,
        sentence = "Open $id.",
        expect = com.hereliesaz.geministrator.tour.TourExpect(ExpectKind.Tapped, id),
        explain = "Short.",
        ifMissed = "Again.",
        checkIn = "Are you with me so far?",
        anotherWay = "Other way.",
        smaller = listOf("one", "two"),
    )

    @Test
    fun theOpeningPlaysAtOnceAndThePlannedChaptersFollowIt() = runTest {
        val surface = FakeSurface()
        val outcome = director(surface, QueenModel { planOf(2) }, opening = listOf(openingLesson("R1"), openingLesson("R2"))) {
            walk(surface)
        }.run("goal", TutorialScope.Focused, null)
        // The two fixed chapters come first, then the two the swarm planned; the tour is one run of four.
        assertEquals(listOf("R1", "R2", "R1", "R2"), surface.state!!.lessons.map { it.target })
        assertEquals(4, surface.state!!.stepTotal)
        assertIs<TutorialOutcome.Ended>(outcome)
        assertEquals(true, outcome.finished)
    }

    @Test
    fun aPlanThatFailsLeavesTheOpeningStanding() = runTest {
        val surface = FakeSurface()
        val outcome = director(surface, QueenModel { "no steps here" }, opening = listOf(openingLesson("R1"))) { }.run("goal", TutorialScope.Broad, null)
        assertIs<TutorialOutcome.PlanFailed>(outcome)
        assertEquals(listOf("R1"), surface.state!!.lessons.map { it.target })
    }

    @Test
    fun aCheckInIsAskedAtThePaceAndAnAnswerIsRecordedWithoutAModel() = runTest {
        val surface = FakeSurface()
        var saved = ComfortRecord()
        val outcome = director(surface, QueenModel { planOf(6) }, save = { saved = it }) { walk(surface) }.run("goal", TutorialScope.Focused, null)
        assertIs<TutorialOutcome.Ended>(outcome)
        // One at the pace after step 4, and the closing one at the end.
        assertEquals(listOf("Are you with me so far?", "Are you with me so far?"), surface.lines)
        assertEquals(listOf("R4", "R6"), saved.comfortableIds())
    }

    @Test
    fun aPlanThatNeverPassesTheRulesGivesNoLessonsAndNoFallback() = runTest {
        val surface = FakeSurface()
        val outcome = director(surface, QueenModel { "no steps here" }) { }.run("goal", TutorialScope.Broad, null)
        assertIs<TutorialOutcome.PlanFailed>(outcome)
        assertNull(surface.state)
    }

    @Test
    fun aReviewerTurnsThePlanBackAndTheRevisionIsUsed() = runTest {
        val surface = FakeSurface()
        var rounds = 0
        val planner = QueenModel { prompt ->
            rounds++
            if ("Reviewers turned your last plan back" in prompt) planOf(2) else planOf(3)
        }
        var reviews = 0
        val strict = TutorialVoice("Antagonist", QueenModel { reviews++; if (reviews == 1) "VERDICT: revise\n- step 3 is padding" else "VERDICT: approve" })
        val outcome = director(surface, planner, reviewers = listOf(strict)) { walk(surface) }.run("goal", TutorialScope.Focused, null)
        assertEquals(2, rounds)
        assertEquals(TutorialOutcome.Ended(2, 0, true, reached = true), outcome)
    }

    @Test
    fun aLessonThatCannotBeMadeValidIsDroppedNotInvented() = runTest {
        val surface = FakeSurface()
        val outcome = director(surface, QueenModel { planOf(2) }, w = QueenModel { "not json" }) { }.run("goal", TutorialScope.Focused, null)
        assertEquals(TutorialOutcome.Ended(written = 0, dropped = 2, finished = false), outcome)
        assertEquals(emptyList(), surface.state!!.lessons)
    }

    @Test
    fun aSourceFailingKeepsWhatWasWritten() = runTest {
        val surface = FakeSurface()
        var calls = 0
        val flaky = QueenModel { prompt -> if (++calls > 1) error("down") else writer.answer(prompt) }
        val outcome = director(surface, QueenModel { planOf(3) }, w = flaky) {
            val st = surface.state!!
            if (st.current != null) surface.replace(reduceTour(st, TourAction.Next))
        }.run("goal", TutorialScope.Focused, null)
        assertEquals(1, (outcome as TutorialOutcome.SourceFailed).written)
        assertEquals(1, surface.state!!.lessons.size)
    }

    @Test
    fun hidingTheLayerStopsTheWriting() = runTest {
        val surface = FakeSurface()
        val outcome = director(surface, QueenModel { planOf(3) }) {
            surface.replace(reduceTour(surface.state!!, TourAction.Escape))
        }.run("goal", TutorialScope.Focused, null)
        assertIs<TutorialOutcome.Ended>(outcome)
        assertTrue((outcome as TutorialOutcome.Ended).written < 3)
    }

    @Test
    fun theClosingQuestionIsTheKeepersAnswerAndNothingElseDecidesReached() = runTest {
        val surface = FakeSurface()
        val said = mutableListOf<TutorialSay>()
        val outcome = director(surface, QueenModel { planOf(2) }, say = { said += it }) { walk(surface, CheckInReply.SortOf) }
            .run("goal", TutorialScope.Focused, null)
        assertEquals(TutorialOutcome.Ended(2, 0, true, reached = false), outcome)
        assertEquals(1, surface.lines.size)
        assertEquals(listOf<TutorialSay>(TutorialSay.Finished(reached = false)), said)
    }

    @Test
    fun aYesAtTheEndReachesAndTheQueenSaysTheHiveIsTheirs() = runTest {
        val surface = FakeSurface()
        val said = mutableListOf<TutorialSay>()
        val outcome = director(surface, QueenModel { planOf(2) }, say = { said += it }) { walk(surface) }.run("goal", TutorialScope.Focused, null)
        assertEquals(TutorialOutcome.Ended(2, 0, true, reached = true), outcome)
        assertEquals(listOf<TutorialSay>(TutorialSay.Finished(reached = true)), said)
    }

    @Test
    fun anUnansweredClosingQuestionEndsQuietlyAndOnlyWhatTheAppSawCounts() = runTest {
        val surface = FakeSurface()
        var clock = 0L
        val outcome = director(surface, QueenModel { planOf(2) }, clock = { clock }) {
            val st = surface.state!!
            if (st.checkInLine != null) clock += TeachingLimits.CHECKIN_QUIET_TIMEOUT_MILLIS
            else if (st.current != null) surface.replace(reduceTour(st, TourAction.Next))
        }.run("goal", TutorialScope.Focused, null)
        // Both steps were only moved past, never done on the real control, so nothing says the keeper reached it.
        assertEquals(TutorialOutcome.Ended(2, 0, true, reached = false), outcome)
        assertNull(surface.state!!.checkInLine)
    }

    @Test
    fun aLostAnswerIsSaidToTheKeeperAndALaterYesStillReaches() = runTest {
        val surface = FakeSurface()
        val said = mutableListOf<TutorialSay>()
        var asked = 0
        val outcome = director(surface, QueenModel { planOf(6) }, say = { said += it }) {
            val st = surface.state!!
            if (st.checkInLine != null) surface.sink?.invoke(if (asked++ == 0) CheckInReply.Lost else CheckInReply.Yes)
            else if (st.current != null) surface.replace(reduceTour(st, TourAction.Next))
        }.run("goal", TutorialScope.Focused, null)
        assertTrue(TutorialSay.Slower in said)
        assertEquals(TutorialSay.Finished(reached = true), said.last())
        assertEquals(true, (outcome as TutorialOutcome.Ended).reached)
    }

    @Test
    fun goingBackTwiceAsksAboutTheStepTheKeeperIsOn() = runTest {
        val surface = FakeSurface()
        var n = 0
        var askedAt: Int? = null
        director(surface, QueenModel { planOf(3) }) {
            val st = surface.state!!
            n++
            when {
                st.checkInLine != null -> {
                    if (askedAt == null) askedAt = st.index
                    surface.sink?.invoke(CheckInReply.Yes)
                }
                n == 1 || n == 3 -> surface.replace(reduceTour(st, TourAction.Next))
                n == 2 || n == 4 -> surface.replace(reduceTour(st, TourAction.Back))
                st.current != null -> surface.replace(reduceTour(st, TourAction.Next))
            }
        }.run("goal", TutorialScope.Focused, null)
        assertEquals(0, askedAt)
        // The question about the step they were stuck on, then the closing one.
        assertEquals(2, surface.lines.size)
    }

    @Test
    fun aLongDwellOnOneStepAsksWithoutWaitingForThePace() = runTest {
        val surface = FakeSurface()
        var clock = 0L
        var askedAt: Int? = null
        director(surface, QueenModel { planOf(3) }, clock = { clock }) {
            val st = surface.state!!
            if (st.checkInLine != null) {
                if (askedAt == null) askedAt = st.index
                surface.sink?.invoke(CheckInReply.Yes)
            } else if (st.index == 0 && clock == 0L) clock += AttentionLimits.LONG_DWELL_MILLIS
            else if (st.current != null) surface.replace(reduceTour(st, TourAction.Next))
        }.run("goal", TutorialScope.Focused, null)
        assertEquals(0, askedAt)
    }

    @Test
    fun theQueenUpdatesAtHelpfulAndStaysQuietAtQuiet() = runTest {
        suspend fun updates(level: QueenLevel): List<TutorialSay> {
            val said = mutableListOf<TutorialSay>()
            val surface = FakeSurface()
            director(surface, QueenModel { planOf(6) }, level = { level }, say = { said += it }) { walk(surface) }
                .run("goal", TutorialScope.Focused, null)
            return said.filterIsInstance<TutorialSay.Progress>()
        }
        assertEquals(listOf(TutorialSay.Progress(3, 6)), updates(QueenLevel.Helpful))
        assertTrue(updates(QueenLevel.Quiet).isEmpty())
    }

    @Test
    fun noPlannedStepCostsAModelCallBeyondWritingItsLesson() = runTest {
        // The writer is called once per lesson (here 3) and never for a question, an update or the closing line: those are strings and signals.
        val surface = FakeSurface()
        var writes = 0
        val counting = QueenModel { prompt ->
            writes++
            writer.answer(prompt)
        }
        director(surface, QueenModel { planOf(3) }, w = counting, level = { QueenLevel.Helpful }) { walk(surface) }.run("goal", TutorialScope.Focused, null)
        assertEquals(3, writes)
    }
}
