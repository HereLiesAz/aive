package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.providers.AgentEvent
import com.hereliesaz.geministrator.providers.AgentProvider
import com.hereliesaz.geministrator.providers.ProviderActionResult
import com.hereliesaz.geministrator.providers.ProviderArtifact
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ProviderBackedManagedSessionGateway(
    private val providerRegistry: AgentProviderRegistry,
    private val scope: CoroutineScope,
) : ManagedSessionGateway {

    private data class SessionSnapshot(
        val status: ManagedSessionStatus,
        val artifacts: List<ProviderArtifact> = emptyList(),
        val progress: ManagedSessionProgress? = null,
        val pendingUsage: ManagedSessionUsage? = null,
    )

    private val mutex = Mutex()
    private val snapshots = mutableMapOf<ManagedSessionHandle, SessionSnapshot>()

    override suspend fun resolveProvider(selection: ProviderSelectionRequest): AgentProviderId =
        selectProvider(selection, "No registered provider can satisfy this task").id

    override suspend fun createSession(request: ManagedSessionRequest): ManagedSessionHandle {
        val provider = selectProvider(request.providerSelection, "No registered provider can satisfy this task")
        return providerOperation("Unable to start provider session") {
            val run = provider.start(request.taskRequest)
            val handle = ManagedSessionHandle(
                taskRunId = request.taskRequest.taskRunId,
                providerId = provider.id,
                providerRunId = run.providerRunId,
            )
            registerAndObserve(
                handle = handle,
                initialStatus = if (request.taskRequest.requirePlanApproval) {
                    ManagedSessionStatus.Planning
                } else {
                    ManagedSessionStatus.Running
                },
            )
            handle
        }
    }

    override suspend fun reconnect(
        handle: ManagedSessionHandle,
        initialStatus: ManagedSessionStatus,
    ) {
        providerFor(handle)
        providerOperation("Unable to reconnect provider session ${handle.providerRunId.value}") {
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
        providerFor(handle).sendMessage(handle.providerRunId, message)
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
                mutex.withLock {
                    val current = snapshots[handle] ?: SessionSnapshot(ManagedSessionStatus.Unknown)
                    snapshots[handle] = current.copy(status = ManagedSessionStatus.Failed)
                }
            }
            result
        }
    }

    override suspend fun artifacts(handle: ManagedSessionHandle): List<ProviderArtifact> =
        mutex.withLock { snapshots[handle]?.artifacts.orEmpty() }

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

        val provider = providerFor(handle)
        scope.launch {
            var consecutiveFailures = 0
            while (isActive && !handle.isTerminal()) {
                try {
                    provider.observe(handle.providerRunId).collect { event -> applyEvent(handle, event) }
                    consecutiveFailures = 0
                    if (!handle.isTerminal()) delay(OBSERVER_RETRY_MILLIS)
                } catch (failure: CancellationException) {
                    throw failure
                } catch (_: Exception) {
                    consecutiveFailures++
                    if (consecutiveFailures >= MAX_OBSERVER_FAILURES) {
                        mutex.withLock {
                            val current = snapshots[handle] ?: return@withLock
                            if (current.status != ManagedSessionStatus.Completed) {
                                snapshots[handle] = current.copy(status = ManagedSessionStatus.Failed)
                            }
                        }
                        return@launch
                    }
                    val backoffBase = OBSERVER_RETRY_MILLIS * (1L shl minOf(consecutiveFailures - 1, 5))
                    val jitter = (backoffBase * 0.25 * kotlin.random.Random.nextDouble()).toLong()
                    delay(backoffBase + jitter)
                }
            }
        }
    }

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
        mutex.withLock {
            val current = snapshots[handle] ?: SessionSnapshot(ManagedSessionStatus.Unknown)
            if (current.status.isTerminal()) return@withLock
            val next = when (event) {
                is AgentEvent.PlanGenerated -> current.copy(
                    status = ManagedSessionStatus.AwaitingApproval,
                    progress = ManagedSessionProgress(
                        fraction = null,
                        message = event.summary.takeIf(String::isNotBlank),
                    ),
                )
                is AgentEvent.PlanApproved -> current.copy(status = ManagedSessionStatus.Running)
                is AgentEvent.Progress -> current.copy(
                    status = ManagedSessionStatus.Running,
                    progress = ManagedSessionProgress(
                        fraction = event.fraction,
                        message = event.message.takeIf { it.isNotBlank() },
                    ),
                )
                is AgentEvent.Message -> current
                is AgentEvent.ArtifactProduced -> current.copy(
                    artifacts = current.artifacts.upsertArtifact(event.artifact),
                )
                is AgentEvent.Completed -> current.copy(
                    status = ManagedSessionStatus.Completed,
                    progress = ManagedSessionProgress(
                        fraction = 1f,
                        message = current.progress?.message,
                    ),
                )
                is AgentEvent.Failed -> current.copy(status = ManagedSessionStatus.Failed)
                is AgentEvent.UsageReported -> current.copy(
                    pendingUsage = ManagedSessionUsage(
                        inputTokens = event.inputTokens,
                        outputTokens = event.outputTokens,
                        costUsd = event.costUsd,
                        cacheHitFraction = event.cacheHitFraction,
                        latencyMillis = event.latencyMillis,
                    ),
                )
            }
            snapshots[handle] = next
        }
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

    private companion object {
        const val OBSERVER_RETRY_MILLIS = 1_000L
        const val MAX_OBSERVER_FAILURES = 10
    }
}
