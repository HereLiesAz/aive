package com.hereliesaz.geministrator.intro

import com.hereliesaz.geministrator.queen.QueenModel
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
        tick: suspend () -> Unit,
    ) = TutorialDirector(planner, reviewers, w, rows, { "somewhere" }, surface, saveComfort = save, tick = tick)

    @Test
    fun aPlannedChainBecomesLessonsOneAheadOfTheKeeperAndEnds() = runTest {
        val surface = FakeSurface()
        val outcome = director(surface, QueenModel { planOf(3) }) {
            val st = surface.state!!
            // The keeper never gets more than two lessons ahead of what is written.
            assertTrue(st.lessons.size - st.index <= 2)
            if (st.current != null) surface.replace(reduceTour(st, TourAction.Next))
        }.run("goal", TutorialScope.Focused, null)
        assertEquals(TutorialOutcome.Ended(written = 3, dropped = 0, finished = true), outcome)
        assertEquals(listOf("R1", "R2", "R3"), surface.state!!.lessons.map { it.target })
    }

    @Test
    fun aCheckInIsAskedAtThePaceAndAnAnswerIsRecordedWithoutAModel() = runTest {
        val surface = FakeSurface()
        var saved = ComfortRecord()
        val outcome = director(surface, QueenModel { planOf(6) }, save = { saved = it }) {
            val st = surface.state!!
            if (st.checkInLine != null) surface.sink?.invoke(CheckInReply.Yes)
            else if (st.current != null) surface.replace(reduceTour(st, TourAction.Next))
        }.run("goal", TutorialScope.Focused, null)
        assertIs<TutorialOutcome.Ended>(outcome)
        assertEquals(listOf("Are you with me so far?"), surface.lines)
        assertEquals(listOf("R4"), saved.comfortableIds())
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
        val outcome = director(surface, planner, reviewers = listOf(strict)) {
            val st = surface.state!!
            if (st.current != null) surface.replace(reduceTour(st, TourAction.Next))
        }.run("goal", TutorialScope.Focused, null)
        assertEquals(2, rounds)
        assertEquals(TutorialOutcome.Ended(2, 0, true), outcome)
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
}
