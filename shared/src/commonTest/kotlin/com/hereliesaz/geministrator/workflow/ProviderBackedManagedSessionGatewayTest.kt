package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.providers.AgentCapabilities
import com.hereliesaz.geministrator.providers.AgentEvent
import com.hereliesaz.geministrator.providers.AgentProvider
import com.hereliesaz.geministrator.providers.AgentRunHandle
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.ProviderActionResult
import com.hereliesaz.geministrator.providers.ProviderArtifact
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProviderBackedManagedSessionGatewayTest {
    @Test
    fun providerSelectionPreservesCancellation() {
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val gateway = ProviderBackedManagedSessionGateway(
                    AgentProviderRegistry(listOf(CancellingCapabilitiesProvider())),
                    scope,
                )

                assertFailsWith<CancellationException> {
                    gateway.resolveProvider(ProviderSelectionRequest())
                }
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun generatedPlanIsRetainedAsIndeterminateProgressForHumanInspection() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val provider = PlanPreviewProvider()
        val handle = ManagedSessionHandle(
            taskRunId = TaskRunId("plan-task"),
            providerId = provider.id,
            providerRunId = ProviderRunId("plan-run"),
        )
        try {
            val gateway = ProviderBackedManagedSessionGateway(
                AgentProviderRegistry(listOf(provider)),
                scope,
            )
            gateway.reconnect(handle, ManagedSessionStatus.Planning)

            withTimeout(2_000L) {
                while (gateway.status(handle) != ManagedSessionStatus.AwaitingApproval) delay(25L)
            }

            assertEquals(null, gateway.progress(handle)?.fraction)
            assertEquals("1. Inspect repository\n2. Implement approved change", gateway.progress(handle)?.message)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun requestAwareReconnectReconstructsProviderLocalSessionWithoutRedispatch() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val provider = RecordingReconnectProvider()
        val taskRunId = TaskRunId("resume-task")
        val handle = ManagedSessionHandle(
            taskRunId = taskRunId,
            providerId = provider.id,
            providerRunId = ProviderRunId("resume-run"),
        )
        val request = AgentTaskRequest(
            taskRunId = taskRunId,
            objective = "Resume the persisted provider task",
            roleInstructions = "Return a concise result.",
            acceptanceCriteria = emptyList(),
            requirePlanApproval = true,
        )
        try {
            val gateway = ProviderBackedManagedSessionGateway(
                AgentProviderRegistry(listOf(provider)),
                scope,
            )

            gateway.reconnect(handle, ManagedSessionStatus.AwaitingApproval, request)

            assertEquals(1, provider.reconnectCalls)
            assertEquals(0, provider.startCalls)
            assertEquals(request, provider.reconnectedRequest)
            assertEquals(false, provider.planApproved)
            assertEquals(ManagedSessionStatus.AwaitingApproval, gateway.status(handle))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun observerTransportFailurePreservesRemoteRunAndRetriesObservation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val provider = RetryingObserverProvider()
        val handle = ManagedSessionHandle(
            taskRunId = TaskRunId("task-run"),
            providerId = provider.id,
            providerRunId = ProviderRunId("provider-run"),
        )
        try {
            val gateway = ProviderBackedManagedSessionGateway(
                AgentProviderRegistry(listOf(provider)),
                scope,
            )

            gateway.reconnect(handle, ManagedSessionStatus.Running)
            withTimeout(2_000L) { provider.firstObservationFailed.await() }

            assertEquals(ManagedSessionStatus.Running, gateway.status(handle))

            withTimeout(4_000L) {
                while (gateway.status(handle) != ManagedSessionStatus.Completed) {
                    delay(25L)
                }
            }
            assertTrue(provider.observeCount >= 2)
            assertEquals(ManagedSessionStatus.Completed, gateway.status(handle))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun observerReplayDoesNotDuplicateArtifactsAcrossReconnectAttempts() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val provider = ReplayingArtifactProvider()
        val handle = ManagedSessionHandle(
            taskRunId = TaskRunId("artifact-task"),
            providerId = provider.id,
            providerRunId = ProviderRunId("artifact-run"),
        )
        try {
            val gateway = ProviderBackedManagedSessionGateway(
                AgentProviderRegistry(listOf(provider)),
                scope,
            )
            gateway.reconnect(handle, ManagedSessionStatus.Running)

            withTimeout(4_000L) {
                while (gateway.status(handle) != ManagedSessionStatus.Completed) delay(25L)
            }

            assertTrue(provider.observeCount >= 2)
            assertEquals(1, gateway.artifacts(handle).size)
            assertEquals("result", gateway.artifacts(handle).single().label)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cancelMarksSessionTerminalAndDoesNotRepeatRemoteCancellation() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val provider = RecordingCancellationProvider()
        val handle = ManagedSessionHandle(
            taskRunId = TaskRunId("cancel-task"),
            providerId = provider.id,
            providerRunId = ProviderRunId("cancel-run"),
        )
        try {
            val gateway = ProviderBackedManagedSessionGateway(
                AgentProviderRegistry(listOf(provider)),
                scope,
            )
            gateway.reconnect(handle, ManagedSessionStatus.AwaitingApproval)

            assertEquals(ProviderActionResult.Accepted, gateway.cancel(handle))
            assertEquals(ManagedSessionStatus.Failed, gateway.status(handle))
            assertEquals(ProviderActionResult.Accepted, gateway.cancel(handle))
            assertEquals(1, provider.cancelCalls)
        } finally {
            scope.cancel()
        }
    }
}

private class CancellingCapabilitiesProvider : AgentProvider {
    override val id = AgentProviderId("cancel-provider")
    override suspend fun capabilities(): AgentCapabilities = throw CancellationException("cancel selection")
    override suspend fun start(request: AgentTaskRequest): AgentRunHandle = error("not called")
    override fun observe(runId: ProviderRunId): Flow<AgentEvent> = flow { error("not called") }
    override suspend fun sendMessage(runId: ProviderRunId, message: String) = ProviderActionResult.Accepted
    override suspend fun approvePlan(runId: ProviderRunId) = ProviderActionResult.Accepted
    override suspend fun cancel(runId: ProviderRunId) = ProviderActionResult.Accepted
}

private class PlanPreviewProvider : AgentProvider {
    override val id = AgentProviderId("plan-preview")
    override suspend fun capabilities() = AgentCapabilities(supported = setOf(AgentCapability.RepositoryRead))
    override suspend fun start(request: AgentTaskRequest) = AgentRunHandle(ProviderRunId("plan-run"))
    override fun observe(runId: ProviderRunId): Flow<AgentEvent> = flow {
        emit(
            AgentEvent.PlanGenerated(
                runId = runId,
                summary = "1. Inspect repository\n2. Implement approved change",
            ),
        )
    }
    override suspend fun sendMessage(runId: ProviderRunId, message: String) = ProviderActionResult.Accepted
    override suspend fun approvePlan(runId: ProviderRunId) = ProviderActionResult.Accepted
    override suspend fun cancel(runId: ProviderRunId) = ProviderActionResult.Accepted
}

private class RecordingReconnectProvider : AgentProvider {
    override val id = AgentProviderId("recording-reconnect")
    var reconnectCalls = 0
    var startCalls = 0
    var reconnectedRequest: AgentTaskRequest? = null
    var planApproved: Boolean? = null

    override suspend fun capabilities() = AgentCapabilities(supported = setOf(AgentCapability.PlanApproval))

    override suspend fun start(request: AgentTaskRequest): AgentRunHandle {
        startCalls += 1
        return AgentRunHandle(ProviderRunId("unexpected-start"))
    }

    override suspend fun reconnect(
        runId: ProviderRunId,
        request: AgentTaskRequest,
        planApproved: Boolean,
    ): ProviderActionResult {
        reconnectCalls += 1
        reconnectedRequest = request
        this.planApproved = planApproved
        return ProviderActionResult.Accepted
    }

    override fun observe(runId: ProviderRunId): Flow<AgentEvent> = emptyFlow()
    override suspend fun sendMessage(runId: ProviderRunId, message: String) = ProviderActionResult.Accepted
    override suspend fun approvePlan(runId: ProviderRunId) = ProviderActionResult.Accepted
    override suspend fun cancel(runId: ProviderRunId) = ProviderActionResult.Accepted
}

private class RetryingObserverProvider : AgentProvider {
    override val id = AgentProviderId("retry-observer")
    private val observeCounter = MutableStateFlow(0)
    val observeCount: Int
        get() = observeCounter.value
    val firstObservationFailed = CompletableDeferred<Unit>()
    override suspend fun capabilities() = AgentCapabilities(supported = setOf(AgentCapability.RepositoryRead))
    override suspend fun start(request: AgentTaskRequest) = AgentRunHandle(ProviderRunId("provider-run"))
    override fun observe(runId: ProviderRunId): Flow<AgentEvent> = flow {
        val attempt = observeCounter.value + 1
        observeCounter.value = attempt
        if (attempt == 1) {
            firstObservationFailed.complete(Unit)
            throw IllegalStateException("temporary transport failure")
        }
        emit(AgentEvent.Completed(runId))
    }
    override suspend fun sendMessage(runId: ProviderRunId, message: String) = ProviderActionResult.Accepted
    override suspend fun approvePlan(runId: ProviderRunId) = ProviderActionResult.Accepted
    override suspend fun cancel(runId: ProviderRunId) = ProviderActionResult.Accepted
}

private class ReplayingArtifactProvider : AgentProvider {
    override val id = AgentProviderId("replaying-artifact")
    private val observeCounter = MutableStateFlow(0)
    val observeCount: Int
        get() = observeCounter.value
    private val artifact = ProviderArtifact(
        kind = ArtifactKind.CommandOutput,
        label = "result",
        textContent = "ok",
        metadata = mapOf("command" to "test"),
    )
    override suspend fun capabilities() = AgentCapabilities(supported = setOf(AgentCapability.RepositoryRead))
    override suspend fun start(request: AgentTaskRequest) = AgentRunHandle(ProviderRunId("artifact-run"))
    override fun observe(runId: ProviderRunId): Flow<AgentEvent> = flow {
        val attempt = observeCounter.value + 1
        observeCounter.value = attempt
        emit(AgentEvent.ArtifactProduced(runId, artifact))
        if (attempt == 1) throw IllegalStateException("reconnect")
        emit(AgentEvent.Completed(runId))
    }
    override suspend fun sendMessage(runId: ProviderRunId, message: String) = ProviderActionResult.Accepted
    override suspend fun approvePlan(runId: ProviderRunId) = ProviderActionResult.Accepted
    override suspend fun cancel(runId: ProviderRunId) = ProviderActionResult.Accepted
}

private class RecordingCancellationProvider : AgentProvider {
    override val id = AgentProviderId("recording-cancel")
    var cancelCalls = 0

    override suspend fun capabilities() = AgentCapabilities(supported = setOf(AgentCapability.RepositoryRead))
    override suspend fun start(request: AgentTaskRequest) = AgentRunHandle(ProviderRunId("cancel-run"))
    override fun observe(runId: ProviderRunId): Flow<AgentEvent> = emptyFlow()
    override suspend fun sendMessage(runId: ProviderRunId, message: String) = ProviderActionResult.Accepted
    override suspend fun approvePlan(runId: ProviderRunId) = ProviderActionResult.Accepted
    override suspend fun cancel(runId: ProviderRunId): ProviderActionResult {
        cancelCalls += 1
        return ProviderActionResult.Accepted
    }
}
