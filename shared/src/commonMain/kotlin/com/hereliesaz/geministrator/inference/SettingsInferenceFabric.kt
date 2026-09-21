package com.hereliesaz.geministrator.inference

import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.persistence.PersistenceCorruptionException
import com.hereliesaz.geministrator.providers.AgentCapabilities
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.PromptCacheMode
import com.hereliesaz.geministrator.providers.ProviderArtifact
import com.russhwolf.settings.Settings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val settingsInferenceStateMutex = Mutex()

/**
 * Versioned durable state used by the Blueprint inference fabric.
 *
 * This store persists orchestration/indexing metadata only. Artifact payloads, memory contents,
 * repository data, model weights, and epistemic conclusions remain owned by their authoritative
 * subsystems.
 */
class SettingsInferenceStateStore(
    private val settings: Settings,
    private val storageKey: String = DEFAULT_STORAGE_KEY,
    private val json: Json = defaultJson,
) {
    suspend fun registerModel(model: InferenceModelDescriptor) = update { snapshot ->
        snapshot.copy(
            models = snapshot.models.upsert(PersistedInferenceModel.fromDomain(model)) {
                it.logicalModelId == model.logicalModelId
            },
        )
    }

    suspend fun model(logicalModelId: String): InferenceModelDescriptor? =
        read().models.firstOrNull { it.logicalModelId == logicalModelId }?.toDomain()

    suspend fun models(): List<InferenceModelDescriptor> = read().models.map(PersistedInferenceModel::toDomain)

    suspend fun removeModel(logicalModelId: String) = update { snapshot ->
        snapshot.copy(models = snapshot.models.filterNot { it.logicalModelId == logicalModelId })
    }

    suspend fun registerAgent(agent: InferenceAgentDescriptor) = update { snapshot ->
        snapshot.copy(
            agents = snapshot.agents.upsert(PersistedInferenceAgent.fromDomain(agent)) {
                it.providerId == agent.providerId.value
            },
        )
    }

    suspend fun agent(providerId: AgentProviderId): InferenceAgentDescriptor? =
        read().agents.firstOrNull { it.providerId == providerId.value }?.toDomain()

    suspend fun agents(): List<InferenceAgentDescriptor> = read().agents.map(PersistedInferenceAgent::toDomain)

    suspend fun registerData(data: InferenceDataDescriptor) = update { snapshot ->
        snapshot.copy(
            data = snapshot.data.upsert(PersistedInferenceData.fromDomain(data)) { it.dataId == data.dataId },
        )
    }

    suspend fun data(dataId: String): InferenceDataDescriptor? =
        read().data.firstOrNull { it.dataId == dataId }?.toDomain()

    suspend fun data(): List<InferenceDataDescriptor> = read().data.map(PersistedInferenceData::toDomain)

    suspend fun appendRecord(
        invocationId: String,
        payload: InferenceStreamPayload,
    ): InferenceStreamRecord = settingsInferenceStateMutex.withLock {
        require(invocationId.isNotBlank()) { "invocationId must not be blank" }
        val snapshot = readUnlocked()
        val sequence = snapshot.records
            .asSequence()
            .filter { it.invocationId == invocationId }
            .maxOfOrNull(PersistedInferenceStreamRecord::sequence)
            ?.plus(1L)
            ?: 0L
        val record = InferenceStreamRecord(
            streamId = "inference:$invocationId",
            sequence = sequence,
            invocationId = invocationId,
            payload = payload,
        )
        writeUnlocked(snapshot.copy(records = snapshot.records + PersistedInferenceStreamRecord.fromDomain(record)))
        record
    }

    suspend fun records(invocationId: String): List<InferenceStreamRecord> =
        read().records
            .asSequence()
            .filter { it.invocationId == invocationId }
            .sortedBy(PersistedInferenceStreamRecord::sequence)
            .map(PersistedInferenceStreamRecord::toDomain)
            .toList()

    suspend fun recordResourceSample(sample: InferenceResourceSample) = update { snapshot ->
        snapshot.copy(resourceSamples = snapshot.resourceSamples + PersistedInferenceResourceSample.fromDomain(sample))
    }

    suspend fun resourceSamples(invocationId: String): List<InferenceResourceSample> =
        read().resourceSamples
            .asSequence()
            .filter { it.invocationId == invocationId }
            .map(PersistedInferenceResourceSample::toDomain)
            .toList()

    suspend fun nextInvocationSequence(taskRunId: TaskRunId): Long = settingsInferenceStateMutex.withLock {
        val snapshot = readUnlocked()
        val previous = snapshot.invocationSequences.firstOrNull { it.taskRunId == taskRunId.value }?.sequence ?: 0L
        val next = previous + 1L
        val updated = PersistedInvocationSequence(taskRunId.value, next)
        writeUnlocked(
            snapshot.copy(
                invocationSequences = snapshot.invocationSequences.upsert(updated) { it.taskRunId == taskRunId.value },
            ),
        )
        next
    }

    suspend fun bindProviderRun(
        invocationId: String,
        taskRunId: TaskRunId,
        providerId: AgentProviderId,
        providerRunId: ProviderRunId,
    ) = settingsInferenceStateMutex.withLock {
        val snapshot = readUnlocked()
        val existing = snapshot.providerRunBindings.firstOrNull {
            it.providerId == providerId.value && it.providerRunId == providerRunId.value
        }
        require(existing == null || existing.invocationId == invocationId) {
            "Provider run ${providerId.value}/${providerRunId.value} is already bound to another inference invocation"
        }
        if (existing == null) {
            writeUnlocked(
                snapshot.copy(
                    providerRunBindings = snapshot.providerRunBindings + PersistedProviderRunBinding(
                        providerId = providerId.value,
                        providerRunId = providerRunId.value,
                        taskRunId = taskRunId.value,
                        invocationId = invocationId,
                    ),
                ),
            )
        }
    }

    suspend fun invocationId(
        providerId: AgentProviderId,
        providerRunId: ProviderRunId,
    ): String? = read().providerRunBindings.firstOrNull {
        it.providerId == providerId.value && it.providerRunId == providerRunId.value
    }?.invocationId

    suspend fun recoverFromCorruption(): Boolean = settingsInferenceStateMutex.withLock {
        val hadData = settings.getStringOrNull(storageKey) != null
        settings.remove(storageKey)
        hadData
    }

    private suspend fun read(): InferenceStateSnapshot = settingsInferenceStateMutex.withLock { readUnlocked() }

    private suspend fun update(transform: (InferenceStateSnapshot) -> InferenceStateSnapshot) {
        settingsInferenceStateMutex.withLock {
            writeUnlocked(transform(readUnlocked()))
        }
    }

    private fun readUnlocked(): InferenceStateSnapshot {
        val encoded = settings.getStringOrNull(storageKey) ?: return InferenceStateSnapshot()
        val snapshot = try {
            json.decodeFromString(InferenceStateSnapshot.serializer(), encoded)
        } catch (failure: Exception) {
            throw PersistenceCorruptionException(
                "Inference fabric persistence is unreadable and must be recovered.",
                failure,
            )
        }
        require(snapshot.version <= CURRENT_SCHEMA_VERSION) {
            "Unsupported inference fabric schema ${snapshot.version}; maximum supported is $CURRENT_SCHEMA_VERSION"
        }
        return snapshot
    }

    private fun writeUnlocked(snapshot: InferenceStateSnapshot) {
        settings.putString(storageKey, json.encodeToString(InferenceStateSnapshot.serializer(), snapshot))
    }

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
        const val DEFAULT_STORAGE_KEY: String = "haive.inference.fabric.v1"

        val defaultJson: Json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
            classDiscriminator = "type"
        }

        fun createDefault(): SettingsInferenceStateStore = SettingsInferenceStateStore(Settings())

        fun createDurable(): SettingsInferenceStateStore = SettingsInferenceStateStore(
            com.hereliesaz.geministrator.persistence.ChunkedStringSettings(
                delegate = Settings(),
                chunkedKeys = setOf(DEFAULT_STORAGE_KEY),
            ),
        )
    }
}

/** Durable production implementation of the existing Blueprint compound-inference behavior. */
class SettingsCompoundInferenceFabric(
    private val state: SettingsInferenceStateStore = SettingsInferenceStateStore.createDefault(),
    private val planner: CompoundInferenceTaskPlanner = DefaultCompoundInferenceTaskPlanner,
) : CompoundInferenceFabric {
    override val modelRegistry: InferenceModelRegistry = object : InferenceModelRegistry {
        override suspend fun register(model: InferenceModelDescriptor) = state.registerModel(model)
        override suspend fun get(logicalModelId: String): InferenceModelDescriptor? = state.model(logicalModelId)
        override suspend fun all(): List<InferenceModelDescriptor> = state.models()
    }

    override val agentRegistry: InferenceAgentRegistry = object : InferenceAgentRegistry {
        override suspend fun register(agent: InferenceAgentDescriptor) = state.registerAgent(agent)
        override suspend fun get(providerId: AgentProviderId): InferenceAgentDescriptor? = state.agent(providerId)
        override suspend fun all(): List<InferenceAgentDescriptor> = state.agents()
    }

    override val dataRegistry: InferenceDataRegistry = object : InferenceDataRegistry {
        override suspend fun register(data: InferenceDataDescriptor) = state.registerData(data)
        override suspend fun get(dataId: String): InferenceDataDescriptor? = state.data(dataId)
        override suspend fun all(): List<InferenceDataDescriptor> = state.data()
    }

    override val streamFabric: InferenceStreamFabric = object : InferenceStreamFabric {
        override suspend fun append(
            invocationId: String,
            payload: InferenceStreamPayload,
        ): InferenceStreamRecord = state.appendRecord(invocationId, payload)

        override suspend fun records(invocationId: String): List<InferenceStreamRecord> = state.records(invocationId)
    }

    override val resourceTelemetry: InferenceResourceTelemetry = object : InferenceResourceTelemetry {
        override suspend fun record(sample: InferenceResourceSample) = state.recordResourceSample(sample)
        override suspend fun samples(invocationId: String): List<InferenceResourceSample> = state.resourceSamples(invocationId)
    }

    override suspend fun prepareDispatch(
        request: AgentTaskRequest,
        providerId: AgentProviderId,
        capabilities: AgentCapabilities,
    ): PreparedInferenceDispatch {
        val nextSequence = state.nextInvocationSequence(request.taskRunId)
        val concreteInvocationId = "${request.compoundInference.genealogy.invocationId}:invocation:$nextSequence"
        val preparedRequest = request.copy(
            compoundInference = request.compoundInference.copy(
                genealogy = request.compoundInference.genealogy.copy(invocationId = concreteInvocationId),
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
        state.bindProviderRun(invocationId, taskRunId, providerId, providerRunId)
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
        val invocationId = state.invocationId(providerId, providerRunId) ?: return
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
        val invocationId = state.invocationId(providerId, providerRunId) ?: return
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
        val invocationId = state.invocationId(providerId, providerRunId) ?: return
        recordTerminal(invocationId, status, reason)
    }

    override suspend fun recordTerminal(
        invocationId: String,
        status: InferenceTerminalStatus,
        reason: String?,
    ) {
        streamFabric.append(invocationId, InferenceStreamPayload.Terminal(status, reason))
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

@Serializable
private data class InferenceStateSnapshot(
    val version: Int = SettingsInferenceStateStore.CURRENT_SCHEMA_VERSION,
    val models: List<PersistedInferenceModel> = emptyList(),
    val agents: List<PersistedInferenceAgent> = emptyList(),
    val data: List<PersistedInferenceData> = emptyList(),
    val records: List<PersistedInferenceStreamRecord> = emptyList(),
    val resourceSamples: List<PersistedInferenceResourceSample> = emptyList(),
    val providerRunBindings: List<PersistedProviderRunBinding> = emptyList(),
    val invocationSequences: List<PersistedInvocationSequence> = emptyList(),
)

@Serializable
private data class PersistedInferenceModel(
    val logicalModelId: String,
    val baseModelId: String? = null,
    val adapterId: String? = null,
    val precision: String? = null,
    val backend: String? = null,
    val capabilities: List<String> = emptyList(),
    val releaseDigest: String? = null,
) {
    fun toDomain() = InferenceModelDescriptor(
        logicalModelId = logicalModelId,
        baseModelId = baseModelId,
        adapterId = adapterId,
        precision = precision,
        backend = backend,
        capabilities = capabilities.toSet(),
        releaseDigest = releaseDigest,
    )

    companion object {
        fun fromDomain(value: InferenceModelDescriptor) = PersistedInferenceModel(
            logicalModelId = value.logicalModelId,
            baseModelId = value.baseModelId,
            adapterId = value.adapterId,
            precision = value.precision,
            backend = value.backend,
            capabilities = value.capabilities.sorted(),
            releaseDigest = value.releaseDigest,
        )
    }
}

@Serializable
private data class PersistedInferenceAgent(
    val providerId: String,
    val capabilities: List<String>,
    val promptCacheModes: List<String>,
    val requiresEnvironmentPlanning: Boolean,
) {
    fun toDomain() = InferenceAgentDescriptor(
        providerId = AgentProviderId(providerId),
        capabilities = capabilities.mapTo(linkedSetOf()) { AgentCapability.valueOf(it) },
        promptCacheModes = promptCacheModes.mapTo(linkedSetOf()) { PromptCacheMode.valueOf(it) },
        requiresEnvironmentPlanning = requiresEnvironmentPlanning,
    )

    companion object {
        fun fromDomain(value: InferenceAgentDescriptor) = PersistedInferenceAgent(
            providerId = value.providerId.value,
            capabilities = value.capabilities.map { it.name }.sorted(),
            promptCacheModes = value.promptCacheModes.map { it.name }.sorted(),
            requiresEnvironmentPlanning = value.requiresEnvironmentPlanning,
        )
    }
}

@Serializable
private data class PersistedInferenceData(
    val dataId: String,
    val kind: String,
    val artifactId: String? = null,
    val producingTaskRunId: String? = null,
    val label: String? = null,
    val mediaType: String? = null,
) {
    fun toDomain() = InferenceDataDescriptor(
        dataId = dataId,
        kind = InferenceDataKind.valueOf(kind),
        artifactId = artifactId?.let { ArtifactId(it) },
        producingTaskRunId = producingTaskRunId?.let { TaskRunId(it) },
        label = label,
        mediaType = mediaType,
    )

    companion object {
        fun fromDomain(value: InferenceDataDescriptor) = PersistedInferenceData(
            dataId = value.dataId,
            kind = value.kind.name,
            artifactId = value.artifactId?.value,
            producingTaskRunId = value.producingTaskRunId?.value,
            label = value.label,
            mediaType = value.mediaType,
        )
    }
}

@Serializable
private data class PersistedInferenceStreamRecord(
    val streamId: String,
    val sequence: Long,
    val invocationId: String,
    val payload: PersistedInferenceStreamPayload,
) {
    fun toDomain() = InferenceStreamRecord(streamId, sequence, invocationId, payload.toDomain())

    companion object {
        fun fromDomain(value: InferenceStreamRecord) = PersistedInferenceStreamRecord(
            streamId = value.streamId,
            sequence = value.sequence,
            invocationId = value.invocationId,
            payload = PersistedInferenceStreamPayload.fromDomain(value.payload),
        )
    }
}

@Serializable
private sealed interface PersistedInferenceStreamPayload {
    fun toDomain(): InferenceStreamPayload

    @Serializable
    @SerialName("dispatch_prepared")
    data class DispatchPrepared(
        val providerId: String,
        val strategy: String,
        val dataIds: List<String>,
        val candidateBudget: Int,
        val aggregatorDepth: Int,
    ) : PersistedInferenceStreamPayload {
        override fun toDomain() = InferenceStreamPayload.DispatchPrepared(
            providerId = AgentProviderId(providerId),
            strategy = CompoundInferenceStrategy.valueOf(strategy),
            dataIds = dataIds,
            candidateBudget = candidateBudget,
            aggregatorDepth = aggregatorDepth,
        )
    }

    @Serializable
    @SerialName("provider_run_bound")
    data class ProviderRunBound(
        val providerId: String,
        val providerRunId: String,
        val taskRunId: String,
    ) : PersistedInferenceStreamPayload {
        override fun toDomain() = InferenceStreamPayload.ProviderRunBound(
            providerId = AgentProviderId(providerId),
            providerRunId = ProviderRunId(providerRunId),
            taskRunId = TaskRunId(taskRunId),
        )
    }

    @Serializable
    @SerialName("artifact_observed")
    data class ArtifactObserved(
        val kind: String,
        val label: String,
        val mediaType: String?,
    ) : PersistedInferenceStreamPayload {
        override fun toDomain() = InferenceStreamPayload.ArtifactObserved(
            kind = ArtifactKind.valueOf(kind),
            label = label,
            mediaType = mediaType,
        )
    }

    @Serializable
    @SerialName("usage_observed")
    data class UsageObserved(
        val inputTokens: Long?,
        val outputTokens: Long?,
        val costUsd: Double?,
        val cacheHitFraction: Float?,
        val latencyMillis: Long?,
    ) : PersistedInferenceStreamPayload {
        override fun toDomain() = InferenceStreamPayload.UsageObserved(
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            costUsd = costUsd,
            cacheHitFraction = cacheHitFraction,
            latencyMillis = latencyMillis,
        )
    }

    @Serializable
    @SerialName("terminal")
    data class Terminal(
        val status: String,
        val reason: String?,
    ) : PersistedInferenceStreamPayload {
        override fun toDomain() = InferenceStreamPayload.Terminal(
            status = InferenceTerminalStatus.valueOf(status),
            reason = reason,
        )
    }

    companion object {
        fun fromDomain(value: InferenceStreamPayload): PersistedInferenceStreamPayload = when (value) {
            is InferenceStreamPayload.DispatchPrepared -> DispatchPrepared(
                providerId = value.providerId.value,
                strategy = value.strategy.name,
                dataIds = value.dataIds,
                candidateBudget = value.candidateBudget,
                aggregatorDepth = value.aggregatorDepth,
            )
            is InferenceStreamPayload.ProviderRunBound -> ProviderRunBound(
                providerId = value.providerId.value,
                providerRunId = value.providerRunId.value,
                taskRunId = value.taskRunId.value,
            )
            is InferenceStreamPayload.ArtifactObserved -> ArtifactObserved(
                kind = value.kind.name,
                label = value.label,
                mediaType = value.mediaType,
            )
            is InferenceStreamPayload.UsageObserved -> UsageObserved(
                inputTokens = value.inputTokens,
                outputTokens = value.outputTokens,
                costUsd = value.costUsd,
                cacheHitFraction = value.cacheHitFraction,
                latencyMillis = value.latencyMillis,
            )
            is InferenceStreamPayload.Terminal -> Terminal(
                status = value.status.name,
                reason = value.reason,
            )
        }
    }
}

@Serializable
private data class PersistedInferenceResourceSample(
    val invocationId: String,
    val providerId: String,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val costUsd: Double? = null,
    val cacheHitFraction: Float? = null,
    val latencyMillis: Long? = null,
) {
    fun toDomain() = InferenceResourceSample(
        invocationId = invocationId,
        providerId = AgentProviderId(providerId),
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        costUsd = costUsd,
        cacheHitFraction = cacheHitFraction,
        latencyMillis = latencyMillis,
    )

    companion object {
        fun fromDomain(value: InferenceResourceSample) = PersistedInferenceResourceSample(
            invocationId = value.invocationId,
            providerId = value.providerId.value,
            inputTokens = value.inputTokens,
            outputTokens = value.outputTokens,
            costUsd = value.costUsd,
            cacheHitFraction = value.cacheHitFraction,
            latencyMillis = value.latencyMillis,
        )
    }
}

@Serializable
private data class PersistedProviderRunBinding(
    val providerId: String,
    val providerRunId: String,
    val taskRunId: String,
    val invocationId: String,
)

@Serializable
private data class PersistedInvocationSequence(
    val taskRunId: String,
    val sequence: Long,
)

private inline fun <T> List<T>.upsert(value: T, matches: (T) -> Boolean): List<T> {
    val index = indexOfFirst(matches)
    if (index < 0) return this + value
    return toMutableList().also { it[index] = value }
}
