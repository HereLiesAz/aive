package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.HallMonitorAction
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.orchestration.OrchestrationAgentRuntime
import com.hereliesaz.geministrator.workflow.ApprovalGateStatus
import com.hereliesaz.geministrator.workflow.HallMonitorGovernanceService
import com.hereliesaz.geministrator.workflow.HallMonitorSolutionTrialService
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

data class HallMonitorTestableSolution(
    val findingId: String,
    val findingSubject: String,
    val solutionIndex: Int,
    val title: String,
    val action: HallMonitorAction,
    val target: String,
    val rationale: String,
    val tradeoffs: List<String>,
    val validationTests: List<String>,
)

data class HallMonitorSolutionTrialState(
    val id: String,
    val findingId: String,
    val solutionIndex: Int,
    val solutionTitle: String,
    val workflowRunId: WorkflowRunId,
    val status: WorkflowRunStatus?,
    val completedTasks: Int,
    val totalTasks: Int,
    val artifactCount: Int,
    val progress: Float?,
)

data class HallMonitorPauseReviewState(
    val reason: String,
    val reportText: String,
    val antagonistReviewText: String,
    val orchestratorStatus: ApprovalGateStatus,
    val orchestratorReviewText: String?,
    val userStatus: ApprovalGateStatus,
    val userDecisionNote: String?,
    val testableSolutions: List<HallMonitorTestableSolution> = emptyList(),
    val solutionTrials: List<HallMonitorSolutionTrialState> = emptyList(),
    val solutionParsingError: String? = null,
) {
    val bothReviewsResolved: Boolean
        get() = orchestratorStatus.isResolved && userStatus.isResolved

    val recommendationsApproved: Boolean
        get() = orchestratorStatus == ApprovalGateStatus.Approved && userStatus == ApprovalGateStatus.Approved

    private val ApprovalGateStatus.isResolved: Boolean
        get() = this == ApprovalGateStatus.Approved || this == ApprovalGateStatus.Rejected
}

/** Manual entry point retained for diagnostics; normal Hall Monitor workflows auto-open the pause. */
suspend fun ApplicationRuntime.pauseForHallMonitorReview(
    reportArtifactId: ArtifactId,
    antagonistReviewArtifactId: ArtifactId,
    nowEpochMillis: Long,
) {
    val live = state.value as? ApplicationRuntimeState.Live ?: error("No active workflow is loaded")
    val resumed = coordinator.resume(live.presentation.run.id)
    HallMonitorGovernanceService(persistence).pauseAfterAntagonistPass(
        state = resumed,
        reportArtifactId = reportArtifactId,
        antagonistReviewArtifactId = antagonistReviewArtifactId,
        sessionGateway = sessionGateway,
        nowEpochMillis = nowEpochMillis,
    )
    refresh()
}

@OptIn(ExperimentalTime::class)
suspend fun ApplicationRuntime.pauseForHallMonitorReview(
    reportArtifactId: ArtifactId,
    antagonistReviewArtifactId: ArtifactId,
) = pauseForHallMonitorReview(
    reportArtifactId = reportArtifactId,
    antagonistReviewArtifactId = antagonistReviewArtifactId,
    nowEpochMillis = Clock.System.now().toEpochMilliseconds(),
)

suspend fun ApplicationRuntime.loadHallMonitorPauseReviewState(): HallMonitorPauseReviewState? {
    val live = state.value as? ApplicationRuntimeState.Live ?: return null
    val pause = live.presentation.run.globalPause ?: return null
    val report = persistence.artifacts.get(pause.reportArtifactId)
    val antagonist = persistence.artifacts.get(pause.antagonistReviewArtifactId)
    val orchestratorGate = persistence.approvalGates.get(pause.orchestratorGateId) ?: return null
    val humanGate = persistence.approvalGates.get(pause.humanGateId) ?: return null

    val parsed = runCatching { decodeHallMonitorReportPayload(report?.textContent.orEmpty()) }
    val solutions = parsed.getOrNull()?.findings.orEmpty().flatMap { finding ->
        finding.solutions.mapIndexed { index, solution ->
            HallMonitorTestableSolution(
                findingId = finding.id,
                findingSubject = finding.subject,
                solutionIndex = index,
                title = solution.title,
                action = solution.action,
                target = solution.target,
                rationale = solution.rationale,
                tradeoffs = solution.tradeoffs,
                validationTests = solution.validationTests,
            )
        }
    }
    val trials = pause.solutionTrials.map { trial ->
        val trialRun = persistence.runs.get(trial.workflowRunId)
        val taskRuns = trialRun?.taskRuns?.values.orEmpty()
        val progressValues = taskRuns.mapNotNull { it.progress }
        HallMonitorSolutionTrialState(
            id = trial.id,
            findingId = trial.findingId,
            solutionIndex = trial.solutionIndex,
            solutionTitle = trial.solutionTitle,
            workflowRunId = trial.workflowRunId,
            status = trialRun?.status,
            completedTasks = taskRuns.count { it.status == TaskRunStatus.Completed },
            totalTasks = taskRuns.size,
            artifactCount = taskRuns.sumOf { it.artifacts.size },
            progress = progressValues.takeIf { it.isNotEmpty() }?.average()?.toFloat(),
        )
    }
    return HallMonitorPauseReviewState(
        reason = pause.reason,
        reportText = report?.textContent.orEmpty(),
        antagonistReviewText = antagonist?.textContent.orEmpty(),
        orchestratorStatus = orchestratorGate.status,
        orchestratorReviewText = orchestratorGate.decisionNote,
        userStatus = humanGate.status,
        userDecisionNote = humanGate.decisionNote,
        testableSolutions = solutions,
        solutionTrials = trials,
        solutionParsingError = parsed.exceptionOrNull()?.message,
    )
}

/**
 * Launch an isolated counterfactual for one proposed Hall Monitor solution. The paused source run
 * remains paused; [loadLatest] switches the live UI to the newer trial run so the user can watch it.
 */
suspend fun ApplicationRuntime.testHallMonitorSolution(
    findingId: String,
    solutionIndex: Int,
    orchestrationRuntime: OrchestrationAgentRuntime,
    nowEpochMillis: Long,
): WorkflowRunId {
    val live = state.value as? ApplicationRuntimeState.Live ?: error("No active workflow is loaded")
    require(live.presentation.run.globalPause != null) {
        "The active workflow is not globally paused for Hall Monitor review"
    }
    val trial = HallMonitorSolutionTrialService(persistence, providerRegistry).launch(
        sourceRun = live.presentation.run,
        findingId = findingId,
        solutionIndex = solutionIndex,
        orchestrationRuntime = orchestrationRuntime,
        nowEpochMillis = nowEpochMillis,
    )
    loadLatest()
    return trial.workflowRunId
}

@OptIn(ExperimentalTime::class)
suspend fun ApplicationRuntime.testHallMonitorSolution(
    findingId: String,
    solutionIndex: Int,
    orchestrationRuntime: OrchestrationAgentRuntime,
): WorkflowRunId = testHallMonitorSolution(
    findingId = findingId,
    solutionIndex = solutionIndex,
    orchestrationRuntime = orchestrationRuntime,
    nowEpochMillis = Clock.System.now().toEpochMilliseconds(),
)

suspend fun ApplicationRuntime.decideHallMonitorUserReview(
    approved: Boolean,
    note: String? = null,
    nowEpochMillis: Long,
) {
    val live = state.value as? ApplicationRuntimeState.Live ?: error("No active workflow is loaded")
    HallMonitorGovernanceService(persistence).decideHumanReview(
        run = live.presentation.run,
        approved = approved,
        note = note,
        nowEpochMillis = nowEpochMillis,
    )
    refresh()
}

@OptIn(ExperimentalTime::class)
suspend fun ApplicationRuntime.decideHallMonitorUserReview(
    approved: Boolean,
    note: String? = null,
) = decideHallMonitorUserReview(
    approved = approved,
    note = note,
    nowEpochMillis = Clock.System.now().toEpochMilliseconds(),
)

/**
 * Resume the snapshotted workflow after both the Orchestrator and user have reviewed the report.
 * A rejection means the recommendations remain unapplied; it does not trap the original workflow.
 */
suspend fun ApplicationRuntime.resumeHallMonitorPause(nowEpochMillis: Long) {
    val live = state.value as? ApplicationRuntimeState.Live ?: error("No active workflow is loaded")
    HallMonitorGovernanceService(persistence).resumeAfterReviews(
        run = live.presentation.run,
        nowEpochMillis = nowEpochMillis,
    )
    refresh()
}

@OptIn(ExperimentalTime::class)
suspend fun ApplicationRuntime.resumeHallMonitorPause() =
    resumeHallMonitorPause(Clock.System.now().toEpochMilliseconds())
