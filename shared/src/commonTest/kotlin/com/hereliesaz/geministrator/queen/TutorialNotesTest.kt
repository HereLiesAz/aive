package com.hereliesaz.geministrator.queen

import com.hereliesaz.geministrator.domain.TaskRunStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the queen says in Messages while she follows the keeper through the tutorial: strings with counts, never model text. */
class TutorialNotesTest {
    private val definition = qDefinition(qTask("a"), qTask("b"))
    private val running = qRun(qTaskRun("a", TaskRunStatus.Completed), qTaskRun("b", TaskRunStatus.Running))

    @Test
    fun progressIsOneLivingLine() {
        var notes = tutorialNotesAfter(emptyList(), "run", TutorialSay.Progress(1, 5))
        notes = tutorialNotesAfter(notes, "run", TutorialSay.Progress(3, 5))
        assertEquals(1, notes.size)
        assertEquals(listOf("3", "5"), notes.single().args)
        assertEquals(QueenVoice.TutorialProgress, notes.single().voice)
    }

    @Test
    fun theClosingLineTakesTheProgressLinesPlace() {
        var notes = tutorialNotesAfter(emptyList(), "run", TutorialSay.Progress(2, 5))
        notes = tutorialNotesAfter(notes, "run", TutorialSay.Finished(reached = true))
        assertEquals(listOf(QueenVoice.TutorialHiveYours), notes.map { it.voice })
    }

    @Test
    fun reachedAndShakyAreDifferentWordsAndNeitherIsAHandOff() {
        val yes = tutorialNotesAfter(emptyList(), "run", TutorialSay.Finished(true)).single()
        val shaky = tutorialNotesAfter(emptyList(), "run", TutorialSay.Finished(false)).single()
        assertEquals(QueenVoice.TutorialHiveYoursShaky, shaky.voice)
        assertTrue(yes.voice != shaky.voice)
        // The closing line says the hive is theirs and leads to the Story of that same hive: no other hive is started or offered.
        assertEquals(QueenLink.Story, yes.link)
        assertEquals(QueenLink.Story, shaky.link)
    }

    @Test
    fun sayingSlowerTwiceStaysOneLine() {
        var notes = tutorialNotesAfter(emptyList(), "run", TutorialSay.Slower)
        notes = tutorialNotesAfter(notes, "run", TutorialSay.Slower)
        assertEquals(1, notes.size)
    }

    @Test
    fun quietKeepsToTheSlowerAndClosingLinesHelpfulAddsProgress() {
        val notes = listOf(TutorialSay.Progress(1, 4), TutorialSay.Slower).fold(emptyList<QueenNote>()) { acc, say -> tutorialNotesAfter(acc, "run", say) }
        assertEquals(listOf(QueenVoice.TutorialSlower), tutorialNotesFor(notes, QueenLevel.Quiet).map { it.voice })
        assertEquals(listOf(QueenVoice.TutorialProgress, QueenVoice.TutorialSlower), tutorialNotesFor(notes, QueenLevel.Helpful).map { it.voice })
    }

    @Test
    fun theLinesReachMessagesThroughTheQueensUpdate() {
        val notes = tutorialNotesAfter(emptyList(), "run", TutorialSay.Slower)
        val voices = queenUpdate(definition, running, emptyList(), QueenLevel.Quiet, tutorial = notes).map { it.voice }
        assertTrue(QueenVoice.TutorialSlower in voices)
        assertTrue(QueenVoice.TutorialSlower !in queenUpdate(definition, running, emptyList(), QueenLevel.Quiet).map { it.voice })
    }

    @Test
    fun atMostFourLinesAreKept() {
        var notes = emptyList<QueenNote>()
        listOf(TutorialSay.Progress(1, 9), TutorialSay.Slower, TutorialSay.Finished(false)).forEach { notes = tutorialNotesAfter(notes, "run", it) }
        assertTrue(notes.size <= 4)
    }
}
