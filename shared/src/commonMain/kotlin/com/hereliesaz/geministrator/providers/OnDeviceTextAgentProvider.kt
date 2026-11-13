package com.hereliesaz.geministrator.providers

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ProviderRunId
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One generation by an on-device model, with the token counts its tokenizer saw. */
data class OnDeviceTextGeneration(
    val text: String,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
)

/**
 * A text generator backed by a model installed on this device. Platforms implement it over the
 * local generator plumbing they already have (ONNX Runtime and an installed instruct model).
 */
interface OnDeviceTextGenerator {
    /** True while the model is installed; checked before every routing decision. */
    fun isReady(): Boolean

    suspend fun generate(system: String, user: String, maxNewTokens: Int): OnDeviceTextGeneration
}

/**
 * Small text tasks on the device's own model: summarise, classify, extract, rewrite. It advertises
 * no [com.hereliesaz.geministrator.domain.AgentCapability], so it covers only tasks that need none,
 * never repository access, and [com.hereliesaz.geministrator.workflow.AgentProviderRegistry] prefers
 * it only for tasks whose estimated input fits [maxInputTokens]. It is never chosen to staff a role
 * on its own: the hive still needs a provider of record.
 *
 * Each run is one generation. Usage is reported like any provider's, with cost 0.
 */
class OnDeviceTextAgentProvider(
    private val generator: OnDeviceTextGenerator,
    val maxInputTokens: Int = DEFAULT_MAX_INPUT_TOKENS,
    private val maxNewTokens: Int = DEFAULT_MAX_NEW_TOKENS,
) : AgentProvider {
    override val id: AgentProviderId = ID
    override val workKind: com.hereliesaz.geministrator.workflow.WorkKind get() = com.hereliesaz.geministrator.workflow.WorkKind.OnDeviceModel

    private enum class Phase { AwaitingApproval, Ready, Cancelled }

    private class Session(val request: AgentTaskRequest, val phase: MutableStateFlow<Phase>)

    private val mutex = Mutex()
    private val sessions = mutableMapOf<ProviderRunId, Session>()
    private var nextSequence = 1L

    /** Whether the model is installed now; a provider whose model was removed takes no work. */
    fun isAvailable(): Boolean = runCatching { generator.isReady() }.getOrDefault(false)

    override suspend fun capabilities(): AgentCapabilities = AgentCapabilities(supported = emptySet())

    override suspend fun draftPlan(request: AgentTaskRequest): String =
        generateOrThrow(
            request,
            planOnly = true,
        ).text.take(MAX_PLAN_PREVIEW_CHARS)

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
            runId.value.substringAfterLast('/').toLongOrNull()?.let { recovered ->
                nextSequence = maxOf(nextSequence, recovered + 1L)
            }
            if (runId !in sessions) {
                sessions[runId] = Session(request, MutableStateFlow(initialPhase(request, planApproved)))
            }
        }
        return ProviderActionResult.Accepted
    }

    override fun observe(runId: ProviderRunId): Flow<AgentEvent> = flow {
        val session = mutex.withLock { sessions[runId] }
            ?: error("On-device session ${runId.value} is not available in this process")
        try {
            if (session.phase.value == Phase.AwaitingApproval) {
                val plan = generateOrThrow(session.request, planOnly = true)
                emit(AgentEvent.PlanGenerated(runId, plan.text.take(MAX_PLAN_PREVIEW_CHARS)))
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
            emit(AgentEvent.Progress(runId, "Writing on this device…"))
            val mark = TimeSource.Monotonic.markNow()
            val result = try {
                generateOrThrow(session.request, planOnly = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                // Throwable, not Exception: a model too large for memory surfaces as OutOfMemoryError.
                emit(AgentEvent.Failed(runId, "On-device model: ${failure.message?.takeIf(String::isNotBlank) ?: failure::class.simpleName}"))
                return@flow
            }
            val latency = mark.elapsedNow().inWholeMilliseconds
            // cancel() only flips the phase; a generation already running finishes and is dropped.
            if (session.phase.value == Phase.Cancelled) {
                emit(AgentEvent.Failed(runId, CANCELLED))
                return@flow
            }
            emit(
                AgentEvent.ArtifactProduced(
                    runId,
                    ProviderArtifact(
                        kind = session.request.requiredArtifacts.firstOrNull() ?: ArtifactKind.Research,
                        label = "On-device response",
                        textContent = result.text,
                        mediaType = "text/markdown",
                        metadata = mapOf(
                            "provider" to id.value,
                            "taskRunId" to session.request.taskRunId.value,
                        ),
                    ),
                ),
            )
            emit(
                AgentEvent.UsageReported(
                    runId = runId,
                    inputTokens = result.inputTokens,
                    outputTokens = result.outputTokens,
                    costUsd = 0.0,
                    latencyMillis = latency,
                ),
            )
            emit(AgentEvent.Completed(runId))
        } finally {
            mutex.withLock { sessions.remove(runId) }
        }
    }

    override suspend fun sendMessage(runId: ProviderRunId, message: String): ProviderActionResult =
        ProviderActionResult.Rejected("The on-device model runs one generation per task; start a new task for follow-up work.")

    override suspend fun approvePlan(runId: ProviderRunId): ProviderActionResult {
        val session = mutex.withLock { sessions[runId] }
            ?: return ProviderActionResult.Rejected("On-device session ${runId.value} is not available")
        if (session.phase.value == Phase.Cancelled) {
            return ProviderActionResult.Rejected("On-device session ${runId.value} is cancelled")
        }
        session.phase.value = Phase.Ready
        return ProviderActionResult.Accepted
    }

    override suspend fun cancel(runId: ProviderRunId): ProviderActionResult {
        mutex.withLock { sessions[runId] }?.phase?.value = Phase.Cancelled
        return ProviderActionResult.Accepted
    }

    private fun initialPhase(request: AgentTaskRequest, planApproved: Boolean): Phase =
        if (request.requirePlanApproval && !planApproved) Phase.AwaitingApproval else Phase.Ready

    private suspend fun generateOrThrow(request: AgentTaskRequest, planOnly: Boolean): OnDeviceTextGeneration {
        check(isAvailable()) { "The on-device model is not installed" }
        return generator.generate(
            system = SYSTEM_PROMPT,
            user = renderTask(request, planOnly),
            maxNewTokens = maxNewTokens,
        )
    }

    companion object {
        val ID = AgentProviderId("on-device")

        /** About 6,000 characters: the length the local instruct model handles well alongside its answer. */
        const val DEFAULT_MAX_INPUT_TOKENS: Int = 1_500
        const val DEFAULT_MAX_NEW_TOKENS: Int = 512
        private const val MAX_PLAN_PREVIEW_CHARS = 8_000
        private const val CANCELLED = "On-device session cancelled"

        private const val SYSTEM_PROMPT =
            "You are a careful assistant doing one small text task: summarise, classify, extract or rewrite. " +
                "Work only from the text given. Return only the result, with no preamble. " +
                "Do not claim to have read files, run commands or changed anything."

        /**
         * The task as the on-device model sees it: the role's own instructions (not the swarm's
         * shared preamble), the objective, its acceptance criteria and upstream text artifacts.
         * [estimateTokens] of the same parts is what routing compares with [maxInputTokens].
         */
        fun renderTask(request: AgentTaskRequest, planOnly: Boolean = false): String = buildString {
            val role = request.promptContext.stablePrefix.firstOrNull { it.label == ROLE_BLOCK_LABEL }?.content
                ?: request.roleInstructions
            if (role.isNotBlank()) append("ROLE\n").append(role.trim()).append("\n\n")
            append("TASK\n").append(request.objective.trim()).append("\n\n")
            if (request.acceptanceCriteria.isNotEmpty()) {
                append("ACCEPTANCE CRITERIA\n")
                request.acceptanceCriteria.forEachIndexed { index, criterion ->
                    append(index + 1).append(". ").append(criterion.description.trim()).append("\n")
                }
                append("\n")
            }
            request.contextArtifacts.forEach { artifact ->
                val text = artifact.textContent?.takeIf(String::isNotBlank) ?: return@forEach
                append(artifact.label.uppercase()).append("\n").append(text.trim()).append("\n\n")
            }
            if (planOnly) {
                append("PLAN ONLY\nReply with a short numbered plan for this task. Do not perform it yet.")
            }
        }.trim()

        /** Rough token count for [chars] characters of English text (four characters a token). */
        fun estimateTokens(chars: Int): Int = (chars.coerceAtLeast(0) + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN

        /** The label of the role-instructions block in [PromptContext.stablePrefix]. */
        const val ROLE_BLOCK_LABEL: String = "Role"

        private const val CHARS_PER_TOKEN = 4
    }
}

/**
 * The on-device provider when the keeper turned it on in the Keeper's room and the model is
 * installed; otherwise null, and nothing is routed to the device.
 */
fun onDeviceTextProviderOrNull(
    enabled: Boolean,
    generator: OnDeviceTextGenerator?,
): OnDeviceTextAgentProvider? =
    if (enabled && generator != null && runCatching { generator.isReady() }.getOrDefault(false)) {
        OnDeviceTextAgentProvider(generator)
    } else {
        null
    }
