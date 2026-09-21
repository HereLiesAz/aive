package com.hereliesaz.geministrator.inference

import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.providers.AgentCapabilities
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.PromptCacheMode
import com.hereliesaz.geministrator.providers.ProviderArtifact
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Runtime description of a locally or remotely executable model asset. */
data class InferenceModelDescriptor(
    val logicalModelId: String,
    val baseModelId: String? = null,
    val adapterId: String? = null,
    val precision: String? = null,
    val backend: String? = null,
    val artifactPath: String? = null,
    val metadataJson: String? = null,
    val capabilities: Set<String> = emptySet(),
    val releaseDigest: String? = null,
) {
    init {
        require(logicalModelId.isNotBlank()) { "logicalModelId must not be blank" }
    }
}

/** Runtime description of an execution-capable provider agent. */
data class InferenceAgentDescriptor(
    val providerId: AgentProviderId,
    val capabilities: Set<AgentCapability>,
    val promptCacheModes: Set<PromptCacheMode>,
    val requiresEnvironmentPlanning: Boolean,
)

enum class InferenceDataKind {
    Artifact,
    Repository,
    Memory,
    ToolEvidence,
    ImportedReference,
}

/** Symbolic governed data reference. Payloads remain in their authoritative subsystem. */
data class InferenceDataDescriptor(
    val dataId: String,
    val kind: InferenceDataKind,
    val artifactId: ArtifactId? = null,
    val producingTaskRunId: TaskRunId? = null,
    val label: String? = null,
    val mediaType: String? = null,
) {
    init {
        require(dataId.isNotBlank()) { "dataId must not be blank" }
    }
}

interface InferenceModelRegistry {
    suspend fun register(model: InferenceModelDescriptor)
    suspend fun get(logicalModelId: String): InferenceModelDescriptor?
    suspend fun all(): List<InferenceModelDescriptor>
}

interface InferenceAgentRegistry {
    suspend fun register(agent: InferenceAgentDescriptor)
    suspend fun get(providerId: AgentProviderId): InferenceAgentDescriptor?
    suspend fun all(): List<InferenceAgentDescriptor>
}

interface InferenceDataRegistry {
    suspend fun register(data: InferenceDataDescriptor)
    suspend fun get(dataId: String): InferenceDataDescriptor?
    suspend fun all(): List<InferenceDataDescriptor>
}

private class MutableRegistry<K, V>(private val keyOf: (V) -> K) {
    private val mutex = Mutex()
    private val values = linkedMapOf<K, V>()

    suspend fun put(value: V) = mutex.withLock {
        values[keyOf(value)] = value
    }

    suspend fun get(key: K): V? = mutex.withLock { values[key] }

    suspend fun all(): List<V> = mutex.withLock { values.values.toList() }
}

class InMemoryInferenceModelRegistry : InferenceModelRegistry {
    private val delegate = MutableRegistry<String, InferenceModelDescriptor>(InferenceModelDescriptor::logicalModelId)

    override suspend fun register(model: InferenceModelDescriptor) = delegate.put(model)
    override suspend fun get(logicalModelId: String): InferenceModelDescriptor? = delegate.get(logicalModelId)
    override suspend fun all(): List<InferenceModelDescriptor> = delegate.all()
}

class InMemoryInferenceAgentRegistry : InferenceAgentRegistry {
    private val delegate = MutableRegistry<AgentProviderId, InferenceAgentDescriptor>(InferenceAgentDescriptor::providerId)

    override suspend fun register(agent: InferenceAgentDescriptor) = delegate.put(agent)
    override suspend fun get(providerId: AgentProviderId): InferenceAgentDescriptor? = delegate.get(providerId)
    override suspend fun all(): List<InferenceAgentDescriptor> = delegate.all()
}

class InMemoryInferenceDataRegistry : InferenceDataRegistry {
    private val delegate = MutableRegistry<String, InferenceDataDescriptor>(InferenceDataDescriptor::dataId)

    override suspend fun register(data: InferenceDataDescriptor) = delegate.put(data)
    override suspend fun get(dataId: String): InferenceDataDescriptor? = delegate.get(dataId)
    override suspend fun all(): List<InferenceDataDescriptor> = delegate.all()
}

sealed interface InferenceStreamPayload {
    data class DispatchPrepared(
        val providerId: AgentProviderId,
        val strategy: CompoundInferenceStrategy,
        val dataIds: List<String>,
        val candidateBudget: Int,
        val aggregatorDepth: Int,
    ) : InferenceStreamPayload

    data class ProviderRunBound(
        val providerId: AgentProviderId,
        val providerRunId: ProviderRunId,
        val taskRunId: TaskRunId,
    ) : InferenceStreamPayload

    data class ArtifactObserved(
        val kind: ArtifactKind,
        val label: String,
        val mediaType: String?,
    ) : InferenceStreamPayload

    data class UsageObserved(
        val inputTokens: Long?,
        val outputTokens: Long?,
        val costUsd: Double?,
        val cacheHitFraction: Float?,
        val latencyMillis: Long?,
    ) : InferenceStreamPayload

    data class Terminal(
        val status: InferenceTerminalStatus,
        val reason: String? = null,
    ) : InferenceStreamPayload
}

data class InferenceStreamRecord(
    val streamId: String,
    val sequence: Long,
    val invocationId: String,
    val payload: InferenceStreamPayload,
)

interface InferenceStreamFabric {
    suspend fun append(invocationId: String, payload: InferenceStreamPayload): InferenceStreamRecord
    suspend fun records(invocationId: String): List<InferenceStreamRecord>
}

class InMemoryInferenceStreamFabric : InferenceStreamFabric {
    private val mutex = Mutex()
    private val recordsByInvocation = linkedMapOf<String, MutableList<InferenceStreamRecord>>()

    override suspend fun append(
        invocationId: String,
        payload: InferenceStreamPayload,
    ): InferenceStreamRecord = mutex.withLock {
        require(invocationId.isNotBlank()) { "invocationId must not be blank" }
        val stream = recordsByInvocation.getOrPut(invocationId) { mutableListOf() }
        val record = InferenceStreamRecord(
            streamId = "inference:$invocationId",
            sequence = stream.size.toLong(),
            invocationId = invocationId,
            payload = payload,
        )
        stream += record
        record
    }

    override suspend fun records(invocationId: String): List<InferenceStreamRecord> =
        mutex.withLock { recordsByInvocation[invocationId].orEmpty().toList() }
}

data class InferenceResourceSample(
    val invocationId: String,
    val providerId: AgentProviderId,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val costUsd: Double? = null,
    val cacheHitFraction: Float? = null,
    val latencyMillis: Long? = null,
)

interface InferenceResourceTelemetry {
    suspend fun record(sample: InferenceResourceSample)
    suspend fun samples(invocationId: String): List<InferenceResourceSample>
}

class InMemoryInferenceResourceTelemetry : InferenceResourceTelemetry {
    private val mutex = Mutex()
    private val samplesByInvocation = linkedMapOf<String, MutableList<InferenceResourceSample>>()

    override suspend fun record(sample: InferenceResourceSample) = mutex.withLock {
        samplesByInvocation.getOrPut(sample.invocationId) { mutableListOf() } += sample
    }

    override suspend fun samples(invocationId: String): List<InferenceResourceSample> =
        mutex.withLock { samplesByInvocation[invocationId].orEmpty().toList() }
}

data class InferencePlanningRequest(
    val request: AgentTaskRequest,
    val providerId: AgentProviderId,
    val agent: InferenceAgentDescriptor,
    val data: List<InferenceDataDescriptor>,
)

data class InferenceExecutionPlan(
    val invocationId: String,
    val strategy: CompoundInferenceStrategy,
    val providerId: AgentProviderId,
    val dataIds: List<String>,
    val candidateBudget: Int,
    val aggregatorDepth: Int,
)

fun interface CompoundInferenceTaskPlanner {
    suspend fun plan(request: InferencePlanningRequest): InferenceExecutionPlan
}

object DefaultCompoundInferenceTaskPlanner : CompoundInferenceTaskPlanner {
    override suspend fun plan(request: InferencePlanningRequest): InferenceExecutionPlan {
        val context = request.request.compoundInference
        return InferenceExecutionPlan(
            invocationId = context.genealogy.invocationId,
            strategy = context.strategy,
            providerId = request.providerId,
            dataIds = request.data.map(InferenceDataDescriptor::dataId),
            candidateBudget = context.candidateBudget,
            aggregatorDepth = context.aggregatorDepth,
        )
    }
}

data class PreparedInferenceDispatch(
    val request: AgentTaskRequest,
    val plan: InferenceExecutionPlan,
)

data class InferenceProviderRunKey(
    val providerId: AgentProviderId,
    val providerRunId: ProviderRunId,
)

enum class InferenceTerminalStatus {
    Completed,
    Failed,
    Cancelled,
}

interface CompoundInferenceFabric {
    val modelRegistry: InferenceModelRegistry
    val agentRegistry: InferenceAgentRegistry
    val dataRegistry: InferenceDataRegistry
    val streamFabric: InferenceStreamFabric
    val resourceTelemetry: InferenceResourceTelemetry

    suspend fun prepareDispatch(
        request: AgentTaskRequest,
        providerId: AgentProviderId,
        capabilities: AgentCapabilities,
    ): PreparedInferenceDispatch

    suspend fun bindProviderRun(
        invocationId: String,
        taskRunId: TaskRunId,
        providerId: AgentProviderId,
        providerRunId: ProviderRunId,
    )

    suspend fun recordArtifact(
        providerId: AgentProviderId,
        providerRunId: ProviderRunId,
        artifact: ProviderArtifact,
    )

    suspend fun recordUsage(
        providerId: AgentProviderId,
        providerRunId: ProviderRunId,
        inputTokens: Long?,
        outputTokens: Long?,
        costUsd: Double?,
        cacheHitFraction: Float?,
        latencyMillis: Long?,
    )

    suspend fun recordTerminal(
        providerId: AgentProviderId,
        providerRunId: ProviderRunId,
        status: InferenceTerminalStatus,
        reason: String? = null,
    )

    suspend fun recordTerminal(
        invocationId: String,
        status: InferenceTerminalStatus,
        reason: String? = null,
    )
}

/**
 * Blueprint-style runtime fabric shared by provider-backed inference inside one application runtime.
 *
 * The mutable registry implementations in this file are intentionally runtime-local. Production
 * wiring uses the Settings-backed fabric for durable registry/stream state. Workflow state,
 * artifacts, memory and repositories remain authoritative in their existing stores; the fabric
 * indexes and coordinates them without duplicating payload ownership.
 */
class BlueprintCompoundInferenceFabric(
    override val modelRegistry: InferenceModelRegistry = InMemoryInferenceModelRegistry(),
    override val agentRegistry: InferenceAgentRegistry = InMemoryInferenceAgentRegistry(),
    override val dataRegistry: InferenceDataRegistry = InMemoryInferenceDataRegistry(),
    override val streamFabric: InferenceStreamFabric = InMemoryInferenceStreamFabric(),
    override val resourceTelemetry: InferenceResourceTelemetry = InMemoryInferenceResourceTelemetry(),
    private val planner: CompoundInferenceTaskPlanner = DefaultCompoundInferenceTaskPlanner,
) : CompoundInferenceFabric {
    private val mutex = Mutex()
    private val invocationByProviderRun = linkedMapOf<InferenceProviderRunKey, String>()
    private val nextInvocationSequenceByTaskRun = linkedMapOf<TaskRunId, Long>()

    override suspend fun prepareDispatch(
        request: AgentTaskRequest,
        providerId: AgentProviderId,
        capabilities: AgentCapabilities,
    ): PreparedInferenceDispatch {
        val concreteInvocationId = mutex.withLock {
            val nextSequence = (nextInvocationSequenceByTaskRun[request.taskRunId] ?: 0L) + 1L
            nextInvocationSequenceByTaskRun[request.taskRunId] = nextSequence
            "${request.compoundInference.genealogy.invocationId}:invocation:$nextSequence"
        }
        val preparedRequest = request.copy(
            compoundInference = request.compoundInference.copy(
                genealogy = request.compoundInference.genealogy.copy(
                    invocationId = concreteInvocationId,
                ),
            ),
        )
        val agent = InferenceAgentDescriptor(
            providerId = providerId,
            capabilities = capabilities.supported,
            promptCacheModes = capabilities.promptCaching.modes,
            requiresEnvironmentPlanning = capabilities.requiresEnvironmentPlanning,
        )
        agentRegistry.register(agent)

        val data = preparedRequest.contextArtifacts.map { artifact ->
            artifact.toInferenceData().also { dataRegistry.register(it) }
        }
        val plan = planner.plan(
            InferencePlanningRequest(
                request = preparedRequest,
                providerId = providerId,
                agent = agent,
                data = data,
            ),
        )
        streamFabric.append(
            invocationId = plan.invocationId,
            payload = InferenceStreamPayload.DispatchPrepared(
                providerId = providerId,
                strategy = plan.strategy,
                dataIds = plan.dataIds,
                candidateBudget = plan.candidateBudget,
                aggregatorDepth = plan.aggregatorDepth,
            ),
        )
        return PreparedInferenceDispatch(request = preparedRequest, plan = plan)
    }

    override suspend fun bindProviderRun(
        invocationId: String,
        taskRunId: TaskRunId,
        providerId: AgentProviderId,
        providerRunId: ProviderRunId,
    ) {
        val key = InferenceProviderRunKey(providerId, providerRunId)
        mutex.withLock {
            val existing = invocationByProviderRun[key]
            require(existing == null || existing == invocationId) {
                "Provider run ${providerId.value}/${providerRunId.value} is already bound to another inference invocation"
            }
            invocationByProviderRun[key] = invocationId
        }
        streamFabric.append(
            invocationId = invocationId,
            payload = InferenceStreamPayload.ProviderRunBound(providerId, providerRunId, taskRunId),
        )
    }

    override suspend fun recordArtifact(
        providerId: AgentProviderId,
        providerRunId: ProviderRunId,
        artifact: ProviderArtifact,
    ) {
        val invocationId = invocationId(providerId, providerRunId) ?: return
        streamFabric.append(
            invocationId = invocationId,
            payload = InferenceStreamPayload.ArtifactObserved(
                kind = artifact.kind,
                label = artifact.label,
                mediaType = artifact.mediaType,
            ),
        )
    }

    override suspend fun recordUsage(
        providerId: AgentProviderId,
        providerRunId: ProviderRunId,
        inputTokens: Long?,
        outputTokens: Long?,
        costUsd: Double?,
        cacheHitFraction: Float?,
        latencyMillis: Long?,
    ) {
        val invocationId = invocationId(providerId, providerRunId) ?: return
        val sample = InferenceResourceSample(
            invocationId = invocationId,
            providerId = providerId,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            costUsd = costUsd,
            cacheHitFraction = cacheHitFraction,
            latencyMillis = latencyMillis,
        )
        resourceTelemetry.record(sample)
        streamFabric.append(
            invocationId = invocationId,
            payload = InferenceStreamPayload.UsageObserved(
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                costUsd = costUsd,
                cacheHitFraction = cacheHitFraction,
                latencyMillis = latencyMillis,
            ),
        )
    }

    override suspend fun recordTerminal(
        providerId: AgentProviderId,
        providerRunId: ProviderRunId,
        status: InferenceTerminalStatus,
        reason: String?,
    ) {
        val invocationId = invocationId(providerId, providerRunId) ?: return
        recordTerminal(invocationId, status, reason)
    }

    override suspend fun recordTerminal(
        invocationId: String,
        status: InferenceTerminalStatus,
        reason: String?,
    ) {
        streamFabric.append(invocationId, InferenceStreamPayload.Terminal(status, reason))
    }

    private suspend fun invocationId(
        providerId: AgentProviderId,
        providerRunId: ProviderRunId,
    ): String? = mutex.withLock {
        invocationByProviderRun[InferenceProviderRunKey(providerId, providerRunId)]
    }

    private fun ArtifactRef.toInferenceData(): InferenceDataDescriptor = InferenceDataDescriptor(
        dataId = "artifact:${id.value}",
        kind = InferenceDataKind.Artifact,
        artifactId = id,
        producingTaskRunId = taskRunId,
        label = label,
        mediaType = mediaType,
    )
}
