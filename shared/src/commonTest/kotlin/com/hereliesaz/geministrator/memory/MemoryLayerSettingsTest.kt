package com.hereliesaz.geministrator.memory

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MemoryLayerSettingsTest {
    @Test
    fun defaultsAreAllProgrammatic() {
        val assembly = MemoryLayerSettings().assembleAgents(object : MemoryEngineProvider {})
        assertTrue(assembly.resolved.values.all { it == MemoryEngineKind.Programmatic })
        assertTrue(assembly.agents.all { it is ProgrammaticMemoryMicroAgent })
        assertTrue(assembly.fallbacks.isEmpty())
    }

    @Test
    fun unavailableEnginesFallBackWithAReason() {
        val settings = MemoryLayerSettings()
            .withEngine(MemoryMicroAgentRole.SummarySynthesizer, MemoryStageEngine(MemoryEngineKind.LocalModel))
            .withEngine(MemoryMicroAgentRole.Sectioner, MemoryStageEngine(MemoryEngineKind.HostedModel, providerId = "llm7"))
        val assembly = settings.assembleAgents(object : MemoryEngineProvider {})
        assertEquals(MemoryEngineKind.Programmatic, assembly.resolved.getValue(MemoryMicroAgentRole.SummarySynthesizer))
        assertEquals(setOf(MemoryMicroAgentRole.SummarySynthesizer, MemoryMicroAgentRole.Sectioner), assembly.fallbacks.keys)
    }

    @Test
    fun hostedStageRunsThroughTheStructuredContract() = runBlocking {
        val prompts = mutableListOf<String>()
        val provider = object : MemoryEngineProvider {
            override fun hostedAgent(role: MemoryMicroAgentRole, engine: MemoryStageEngine) =
                HostedMemoryGenerativeRuntime.agent(role, engine, MemoryMicroAgentPlatform.Linux) { prompt ->
                    prompts += prompt
                    """{"sections":[],"nodes":[],"links":[]}"""
                }
        }
        val settings = MemoryLayerSettings()
            .withEngine(MemoryMicroAgentRole.Sectioner, MemoryStageEngine(MemoryEngineKind.HostedModel, providerId = "kilo"))
        val assembly = settings.assembleAgents(provider)
        assertEquals(MemoryEngineKind.HostedModel, assembly.resolved.getValue(MemoryMicroAgentRole.Sectioner))

        val store = InMemoryMemoryStore()
        val layer = AgentMemoryLayer.createWithMicroAgents(store, assembly.agents, settings.policy)
        layer.queue.enqueueSession(
            MemorySessionEnvelope("s", userPrompt = "Remember the hosted path.", parts = emptyList(), closedAtEpochMillis = 1),
        )
        while (layer.consolidateOne(1) != MemoryConsolidationResult.Idle) Unit
        assertEquals(1, prompts.size)
        assertTrue("ROLE: Sectioner" in prompts.single())
    }

    @Test
    fun associationCannotBeHosted() {
        assertFailsWith<IllegalArgumentException> {
            MemoryLayerSettings().withEngine(
                MemoryMicroAgentRole.AssociationLinker,
                MemoryStageEngine(MemoryEngineKind.HostedModel),
            )
        }
    }

    @Test
    fun storePersistsAndPublishes() {
        val settings = MapSettings()
        val store = MemoryLayerSettingsStore(settings)
        store.update { it.copy(consolidationPaused = true).withEngine(MemoryMicroAgentRole.CategoryClassifier, MemoryStageEngine(MemoryEngineKind.LocalModel)) }
        assertTrue(store.state.value.consolidationPaused)
        val reopened = MemoryLayerSettingsStore(settings).state.value
        assertEquals(MemoryEngineKind.LocalModel, reopened.engineFor(MemoryMicroAgentRole.CategoryClassifier).kind)
        assertTrue(reopened.consolidationPaused)
    }
}
