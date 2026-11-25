package com.hereliesaz.geministrator.queen

import com.hereliesaz.geministrator.research.LocalResearchSource
import com.hereliesaz.geministrator.research.ResearchBudget
import com.hereliesaz.geministrator.research.ResearchContext
import com.hereliesaz.geministrator.research.ResearchLead
import com.hereliesaz.geministrator.research.ResearchSource
import com.hereliesaz.geministrator.research.ResearchStep
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CollectiveEvidenceLedgerTest {
    @Test
    fun researchStepRetainsReturnedClaimsWithoutVerification() = runTest {
        val settings = MapSettings()
        val ledger = CollectiveEvidenceLedger(settings) { 100L }
        val source = LocalResearchSource {
            listOf(
                ResearchLead("Manual", ResearchSource.Repository, "local", "Uses SQLite", true),
                ResearchLead("Extra", ResearchSource.Repository, "local", "Unbounded second lead", true),
            )
        }
        val step = ResearchStep(
            local = listOf(ResearchSource.Repository to source),
            budget = ResearchBudget(maxLeads = 1),
            observeOutcome = ledger::recordResearch,
        )
        step.run("Learn about storage", ResearchContext(repositoryLinked = true), "lookup-1")
        step.run("Paint a wall", ResearchContext(), "lookup-empty")
        val book = CollectiveEvidenceLedger(settings) { 200L }.snapshot()
        assertEquals(1, book.entries.size)
        val entry = book.entries.single()
        assertEquals(EvidenceKind.CitedClaim, entry.kind)
        assertEquals("Learn about storage", entry.question)
        assertEquals("Research operation: lookup-1", entry.source)
        assertTrue(entry.text.contains("linked repository"))
        assertTrue(entry.text.contains("Uses SQLite"))
        assertEquals(false, entry.recordedByKeeper)
        assertTrue(book.reviews.isEmpty())
    }

    @Test
    fun opinionsRemainIndependentAcrossRestartAndReview() = runTest {
        val settings = MapSettings()
        val ledger = CollectiveEvidenceLedger(settings) { 100L }
        val issue = ledger.issueId()
        val voice = CollectiveVoice("reviewer", QueenModel { error("Recording must not invoke a model") }, "instance-a")
        ledger.recordAdvisory(issue, "conversation-a", "Does the circuit work?", voice, "It works.")
        ledger.recordAdvisory(issue, "conversation-a", "Does the circuit work?", voice.copy(role = "skeptic"), "It fails.")
        val restored = CollectiveEvidenceLedger(settings) { 200L }
        val first = restored.snapshot().entries
        assertEquals(listOf("It works.", "It fails."), first.map { it.text })
        assertTrue(first.all { it.kind == EvidenceKind.Opinion && it.issueId == issue })
        restored.review(first[0].id, first[1].id, EvidenceRelation.Contradicts, "Opposing findings.")
        assertFailsWith<IllegalArgumentException> {
            restored.review(first[0].id, first[1].id, EvidenceRelation.VerifiedByKeeper, "Agreement is not evidence.")
        }
        val observed = restored.recordKeeper(issue, "Does the circuit work?", EvidenceKind.Observation, "Measured zero volts.", "Meter reading")
        restored.review(first[1].id, observed.id, EvidenceRelation.VerifiedByKeeper, "Observed the meter.")
        restored.review(first[0].id, null, EvidenceRelation.RetractedByKeeper, "Rechecked.")
        val book = CollectiveEvidenceLedger(settings) { 300L }.snapshot()
        assertEquals(listOf(EvidenceKind.Opinion, EvidenceKind.Opinion, EvidenceKind.Observation), book.entries.map { it.kind })
        assertEquals(listOf(EvidenceRelation.Contradicts, EvidenceRelation.VerifiedByKeeper, EvidenceRelation.RetractedByKeeper), book.reviews.map { it.relation })
        restored.forget(first[0].id)
        assertEquals(1, restored.snapshot().reviews.size)
        assertEquals(2, restored.snapshot().entries.size)
        restored.clear()
        assertEquals(EvidenceBook(), CollectiveEvidenceLedger(settings) { 400L }.snapshot())
    }
}
