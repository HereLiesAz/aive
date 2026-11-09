package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.persistence.InMemorySettings
import com.hereliesaz.geministrator.providers.AgentEvent
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** A creature pinned to a provider is never moved to another one, even while that provider is benched. */
class PinnedProviderBenchTest {
    private val a = ScriptedProvider("pinned-a") { emptyFlow<AgentEvent>() }
    private val b = ScriptedProvider("other-b") { emptyFlow<AgentEvent>() }

    @Test
    fun aPinnedRoleKeepsItsBenchedProviderInsteadOfBeingSubstituted() = runBlocking<Unit> {
        val registry = AgentProviderRegistry(listOf(a, b), inferenceSettings = InMemorySettings())
        registry.recordFailure(a.id, "HTTP 402: insufficient credit")

        val pinned = registry.select(ProviderSelectionRequest(preferredProviderId = a.id))

        // Before: the benched provider was dropped from the choice and b answered, silently.
        assertEquals(a.id, pinned.id)
    }

    @Test
    fun aPinnedRoleKeepsItsProviderWhenTheRoutersCircuitBreakerIsOpen() = runBlocking<Unit> {
        val registry = AgentProviderRegistry(listOf(a, b), inferenceSettings = InMemorySettings())
        repeat(3) { registry.recordOutcome(a.id, success = false) }

        assertEquals(a.id, registry.select(ProviderSelectionRequest(preferredProviderId = a.id)).id)
    }

    @Test
    fun anAutoRoleStillPassesOverABenchedProvider() = runBlocking<Unit> {
        val registry = AgentProviderRegistry(listOf(a, b), inferenceSettings = InMemorySettings())
        registry.recordFailure(a.id, "HTTP 402: insufficient credit")

        assertEquals(AgentProviderId("other-b"), registry.select(ProviderSelectionRequest(failoverAllowed = true)).id)
    }
}
