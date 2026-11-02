package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AcceptanceCriterion
import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.persistence.InMemorySettings
import com.hereliesaz.geministrator.providers.AgentCapabilities
import com.hereliesaz.geministrator.providers.AgentEvent
import com.hereliesaz.geministrator.providers.AgentProvider
import com.hereliesaz.geministrator.providers.AgentRunHandle
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.OnDeviceTextAgentProvider
import com.hereliesaz.geministrator.providers.OnDeviceTextGeneration
import com.hereliesaz.geministrator.providers.OnDeviceTextGenerator
import com.hereliesaz.geministrator.providers.ProviderActionResult
import com.hereliesaz.geministrator.providers.onDeviceTextProviderOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnDeviceSmallTaskRoutingTest {
    private val cloud = CloudProvider()

    private fun registry(vararg providers: AgentProvider) =
        AgentProviderRegistry(providers.toList(), inferenceSettings = InMemorySettings())

    private fun onDevice(generator: FakeGenerator = FakeGenerator()) =
        assertNotNull(onDeviceTextProviderOrNull(enabled = true, generator = generator))

    private val smallTextTask = ProviderSelectionRequest(estimatedInputTokens = 200)

    @Test
    fun aSmallTextTaskGoesToTheDeviceWhenEnabled() = runBlocking {
        // Registered after the cloud provider, and still preferred.
        val selected = registry(cloud, onDevice()).select(smallTextTask)
        assertEquals(OnDeviceTextAgentProvider.ID, selected.id)
    }

    @Test
    fun aTaskNeedingRepositoryAccessNeverGoesToTheDevice() = runBlocking {
        val registry = registry(cloud, onDevice())
        listOf(AgentCapability.RepositoryRead, AgentCapability.RepositoryWrite).forEach { capability ->
            val selected = registry.select(
                ProviderSelectionRequest(requiredCapabilities = setOf(capability), estimatedInputTokens = 50),
            )
            assertEquals(cloud.id, selected.id, "$capability")
        }
    }

    @Test
    fun aLargeOrUnmeasuredInputNeverGoesToTheDevice() = runBlocking {
        val onDevice = onDevice()
        val registry = registry(cloud, onDevice)
        val large = ProviderSelectionRequest(estimatedInputTokens = onDevice.maxInputTokens + 1)
        assertEquals(cloud.id, registry.select(large).id)
        assertEquals(cloud.id, registry.select(ProviderSelectionRequest()).id, "unknown size")
        // At the limit it still fits.
        val atLimit = ProviderSelectionRequest(estimatedInputTokens = onDevice.maxInputTokens)
        assertEquals(OnDeviceTextAgentProvider.ID, registry.select(atLimit).id)
    }

    @Test
    fun aLargeTaskIsNotSentToTheDeviceEvenAsTheRolesPreferredProvider() = runBlocking {
        val onDevice = onDevice()
        val selected = registry(cloud, onDevice).select(
            ProviderSelectionRequest(
                preferredProviderId = OnDeviceTextAgentProvider.ID,
                estimatedInputTokens = onDevice.maxInputTokens * 4,
            ),
        )
        assertEquals(cloud.id, selected.id)
    }

    @Test
    fun theRolesOwnPreferredProviderStaysFirst() = runBlocking {
        val other = CloudProvider(AgentProviderId("other"))
        val selected = registry(cloud, other, onDevice()).select(smallTextTask.copy(preferredProviderId = other.id))
        assertEquals(other.id, selected.id)
    }

    @Test
    fun disabledNeverRegistersTheProvider() = runBlocking {
        assertNull(onDeviceTextProviderOrNull(enabled = false, generator = FakeGenerator()))
        assertNull(onDeviceTextProviderOrNull(enabled = true, generator = FakeGenerator(ready = false)), "no model installed")
        assertNull(onDeviceTextProviderOrNull(enabled = true, generator = null))
        val providers = listOf<AgentProvider>(cloud) + listOfNotNull(onDeviceTextProviderOrNull(false, FakeGenerator()))
        assertEquals(cloud.id, registry(*providers.toTypedArray()).select(smallTextTask).id)
    }

    @Test
    fun aModelRemovedAfterRegistrationTakesNoWork() = runBlocking {
        val generator = FakeGenerator()
        val registry = registry(cloud, onDevice(generator))
        generator.ready = false
        assertEquals(cloud.id, registry.select(smallTextTask).id)
    }

    @Test
    fun theDeviceAloneStaffsNoRole() = runBlocking {
        val registry = registry(onDevice())
        assertFalse(registry.canStaff(emptySet()))
        assertTrue(registry(cloud, onDevice()).canStaff(emptySet()))
    }

    @Test
    fun aRunProducesItsTextAndReportsUsageAtNoCost() = runBlocking {
        val generator = FakeGenerator(reply = "A short summary.")
        val provider = onDevice(generator)
        val request = AgentTaskRequest(
            taskRunId = TaskRunId("task-1"),
            objective = "Summarise the notes.",
            roleInstructions = "Be brief.",
            acceptanceCriteria = listOf(AcceptanceCriterion("One sentence")),
            requiredArtifacts = setOf(ArtifactKind.Requirement),
        )
        val handle = provider.start(request)
        val events = provider.observe(handle.providerRunId).toList()

        val artifact = assertIs<AgentEvent.ArtifactProduced>(events.single { it is AgentEvent.ArtifactProduced }).artifact
        assertEquals("A short summary.", artifact.textContent)
        assertEquals(ArtifactKind.Requirement, artifact.kind)
        val usage = assertIs<AgentEvent.UsageReported>(events.single { it is AgentEvent.UsageReported })
        assertEquals(0.0, usage.costUsd)
        assertEquals(12L, usage.inputTokens)
        assertEquals(4L, usage.outputTokens)
        assertIs<AgentEvent.Completed>(events.last())
        assertTrue("Summarise the notes." in generator.lastUser.orEmpty())
        assertTrue("One sentence" in generator.lastUser.orEmpty())
    }

    @Test
    fun aGenerationFailureFailsTheRun() = runBlocking {
        val provider = onDevice(FakeGenerator(failure = IllegalStateException("out of memory")))
        val handle = provider.start(
            AgentTaskRequest(TaskRunId("task-2"), "Classify this.", "", emptyList()),
        )
        val events = provider.observe(handle.providerRunId).toList()
        val failed = assertIs<AgentEvent.Failed>(events.last())
        assertTrue("out of memory" in failed.reason)
        assertTrue(events.none { it is AgentEvent.ArtifactProduced || it is AgentEvent.Completed })
    }

    @Test
    fun tokenEstimateRoundsUp() {
        assertEquals(0, OnDeviceTextAgentProvider.estimateTokens(0))
        assertEquals(1, OnDeviceTextAgentProvider.estimateTokens(1))
        assertEquals(2, OnDeviceTextAgentProvider.estimateTokens(5))
    }
}

private class FakeGenerator(
    var ready: Boolean = true,
    private val reply: String = "ok",
    private val failure: Throwable? = null,
) : OnDeviceTextGenerator {
    var lastUser: String? = null

    override fun isReady(): Boolean = ready

    override suspend fun generate(system: String, user: String, maxNewTokens: Int): OnDeviceTextGeneration {
        lastUser = user
        failure?.let { throw it }
        return OnDeviceTextGeneration(reply, inputTokens = 12, outputTokens = 4)
    }
}

private class CloudProvider(
    override val id: AgentProviderId = AgentProviderId("cloud"),
) : AgentProvider {
    override suspend fun capabilities(): AgentCapabilities = AgentCapabilities(
        setOf(AgentCapability.RepositoryRead, AgentCapability.RepositoryWrite, AgentCapability.Research),
    )

    override suspend fun start(request: AgentTaskRequest): AgentRunHandle = AgentRunHandle(ProviderRunId("run"))

    override fun observe(runId: ProviderRunId): Flow<AgentEvent> = emptyFlow()

    override suspend fun sendMessage(runId: ProviderRunId, message: String): ProviderActionResult =
        ProviderActionResult.Accepted

    override suspend fun approvePlan(runId: ProviderRunId): ProviderActionResult = ProviderActionResult.Accepted

    override suspend fun cancel(runId: ProviderRunId): ProviderActionResult = ProviderActionResult.Accepted
}
