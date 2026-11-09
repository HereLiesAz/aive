package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.persistence.InMemorySettings
import com.hereliesaz.geministrator.providers.AgentCapabilities
import com.hereliesaz.geministrator.providers.AgentEvent
import com.hereliesaz.geministrator.providers.AgentProvider
import com.hereliesaz.geministrator.providers.AgentRunHandle
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.ProviderActionResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the gateway does with a session's usage and its observer: reports are summed, a bad number never
 * reaches a total, usage that arrives once the session ended still counts, a cancelled session stops
 * its observer, a failed session counts once against its provider, and a released one is forgotten.
 */
class SessionUsageAndLifecycleTest {
    private fun handle(provider: ScriptedProvider, name: String) = ManagedSessionHandle(
        taskRunId = TaskRunId("task-$name"),
        providerId = provider.id,
        providerRunId = ProviderRunId("run-$name"),
    )

    private suspend fun gatewayWith(
        scope: CoroutineScope,
        vararg providers: ScriptedProvider,
    ): Pair<ProviderBackedManagedSessionGateway, AgentProviderRegistry> {
        val registry = AgentProviderRegistry(providers.toList(), inferenceSettings = InMemorySettings())
        return ProviderBackedManagedSessionGateway(registry, scope) to registry
    }

    private suspend fun waitFor(condition: suspend () -> Boolean) = withTimeout(3_000L) {
        while (!condition()) delay(10L)
    }

    @Test
    fun twoUsageReportsBeforeTheEngineReadsThemAreSummedNotOverwritten() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val release = CompletableDeferred<Unit>()
            val provider = ScriptedProvider("summing") { runId ->
                flow {
                    emit(AgentEvent.UsageReported(runId, inputTokens = 100, outputTokens = 10, costUsd = 0.5))
                    emit(AgentEvent.UsageReported(runId, inputTokens = 40, outputTokens = 4, costUsd = 0.25))
                    release.await()
                }
            }
            val (gateway, _) = gatewayWith(scope, provider)
            val handle = handle(provider, "sum")
            gateway.reconnect(handle, ManagedSessionStatus.Running)
            waitFor { provider.observed.isCompleted }
            delay(100L)

            val usage = assertNotNull(gateway.usageReport(handle))
            assertEquals(140L, usage.inputTokens)
            assertEquals(14L, usage.outputTokens)
            assertEquals(0.75, usage.costUsd)
            release.complete(Unit)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aReportThatIsNotANumberOrIsNegativeNeverReachesATotal() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val provider = ScriptedProvider("bad-numbers") { runId ->
                flow {
                    emit(AgentEvent.UsageReported(runId, inputTokens = 10, outputTokens = 1, costUsd = Double.NaN))
                    emit(AgentEvent.UsageReported(runId, inputTokens = -500, outputTokens = 2, costUsd = Double.POSITIVE_INFINITY))
                    emit(AgentEvent.UsageReported(runId, inputTokens = 5, outputTokens = 3, costUsd = -1.0))
                    awaitCancellation()
                }
            }
            val (gateway, _) = gatewayWith(scope, provider)
            val handle = handle(provider, "bad")
            gateway.reconnect(handle, ManagedSessionStatus.Running)
            waitFor { provider.observed.isCompleted }
            delay(100L)

            val usage = assertNotNull(gateway.usageReport(handle))
            assertTrue(usage.costUsd == null || (usage.costUsd!!.isFinite() && usage.costUsd!! >= 0.0), "cost ${usage.costUsd}")
            assertTrue((usage.inputTokens ?: 0L) >= 0L, "input ${usage.inputTokens}")
            assertEquals(15L, usage.inputTokens, "only the readable token counts are kept")
            assertEquals(6L, usage.outputTokens)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun usageThatArrivesAfterTheSessionEndedIsStillCounted() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val provider = ScriptedProvider("late-usage") { runId ->
                flow {
                    emit(AgentEvent.Completed(runId))
                    emit(AgentEvent.UsageReported(runId, inputTokens = 70, outputTokens = 7))
                }
            }
            val (gateway, _) = gatewayWith(scope, provider)
            val handle = handle(provider, "late")
            gateway.reconnect(handle, ManagedSessionStatus.Running)
            waitFor { gateway.status(handle) == ManagedSessionStatus.Completed }
            delay(150L)

            val usage = gateway.usageReport(handle)
            assertEquals(70L, usage?.inputTokens)
            assertEquals(7L, usage?.outputTokens)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cancellingASessionStopsItsObserverSoTheGenerationStopsSpending() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val stopped = CompletableDeferred<Unit>()
            val provider = ScriptedProvider("cancel-observer") {
                flow {
                    try {
                        awaitCancellation()
                    } finally {
                        stopped.complete(Unit)
                    }
                }
            }
            val (gateway, _) = gatewayWith(scope, provider)
            val handle = handle(provider, "observer")
            gateway.reconnect(handle, ManagedSessionStatus.Running)
            waitFor { provider.observed.isCompleted }

            assertEquals(ProviderActionResult.Accepted, gateway.cancel(handle))

            withTimeout(2_000L) { stopped.await() }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aFailedSessionCountsOnceAgainstItsProviderEvenWhenFailoverIsAskedAbout() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val provider = ScriptedProvider("fails-once") { runId ->
                flow { emit(AgentEvent.Failed(runId, "HTTP 503: upstream unavailable")) }
            }
            val other = ScriptedProvider("other") { runId -> flow { emit(AgentEvent.Completed(runId)) } }
            val (gateway, registry) = gatewayWith(scope, provider, other)
            val handle = handle(provider, "fail")
            gateway.reconnect(handle, ManagedSessionStatus.Running)
            waitFor { gateway.status(handle) == ManagedSessionStatus.Failed }

            gateway.adviseFailover(
                failedProviderId = provider.id,
                reason = "HTTP 503: upstream unavailable",
                selection = ProviderSelectionRequest(failoverAllowed = true),
            )

            assertEquals(1, registry.consecutiveFailuresOf(provider.id))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aReleasedSessionIsForgotten() = runBlocking<Unit> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val provider = ScriptedProvider("release") { runId -> flow { emit(AgentEvent.Completed(runId)) } }
            val (gateway, _) = gatewayWith(scope, provider)
            val handle = handle(provider, "released")
            gateway.reconnect(handle, ManagedSessionStatus.Running)
            waitFor { gateway.status(handle) == ManagedSessionStatus.Completed }
            assertEquals(1, gateway.trackedSessionCount())

            gateway.release(handle)

            assertEquals(0, gateway.trackedSessionCount())
            assertEquals(ManagedSessionStatus.Unknown, gateway.status(handle))
            assertNull(gateway.usageReport(handle))
        } finally {
            scope.cancel()
        }
    }
}

internal class ScriptedProvider(
    private val name: String,
    private val script: (ProviderRunId) -> Flow<AgentEvent>,
) : AgentProvider {
    override val id = AgentProviderId(name)
    val observed = CompletableDeferred<Unit>()

    override suspend fun capabilities() = AgentCapabilities(supported = setOf(AgentCapability.RepositoryRead))
    override suspend fun start(request: AgentTaskRequest) = AgentRunHandle(ProviderRunId("$name-run"))
    override fun observe(runId: ProviderRunId): Flow<AgentEvent> = flow {
        observed.complete(Unit)
        script(runId).collect { emit(it) }
    }

    override suspend fun sendMessage(runId: ProviderRunId, message: String) = ProviderActionResult.Accepted
    override suspend fun approvePlan(runId: ProviderRunId) = ProviderActionResult.Accepted
    override suspend fun cancel(runId: ProviderRunId) = ProviderActionResult.Accepted
}
