package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ContextProvenance
import com.hereliesaz.geministrator.agentui.AgentUi
import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.duplex.DuplexSessionAttachment
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.inference.CompoundInferenceFabric
import com.hereliesaz.geministrator.inference.GovernedCompoundInferenceFabric
import com.hereliesaz.geministrator.inference.INFERENCE_INVOCATION_ID_METADATA_KEY
import com.hereliesaz.geministrator.inference.InferenceTerminalStatus
import com.hereliesaz.geministrator.memory.MemoryRuntimeBridge
import com.hereliesaz.geministrator.memory.MEMORY_MESSAGE_MARKER
import com.hereliesaz.geministrator.memory.MemorySessionObserver
import com.hereliesaz.geministrator.orchestration.ContextEvidence
import com.hereliesaz.geministrator.orchestration.ContextPackingInput
import com.hereliesaz.geministrator.orchestration.DeterministicLocalOrchestrationUtilities
import com.hereliesaz.geministrator.orchestration.LocalOrchestrationUtilityFamily
import com.hereliesaz.geministrator.orchestration.MemoryQueryInput
import com.hereliesaz.geministrator.providers.AgentEvent
import com.hereliesaz.geministrator.providers.AgentProvider
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.ProviderActionResult
import com.hereliesaz.geministrator.providers.ProviderArtifact
import com.hereliesaz.geministrator.providers.ProviderPlanDraft
import com.hereliesaz.geministrator.providers.PromptContextBlock
import com.hereliesaz.geministrator.retrieval.ArtifactChunkSource
import com.hereliesaz.geministrator.retrieval.ContextRetriever
import com.hereliesaz.geministrator.retrieval.SimilarityContextRetriever
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class ProviderBackedManagedSessionGateway(
    private val providerRegistry: AgentProviderRegistry,
    private val scope: CoroutineScope,
    private val memoryObserver: MemorySessionObserver = MemoryRuntimeBridge.observer,
    inferenceFabric: CompoundInferenceFabric = providerRegistry.inferenceFabric,
    private val orchestrationUtilities: LocalOrchestrationUtilityFamily =
        DeterministicLocalOrchestrationUtilities,
    /**
     * Repository and artifact chunks relevant to each request, offered to the Context Packer beside
     * recalled memory. Upstream artifacts only, by default.
     */
    private val contextRetriever: ContextRetriever? = SimilarityContextRetriever(listOf(ArtifactChunkSource)),
    /** Opt-in agent-neutral conversation hook; never a substitute for memory or workflow gates. */
    private val duplexAttachment: DuplexSessionAttachment? = null,
    /**
     * Each session's live thinking and progress lines for the creature's panel: bounded, in memory,
     * never persisted into workflow state nor passed to the duplex peer.
     */
    private val activity: SessionActivityFeeds = SessionActivityFeeds.Shared,
) : ManagedSessionGateway {
    private val inferenceFabric: CompoundInferenceFabric =
        if (inferenceFabric is GovernedCompoundInferenceFabric) {
            inferenceFabric
        } else {
            GovernedCompoundInferenceFabric(inferenceFabric, providerRegistry.genealogyGovernance)
        }

    private companion object {
        const val MAX_MEMORY_QUERIES = 6
        /** Token budget for retrieved repository and artifact chunks, on top of memory's own. */
        const val RETRIEVAL_TOKENS = 1_500
        const val APPROXIMATE_CHARS_PER_TOKEN = 4
        const val OBSERVER_RETRY_MILLIS = 1_000L
        const val MAX_OBSERVER_FAILURES = 10
        const val MAX_FAILURE_MESSAGE_CHARS = 500
        const val MAX_RECORDED_FAILURES = 32
    }

    private data class SessionSnapshot(
        val status: ManagedSessionStatus,
        val artifacts: List<ProviderArtifact> = emptyList(),
        val progress: ManagedSessionProgress? = null,
        val pendingUsage: ManagedSessionUsage? = null,
        /** The agent's open question to the keeper (docs/Agent-UI.md), as the message that asked it. */
        val keeperQuestion: String? = null,
        val answeredQuestionKey: String? = null,
        /** True once this process has seen a question or an answer, so [keeperQuestion] is authoritative. */
        val questionKnown: Boolean = false,
    )

    private val mutex = Mutex()
    private val snapshots = mutableMapOf<ManagedSessionHandle, SessionSnapshot>()
    private val observedEventKeys = mutableMapOf<ManagedSessionHandle, MutableSet<String>>()

    /** Each session's observer, kept so a cancel stops the generation it is watching ([cancel]) and a release frees it. */
    private val observerJobs = mutableMapOf<ManagedSessionHandle, Job>()

    /**
     * Failures this gateway already recorded against a provider for a session, by provider and reason. The
     * engine asks about failover for the same failure afterwards ([adviseFailover]); that ask is told, not
     * recorded again, so one failed session counts once toward the provider's circuit breaker and bench.
     * Bounded: a failure nobody asks about is dropped oldest first.
     */
    private val recordedFailures = LinkedHashMap<String, Int>()

    /** How many sessions this gateway still holds state for. */
    internal suspend fun trackedSessionCount(): Int = mutex.withLock { snapshots.size }

    override suspend fun resolveProvider(selection: ProviderSelectionRequest): AgentProviderId =
        selectProvider(selection, "No registered provider can satisfy this task").id

    override suspend fun createSession(request: ManagedSessionRequest): ManagedSessionHandle {
        val selection = request.providerSelection
        if (!selection.failoverAllowed || !providerRegistry.hasAlternatives()) return startSession(request, null)
        // Auto provider with more than one connected: a failure that is the provider's own moves the
        // same task to the next healthy one. Each provider is tried once; the bound is their number.
        val tried = linkedSetOf<AgentProviderId>()
        val failures = mutableListOf<ProviderFailure>()
        var attempt = request
        while (true) {
            val chosen = try {
                selectProvider(attempt.providerSelection.copy(repository = attempt.taskRequest.repository), "No registered provider can satisfy this task")
            } catch (unavailable: ManagedSessionFailure.ProviderUnavailable) {
                if (failures.isEmpty()) throw unavailable
                throw ManagedSessionFailure.ProviderOperationFailed(
                    ProviderFailover.allFailedMessage(failures),
                    AllProvidersFailedException(failures),
                )
            }
            tried += chosen.id
            try {
                val handle = startSession(attempt, chosen)
                failures.lastOrNull()?.let { last ->
                    val note = ProviderFailover.note(last, providerRegistry.displayName(handle.providerId))
                    providerRegistry.recordFailoverNote(note)
                    mutex.withLock {
                        snapshots[handle]?.let { snapshots[handle] = it.copy(progress = ManagedSessionProgress(message = note)) }
                    }
                    activity.record(handle.activityKey(), SessionActivityKind.Progress, note)
                }
                return handle
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                val benched = providerRegistry.recordFailure(chosen.id, failure.providerFailureMessage("Unable to start provider session"))
                    // Not the provider's own failure (a malformed request would fail anywhere): stop here.
                    ?: throw failure
                failures += benched
                // Whatever the engine pinned this call to, the next pick is open again, minus those tried.
                attempt = attempt.copy(
                    providerSelection = attempt.providerSelection.copy(
                        constraints = com.hereliesaz.geministrator.domain.ProviderConstraints.None,
                        requiredCapabilities = attempt.providerSelection.requiredCapabilities +
                            ((attempt.providerSelection.constraints as? com.hereliesaz.geministrator.domain.ProviderConstraints.RequireCapabilities)?.capabilities.orEmpty()),
                        preferredProviderId = null,
                        excludedProviderIds = tried.toSet(),
                    ),
                )
            }
        }
    }

    override suspend fun adviseFailover(
        failedProviderId: AgentProviderId,
        reason: String?,
        selection: ProviderSelectionRequest,
    ): FailoverAdvice {
        val failure = if (takeRecordedFailure(failedProviderId, reason)) {
            providerRegistry.classifyFailure(failedProviderId, reason)
        } else {
            providerRegistry.recordFailure(failedProviderId, reason)
        } ?: return FailoverAdvice.NotApplicable
        if (!providerRegistry.hasAlternatives()) return FailoverAdvice.NotApplicable
        if (!selection.failoverAllowed) return FailoverAdvice.Stop(ProviderFailover.pinnedMessage(failure))
        val tried = selection.excludedProviderIds + failedProviderId
        val next = try {
            providerRegistry.select(
                selection.copy(
                    constraints = com.hereliesaz.geministrator.domain.ProviderConstraints.None,
                    preferredProviderId = null,
                    excludedProviderIds = tried,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            return FailoverAdvice.Stop(ProviderFailover.allFailedMessage(providerRegistry.failuresOf(tried)))
        }
        val note = ProviderFailover.note(failure, providerRegistry.displayName(next.id))
        providerRegistry.recordFailoverNote(note)
        return FailoverAdvice.Restart(note)
    }

    private suspend fun startSession(request: ManagedSessionRequest, chosen: AgentProvider?): ManagedSessionHandle {
        val requiredCapabilities = buildSet {
            addAll(request.providerSelection.requiredCapabilities)
            val constraints = request.providerSelection.constraints
            if (constraints is com.hereliesaz.geministrator.domain.ProviderConstraints.RequireCapabilities) {
                addAll(constraints.capabilities)
            }
        }
        val recalledRequest = request.taskRequest
            .copy(requiredCapabilities = requiredCapabilities)
            .withRecalledMemory()
        val provider = chosen ?: selectProvider(
            request.providerSelection.copy(repository = recalledRequest.repository),
            "No registered provider can satisfy this task",
        )
        val providerCapabilities = provider.capabilities()
        // Only an agent the keeper can reply to mid-task is told how to ask the keeper a question.
        val askingRequest = if (AgentCapability.Messaging in providerCapabilities.supported) {
            recalledRequest.withKeeperQuestionInstructions()
        } else {
            recalledRequest
        }
        val prepared = inferenceFabric.prepareDispatch(
            request = askingRequest,
            providerId = provider.id,
            capabilities = providerCapabilities,
        )
        val taskRequest = prepared.request
        var startedProviderRunId: ProviderRunId? = null
        return try {
            providerOperation("Unable to start provider session") {
                val run = provider.start(taskRequest)
                startedProviderRunId = run.providerRunId
                inferenceFabric.bindProviderRun(
                    invocationId = prepared.plan.invocationId,
                    taskRunId = taskRequest.taskRunId,
                    providerId = provider.id,
                    providerRunId = run.providerRunId,
                )
                val handle = ManagedSessionHandle(
                    taskRunId = taskRequest.taskRunId,
                    providerId = provider.id,
                    providerRunId = run.providerRunId,
                    inferenceInvocationId = prepared.plan.invocationId,
                )
                recordMemory { memoryObserver.onSessionStarted(handle, taskRequest) }
                registerAndObserve(
                    handle = handle,
                    initialStatus = if (taskRequest.requirePlanApproval) {
                        ManagedSessionStatus.Planning
                    } else {
                        ManagedSessionStatus.Running
                    },
                )
                // No suspension occurs between clearing the rollback marker and returning the
                // committed handle to the caller.
                startedProviderRunId = null
                handle
            }
        } catch (failure: CancellationException) {
            cancelUnreturnedProviderRun(provider, startedProviderRunId)

            inferenceFabric.recordTerminal(
                prepared.plan.invocationId,
                InferenceTerminalStatus.Cancelled,
                "Provider start cancelled",
            )
            throw failure
        } catch (failure: Throwable) {
            cancelUnreturnedProviderRun(provider, startedProviderRunId)
            inferenceFabric.recordTerminal(
                prepared.plan.invocationId,
                InferenceTerminalStatus.Failed,
                failure.providerFailureMessage("Unable to start provider session"),
            )
            // Failure bookkeeping (and the bench) is the caller's: createSession classifies it.
            if (chosen == null) {
                recordSessionFailure(provider.id, failure.providerFailureMessage("Unable to start provider session"))
            }
            throw failure
        }
    }

    private suspend fun cancelUnreturnedProviderRun(
        provider: AgentProvider,
        runId: ProviderRunId?,
    ) {
        if (runId == null) return
        withContext(NonCancellable) {
            runCatching { provider.cancel(runId) }
        }
    }

    override suspend fun draftPlan(providerId: AgentProviderId, request: AgentTaskRequest): String? =
        draftPlanWithUsage(providerId, request)?.text

    override suspend fun draftPlanWithUsage(providerId: AgentProviderId, request: AgentTaskRequest): ProviderPlanDraft? {
        val provider = providerRegistry.provider(providerId)
            ?: throw ManagedSessionFailure.ProviderUnavailable("Provider ${providerId.value} is not registered")
        return providerOperation("Unable to draft a plan with ${providerId.value}") {
            provider.draftPlanWithUsage(request.withRecalledMemory())
                ?.let { draft -> draft.copy(text = draft.text.trim()) }
                ?.takeIf { it.text.isNotEmpty() }
        }
    }

    private suspend fun AgentTaskRequest.withRecalledMemory(): AgentTaskRequest {
        val memory = MemoryRuntimeBridge.promptContextProvider
        suspend fun <T> guarded(default: T, block: suspend () -> T): T = try {
            block()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Throwable) {
            default
        }

        // The request's own terms and their rarity feed the composer; a first pass's uncommon
        // result words feed one second-pass query.
        val hints = guarded(com.hereliesaz.geministrator.memory.MemoryQueryHints()) { memory.queryHints(this) }
        val queryInput = MemoryQueryInput(
            objective = objective,
            knownEntities = hints.entities,
            knownActions = hints.actions,
            codeSymbols = hints.codeSymbols,
            termDocumentFrequency = hints.documentFrequency,
            maxQueries = MAX_MEMORY_QUERIES,
        )
        var queryPlan = orchestrationUtilities.composeMemoryQueries(queryInput)
        val recallMemory = !queryPlan.enoughEvidence && queryPlan.queries.isNotEmpty()
        if (recallMemory) {
            val feedback = guarded(emptyMap()) { memory.feedbackTerms(this, queryPlan) }
            if (feedback.isNotEmpty()) {
                queryPlan = orchestrationUtilities.composeMemoryQueries(
                    queryInput.copy(
                        feedbackTerms = feedback.keys.toList(),
                        termDocumentFrequency = queryInput.termDocumentFrequency + feedback,
                    ),
                )
            }
        }

        val recalled = if (recallMemory) {
            guarded(com.hereliesaz.geministrator.memory.MemoryPromptRecall()) { memory.recallFor(this, queryPlan) }
        } else {
            com.hereliesaz.geministrator.memory.MemoryPromptRecall()
        }
        // A chunk already present in the prompt (an artifact the handoff carries, say) is not repeated.
        val present = (promptContext.stablePrefix + promptContext.dynamicContext).map(PromptContextBlock::content)
        val retrieved = contextRetriever?.let { retriever -> guarded(emptyList()) { retriever.retrieve(this) } }.orEmpty()
            .filterNot { hit -> present.any { hit.chunk.text in it } }
        if (recalled.blocks.isEmpty() && recalled.memoryAddresses.isEmpty() && retrieved.isEmpty()) return this

        val blocksById = linkedMapOf<String, PromptContextBlock>()
        val memoryEvidence = recalled.blocks.mapIndexed { index, block ->
            blocksById["memory-context-$index"] = block.copy(provenance = block.provenance ?: ContextProvenance.MemoryRecall)
            ContextEvidence(
                id = "memory-context-$index",
                estimatedTokens = approximateTokens(block.content),
                priority = recalled.blocks.size - index,
                text = block.content,
            )
        }
        // Retrieved chunks rank by similarity among themselves and below every memory block.
        val retrievedEvidence = retrieved.mapIndexed { index, hit ->
            val id = "retrieved-context-$index"
            blocksById[id] = PromptContextBlock(
                label = hit.chunk.label,
                content = hit.chunk.text,
                provenance = ContextProvenance.RepositoryContent,
            )
            ContextEvidence(
                id = id,
                estimatedTokens = approximateTokens(hit.chunk.text),
                priority = -(index + 1),
                text = hit.chunk.text,
            )
        }
        val memoryBudget = recalled.maxContextTokens ?: memoryEvidence.sumOf(ContextEvidence::estimatedTokens)
        val retrievalBudget = minOf(RETRIEVAL_TOKENS, retrievedEvidence.sumOf(ContextEvidence::estimatedTokens))
        val packing = orchestrationUtilities.packContext(
            ContextPackingInput(
                tokenBudget = memoryBudget + retrievalBudget,
                evidence = memoryEvidence + retrievedEvidence,
            ),
        )
        // The packer's prompt order: the most important block first, the next most important last.
        val selectedBlocks = packing.selectedEvidenceIds.mapNotNull(blocksById::get)

        return copy(
            promptContext = promptContext.copy(
                dynamicContext = promptContext.dynamicContext + selectedBlocks,
            ),
            compoundInference = compoundInference.copy(
                genealogy = compoundInference.genealogy.copy(
                    memoryAddresses = compoundInference.genealogy.memoryAddresses + recalled.memoryAddresses,
                ),
            ),
        )
    }

    /** Adds how to ask the keeper a declarative question ([AgentUi.promptInstruction]) to the role's context. */
    private fun AgentTaskRequest.withKeeperQuestionInstructions(): AgentTaskRequest {
        if (promptContext.stablePrefix.any { it.label == AgentUi.PROMPT_LABEL }) return this
        return copy(
            promptContext = promptContext.copy(
                stablePrefix = promptContext.stablePrefix + PromptContextBlock(AgentUi.PROMPT_LABEL, AgentUi.promptInstruction),
            ),
        )
    }

    private fun approximateTokens(text: String): Int =
        ((text.length + APPROXIMATE_CHARS_PER_TOKEN - 1) / APPROXIMATE_CHARS_PER_TOKEN).coerceAtLeast(1)

    override suspend fun reconnect(
        handle: ManagedSessionHandle,
        initialStatus: ManagedSessionStatus,
    ) {
        providerFor(handle)
        providerOperation("Unable to reconnect provider session ${handle.providerRunId.value}") {
            registerAndObserve(handle, initialStatus)
        }
    }

    override suspend fun reconnect(
        handle: ManagedSessionHandle,
        initialStatus: ManagedSessionStatus,
        request: AgentTaskRequest,
        providerPlan: String?,
    ) {
        val provider = providerFor(handle)
        providerOperation("Unable to reconstruct provider session ${handle.providerRunId.value}") {
            when (
                val result = provider.reconnect(
                    runId = handle.providerRunId,
                    request = request,
                    planGenerated = initialStatus != ManagedSessionStatus.Planning,
                    planApproved = initialStatus !in setOf(
                        ManagedSessionStatus.Planning,
                        ManagedSessionStatus.AwaitingApproval,
                    ),
                    planPreview = providerPlan,
                )
            ) {
                ProviderActionResult.Accepted -> Unit
                is ProviderActionResult.Rejected -> error(result.reason)
            }
            recordMemory { memoryObserver.onSessionStarted(handle, request) }
            registerAndObserve(handle, initialStatus)
        }
    }

    override suspend fun status(handle: ManagedSessionHandle): ManagedSessionStatus =
        mutex.withLock { snapshots[handle]?.status ?: ManagedSessionStatus.Unknown }

    override suspend fun progress(handle: ManagedSessionHandle): ManagedSessionProgress? =
        mutex.withLock { snapshots[handle]?.progress }

    override suspend fun message(
        handle: ManagedSessionHandle,
        message: String,
    ): ProviderActionResult = providerOperation("Unable to message provider session ${handle.providerRunId.value}") {
        // A user's message carries memory's cue cloud for its words; memory's own messages go as they are.
        val outgoing = if (message.startsWith(MEMORY_MESSAGE_MARKER)) {
            message
        } else {
            runCatching { memoryObserver.annotateUserMessage(handle, message) }.getOrDefault(message)
        }
        providerFor(handle).sendMessage(handle.providerRunId, outgoing)
    }

    override suspend fun keeperQuestion(handle: ManagedSessionHandle): KeeperQuestionState? =
        mutex.withLock {
            val snapshot = snapshots[handle] ?: return@withLock null
            if (!snapshot.questionKnown) return@withLock null
            KeeperQuestionState(pending = snapshot.keeperQuestion, answeredKey = snapshot.answeredQuestionKey)
        }

    override suspend fun answerKeeperQuestion(
        handle: ManagedSessionHandle,
        questionKey: String,
        answer: String,
    ): ProviderActionResult = providerOperation("Unable to answer provider session ${handle.providerRunId.value}") {
        // The answer is structured data for the agent; memory's cue cloud is not added to it.
        val result = providerFor(handle).sendMessage(handle.providerRunId, answer)
        if (result is ProviderActionResult.Accepted) {
            mutex.withLock {
                val current = snapshots[handle] ?: SessionSnapshot(ManagedSessionStatus.Unknown)
                snapshots[handle] = current.copy(
                    keeperQuestion = current.keeperQuestion?.takeUnless { AgentUi.questionKey(it) == questionKey },
                    answeredQuestionKey = questionKey,
                    questionKnown = true,
                )
            }
        }
        result
    }

    override suspend fun approvePlan(handle: ManagedSessionHandle): ProviderActionResult =
        providerOperation("Unable to approve provider session ${handle.providerRunId.value}") {
            val result = providerFor(handle).approvePlan(handle.providerRunId)
            if (result is ProviderActionResult.Accepted) {
                mutex.withLock {
                    val current = snapshots[handle] ?: return@withLock
                    if (!current.status.isTerminal()) {
                        snapshots[handle] = current.copy(status = ManagedSessionStatus.Running)
                    }
                }
            }
            result
        }

    override suspend fun cancel(handle: ManagedSessionHandle): ProviderActionResult {
        val alreadyTerminal = mutex.withLock { snapshots[handle]?.status?.isTerminal() == true }
        if (alreadyTerminal) return ProviderActionResult.Accepted

        return providerOperation("Unable to cancel provider session ${handle.providerRunId.value}") {
            val result = providerFor(handle).cancel(handle.providerRunId)
            if (result is ProviderActionResult.Accepted) {
                val observer = mutex.withLock {
                    val current = snapshots[handle] ?: SessionSnapshot(ManagedSessionStatus.Unknown)
                    snapshots[handle] = current.copy(status = ManagedSessionStatus.Failed)
                    observerJobs.remove(handle)
                }
                // The provider was told to stop; the observer is what keeps a generation streaming or a
                // tool loop running inside this process, so it is stopped too.
                observer?.cancel()
                inferenceFabric.recordTerminal(
                    handle.providerId,
                    handle.providerRunId,
                    InferenceTerminalStatus.Cancelled,
                )
                recordMemory { memoryObserver.onSessionFinished(handle, ManagedSessionStatus.Failed) }
                recordMemory { duplexAttachment?.onSessionFinished(handle, ManagedSessionStatus.Failed) }
            }
            result
        }
    }

    override suspend fun artifacts(handle: ManagedSessionHandle): List<ProviderArtifact> =
        mutex.withLock { snapshots[handle]?.artifacts.orEmpty() }

    override suspend fun release(handle: ManagedSessionHandle) {
        val observer = mutex.withLock {
            snapshots.remove(handle)
            observedEventKeys.remove(handle)
            observerJobs.remove(handle)
        }
        observer?.cancel()
    }

    override suspend fun usageReport(handle: ManagedSessionHandle): ManagedSessionUsage? =
        mutex.withLock {
            val usage = snapshots[handle]?.pendingUsage ?: return@withLock null
            snapshots[handle] = snapshots[handle]!!.copy(pendingUsage = null)
            usage
        }

    private suspend fun registerAndObserve(
        handle: ManagedSessionHandle,
        initialStatus: ManagedSessionStatus,
    ) {
        val shouldObserve = mutex.withLock {
            if (snapshots.containsKey(handle)) {
                false
            } else {
                snapshots[handle] = SessionSnapshot(initialStatus)
                true
            }
        }
        if (!shouldObserve) return
        // Only a configured task binding may participate, and attachment failures never fail work.
        recordMemory { duplexAttachment?.onSessionStarted(handle, this) }

        val provider = providerFor(handle)
        val observer = scope.launch {
            var consecutiveFailures = 0
            while (isActive && !handle.isTerminal()) {
                try {
                    provider.observe(handle.providerRunId).collect { event -> applyEvent(handle, event) }
                    consecutiveFailures = 0
                    if (provider.observeDrivesBilledWork && !handle.isTerminal()) {
                        // The generation ran and ended without a result event; collecting again would pay for it again.
                        throw IllegalStateException("The provider's work ended without a result")
                    }
                    if (!handle.isTerminal()) delay(OBSERVER_RETRY_MILLIS)
                } catch (failure: CancellationException) {
                    throw failure
                } catch (failure: Exception) {
                    // A billed observer is not retried: its next collect would run the paid work again.
                    consecutiveFailures = if (provider.observeDrivesBilledWork) MAX_OBSERVER_FAILURES else consecutiveFailures + 1
                    if (consecutiveFailures >= MAX_OBSERVER_FAILURES) {
                        val failed = mutex.withLock {
                            val current = snapshots[handle] ?: return@withLock false
                            if (current.status != ManagedSessionStatus.Completed && !current.status.isTerminal()) {
                                // Recorded before the failure is visible, so the engine's failover question about it is told.
                                recordSessionFailureLocked(handle.providerId, failure.message)
                                snapshots[handle] = current.copy(
                                    status = ManagedSessionStatus.Failed,
                                    progress = ManagedSessionProgress(
                                        message = failure.message?.takeIf { it.isNotBlank() }?.take(MAX_FAILURE_MESSAGE_CHARS)
                                            ?: failure::class.simpleName,
                                    ),
                                )
                                true
                            } else {
                                false
                            }
                        }
                        if (failed) {
                            inferenceFabric.recordTerminal(
                                handle.providerId,
                                handle.providerRunId,
                                InferenceTerminalStatus.Failed,
                                "Provider observation failed repeatedly",
                            )
                            recordMemory { memoryObserver.onSessionFinished(handle, ManagedSessionStatus.Failed) }
                            recordMemory { duplexAttachment?.onSessionFinished(handle, ManagedSessionStatus.Failed) }
                        }
                        return@launch
                    }
                    val backoffBase = OBSERVER_RETRY_MILLIS * (1L shl minOf(consecutiveFailures - 1, 5))
                    val jitter = (backoffBase * 0.25 * kotlin.random.Random.nextDouble()).toLong()
                    delay(backoffBase + jitter)
                }
            }
        }
        mutex.withLock {
            // A session released or cancelled while it was being registered has no snapshot to watch for.
            if (snapshots.containsKey(handle)) observerJobs[handle] = observer else observer.cancel()
        }
    }

    /** Records a provider's failure for one session; [adviseFailover] is later told it was recorded. */
    private suspend fun recordSessionFailure(providerId: AgentProviderId, reason: String?): ProviderFailure? =
        mutex.withLock { recordSessionFailureLocked(providerId, reason) }

    /** [recordSessionFailure] for a caller that already holds [mutex]. */
    private suspend fun recordSessionFailureLocked(providerId: AgentProviderId, reason: String?): ProviderFailure? {
        val failure = providerRegistry.recordFailure(providerId, reason)
        val key = failureKey(providerId, reason)
        recordedFailures[key] = (recordedFailures[key] ?: 0) + 1
        while (recordedFailures.size > MAX_RECORDED_FAILURES) recordedFailures.remove(recordedFailures.keys.first())
        return failure
    }

    private suspend fun takeRecordedFailure(providerId: AgentProviderId, reason: String?): Boolean = mutex.withLock {
        val key = failureKey(providerId, reason)
        val count = recordedFailures[key] ?: return@withLock false
        if (count <= 1) recordedFailures.remove(key) else recordedFailures[key] = count - 1
        true
    }

    private fun failureKey(providerId: AgentProviderId, reason: String?): String =
        providerId.value + "\u0000" + reason?.trim().orEmpty()

    private suspend fun ManagedSessionHandle.isTerminal(): Boolean = mutex.withLock {
        snapshots[this]?.status?.isTerminal() == true
    }

    private fun ManagedSessionStatus.isTerminal(): Boolean =
        this == ManagedSessionStatus.Completed || this == ManagedSessionStatus.Failed

    private suspend fun selectProvider(
        selection: ProviderSelectionRequest,
        fallbackMessage: String,
    ): AgentProvider = try {
        providerRegistry.select(selection)
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: ManagedSessionFailure.ProviderUnavailable) {
        throw failure
    } catch (failure: Throwable) {
        throw ManagedSessionFailure.ProviderUnavailable(
            failure.providerFailureMessage(fallbackMessage),
            failure,
        )
    }

    private fun providerFor(handle: ManagedSessionHandle): AgentProvider =
        providerRegistry.provider(handle.providerId)
            ?: throw ManagedSessionFailure.ProviderUnavailable(
                "Provider ${handle.providerId.value} is no longer registered",
            )

    private suspend fun <T> providerOperation(
        fallbackMessage: String,
        block: suspend () -> T,
    ): T = try {
        block()
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: ManagedSessionFailure.ProviderUnavailable) {
        throw failure
    } catch (failure: ManagedSessionFailure.ProviderOperationFailed) {
        throw failure
    } catch (failure: Throwable) {
        throw ManagedSessionFailure.ProviderOperationFailed(
            failure.providerFailureMessage(fallbackMessage),
            failure,
        )
    }

    private suspend fun recordMemory(block: suspend () -> Unit) {
        try {
            block()
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Throwable) {
            // Memory is a separate subsystem. A persistence/consolidation problem must not crash
            // or alter the state of the live provider session.
        }
    }

    private fun Throwable.providerFailureMessage(fallbackMessage: String): String {
        var current: Throwable? = this
        while (current != null) {
            current.message?.trim()?.takeIf(String::isNotEmpty)?.let { return it }
            current = current.cause
        }
        val typeName = this::class.simpleName?.takeIf(String::isNotBlank)
        return typeName?.let { "$fallbackMessage ($it)" } ?: fallbackMessage
    }

    private suspend fun applyEvent(
        handle: ManagedSessionHandle,
        event: AgentEvent,
    ) {
        val eventKey = event.memoryReplayKey()
        val unseen = mutex.withLock {
            observedEventKeys.getOrPut(handle) { linkedSetOf() }.add(eventKey)
        }
        if (!unseen) return

        val effectiveEvent = when (event) {
            is AgentEvent.ArtifactProduced -> {
                val invocationId = handle.inferenceInvocationId
                if (invocationId == null) {
                    event
                } else {
                    event.copy(
                        artifact = event.artifact.copy(
                            metadata = event.artifact.metadata +
                                (INFERENCE_INVOCATION_ID_METADATA_KEY to invocationId),
                        ),
                    )
                }
            }
            else -> event
        }

        var changed = false
        var alreadyTerminal = false
        var terminalStatus: ManagedSessionStatus? = null
        mutex.withLock {
            val current = snapshots[handle] ?: SessionSnapshot(ManagedSessionStatus.Unknown)
            if (current.status.isTerminal()) {
                alreadyTerminal = true
                // What the session spent is spent whatever state it is in: a report that arrives once it
                // ended is added, never dropped. Nothing else about an ended session changes.
                if (effectiveEvent is AgentEvent.UsageReported) {
                    snapshots[handle] = current.copy(pendingUsage = current.pendingUsage.plusReport(effectiveEvent.sanitizedUsage()))
                    changed = true
                }
                return@withLock
            }
            val next = when (effectiveEvent) {
                is AgentEvent.PlanGenerated -> current.copy(
                    status = ManagedSessionStatus.AwaitingApproval,
                    progress = ManagedSessionProgress(
                        fraction = null,
                        message = effectiveEvent.summary.takeIf(String::isNotBlank),
                    ),
                )
                is AgentEvent.PlanApproved -> current.copy(status = ManagedSessionStatus.Running)
                is AgentEvent.Progress -> current.copy(
                    status = if (
                        current.status == ManagedSessionStatus.Planning ||
                        current.status == ManagedSessionStatus.AwaitingApproval
                    ) {
                        current.status
                    } else {
                        ManagedSessionStatus.Running
                    },
                    progress = ManagedSessionProgress(
                        fraction = effectiveEvent.fraction,
                        message = effectiveEvent.message.takeIf { it.isNotBlank() },
                    ),
                )
                // A message that asks the keeper something becomes the session's open question.
                is AgentEvent.Message -> if (AgentUi.isRequest(effectiveEvent.content)) {
                    current.copy(
                        keeperQuestion = effectiveEvent.content.take(AgentUi.MAX_MESSAGE_CHARS + 1),
                        questionKnown = true,
                    )
                } else {
                    current
                }
                is AgentEvent.Thinking -> current
                is AgentEvent.ArtifactProduced -> current.copy(
                    artifacts = current.artifacts.upsertArtifact(effectiveEvent.artifact),
                )
                is AgentEvent.Completed -> current.copy(
                    keeperQuestion = null,
                    status = ManagedSessionStatus.Completed,
                    progress = ManagedSessionProgress(
                        fraction = 1f,
                        message = current.progress?.message,
                    ),
                )
                // Keep the provider's reason; it becomes the task's last status and the failure reason.
                is AgentEvent.Failed -> {
                    // Recorded before the failure is visible, so the engine's failover question about it is told.
                    recordSessionFailureLocked(handle.providerId, effectiveEvent.reason)
                    current.copy(
                        keeperQuestion = null,
                        status = ManagedSessionStatus.Failed,
                        progress = ManagedSessionProgress(message = effectiveEvent.reason.takeIf { it.isNotBlank() }),
                    )
                }
                // Summed with what the engine has not read yet: two reports between polls are both spend.
                is AgentEvent.UsageReported -> current.copy(
                    pendingUsage = current.pendingUsage.plusReport(effectiveEvent.sanitizedUsage()),
                )
            }
            snapshots[handle] = next
            changed = true
            if (!current.status.isTerminal() && next.status.isTerminal()) {
                terminalStatus = next.status
            }
        }
        if (!changed) return
        when (effectiveEvent) {
            is AgentEvent.ArtifactProduced -> inferenceFabric.recordArtifact(
                handle.providerId,
                handle.providerRunId,
                effectiveEvent.artifact,
            )
            is AgentEvent.UsageReported -> effectiveEvent.sanitizedUsage().let { usage ->
                inferenceFabric.recordUsage(
                    providerId = handle.providerId,
                    providerRunId = handle.providerRunId,
                    inputTokens = usage.inputTokens,
                    outputTokens = usage.outputTokens,
                    costUsd = usage.costUsd,
                    cacheHitFraction = usage.cacheHitFraction,
                    latencyMillis = usage.latencyMillis,
                )
            }
            else -> Unit
        }
        // Nothing but usage is recorded about a session that had already ended.
        if (alreadyTerminal) return
        activity.record(handle, effectiveEvent)
        recordMemory { memoryObserver.onSessionEvent(handle, effectiveEvent) { text -> message(handle, text) } }
        recordMemory { duplexAttachment?.onSessionEvent(handle, effectiveEvent) }
        terminalStatus?.let { status ->
            inferenceFabric.recordTerminal(
                providerId = handle.providerId,
                providerRunId = handle.providerRunId,
                status = if (status == ManagedSessionStatus.Completed) {
                    InferenceTerminalStatus.Completed
                } else {
                    InferenceTerminalStatus.Failed
                },
                reason = (effectiveEvent as? AgentEvent.Failed)?.reason,
            )
            if (status == ManagedSessionStatus.Completed) {
                providerRegistry.recordOutcome(handle.providerId, success = true)
            }
            recordMemory { memoryObserver.onSessionFinished(handle, status) }
            recordMemory { duplexAttachment?.onSessionFinished(handle, status) }
        }
    }

    private fun AgentEvent.UsageReported.sanitizedUsage(): ManagedSessionUsage = ManagedSessionUsage(
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        costUsd = costUsd,
        cacheHitFraction = cacheHitFraction,
        latencyMillis = latencyMillis,
        costUnknown = costUnknown,
    ).sanitized()

    private fun AgentEvent.memoryReplayKey(): String = when (this) {
        is AgentEvent.PlanGenerated -> "plan:${runId.value}:${summary}"
        is AgentEvent.PlanApproved -> "approved:${runId.value}"
        is AgentEvent.Progress -> "progress:${runId.value}:${fraction}:${message}"
        is AgentEvent.Message -> "message:${runId.value}:${content}"
        is AgentEvent.Thinking -> "thinking:${runId.value}:${content}"
        is AgentEvent.ArtifactProduced -> "artifact:${runId.value}:${artifact.identityKey()}"
        is AgentEvent.Completed -> "completed:${runId.value}"
        is AgentEvent.Failed -> "failed:${runId.value}:${reason}"
        is AgentEvent.UsageReported ->
            "usage:${runId.value}:${inputTokens}:${outputTokens}:${costUsd}:${cacheHitFraction}:${latencyMillis}:${costUnknown}"
    }

    private fun List<ProviderArtifact>.upsertArtifact(artifact: ProviderArtifact): List<ProviderArtifact> {
        val identity = artifact.identityKey()
        val index = indexOfFirst { it.identityKey() == identity }
        if (index < 0) return this + artifact
        if (this[index] == artifact) return this
        return toMutableList().also { it[index] = artifact }
    }

    private fun ProviderArtifact.identityKey(): String = listOf(
        kind.name,
        uri.orEmpty(),
        metadata["source"].orEmpty(),
        metadata["baseCommitId"].orEmpty(),
        metadata["command"].orEmpty(),
        label,
    ).joinToString("\u001f")

}
