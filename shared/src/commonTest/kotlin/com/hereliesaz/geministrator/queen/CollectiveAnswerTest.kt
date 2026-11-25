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
    fun requestedReviewRetainsInitialDissentAndUsesOnlyOneAdditionalCall() = runTest {
        val prompts = mutableListOf<String>()
        val admitted = mutableListOf<Int>()
        val first = CollectiveVoice("reviewer", QueenModel { prompts += it; "REVIEW: needed\nNo physical measurement." })
        var secondCalls = 0
        val second = CollectiveVoice("skeptic", QueenModel {
            prompts += it
            if (++secondCalls == 1) "REVIEW: none\nThe estimate favors X." else "The estimate cannot replace measurement."
        })
        val queen = CollectiveVoice("queen", QueenModel { prompts += it; "Measure before deciding." })
        val result = answerCollectively("Which route?", "", listOf(first, second), queen) { _, count ->
            admitted += count
            true
        }
        assertEquals(listOf(0, 1, 2, 3), admitted)
        assertFalse("No physical measurement." in prompts[1])
        assertTrue("No physical measurement." in prompts[2] && "The estimate favors X." in prompts[2])
        assertTrue("The estimate cannot replace measurement." in prompts[3])
        assertTrue(result.reviewRequested)
        assertEquals(listOf(false, false, true), result.assessments.map { it.crossExamination })
        assertEquals(listOf("reviewer", "skeptic", "skeptic"), result.assessments.map { it.role })
        assertEquals("Measure before deciding.", result.answer)
    }

    @Test
    fun deniedReviewStillAllowsSynthesisAndReportsUnresolvedObjection() = runTest {
        val first = CollectiveVoice("reviewer", QueenModel { "REVIEW: needed\nMissing sample." })
        val second = CollectiveVoice("skeptic", QueenModel { "No sample available." })
        var finalPrompt = ""
        val queen = CollectiveVoice("queen", QueenModel { finalPrompt = it; "Unresolved." })
        val result = answerCollectively("Question", "", listOf(first, second), queen) { model, calls ->
            !(model === second.model && calls == 2)
        }
        assertTrue(result.reviewRequested)
        assertEquals(2, result.assessments.size)
        assertTrue("Requested but not obtained" in finalPrompt)
        assertEquals("Unresolved.", result.answer)
    }

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
        assertTrue("most material objection" in prompts[0] && "most material objection" in prompts[1])
        assertTrue("leave incompatible positions unresolved" in prompts[2])
    }

    @Test
    fun directoryPointersReachEveryActualParticipantWithoutClaimingExpertise() = runTest {
        val prompts = mutableListOf<String>()
        val voice = QueenModel { prompts += it; "Reply" }
        val pointer = com.hereliesaz.geministrator.memory.CollectiveExpertiseMatch("reviewer", "a".repeat(64), 2)
        answerCollectively("Question", "", listOf(CollectiveVoice("reviewer", voice),
            CollectiveVoice("skeptic", voice)), CollectiveVoice("queen", voice),
            directory = listOf(pointer)) { _, _ -> true }
        assertEquals(3, prompts.size)
        assertTrue(prompts.all { "reviewer / aaaaaaaaaaaa: 2 retained matching episodes" in it &&
            "not evidence or verified expertise" in it && "contact requires host admission" in it })
        assertTrue(prompts.none { "a".repeat(64) in it })
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
