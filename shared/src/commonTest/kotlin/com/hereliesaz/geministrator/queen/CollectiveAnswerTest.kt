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
    fun conversationContinuesPastFourCallsAndCanRecruitAdditionalPeers() = runTest {
        val admissions = mutableListOf<Int>()
        var firstCalls = 0
        val first = CollectiveVoice("a", QueenModel {
            when (++firstCalls) {
                1 -> "REVIEW: needed\nPosition A."
                2 -> "REVIEW: needed\nI want another perspective."
                else -> "REVIEW: none\nI now prefer B."
            }
        })
        var secondCalls = 0
        val second = CollectiveVoice("b", QueenModel {
            if (++secondCalls == 1) "REVIEW: none\nPosition B." else "REVIEW: none\nStill B."
        })
        val third = CollectiveVoice("c", QueenModel { "REVIEW: none\nI also favor B." })
        val queen = CollectiveVoice("queen", QueenModel { "Positions remain mixed." })
        val result = answerCollectively("Question", "", listOf(first, second, third), queen) { _, count ->
            admissions += count
            true
        }
        assertEquals(listOf(0, 1, 2, 3, 4, 5), admissions)
        assertEquals(listOf("a", "b", "a", "b", "c"), result.assessments.map { it.role })
        assertEquals(listOf(null, null, 2, 1, 3), result.assessments.map { it.respondsTo })
        assertEquals(DeliberationStop.NoFurtherReviewRequested, result.stop)
        assertEquals("Positions remain mixed.", result.answer)
    }

    @Test
    fun selfReportedPersuasionByAnExplicitlyIllogicalPositionIsRetainedWithoutAdjudication() = runTest {
        var aCalls = 0
        var bCalls = 0
        val prompts = mutableListOf<String>()
        val first = CollectiveVoice("a", QueenModel {
            prompts += it
            if (++aCalls == 1) "REVIEW: none\nI prefer X against all logic." else "REVIEW: none\nStill X."
        })
        val second = CollectiveVoice("b", QueenModel {
            prompts += it
            if (++bCalls == 1) "REVIEW: none\nI prefer Y." else "REVIEW: none\nPERSUADED: #1\nThe appeal persuaded me. I now prefer X."
        })
        var synthesis = ""
        val queen = CollectiveVoice("queen", QueenModel { synthesis = it; "Both now prefer X." })
        val result = answerCollectively("Question", "", listOf(first, second), queen) { _, _ -> true }
        assertEquals(listOf(null, null, null, 1), result.assessments.map { it.persuadedBy })
        assertTrue("I prefer X against all logic." in prompts[3])
        assertTrue("no logical, evidential, stylistic" in prompts[3])
        assertTrue("even if you would not agree" in synthesis)
        assertTrue("do not choose a winner" in synthesis)
        assertTrue("speaker reports persuaded by #1" in synthesis)
        assertEquals("Both now prefer X.", result.answer)
    }

    @Test
    fun aSpeakerCannotAttributePersuasionToTheirOwnOrNonexistentUtterance() = runTest {
        var aCalls = 0
        var bCalls = 0
        val first = CollectiveVoice("a", QueenModel {
            if (++aCalls == 1) "X" else "REVIEW: none\nPERSUADED: #1\nStill X."
        })
        val second = CollectiveVoice("b", QueenModel {
            if (++bCalls == 1) "Y" else "REVIEW: none\nPERSUADED: #99\nStill Y."
        })
        val result = answerCollectively("Question", "", listOf(first, second),
            CollectiveVoice("queen", QueenModel { "Different positions." })) { _, _ -> true }
        assertTrue(result.assessments.all { it.persuadedBy == null })
        assertEquals(4, result.assessments.size)
    }

    @Test
    fun repeatedAppealCanStillPersuadeAnotherParticipant() = runTest {
        var aCalls = 0
        var bCalls = 0
        val first = CollectiveVoice("a", QueenModel {
            when (++aCalls) {
                1, 2 -> "REVIEW: needed\nConsider X again."
                else -> "REVIEW: none\nI still favor X."
            }
        })
        val second = CollectiveVoice("b", QueenModel {
            when (++bCalls) {
                1 -> "REVIEW: none\nI favor Y."
                2 -> "REVIEW: none\nI remain unconvinced."
                else -> "REVIEW: none\nPERSUADED: #3\nI changed my mind to X."
            }
        })
        val result = answerCollectively("Question", "", listOf(first, second),
            CollectiveVoice("queen", QueenModel { "Both now favor X." })) { _, _ -> true }
        assertEquals(5, result.assessments.size)
        assertEquals(3, result.assessments.last().persuadedBy)
        assertEquals(DeliberationStop.NoFurtherReviewRequested, result.stop)
    }

    @Test
    fun keeperDeadlineStopsExplorationAndReservesSynthesis() = runTest {
        val first = CollectiveVoice("a", QueenModel { "REVIEW: needed\nMissing test." })
        val second = CollectiveVoice("b", QueenModel {
            kotlinx.coroutines.delay(1_000)
            "Too late"
        })
        val queen = CollectiveVoice("queen", QueenModel { "Partial answer." })
        val result = answerCollectively("Question", "", listOf(first, second), queen,
            timeBudgetMillis = 300L) { _, _ -> true }
        assertEquals(DeliberationStop.TimeBudget, result.stop)
        assertEquals(listOf(1), result.unexaminedRequests)
        assertEquals("Partial answer.", result.answer)
        assertEquals(1, result.assessments.size)
    }

    @Test
    fun requestedReviewRetainsInitialDissentAndStopsWhenNoFurtherReviewRequested() = runTest {
        val prompts = mutableListOf<String>()
        val admitted = mutableListOf<Int>()
        var firstCalls = 0
        val first = CollectiveVoice("reviewer", QueenModel {
            prompts += it
            if (++firstCalls == 1) "REVIEW: needed\nNo physical measurement." else "REVIEW: none\nStill missing measurement."
        })
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
        assertEquals(listOf(0, 1, 2, 3, 4), admitted)
        assertFalse("No physical measurement." in prompts[1])
        assertTrue("No physical measurement." in prompts[2] && "The estimate favors X." in prompts[2])
        assertTrue("The estimate cannot replace measurement." in prompts[4])
        assertTrue(result.reviewRequested)
        assertEquals(listOf(false, false, true, true), result.assessments.map { it.crossExamination })
        assertEquals(listOf("reviewer", "skeptic", "reviewer", "skeptic"), result.assessments.map { it.role })
        assertEquals("Measure before deciding.", result.answer)
    }

    @Test
    fun deniedReviewStillAllowsSynthesisAndReportsUnresolvedObjection() = runTest {
        var firstCalls = 0
        val first = CollectiveVoice("reviewer", QueenModel {
            if (++firstCalls == 1) "REVIEW: needed\nMissing sample." else "REVIEW: none\nStill missing sample."
        })
        val second = CollectiveVoice("skeptic", QueenModel { "No sample available." })
        var finalPrompt = ""
        val queen = CollectiveVoice("queen", QueenModel { finalPrompt = it; "Unresolved." })
        val result = answerCollectively("Question", "", listOf(first, second), queen) { model, calls ->
            !(model === second.model && calls >= 2)
        }
        assertTrue(result.reviewRequested)
        assertEquals(3, result.assessments.size)
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
        assertEquals(listOf(0, 1, 2, 3, 4), admitted)
        assertEquals("Measure first.", result.answer)
        assertEquals(listOf("Use option X; uncertain about cost.", "Reject option X; missing measurements.",
            "Use option X; uncertain about cost.", "Reject option X; missing measurements."), result.assessments.map { it.text })
        assertFalse("Use option X" in prompts[1])
        assertTrue("Use option X" in prompts[2] && "Reject option X" in prompts[2])
        assertTrue(prompts.all { "Which option?" in it && "Prior discussion" in it })
        assertTrue("relay their differences" in prompts[4])
        assertTrue("without resolving them yourself" in prompts[4])
    }

    @Test
    fun directoryPointersReachEveryActualParticipantWithoutClaimingExpertise() = runTest {
        val prompts = mutableListOf<String>()
        val voice = QueenModel { prompts += it; "Reply" }
        val pointer = com.hereliesaz.geministrator.memory.CollectiveExpertiseMatch("reviewer", "a".repeat(64), 2)
        answerCollectively("Question", "", listOf(CollectiveVoice("reviewer", voice),
            CollectiveVoice("skeptic", voice)), CollectiveVoice("queen", voice),
            directory = listOf(pointer)) { _, _ -> true }
        assertEquals(5, prompts.size)
        assertTrue(prompts.all { "reviewer / aaaaaaaaaaaa: 2 retained matching episodes" in it &&
            "not evidence or verified expertise" in it && "contact requires host admission" in it })
        assertTrue(prompts.none { "a".repeat(64) in it })
    }

    @Test
    fun linkedObservationReachesAffectedInstancesAndRecordsOnlyReturnedReplies() = runTest {
        val prompts = mutableMapOf<String, MutableList<String>>()
        fun voice(role: String, id: String) = CollectiveVoice(role, QueenModel {
            prompts.getOrPut(role) { mutableListOf() }.add(it)
            "REVIEW: none\nI have reconsidered."
        }, id)
        val a = voice("a", "a")
        val b = voice("b", "b")
        val c = voice("c", "c")
        val replacement = voice("old", "replacement")
        val denied = voice("denied", "denied")
        val queen = voice("queen", "queen")
        val observations = LinkedConversationObservations(
            "Observation e: local process exited 1", setOf("b", "c", "old-instance", "denied"), listOf("e"))
        val result = answerCollectively("Question", "", listOf(a, replacement, b, c, denied), queen,
            observations = observations, admitRole = { it.role != "denied" }) { _, _ -> true }
        assertEquals(setOf("b", "c"), result.assessments.map { it.role }.toSet())
        assertTrue(result.assessments.all { it.observationIds == listOf("e") })
        assertTrue(prompts.values.flatten().all { "local process exited 1" in it })
        assertEquals(listOf("denied", "old-instance"), result.observationRecipientsMissing)
        assertTrue("Do not infer their agreement" in prompts.getValue("queen").single())
    }

    @Test
    fun newObservationCanPersuadeParticipantsAwayFromTheirEarlierAgreement() = runTest {
        val history = "a: I favor X.\nb: I was persuaded by a and now favor X."
        var aCalls = 0
        var bCalls = 0
        val first = CollectiveVoice("a", QueenModel {
            assertTrue("Observed outcome: X failed." in it)
            if (++aCalls == 1) "REVIEW: none\nI no longer favor X after that observation; I favor Y."
            else "REVIEW: none\nStill Y."
        })
        val second = CollectiveVoice("b", QueenModel {
            if (++bCalls == 1) "REVIEW: none\nI still favor X." else
                "REVIEW: none\nPERSUADED: #1\nI accept the correction; now Y."
        })
        val result = answerCollectively("Observed outcome: X failed.", history, listOf(first, second),
            CollectiveVoice("queen", QueenModel { "Both now favor Y." })) { _, _ -> true }
        assertEquals(1, result.assessments.last().persuadedBy)
        assertEquals("Both now favor Y.", result.answer)
        assertTrue(result.assessments[1].text.contains("still favor X"))
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
