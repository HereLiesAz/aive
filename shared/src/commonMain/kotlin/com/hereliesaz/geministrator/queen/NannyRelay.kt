package com.hereliesaz.geministrator.queen

import com.hereliesaz.geministrator.HallMonitorPauseReviewState
import com.hereliesaz.geministrator.decodeHallMonitorReportPayload
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.HallMonitorReport
import com.hereliesaz.geministrator.domain.HallMonitorRole
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.pauseInboxKey
import com.hereliesaz.geministrator.workflow.ApprovalGateStatus
import com.hereliesaz.geministrator.workflow.HALL_MONITOR_REPORT_ID_METADATA
import com.hereliesaz.geministrator.workflow.HALL_MONITOR_REVIEW_PASS
import com.hereliesaz.geministrator.workflow.HALL_MONITOR_REVIEW_VERDICT_METADATA

private const val FINDING_QUOTE_MAX = 160
private const val REASON_QUOTE_MAX = 160
private const val TITLE_QUOTE_MAX = 80
private const val MAX_PRACTICE_NOTES = 3

/** Where the Nanny's report has got, read from the run alone. */
enum class NannyPhase { None, Checking, AwaitingReview, SentBack, Rejected, Frozen }

internal fun hasNanny(definition: WorkflowDefinition, run: WorkflowRun): Boolean =
    definition.tasks.any { it.roleId == HallMonitorRole.id } || run.taskRuns.values.any { it.assignedRoleId == HallMonitorRole.id }

private fun latestReport(run: WorkflowRun): ArtifactRef? = run.taskRuns.values
    .filter { it.assignedRoleId == HallMonitorRole.id && it.status == TaskRunStatus.Completed }
    .flatMap { it.artifacts }
    .filter { it.kind == ArtifactKind.HallMonitorReport }
    .maxByOrNull { it.createdAtEpochMillis }

private fun reportId(report: ArtifactRef) = report.metadata[HALL_MONITOR_REPORT_ID_METADATA] ?: report.id.value

/** The latest Antagonist verdict on [report]; a review that names another report is not about it. */
private fun verdictOn(run: WorkflowRun, report: ArtifactRef): String? = run.taskRuns.values
    .filter { it.assignedRoleId == BuiltInRoles.Antagonist.id && it.status == TaskRunStatus.Completed }
    .flatMap { it.artifacts }
    .filter { it.kind == ArtifactKind.HallMonitorReview || it.kind == ArtifactKind.Review }
    .filter { it.metadata[HALL_MONITOR_REPORT_ID_METADATA].let { id -> id == null || id == reportId(report) } }
    .filter { it.createdAtEpochMillis >= report.createdAtEpochMillis }
    .maxByOrNull { it.createdAtEpochMillis }
    ?.metadata?.get(HALL_MONITOR_REVIEW_VERDICT_METADATA)?.trim()?.lowercase()

/** The phase of the Nanny's work in [run]; [NannyPhase.None] when the swarm has no Nanny. */
fun nannyPhase(definition: WorkflowDefinition, run: WorkflowRun): NannyPhase {
    if (!hasNanny(definition, run)) return NannyPhase.None
    if (run.globalPause != null) return NannyPhase.Frozen
    val working = run.taskRuns.values.any {
        it.assignedRoleId == HallMonitorRole.id &&
            it.status in setOf(TaskRunStatus.Running, TaskRunStatus.Planning, TaskRunStatus.Verifying)
    }
    if (working) return NannyPhase.Checking
    val report = latestReport(run) ?: return NannyPhase.None
    return when (verdictOn(run, report)) {
        null -> NannyPhase.AwaitingReview
        HALL_MONITOR_REVIEW_PASS -> NannyPhase.None
        "reject" -> NannyPhase.Rejected
        else -> NannyPhase.SentBack
    }
}

/** The first reason line of the queen's own review, when it gives a verdict. */
internal fun queenVerdict(review: String?): Pair<Boolean, String?>? {
    val lines = review?.lineSequence()?.map { it.trim() }?.filter { it.isNotEmpty() }?.toList().orEmpty()
    val first = lines.firstOrNull() ?: return null
    if (!first.startsWith("VERDICT:", ignoreCase = true)) return null
    val value = first.substringAfter(':').trim().uppercase()
    val rest = lines.drop(1).firstOrNull()
    return when (value) {
        "APPROVE" -> true to rest
        "REJECT" -> false to rest
        else -> null
    }
}

private fun decodedReport(run: WorkflowRun, review: HallMonitorPauseReviewState?): HallMonitorReport? {
    val pause = run.globalPause ?: return null
    val text = review?.reportText?.takeIf { it.isNotBlank() }
        ?: run.taskRuns.values.flatMap { it.artifacts }.firstOrNull { it.id == pause.reportArtifactId }?.textContent
        ?: return null
    return runCatching { decodeHallMonitorReportPayload(text) }.getOrNull()
}

/**
 * The queen relaying the Nanny, in her own voice. Silent without a Nanny step. Before a freeze the
 * notes are rows of their own; while frozen they are sentences on the freeze row, since the freeze
 * is the only row. Finding and review text is quoted on its own line and never put in a sentence.
 */
fun nannyRelay(definition: WorkflowDefinition, run: WorkflowRun, review: HallMonitorPauseReviewState? = null): List<QueenNote> {
    if (run.status == WorkflowRunStatus.Completed || run.status == WorkflowRunStatus.Failed || run.status == WorkflowRunStatus.Cancelled) return emptyList()
    val id = run.id.value
    return when (nannyPhase(definition, run)) {
        NannyPhase.None, NannyPhase.Checking -> emptyList()
        NannyPhase.AwaitingReview -> listOf(QueenNote(queenNoteId(id, "nanny", "report", latestReport(run)?.id?.value.orEmpty()), QueenVoice.NannyReport))
        NannyPhase.SentBack -> listOf(QueenNote(queenNoteId(id, "nanny", "revise", latestReport(run)?.id?.value.orEmpty()), QueenVoice.NannyRevise, link = QueenLink.Story))
        NannyPhase.Rejected -> listOf(QueenNote(queenNoteId(id, "nanny", "rejected", latestReport(run)?.id?.value.orEmpty()), QueenVoice.NannyRejected, link = QueenLink.Story))
        NannyPhase.Frozen -> frozenRelay(run, review)
    }
}

private fun frozenRelay(run: WorkflowRun, review: HallMonitorPauseReviewState?): List<QueenNote> {
    val pause = run.globalPause ?: return emptyList()
    val row = pauseInboxKey(run, pause)
    val id = run.id.value
    val report = decodedReport(run, review)
    val notes = mutableListOf<QueenNote>()
    val top = report?.findings?.firstOrNull()
    notes += QueenNote(
        queenNoteId(id, "nanny", "froze", pause.reportArtifactId.value),
        QueenVoice.NannyFroze,
        attachedTo = row,
        quotes = top?.let { listOf(QueenQuote(QueenQuoteLabel.NannyFinding, queenQuoteText(it.observation, FINDING_QUOTE_MAX))) }.orEmpty(),
    )
    val more = (report?.findings?.size ?: 1) - 1
    if (more > 0) {
        notes += QueenNote(queenNoteId(id, "nanny", "more", more.toString()), QueenVoice.NannyMoreFindings, listOf(more.toString()), attachedTo = row)
    }
    if (review != null) {
        notes += when (review.orchestratorStatus) {
            ApprovalGateStatus.Pending, ApprovalGateStatus.Applying ->
                QueenNote(queenNoteId(id, "queen", "reading"), QueenVoice.QueenReading, attachedTo = row)
            ApprovalGateStatus.Approved ->
                QueenNote(queenNoteId(id, "queen", "approves"), QueenVoice.QueenApproves, attachedTo = row)
            ApprovalGateStatus.Rejected -> {
                val verdict = queenVerdict(review.orchestratorReviewText)
                if (verdict != null && !verdict.first) {
                    QueenNote(
                        queenNoteId(id, "queen", "rejects"),
                        QueenVoice.QueenRejects,
                        attachedTo = row,
                        quotes = verdict.second?.let { listOf(QueenQuote(QueenQuoteLabel.QueenReason, queenQuoteText(it, REASON_QUOTE_MAX))) }.orEmpty(),
                    )
                } else {
                    QueenNote(queenNoteId(id, "queen", "failed"), QueenVoice.QueenCouldNotRead, attachedTo = row)
                }
            }
        }
        review.solutionTrials.takeLast(MAX_PRACTICE_NOTES).forEach { trial ->
            val voice = when (trial.status) {
                WorkflowRunStatus.Completed -> QueenVoice.PracticeDone
                WorkflowRunStatus.Failed, WorkflowRunStatus.Cancelled -> QueenVoice.PracticeEnded
                else -> QueenVoice.PracticeRunning
            }
            notes += QueenNote(
                queenNoteId(id, "practice", trial.id, voice.name),
                voice,
                link = QueenLink.Run(trial.workflowRunId.value),
                attachedTo = row,
                quotes = listOf(QueenQuote(QueenQuoteLabel.PracticeSolution, queenQuoteText(trial.solutionTitle, TITLE_QUOTE_MAX))),
            )
        }
        if (!review.userStatus.let { it == ApprovalGateStatus.Approved || it == ApprovalGateStatus.Rejected }) {
            notes += QueenNote(queenNoteId(id, "nanny", "decide"), QueenVoice.NannyYouDecide, attachedTo = row)
        }
    }
    return notes
}
