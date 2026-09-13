package com.hereliesaz.geministrator.providers.llm

import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.providers.AgentCapabilities
import com.hereliesaz.geministrator.providers.AgentEvent
import com.hereliesaz.geministrator.providers.AgentProvider
import com.hereliesaz.geministrator.providers.AgentRunHandle
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.PromptCacheCapabilities
import com.hereliesaz.geministrator.providers.PromptCacheMode
import com.hereliesaz.geministrator.providers.ProviderActionResult
import com.hereliesaz.geministrator.providers.ProviderArtifact
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class TextLlmSessionPhase {
    AwaitingApproval,
    Ready,
    Cancelled,
}

open class TextLlmProvider(
    override val id: AgentProviderId,
    private val displayName: String,
    private val api: TextGenerationApi,
) : AgentProvider {
    private data class Session(
        val request: AgentTaskRequest,
        val phase: MutableStateFlow<TextLlmSessionPhase>,
    )

    private val mutex = Mutex()
    private var nextSequence = 1L

    private companion object {
        // Keyed by provider id prefix so sessions survive provider instance recreation.
        val sessionsByProvider = mutableMapOf<String, MutableMap<ProviderRunId, Session>>()
        val sessionsMutex = Mutex()
        const val MAX_PLAN_PREVIEW_CHARS = 8_000
    }

    private suspend fun providerSessions(): MutableMap<ProviderRunId, Session> =
        sessionsMutex.withLock {
            sessionsByProvider.getOrPut(id.value) { mutableMapOf() }
        }

    override suspend fun capabilities(): AgentCapabilities = AgentCapabilities(
        supported = setOf(
            AgentCapability.PlanGeneration,
            AgentCapability.PlanApproval,
        ),
        promptCaching = PromptCacheCapabilities(modes = setOf(PromptCacheMode.Unsupported)),
    )

    override suspend fun start(request: AgentTaskRequest): AgentRunHandle {
        val sessions = providerSessions()
        val runId = mutex.withLock {
            val sequence = nextSequence++
            ProviderRunId("${id.value}/${request.taskRunId.value}/$sequence").also { providerRunId ->
                sessions[providerRunId] = Session(
                    request = request,
                    phase = MutableStateFlow(
                        if (request.requirePlanApproval) {
                            TextLlmSessionPhase.AwaitingApproval
                        } else {
                            TextLlmSessionPhase.Ready
                        },
                    ),
                )
            }
        }
        return AgentRunHandle(runId)
    }

    override fun observe(runId: ProviderRunId): Flow<AgentEvent> = flow {
        val sessions = providerSessions()
        val session = mutex.withLock { sessions[runId] }
            ?: error("$displayName session ${runId.value} is not available in this process")

        if (session.request.requirePlanApproval) {
            // Generate a preview plan to show the user before they approve.
            val preview = api.generate(renderPrompt(session.request))
            emit(
                AgentEvent.PlanGenerated(
                    runId = runId,
                    summary = preview.text.take(MAX_PLAN_PREVIEW_CHARS),
                ),
            )
            val phase = session.phase
                .filter { it != TextLlmSessionPhase.AwaitingApproval }
                .first()
            if (phase == TextLlmSessionPhase.Cancelled) {
                emit(AgentEvent.Failed(runId, "$displayName session cancelled"))
                return@flow
            }
            emit(AgentEvent.PlanApproved(runId))
            // Re-generate after approval so the full response reflects confirmed intent.
            val result = api.generate(renderPrompt(session.request))
            emit(
                AgentEvent.ArtifactProduced(
                    runId = runId,
                    artifact = ProviderArtifact(
                        kind = inferArtifactKind(session.request),
                        label = "$displayName response",
                        textContent = result.text,
                        mediaType = "text/markdown",
                        metadata = mapOf(
                            "provider" to id.value,
                            "taskRunId" to session.request.taskRunId.value,
                        ),
                    ),
                ),
            )
            if (result.inputTokens != null || result.outputTokens != null) {
                emit(
                    AgentEvent.UsageReported(
                        runId = runId,
                        inputTokens = result.inputTokens,
                        outputTokens = result.outputTokens,
                    ),
                )
            }
        } else {
            if (session.phase.value == TextLlmSessionPhase.Cancelled) {
                emit(AgentEvent.Failed(runId, "$displayName session cancelled"))
                return@flow
            }
            val result = api.generate(renderPrompt(session.request))
            emit(
                AgentEvent.ArtifactProduced(
                    runId = runId,
                    artifact = ProviderArtifact(
                        kind = inferArtifactKind(session.request),
                        label = "$displayName response",
                        textContent = result.text,
                        mediaType = "text/markdown",
                        metadata = mapOf(
                            "provider" to id.value,
                            "taskRunId" to session.request.taskRunId.value,
                        ),
                    ),
                ),
            )
            if (result.inputTokens != null || result.outputTokens != null) {
                emit(
                    AgentEvent.UsageReported(
                        runId = runId,
                        inputTokens = result.inputTokens,
                        outputTokens = result.outputTokens,
                    ),
                )
            }
        }
        emit(AgentEvent.Completed(runId))
    }

    override suspend fun sendMessage(
        runId: ProviderRunId,
        message: String,
    ): ProviderActionResult = ProviderActionResult.Rejected(
        "$displayName uses one-shot task execution; start a new governed task for follow-up work.",
    )

    override suspend fun approvePlan(runId: ProviderRunId): ProviderActionResult {
        val sessions = providerSessions()
        val session = mutex.withLock { sessions[runId] }
            ?: return ProviderActionResult.Rejected("$displayName session ${runId.value} is not available")
        if (!session.request.requirePlanApproval) return ProviderActionResult.Accepted
        if (session.phase.value == TextLlmSessionPhase.Cancelled) {
            return ProviderActionResult.Rejected("$displayName session ${runId.value} is cancelled")
        }
        session.phase.value = TextLlmSessionPhase.Ready
        return ProviderActionResult.Accepted
    }

    override suspend fun cancel(runId: ProviderRunId): ProviderActionResult {
        val sessions = providerSessions()
        val session = mutex.withLock { sessions[runId] }
            ?: return ProviderActionResult.Accepted
        session.phase.value = TextLlmSessionPhase.Cancelled
        return ProviderActionResult.Accepted
    }

    private fun renderPrompt(request: AgentTaskRequest): String = buildString {
        append("ROLE INSTRUCTIONS\n")
        append(request.roleInstructions.trim())
        append("\n\n")
        request.promptContext.stablePrefix.forEach { block ->
            append(block.label.uppercase())
            append("\n")
            append(block.content.trim())
            append("\n\n")
        }
        append("TASK\n")
        append(request.objective.trim())
        append("\n\n")
        if (request.acceptanceCriteria.isNotEmpty()) {
            append("ACCEPTANCE CRITERIA\n")
            request.acceptanceCriteria.forEachIndexed { index, criterion ->
                append(index + 1)
                append(". ")
                append(criterion.description.trim())
                append("\n")
            }
            append("\n")
        }
        request.promptContext.dynamicContext.forEach { block ->
            append(block.label.uppercase())
            append("\n")
            append(block.content.trim())
            append("\n\n")
        }
        val repository = request.repository
        if (repository != null) {
            append("REPOSITORY CONTEXT\n")
            append(repository.owner)
            append('/')
            append(repository.name)
            repository.defaultBranch?.let { branch ->
                append(" @ ")
                append(branch)
            }
            append("\n\n")
        }
        val textArtifacts = request.contextArtifacts.filter { !it.textContent.isNullOrBlank() }
        if (textArtifacts.isNotEmpty()) {
            textArtifacts.forEach { artifact ->
                append(artifact.label.uppercase())
                append("\n")
                append(artifact.textContent!!.trim())
                append("\n\n")
            }
        }
        append("Return only the concrete work product for this assigned role. Do not claim repository access, shell execution, tests, or changes you did not actually perform.")
    }.trim()

    private fun inferArtifactKind(request: AgentTaskRequest): ArtifactKind {
        val signal = "${request.roleInstructions}\n${request.objective}".lowercase()
        return when {
            "release" in signal -> ArtifactKind.Release
            "failure" in signal || "root cause" in signal -> ArtifactKind.FailureAnalysis
            "verify" in signal || "verification" in signal || "qa" in signal -> ArtifactKind.Verification
            "review" in signal -> ArtifactKind.Review
            "architect" in signal || "architecture" in signal -> ArtifactKind.Architecture
            "design" in signal || "ux" in signal -> ArtifactKind.Design
            "requirement" in signal || "product" in signal -> ArtifactKind.Requirement
            else -> ArtifactKind.Research
        }
    }

}


class OpenAiProvider(
    apiKeyProvider: LlmApiKeyProvider,
    model: String = "gpt-5.6",
) : TextLlmProvider(
    id = AgentProviderId("openai"),
    displayName = "OpenAI / Codex",
    api = OpenAiResponsesApi(apiKeyProvider = apiKeyProvider, model = model),
)

class AnthropicProvider(
    apiKeyProvider: LlmApiKeyProvider,
    model: String = "claude-sonnet-5",
) : TextLlmProvider(
    id = AgentProviderId("anthropic"),
    displayName = "Claude",
    api = AnthropicMessagesApi(apiKeyProvider = apiKeyProvider, model = model),
)

class GeminiProvider(
    apiKeyProvider: LlmApiKeyProvider,
    model: String = "gemini-3.8-flash",
) : TextLlmProvider(
    id = AgentProviderId("gemini"),
    displayName = "Gemini",
    api = GeminiGenerateContentApi(apiKeyProvider = apiKeyProvider, model = model),
)

class XaiProvider(
    apiKeyProvider: LlmApiKeyProvider,
    model: String = "grok-4.6",
) : TextLlmProvider(
    id = AgentProviderId("xai"),
    displayName = "Grok",
    api = XaiResponsesApi(apiKeyProvider = apiKeyProvider, model = model),
)
