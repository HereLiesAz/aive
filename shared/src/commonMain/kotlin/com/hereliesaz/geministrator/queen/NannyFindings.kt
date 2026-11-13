package com.hereliesaz.geministrator.queen

import com.hereliesaz.geministrator.decodeHallMonitorReportPayload
import com.hereliesaz.geministrator.domain.HallMonitorAction
import com.hereliesaz.geministrator.domain.HallMonitorReport
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.workflow.improvement.ImprovementKind
import com.hereliesaz.geministrator.workflow.improvement.ImprovementProposal
import com.hereliesaz.geministrator.workflow.improvement.ImprovementSource
import com.hereliesaz.geministrator.workflow.improvement.ProposalStatus

private const val NANNY_SUMMARY_CHARS = 200
private const val NANNY_EVIDENCE_CHARS = 600

/**
 * One finding of the Nanny's report, as the queen receives it. The Nanny works for the next hive or swarm; when she
 * has a finding before the swarm ends she hands it over here, as an input to the queen's own judgement. It is never
 * applied: it becomes at most a candidate for her closed suggestion menu (still paced, still decided by the keeper's
 * Go ahead or No thanks, still re-checked at accept time). Text from the report is bounded and never put in a sentence.
 */
data class NannyFinding(
    val id: String,
    val reportId: String,
    val subject: String,
    val observation: String,
    /** The step it is about, when the subject names one of the swarm's steps. */
    val taskId: String?,
    /** The suggestion the queen could make from it, or null when it can only be carried to the next run. */
    val action: QueenAction?,
    /** The titles of the solutions the Nanny proposed, for the record. */
    val solutions: List<String>,
)

/** The findings of the Nanny's newest completed report, or none (no Nanny, no report, a report that cannot be read). */
fun nannyFindings(definition: WorkflowDefinition, run: WorkflowRun): List<NannyFinding> {
    val artifact = latestReport(run) ?: return emptyList()
    val report: HallMonitorReport = artifact.textContent?.let { runCatching { decodeHallMonitorReportPayload(it) }.getOrNull() }
        ?: return emptyList()
    return report.findings.map { finding ->
        val task = definition.tasks.firstOrNull {
            it.id.value == finding.subject || it.name.trim().equals(finding.subject.trim(), ignoreCase = true)
        }
        // Only a rerouting of one named step has a place on the queen's menu (a Source change, on the creature's Mind).
        val reroute = task != null && finding.solutions.any { it.action == HallMonitorAction.Reroute }
        NannyFinding(
            id = finding.id,
            reportId = report.reportId,
            subject = finding.subject,
            observation = finding.observation,
            taskId = task?.id?.value,
            action = if (reroute) QueenAction.SwapSource else null,
            solutions = finding.solutions.map { it.title },
        )
    }
}

/** What the queen could suggest from the Nanny's findings: candidates only, which the pacing rules and the keeper decide. */
internal fun nannyCandidates(definition: WorkflowDefinition, run: WorkflowRun): List<QueenCandidate> =
    nannyFindings(definition, run).mapNotNull { finding ->
        val taskId = finding.taskId ?: return@mapNotNull null
        val action = finding.action ?: return@mapNotNull null
        val name = definition.tasks.firstOrNull { it.id.value == taskId }?.name.orEmpty()
        QueenCandidate(
            action, taskId, QueenVoice.SuggestNannyFinding,
            listOf(stepNumber(definition, taskId).toString(), queenSlot(name)),
            outsideFor(definition, run, taskId),
        )
    }

/** The ledger id of a carried finding: the same report and finding give the same id, so it is carried once. */
fun nannyCarriedId(finding: NannyFinding): String = "nanny:${finding.reportId}:${finding.id}"

/**
 * Findings the queen did not use in this run, as rows of the one improvement ledger (source Nanny), the place the next
 * hive or swarm's planning already reads (the loop's evidence lists every source). A finding is used when a queen row
 * made from it was adopted; every other one is carried, once. Pure. Meant for a run that has ended.
 */
fun nannyCarryForward(
    definition: WorkflowDefinition,
    run: WorkflowRun,
    existing: List<ImprovementProposal>,
    now: Long,
): List<ImprovementProposal> {
    if (run.status != WorkflowRunStatus.Completed && run.status != WorkflowRunStatus.Failed && run.status != WorkflowRunStatus.Cancelled) return emptyList()
    val report: HallMonitorReport? = latestReport(run)?.textContent?.let { runCatching { decodeHallMonitorReportPayload(it) }.getOrNull() }
    return nannyFindings(definition, run).mapNotNull { finding ->
        val id = nannyCarriedId(finding)
        if (existing.any { it.id == id }) return@mapNotNull null
        // A finding about a template or a role becomes a revision of its lineage, not a note.
        if (report != null && templateTargetOf(finding) != null) {
            templateProposalFor(finding, report, definition, run, now)?.let { return@mapNotNull it }
        }
        val usedId = finding.taskId?.let { t -> finding.action?.let { a -> queenSuggestionId(run, a, t) } }
        if (usedId != null && existing.any { it.id == usedId && it.status == ProposalStatus.Adopted }) return@mapNotNull null
        ImprovementProposal(
            id = id,
            cycleRunId = run.id.value,
            kind = ImprovementKind.Other,
            target = finding.taskId ?: queenText(finding.subject, 60),
            summary = queenText("Nanny: " + finding.observation, NANNY_SUMMARY_CHARS),
            newText = "",
            expectedEffect = "",
            undo = "",
            meta = false,
            createdAtEpochMillis = now,
            source = ImprovementSource.Nanny,
            sourceRef = definition.id.value,
            evidence = queenText(
                "Not used in the run that found it. Solutions: " + finding.solutions.joinToString("; ").ifBlank { "none" },
                NANNY_EVIDENCE_CHARS,
            ),
        )
    }
}
