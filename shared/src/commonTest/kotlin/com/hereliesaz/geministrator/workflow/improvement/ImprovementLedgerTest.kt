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
        val saidNoChange = skipped.copy(metaNoChange = "the evidence shows no retries")
        assertTrue(saidNoChange.examinedLoop)
        assertNull(skipped.metaNoChange)
    }
}
