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
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.PromptCacheCapabilities
import com.hereliesaz.geministrator.providers.PromptCacheMode
import com.hereliesaz.geministrator.providers.ProviderArtifact
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class SettingsInferenceFabricTest {
    @Test
    fun registriesStreamsTelemetryBindingsAndSequenceSurviveRestart() = runBlocking {
        val settings = MapSettings()
        val first = SettingsCompoundInferenceFabric(SettingsInferenceStateStore(settings))
        val providerId = AgentProviderId("provider")
        val providerRunId = ProviderRunId("provider-run-1")
        val taskRunId = TaskRunId("restart-task")
        val artifact = ArtifactRef(
            id = ArtifactId("research-source"),
            kind = ArtifactKind.Research,
            taskRunId = TaskRunId("research-task"),
            label = "Research source",
            mediaType = "text/plain",
            textContent = "evidence",
            createdAtEpochMillis = 1L,
        )
        val request = AgentTaskRequest(
            taskRunId = taskRunId,
            objective = "Synthesize evidence",
            roleInstructions = "Use the supplied evidence.",
            acceptanceCriteria = listOf(AcceptanceCriterion("Produces a synthesis")),
            contextArtifacts = listOf(artifact),
        )
        val capabilities = AgentCapabilities(
            supported = setOf(AgentCapability.Research),
            promptCaching = PromptCacheCapabilities(
                modes = setOf(PromptCacheMode.SessionScoped),
                reportsCacheUsage = true,
            ),
        )
        val model = InferenceModelDescriptor(
            logicalModelId = "specialist",
            baseModelId = "base",
            adapterId = "adapter",
            precision = "int8",
            backend = "onnx",
            capabilities = setOf("research"),
            releaseDigest = "sha256:model",
        )
        first.modelRegistry.register(model)

        val firstPlan = first.prepareDispatch(request, providerId, capabilities).plan
        first.bindProviderRun(firstPlan.invocationId, taskRunId, providerId, providerRunId)
        first.recordUsage(
            providerId = providerId,
            providerRunId = providerRunId,
            inputTokens = 100,
            outputTokens = 25,
            costUsd = 0.01,
            cacheHitFraction = 0.5f,
            latencyMillis = 400,
        )
        first.recordArtifact(
            providerId,
            providerRunId,
            ProviderArtifact(ArtifactKind.Research, "Observed evidence", textContent = "observed"),
        )
        first.recordTerminal(providerId, providerRunId, InferenceTerminalStatus.Completed)

        val recreated = SettingsCompoundInferenceFabric(SettingsInferenceStateStore(settings))
        assertEquals(model, recreated.modelRegistry.get(model.logicalModelId))
        assertEquals(setOf(AgentCapability.Research), assertNotNull(recreated.agentRegistry.get(providerId)).capabilities)
        assertEquals(artifact.id, assertNotNull(recreated.dataRegistry.get("artifact:${artifact.id.value}")).artifactId)

        val restoredRecords = recreated.streamFabric.records(firstPlan.invocationId)
        assertEquals(5, restoredRecords.size)
        assertIs<InferenceStreamPayload.DispatchPrepared>(restoredRecords[0].payload)
        assertIs<InferenceStreamPayload.ProviderRunBound>(restoredRecords[1].payload)
        assertIs<InferenceStreamPayload.UsageObserved>(restoredRecords[2].payload)
        assertIs<InferenceStreamPayload.ArtifactObserved>(restoredRecords[3].payload)
        assertIs<InferenceStreamPayload.Terminal>(restoredRecords[4].payload)
        assertEquals(400, recreated.resourceTelemetry.samples(firstPlan.invocationId).single().latencyMillis)

        val secondPlan = recreated.prepareDispatch(request, providerId, capabilities).plan
        assertNotEquals(firstPlan.invocationId, secondPlan.invocationId)
        assertEquals("${request.compoundInference.genealogy.invocationId}:invocation:2", secondPlan.invocationId)

        recreated.recordArtifact(
            providerId,
            providerRunId,
            ProviderArtifact(ArtifactKind.Research, "Late event after restart", textContent = "late"),
        )
        val oldStreamAfterRestart = recreated.streamFabric.records(firstPlan.invocationId)
        assertIs<InferenceStreamPayload.ArtifactObserved>(oldStreamAfterRestart.last().payload)
        assertEquals("Late event after restart", (oldStreamAfterRestart.last().payload as InferenceStreamPayload.ArtifactObserved).label)
    }
}
