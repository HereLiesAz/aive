package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.CompoundInferencePolicy
import com.hereliesaz.geministrator.domain.EnvironmentPlanningPolicy
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TestDesignPolicy
import com.hereliesaz.geministrator.domain.VerificationPolicy
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.inference.BlueprintCompoundInferenceFabric
import com.hereliesaz.geministrator.inference.InferenceGenealogy
import com.hereliesaz.geministrator.inference.InferenceGenealogyGovernanceRuntime
import com.hereliesaz.geministrator.inference.InferenceResourceSample
import com.hereliesaz.geministrator.providers.AgentCapabilities
import com.hereliesaz.geministrator.providers.AgentEvent
import com.hereliesaz.geministrator.providers.AgentProvider
import com.hereliesaz.geministrator.providers.AgentRunHandle
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.ProviderActionResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class ResourceAwareCompoundInferencePolicyResolverTest {
    @Test
    fun measuredBudgetCanSelectAndMaterializeCentralizedMoa() = runBlocking {
        val registry = registryWithHistory(costUsd = 0.10, samples = 2)

        val prepared = WorkflowDefinitionPreparer(registry, BuiltInRoles.all).prepare(
            resourceAwareDefinition(maxCostUsd = 0.50),
        )

        assertEquals(5, prepared.tasks.size)
        assertEquals(
            setOf(
                TaskDefinitionId("reason--moa-proposer-1"),
                TaskDefinitionId("reason--moa-proposer-2"),
                TaskDefinitionId("reason--moa-governance"),
            ),
            prepared.tasks.single { it.id == TaskDefinitionId("reason") }.dependsOn,
        )
    }

    @Test
    fun measuredBudgetOverrunFallsBackToSingle() = runBlocking {
        val registry = registryWithHistory(costUsd = 0.10, samples = 2)

        val prepared = WorkflowDefinitionPreparer(registry, BuiltInRoles.all).prepare(
            resourceAwareDefinition(maxCostUsd = 0.30),
        )

        assertEquals(1, prepared.tasks.size)
        assertEquals(CompoundInferencePolicy.Single, prepared.tasks.single().compoundInferencePolicy)
    }

    @Test
    fun missingResourceHistoryFallsBackToSingle() = runBlocking {
        val fabric = BlueprintCompoundInferenceFabric()
        val governance = InferenceGenealogyGovernanceRuntime()
        val registry = AgentProviderRegistry(
            providers = listOf(ResourceHistoryProvider),
            inferenceFabric = fabric,
            genealogyGovernance = governance,
        )

        val prepared = WorkflowDefinitionPreparer(registry, BuiltInRoles.all).prepare(
            resourceAwareDefinition(maxCostUsd = 100.0),
        )

        assertEquals(1, prepared.tasks.size)
        assertEquals(CompoundInferencePolicy.Single, prepared.tasks.single().compoundInferencePolicy)
    }

    private suspend fun registryWithHistory(
        costUsd: Double,
        samples: Int,
    ): AgentProviderRegistry {
        val fabric = BlueprintCompoundInferenceFabric()
        val governance = InferenceGenealogyGovernanceRuntime()
        repeat(samples) { index ->
            val invocationId = "historical-$index"
            governance.registerInvocation(InferenceGenealogy(invocationId = invocationId))
            fabric.resourceTelemetry.record(
                InferenceResourceSample(
                    invocationId = invocationId,
                    providerId = ResourceHistoryProvider.id,
                    costUsd = costUsd,
                    latencyMillis = 1_000L,
                ),
            )
        }
        return AgentProviderRegistry(
            providers = listOf(ResourceHistoryProvider),
            inferenceFabric = fabric,
            genealogyGovernance = governance,
        )
    }

    private fun resourceAwareDefinition(maxCostUsd: Double): WorkflowDefinition = WorkflowDefinition(
        id = WorkflowDefinitionId("resource-aware"),
        name = "Resource-aware topology",
        testDesignPolicy = TestDesignPolicy.None,
        tasks = listOf(
            TaskDefinition(
                id = TaskDefinitionId("reason"),
                name = "Reason",
                objective = "Synthesize a bounded implementation decision from the available evidence.",
                roleId = BuiltInRoles.ImplementationEngineer.id,
                executor = TaskExecutor.RoleAgent(BuiltInRoles.ImplementationEngineer.id),
                verificationPolicy = VerificationPolicy.Required(BuiltInRoles.QaEngineer.id),
                environmentPlanningPolicy = EnvironmentPlanningPolicy.NotRequired,
                compoundInferencePolicy = CompoundInferencePolicy.ResourceAware(
                    proposerRoleIds = listOf(BuiltInRoles.Researcher.id, BuiltInRoles.Architect.id),
                    aggregatorRoleId = BuiltInRoles.ImplementationEngineer.id,
                    maxEstimatedCostUsd = maxCostUsd,
                    minimumHistoricalSamplesPerProvider = 2,
                    minimumComplexityScore = 0,
                ),
            ),
        ),
    )
}

private object ResourceHistoryProvider : AgentProvider {
    override val id: AgentProviderId = AgentProviderId("resource-history")

    override suspend fun capabilities(): AgentCapabilities = AgentCapabilities(AgentCapability.entries.toSet())

    override suspend fun start(request: AgentTaskRequest): AgentRunHandle =
        AgentRunHandle(ProviderRunId("unused"))

    override fun observe(runId: ProviderRunId): Flow<AgentEvent> = emptyFlow()

    override suspend fun sendMessage(runId: ProviderRunId, message: String): ProviderActionResult =
        ProviderActionResult.Accepted

    override suspend fun approvePlan(runId: ProviderRunId): ProviderActionResult = ProviderActionResult.Accepted

    override suspend fun cancel(runId: ProviderRunId): ProviderActionResult = ProviderActionResult.Accepted
}
