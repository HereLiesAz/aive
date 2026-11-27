package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.RoleExecutionSource
import com.hereliesaz.geministrator.domain.effectiveExecutor
import com.hereliesaz.geministrator.domain.effectiveTokenBudget
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.events.ProviderUsageRecorded
import com.hereliesaz.geministrator.workflow.ArtifactVerdict
import com.hereliesaz.geministrator.workflow.ArtifactVerdicts
import com.hereliesaz.geministrator.workflow.GATE_VERDICT_BLOCKED
import com.hereliesaz.geministrator.workflow.HALL_MONITOR_REPORT_ID_METADATA
import com.hereliesaz.geministrator.workflow.HALL_MONITOR_REVIEW_VERDICT_METADATA
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Swarm OS step 2 (docs/Swarm-OS.md): a creature's four needs, read from the run and nothing else
 * (docs/Ecology-and-the-Swarm.md, the binding rule). A need with no data behind it is null and shown
 * as not reported, never estimated.
 *
 * Cumulative Echo uses recorded artifact delivery and model evidence. Delivery does not establish
 * semantic reliance, memory reads, contamination, sepsis, independence or a completed review.
 */
internal data class CreatureNeeds(
    /** Diagnostic ratio of current comparable delivered input paths; not the cumulative health score. */
    val echo: Int,
    val echoState: EchoState,
    /** 0..100: its reliability in this run; retries and failures cost it. */
    val integrity: Int,
    /** 0..100 of its agent's token budget left in this run; null when the user set no budget. */
    val budget: Int?,
    /** Tokens its provider has reported spending on it; null when none were reported. */
    val tokensSpent: Long?,
    /** Percentage of reporting downstream sessions with matching delivery; null if none report. */
    val outputUsed: Int?,
    /** Another task with high configuration overlap and matching reported provider/model. */
    val nearClone: String?,
    /** Completed, attributable review outcomes from reviewers with near-identical configurations. */
    val selfReviews: Int,
    val reviews: Int,
    /** Number of comparable active paths. Zero means Echo exposure is not reported. */
    val echoComparablePaths: Int = 0,
    /** Whether there is a planned downstream path, distinct from unknown delivery. */
    val hasDownstream: Boolean = false,
    /** Attributable stated review outcomes, not independent proof of work quality or approval authority. */
    val reviewApprovals: Int = 0,
    val reviewRevisions: Int = 0,
    val reviewRejections: Int = 0,
    /** Cumulative recorded Echo in hundredths of one point; null when no comparable history exists. */
    val echoHundredths: Int? = null,
    /** Cumulative trophic reserve in hundredths (see Trophic.kt); null when no downstream evidence exists. */
    val trophicHundredths: Int? = null,
    /** Distinct stored memory nodes carried in this task's successful requests (MemoryProvenance.kt); null when unreported. */
    val memoryNodesCarried: Int? = null,
    /** Delivered inputs whose producer's role is septic (Echo 85 or more): each costs this task 2 integrity. */
    val septicInputs: Int = 0,
)

internal enum class EchoState { Grounded, SelfAbsorbed, Fevered, Insane }

internal fun echoStateOf(echo: Int): EchoState = when {
    echo >= 85 -> EchoState.Insane
    echo >= 60 -> EchoState.Fevered
    echo >= 30 -> EchoState.SelfAbsorbed
    else -> EchoState.Grounded
}

/** The kinds whose work is reviewing others' work: Echo counts them, and the Swarm filters on them. */
internal val REVIEWER_KINDS = setOf(
    NodeCreatureRoleKind.QaEngineer,
    NodeCreatureRoleKind.AdversarialReviewer,
    NodeCreatureRoleKind.CodeReviewer,
    NodeCreatureRoleKind.CrashTestDummy,
    NodeCreatureRoleKind.Antagonist,
    NodeCreatureRoleKind.HallMonitor,
    NodeCreatureRoleKind.ComplianceOfficer,
)

/** Whether a role, by its name, is a reviewer (see [REVIEWER_KINDS]). */
internal fun isReviewerRoleName(roleName: String): Boolean = classifyNodeCreatureRole(roleName) in REVIEWER_KINDS

/**
 * Resolve only consistent model reports bound to the run's exact current task session.
 * Old, missing, mixed and replayed reports cannot establish a single model. This is
 * provider-reported identity, not independent attestation or an exhaustive inference trace.
 */
internal fun reportedSessionModels(run: WorkflowRun, reports: List<ProviderUsageRecorded>): Map<TaskDefinitionId,String> {
    val grouped=reports.filter { event ->
        val task=run.taskRuns[event.taskDefinitionId]
        event.workflowRunId==run.id && task!=null &&
            event.providerId==task.assignedProviderId && event.providerRunId==task.providerRunId
    }.groupBy { it.taskDefinitionId }
    return grouped.mapNotNull { (id, events) ->
        if(events.any { it.modelConsistent!=true || it.model.isNullOrBlank() || it.tokensAvoided!=0L }) null
        else events.mapNotNull { it.model }.distinct().singleOrNull()?.let { id to it }
    }.toMap()
}

/**
 * Exact current-session delivery evidence from the existing journal. Missing evidence is unknown.
 * Duplicate reports cannot amplify the set; replayed work is excluded.
 */
internal fun reportedArtifactDeliveries(run: WorkflowRun, reports: List<ProviderUsageRecorded>): Map<TaskDefinitionId, Set<String>> =
    reports.filter { event ->
        val task = run.taskRuns[event.taskDefinitionId]
        event.workflowRunId == run.id && task != null && event.providerId == task.assignedProviderId &&
            event.providerRunId == task.providerRunId && task.status != TaskRunStatus.Ready &&
            event.tokensAvoided == 0L && event.deliveredArtifactIds != null
    }.groupBy { it.taskDefinitionId }.mapValues { (_, events) ->
        events.flatMap { it.deliveredArtifactIds.orEmpty() }.toSet()
    }

/** Null means unreported; only exact current producer artifact IDs can match. */
internal fun artifactDelivered(
    run: WorkflowRun, producer: TaskDefinitionId, consumer: TaskDefinitionId,
    deliveries: Map<TaskDefinitionId, Set<String>>,
): Boolean? {
    val source = run.taskRuns[producer] ?: return null
    if (source.status != TaskRunStatus.Completed) return null
    val ids = deliveries[consumer] ?: return null
    return source.artifacts.any { it.taskRunId == source.id && it.id.value in ids }
}

/**
 * Read a stated outcome on a single-source review path. A multi-source review has no reliable
 * per-source attribution in the generic verdict format, so do not distribute its verdict to every
 * producer. A Hall Monitor review must additionally name the actual delivered report.
 * The current consumer must have finished, or have been failed specifically by a blocking verdict.
 * No outcome grants authority or establishes that the producer subsequently received the review.
 */
internal fun reportedReviewVerdict(
    run: WorkflowRun,
    producer: TaskDefinitionId,
    review: TaskDefinition,
    deliveries: Map<TaskDefinitionId, Set<String>>,
): ArtifactVerdict? {
    if (review.dependsOn != setOf(producer)) return null
    if (artifactDelivered(run, producer, review.id, deliveries) != true) return null
    val source = run.taskRuns[producer] ?: return null
    val consumer = run.taskRuns[review.id] ?: return null
    val blocked = consumer.status == TaskRunStatus.Failed && consumer.blockingReason?.code == GATE_VERDICT_BLOCKED
    if (consumer.status != TaskRunStatus.Completed && !blocked) return null
    val deliveredIds = deliveries[review.id] ?: return null
    val sourceArtifacts = source.artifacts.filter { it.taskRunId == source.id && it.id.value in deliveredIds }
    // Unknown additional inputs make even a single planned dependency ambiguous.
    if (sourceArtifacts.map { it.id.value }.toSet() != deliveredIds) return null
    val verdicts = consumer.artifacts
        .filter { it.taskRunId == consumer.id && it.kind in setOf(ArtifactKind.Review, ArtifactKind.Verification, ArtifactKind.HallMonitorReview) }
        .map { artifact ->
            val hallVerdict = artifact.metadata[HALL_MONITOR_REVIEW_VERDICT_METADATA]
            if (artifact.kind == ArtifactKind.HallMonitorReview || hallVerdict != null) {
                val reportId = artifact.metadata[HALL_MONITOR_REPORT_ID_METADATA] ?: return null
                val matches = sourceArtifacts.filter { it.kind == ArtifactKind.HallMonitorReport &&
                    (it.metadata[HALL_MONITOR_REPORT_ID_METADATA] ?: it.id.value) == reportId }
                if (matches.size != 1) return null
                ArtifactVerdicts.parse(hallVerdict ?: return null)
            } else ArtifactVerdicts.of(artifact)
        }
    val verdict = verdicts.distinct().singleOrNull() ?: return null
    return verdict.takeUnless { blocked && !it.blocks }
}

/** Shared run-correlated journal projection for the inspector and tissue, never the GPU. */
internal val LocalModelUsageReports = staticCompositionLocalOf<List<ProviderUsageRecorded>> { emptyList() }

/** Shared trophic reserve per agent role (Trophic.kt), rebuilt the same way as Echo. */
internal val LocalCumulativeTrophic = staticCompositionLocalOf<Map<RoleDefinitionId, Int>> { emptyMap() }

/** Shared cumulative projection, keyed by actual agent role rather than task or visual breed. */
internal val LocalCumulativeEcho = staticCompositionLocalOf<Map<RoleDefinitionId, Int>> { emptyMap() }

/**
 * Provisional overlap: provider 20 and model 30 retain the previous provider-half weight;
 * exact instructions 30 and declared capability/authority/tool/surface contract 20.
 * Missing model/provider evidence is unknown, not a clone or a stranger.
 */
internal fun configurationKinship(
    a: RoleDefinition, providerA: String?, b: RoleDefinition, providerB: String?,
    modelA: String?, modelB: String?,
): Int? {
    if(a.executionSource!=RoleExecutionSource.Agent || b.executionSource!=RoleExecutionSource.Agent ||
        providerA.isNullOrBlank() || providerB.isNullOrBlank() ||
        modelA.isNullOrBlank() || modelB.isNullOrBlank()) return null
    val provider=if(providerA==providerB) 20 else 0
    val model=if(modelA==modelB) 30 else 0
    // Exact instruction text avoids treating a negated safety constraint as equivalent.
    val instructions=if(a.instructions==b.instructions && a.keeperInstructions==b.keeperInstructions) 30 else 0
    val contract=if(a.capabilitiesRequired==b.capabilitiesRequired &&
        a.authorities==b.authorities && a.toolCeiling.toSet()==b.toolCeiling.toSet() &&
        a.surfaces==b.surfaces) 20 else 0
    return provider+model+instructions+contract
}

/** Near-clone threshold from Node-Creature-Genome.md; applied only to comparable configurations. */
internal const val NEAR_CLONE_KINSHIP = 95

/** Settlement evidence for the actual persisted lineage, not a newly simulated hatch. */
internal data class EchoBirthSeed(
    val source: WorkflowRunId,
    val contributors: Set<RoleDefinitionId>,
    val balances: Map<RoleDefinitionId, Int>,
    val kinships: Map<Set<RoleDefinitionId>, Int>,
) {
    /** Solo copies the parent; near-clones sum both; other hybrids inherit a quarter of the contributors' mean. */
    fun inherited(parents: List<RoleDefinitionId>): Int? {
        val unique = parents.toSet()
        if (parents.size != unique.size || !contributors.containsAll(unique)) return null
        if (parents.size == 1) return balances[parents.single()].takeIf { contributors.size == 1 }
        if (parents.size != 2) return null
        val kinship = kinships[unique] ?: return null
        if (kinship >= NEAR_CLONE_KINSHIP) {
            val a = balances[parents[0]] ?: return null
            val b = balances[parents[1]] ?: return null
            return (a + b).coerceAtMost(10_000)
        }
        val values = contributors.map { balances[it] ?: return null }
        return values.sum() / (values.size * 4)
    }
}

/**
 * Pure projection over retained run snapshots and their existing journals. Replaying a run is
 * idempotent. Birth candidates have no effect until the actual lineage appears in a snapshot.
 * Missing historical snapshots, kinship or parental balances remain unreported.
 */
internal data class EchoHistory(
    val balances: Map<RoleDefinitionId, Int> = emptyMap(),
    private val births: Map<RoleDefinitionId, EchoBirthSeed> = emptyMap(),
    private val seen: Set<WorkflowRunId> = emptySet(),
) {
    /** Apply an observed hatch once; later changes to parents never rewrite the seed. */
    fun withBirths(roles: List<RoleDefinition>): EchoHistory {
        val next = balances.toMutableMap()
        for (role in roles) {
            val seed = births[role.id] ?: continue
            if (role.id !in next && role.executionSource == RoleExecutionSource.Agent &&
                role.hatchedFromRunId == seed.source.value) {
                seed.inherited(role.parents)?.let { next[role.id] = it }
            }
        }
        return copy(balances = next)
    }

    /**
     * Count each positively reported source/current consumer session path once. Rates are
     * the documented bench rates in hundredths: own output +60, near-clone +40,
     * a stranger below 85 kinship -4 * (90 - kinship). The intermediate band is neutral.
     * Delivery is an exposure proxy, not proof of reliance.
     *
     * Beyond reads (see EchoEffects.kt): memory the creature's own role wrote, carried in its successful
     * requests ([memoryAuthors]: `memory-node:<id>` to the roles that wrote it), a criticism returned to the producer
     * that it received, a fevered creature ignoring that criticism, and a septic producer's contagion.
     */
    fun advance(
        run: WorkflowRun, reports: List<ProviderUsageRecorded>,
        memoryAuthors: Map<String, Set<RoleDefinitionId>> = emptyMap(),
    ): EchoHistory {
        if (run.id in seen) return this
        val seeded = withBirths(run.roleSnapshot)
        val definition = run.definitionSnapshot ?: return seeded
        val roles = run.roleSnapshot.associateBy { it.id }
        if (roles.isEmpty()) return seeded
        val models = reportedSessionModels(run, reports)
        val deliveries = reportedArtifactDeliveries(run, reports)
        val tasks = definition.tasks.associateBy { it.id }
        // A drained batch contains a set, not individual model calls. Count paths, not reports.
        val steps = mutableListOf<EchoStep>()
        for (consumer in definition.tasks) {
            val target = run.taskRuns[consumer.id] ?: continue
            if (!(target.executor ?: consumer.effectiveExecutor()).isAgent) continue
            val own = target.assignedRoleId?.let(roles::get) ?: continue
            if (own.executionSource != RoleExecutionSource.Agent) continue
            for (sourceId in consumer.dependsOn) {
                if (artifactDelivered(run, sourceId, consumer.id, deliveries) != true) continue
                val source = run.taskRuns[sourceId] ?: continue
                val sourceDef = tasks[sourceId] ?: continue
                val sourceAgent = (source.executor ?: sourceDef.effectiveExecutor()).isAgent
                val peer = source.assignedRoleId?.let(roles::get)
                val score = if (sourceAgent) {
                    if (peer == null) continue
                    configurationKinship(own, target.assignedProviderId?.value, peer,
                        source.assignedProviderId?.value, models[consumer.id], models[sourceId]) ?: continue
                } else 0
                val delta = when {
                    sourceAgent && own.id == peer?.id -> 60
                    score >= NEAR_CLONE_KINSHIP -> 40
                    score < 85 -> -4 * (90 - score)
                    else -> 0
                }
                val sourceArtifacts = source.artifacts.filter { it.taskRunId == source.id }.map { it.id.value }.toSet()
                val time = reports.asSequence().filter { event ->
                    event.workflowRunId == run.id && event.taskDefinitionId == consumer.id &&
                        event.providerId == target.assignedProviderId && event.providerRunId == target.providerRunId &&
                        event.tokensAvoided == 0L && event.deliveredArtifactIds.orEmpty().any { it in sourceArtifacts }
                }.minOfOrNull { it.occurredAtEpochMillis } ?: continue
                steps += EchoStep(time, consumer.id.value, sourceId.value, own.id, delta, EchoStepKind.Read,
                    peer?.id.takeIf { sourceAgent })
            }
        }
        steps += memoryEchoSteps(run, reports, roles, memoryAuthors)
        steps += criticismEchoSteps(run, reports, roles, models)
        val next = seeded.balances.toMutableMap()
        for (step in steps.sortedWith(compareBy({ it.time }, { it.a }, { it.b }, { it.kind.ordinal }))) {
            // A fevered creature stops responding to criticism; only wiring to very different creatures still lowers it.
            val delta = if (step.kind == EchoStepKind.Criticism && step.delta < 0 &&
                (next[step.role] ?: 0) >= ECHO_FEVERED) 0 else step.delta
            var value = (next[step.role] ?: 0) + delta
            // A septic producer's output contaminates whoever reads it, however the read itself rates.
            if (step.kind == EchoStepKind.Read && step.source != null && step.source != step.role &&
                (next[step.source] ?: 0) >= ECHO_SEPTIC) value += ECHO_SEPTIC_CONTAGION
            next[step.role] = value.coerceIn(0, 10_000)
        }
        val contributors = hatchContributors(run, run.roleSnapshot)
        val newBirths = if (run.status == com.hereliesaz.geministrator.domain.WorkflowRunStatus.Completed &&
            contributors.isNotEmpty()) {
            val ids = contributors.map { it.id }.toSet()
            val seed = EchoBirthSeed(run.id, ids, next.filterKeys { it in ids },
                hatchKinships(run, contributors, reports))
            seeded.births + (RoleDefinitionId("hatch-${run.id.value}") to seed)
        } else seeded.births
        return EchoHistory(next, newBirths, seeded.seen + run.id)
    }
}

internal fun creatureNeeds(
    taskId: TaskDefinitionId,
    definition: WorkflowDefinition,
    run: WorkflowRun,
    roles: Collection<RoleDefinition>,
    modelReports: List<ProviderUsageRecorded> = emptyList(),
    cumulative: Map<RoleDefinitionId, Int> = emptyMap(),
    trophic: Map<RoleDefinitionId, Int> = emptyMap(),
): CreatureNeeds? {
    val tasks = definition.tasks.associateBy(TaskDefinition::id)
    val task = tasks[taskId] ?: return null
    // Historical runs keep their own configurations; later roster edits cannot rewrite their ancestry.
    val rolesById = if(run.roleSnapshot.isNotEmpty()) run.roleSnapshot.associateBy(RoleDefinition::id)
        else roles.associateBy(RoleDefinition::id)

    fun role(id: TaskDefinitionId): RoleDefinition? =
        (run.taskRuns[id]?.assignedRoleId ?: tasks[id]?.roleId)?.let(rolesById::get)

    fun agent(id: TaskDefinitionId): Boolean {
        val t=tasks[id] ?: return false
        return (run.taskRuns[id]?.executor ?: t.effectiveExecutor()).isAgent
    }

    val models=reportedSessionModels(run,modelReports)
    fun kinship(other: TaskDefinitionId): Int? {
        if(!agent(taskId) || !agent(other)) return null
        val own=role(taskId) ?: return null
        val peer=role(other) ?: return null
        return configurationKinship(own,run.taskRuns[taskId]?.assignedProviderId?.value,
            peer,run.taskRuns[other]?.assignedProviderId?.value,models[taskId],models[other])
    }

    fun isReviewer(id: TaskDefinitionId): Boolean =
        agent(id) && role(id)?.let { isReviewerRoleName(it.name) } == true

    val dependents = definition.tasks.filter { taskId in it.dependsOn }.map(TaskDefinition::id)
    val deliveries = reportedArtifactDeliveries(run, modelReports)
    // Only incoming deliveries contribute to exposure. Outgoing review outcomes are separate:
    // a reviewer seeing work does not establish that its producer ever saw the review.
    val inputs = task.dependsOn.filter { artifactDelivered(run, it, taskId, deliveries) == true }
    val reviewOutcomes = dependents.mapNotNull { id ->
        val review = tasks[id] ?: return@mapNotNull null
        if (!isReviewer(id)) return@mapNotNull null
        reportedReviewVerdict(run, taskId, review, deliveries)?.let { id to it }
    }
    fun exposure(id: TaskDefinitionId): Int? {
        if(!agent(taskId)) return null
        // A completed programmatic dependency is a distinct source, not a model clone.
        return if(!agent(id)) 0 else kinship(id)
    }
    val voices=inputs.distinct().mapNotNull(::exposure)
    val reviewScores=reviewOutcomes.mapNotNull { (id, _) -> kinship(id) }
    val selfReviews=reviewScores.count { it>=NEAR_CLONE_KINSHIP }
    val echo=if(voices.isEmpty()) 0 else voices.count { it>=NEAR_CLONE_KINSHIP }*100/voices.size

    val taskRun = run.taskRuns[taskId]
    val attempt = taskRun?.attempt ?: 1
    val failed = taskRun?.status == TaskRunStatus.Failed || taskRun?.status == TaskRunStatus.Escalated
    val septicInputs = inputs.distinct().count { id ->
        val producer = run.taskRuns[id]?.assignedRoleId
        agent(taskId) && producer != null && producer != taskRun?.assignedRoleId && agent(id) &&
            (cumulative[producer] ?: 0) >= ECHO_SEPTIC
    }
    val integrity = (100 - 15 * (attempt - 1) - (if (failed) 40 else 0) - 2 * septicInputs).coerceIn(0, 100)

    // The budget belongs to the agent (its role) and covers everything it spends in this run.
    val roleId = taskRun?.assignedRoleId ?: task.roleId
    val echoHundredths = if (agent(taskId)) taskRun?.assignedRoleId?.let(cumulative::get) else null
    val budgetLeft = roleId?.let(rolesById::get)?.effectiveTokenBudget()?.let { cap ->
        val spent = run.taskRuns.values.filter { it.assignedRoleId == roleId }.sumOf { it.usage?.totalTokens ?: 0 }
        (100 - spent * 100 / cap).coerceIn(0, 100).toInt()
    }

    val knownDeliveries = dependents.mapNotNull { artifactDelivered(run, taskId, it, deliveries) }
    val outputUsed = knownDeliveries.takeIf { it.isNotEmpty() }?.let { known ->
        known.count { it } * 100 / known.size
    }
    val nearClone = definition.tasks.asSequence()
        .filter { it.id != taskId }
        .mapNotNull { peer -> kinship(peer.id)?.let { peer to it } }
        .filter { (_, score) -> score >= NEAR_CLONE_KINSHIP }
        .sortedWith(compareByDescending<Pair<TaskDefinition,Int>> { it.second }.thenBy { it.first.id.value })
        .firstOrNull()?.first?.name

    return CreatureNeeds(
        echo = echo,
        echoState = echoStateOf((echoHundredths ?: 0) / 100),
        integrity = integrity,
        budget = budgetLeft,
        tokensSpent = taskRun?.usage?.totalTokens,
        outputUsed = outputUsed,
        nearClone = nearClone,
        selfReviews = selfReviews,
        reviews = reviewScores.size,
        echoComparablePaths = voices.size,
        hasDownstream = dependents.isNotEmpty(),
        reviewApprovals = reviewOutcomes.count { it.second == ArtifactVerdict.Approve },
        reviewRevisions = reviewOutcomes.count { it.second == ArtifactVerdict.Revise },
        reviewRejections = reviewOutcomes.count { it.second == ArtifactVerdict.Reject },
        echoHundredths = echoHundredths,
        trophicHundredths = if (agent(taskId)) taskRun?.assignedRoleId?.let(trophic::get) else null,
        memoryNodesCarried = reportedMemoryReads(run, modelReports)[taskId]?.size,
        septicInputs = septicInputs,
    )
}
