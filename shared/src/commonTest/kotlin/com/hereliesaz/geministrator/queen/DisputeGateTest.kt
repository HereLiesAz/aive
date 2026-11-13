package com.hereliesaz.geministrator.queen

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DisputeGateTest {
    private val nanny = Disputant("nanny", "Nanny")
    private val antagonist = Disputant("antagonist", "Antagonist")
    private val parties = listOf(nanny, antagonist)

    private val dispute = Dispute(
        question = "Pass or refine the practice?",
        options = listOf(DisputeOption("pass", "Pass it", safe = true), DisputeOption("refine", "Refine it", safe = true), DisputeOption("skip", "Skip the gate", safe = false)),
        facts = mapOf("f1" to "The specialist scored higher.", "f2" to "The cost doubled."),
    )
    private val oneSafe = dispute.copy(options = listOf(DisputeOption("pass", "Pass it", safe = true), DisputeOption("skip", "Skip the gate", safe = false)))

    private val rule = Approach(ApproachKind.Rule, "I will rule: Pass it, because [f1]", listOf("f1"))
    private val agree = ApproachAnswer(agrees = true)
    private fun no(fact: String? = "f2", why: String = "Too costly.") = ApproachAnswer(agrees = false, factId = fact, objection = why)

    // --- the pure gate ---------------------------------------------------------------------------------------------

    @Test
    fun withoutAnApproachTheQueenMustStateOneFirst() {
        assertEquals(ApproachGate.NeedApproach, approachGate(parties, emptyList(), queenAvailable = true))
    }

    @Test
    fun aMissingAnswerIsNotAgreement() {
        val gate = approachGate(parties, listOf(ApproachRound(rule, mapOf("nanny" to agree))), queenAvailable = true)
        assertEquals(ApproachGate.AwaitAnswers(rule, listOf("antagonist")), gate)
    }

    @Test
    fun bothAgreeingLetsItProceed() {
        val gate = approachGate(parties, listOf(ApproachRound(rule, mapOf("nanny" to agree, "antagonist" to agree))), queenAvailable = true)
        assertEquals(ApproachGate.Proceed(rule), gate)
    }

    @Test
    fun oneDisagreementGivesOneRoundOfRevision() {
        val gate = approachGate(parties, listOf(ApproachRound(rule, mapOf("nanny" to agree, "antagonist" to no()))), queenAvailable = true)
        assertEquals(ApproachGate.Revise(listOf(Objection("antagonist", "Too costly.", "f2"))), gate)
    }

    @Test
    fun stillNotAgreedAfterTheRevisionGoesToTheKeeper() {
        val round = ApproachRound(rule, mapOf("nanny" to no("f1", "No."), "antagonist" to no()))
        val gate = approachGate(parties, listOf(round, round), queenAvailable = true)
        val escalation = (gate as ApproachGate.Escalate).escalation
        assertEquals(EscalationReason.NotAgreed, escalation.reason)
        assertEquals(rule, escalation.approach)
        assertEquals(2, escalation.objections.size)
    }

    @Test
    fun anUncitedDisagreementStillBlocks() {
        val gate = approachGate(parties, listOf(ApproachRound(rule, mapOf("nanny" to agree, "antagonist" to no(fact = null, why = "Just no.")))), queenAvailable = true)
        assertIs<ApproachGate.Revise>(gate)
    }

    @Test
    fun noQueenModelSendsItToTheKeeperUnlessBothAlreadyAgreed() {
        val gate = approachGate(parties, emptyList(), queenAvailable = false)
        assertEquals(EscalationReason.QueenUnavailable, (gate as ApproachGate.Escalate).escalation.reason)
        val disagreed = approachGate(parties, listOf(ApproachRound(rule, mapOf("nanny" to agree, "antagonist" to no()))), queenAvailable = false)
        assertEquals(EscalationReason.QueenUnavailable, (disagreed as ApproachGate.Escalate).escalation.reason)
        val agreed = approachGate(parties, listOf(ApproachRound(rule, mapOf("nanny" to agree, "antagonist" to agree))), queenAvailable = false)
        assertIs<ApproachGate.Proceed>(agreed)
    }

    @Test
    fun aDisputeNeedsExactlyTwoDistinctCreatures() {
        for (bad in listOf(listOf(nanny), listOf(nanny, nanny), listOf(nanny, antagonist, Disputant("x", "X")))) {
            val gate = approachGate(bad, listOf(ApproachRound(rule, mapOf("nanny" to agree))), queenAvailable = true)
            assertEquals(EscalationReason.NotTwoParties, (gate as ApproachGate.Escalate).escalation.reason)
        }
    }

    // --- reading answers -------------------------------------------------------------------------------------------

    @Test
    fun anAnswerIsReadAndACitedFactMustBeListed() {
        assertEquals(ApproachAnswer(true), parseApproachAnswer(dispute, "ANSWER: AGREE"))
        assertEquals(ApproachAnswer(false, "f2", "It costs too much."), parseApproachAnswer(dispute, "**ANSWER: DISAGREE**\nCITE: [f2]\nWHY: It costs too much."))
        assertNull(parseApproachAnswer(dispute, "ANSWER: DISAGREE\nCITE: f9\nWHY: x")!!.factId)
        assertNull(parseApproachAnswer(dispute, "I think so"))
        assertNull(parseApproachAnswer(dispute, "ANSWER: MAYBE"))
    }

    // --- settling with agreement ----------------------------------------------------------------------------------

    private val rulePass = "RULING: pass\nREASON: It scored higher [f1]."
    private val unsureResearch = "RULING: UNSURE\nINVESTIGATE: research\nMISSING: What the specialist costs per task."
    private val unsureTest = "RULING: UNSURE\nINVESTIGATE: test\nMISSING: Which option works on a small task."

    private fun script(vararg replies: String): QueenModel {
        val queue = replies.toMutableList()
        return QueenModel { queue.removeAt(0) }
    }

    private fun settle(queen: QueenModel?, nannySays: QueenModel?, antagonistSays: QueenModel?, d: Dispute = dispute, allow: Boolean = true) = runBlocking {
        settleWithAgreement(d, parties, queen, listOfNotNull(nannySays?.let { "nanny" to it }, antagonistSays?.let { "antagonist" to it }).toMap(), allow)
    }

    @Test
    fun bothAgreeingLetsHerRule() {
        val resolution = settle(script(rulePass), script("ANSWER: AGREE"), script("ANSWER: AGREE"))
        val agreed = assertIs<DisputeResolution.Agreed>(resolution)
        assertEquals("pass", (agreed.outcome as DisputeOutcome.Ruled).optionId)
        assertEquals(listOf("f1"), agreed.approach.factIds)
    }

    @Test
    fun aDisagreementIsAnsweredWithOneRevisedApproachAndThenBothAgree() {
        val resolution = settle(
            script(rulePass, "RULING: refine\nREASON: It cost more [f2]."),
            script("ANSWER: AGREE", "ANSWER: AGREE"),
            script("ANSWER: DISAGREE\nCITE: f2\nWHY: It cost too much.", "ANSWER: AGREE"),
        )
        assertEquals("refine", ((resolution as DisputeResolution.Agreed).outcome as DisputeOutcome.Ruled).optionId)
    }

    @Test
    fun stillNotBothAgreedAfterTheRoundGivesTheKeeperHerApproachAndEachObjection() {
        val resolution = settle(
            script(rulePass, "RULING: refine\nREASON: It cost more [f2]."),
            script("ANSWER: AGREE", "ANSWER: DISAGREE\nCITE: f1\nWHY: It scored higher."),
            script("ANSWER: DISAGREE\nCITE: f2\nWHY: It cost too much.", "ANSWER: DISAGREE\nCITE: f2\nWHY: Still too much."),
        )
        val escalation = (resolution as DisputeResolution.Escalated).escalation
        assertEquals(EscalationReason.NotAgreed, escalation.reason)
        assertTrue(escalation.approach!!.line.startsWith("I will rule: Refine it"))
        assertEquals(listOf("nanny", "antagonist"), escalation.objections.map { it.disputantId })
    }

    @Test
    fun nothingStartsWhenADisputantCannotBeReached() {
        val resolution = settle(script(rulePass, rulePass), script("ANSWER: AGREE", "ANSWER: AGREE"), null)
        assertIs<DisputeResolution.Escalated>(resolution)
        val silent = settle(script(rulePass, rulePass), QueenModel { error("down") }, QueenModel { error("down") })
        assertIs<DisputeResolution.Escalated>(silent)
    }

    @Test
    fun noQueenModelOrAFailingOneEscalatesWithoutAskingAnyone() {
        var asked = 0
        val voice = QueenModel { asked++; "ANSWER: AGREE" }
        val none = settle(null, voice, voice)
        assertEquals(EscalationReason.QueenUnavailable, (none as DisputeResolution.Escalated).escalation.reason)
        val failing = settle(QueenModel { error("down") }, voice, voice)
        assertEquals(EscalationReason.QueenUnavailable, (failing as DisputeResolution.Escalated).escalation.reason)
        assertEquals(0, asked)
    }

    @Test
    fun aRefusedCallIsTheSameAsNoQueen() = runBlocking {
        val resolution = settleWithAgreement(dispute, parties, script(rulePass), emptyMap(), mayCall = { false })
        assertEquals(EscalationReason.QueenUnavailable, (resolution as DisputeResolution.Escalated).escalation.reason)
    }

    @Test
    fun whenNoFactSettlesItSheAsksToResearchAndNeedsBothToAgree() {
        val agreed = settle(script(unsureResearch), script("ANSWER: AGREE"), script("ANSWER: AGREE"))
        val request = ((agreed as DisputeResolution.Agreed).outcome as DisputeOutcome.Investigate).request
        assertEquals(InvestigationKind.Research, request.kind)
        assertEquals("What the specialist costs per task.", request.missingFact)
        assertEquals(emptyList(), request.optionIds)
        val refused = settle(script(unsureResearch, unsureResearch), script("ANSWER: AGREE", "ANSWER: AGREE"), script("ANSWER: DISAGREE\nCITE: f2\nWHY: No.", "ANSWER: DISAGREE\nCITE: f2\nWHY: No."))
        assertIs<DisputeResolution.Escalated>(refused)
    }

    @Test
    fun aTestNeedsTwoSafeOptionsToTry() {
        val agreed = settle(script(unsureTest), script("ANSWER: AGREE"), script("ANSWER: AGREE"))
        val request = ((agreed as DisputeResolution.Agreed).outcome as DisputeOutcome.Investigate).request
        assertEquals(InvestigationKind.Test, request.kind)
        assertEquals(listOf("pass", "refine"), request.optionIds)
        // One safe option: a test would try nothing, so the rules' one safe ruling is what she puts to them.
        val single = settle(script(unsureTest), script("ANSWER: AGREE"), script("ANSWER: AGREE"), oneSafe)
        assertEquals("pass", ((single as DisputeResolution.Agreed).outcome as DisputeOutcome.Ruled).optionId)
    }

    @Test
    fun aSecondInvestigationIsNotAllowedSoUnsureAgainGoesToTheKeeper() {
        val resolution = settle(script(unsureResearch), script("ANSWER: AGREE"), script("ANSWER: AGREE"), allow = false)
        assertEquals(EscalationReason.StillUnsure, (resolution as DisputeResolution.Escalated).escalation.reason)
    }

    @Test
    fun anInvalidOrUnsafeRulingFallsBackToTheRulesAndStillNeedsAgreement() {
        val resolution = settle(script("RULING: skip\nREASON: [f1]"), script("ANSWER: AGREE"), script("ANSWER: AGREE"), oneSafe)
        assertEquals("pass", ((resolution as DisputeResolution.Agreed).outcome as DisputeOutcome.Ruled).optionId)
        val keeper = settle(script("nonsense"), script("ANSWER: AGREE"), script("ANSWER: AGREE"), dispute)
        assertEquals(EscalationReason.NoApproach, (keeper as DisputeResolution.Escalated).escalation.reason)
    }

    // --- findings and notes ----------------------------------------------------------------------------------------

    @Test
    fun findingsBecomeListedFactsSheCanCite() {
        val after = dispute.withFindings(listOf("  Costs 2 cents a task.\n", "", "x".repeat(900)))
        assertEquals(setOf("f1", "f2", "i1", "i2"), after.facts.keys)
        assertEquals("Costs 2 cents a task.", after.facts["i1"])
        assertEquals(300, after.facts["i2"]!!.length)
        assertTrue(acceptRuling(after, "RULING: pass\nREASON: It is cheap [i1].") != null)
    }

    @Test
    fun anEscalationCarriesHerApproachEachObjectionAndWhatIsMissing() {
        val escalation = Escalation(
            EscalationReason.NotAgreed,
            Approach(ApproachKind.Research, "No listed fact settles it, so I will research. I lack: the cost.", emptyList(), "the cost"),
            listOf(Objection("nanny", "Too slow.", "f2"), Objection("antagonist", "", null)),
            "the cost",
        )
        val note = disputeNotes("run-1", "q", parties, DisputeResolution.Escalated(escalation)).single()
        assertEquals(QueenVoice.DisputeNeedsYou, note.voice)
        assertEquals(listOf("Nanny", "Antagonist"), note.args)
        assertEquals(
            listOf(QueenQuoteLabel.QueenApproach, QueenQuoteLabel.Objection, QueenQuoteLabel.Objection, QueenQuoteLabel.StillMissing),
            note.quotes.map { it.label },
        )
        assertTrue(note.quotes[1].text.contains("(fact f2)"))
        assertTrue(note.quotes[2].text.contains("No reason given"))
        assertFalse(note.countsToBadge)
    }

    @Test
    fun eachReasonHasItsOwnWordsAndAnAgreedInvestigationLinksToItsRun() {
        fun voice(reason: EscalationReason) =
            disputeNotes("r", "q", parties, DisputeResolution.Escalated(Escalation(reason, null, emptyList(), null))).single().voice
        assertEquals(QueenVoice.DisputeNeedsYouQueenAway, voice(EscalationReason.QueenUnavailable))
        assertEquals(QueenVoice.DisputeStillUnsure, voice(EscalationReason.StillUnsure))
        assertEquals(QueenVoice.DisputeCannotInvestigate, voice(EscalationReason.CannotInvestigate))
        assertEquals(QueenVoice.DisputeNeedsYou, voice(EscalationReason.NoApproach))
        val agreed = DisputeResolution.Agreed(
            DisputeOutcome.Investigate(InvestigationRequest(InvestigationKind.Test, "which works", listOf("a", "b"))),
            Approach(ApproachKind.Test, "I will test", emptyList(), "which works"),
        )
        val note = disputeNotes("r", "q", parties, agreed, investigationRunId = "run-9").single()
        assertEquals(QueenVoice.DisputeTesting, note.voice)
        assertEquals(QueenLink.Run("run-9"), note.link)
        assertEquals(emptyList(), disputeNotes("r", "q", parties, DisputeResolution.Agreed(DisputeOutcome.Ruled("pass", "x", true), rule)))
    }

    @Test
    fun disputeNotesReachMessagesAtEveryLevel() {
        val definition = qDefinition(qTask("a"))
        val run = qRun(qTaskRun("a", com.hereliesaz.geministrator.domain.TaskRunStatus.Running))
        val note = disputeNotes("run-1", "q", parties, DisputeResolution.Escalated(Escalation(EscalationReason.NotAgreed, rule, emptyList(), null))).single()
        for (level in QueenLevel.entries) {
            val voices = queenUpdate(definition, run, com.hereliesaz.geministrator.domain.BuiltInRoles.all, level, null, null, emptyList(), listOf(note)).map { it.voice }
            assertTrue(QueenVoice.DisputeNeedsYou in voices, "$level")
        }
    }
}
