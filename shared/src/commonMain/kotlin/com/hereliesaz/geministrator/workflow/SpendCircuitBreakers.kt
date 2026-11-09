package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.BlockingReason
import com.hereliesaz.geministrator.domain.PausedTaskSnapshot
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.SpendCapKind
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.domain.WorkflowSpendPause
import com.hereliesaz.geministrator.domain.isTerminal
import com.hereliesaz.geministrator.events.ProviderUsageRecorded
import com.hereliesaz.geministrator.persistence.WorkflowPersistence

/** A task parked because its role spent its token budget in this run. Raising the budget releases it. */
const val ROLE_BUDGET_SPENT: String = "ROLE_BUDGET_SPENT"

/** A task parked because its job must run on a paired device and none is reachable; the Compute place resolves it. */
const val REMOTE_COMPUTE_UNAVAILABLE_CODE: String = "remote_compute_unavailable"

/** A task parked because a keeper's spend cap stopped the whole run. Lifting the stop releases it. */
const val SPEND_CAP_STOP: String = "SPEND_CAP_STOP"

const val ONE_HOUR_MILLIS: Long = 60L * 60L * 1000L

/**
 * The keeper's spend caps, in US dollars, set in the Keeper's room (This device). Null is no cap.
 * Known cost counts. A hosted API reports tokens, not dollars, so the app prices them from its one price
 * list ([com.hereliesaz.geministrator.providers.ModelPriceTable]); a model with no price there is billed
 * but unpriced, and a cap stops the run that used it instead of counting it as free. Providers that are
 * free (on-device, keyless) report no cost and never trip a cap.
 */
data class SpendCaps(
    val runCostCapUsd: Double? = null,
    val hourlySpendCapUsd: Double? = null,
) {
    init {
        require(runCostCapUsd == null || runCostCapUsd > 0.0) { "Run cost cap must be positive" }
        require(hourlySpendCapUsd == null || hourlySpendCapUsd > 0.0) { "Hourly spend cap must be positive" }
    }
}

/**
 * Which cap tripped, the cap, and the known spend that reached it. [unknownCost] is a stop for usage that
 * was billed but could not be priced, with [spentUsd] the known part.
 */
data class SpendCapTrip(
    val kind: SpendCapKind,
    val capUsd: Double,
    val spentUsd: Double,
    val unknownCost: Boolean = false,
)

/** The cost providers have reported (or the app priced) for this run. Unpriced usage adds nothing here: see [hasUnpricedUsage]. */
fun WorkflowRun.knownCostUsd(): Double = taskRuns.values.sumOf { it.usage?.costUsd ?: 0.0 }

/** Whether some of this run's usage was billed but could not be priced, so [knownCostUsd] is only a floor. */
fun WorkflowRun.hasUnpricedUsage(): Boolean = taskRuns.values.any { it.usage?.costUnknown == true }

/** Tokens reported for every task [roleId] has worked in this run: the role's spend against its budget. */
fun WorkflowRun.roleTokensSpent(roleId: RoleDefinitionId): Long =
    taskRuns.values.filter { it.assignedRoleId == roleId }.sumOf { it.usage?.totalTokens ?: 0L }

/** Whether [taskRun] is parked because its role spent its token budget. */
fun TaskRun.isHeldForRoleBudget(): Boolean =
    status == TaskRunStatus.Blocked && blockingReason?.code == ROLE_BUDGET_SPENT

/** The run-cost cap's trip for [run], or null while its known cost is under the cap (or no cap is set). */
fun runCostTrip(run: WorkflowRun, caps: SpendCaps): SpendCapTrip? {
    val cap = caps.runCostCapUsd ?: return null
    val spent = run.knownCostUsd()
    if (spent >= cap) return SpendCapTrip(SpendCapKind.RunCost, cap, spent)
    // Spend that could not be priced is not free: a cap that cannot count it stops the run (the keeper can lift that).
    if (run.hasUnpricedUsage() && !run.unpricedCostAccepted) return SpendCapTrip(SpendCapKind.RunCost, cap, spent, unknownCost = true)
    return null
}

/**
 * Known cost reported in the hour before [nowEpochMillis] across every run in [persistence], plus
 * [alsoRunId] (the run being cycled, in case it is not stored yet) and [ambient] (spend outside any run).
 */
suspend fun hourlySpendUsd(
    persistence: WorkflowPersistence,
    nowEpochMillis: Long,
    alsoRunId: WorkflowRunId? = null,
    ambient: AmbientSpendLedger? = null,
): Double = hourlySpend(persistence, nowEpochMillis, alsoRunId, ambient).knownUsd

/**
 * [hourlySpendUsd] that also says whether any usage in that hour was billed but unpriced. Only the runs
 * changed in the hour are read, and from each only the events of that hour (a range query on a SQL store).
 * A run whose keeper accepted unpriced cost ([WorkflowRun.unpricedCostAccepted]) does not make the hour unpriced.
 */
suspend fun hourlySpend(
    persistence: WorkflowPersistence,
    nowEpochMillis: Long,
    alsoRunId: WorkflowRunId? = null,
    ambient: AmbientSpendLedger? = null,
): HourlySpend {
    val since = nowEpochMillis - ONE_HOUR_MILLIS
    val runIds = linkedSetOf<WorkflowRunId>()
    alsoRunId?.let(runIds::add)
    // A run that recorded cost in the last hour was updated in the last hour.
    val accepted = mutableSetOf<WorkflowRunId>()
    persistence.runsUpdatedSince(since).forEach { run ->
        runIds += run.id
        if (run.unpricedCostAccepted) accepted += run.id
    }
    var known = 0.0
    var unpriced = false
    for (runId in runIds) {
        persistence.eventsSince(runId, since)
            .asSequence()
            .filterIsInstance<ProviderUsageRecorded>()
            .filter { it.occurredAtEpochMillis in since..nowEpochMillis }
            .forEach { event ->
                known += event.costUsd ?: 0.0
                if (event.costUnknown && runId !in accepted) unpriced = true
            }
    }
    val outside = ambient?.hourlySpend(nowEpochMillis) ?: HourlySpend(0.0, false)
    return HourlySpend(known + outside.knownUsd, unpriced || outside.unpricedUsage)
}

/**
 * [run] stopped by [trip]: every non-terminal task parked in Blocked with its state kept, so
 * [liftSpendPause] can put it back. [cancelledTaskIds] had a live provider session that was
 * cancelled for the stop; they resume as a fresh attempt.
 */
fun pauseRunForSpendCap(
    run: WorkflowRun,
    trip: SpendCapTrip,
    cancelledTaskIds: Set<TaskDefinitionId>,
    nowEpochMillis: Long,
): WorkflowRun {
    require(run.spendPause == null) { "Workflow ${run.id.value} is already stopped by a spend cap" }
    val reason = spendStopReason(trip)
    val snapshots = run.taskRuns.values
        .filterNot { it.status.isTerminal() }
        .map { taskRun ->
            PausedTaskSnapshot(
                taskDefinitionId = taskRun.taskDefinitionId,
                status = taskRun.status,
                blockingReason = taskRun.blockingReason,
                progress = taskRun.progress,
                progressMessage = taskRun.progressMessage,
                resumeAsRetry = taskRun.taskDefinitionId in cancelledTaskIds,
            )
        }
    val parked = run.taskRuns.mapValues { (_, taskRun) ->
        if (taskRun.status.isTerminal()) taskRun else taskRun.copy(
            status = TaskRunStatus.Blocked,
            blockingReason = BlockingReason(SPEND_CAP_STOP, reason),
        )
    }
    return run.copy(
        status = WorkflowRunStatus.AwaitingHuman,
        taskRuns = parked,
        spendPause = WorkflowSpendPause(
            kind = trip.kind,
            reason = reason,
            capUsd = trip.capUsd,
            spentUsd = trip.spentUsd,
            taskSnapshots = snapshots,
            pausedAtEpochMillis = nowEpochMillis,
            unknownCost = trip.unknownCost,
        ),
        updatedAtEpochMillis = nowEpochMillis,
    )
}

/**
 * Lifts a spend stop: every parked task returns to the state it was stopped in, and a task whose
 * session was cancelled is dispatched again as a new attempt. If the cap is still reached, the
 * runtime stops the run again at its next check.
 */
fun liftSpendPause(run: WorkflowRun, nowEpochMillis: Long): WorkflowRun {
    val pause = run.spendPause ?: return run
    val snapshots = pause.taskSnapshots.associateBy { it.taskDefinitionId }
    val restored = run.taskRuns.mapValues { (taskId, taskRun) ->
        val snapshot = snapshots[taskId] ?: return@mapValues taskRun
        if (snapshot.resumeAsRetry) {
            taskRun.copy(
                status = TaskRunStatus.Retrying,
                attempt = taskRun.attempt + 1,
                heldAttempts = taskRun.heldAttempts + 1,
                assignedProviderId = null,
                providerRunId = null,
                externalRunId = null,
                blockingReason = null,
                progress = null,
                progressMessage = "Resuming after a spend cap stop",
                providerPlan = null,
            )
        } else {
            taskRun.copy(
                status = snapshot.status,
                blockingReason = snapshot.blockingReason,
                progress = snapshot.progress,
                progressMessage = snapshot.progressMessage,
            )
        }
    }
    val status = when {
        restored.values.isNotEmpty() && restored.values.all { it.status == TaskRunStatus.Completed } ->
            WorkflowRunStatus.Completed
        restored.values.any { it.status == TaskRunStatus.AwaitingApproval || it.status == TaskRunStatus.Escalated } ->
            WorkflowRunStatus.AwaitingHuman
        else -> WorkflowRunStatus.Running
    }
    return run.copy(
        status = status,
        taskRuns = restored,
        spendPause = null,
        // Lifting a stop for unpriced spend is the keeper's word that the run may go on without counting it.
        unpricedCostAccepted = run.unpricedCostAccepted || pause.unknownCost,
        updatedAtEpochMillis = nowEpochMillis,
    )
}

private fun spendStopReason(trip: SpendCapTrip): String = if (trip.unknownCost) {
    val scope = if (trip.kind == SpendCapKind.RunCost) "this run" else "the last hour"
    "A model used in $scope has no known price, so its cost cannot be counted against the cap " +
        "(${formatUsd(trip.spentUsd)} counted so far of ${formatUsd(trip.capUsd)}). " +
        "Lift the stop to go on without counting it, or use a creature's token budget instead."
} else when (trip.kind) {
    SpendCapKind.RunCost ->
        "This run's known cost reached its cap: ${formatUsd(trip.spentUsd)} of ${formatUsd(trip.capUsd)}."
    SpendCapKind.HourlySpend ->
        "Known spend in the last hour reached the hourly cap: ${formatUsd(trip.spentUsd)} of ${formatUsd(trip.capUsd)}."
}

/** Dollars to the cent, without platform formatting (common code runs on JS and Wasm too). */
fun formatUsd(amount: Double): String {
    val cents = kotlin.math.round(amount * 100.0).toLong()
    val sign = if (cents < 0) "-" else ""
    val abs = kotlin.math.abs(cents)
    return sign + "\$" + (abs / 100).toString() + "." + (abs % 100).toString().padStart(2, '0')
}
