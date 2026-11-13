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
import com.hereliesaz.geministrator.domain.TaskContract
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.TemplateRef
import com.hereliesaz.geministrator.workflow.HALL_MONITOR_REPORT_ID_METADATA
import com.hereliesaz.geministrator.workflow.SpendCaps
import com.hereliesaz.geministrator.workflow.improvement.ImprovementSource
import com.hereliesaz.geministrator.workflow.improvement.ProposalStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Nanny's mid-run finding about a template: the queen decides on the data, and delivers it only when the keeper says go ahead. */
class NannyTemplateHandoffTest {
    private val nannyRole = HallMonitorRole.id

    private fun templated(id: String) =
        qTask(id).copy(contract = TaskContract(template = TemplateRef("write-document", 1)))

    private fun definitionOf(steps: Int) =
        qDefinition(*((1..steps).map { templated("doc$it") } + qTask("nanny", nannyRole)).toTypedArray())

    private fun finding(subject: String, action: HallMonitorAction, wording: String) = HallMonitorFinding(
        id = "f1", scope = HallMonitorScope.Workflow, subject = subject, observation = "Documents came back without headings.",
        evidence = listOf(HallMonitorEvidence("c", "src", "w", "p")),
        counterEvidence = listOf(HallMonitorEvidence("c", "src", "w", "p")),
        falsificationCriteria = listOf("f"),
        solutions = listOf(HallMonitorSolution("Reword", action, wording, "r", listOf("tr"), listOf("v"))),
    )

    private fun runOf(steps: Int, finding: HallMonitorFinding): com.hereliesaz.geministrator.domain.WorkflowRun {
        val test = HallMonitorTestDesign("n", "h", "f", listOf("c"), listOf("m"), listOf("e"))
        val json = HallMonitorReportJson.encodeToString(
            HallMonitorReport.serializer(),
            HallMonitorReport("rep", "Title", 1, 2, listOf(finding), listOf(test), listOf(test), "sum"),
        )
        val artifact = ArtifactRef(
            id = ArtifactId("report"), kind = ArtifactKind.HallMonitorReport, taskRunId = TaskRunId("r-nanny"),
            label = "report", textContent = json, metadata = mapOf(HALL_MONITOR_REPORT_ID_METADATA to "rep"), createdAtEpochMillis = 10,
        )
        val docs = (1..steps).map { qTaskRun("doc$it", TaskRunStatus.Completed) }
        return qRun(*(docs + qTaskRun("nanny", TaskRunStatus.Completed, role = nannyRole, artifacts = listOf(artifact))).toTypedArray())
    }

    private val wording = "Write the document the brief asks for, with two levels of headings."
    private val subject = "template:step:write-document@1"

    @Test
    fun aQuickChangeWithEnoughEvidenceIsOfferedNowAndSaysSo() {
        val definition = definitionOf(3)
        val run = runOf(3, finding(subject, HallMonitorAction.Rewrite, wording))
        val candidate = nannyTemplateCandidates(definition, run).single()
        assertEquals(QueenAction.TemplateChange, candidate.action)
        assertEquals(QueenVoice.SuggestNannyTemplate, candidate.voice)
        assertEquals(listOf("write-document", "3"), candidate.args)
        assertTrue(queenCandidates(definition, run, emptyList(), SpendCaps()).any { it.voice == QueenVoice.SuggestNannyTemplate })
    }

    @Test
    fun thinEvidenceANewGenerationWithoutTenStepsAndANonTemplateAreNotOfferedNow() {
        assertTrue(nannyTemplateCandidates(definitionOf(2), runOf(2, finding(subject, HallMonitorAction.Rewrite, wording))).isEmpty())
        assertTrue(nannyTemplateCandidates(definitionOf(3), runOf(3, finding(subject, HallMonitorAction.Restructure, wording))).isEmpty())
        assertTrue(nannyTemplateCandidates(definitionOf(3), runOf(3, finding("doc1", HallMonitorAction.Rewrite, wording))).isEmpty())
        assertTrue(nannyTemplateCandidates(definitionOf(3), runOf(3, finding("template:step:write-document@9", HallMonitorAction.Rewrite, wording))).isEmpty())
    }

    @Test
    fun theQueensOfferGoesThroughHerPacingAndIsOnlyAnOfferUntilTheKeeperAgrees() {
        val definition = definitionOf(3)
        val run = runOf(3, finding(subject, HallMonitorAction.Rewrite, wording))
        val made = queenNewProposals(definition, run, nannyTemplateCandidates(definition, run), emptyList(), QueenLevel.Quiet, 1_000_000L).single()
        assertEquals(ImprovementSource.Queen, made.source)
        assertEquals(ProposalStatus.Proposed, made.status)
        assertEquals(QueenAction.TemplateChange.name, made.action)
        assertTrue(QueenAction.TemplateChange.appliesHere)
        val note = queenSuggestionNotes(listOf(made), run).single()
        assertEquals(QueenVoice.SuggestNannyTemplate, note.voice)
        assertNotNull(note.suggestion)
        assertNull(note.link)
    }

    @Test
    fun goingAheadMakesTheNannysRowWithTheIdTheCarryForwardUses() {
        val definition = definitionOf(3)
        val run = runOf(3, finding(subject, HallMonitorAction.Rewrite, wording))
        val row = nannyTemplateProposal(definition, run, "f1", now = 7L)!!
        assertEquals(ImprovementSource.Nanny, row.source)
        assertEquals("step:write-document@1", row.target)
        assertEquals(nannyCarriedId(nannyFindings(definition, run).single()), row.id)
        assertNull(nannyTemplateProposal(definition, run, "missing", now = 7L))
    }
}
