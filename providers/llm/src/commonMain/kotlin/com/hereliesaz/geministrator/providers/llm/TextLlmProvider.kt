package com.hereliesaz.geministrator.providers.llm

import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.CONTEXT_PROVENANCE_METADATA_KEY
import com.hereliesaz.geministrator.domain.ContextProvenance
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.displayName
import com.hereliesaz.geministrator.domain.locationLabel
import com.hereliesaz.geministrator.mcp.McpToolOutcome
import com.hereliesaz.geministrator.mcp.McpToolSource
import com.hereliesaz.geministrator.mcp.McpToolbox
import com.hereliesaz.geministrator.mcp.restrictedTo
import com.hereliesaz.geministrator.providers.AgentCapabilities
import com.hereliesaz.geministrator.providers.AgentEvent
import com.hereliesaz.geministrator.providers.AgentProvider
import com.hereliesaz.geministrator.providers.AgentRunHandle
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.ProviderActionResult
import com.hereliesaz.geministrator.providers.ModelPriceTable
import com.hereliesaz.geministrator.providers.ProviderArtifact
import com.hereliesaz.geministrator.providers.ProviderPlanDraft
import com.hereliesaz.geministrator.providers.spentTokens
import com.hereliesaz.geministrator.workflow.HALL_MONITOR_REPORT_ID_METADATA
import com.hereliesaz.geministrator.workflow.HALL_MONITOR_REVIEW_VERDICT_METADATA
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
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
    /**
     * The MCP host's tools (docs/architecture/MCP_HOST.md). When [api] can call functions and the
     * run has tools, the run goes through [McpToolLoop] instead of one streamed generation.
     */
    private val tools: McpToolSource? = null,
    /** What this provider's models cost; the app's one price list unless a test gives its own. */
    private val pricing: ModelPriceTable = ModelPriceTable.Default,
) : AgentProvider {
    /** Used without a key (an anonymous keyless endpoint): the one thing that cannot bill. */
    override val keyless: Boolean get() = !api.billsPerToken

    /** Tools are offered only through [toolboxFor], which cuts them to the request's allowed set. */
    override val enforcesToolFilter: Boolean get() = true

    private data class Session(
        val request: AgentTaskRequest,
        val phase: MutableStateFlow<TextLlmSessionPhase>,
        val planGenerated: MutableStateFlow<Boolean>,
        var planResult: TextGenerationResult? = null,
        /** Streams the run's generation; memory can interrupt it mid-thought (see [sendMessage]). */
        val generation: InterruptibleGeneration,
        /** MCP tool calls the run made: their results are external-provenance content. */
        var toolOutcomes: List<McpToolOutcome> = emptyList(),
        /** True while an [observe] collector holds the session: it ends the session itself when it finishes. */
        var observing: Boolean = false,
    )

    private val mutex = Mutex()
    private val sessions = mutableMapOf<ProviderRunId, Session>()
    private var nextSequence = 1L

    private companion object {
        const val MAX_PLAN_PREVIEW_CHARS = 8_000
    }

    override suspend fun capabilities(): AgentCapabilities = AgentCapabilities(
        supported = setOf(
            AgentCapability.PlanGeneration,
            AgentCapability.PlanApproval,
        ),
        promptCaching = api.promptCaching,
    )

    /**
     * The engine-owned plan gate's draft: a numbered plan, not the work itself, so nothing is done
     * before approval. After approval the engine starts the run with the plan in context and no
     * provider-side gate. A refusal (HTTP 429, bad key) propagates, and the engine fails the task
     * with this provider's reason.
     */
    override suspend fun draftPlan(request: AgentTaskRequest): String = draftPlanWithUsage(request).text

    /** The plan and what drafting it spent: a draft is a billed generation, counted like any other. */
    override suspend fun draftPlanWithUsage(request: AgentTaskRequest): ProviderPlanDraft {
        val rendered = renderPrompt(request)
        // Appended to the dynamic part: the plan draft shares the stable prefix the run will reuse.
        val prompt = rendered.copy(
            dynamicSuffix = rendered.dynamicSuffix +
                "PLAN ONLY\nReply with a short numbered plan for this task: the steps you will take and what each " +
                "produces. Do not perform the task yet; it starts once the plan is approved.\n",
        )
        val draft = try {
            api.generate(prompt)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // A draft cut off at the limit was still billed: the tokens ride on the failure the engine reads.
            val spent = failure.spentTokens()
            throw SpentGenerationFailure(
                "$displayName: ${failure.message?.takeIf { it.isNotBlank() } ?: failure::class.simpleName}",
                failure,
                spent?.spentInputTokens,
                spent?.spentOutputTokens,
                spent?.spentCachedInputTokens,
                spent?.let { priced(it.spentInputTokens, it.spentOutputTokens, it.spentCachedInputTokens)?.first },
            )
        }
        val usage = billedUsage(draft)
        return ProviderPlanDraft(
            text = draft.text.take(MAX_PLAN_PREVIEW_CHARS),
            inputTokens = usage?.inputTokens,
            outputTokens = usage?.outputTokens,
            costUsd = usage?.costUsd,
            cacheHitFraction = usage?.cacheHitFraction,
            costUnknown = usage?.costUnknown == true,
        )
    }

    override suspend fun start(request: AgentTaskRequest): AgentRunHandle {
        val runId = mutex.withLock {
            val sequence = nextSequence++
            ProviderRunId("${id.value}/${request.taskRunId.value}/$sequence").also { providerRunId ->
                sessions[providerRunId] = Session(
                    request = request,
                    phase = MutableStateFlow(
                        if (request.requirePlanApproval) TextLlmSessionPhase.AwaitingApproval else TextLlmSessionPhase.Ready,
                    ),
                    planGenerated = MutableStateFlow(false),
                    generation = InterruptibleGeneration(api),
                )
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
            runId.value.substringAfterLast('/').toLongOrNull()?.let { recoveredSequence ->
                nextSequence = maxOf(nextSequence, recoveredSequence + 1L)
            }
            if (runId !in sessions) {
                sessions[runId] = Session(
                    request = request,
                    phase = MutableStateFlow(
                        if (request.requirePlanApproval && !planApproved) {
                            TextLlmSessionPhase.AwaitingApproval
                        } else {
                            TextLlmSessionPhase.Ready
                        },
                    ),
                    planGenerated = MutableStateFlow(planGenerated),
                    generation = InterruptibleGeneration(api),
                )
            }
        }
        return ProviderActionResult.Accepted
    }

    // The generation runs inside observe(): collecting it again would bill it again.
    override val observeDrivesBilledWork: Boolean = true

    override fun observe(runId: ProviderRunId): Flow<AgentEvent> = flow {
        val session = mutex.withLock { sessions[runId] }
            ?: error("$displayName session ${runId.value} is not available in this process")
        session.observing = true
        try {
            observeSession(runId, session)
        } finally {
            session.observing = false
            // A collector stopped from outside (the app closing, the task cancelled) leaves a cancelled session behind.
            if (session.phase.value == TextLlmSessionPhase.Cancelled) mutex.withLock { sessions.remove(runId) }
        }
    }

    private suspend fun FlowCollector<AgentEvent>.observeSession(runId: ProviderRunId, session: Session) {

        if (session.request.requirePlanApproval) {
            if (!session.planGenerated.value) {
                val preview = generateOrFail(runId, session) ?: return
                session.planResult = preview
                session.planGenerated.value = true
                emit(AgentEvent.PlanGenerated(runId = runId, summary = preview.text.take(MAX_PLAN_PREVIEW_CHARS)))
            }
            val phase = session.phase.filter { it != TextLlmSessionPhase.AwaitingApproval }.first()
            if (phase == TextLlmSessionPhase.Cancelled) {
                // The plan was generated, and billed, before it was cancelled.
                session.planResult?.let { emitUsage(runId, it) }
                emit(AgentEvent.Failed(runId, "$displayName session cancelled"))
                mutex.withLock { sessions.remove(runId) }
                return
            }
            emit(AgentEvent.PlanApproved(runId))
            // In-process sessions deliver the generation the user approved. A session
            // reconstructed by reconnect() after a restart only has the durable plan
            // flag, not the generation itself, so it executes exactly once here.
            val result = session.planResult ?: generateOrFail(runId, session) ?: return
            if (cancelledDuringGeneration(runId, session, result)) return
            emit(AgentEvent.ArtifactProduced(runId, responseArtifact(session.request, result.text, session.toolOutcomes)))
            emitUsage(runId, result)
        } else {
            if (session.phase.value == TextLlmSessionPhase.Cancelled) {
                emit(AgentEvent.Failed(runId, "$displayName session cancelled"))
                mutex.withLock { sessions.remove(runId) }
                return
            }
            val result = generateOrFail(runId, session) ?: return
            if (cancelledDuringGeneration(runId, session, result)) return
            emit(AgentEvent.ArtifactProduced(runId, responseArtifact(session.request, result.text, session.toolOutcomes)))
            emitUsage(runId, result)
        }
        emit(AgentEvent.Completed(runId))
        mutex.withLock { sessions.remove(runId) }
    }

    /** Token counts and, where the API reported cached input, the prompt-cache hit fraction; priced when the model has a price. */
    private suspend fun FlowCollector<AgentEvent>.emitUsage(runId: ProviderRunId, result: TextGenerationResult) {
        val usage = billedUsage(result) ?: return
        emit(usage.toEvent(runId))
    }

    /** What [result] cost: its tokens, and a price (or "unknown", which a dollar cap does not treat as free). */
    private fun billedUsage(result: TextGenerationResult): BilledUsage? {
        if (result.inputTokens == null && result.outputTokens == null) return null
        val (cost, unknown) = priced(result.inputTokens, result.outputTokens, result.cachedInputTokens) ?: (null to false)
        return BilledUsage(result.inputTokens, result.outputTokens, cost, result.cacheHitFraction, unknown)
    }

    /**
     * The cost of [inputTokens] and [outputTokens] (with its unknown flag): free for an anonymous keyless
     * endpoint, the list price for a priced model, otherwise null cost with the unknown flag set. Null when
     * there are no tokens to price.
     */
    private fun priced(inputTokens: Long?, outputTokens: Long?, cachedInputTokens: Long?): Pair<Double?, Boolean>? =
        pricedUsage(api, id.value, pricing, inputTokens, outputTokens, cachedInputTokens)

    private class BilledUsage(
        val inputTokens: Long?,
        val outputTokens: Long?,
        val costUsd: Double?,
        val cacheHitFraction: Float?,
        val costUnknown: Boolean,
    ) {
        fun toEvent(runId: ProviderRunId) = AgentEvent.UsageReported(
            runId = runId,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            costUsd = costUsd,
            cacheHitFraction = cacheHitFraction,
            costUnknown = costUnknown,
        )
    }

    /**
     * cancel() flips the phase and stops the streaming generation and the tool loop at their next step, but a
     * request already in flight can still come back. Its result must then be dropped, not delivered as the
     * artifact of a run the engine believes cancelled.
     */
    private suspend fun FlowCollector<AgentEvent>.cancelledDuringGeneration(
        runId: ProviderRunId,
        session: Session,
        result: TextGenerationResult,
    ): Boolean {
        if (session.phase.value != TextLlmSessionPhase.Cancelled) return false
        // Dropped, but not free: the provider billed it.
        emitUsage(runId, result)
        emit(AgentEvent.Failed(runId, "$displayName session cancelled"))
        mutex.withLock { sessions.remove(runId) }
        return true
    }

    /**
     * A provider refusal (HTTP 429, 5xx, bad key, unreachable host) fails the run at once with the
     * provider's reason. Letting it escape would read as an observer transport fault, which the
     * gateway retries with backoff while the task sits in Planning until the plan gate times out.
     */
    private suspend fun FlowCollector<AgentEvent>.generateOrFail(
        runId: ProviderRunId,
        session: Session,
    ): TextGenerationResult? = try {
        val toolbox = toolboxFor(session.request)
        val withTools = if (api is ToolCallingTextGenerationApi && toolbox != null) {
            try {
                val outcome = McpToolLoop().run(
                    api = api,
                    prompt = renderPrompt(session.request).text + "\n\n" + McpToolLoop.TOOL_GUIDANCE,
                    toolbox = toolbox,
                    // A cancelled run starts no further round and calls no further tool.
                    isCancelled = { session.phase.value == TextLlmSessionPhase.Cancelled },
                ) { call ->
                    val offer = toolbox.offers.firstOrNull { it.name == call.name }
                    emit(AgentEvent.Progress(runId, "Calling ${offer?.toolName ?: call.name} on ${offer?.serverName ?: "an MCP server"}"))
                }
                session.toolOutcomes = outcome.calls
                outcome.result
            } catch (_: McpToolLoop.ToolOfferRefused) {
                // A model or service that does not take tools still does the work without them.
                null
            }
        } else {
            null
        }
        // Streamed: visible reasoning goes out as Thinking, where memory can answer it mid-thought.
        withTools ?: session.generation.run(renderPrompt(session.request)) { thought -> emit(AgentEvent.Thinking(runId, thought)) }
    } catch (_: InterruptibleGeneration.GenerationCancelled) {
        failCancelled(runId)
    } catch (_: McpToolLoop.Cancelled) {
        failCancelled(runId)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        // A reply that was cut off or unusable was still billed: its tokens are reported before the failure.
        failure.spentTokens()?.let { spent ->
            priced(spent.spentInputTokens, spent.spentOutputTokens, spent.spentCachedInputTokens)?.let { (cost, unknown) ->
                emit(
                    AgentEvent.UsageReported(
                        runId = runId,
                        inputTokens = spent.spentInputTokens,
                        outputTokens = spent.spentOutputTokens,
                        costUsd = cost,
                        costUnknown = unknown,
                    ),
                )
            }
        }
        emit(AgentEvent.Failed(runId, "$displayName: ${failure.message?.takeIf { it.isNotBlank() } ?: failure::class.simpleName}"))
        mutex.withLock { sessions.remove(runId) }
        null
    }

    /** The run was cancelled while generating: it ends as cancelled, and nothing it made is delivered. */
    private suspend fun FlowCollector<AgentEvent>.failCancelled(runId: ProviderRunId): TextGenerationResult? {
        emit(AgentEvent.Failed(runId, "$displayName session cancelled"))
        mutex.withLock { sessions.remove(runId) }
        return null
    }

    /**
     * Memory's `⟦memory⟧` messages are taken while a generation streams and interrupt it (see
     * [InterruptibleGeneration]); anything else is declined, since each run is one generation.
     */
    override suspend fun sendMessage(runId: ProviderRunId, message: String): ProviderActionResult {
        val session = mutex.withLock { sessions[runId] }
        if (session != null && session.generation.offer(message)) return ProviderActionResult.Accepted
        return ProviderActionResult.Rejected(
            "$displayName uses one-shot task execution; start a new governed task for follow-up work.",
        )
    }

    override suspend fun approvePlan(runId: ProviderRunId): ProviderActionResult {
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
        val session = mutex.withLock { sessions[runId] } ?: return ProviderActionResult.Accepted
        session.phase.value = TextLlmSessionPhase.Cancelled
        // A generation already streaming stops at its next piece instead of running to its end, billed.
        session.generation.cancel()
        // Nobody is collecting it, so nobody will end it: a session cancelled before (or between) observers is dropped here.
        if (!session.observing) mutex.withLock { sessions.remove(runId) }
        return ProviderActionResult.Accepted
    }

    /** The run's MCP tools, when there is a host, the API can call functions and a server offers any. */
    private suspend fun toolboxFor(request: AgentTaskRequest): McpToolbox? {
        val source = tools ?: return null
        if (api !is ToolCallingTextGenerationApi) return null
        // A provider-side plan gate approves a generation made before approval; no tool may run
        // before a person approves, so such runs get none. The engine's own plan gate drafts the
        // plan without tools and starts the approved run ungated, which does get them.
        if (request.requirePlanApproval) return null
        val runKey = request.orchestrationContext.workflowRunId?.value ?: request.taskRunId.value
        return try {
            source.toolbox(runKey)?.takeIf { it.offers.isNotEmpty() }?.restrictedTo(request.allowedTools)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // No tools this run is not a failure of the run.
            null
        }
    }

    private fun responseArtifact(
        request: AgentTaskRequest,
        text: String,
        toolOutcomes: List<McpToolOutcome> = emptyList(),
    ): ProviderArtifact {
        val kind = inferArtifactKind(request)
        return ProviderArtifact(
            kind = kind,
            label = "$displayName response",
            textContent = text,
            mediaType = "text/markdown",
            metadata = buildMap {
                put("provider", id.value)
                put("taskRunId", request.taskRunId.value)
                if (toolOutcomes.isNotEmpty()) {
                    // The answer was written with external content in view (docs/THREAT_MODEL.md). The
                    // runtime's taint rule reads the standard label, so a downstream task that can change
                    // something is held at a gate; MCP_PROVENANCE_METADATA only says where it came from.
                    put(CONTEXT_PROVENANCE_METADATA_KEY, ContextProvenance.ExternalContent.name)
                    put(MCP_PROVENANCE_METADATA, "external-tool-results")
                    put(
                        MCP_TOOL_CALLS_METADATA,
                        toolOutcomes.joinToString(", ") { "${it.serverName}/${it.toolName}" + if (it.isError) " (failed)" else "" }.take(1_000),
                    )
                }
                when (kind) {
                    ArtifactKind.HallMonitorReport -> {
                        put(HALL_MONITOR_REPORT_ID_METADATA, request.taskRunId.value)
                    }
                    ArtifactKind.HallMonitorReview -> {
                        request.contextArtifacts
                            .firstOrNull { it.kind == ArtifactKind.HallMonitorReport }
                            ?.metadata
                            ?.get(HALL_MONITOR_REPORT_ID_METADATA)
                            ?.let { put(HALL_MONITOR_REPORT_ID_METADATA, it) }
                        parseHallMonitorVerdict(text)?.let { put(HALL_MONITOR_REVIEW_VERDICT_METADATA, it) }
                    }
                    else -> Unit
                }
            },
        )
    }

    /**
     * Role instructions and the stable prompt blocks come first, as the cacheable prefix; the task,
     * dynamic blocks, repository and artifacts follow. [CacheablePrompt.text] is the whole prompt.
     */
    private fun renderPrompt(request: AgentTaskRequest): CacheablePrompt = CacheablePrompt(
        stablePrefix = renderStablePrefix(request),
        dynamicSuffix = renderDynamicSuffix(request),
        reusePolicy = request.promptContext.reusePolicy,
    )

    private fun renderStablePrefix(request: AgentTaskRequest): String = buildString {
        append("ROLE INSTRUCTIONS\n")
        append(request.roleInstructions.trim())
        append("\n\n")
        request.promptContext.stablePrefix.forEach { block ->
            append(block.label.uppercase())
            append("\n")
            append(block.content.trim())
            append("\n\n")
        }
    }

    private fun renderDynamicSuffix(request: AgentTaskRequest): String = buildString {
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
            append("REPOSITORY CONTEXT\nSource: ")
            append(repository.source.displayName())
            append("\nLocation: ")
            append(repository.locationLabel())
            repository.defaultBranch?.let { branch -> append("\nBranch: ").append(branch) }
            append("\n\n")
        }
        request.contextArtifacts.filter { !it.textContent.isNullOrBlank() }.forEach { artifact ->
            append(artifact.label.uppercase())
            append("\n")
            append(artifact.textContent!!.trim())
            append("\n\n")
        }
        append("Return only the concrete work product for this assigned role. Do not claim repository access, shell execution, tests, or changes you did not actually perform.")
    }

    /**
     * The artifact kind for a text response: the task's declared artifact contract first, then the
     * role, and only then keywords in the objective (matched as whole words, so "qa" does not match
     * "equal" and role boilerplate mentioning failures does not turn a plan into a failure analysis).
     */
    private fun inferArtifactKind(request: AgentTaskRequest): ArtifactKind {
        val roleId = request.orchestrationContext.roleId?.value
        val signal = "${request.roleInstructions}\n${request.objective}".lowercase()
        when {
            roleId == "hall-monitor" -> return ArtifactKind.HallMonitorReport
            roleId == "antagonist" && "hall monitor" in signal -> return ArtifactKind.HallMonitorReview
        }
        request.requiredArtifacts.firstOrNull()?.let { return it }
        ROLE_ARTIFACT_KINDS[roleId]?.let { return it }
        val words = Regex("[a-z]+").findAll(request.objective.lowercase()).map { it.value }.toSet()
        return when {
            "release" in words -> ArtifactKind.Release
            "failure" in words || "root" in words && "cause" in words -> ArtifactKind.FailureAnalysis
            "verify" in words || "verification" in words || "qa" in words -> ArtifactKind.Verification
            "review" in words -> ArtifactKind.Review
            "architect" in words || "architecture" in words -> ArtifactKind.Architecture
            "design" in words || "ux" in words -> ArtifactKind.Design
            "requirement" in words || "requirements" in words || "product" in words -> ArtifactKind.Requirement
            "plan" in words -> ArtifactKind.TaskPlan
            else -> ArtifactKind.Research
        }
    }

    private fun parseHallMonitorVerdict(text: String): String? = text.lineSequence()
        .map(String::trim)
        .firstOrNull { it.startsWith("VERDICT:", ignoreCase = true) }
        ?.substringAfter(':')
        ?.trim()
        ?.lowercase()
        ?.takeIf { it in setOf("pass", "revise", "reject") }
}

/** Artifact metadata: the response was written with MCP tool results (external content) in view. */
const val MCP_PROVENANCE_METADATA = "contentProvenance"

/** Artifact metadata: which MCP server tools the run called. */
const val MCP_TOOL_CALLS_METADATA = "mcpToolCalls"

private val ROLE_ARTIFACT_KINDS: Map<String, ArtifactKind> = mapOf(
    "orchestrator" to ArtifactKind.TaskPlan,
    "product-manager" to ArtifactKind.Requirement,
    "researcher" to ArtifactKind.Research,
    "architect" to ArtifactKind.Architecture,
    "epa-representative" to ArtifactKind.EnvironmentSpecification,
    "ux-designer" to ArtifactKind.Design,
    "qa-engineer" to ArtifactKind.Verification,
    "adversarial-reviewer" to ArtifactKind.Review,
    "code-reviewer" to ArtifactKind.Review,
    "recovery-engineer" to ArtifactKind.FailureAnalysis,
    "release-engineer" to ArtifactKind.Release,
)

class OpenAiProvider(
    apiKeyProvider: LlmApiKeyProvider,
    model: String = "gpt-5.6",
    tools: McpToolSource? = null,
) : TextLlmProvider(
    id = AgentProviderId("openai"),
    displayName = "OpenAI / Codex",
    api = OpenAiResponsesApi(apiKeyProvider = apiKeyProvider, model = model),
    tools = tools,
)

class AnthropicProvider(
    apiKeyProvider: LlmApiKeyProvider,
    model: String = "claude-sonnet-5",
    tools: McpToolSource? = null,
) : TextLlmProvider(
    id = AgentProviderId("anthropic"),
    displayName = "Claude",
    api = AnthropicMessagesApi(apiKeyProvider = apiKeyProvider, model = model),
    tools = tools,
)

class GeminiProvider(
    apiKeyProvider: LlmApiKeyProvider,
    model: String = "gemini-3.8-flash",
    tools: McpToolSource? = null,
) : TextLlmProvider(
    id = AgentProviderId("gemini"),
    displayName = "Gemini",
    api = GeminiGenerateContentApi(apiKeyProvider = apiKeyProvider, model = model),
    tools = tools,
)

class XaiProvider(
    apiKeyProvider: LlmApiKeyProvider,
    model: String = "grok-4.6",
    tools: McpToolSource? = null,
) : TextLlmProvider(
    id = AgentProviderId("xai"),
    displayName = "Grok",
    api = XaiResponsesApi(apiKeyProvider = apiKeyProvider, model = model),
    tools = tools,
)
