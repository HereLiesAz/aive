package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.HallMonitorRole
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.queen.AdoptionResult
import com.hereliesaz.geministrator.queen.DisputeOutcome
import com.hereliesaz.geministrator.queen.DisputeResolution
import com.hereliesaz.geministrator.queen.NannyRuling
import com.hereliesaz.geministrator.queen.TRAINING_OFFER_ID
import com.hereliesaz.geministrator.queen.TRAINING_REVIEW_ID
import com.hereliesaz.geministrator.queen.TrainingNext
import com.hereliesaz.geministrator.queen.TrainingRuling
import com.hereliesaz.geministrator.queen.TrainingRunInfo
import com.hereliesaz.geministrator.queen.defaultTrainingLimits
import com.hereliesaz.geministrator.queen.exchangeOnRuling
import com.hereliesaz.geministrator.queen.nextForExchange
import com.hereliesaz.geministrator.queen.reviewerFindings
import com.hereliesaz.geministrator.queen.tightenLimits
import com.hereliesaz.geministrator.queen.trainingAdoptedNote
import com.hereliesaz.geministrator.queen.trainingCarryForward
import com.hereliesaz.geministrator.queen.trainingDispute
import com.hereliesaz.geministrator.queen.trainingDisputants
import com.hereliesaz.geministrator.queen.trainingNextAfterDispute
import com.hereliesaz.geministrator.queen.trainingNotes
import com.hereliesaz.geministrator.queen.trainingResults
import com.hereliesaz.geministrator.queen.trainingRunInfo
import com.hereliesaz.geministrator.queen.trainingStepText
import com.hereliesaz.geministrator.workflow.WorkflowRuntimeState
import com.hereliesaz.geministrator.workflow.definitionFor
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

// The training round driver. After a round's review is in, it reads the run, has the Nanny rule, the Antagonist challenge once,
// the Nanny answer once and, on a remaining disagreement, the queen settle the dispute (through raiseDispute, so both creatures
// must agree to her approach, her Source is the keeper's pin or automatic, and her calls are paced and held to the keeper's spend
// caps). Then it launches the next round, stops, or offers the keeper the specialist. The queen's job is the hive: the driver
// only reads and rules on the training swarm's own results, and the keeper's approval stays the only way to an adoption.

private const val REVIEW_POLL_MILLIS = 5_000L
private const val REVIEW_POLLS = 1_440
private const val OFFER_POLL_MILLIS = 30_000L
private const val OFFER_POLLS = 20_160

private fun WorkflowRun.ended(): Boolean =
    status == WorkflowRunStatus.Completed || status == WorkflowRunStatus.Failed || status == WorkflowRunStatus.Cancelled

/** Starts the driver for the training run [runId], off the cycle. The keeper's waiting is never counted against any creature. */
internal fun ApplicationRuntime.startTrainingDriver(runId: String) {
    launchQueenWork(runId) { driveTrainingRound(runId) }
}

/** The run once the reviewer's step is complete; null when it ended without one (the keeper stopped it, or it failed) or is gone. */
private suspend fun ApplicationRuntime.awaitReview(runId: String): WorkflowRun? {
    repeat(REVIEW_POLLS) {
        val run = persistence.runs.get(WorkflowRunId(runId)) ?: return null
        if (trainingStepText(run, TRAINING_REVIEW_ID) != null) return run
        if (run.ended()) return null
        delay(REVIEW_POLL_MILLIS)
    }
    return null
}

/**
 * Cancels the training run [runId] so its offer step can never be approved: through the live run when it is the active one,
 * else by cancelling the persisted run. True when the run has ended (now or already).
 */
@OptIn(ExperimentalTime::class)
internal suspend fun ApplicationRuntime.cancelTrainingRun(runId: String): Boolean {
    if (cancelActiveRun(WorkflowRunId(runId))) return true
    val run = persistence.runs.get(WorkflowRunId(runId)) ?: return false
    if (run.ended()) return true
    val definition = persistence.definitionFor(run) ?: return false
    val project = persistence.projects.get(run.projectId) ?: return false
    return try {
        coordinator.cancel(project, definition, WorkflowRuntimeState(run), Clock.System.now().toEpochMilliseconds())
        true
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }
}

/** One round: rule, challenge, answer, settle, then refine, stop or offer. */
internal suspend fun ApplicationRuntime.driveTrainingRound(runId: String) {
    val run = awaitReview(runId) ?: return
    val definition = persistence.definitionFor(run) ?: return
    val info = trainingRunInfo(definition) ?: return
    val results = trainingResults(run)
    val findings = reviewerFindings(trainingStepText(run, TRAINING_REVIEW_ID).orEmpty())
    val limits = tightenLimits(defaultTrainingLimits(keeperHourlyCapUsd()), trainerLimitOf(run))
    val models = trainingModels()
    val mayCall = models?.mayCall ?: { true }

    val exchange = exchangeOnRuling(
        results, findings, info.round, limits,
        nanny = models?.voices?.get(HallMonitorRole.id.value),
        antagonist = models?.voices?.get(BuiltInRoles.Antagonist.id.value),
        mayCall = mayCall,
    )
    var next = nextForExchange(exchange, info.round, limits, findings)
    val disputed = (next as? TrainingNext.SettleDispute)?.disputed
    if (disputed != null) {
        // The Nanny and the Antagonist are the two creatures in dispute; the same route as any dispute (notes for Messages included).
        val resolution = raiseDispute(
            runId, trainingDispute(disputed, exchange.facts), trainingDisputants(), models?.queen, models?.voices.orEmpty(),
            launcher = null, investigated = true, mayCall = mayCall,
        )
        val option = ((resolution as? DisputeResolution.Agreed)?.outcome as? DisputeOutcome.Ruled)?.optionId
        next = trainingNextAfterDispute(option, disputed, info.round, limits, findings)
    }
    val reason = (exchange.ruling as? TrainingRuling.Ruled)?.ruling?.let(NannyRuling::reason)
    rememberDisputeNotes(runId, trainingNotes(runId, info.domain, next, results))

    when (next) {
        is TrainingNext.OfferToKeeper -> {
            trainingOffered += runId
            republishState()
            awaitKeeperThenAdopt(runId, info)
        }
        is TrainingNext.Refine -> {
            // This round is over: its offer is cancelled, so nothing it holds can be approved; the findings go to the next round and the next swarm.
            cancelTrainingRun(runId)
            carryRound(runId, info, reason, findings)
            launchTrainingSwarm(
                sourceRunId = runId, domain = info.domain, parentRoleId = info.parentRoleId, round = next.round, findings = next.findings,
                trainerLimit = trainerLimitOf(run), keeperSpendCapUsd = keeperHourlyCapUsd(),
            )
        }
        is TrainingNext.Stop, is TrainingNext.AskKeeper, is TrainingNext.SettleDispute -> {
            cancelTrainingRun(runId)
            carryRound(runId, info, reason, findings)
        }
    }
    republishState()
}

private suspend fun ApplicationRuntime.carryRound(runId: String, info: TrainingRunInfo, reason: String?, findings: List<String>) {
    val run = persistence.runs.get(WorkflowRunId(runId)) ?: return
    val definition = persistence.definitionFor(run) ?: return
    carryEndedRunFindings(definition, run) { existing, now ->
        trainingCarryForward(runId, definition.id.value, info.domain, reason, findings, existing, now)
    }
}

/**
 * After a pass was offered: waits (the keeper's waiting is not counted) until the keeper has decided the offer in Messages. Only
 * an approval adopts, through [adoptTrainedSpecialist]'s gate; a refusal or a cancelled offer adopts nothing.
 */
private suspend fun ApplicationRuntime.awaitKeeperThenAdopt(runId: String, info: TrainingRunInfo) {
    repeat(OFFER_POLLS) {
        val run = persistence.runs.get(WorkflowRunId(runId)) ?: return
        val offer = run.taskRuns.values.firstOrNull { it.taskDefinitionId.value == TRAINING_OFFER_ID }
        when (offer?.status) {
            TaskRunStatus.Completed -> {
                val result = adoptTrainedSpecialist(runId, info.parentRoleId, info.domain)
                if (result is AdoptionResult.Adopted) rememberDisputeNotes(runId, listOf(trainingAdoptedNote(runId, info.domain)))
                carryRound(runId, info, null, emptyList())
                return
            }
            TaskRunStatus.Failed, TaskRunStatus.Escalated, TaskRunStatus.Cancelled -> {
                carryRound(runId, info, null, emptyList())
                return
            }
            else -> delay(OFFER_POLL_MILLIS)
        }
    }
}
