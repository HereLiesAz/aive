package com.hereliesaz.geministrator.inference

import com.hereliesaz.geministrator.domain.AcceptanceCriterion
import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.providers.AgentCapabilities
import com.hereliesaz.geministrator.providers.AgentProvider
import com.hereliesaz.geministrator.providers.AgentRunHandle
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.PromptCacheCapabilities
import com.hereliesaz.geministrator.providers.PromptCacheMode
import com.hereliesaz.geministrator.providers.ProviderActionResult
import com.hereliesaz.geministrator.providers.ProviderArtifact
import com.hereliesaz.geministrator.workflow.AgentProviderRegistry
import com.hereliesaz.geministrator.workflow.ManagedSessionRequest
import com.hereliesaz.geministrator.workflow.ProviderBackedManagedSessionGateway
import com.hereliesaz.geministrator.workflow.ProviderSelectionRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class BlueprintInferenceFabricTest {
    @Test
    fun prepareDispatchIndexesAgentDataAndCreatesTypedInputStream() = runBlocking {
        val fabric = BlueprintCompoundInferenceFabric()
        val taskRunId = TaskRunId("child")
        val artifact = ArtifactRef(
            id = ArtifactId("research"),
            kind = ArtifactKind.Research,
            taskRunId = TaskRunId("parent"),
            label = "Research evidence",
            textContent = "evidence",
            createdAtEpochMillis = 1L,
        )
        val request = AgentTaskRequest(
            taskRunId = taskRunId,
            objective = "Synthesize",
            roleInstructions = "Use the evidence.",
            acceptanceCriteria = listOf(AcceptanceCriterion("Produces synthesis")),
            contextArtifacts = listOf(artifact),
        )
        val providerId = AgentProviderId("provider")
        val capabilities = AgentCapabilities(
            supported = setOf(AgentCapability.Research),
            promptCaching = PromptCacheCapabilities(
                modes = setOf(PromptCacheMode.SessionScoped),
                reportsCacheUsage = true,
            ),
        )

        val prepared = fabric.prepareDispatch(request, providerId, capabilities)

        assertEquals(CompoundInferenceStrategy.Single, prepared.plan.strategy)
        assertEquals(providerId, prepared.plan.providerId)
        assertEquals(listOf("artifact:research"), prepared.plan.dataIds)
        assertEquals(setOf(AgentCapability.Research), assertNotNull(fabric.agentRegistry.get(providerId)).capabilities)
        assertEquals(artifact.id, assertNotNull(fabric.dataRegistry.get("artifact:research")).artifactId)
        val records = fabric.streamFabric.records(prepared.plan.invocationId)
        assertEquals(1, records.size)
        assertIs<InferenceStreamPayload.DispatchPrepared>(records.single().payload)
    }

    @Test
    fun repeatedStartsOfTheSameTaskCreateDistinctConcreteInvocations() = runBlocking {
        val fabric = BlueprintCompoundInferenceFabric()
        val providerId = AgentProviderId("provider")
        val request = AgentTaskRequest(
            taskRunId = TaskRunId("retry-task"),
            objective = "Retryable work",
            roleInstructions = "Do the work.",
            acceptanceCriteria = emptyList(),
        )
        val capabilities = AgentCapabilities(supported = emptySet())

        val first = fabric.prepareDispatch(request, providerId, capabilities)
        val second = fabric.prepareDispatch(request, providerId, capabilities)

        assertNotEquals(first.plan.invocationId, second.plan.invocationId)
        assertEquals("${request.compoundInference.genealogy.invocationId}:invocation:1", first.plan.invocationId)
        assertEquals("${request.compoundInference.genealogy.invocationId}:invocation:2", second.plan.invocationId)
    }

    @Test
    fun usageArtifactsAndTerminalStateStayOnTheSameInvocationStream() = runBlocking {
        val fabric = BlueprintCompoundInferenceFabric()
        val providerId = AgentProviderId("provider")
        val taskRunId = TaskRunId("task")
        val request = AgentTaskRequest(
            taskRunId = taskRunId,
            objective = "Work",
            roleInstructions = "Do the work.",
            acceptanceCriteria = emptyList(),
        )
        val plan = fabric.prepareDispatch(
            request,
            providerId,
            AgentCapabilities(supported = emptySet()),
        ).plan

        fabric.recordArtifact(
            taskRunId,
            ProviderArtifact(ArtifactKind.Research, "Observed evidence", textContent = "evidence"),
        )
        fabric.recordUsage(
            taskRunId = taskRunId,
            providerId = providerId,
            inputTokens = 100,
            outputTokens = 25,
            costUsd = 0.01,
            cacheHitFraction = 0.5f,
            latencyMillis = 400,
        )
        fabric.recordTerminal(taskRunId, InferenceTerminalStatus.Completed)

        val records = fabric.streamFabric.records(plan.invocationId)
        assertEquals(4, records.size)
        assertIs<InferenceStreamPayload.ArtifactObserved>(records[1].payload)
        assertIs<InferenceStreamPayload.UsageObserved>(records[2].payload)
        assertIs<InferenceStreamPayload.Terminal>(records[3].payload)
        val samples = fabric.resourceTelemetry.samples(plan.invocationId)
        assertEquals(1, samples.size)
        assertEquals(400, samples.single().latencyMillis)
    }

    @Test
    fun providerGatewayRoutesEveryStartedAgentSessionThroughFabric() = runBlocking {
        val fabric = BlueprintCompoundInferenceFabric()
        val provider = CapturingInferenceProvider()
        val registry = AgentProviderRegistry(listOf(provider), fabric)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val gateway = ProviderBackedManagedSessionGateway(registry, scope)
            val request = AgentTaskRequest(
                taskRunId = TaskRunId("gateway-task"),
                objective = "Work",
                roleInstructions = "Work within scope.",
                acceptanceCriteria = emptyList(),
            )

            gateway.createSession(
                ManagedSessionRequest(
                    providerSelection = ProviderSelectionRequest(),
                    taskRequest = request,
                ),
            )

            val started = assertNotNull(provider.startedRequest)
            assertNotNull(fabric.agentRegistry.get(provider.id))
            val records = fabric.streamFabric.records(started.compoundInference.genealogy.invocationId)
            assertEquals(1, records.size)
            assertIs<InferenceStreamPayload.DispatchPrepared>(records.single().payload)
        } finally {
            scope.cancel()
        }
    }
}

private class CapturingInferenceProvider : AgentProvider {
    override val id = AgentProviderId("capturing-inference")
    var startedRequest: AgentTaskRequest? = null

    override suspend fun capabilities(): AgentCapabilities = AgentCapabilities(
        supported = setOf(AgentCapability.Research),
    )

    override suspend fun start(request: AgentTaskRequest): AgentRunHandle {
        startedRequest = request
        return AgentRunHandle(ProviderRunId("capturing-run"))
    }

    override fun observe(runId: ProviderRunId): Flow<com.hereliesaz.geministrator.providers.AgentEvent> = emptyFlow()

    override suspend fun sendMessage(runId: ProviderRunId, message: String): ProviderActionResult =
        ProviderActionResult.Accepted

    override suspend fun approvePlan(runId: ProviderRunId): ProviderActionResult = ProviderActionResult.Accepted

    override suspend fun cancel(runId: ProviderRunId): ProviderActionResult = ProviderActionResult.Accepted
}
