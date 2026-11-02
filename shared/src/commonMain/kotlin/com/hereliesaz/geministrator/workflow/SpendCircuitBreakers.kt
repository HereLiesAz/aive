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

/** A task parked because a keeper's spend cap stopped the whole run. Lifting the stop releases it. */
const val SPEND_CAP_STOP: String = "SPEND_CAP_STOP"

const val ONE_HOUR_MILLIS: Long = 60L * 60L * 1000L

/**
 * The keeper's spend caps, in US dollars, set in the Keeper's room (This device). Null is no cap.
 * Only known cost counts: usage reported without a cost (keyless and free providers, or providers
 * that report tokens only) never trips a cap.
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

/** Which cap tripped, the cap, and the known spend that reached it. */
data class SpendCapTrip(
    val kind: SpendCapKind,
    val capUsd: Double,
    val spentUsd: Double,
)

/** The cost providers have reported for this run. Usage without a cost adds nothing. */
fun WorkflowRun.knownCostUsd(): Double = taskRuns.values.sumOf { it.usage?.costUsd ?: 0.0 }

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
    return if (spent >= cap) SpendCapTrip(SpendCapKind.RunCost, cap, spent) else null
}

/**
 * Known cost reported in the hour before [nowEpochMillis] across every run in [persistence], plus
 * [alsoRunId] (the run being cycled, in case it is not stored yet). Usage without a cost adds nothing.
 */
suspend fun hourlySpendUsd(
    persistence: WorkflowPersistence,
    nowEpochMillis: Long,
    alsoRunId: WorkflowRunId? = null,
): Double {
    val since = nowEpochMillis - ONE_HOUR_MILLIS
    val runIds = linkedSetOf<WorkflowRunId>()
    alsoRunId?.let(runIds::add)
    persistence.projects.all().forEach { project ->
        persistence.runs.byProject(project.id)
            // A run that recorded cost in the last hour was updated in the last hour.
            .filter { it.updatedAtEpochMillis >= since }
            .forEach { runIds += it.id }
    }
    return runIds.sumOf { runId ->
        persistence.events.forRun(runId)
            .asSequence()
            .filterIsInstance<ProviderUsageRecorded>()
            .filter { it.occurredAtEpochMillis in since..nowEpochMillis }
            .sumOf { it.costUsd ?: 0.0 }
    }
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
        updatedAtEpochMillis = nowEpochMillis,
    )
}

private fun spendStopReason(trip: SpendCapTrip): String = when (trip.kind) {
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
