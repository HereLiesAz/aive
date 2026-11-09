package com.hereliesaz.geministrator.workflow.improvement

import com.hereliesaz.geministrator.persistence.InMemorySettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImprovementLedgerTest {
    private val cycleText = """
        some chatter that is ignored
        IMPROVEMENT | role-instruction | qa-engineer | Ask for the failing command | Always quote the command that failed. | Fewer retries on verification; measure retried steps for qa-engineer | Remove the added line
        IMPROVEMENT | loop-definition | suite-self-improvement/examine-loop | Examine retries first | Start from retried steps. | Findings cite retry counts | Restore the old objective
        IMPROVEMENT | role-instruction | broken line with too few parts
        META-NO-CHANGE | not used here
    """.trimIndent()

    @Test
    fun linesBecomeBoundedProposalsAndAMalformedOneIsCountedNotGuessed() {
        val (cycle, proposals) = readCycle("run-1", cycleText, 100L)

        assertEquals(2, proposals.size)
        assertEquals(listOf("run-1-1", "run-1-2"), proposals.map { it.id })
        assertEquals(ImprovementKind.RoleInstruction, proposals[0].kind)
        assertEquals("qa-engineer", proposals[0].target)
        assertFalse(proposals[0].meta)
        assertTrue(proposals[1].meta, "the loop's own step")
        assertEquals(1, cycle.malformedLines)
        assertEquals(1, cycle.metaProposals)
        assertEquals("not used here", cycle.metaNoChange)
        assertTrue(cycle.examinedLoop)
    }

    @Test
    fun textIsBoundedAndControlCharactersAreDropped() {
        val long = "x".repeat(5_000)
        val (_, proposals) = readCycle("r", "IMPROVEMENT | other | t | s\u0007 | $long | e | u", 1L)
        assertEquals(1_200, proposals.single().newText.length)
        assertFalse(proposals.single().summary.contains('\u0007'))
    }

    @Test
    fun aCycleIsIngestedOnceAndDecisionsStick() = runTest {
        val store = SettingsImprovementStore(InMemorySettings())
        val (cycle, proposals) = readCycle("run-1", cycleText, 100L)

        assertTrue(store.ingest(cycle, proposals))
        assertFalse(store.ingest(cycle, proposals), "the same cycle is not added twice")
        store.decide("run-1-1") { it.copy(status = ProposalStatus.Adopted, decidedAtEpochMillis = 200L, appliedBefore = "a", appliedAfter = "b") }

        val state = SettingsImprovementStore(InMemorySettings().also { }).state()
        assertEquals(emptyList(), state.proposals)
        val read = store.state()
        assertEquals(2, read.proposals.size)
        assertEquals(ProposalStatus.Adopted, read.proposals.first().status)
        assertEquals(ProposalStatus.Proposed, read.proposals.last().status)
        assertTrue(ImprovementApplier.canRevert(read.proposals.first(), "b"))
        assertFalse(ImprovementApplier.canRevert(read.proposals.first(), "edited since"), "a later edit is not undone")
    }

    @Test
    fun theLoopOwesAnExaminationOfItselfAndTheScreenCanSayWhenItIsMissed() {
        val day = 86_400_000L
        assertEquals(LoopObligation.Standing.NeverRan, LoopObligation.standing(ImprovementState(), 0L))
        val examined = CycleRecord("a", 10 * day, proposals = 1, metaProposals = 1)
        assertEquals(LoopObligation.Standing.Examined, LoopObligation.standing(ImprovementState(cycles = listOf(examined)), 15 * day))
        assertEquals(LoopObligation.Standing.Overdue, LoopObligation.standing(ImprovementState(cycles = listOf(examined)), 40 * day))
        val skipped = CycleRecord("b", 12 * day, proposals = 2, metaProposals = 0)
        assertEquals(LoopObligation.Standing.LastCycleSkippedItself, LoopObligation.standing(ImprovementState(cycles = listOf(examined, skipped)), 13 * day))
        val saidNoChange = skipped.copy(metaNoChange = "The evidence shows 0 retried steps in 14 days, so nothing in the loop needs to change.")
        assertTrue(saidNoChange.examinedLoop)
        assertNull(skipped.metaNoChange)
    }

    @Test
    fun aNoChangeLineOfAFewWordsOrWithNoFigureInItIsNotAnExaminationOfTheLoop() {
        assertFalse(CycleRecord("a", 1L, 0, 0, metaNoChange = "x").examinedLoop)
        assertFalse(CycleRecord("a", 1L, 0, 0, metaNoChange = "the evidence shows no retries").examinedLoop, "short, and cites no number")
        assertFalse(CycleRecord("a", 1L, 0, 0, metaNoChange = "The loop seems fine to me and I see nothing about it that ought to be changed.").examinedLoop, "long enough, but cites nothing from the evidence")
        assertFalse(CycleRecord("a", 1L, 0, 0, metaNoChange = "3 fine").examinedLoop, "a digit alone is not enough")
        assertTrue(CycleRecord("a", 1L, 0, 0, metaNoChange = "Failures fell from 9 to 2 and no step was retried, so the loop needs no change.").examinedLoop)
        assertTrue(CycleRecord("a", 1L, 1, 1).examinedLoop, "a proposal about the loop is still an examination")
        // Read back from a cycle's last step, a bare gesture is recorded but does not count.
        val (cycle, _) = readCycle("r", "META-NO-CHANGE | fine", 1L)
        assertEquals("fine", cycle.metaNoChange)
        assertFalse(cycle.examinedLoop)
    }

    private fun proposal(id: String, status: ProposalStatus, before: String? = null) = ImprovementProposal(
        id = id, cycleRunId = "c", kind = ImprovementKind.RoleInstruction, target = "qa-engineer", summary = "s $id", newText = "t",
        expectedEffect = "e", undo = "u", meta = false, status = status, createdAtEpochMillis = 1L, appliedBefore = before, appliedAfter = before?.let { "after" },
    )

    @Test
    fun aFullLedgerNeverForgetsAnAdoptedProposalAndForgetsDismissedAndSupersededOnesFirstOldestFirst() = runTest {
        val store = SettingsImprovementStore(InMemorySettings())
        // The oldest are the adopted ones, which hold the text a revert needs; then dismissed, superseded and waiting ones.
        val old = (1..20).map { proposal("adopted-$it", ProposalStatus.Adopted, before = "original $it") } +
            (1..200).map { proposal("dismissed-$it", ProposalStatus.Dismissed) } +
            (1..100).map { proposal("superseded-$it", ProposalStatus.Superseded) } +
            (1..180).map { proposal("waiting-$it", ProposalStatus.Proposed) }
        store.merge { it.copy(proposals = old) }
        assertEquals(500, store.state().proposals.size)

        store.merge { it.copy(proposals = it.proposals + (1..60).map { n -> proposal("new-$n", ProposalStatus.Proposed) }) }

        val kept = store.state().proposals
        assertEquals(500, kept.size)
        assertEquals(20, kept.count { it.status == ProposalStatus.Adopted }, "every adopted one survives, with its appliedBefore")
        assertTrue(kept.filter { it.status == ProposalStatus.Adopted }.all { it.appliedBefore != null })
        assertEquals(listOf("dismissed-61"), kept.filter { it.status == ProposalStatus.Dismissed }.take(1).map { it.id }, "the oldest dismissed ones went first")
        assertEquals(100, kept.count { it.status == ProposalStatus.Superseded })
        assertEquals(240, kept.count { it.status == ProposalStatus.Proposed }, "waiting ones are only forgotten after every dismissed and superseded one")
    }

    @Test
    fun theLedgerExceedsItsCapRatherThanForgetAnAdoptedProposal() = runTest {
        val store = SettingsImprovementStore(InMemorySettings())
        store.merge { it.copy(proposals = (1..510).map { n -> proposal("adopted-$n", ProposalStatus.Adopted, before = "b") } + proposal("waiting", ProposalStatus.Proposed)) }

        val kept = store.state().proposals
        assertEquals(510, kept.size, "only the one that was not adopted could go")
        assertTrue(kept.none { it.id == "waiting" })
    }

    @Test
    fun aDecisionIsACompareAndSetOnTheStatusAndAMissingProposalIsNotASilentSuccess() = runTest {
        val store = SettingsImprovementStore(InMemorySettings())
        store.merge { it.copy(proposals = listOf(proposal("p1", ProposalStatus.Proposed))) }

        assertTrue(store.decide("p1") { it.copy(status = ProposalStatus.Adopted, appliedBefore = "orig", appliedAfter = "new") })
        assertFalse(store.decide("p1") { it.copy(status = ProposalStatus.Adopted, appliedBefore = "changed", appliedAfter = "again") }, "already decided")
        assertEquals("orig", store.state().proposals.single().appliedBefore, "the second decision wrote nothing")
        assertFalse(store.decide("gone") { it.copy(status = ProposalStatus.Dismissed) }, "no such proposal")
        assertTrue(store.decide("p1", expected = ProposalStatus.Adopted) { it.copy(status = ProposalStatus.Reverted) })

        val memory = InMemoryImprovementStore()
        memory.merge { it.copy(proposals = listOf(proposal("p1", ProposalStatus.Proposed))) }
        assertTrue(memory.decide("p1") { it.copy(status = ProposalStatus.Dismissed) })
        assertFalse(memory.decide("p1") { it.copy(status = ProposalStatus.Adopted) })
        assertFalse(memory.decide("gone") { it })
    }

    private fun line(vararg fields: String) = "IMPROVEMENT | " + fields.joinToString(" | ")

    @Test
    fun aPipeInANewTextCannotShiftTheFieldsAfterItAndALineWithTheWrongNumberOfFieldsIsFlaggedNotRead() {
        // Unescaped, the pipe makes eight fields: nothing is read with its fields moved, and the line is counted.
        val shifted = readCycle("r", line("role-instruction", "qa-engineer", "Use pipes", "grep x | sort", "Fewer retries", "Remove the line"), 1L)
        assertEquals(emptyList(), shifted.second)
        assertEquals(1, shifted.first.malformedLines)

        // Escaped, the pipe is part of the text and every field is where the grammar puts it.
        val escaped = readCycle("r", line("role-instruction", "qa-engineer", "Use pipes", "grep x \\| sort", "Fewer retries; measured by retried steps", "Remove the line"), 1L)
        val p = escaped.second.single()
        assertEquals("grep x | sort", p.newText)
        assertEquals("Fewer retries; measured by retried steps", p.expectedEffect)
        assertEquals("Remove the line", p.undo)
        assertEquals(0, escaped.first.malformedLines)

        // A line written the old way, with seven fields and no escape, reads as it always did; a short one is still malformed.
        val old = readCycle("r", line("role-instruction", "qa-engineer", "Quote the command", "Quote it.", "Fewer retries", "Remove it"), 1L)
        assertEquals("Quote it.", old.second.single().newText)
        assertEquals(1, readCycle("r", line("role-instruction", "qa-engineer", "s", "t", "e"), 1L).first.malformedLines)
    }
}
