package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.ProviderCatalog
import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.persistence.InMemorySettings
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class SourceFake(name: String, override val keyless: Boolean) : AgentProvider {
    override val id = AgentProviderId(name)
    override suspend fun capabilities() = AgentCapabilities(supported = setOf(AgentCapability.RepositoryRead))
    override suspend fun start(request: AgentTaskRequest) = AgentRunHandle(ProviderRunId("$id-run"))
    override fun observe(runId: ProviderRunId): Flow<AgentEvent> = emptyFlow()
    override suspend fun sendMessage(runId: ProviderRunId, message: String) = ProviderActionResult.Accepted
    override suspend fun approvePlan(runId: ProviderRunId) = ProviderActionResult.Accepted
    override suspend fun cancel(runId: ProviderRunId) = ProviderActionResult.Accepted
}

/** Keyless Sources start enabled, so they must never outrank, or stand in for, what the keeper chose. */
class KeylessSourceOrderingTest {
    private val keyedA = SourceFake("keyed-a", keyless = false)
    private val keyedB = SourceFake("keyed-b", keyless = false)
    private val free = SourceFake(ProviderCatalog.KILO_ID, keyless = true)

    private fun registry(vararg providers: AgentProvider, pacer: KeylessPacer = KeylessPacer()) =
        AgentProviderRegistry(providers.toList(), inferenceSettings = InMemorySettings(), keylessPacer = pacer)

    @Test
    fun autoWorkNeverSpreadsOntoAKeylessSourceWhileAKeyedOneIsHealthy() = runBlocking<Unit> {
        // The keyless one is registered first, as the hosted ones are, ahead of the native keyed ones.
        val registry = registry(free, keyedA, keyedB)
        val chosen = List(6) { registry.select(ProviderSelectionRequest(failoverAllowed = true)).id.value }
        assertFalse(ProviderCatalog.KILO_ID in chosen, "chosen: $chosen")
        assertEquals(setOf("keyed-a", "keyed-b"), chosen.toSet(), "the keyed ones still share the work")
    }

    @Test
    fun aKeylessSourceIsTheLastResortWhenEveryKeyedOneIsBenchedOrTried() = runBlocking<Unit> {
        val registry = registry(keyedA, free)
        registry.recordFailure(keyedA.id, "HTTP 402: insufficient credit")
        assertEquals(ProviderCatalog.KILO_ID, registry.select(ProviderSelectionRequest(failoverAllowed = true)).id.value)

        val fresh = registry(keyedA, free)
        val afterKeyed = fresh.select(ProviderSelectionRequest(failoverAllowed = true, excludedProviderIds = setOf(keyedA.id)))
        assertEquals(ProviderCatalog.KILO_ID, afterKeyed.id.value)
    }

    @Test
    fun aKeylessSourceIsTheOnlyChoiceOnAFreshInstall() = runBlocking<Unit> {
        assertEquals(ProviderCatalog.KILO_ID, registry(free).select(ProviderSelectionRequest()).id.value)
    }

    @Test
    fun aCreaturePinnedToAnotherSourceIsNeverMovedOntoAKeylessOne() = runBlocking<Unit> {
        val registry = registry(keyedA, free)
        val notConnected = AgentProviderId("pinned-but-gone")
        // The pin cannot be met; the app does not quietly hand the work to a keyless gateway.
        assertFailsWith<IllegalStateException> {
            registry.select(ProviderSelectionRequest(preferredProviderId = notConnected, excludedProviderIds = setOf(keyedA.id)))
        }
    }

    @Test
    fun aCreaturePinnedToAKeylessSourceStillGetsIt() = runBlocking<Unit> {
        val registry = registry(keyedA, free)
        assertEquals(
            ProviderCatalog.KILO_ID,
            registry.select(ProviderSelectionRequest(preferredProviderId = free.id)).id.value,
        )
    }

    @Test
    fun aKeylessSourceAtItsCatalogLimitIsPassedOverForTheWindow() {
        var now = 0L
        val pacer = KeylessPacer(now = { now })
        val ovh = ProviderCatalog.OVHCLOUD_ID
        assertTrue(pacer.hasRoom(ovh))
        pacer.record(ovh)
        pacer.record(ovh)
        assertFalse(pacer.hasRoom(ovh), "2 requests a minute")
        now = 61_000
        assertTrue(pacer.hasRoom(ovh))
        // A Source whose text names no number is never paced here.
        repeat(1000) { pacer.record(ProviderCatalog.LLM7_ID) }
        assertTrue(pacer.hasRoom(ProviderCatalog.LLM7_ID))
    }

    @Test
    fun aKeylessSourceOutOfRoomIsSkippedForAnotherThatHasSome() = runBlocking<Unit> {
        val pacer = KeylessPacer(now = { 0L })
        repeat(2) { pacer.record(ProviderCatalog.OVHCLOUD_ID) }
        val registry = registry(SourceFake(ProviderCatalog.OVHCLOUD_ID, keyless = true), free, pacer = pacer)
        repeat(4) {
            assertEquals(ProviderCatalog.KILO_ID, registry.select(ProviderSelectionRequest(failoverAllowed = true)).id.value)
        }
    }
}
