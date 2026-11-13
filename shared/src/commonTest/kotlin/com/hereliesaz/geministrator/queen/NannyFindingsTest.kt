package com.hereliesaz.geministrator.queen

import com.hereliesaz.geministrator.HallMonitorReportJson
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.HallMonitorAction
import com.hereliesaz.geministrator.domain.HallMonitorEvidence
import com.hereliesaz.geministrator.domain.HallMonitorFinding
import com.hereliesaz.geministrator.domain.HallMonitorReport
import com.hereliesaz.geministrator.domain.HallMonitorRole
import com.hereliesaz.geministrator.domain.HallMonitorScope
import com.hereliesaz.geministrator.domain.HallMonitorSolution
import com.hereliesaz.geministrator.domain.HallMonitorTestDesign
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.workflow.HALL_MONITOR_REPORT_ID_METADATA
import com.hereliesaz.geministrator.workflow.SpendCaps
import com.hereliesaz.geministrator.workflow.improvement.ImprovementSource
import com.hereliesaz.geministrator.workflow.improvement.ProposalStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NannyFindingsTest {
    private val nannyRole = HallMonitorRole.id
    private val definition = qDefinition(qTask("work"), qTask("nanny", nannyRole))

    private fun finding(id: String, subject: String, vararg actions: HallMonitorAction) = HallMonitorFinding(
        id = id, scope = HallMonitorScope.Workflow, subject = subject, observation = "Observation $id.",
        evidence = listOf(HallMonitorEvidence("c", "src", "w", "p")),
        counterEvidence = listOf(HallMonitorEvidence("c", "src", "w", "p")),
        falsificationCriteria = listOf("f"),
        solutions = actions.map { HallMonitorSolution("Solution $it", it, "t", "r", listOf("tr"), listOf("v")) },
    )

    private fun run(vararg findings: HallMonitorFinding, status: WorkflowRunStatus = WorkflowRunStatus.Running): com.hereliesaz.geministrator.domain.WorkflowRun {
        val test = HallMonitorTestDesign("n", "h", "f", listOf("c"), listOf("m"), listOf("e"))
        val json = HallMonitorReportJson.encodeToString(
            HallMonitorReport.serializer(),
            HallMonitorReport("rep", "Title", 1, 2, findings.toList(), listOf(test), listOf(test), "sum"),
        )
        val artifact = ArtifactRef(
            id = ArtifactId("report"), kind = ArtifactKind.HallMonitorReport, taskRunId = TaskRunId("r-nanny"),
            label = "report", textContent = json, metadata = mapOf(HALL_MONITOR_REPORT_ID_METADATA to "rep"), createdAtEpochMillis = 10,
        )
        return qRun(
            qTaskRun("work", TaskRunStatus.Running),
            qTaskRun("nanny", TaskRunStatus.Completed, role = nannyRole, artifacts = listOf(artifact)),
            status = status,
        )
    }

    private val reroute = arrayOf(HallMonitorAction.Reroute, HallMonitorAction.Rewrite)

    @Test
    fun rerouteOfANamedStepBecomesAFindingWithAnAction() {
        val found = nannyFindings(definition, run(finding("f1", "work", *reroute)))
        assertEquals(QueenAction.SwapSource, found.single().action)
        assertEquals("work", found.single().taskId)
    }

    @Test
    fun otherFindingsHaveNoActionAndNoCandidate() {
        val r = run(finding("f1", "work", HallMonitorAction.Rewrite, HallMonitorAction.Split), finding("f2", "elsewhere", *reroute))
        val found = nannyFindings(definition, r)
        assertTrue(found.all { it.action == null })
        assertNull(found.last().taskId)
        assertTrue(nannyCandidates(definition, r).isEmpty())
    }

    @Test
    fun noNannyReportGivesNothing() {
        assertTrue(nannyFindings(definition, qRun(qTaskRun("work", TaskRunStatus.Running))).isEmpty())
    }

    @Test
    fun handOffIsACandidateNeverAnAppliedChange() {
        val r = run(finding("f1", "work", *reroute))
        val candidate = nannyCandidates(definition, r).single()
        assertEquals(QueenAction.SwapSource, candidate.action)
        assertEquals(QueenVoice.SuggestNannyFinding, candidate.voice)
        assertEquals(listOf("1", "Task work"), candidate.args)
        // The menu action only leads to the creature's Mind: the queen applies nothing from it.
        assertTrue(!candidate.action.appliesHere)
        val queenSees = queenCandidates(definition, r, emptyList(), SpendCaps())
        assertTrue(queenSees.any { it.voice == QueenVoice.SuggestNannyFinding })
    }

    @Test
    fun handOffGoesThroughTheQueensPacing() {
        val r = run(finding("f1", "work", *reroute))
        val made = queenNewProposals(definition, r, nannyCandidates(definition, r), emptyList(), QueenLevel.Quiet, 1_000_000L)
        assertEquals(1, made.size)
        assertEquals(ImprovementSource.Queen, made.single().source)
        assertEquals(ProposalStatus.Proposed, made.single().status)
    }

    @Test
    fun unusedFindingsAreCarriedOnceAfterTheRunEnds() {
        val ended = run(finding("f1", "work", *reroute), finding("f2", "elsewhere", HallMonitorAction.Rewrite, HallMonitorAction.Split), status = WorkflowRunStatus.Completed)
        val carried = nannyCarryForward(definition, ended, emptyList(), 5L)
        assertEquals(2, carried.size)
        assertTrue(carried.all { it.source == ImprovementSource.Nanny && it.status == ProposalStatus.Proposed })
        assertEquals("work", carried.first().target)
        // Carried once: the ledger already holds them.
        assertTrue(nannyCarryForward(definition, ended, carried, 6L).isEmpty())
    }

    @Test
    fun nothingIsCarriedWhileTheRunGoesOn() {
        assertTrue(nannyCarryForward(definition, run(finding("f1", "work", *reroute)), emptyList(), 5L).isEmpty())
    }

    @Test
    fun aFindingTheKeeperAdoptedThroughTheQueenIsNotCarried() {
        val r = run(finding("f1", "work", *reroute), status = WorkflowRunStatus.Completed)
        val offered = queenNewProposals(definition, r.copy(status = WorkflowRunStatus.Running), nannyCandidates(definition, r), emptyList(), QueenLevel.Quiet, 1_000_000L)
        val adopted = offered.map { it.copy(status = ProposalStatus.Adopted) }
        assertTrue(nannyCarryForward(definition, r, adopted, 5L).isEmpty())
    }
}
