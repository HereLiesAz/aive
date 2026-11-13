package com.hereliesaz.geministrator.queen

import com.hereliesaz.geministrator.InboxEntryKind
import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.HallMonitorRole
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrainingLoopTest {
    private val limits = TrainingLimits(rounds = 3, tasks = 3, spendUsd = 1.0)
    private fun results(generic: Int, specialist: Int, spent: Double? = 0.1, unpriced: Boolean = false) =
        PracticeResults(PracticeScore(generic, 3), PracticeScore(specialist, 3), spent, unpriced)

    private fun facts(r: PracticeResults, round: Int = 1) = rulingFacts(r, listOf("It missed the edge case."), round, limits)

    @Test
    fun trainerCanOnlyTighten() {
        val tight = tightenLimits(limits, TrainerLimit(rounds = 2, tasks = 9, spendUsd = 5.0))
        assertEquals(TrainingLimits(2, 3, 1.0), tight)
        assertTrue(trainerLimitLoosens(limits, TrainerLimit(tasks = 9)))
        assertFalse(trainerLimitLoosens(limits, TrainerLimit(rounds = 1)))
        assertEquals(limits, tightenLimits(limits, TrainerLimit(rounds = 0, spendUsd = -1.0)))
        assertEquals(0.5, tightenLimits(TrainingLimits(3, 3, null), TrainerLimit(spendUsd = 0.5)).spendUsd)
        assertEquals(limits, tightenLimits(limits, null))
    }

    @Test
    fun trainerLimitIsParsedFromItsReply() {
        assertEquals(TrainerLimit(2, null, 0.25), parseTrainerLimit("LIMIT ROUNDS: 2\nLIMIT SPEND: $0.25"))
        assertNull(parseTrainerLimit("no limit"))
    }

    @Test
    fun practiceResultsNeedBothScoresOverTheSameTasks() {
        assertNotNull(parsePracticeResults("GENERIC: 1/3\nSPECIALIST: 3/3\nmissed X", 0.0, false))
        assertNull(parsePracticeResults("GENERIC: 1/3\nSPECIALIST: 3/4", 0.0, false))
        assertNull(parsePracticeResults("GENERIC: 1/3", 0.0, false))
        assertNull(parsePracticeResults("GENERIC: 5/3\nSPECIALIST: 1/3", 0.0, false))
    }

    @Test
    fun rulingMustNameAnOptionAndCiteAListedFact() {
        val r = results(1, 3)
        val f = facts(r)
        val ok = parseNannyRuling(f, r, 1, limits, "RULING: PASS\nREASON: It met more tasks [f2].")
        assertEquals(TrainingCall.Pass, ok?.call)
        assertEquals("f2", ok?.factId)
        assertNull(parseNannyRuling(f, r, 1, limits, "RULING: MAYBE\nREASON: [f2]"))
        assertNull(parseNannyRuling(f, r, 1, limits, "RULING: PASS\nREASON: Looks good."))
        assertNull(parseNannyRuling(f, r, 1, limits, "RULING: PASS\nREASON: Looks good [f99]."))
    }

    @Test
    fun passNeedsAMeasurableWinAndRefineNeedsARoundAndSpend() {
        val tie = results(2, 2)
        assertNull(parseNannyRuling(facts(tie), tie, 1, limits, "RULING: PASS\nREASON: Same [f1]."))
        assertNull(parseNannyRuling(facts(tie, 3), tie, 3, limits, "RULING: REFINE\nREASON: More [f4]."))
        val spent = results(1, 3, spent = 2.0)
        assertNull(parseNannyRuling(facts(spent), spent, 1, limits, "RULING: PASS\nREASON: Won [f2]."))
        assertEquals(TrainingCall.Stop, parseNannyRuling(facts(spent), spent, 1, limits, "RULING: STOP\nREASON: Cost [f3].")?.call)
    }

    @Test
    fun anInvalidRulingFallsBackToTheRulesThenTheKeeper() {
        val win = results(1, 3)
        val byRules = settleNannyRuling(win, emptyList(), 1, limits, "garbage")
        assertEquals(TrainingCall.Pass, (byRules as TrainingRuling.Ruled).ruling.call)
        assertFalse(byRules.ruling.byModel)
        assertEquals(TrainingCall.Refine, (settleNannyRuling(results(2, 2), emptyList(), 1, limits, null) as TrainingRuling.Ruled).ruling.call)
        assertEquals(TrainingCall.Stop, (settleNannyRuling(results(2, 2), emptyList(), 3, limits, null) as TrainingRuling.Ruled).ruling.call)
        assertEquals(TrainingCall.Stop, (settleNannyRuling(results(1, 3, unpriced = true), emptyList(), 1, limits, null) as TrainingRuling.Ruled).ruling.call)
        assertEquals(TrainingRuling.NeedsKeeper(TrainingKeeperWhy.NoReadableResults), settleNannyRuling(null, emptyList(), 1, limits, "RULING: PASS"))
    }

    @Test
    fun aChallengeWithoutAListedFactDoesNotCount() {
        val f = facts(results(1, 3))
        assertEquals(Challenge.None, parseChallenge(f, "CHALLENGE: NONE"))
        assertEquals(Challenge.None, parseChallenge(f, "CHALLENGE: It is wrong.\nCITE: f99"))
        assertEquals(Challenge.None, parseChallenge(f, "CHALLENGE: It is wrong."))
        assertEquals("f3", (parseChallenge(f, "CHALLENGE: Too costly.\nCITE: f3") as Challenge.Cited).factId)
    }

    @Test
    fun oneChallengeOneAnswerThenADisputeIfStillNotSatisfied() {
        val ruling = NannyRuling(TrainingCall.Pass, "Won [f2]", "f2", true)
        val cited = Challenge.Cited("f3", "Too costly")
        assertIs<RulingOutcome.Stands>(rulingOutcome(ruling, Challenge.None, null, null))
        assertIs<RulingOutcome.Stands>(rulingOutcome(ruling, cited, ChallengeAnswer.Upheld("It holds"), true))
        assertIs<RulingOutcome.Disputed>(rulingOutcome(ruling, cited, ChallengeAnswer.Upheld("It holds"), false))
        assertIs<RulingOutcome.Disputed>(rulingOutcome(ruling, cited, ChallengeAnswer.Upheld("It holds"), null))
        assertIs<RulingOutcome.Disputed>(rulingOutcome(ruling, cited, null, true))
        val revised = ruling.copy(call = TrainingCall.Refine)
        assertEquals(revised, (rulingOutcome(ruling, cited, ChallengeAnswer.Revised(revised), true) as RulingOutcome.Stands).ruling)
    }

    @Test
    fun theDisputeIsTheNannyAndTheAntagonistWithOnlySafeOptions() {
        val disputed = RulingOutcome.Disputed(NannyRuling(TrainingCall.Pass, "Won [f2]", "f2", true), Challenge.Cited("f3", "Too costly"))
        val dispute = trainingDispute(disputed, facts(results(1, 3)))
        assertTrue(dispute.options.all { it.safe })
        assertTrue("n1" in dispute.facts && "a1" in dispute.facts && "f2" in dispute.facts)
        assertEquals(listOf(HallMonitorRole.id.value, BuiltInRoles.Antagonist.id.value), trainingDisputants().map { it.id })
    }

    @Test
    fun theLoopSteps() {
        val pass = TrainingRuling.Ruled(NannyRuling(TrainingCall.Pass, "Won [f2]", "f2", false))
        assertIs<TrainingNext.OfferToKeeper>(trainingNext(1, limits, pass, null, emptyList()))
        val refine = TrainingRuling.Ruled(NannyRuling(TrainingCall.Refine, "More [f2]", "f2", false))
        assertEquals(TrainingNext.Refine(2, listOf("a")), trainingNext(1, limits, refine, null, listOf("a")))
        assertIs<TrainingNext.Stop>(trainingNext(3, limits, refine, null, emptyList()))
        assertIs<TrainingNext.AskKeeper>(trainingNext(1, limits, TrainingRuling.NeedsKeeper(TrainingKeeperWhy.NoReadableResults), null, emptyList()))
        val disputed = RulingOutcome.Disputed((pass as TrainingRuling.Ruled).ruling, Challenge.Cited("f3", "x"))
        assertIs<TrainingNext.SettleDispute>(trainingNext(1, limits, pass, disputed, emptyList()))
        assertIs<TrainingNext.Stop>(trainingNextAfterDispute("stop", disputed, 1, limits, emptyList()))
        assertIs<TrainingNext.Refine>(trainingNextAfterDispute("refine", disputed, 1, limits, emptyList()))
        assertIs<TrainingNext.OfferToKeeper>(trainingNextAfterDispute("uphold", disputed, 1, limits, emptyList()))
        assertEquals(TrainingNext.AskKeeper(TrainingKeeperWhy.Disputed), trainingNextAfterDispute(null, disputed, 1, limits, emptyList()))
    }

    @Test
    fun everyLessonKeepsItsSourceAndDate() {
        val lessons = parseLessons(
            "LESSON: Check units first | SOURCE: Style guide | FETCHED: 2026-11-13\n" +
                "LESSON: No source here | FETCHED: 2026-11-13\n" +
                "LESSON: No date | SOURCE: Somewhere | FETCHED: soon\n" +
                "LESSON: check units first | SOURCE: Other | FETCHED: 2026-11-14",
        )
        assertEquals(listOf(TrainingLesson("Check units first", "Style guide", "2026-11-13")), lessons)
        val adoption = trainingAdoption("reviewer", "Tax law!", lessons)
        assertEquals("reviewer-tax-law", adoption?.roleId)
        assertEquals("reviewer", adoption?.parentRoleId)
        assertEquals(listOf("Check units first (source: Style guide, fetched 2026-11-13)"), adoption?.lessons)
        assertNull(trainingAdoption("reviewer", "!!!", lessons))
        assertNull(trainingAdoption("reviewer", "tax", emptyList()))
    }

    private val roles = TrainingRoles(
        BuiltInRoles.Planner, BuiltInRoles.Researcher, BuiltInRoles.Trainer, BuiltInRoles.Reviewer,
        BuiltInRoles.Antagonist, HallMonitorRole.definition, BuiltInRoles.Orchestrator,
    )

    @Test
    fun theSwarmIsOrdinaryGenericRolesAndTheKeeperGatesTheOffer() {
        val def = trainingDefinition(WorkflowDefinitionId("t"), "tax law", BuiltInRoles.Reviewer, roles, limits, 2, listOf("missed X"))!!
        assertEquals(9, def.tasks.size)
        val ids = def.tasks.map { it.id.value }.toSet()
        assertTrue(def.tasks.all { task -> task.dependsOn.all { it.value in ids } })
        val offer = def.tasks.first { it.id.value == TRAINING_OFFER_ID }
        assertEquals(ApprovalPolicy.HumanApproval, offer.approvalPolicy)
        assertEquals(BuiltInRoles.Orchestrator.id, offer.roleId)
        assertEquals(BuiltInRoles.Reviewer.id, def.tasks.first { it.id.value == TRAINING_GENERIC_ID }.roleId)
        assertEquals(draftSpecialistRole(BuiltInRoles.Reviewer).id, def.tasks.first { it.id.value == TRAINING_SPECIALIST_ID }.roleId)
        val info = trainingRunInfo(def)!!
        assertEquals(TrainingRunInfo("tax law", "reviewer", 2), info)
        val draft = draftSpecialistRole(BuiltInRoles.Reviewer)
        assertEquals(listOf(BuiltInRoles.Reviewer.id), draft.parents)
        assertTrue(draft.id.value.endsWith(TRAINING_DRAFT_SUFFIX) && draft.id != BuiltInRoles.Reviewer.id)
        assertTrue(draft.instructions.startsWith(BuiltInRoles.Reviewer.instructions.trimEnd()) && "LESSON:" in draft.instructions)
        assertTrue(def.tasks.first { it.id.value == TRAINING_RESEARCH_ID }.objective.startsWith("Learn about tax law"))
        assertTrue("missed X" in def.tasks.first { it.id.value == TRAINING_LESSONS_ID }.objective)
        assertNull(trainingDefinition(WorkflowDefinitionId("t"), "  ", BuiltInRoles.Reviewer, roles, limits))
    }

    @Test
    fun nothingIsAdoptedWithoutTheKeepersApprovalOfTheOffer() = runTest {
        val adoption = trainingAdoption("reviewer", "tax", listOf(TrainingLesson("Check units", "Guide", "2026-11-13")))!!
        fun runWith(status: TaskRunStatus?) = qRun(*listOfNotNull(status?.let { qTaskRun(TRAINING_OFFER_ID, it) }).toTypedArray())
        assertNull(trainingApproval(runWith(null), adoption, 1))
        assertNull(trainingApproval(runWith(TaskRunStatus.AwaitingApproval), adoption, 1))
        val refused = adoptSpecialist(adoption, trainingApproval(runWith(TaskRunStatus.Cancelled), adoption, 1)) { true }
        assertEquals(AdoptionResult.Refused(AdoptionRefusal.NotApproved), refused)
        val none = adoptSpecialist(adoption, trainingApproval(runWith(null), adoption, 1)) { true }
        assertEquals(AdoptionResult.Refused(AdoptionRefusal.NoApproval), none)
        val approval = trainingApproval(runWith(TaskRunStatus.Completed), adoption, 1)
        assertEquals(InboxEntryKind.TaskApproval, approval?.entryKind)
        assertEquals(AdoptionResult.Adopted(adoption), adoptSpecialist(adoption, approval) { true })
    }

    @Test
    fun theQueenSaysOnlyTheOfferTheStopAndTheHandOff() {
        val r = results(1, 3)
        val offer = trainingNotes("r1", "tax law", TrainingNext.OfferToKeeper("Won [f2]"), r).single()
        assertEquals(QueenVoice.TrainingOffer, offer.voice)
        assertEquals(listOf("tax law", "1/3", "3/3"), offer.args)
        assertEquals(QueenVoice.TrainingStopped, trainingNotes("r1", "tax", TrainingNext.Stop("x"), r).single().voice)
        assertEquals(QueenVoice.TrainingNeedsYou, trainingNotes("r1", "tax", TrainingNext.AskKeeper(TrainingKeeperWhy.Disputed), null).single().voice)
        assertTrue(trainingNotes("r1", "tax", TrainingNext.Refine(2, emptyList()), r).isEmpty())
    }
}
