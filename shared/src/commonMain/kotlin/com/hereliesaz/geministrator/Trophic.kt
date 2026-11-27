package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.RoleExecutionSource
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.effectiveExecutor
import com.hereliesaz.geministrator.events.ProviderUsageRecorded
import com.hereliesaz.geministrator.workflow.ArtifactVerdict

/**
 * Trophic support (docs/Ecology-and-the-Swarm.md, docs/Node-Creature-Genome.md): an agent is fed when
 * its output is used and accepted downstream, and starves when it is not. Echo counts delivery as
 * exposure; trophic support asks the stronger question, so the two are kept apart here.
 *
 * What one producer-to-consumer path can show, weakest to strongest. Each step needs its own evidence;
 * a missing report is [Unknown], never [Ignored].
 */
internal enum class TrophicOutcome {
    /** No delivery report for the consumer's session, or the consumer has not finished. Never counts either way. */
    Unknown,

    /** The consumer finished, its session reported what it was given, and the producer's artifact was not in it. */
    Ignored,

    /** The artifact reached the consumer's request, but the consumer did not complete. Delivery only. */
    Delivered,

    /** The consumer completed with it, but a reviewer's verdict on it is missing or ambiguous. Used, acceptance unproven. */
    Consumed,

    /** A non-reviewing consumer completed with it, or a reviewer stated Approve. */
    Accepted,

    /** A reviewer read it and stated Revise or Reject. Used, and turned away. */
    Rejected,
}

/**
 * Judge one path from the run's own records. A reviewer's stated verdict is read through
 * [reportedReviewVerdict], which already refuses multi-source and unattributable reviews. For a
 * non-reviewer, a completed consumer that was handed the artifact is the acceptance; a later reviewer of
 * the consumer's work does not reach back to the producer here.
 */
internal fun trophicOutcome(
    run: WorkflowRun,
    producer: TaskDefinitionId,
    consumer: TaskDefinition,
    deliveries: Map<TaskDefinitionId, Set<String>>,
    rolesById: Map<RoleDefinitionId, RoleDefinition>,
): TrophicOutcome {
    val consumerRun = run.taskRuns[consumer.id] ?: return TrophicOutcome.Unknown
    val delivered = artifactDelivered(run, producer, consumer.id, deliveries) ?: return TrophicOutcome.Unknown
    val status = consumerRun.status
    if (!delivered) {
        // Only a finished consumer that reported its inputs can show they were passed over.
        val finished = status == TaskRunStatus.Completed || status == TaskRunStatus.Failed || status == TaskRunStatus.Escalated
        return if (finished) TrophicOutcome.Ignored else TrophicOutcome.Unknown
    }
    val agent = (consumerRun.executor ?: consumer.effectiveExecutor()).isAgent
    val role = (consumerRun.assignedRoleId ?: consumer.roleId)?.let(rolesById::get)
    val reviewer = agent && role?.let { isReviewerRoleName(it.name) } == true
    if (reviewer) {
        reportedReviewVerdict(run, producer, consumer, deliveries)?.let {
            return if (it == ArtifactVerdict.Approve) TrophicOutcome.Accepted else TrophicOutcome.Rejected
        }
    }
    return when (status) {
        TaskRunStatus.Completed -> if (reviewer) TrophicOutcome.Consumed else TrophicOutcome.Accepted
        else -> TrophicOutcome.Delivered
    }
}

/** What a role's outputs earned in one run. */
internal data class TrophicRunEvidence(
    /** Paths a downstream worker completed with it. */
    val reads: Int,
    /** Paths a reviewer approved. */
    val approvals: Int,
    /** True only when every downstream path is known and none was accepted. */
    val barren: Boolean,
    /**
     * Hundredths of a point earned. A consumer's supply is limited, so it is shared among the producers
     * it accepted (see [trophicEvidence]); a sole producer gets the whole read or approval.
     */
    val credit: Int = reads * TROPHIC_READ + approvals * TROPHIC_APPROVAL,
)

/** One producer-to-consumer path of a settled run, judged. */
private data class TrophicPath(
    val role: RoleDefinitionId,
    val consumer: TaskDefinitionId,
    val outcome: TrophicOutcome,
    val reviewer: Boolean,
)

/**
 * Per role, what this run's completed agent outputs earned. A task with no planned dependents is
 * exempt: its output goes to the keeper, and no downstream path can be missing. A role whose paths are
 * all unknown is absent: no evidence, no change.
 *
 * Trophic factor is limited (docs/Node-Creature-Genome.md, Competition): a consumer releases one read's
 * worth, or one approval's if it is a reviewer, however many producers fed it, and that supply is split
 * among the producers whose output it accepted. The share is a whole number of hundredths, rounded down.
 * So redundant producers thin each other out and the weakest of them starve first, while a producer
 * that is the only one a consumer read is paid in full.
 */
internal fun trophicEvidence(run: WorkflowRun, reports: List<ProviderUsageRecorded>): Map<RoleDefinitionId, TrophicRunEvidence> {
    val definition = run.definitionSnapshot ?: return emptyMap()
    if (run.roleSnapshot.isEmpty()) return emptyMap()
    val roles = run.roleSnapshot.associateBy { it.id }
    val deliveries = reportedArtifactDeliveries(run, reports)
    val paths = mutableListOf<TrophicPath>()
    for (producer in definition.tasks) {
        val source = run.taskRuns[producer.id] ?: continue
        if (source.status != TaskRunStatus.Completed) continue
        if (!(source.executor ?: producer.effectiveExecutor()).isAgent) continue
        val role = source.assignedRoleId?.let(roles::get) ?: continue
        if (role.executionSource != RoleExecutionSource.Agent) continue
        val dependents = definition.tasks.filter { producer.id in it.dependsOn }
        for (consumer in dependents) {
            val outcome = trophicOutcome(run, producer.id, consumer, deliveries, roles)
            paths += TrophicPath(role.id, consumer.id, outcome,
                reviewer = outcome == TrophicOutcome.Accepted && isReviewerPath(run, consumer, roles))
        }
    }
    val accepted = paths.filter { it.outcome == TrophicOutcome.Accepted }.groupBy { it.consumer }
    val credit = mutableMapOf<RoleDefinitionId, Int>()
    for (path in accepted.values.flatten()) {
        val sharing = accepted.getValue(path.consumer)
        val pot = if (path.reviewer) TROPHIC_APPROVAL else TROPHIC_READ
        credit[path.role] = (credit[path.role] ?: 0) + pot / sharing.size
    }
    return paths.groupBy { it.role }.mapNotNull { (id, own) ->
        val reads = own.count { it.outcome == TrophicOutcome.Accepted && !it.reviewer }
        val approvals = own.count { it.outcome == TrophicOutcome.Accepted && it.reviewer }
        val barren = reads + approvals == 0 &&
            own.all { it.outcome == TrophicOutcome.Ignored || it.outcome == TrophicOutcome.Rejected }
        if (reads + approvals == 0 && !barren) null
        else id to TrophicRunEvidence(reads, approvals, barren, credit[id] ?: 0)
    }.toMap()
}

private fun isReviewerPath(run: WorkflowRun, consumer: TaskDefinition, roles: Map<RoleDefinitionId, RoleDefinition>): Boolean {
    val consumerRun = run.taskRuns[consumer.id]
    val agent = ((consumerRun?.executor) ?: consumer.effectiveExecutor()).isAgent
    val role = (consumerRun?.assignedRoleId ?: consumer.roleId)?.let(roles::get)
    return agent && role?.let { isReviewerRoleName(it.name) } == true
}

/** Where a role stands, from its reserve (docs/Node-Creature-Genome.md, Survival). */
internal enum class TrophicState { Fed, Hungry, Starving, Starved }

internal fun trophicStateOf(hundredths: Int): TrophicState = when {
    hundredths >= TROPHIC_FED -> TrophicState.Fed
    hundredths >= TROPHIC_HUNGRY -> TrophicState.Hungry
    hundredths > 0 -> TrophicState.Starving
    else -> TrophicState.Starved
}

/** The reserve a role is first observed with: neither fed nor failing. Hundredths of a point, 0..100. */
internal const val TROPHIC_START = 5_000
internal const val TROPHIC_MAX = 10_000

/** A downstream worker completed with its output: +4, the documented rate for a read of clean output. */
internal const val TROPHIC_READ = 400

/** A reviewer approved its output: +8, the documented rate for a clean review. Replaces the read, never stacks on it. */
internal const val TROPHIC_APPROVAL = 800

/**
 * Settled runs carry no ticks, so the documented 0.08 a tick becomes one fixed step per barren run:
 * five barren runs take a role from its start to zero. A provisional default; what would show it
 * wrong is a role that starves while its work is plainly used downstream.
 */
internal const val TROPHIC_BARREN_RUN = 1_000

internal const val TROPHIC_FED = 5_000
internal const val TROPHIC_HUNGRY = 2_000

/**
 * Cumulative reserve per role, rebuilt from retained runs and their journals. Replaying a run is a
 * no-op. A role with no downstream evidence has no entry: unreported, not zero and not the start.
 */
internal data class TrophicHistory(
    val balances: Map<RoleDefinitionId, Int> = emptyMap(),
    private val seen: Set<WorkflowRunId> = emptySet(),
) {
    fun advance(run: WorkflowRun, reports: List<ProviderUsageRecorded>): TrophicHistory {
        if (run.id in seen) return this
        val next = balances.toMutableMap()
        for ((role, evidence) in trophicEvidence(run, reports)) {
            val before = next[role] ?: TROPHIC_START
            val after = if (evidence.barren) before - TROPHIC_BARREN_RUN
            else before + evidence.credit
            next[role] = after.coerceIn(0, TROPHIC_MAX)
        }
        return TrophicHistory(next, seen + run.id)
    }
}

/**
 * Roles whose reserve has run out and that may be retired. Never a founder (it has no hatch run: the
 * starting roster keeps its coverage), never a programmatic role, never one already disabled.
 * Retiring disables; it deletes nothing and keeps the lineage.
 */
internal fun starvedRoles(roles: Collection<RoleDefinition>, balances: Map<RoleDefinitionId, Int>): List<RoleDefinition> =
    roles.filter {
        it.enabled && it.executionSource == RoleExecutionSource.Agent &&
            it.hatchedFromRunId != null && balances[it.id] == 0
    }.sortedBy { it.id.value }
