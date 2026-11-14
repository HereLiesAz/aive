package com.hereliesaz.geministrator.queen

import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrainingSuggestionTest {
    private fun role(id: String, parents: List<String> = emptyList(), enabled: Boolean = true, hatched: String? = null) =
        RoleDefinition(
            RoleDefinitionId(id), id, "d", "i",
            enabled = enabled, parents = parents.map { RoleDefinitionId(it) }, hatchedFromRunId = hatched,
        )

    private fun uses(roleId: String, domain: String, count: Int, runs: Int) =
        (0 until count).map { RoleUse(roleId, "run-${it % runs}", domain) }

    @Test
    fun repeatedUseInOneDomainSuggestsTraining() {
        val found = trainingCandidates(uses("qa", "p1", 3, 2), listOf(role("qa")))
        assertEquals(1, found.size)
        assertEquals(QueenAction.TrainSpecialist, found.single().action)
        assertEquals("qa", found.single().subject)
        assertEquals(listOf("qa", "3"), found.single().args)
    }

    @Test
    fun useSpreadAcrossDomainsOrTooFewRunsOrTooFewUsesSuggestsNothing() {
        val qa = listOf(role("qa"))
        assertTrue(trainingCandidates(uses("qa", "p1", 2, 2) + uses("qa", "p2", 2, 2), qa).isEmpty())
        assertTrue(trainingCandidates(uses("qa", "p1", 6, 1), qa).isEmpty())
        assertTrue(trainingCandidates(uses("qa", "p1", 2, 2), qa).isEmpty())
        assertTrue(trainingCandidates(emptyList(), qa).isEmpty())
    }

    @Test
    fun onlyAGenericRoleWithoutASpecialistQualifies() {
        val spec = role("qa-web", parents = listOf("qa"), hatched = "run-x")
        assertTrue(trainingCandidates(uses("qa", "p1", 6, 4), listOf(role("qa"), spec)).isEmpty())
        assertTrue(trainingCandidates(uses("qa-web", "p1", 6, 4), listOf(role("qa"), spec)).isEmpty())
        assertTrue(trainingCandidates(uses("qa", "p1", 6, 4), listOf(role("qa", enabled = false))).isEmpty())
        assertTrue(trainingCandidates(uses("gone", "p1", 6, 4), listOf(role("qa"))).isEmpty())
        assertTrue(trainingCandidates(uses("qa-draft", "p1", 6, 4), listOf(role("qa-draft"))).isEmpty())
    }

    @Test
    fun mostUsedRoleComesFirst() {
        val roles = listOf(role("a"), role("b"))
        val found = trainingCandidates(uses("a", "p", 3, 2) + uses("b", "p", 9, 3), roles)
        assertEquals(listOf("b", "a"), found.map { it.subject })
    }

    @Test
    fun acceptingNeverAppliesItAndTheKeeperAlwaysDecidesAdoption() {
        assertTrue(!QueenAction.TrainSpecialist.appliesHere)
        assertTrue(needsKeeper(QueenAction.TrainSpecialist.keeperSituation()!!) != null)
    }

    @Test
    fun thresholdsAreThreeUsesOverTwoRuns() {
        assertEquals(3, TRAIN_SUGGEST_MIN_USES)
        assertEquals(2, TRAIN_SUGGEST_MIN_RUNS)
    }

    @Test
    fun theSuggestionLinksToTheRolesLibraryEntryNotTheRoster() {
        val candidate = trainingCandidates(uses("qa", "p1", 3, 2), listOf(role("qa"))).single()
        val run = qRun(qTaskRun("a", TaskRunStatus.Running))
        val proposal = queenNewProposals(qDefinition(qTask("a")), run, listOf(candidate), emptyList(), QueenLevel.Quiet, 1L)
        val note = queenSuggestionNotes(proposal, run).single()
        assertEquals(QueenLink.Library("qa"), note.link)
        assertEquals("role:qa", (note.link as QueenLink.Library).entryKey)
    }

    @Test
    fun trainingIsOfferedWhileRunningAndJustAfterAFinishButNothingElseIsOfferedAfterwards() {
        val definition = qDefinition(qTask("a"))
        val usage = uses("qa", "p1", 3, 2)
        val roles = listOf(role("qa"))
        val running = qRun(qTaskRun("a", TaskRunStatus.Running))
        val done = qRun(qTaskRun("a", TaskRunStatus.Completed), status = WorkflowRunStatus.Completed)
        val failed = qRun(qTaskRun("a", TaskRunStatus.Failed), status = WorkflowRunStatus.Failed)
        fun trainingFor(run: com.hereliesaz.geministrator.domain.WorkflowRun) =
            queenCandidates(definition, run, emptyList(), com.hereliesaz.geministrator.workflow.SpendCaps(), roles, usage).map { it.action }
        assertEquals(listOf(QueenAction.TrainSpecialist), trainingFor(running))
        assertEquals(listOf(QueenAction.TrainSpecialist), trainingFor(done))
        assertTrue(trainingFor(failed).isEmpty())
    }

    @Test
    fun theTrainingRowSurvivesAtTheEndAndIsShownAtQuietBesideTheFinishNote() {
        val definition = qDefinition(qTask("a"))
        val done = qRun(qTaskRun("a", TaskRunStatus.Completed), status = WorkflowRunStatus.Completed)
        val candidate = trainingCandidates(uses("qa", "p1", 3, 2), listOf(role("qa"))).single()
        val proposal = queenNewProposals(definition, done, listOf(candidate), emptyList(), QueenLevel.Quiet, 1L)
        assertEquals(1, proposal.size)
        assertFalse(queenIsMoot(proposal.single(), definition, done, listOf(candidate)))
        val notes = queenUpdate(definition, done, emptyList(), QueenLevel.Quiet, suggestions = queenSuggestionNotes(proposal, done))
        assertTrue(notes.any { it.voice == QueenVoice.SuggestTrainSpecialist })
        assertTrue(notes.any { it.voice.name.startsWith("Finished") })
    }
}
