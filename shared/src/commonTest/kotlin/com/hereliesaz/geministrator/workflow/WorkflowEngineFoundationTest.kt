package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.persistence.InMemorySettings
import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.ProviderConstraints
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
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
import kotlin.test.assertTrue

class WorkflowEngineFoundationTest {
    @Test
    fun defaultTestPolicyInjectsIndependentPreAndPostCodePasses() {
        val implementation = TaskDefinition(
            id = TaskDefinitionId("implementation"),
            name = "Implementation",
            objective = "Build it",
            roleId = BuiltInRoles.ImplementationEngineer.id,
        )
        val qa = TaskDefinition(
            id = TaskDefinitionId("qa"),
            name = "QA",
            objective = "Verify it",
            roleId = BuiltInRoles.QaEngineer.id,
            dependsOn = setOf(implementation.id),
        )
        val expanded = WorkflowDefinitionExpander.expand(
            WorkflowDefinition(
                id = WorkflowDefinitionId("workflow"),
                name = "Workflow",
                tasks = listOf(implementation, qa),
            ),
        )

        val preCode = expanded.tasks.single { it.id.value == "implementation--pre-code-tests" }
        val postCode = expanded.tasks.single { it.id.value == "implementation--post-code-tests" }
        val implementationAfterExpansion = expanded.tasks.single { it.id == implementation.id }
        val qaAfterExpansion = expanded.tasks.single { it.id == qa.id }

        assertEquals(BuiltInRoles.CrashTestDummy.id, preCode.roleId)
        assertEquals(BuiltInRoles.CrashTestDummy.id, postCode.roleId)
        assertTrue(preCode.id in implementationAfterExpansion.dependsOn)
        assertTrue(preCode.dependsOn.isEmpty())
        assertEquals(setOf(implementation.id), postCode.dependsOn)
        assertTrue(ArtifactKind.RegressionTest in postCode.requiredArtifacts)
        assertEquals(setOf(postCode.id), qaAfterExpansion.dependsOn)
    }

    @Test
    fun providerRegistryHonorsCapabilitiesAndHardConstraint() = runBlocking {
        val basic = FakeProvider(AgentProviderId("basic"), setOf(AgentCapability.RepositoryRead))
        val capable = FakeProvider(
            AgentProviderId("capable"),
            setOf(AgentCapability.RepositoryRead, AgentCapability.RepositoryWrite),
        )
        val registry = AgentProviderRegistry(listOf(basic, capable), inferenceSettings = InMemorySettings())

        assertEquals(
            capable.id,
            registry.select(
                ProviderSelectionRequest(
                    requiredCapabilities = setOf(AgentCapability.RepositoryWrite),
                ),
            ).id,
        )
        assertEquals(
            basic.id,
            registry.select(
                ProviderSelectionRequest(
                    constraints = ProviderConstraints.RequireProvider(basic.id),
                ),
            ).id,
        )
    }
}

private class FakeProvider(
    override val id: AgentProviderId,
    private val supported: Set<AgentCapability>,
) : AgentProvider {
    override suspend fun capabilities() = AgentCapabilities(supported)
    override suspend fun start(request: AgentTaskRequest) = AgentRunHandle(ProviderRunId("run"))
    override fun observe(runId: ProviderRunId): Flow<AgentEvent> = emptyFlow()
    override suspend fun sendMessage(runId: ProviderRunId, message: String) = ProviderActionResult.Accepted
    override suspend fun approvePlan(runId: ProviderRunId) = ProviderActionResult.Accepted
    override suspend fun cancel(runId: ProviderRunId) = ProviderActionResult.Accepted
}
