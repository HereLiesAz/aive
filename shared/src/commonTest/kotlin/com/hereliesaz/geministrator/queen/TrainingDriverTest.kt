package com.hereliesaz.geministrator.queen

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrainingDriverTest {
    private val limits = TrainingLimits(rounds = 3, tasks = 3, spendUsd = 1.0)
    private val won = PracticeResults(PracticeScore(1, 3), PracticeScore(3, 3), 0.1, false)
    private val nannyPass = QueenModel { prompt ->
        if ("Reply with exactly two lines" in prompt) "RULING: PASS\nREASON: The specialist did better. [f2]"
        else "ANSWER: UPHOLD\nREASON: Still the better result. [f2]"
    }

    @Test
    fun noModelsMeansTheRulesRuleAndNobodyChallenges() = runTest {
        val exchange = exchangeOnRuling(won, listOf("x"), 1, limits, null, null)
        val ruled = assertIs<TrainingRuling.Ruled>(exchange.ruling)
        assertEquals(TrainingCall.Pass, ruled.ruling.call)
        assertFalse(ruled.ruling.byModel)
        assertIs<RulingOutcome.Stands>(exchange.outcome)
    }

    @Test
    fun noReadableResultsGoToTheKeeper() = runTest {
        val exchange = exchangeOnRuling(null, emptyList(), 1, limits, nannyPass, null)
        assertIs<TrainingRuling.NeedsKeeper>(exchange.ruling)
        assertNull(exchange.outcome)
        assertIs<TrainingNext.AskKeeper>(nextForExchange(exchange, 1, limits, emptyList()))
    }

    @Test
    fun anAntagonistWithNothingCitedLetsTheRulingStand() = runTest {
        val antagonist = QueenModel { "CHALLENGE: NONE" }
        val exchange = exchangeOnRuling(won, emptyList(), 1, limits, nannyPass, antagonist)
        assertTrue((exchange.ruling as TrainingRuling.Ruled).ruling.byModel)
        assertIs<RulingOutcome.Stands>(exchange.outcome)
        assertIs<TrainingNext.OfferToKeeper>(nextForExchange(exchange, 1, limits, emptyList()))
    }

    @Test
    fun aChallengeTheNannyAnswersAndTheAntagonistAcceptsStands() = runTest {
        val antagonist = QueenModel { prompt ->
            if ("SATISFIED" in prompt) "SATISFIED: YES" else "CHALLENGE: Only three tasks.\nCITE: f2"
        }
        val exchange = exchangeOnRuling(won, emptyList(), 1, limits, nannyPass, antagonist)
        assertIs<RulingOutcome.Stands>(exchange.outcome)
    }

    @Test
    fun anAntagonistStillNotSatisfiedIsADisputeForTheQueen() = runTest {
        val antagonist = QueenModel { prompt ->
            if ("SATISFIED" in prompt) "SATISFIED: NO" else "CHALLENGE: Only three tasks.\nCITE: f2"
        }
        val exchange = exchangeOnRuling(won, emptyList(), 1, limits, nannyPass, antagonist)
        assertIs<RulingOutcome.Disputed>(exchange.outcome)
        assertIs<TrainingNext.SettleDispute>(nextForExchange(exchange, 1, limits, emptyList()))
    }

    @Test
    fun aChallengeWithNoNannyAnswerIsADispute() = runTest {
        val antagonist = QueenModel { "CHALLENGE: Only three tasks.\nCITE: f2" }
        val exchange = exchangeOnRuling(won, emptyList(), 1, limits, null, antagonist)
        assertIs<RulingOutcome.Disputed>(exchange.outcome)
    }

    @Test
    fun aSpendCapBlocksAPassTheModelAsked() = runTest {
        val over = won.copy(spentUsd = 2.0)
        val exchange = exchangeOnRuling(over, emptyList(), 1, limits, nannyPass, null)
        val ruled = assertIs<TrainingRuling.Ruled>(exchange.ruling)
        assertEquals(TrainingCall.Stop, ruled.ruling.call)
    }

    @Test
    fun satisfiedIsOnlyAnExplicitYes() {
        assertEquals(true, parseSatisfied("SATISFIED: yes."))
        assertEquals(false, parseSatisfied("**SATISFIED: NO**"))
        assertNull(parseSatisfied("Maybe"))
    }
}

class TrainingCarryTest {
    @Test
    fun theRulingAndFindingsAreCarriedOnceAsNannyRows() {
        val rows = trainingCarryForward("r1", "d1", "tax law", "Won on f2", listOf("Missed edge cases", " "), emptyList(), 5L)
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.source == com.hereliesaz.geministrator.workflow.improvement.ImprovementSource.Nanny })
        assertTrue(rows.first().summary.startsWith("Training: Ruling:"))
        assertTrue(trainingCarryForward("r1", "d1", "tax law", "Won on f2", listOf("Missed edge cases"), rows, 6L).isEmpty())
    }

    @Test
    fun theAdoptedNoteLinksToTheRoster() {
        val note = trainingAdoptedNote("r1", "tax law")
        assertEquals(QueenVoice.TrainingAdopted, note.voice)
        assertEquals(QueenLink.Roster, note.link)
    }
}
