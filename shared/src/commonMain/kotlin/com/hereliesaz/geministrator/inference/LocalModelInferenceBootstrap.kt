package com.hereliesaz.geministrator.inference

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.providers.AgentCapabilities
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.ProviderArtifact
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Decorates an inference fabric with a reusable local-model catalog.
 *
 * Bootstrap is lazy because model registry operations are suspend functions. The first registry
 * read or provider dispatch persists the catalog exactly once per runtime; durable registries then
 * retain the same stable model identities across restarts.
 */
class LocalModelBootstrapInferenceFabric(
    private val delegate: CompoundInferenceFabric,
    private val library: LocalModelLibrary,
) : CompoundInferenceFabric by delegate {
    private val bootstrapMutex = Mutex()
    private var bootstrapped = false

    override val modelRegistry: InferenceModelRegistry = object : InferenceModelRegistry {
        override suspend fun register(model: InferenceModelDescriptor) {
            delegate.modelRegistry.register(model)
        }

        override suspend fun get(logicalModelId: String): InferenceModelDescriptor? {
            ensureBootstrapped()
            return delegate.modelRegistry.get(logicalModelId)
        }

        override suspend fun all(): List<InferenceModelDescriptor> {
            ensureBootstrapped()
            return delegate.modelRegistry.all()
        }
    }

    override suspend fun prepareDispatch(
        request: AgentTaskRequest,
        providerId: AgentProviderId,
        capabilities: AgentCapabilities,
    ): PreparedInferenceDispatch {
        ensureBootstrapped()
        return delegate.prepareDispatch(request, providerId, capabilities)
    }

    override suspend fun bindProviderRun(
        invocationId: String,
        taskRunId: TaskRunId,
        providerId: AgentProviderId,
        providerRunId: ProviderRunId,
    ) = delegate.bindProviderRun(invocationId, taskRunId, providerId, providerRunId)

    override suspend fun recordArtifact(
        providerId: AgentProviderId,
        providerRunId: ProviderRunId,
        artifact: ProviderArtifact,
    ) = delegate.recordArtifact(providerId, providerRunId, artifact)

    override suspend fun recordUsage(
        providerId: AgentProviderId,
        providerRunId: ProviderRunId,
        inputTokens: Long?,
        outputTokens: Long?,
        costUsd: Double?,
        cacheHitFraction: Float?,
        latencyMillis: Long?,
    ) = delegate.recordUsage(
        providerId,
        providerRunId,
        inputTokens,
        outputTokens,
        costUsd,
        cacheHitFraction,
        latencyMillis,
    )

    override suspend fun recordTerminal(
        providerId: AgentProviderId,
        providerRunId: ProviderRunId,
        status: InferenceTerminalStatus,
        reason: String?,
    ) = delegate.recordTerminal(providerId, providerRunId, status, reason)

    override suspend fun recordTerminal(
        invocationId: String,
        status: InferenceTerminalStatus,
        reason: String?,
    ) = delegate.recordTerminal(invocationId, status, reason)

    private suspend fun ensureBootstrapped() {
        if (bootstrapped) return
        bootstrapMutex.withLock {
            if (bootstrapped) return@withLock
            library.registerInto(delegate.modelRegistry)
            bootstrapped = true
        }
    }
}

fun CompoundInferenceFabric.withLocalModelLibrary(library: LocalModelLibrary): CompoundInferenceFabric =
    LocalModelBootstrapInferenceFabric(this, library)
