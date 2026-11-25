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
    fun sourceTextChecksPersistWithActualExcerptAndDoNotVerifyTruth() = runTest {
        val settings = MapSettings()
        val ledger = CollectiveEvidenceLedger(settings) { 100L }
        val source = LocalResearchSource {
            listOf(ResearchLead("Manual", ResearchSource.Repository, "local", "It is not safe.", true))
        }
        val research = com.hereliesaz.geministrator.research.ResearchInvestigation(
            ResearchStep(local = listOf(ResearchSource.Repository to source)),
            { ResearchContext(repositoryLinked = true) },
            observeClaims = ledger::recordResearchClaims,
        ).withFactModel(QueenModel { "FACT: It is safe. | SOURCE: 1\nFACT: It is not safe. | SOURCE: 1\nFACT: Invented. | SOURCE: 9" })
        val findings = requireNotNull(research.findings("Is this safe?", "lookup-2"))
        assertTrue(findings.none { "It is safe." in it || "Invented." in it })
        assertTrue(findings.any { "It is not safe." in it })
        val book = CollectiveEvidenceLedger(settings) { 200L }.snapshot()
        assertEquals(listOf(EvidenceKind.Hypothesis, EvidenceKind.CitedClaim), book.entries.map { it.kind })
        assertEquals(listOf(false, true), book.entries.map { it.researchCitation?.matchesSourceText })
        assertTrue(book.entries.all { it.researchCitation?.sourceExcerpt == "It is not safe." })
        assertTrue(book.entries.all { it.researchCitation?.sourceNumber == 1 })
        assertTrue(book.reviews.isEmpty())
    }

    @Test
    fun associatedReportsAndResearchExcerptsReachOnlyTheRelevantConversation() = runTest {
        val settings = MapSettings()
        val ledger = CollectiveEvidenceLedger(settings) { 100L }
        val voice = CollectiveVoice("reviewer", QueenModel { error("Projection must not invoke models") }, "instance-a")
        val issue = ledger.issueId()
        ledger.recordAdvisory(issue, "conversation-a", "Does the circuit work?", voice, "It works.")
        ledger.recordAdvisory(ledger.issueId(), "conversation-b", "Unrelated issue", voice, "Another view.")
        val opinion = ledger.snapshot().entries.first { it.conversationId == "conversation-a" }
        val observation = ledger.recordKeeper(issue, opinion.question, EvidenceKind.Observation, "Measured zero volts.", "Meter")
        ledger.recordKeeper(issue, opinion.question, EvidenceKind.Opinion, "An unsupported opinion.", "Keeper")
        ledger.recordKeeper(null, opinion.question, EvidenceKind.Observation, "Same words, different issue.", "Meter")
        val source = LocalResearchSource {
            listOf(ResearchLead("Manual", ResearchSource.Repository, "local", "It requires 5 volts.", true))
        }
        val research = com.hereliesaz.geministrator.research.ResearchInvestigation(
            ResearchStep(local = listOf(ResearchSource.Repository to source)),
            { ResearchContext(repositoryLinked = true) },
            observeClaims = ledger::recordResearchClaims,
        ).withFactModel(QueenModel { "FACT: It requires 10 volts. | SOURCE: 1" })
        research.findings("Circuit manual", "lookup-a")
        val citation = ledger.snapshot().entries.single { it.researchCitation != null }
        ledger.review(citation.id, opinion.id, EvidenceRelation.Contradicts, "PRIVATE REVIEW")
        ledger.review(observation.id, null, EvidenceRelation.RetractedByKeeper, "PRIVATE RETRACTION")
        val restored = CollectiveEvidenceLedger(settings) { 200L }
        val evidence = restored.linkedObservations("conversation-a")
        assertEquals(listOf(citation.id, observation.id), evidence.evidenceIds)
        assertEquals(setOf("instance-a"), evidence.participantIds)
        assertTrue("Measured zero volts." in evidence.text && "Keeper-reported Observation" in evidence.text)
        assertTrue("Keeper recorded a retraction." in evidence.text)
        assertTrue("It requires 5 volts." in evidence.text && "It requires 10 volts." in evidence.text)
        assertTrue("matches bounded source excerpt=false" in evidence.text)
        assertTrue("PRIVATE" !in evidence.text && "unsupported opinion" !in evidence.text &&
            "different issue" !in evidence.text)
        assertEquals(emptyList(), restored.linkedObservations("conversation-b").evidenceIds)
        restored.forget(citation.id)
        assertEquals(listOf(observation.id), ledger.linkedObservations("conversation-a").evidenceIds)
    }

    @Test
    fun localReconsiderationDiscoveryDoesNotLinkOrSendCandidatesUntilKeeperAssociatesThem() = runTest {
        val settings = MapSettings()
        val ledger = CollectiveEvidenceLedger(settings) { 100L }
        val voice = CollectiveVoice("reviewer", QueenModel { error("Discovery must not call a model") }, "instance-a")
        val question = "Does the quartz oscillator drift?"
        ledger.recordAdvisory(ledger.issueId(), "conversation-a", question, voice, "No drift.")
        val opinion = ledger.snapshot().entries.single()
        val other = ledger.recordKeeper(null, "Unrelated garden", EvidenceKind.Observation, "Flowers bloom.", "Keeper")
        val exact = ledger.recordKeeper(null, question, EvidenceKind.Observation, "Measured drift.", "Meter")
        val unsupported = ledger.recordKeeper(null, question, EvidenceKind.Opinion, "I think so.", "Keeper")
        val initial = ledger.snapshot()
        val candidates = initial.reconsiderationCandidates(opinion.id)
        assertEquals(exact.id, candidates.first().id)
        assertEquals(setOf(exact.id, other.id), candidates.map { it.id }.toSet())
        assertTrue(unsupported.id !in candidates.map { it.id })
        assertEquals(emptyList(), ledger.linkedObservations("conversation-a").evidenceIds)
        assertEquals(initial, ledger.snapshot())
        ledger.review(opinion.id, exact.id, EvidenceRelation.Relevant, "For reconsideration.")
        val restored = CollectiveEvidenceLedger(settings) { 200L }
        assertEquals(listOf(exact.id), restored.linkedObservations("conversation-a").evidenceIds)
        assertEquals(listOf(other.id), restored.snapshot().reconsiderationCandidates(opinion.id).map { it.id })
        assertEquals(EvidenceRelation.Relevant, restored.snapshot().reviews.single().relation)
        restored.forget(exact.id)
        assertEquals(emptyList(), ledger.linkedObservations("conversation-a").evidenceIds)
    }

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
