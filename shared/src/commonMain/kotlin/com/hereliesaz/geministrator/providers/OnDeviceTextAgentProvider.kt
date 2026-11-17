package com.hereliesaz.geministrator.providers

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.memory.MEMORY_PROTOCOL_PROMPT_LABEL
import com.hereliesaz.geministrator.memory.MEMORY_RECALL_PROMPT_LABEL
import com.hereliesaz.geministrator.memory.RECALL_UNTRUSTED_NOTE
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

    /**
     * The same call with the user text in two parts, `stablePrefix + dynamic`: the leading part repeats
     * across calls for the same role, so an engine may keep its work on it. The default ignores the split.
     */
    suspend fun generate(
        system: String,
        stablePrefix: String,
        dynamic: String,
        maxNewTokens: Int,
    ): OnDeviceTextGeneration = generate(system, stablePrefix + dynamic, maxNewTokens)

    /** How this generator's prompt-prefix cache has done, or null when it keeps none or none is loaded. */
    fun prefixStats(): PromptPrefixStats? = null
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
    private val maxInputTokensOverride: Int? = null,
    private val maxNewTokens: Int = DEFAULT_MAX_NEW_TOKENS,
) : AgentProvider {
    override val id: AgentProviderId = ID

    /** Task text routed here: the override, else the active runtime's own limit (LiteRT-LM has a smaller context). */
    val maxInputTokens: Int
        get() = maxInputTokensOverride
            ?: (generator as? OnDeviceTextRuntimeSource)?.activeRuntime()?.maxInputTokens
            ?: DEFAULT_MAX_INPUT_TOKENS
    override val workKind: com.hereliesaz.geministrator.workflow.WorkKind get() = com.hereliesaz.geministrator.workflow.WorkKind.OnDeviceModel

    /** A task prompt cut after the role block; see [renderTaskParts]. */
    data class TaskParts(val stable: String, val dynamic: String)

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
        val parts = renderTaskParts(request, planOnly, maxInputTokens)
        return generator.generate(
            system = SYSTEM_PROMPT,
            stablePrefix = parts.stable,
            dynamic = parts.dynamic,
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

        /** The one system prompt every on-device task sends; the adapter corpus (tools/app_integration_training) reads it from here. */
        const val SYSTEM_PROMPT =
            "You are a careful assistant doing one small text task: summarise, classify, extract or rewrite. " +
                "Work only from the text given. Return only the result, with no preamble. " +
                "Do not claim to have read files, run commands or changed anything."

        /**
         * The task as the on-device model sees it: the role's own instructions (not the swarm's
         * shared preamble), the objective, its acceptance criteria and upstream text artifacts.
         * [estimateTokens] of the same parts is what routing compares with [maxInputTokens].
         */
        fun renderTask(request: AgentTaskRequest, planOnly: Boolean = false, maxInputTokens: Int? = null): String =
            renderTaskParts(request, planOnly, maxInputTokens).let { it.stable + it.dynamic }

        /**
         * [estimateTokens] of the prompt as it is rendered ([renderTask]), memory recall included: the one
         * estimate of a built request's input. Before dispatch no request exists and recall is not yet
         * drawn, so [estimateTaskInputTokens][com.hereliesaz.geministrator.workflow.estimateTaskInputTokens]
         * counts the part that cannot be dropped; recall is dropped first at render time (see [renderTaskParts]).
         */
        fun estimateRequestTokens(request: AgentTaskRequest, planOnly: Boolean = false): Int =
            estimateTokens(renderTask(request, planOnly).length)

        /**
         * [renderTask] in two parts whose concatenation is exactly its text. [TaskParts.stable] is the
         * leading `ROLE\n<role>\n\n` block (empty when there is no role); it repeats across every task of a
         * role, plan-only or not, so an engine can keep its work on it.
         *
         * Invariant: a non-empty [TaskParts.stable] ends with a blank line (`\n\n`) and [TaskParts.dynamic]
         * always begins with `TASK`, a letter. Qwen's pre-tokenizer isolates a run of newlines, so the
         * tokens of the two parts read one after the other are the tokens of the whole text.
         *
         * A `Memory protocol` block (stable prefix or dynamic context) renders as `MEMORY PROTOCOL\n<text>\n\n`
         * into [TaskParts.stable] right after the ROLE block: it is constant text, so the head stays
         * byte-identical across calls while memory is on. Absent, nothing is rendered.
         *
         * Memory recall ([MEMORY_RECALL_LABELS] blocks of the dynamic context, capped by
         * [MAX_RECALL_CHARS]) renders after the acceptance criteria and before the artifacts, in the dynamic
         * part only, so the stable prefix never changes with recall. When [maxInputTokens] is given and the
         * prompt with recall would exceed it, recall is left out first: it is the one optional input, while
         * the criteria and artifacts are what the task is.
         */
        fun renderTaskParts(request: AgentTaskRequest, planOnly: Boolean = false, maxInputTokens: Int? = null): TaskParts {
            val role = request.promptContext.stablePrefix.firstOrNull { it.label == ROLE_BLOCK_LABEL }?.content
                ?: request.roleInstructions
            val roleBlock = if (role.isNotBlank()) "ROLE\n" + role.trim() + "\n\n" else ""
            val protocol = memoryProtocol(request)
            val protocolBlock = if (protocol != null) "MEMORY PROTOCOL\n$protocol\n\n" else ""
            var stable = roleBlock + protocolBlock
            val recall = recallBlocks(request)
            fun render(withRecall: Boolean): String = buildString {
                append("TASK\n").append(request.objective.trim()).append("\n\n")
                if (request.acceptanceCriteria.isNotEmpty()) {
                    append("ACCEPTANCE CRITERIA\n")
                    request.acceptanceCriteria.forEachIndexed { index, criterion ->
                        append(index + 1).append(". ").append(criterion.description.trim()).append("\n")
                    }
                    append("\n")
                }
                if (withRecall) {
                    recall.forEach { (label, content) -> append(label.uppercase()).append("\n").append(content).append("\n\n") }
                }
                request.contextArtifacts.forEach { artifact ->
                    val text = artifact.textContent?.takeIf(String::isNotBlank) ?: return@forEach
                    append(artifact.label.uppercase()).append("\n").append(text.trim()).append("\n\n")
                }
                if (planOnly) {
                    append("PLAN ONLY\nReply with a short numbered plan for this task. Do not perform it yet.")
                }
            }.trim()
            var dynamic = render(withRecall = true)
            if (recall.isNotEmpty() && maxInputTokens != null && estimateTokens(stable.length + dynamic.length) > maxInputTokens) {
                dynamic = render(withRecall = false)
            }
            // The protocol is rules, not recall: it is not subject to the recall cap or the recall drop. Only
            // when the prompt still exceeds the limit without recall is it dropped, and last.
            if (protocolBlock.isNotEmpty() && maxInputTokens != null && estimateTokens(stable.length + dynamic.length) > maxInputTokens) {
                stable = roleBlock
            }
            return TaskParts(stable, dynamic)
        }

        /** The trimmed text of the request's `Memory protocol` block, from either part of its prompt context. */
        internal fun memoryProtocol(request: AgentTaskRequest): String? =
            (request.promptContext.stablePrefix + request.promptContext.dynamicContext)
                .firstOrNull { it.label == MEMORY_PROTOCOL_PROMPT_LABEL }
                ?.content?.trim()?.takeIf(String::isNotEmpty)

        /**
         * The memory blocks the on-device model sees, as (label, trimmed content): only [MEMORY_RECALL_LABELS]
         * from the request's dynamic context, each led by the untrusted-recall note (added here when the block
         * lacks it), in the order given, within [MAX_RECALL_CHARS] of rendered text. A block that does not fit is
         * cut at a line boundary and the blocks after it are dropped; one that would keep only the note is dropped.
         * `Habits` is not passed: the owner is redesigning habits as programmatic replay, not prompt text.
         */
        internal fun recallBlocks(request: AgentTaskRequest): List<Pair<String, String>> {
            var remaining = MAX_RECALL_CHARS
            val kept = mutableListOf<Pair<String, String>>()
            for (block in request.promptContext.dynamicContext) {
                if (block.label !in MEMORY_RECALL_LABELS) continue
                val body = block.content.trim().takeIf(String::isNotEmpty) ?: continue
                val content = if (body.startsWith(RECALL_UNTRUSTED_NOTE)) body else RECALL_UNTRUSTED_NOTE + "\n" + body
                // Rendered as LABEL, newline, content, blank line.
                val overhead = block.label.length + 3
                val allowed = remaining - overhead
                if (content.length <= allowed) {
                    kept += block.label to content
                    remaining -= overhead + content.length
                    continue
                }
                if (allowed > 0) {
                    val lineEnd = if (content[allowed] == '\n') allowed else content.substring(0, allowed).lastIndexOf('\n')
                    val trimmed = if (lineEnd > 0) content.substring(0, lineEnd).trimEnd() else ""
                    // Keep it only if some body remains after the note.
                    if (trimmed.length > RECALL_UNTRUSTED_NOTE.length) kept += block.label to trimmed
                }
                break
            }
            return kept
        }

        /**
         * The most characters of memory recall rendered into one on-device prompt, labels and the untrusted
         * note included: 2,400 characters (about 600 tokens) so recall cannot flood a small model whose whole
         * input is [DEFAULT_MAX_INPUT_TOKENS].
         */
        const val MAX_RECALL_CHARS: Int = 2_400

        /**
         * The dynamic-context labels rendered as recall: `Memory`, `Relevant memory`, `Memory timeline`.
         * Not `Habits` (being redesigned as programmatic replay), nor the swarm preamble or other blocks.
         */
        val MEMORY_RECALL_LABELS: Set<String> = setOf("Memory", MEMORY_RECALL_PROMPT_LABEL, "Memory timeline")

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
