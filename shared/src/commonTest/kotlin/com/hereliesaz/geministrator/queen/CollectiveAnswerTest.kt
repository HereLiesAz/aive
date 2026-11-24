package com.hereliesaz.geministrator.queen

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CollectiveAnswerTest {
    @Test
    fun assessmentsAreIndependentAndActualDisagreementRemainsInspectable() = runTest {
        val prompts = mutableListOf<String>()
        val first = CollectiveVoice("reviewer", QueenModel { prompts += it; "Use option X; uncertain about cost." })
        val second = CollectiveVoice("skeptic", QueenModel { prompts += it; "Reject option X; missing measurements." })
        val queen = CollectiveVoice("queen", QueenModel { prompts += it; "Measure first." })
        val admitted = mutableListOf<Int>()
        val result = answerCollectively("Which option?", "Prior discussion", listOf(first, second), queen) { _, count ->
            admitted += count
            true
        }
        assertEquals(listOf(0, 1, 2), admitted)
        assertEquals("Measure first.", result.answer)
        assertEquals(listOf("Use option X; uncertain about cost.", "Reject option X; missing measurements."), result.assessments.map { it.text })
        assertFalse("Use option X" in prompts[1])
        assertTrue("Use option X" in prompts[2] && "Reject option X" in prompts[2])
        assertTrue(prompts.all { "Which option?" in it && "Prior discussion" in it })
    }

    @Test
    fun captureReceivesOnlyActualCompletedRepliesEvenWhenLaterCallIsCancelled() = runTest {
        val recorded = mutableListOf<Pair<String, String>>()
        val reviewer = CollectiveVoice("reviewer", QueenModel { "Actual objection" })
        val cancelled = CollectiveVoice("skeptic", QueenModel { throw CancellationException("Cancelled") })
        val queen = CollectiveVoice("queen", QueenModel { error("Must not synthesize") })
        assertFailsWith<CancellationException> {
            answerCollectively("Question", "", listOf(reviewer, cancelled), queen,
                recordContribution = { voice, text -> recorded += voice.role to text }) { _, _ -> true }
        }
        assertEquals(listOf("reviewer" to "Actual objection"), recorded)
    }

    @Test
    fun deniedSynthesisDoesNotInventAnAnswer() = runTest {
        val reviewer = CollectiveVoice("reviewer", QueenModel { "One actual assessment" })
        val queen = CollectiveVoice("queen", QueenModel { error("Must not be called") })
        val result = answerCollectively("Question", "", listOf(reviewer), queen) { _, calls -> calls == 0 }
        assertNull(result.answer)
        assertEquals(listOf("queen"), result.unavailable)
        assertEquals("One actual assessment", result.assessments.single().text)
    }

    @Test
    fun noActualAssessmentMeansNoSynthesisAndCancellationPropagates() = runTest {
        val queen = CollectiveVoice("queen", QueenModel { error("Must not synthesize") })
        val absent = CollectiveVoice("reviewer", QueenModel { error("Unavailable") })
        val result = answerCollectively("Question", "", listOf(absent), queen) { _, _ -> true }
        assertNull(result.answer)
        assertTrue(result.assessments.isEmpty())
        assertEquals(listOf("reviewer"), result.unavailable)
        val cancelled = CollectiveVoice("reviewer", QueenModel { throw CancellationException("Cancelled") })
        assertFailsWith<CancellationException> {
            answerCollectively("Question", "", listOf(cancelled), queen) { _, _ -> true }
        }
    }
}
