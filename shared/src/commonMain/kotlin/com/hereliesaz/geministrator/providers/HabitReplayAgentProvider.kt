package com.hereliesaz.geministrator.providers

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.isUntrustedContext
import com.hereliesaz.geministrator.memory.procedural.HabitReplayPlanner
import com.hereliesaz.geministrator.memory.procedural.ReplayContextItem
import com.hereliesaz.geministrator.memory.procedural.ReplayDecision
import com.hereliesaz.geministrator.memory.procedural.ReplayInputs
import com.hereliesaz.geministrator.memory.procedural.ReplayQuery
import com.hereliesaz.geministrator.workflow.WorkKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Answers a task from a habit the keeper approved, with no model call. It is never chosen on its own
 * ([explicitOnly]); [com.hereliesaz.geministrator.workflow.AgentProviderRegistry] puts it first only for a task
 * [HabitReplayPlanner] has said it may answer. The output is a text artifact like any provider's, so every
 * downstream check, review and gate sees it as it sees any other. A task that held for plan approval is shown
 * the replayed text as its plan and runs only after the approval.
 *
 * It reports usage of zero model tokens, a cost of zero and [AgentEvent.UsageReported.tokensAvoided].
 */
class HabitReplayAgentProvider(val planner: HabitReplayPlanner) : AgentProvider {
    override val id: AgentProviderId = ID
    override val explicitOnly: Boolean = true
    override val workKind: WorkKind get() = WorkKind.OnDeviceModel

    private enum class Phase { AwaitingApproval, Ready, Cancelled }

    private class Session(val request: AgentTaskRequest, val phase: MutableStateFlow<Phase>)

    private val mutex = Mutex()
    private val sessions = mutableMapOf<ProviderRunId, Session>()
    private var nextSequence = 1L

    override suspend fun capabilities(): AgentCapabilities = AgentCapabilities(supported = HabitReplayPlanner.TEXT_SAFE)

    /** The replayed text as the plan, or null (and the engine then falls back) when this task cannot be replayed. */
    override suspend fun draftPlan(request: AgentTaskRequest): String? =
        (planner.decide(queryOf(request)) as? ReplayDecision.Replay)?.text

    override suspend fun start(request: AgentTaskRequest): AgentRunHandle {
        val runId = mutex.withLock {
            val sequence = nextSequence++
            ProviderRunId("${id.value}/${request.taskRunId.value}/$sequence").also { runId ->
                sessions[runId] = Session(request, MutableStateFlow(initialPhase(request, planApproved = false)))
            }
        }
        return AgentRunHandle(runId)
    }

    override suspend fun reconnect(
        runId: ProviderRunId,
        request: AgentTaskRequest,
        planGenerated: Boolean,
        planApproved: Boolean,
        planPreview: String?,
    ): ProviderActionResult {
        mutex.withLock {
            runId.value.substringAfterLast('/').toLongOrNull()?.let { nextSequence = maxOf(nextSequence, it + 1L) }
            if (runId !in sessions) sessions[runId] = Session(request, MutableStateFlow(initialPhase(request, planApproved)))
        }
        return ProviderActionResult.Accepted
    }

    override fun observe(runId: ProviderRunId): Flow<AgentEvent> = flow {
        val session = mutex.withLock { sessions[runId] } ?: error("Habit replay session ${runId.value} is not available in this process")
        try {
            val decision = planner.decide(queryOf(session.request))
            if (decision !is ReplayDecision.Replay) {
                // 503 so that failover treats it as the provider's own failure and the normal chain runs.
                emit(AgentEvent.Failed(runId, "Habit replay unavailable (503): " + (decision as ReplayDecision.NotApplicable).reason))
                return@flow
            }
            if (session.phase.value == Phase.AwaitingApproval) {
                emit(AgentEvent.PlanGenerated(runId, decision.text))
                if (session.phase.first { it != Phase.AwaitingApproval } == Phase.Cancelled) {
                    emit(AgentEvent.Failed(runId, CANCELLED))
                    return@flow
                }
                emit(AgentEvent.PlanApproved(runId))
            }
            if (session.phase.value == Phase.Cancelled) {
                emit(AgentEvent.Failed(runId, CANCELLED))
                return@flow
            }
            emit(AgentEvent.Progress(runId, "Reused habit ${decision.habit.id}@${decision.habit.version} from ${decision.habit.template.sourceRuns} earlier runs, no model call"))
            val kind = runCatching { ArtifactKind.valueOf(decision.habit.template.artifactKind) }.getOrDefault(ArtifactKind.Research)
            emit(
                AgentEvent.ArtifactProduced(
                    runId,
                    ProviderArtifact(
                        kind = kind,
                        label = decision.provenance,
                        textContent = decision.text,
                        mediaType = "text/markdown",
                        metadata = mapOf(
                            "provider" to id.value,
                            "taskRunId" to session.request.taskRunId.value,
                            META_HABIT_ID to decision.habit.id,
                            META_HABIT_VERSION to decision.habit.version.toString(),
                            "provenance" to decision.provenance,
                        ),
                    ),
                ),
            )
            planner.ledger.recordReplay(decision.habit.id, decision.tokensAvoided)
            emit(AgentEvent.UsageReported(runId, inputTokens = 0, outputTokens = 0, costUsd = 0.0, tokensAvoided = decision.tokensAvoided))
            emit(AgentEvent.Completed(runId))
        } finally {
            mutex.withLock { sessions.remove(runId) }
        }
    }

    override suspend fun sendMessage(runId: ProviderRunId, message: String): ProviderActionResult =
        ProviderActionResult.Rejected("A replayed habit takes no messages; start a new task for follow-up work.")

    override suspend fun approvePlan(runId: ProviderRunId): ProviderActionResult {
        val session = mutex.withLock { sessions[runId] } ?: return ProviderActionResult.Rejected("Habit replay session ${runId.value} is not available")
        if (session.phase.value == Phase.Cancelled) return ProviderActionResult.Rejected("Habit replay session ${runId.value} is cancelled")
        session.phase.value = Phase.Ready
        return ProviderActionResult.Accepted
    }

    override suspend fun cancel(runId: ProviderRunId): ProviderActionResult {
        mutex.withLock { sessions[runId] }?.phase?.value = Phase.Cancelled
        return ProviderActionResult.Accepted
    }

    private fun initialPhase(request: AgentTaskRequest, planApproved: Boolean): Phase =
        if (request.requirePlanApproval && !planApproved) Phase.AwaitingApproval else Phase.Ready

    companion object {
        val ID = AgentProviderId("habit-replay")
        const val META_HABIT_ID = "habit.replay.id"
        const val META_HABIT_VERSION = "habit.replay.version"
        private const val CANCELLED = "Habit replay session cancelled"

        /** What the planner is asked about a request. [constrained] is true when the task requires some provider by id. */
        fun queryOf(request: AgentTaskRequest, constrained: Boolean = false): ReplayQuery = ReplayQuery(
            roleId = request.orchestrationContext.roleId?.value.orEmpty(),
            inputs = ReplayInputs(
                objective = request.objective,
                criteria = request.acceptanceCriteria.map { it.description },
                context = request.contextArtifacts.mapNotNull { artifact ->
                    artifact.textContent?.let { ReplayContextItem(artifact.label, it) }
                },
            ),
            requiredCapabilities = request.requiredCapabilities,
            requiredArtifactKinds = request.requiredArtifacts.map { it.name }.toSet(),
            untrusted = request.contextArtifacts.any { it.isUntrustedContext() },
            constrained = constrained,
        )
    }
}
