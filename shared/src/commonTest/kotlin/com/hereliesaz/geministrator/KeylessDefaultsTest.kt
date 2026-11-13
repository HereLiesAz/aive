package com.hereliesaz.geministrator

import com.russhwolf.settings.MapSettings
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeylessDefaultsTest {
    private val original = KeylessDefaults.store

    @BeforeTest
    fun freshStore() {
        KeylessDefaults.store = DurableUiStateStore(MapSettings())
    }

    @AfterTest
    fun restoreStore() {
        KeylessDefaults.store = original
    }

    private val allKeyless = listOf(
        ProviderCatalog.KILO_ID,
        ProviderCatalog.LLM7_ID,
        ProviderCatalog.OVHCLOUD_ID,
        ProviderCatalog.AI_HORDE_ID,
    )

    @Test
    fun freshStateConnectsEveryKeylessSourceTextWritersFirst() {
        val plan = defaultsToApply(KeylessDefaultsState(emptySet(), emptySet()))
        assertEquals(allKeyless, plan.connect, "text writers best limits first, then the image generator")
        assertEquals(allKeyless.toSet(), plan.applied)
    }

    @Test
    fun onlyBuiltInKeylessSourcesAreEverDefaulted() {
        val ids = keylessSources().map { it.id }.toSet()
        // The keyed sources, Ollama Cloud (an account key and, from 2026-11-09, credits) among them, are not defaulted.
        assertFalse(ProviderCatalog.OLLAMA_CLOUD_ID in ids)
        assertFalse(ProviderCatalog.entry(ProviderCatalog.OLLAMA_CLOUD_ID)!!.keyOptional)
        assertTrue(ids.all { id -> ProviderCatalog.builtInEntries.any { it.id == id && it.keyOptional } })
        // A remote agent, a store model or a vendor CLI is keyless but is not built in: never defaulted.
        assertTrue(ids.none { ProviderCatalog.isA2aAgent(it) || ProviderCatalog.isCliAgent(it) })
    }

    @Test
    fun aSourceAlreadyConnectedIsNotReconnectedButIsRemembered() {
        val plan = defaultsToApply(KeylessDefaultsState(setOf(ProviderCatalog.KILO_ID), emptySet()))
        assertFalse(ProviderCatalog.KILO_ID in plan.connect)
        assertTrue(ProviderCatalog.KILO_ID in plan.applied)
    }

    @Test
    fun aSourceThatHadItsDefaultIsNeverTurnedBackOn() {
        // The keeper disconnected Kilo after its default was applied.
        val plan = defaultsToApply(KeylessDefaultsState(emptySet(), setOf(ProviderCatalog.KILO_ID)))
        assertFalse(ProviderCatalog.KILO_ID in plan.connect)
        assertEquals(listOf(ProviderCatalog.LLM7_ID, ProviderCatalog.OVHCLOUD_ID, ProviderCatalog.AI_HORDE_ID), plan.connect)
    }

    @Test
    fun appliedDefaultsDoNothingAtTheNextLaunch() {
        val plan = defaultsToApply(KeylessDefaultsState(emptySet(), allKeyless.toSet()))
        assertTrue(plan.connect.isEmpty())
    }

    @Test
    fun aSourceAddedByALaterReleaseIsDefaultedOnceAndOthersAreLeftAlone() {
        val later = ProviderCatalogEntry("newfree", "New free", "", "", "", keyOptional = true)
        val catalog = ProviderCatalog.builtInEntries + later
        val plan = defaultsToApply(KeylessDefaultsState(emptySet(), allKeyless.toSet()), catalog)
        assertEquals(listOf("newfree"), plan.connect)
    }

    @Test
    fun theOneSwitchOffKeepsEverythingOffAndChangesNothing() {
        val state = KeylessDefaultsState(emptySet(), setOf(ProviderCatalog.KILO_ID), allOff = true)
        val plan = defaultsToApply(state)
        assertTrue(plan.connect.isEmpty())
        assertEquals(state.appliedIds, plan.applied)
    }

    @Test
    fun theStoreRemembersAppliedSourcesAcrossLaunches() {
        val first = KeylessDefaults.pending(emptySet())
        assertEquals(allKeyless, first.connect)
        KeylessDefaults.commit(first)
        // Next launch: the keeper had disconnected everything meanwhile.
        assertTrue(KeylessDefaults.pending(emptySet()).connect.isEmpty())
    }

    @Test
    fun theSwitchOffDisconnectsOnlyConnectedKeylessSourcesAndSticks() {
        val disconnected = mutableListOf<String>()
        val connected = setOf(ProviderCatalog.KILO_ID, ProviderCatalog.OPENAI_ID)
        KeylessDefaults.setAll(on = false, connectedIds = connected, connect = { error("no") }, disconnect = { disconnected += it })
        assertEquals(listOf(ProviderCatalog.KILO_ID), disconnected)
        assertTrue(KeylessDefaults.allOff())
        assertTrue(KeylessDefaults.pending(emptySet()).connect.isEmpty(), "off stays off at the next launch")
    }

    @Test
    fun theSwitchOnConnectsTheMissingOnesAndAHandDisconnectThenSticks() {
        KeylessDefaults.setAllOff(true)
        val connected = mutableListOf<String>()
        KeylessDefaults.setAll(on = true, connectedIds = setOf(ProviderCatalog.LLM7_ID), connect = { connected += it }, disconnect = { error("no") })
        assertEquals(listOf(ProviderCatalog.KILO_ID, ProviderCatalog.OVHCLOUD_ID, ProviderCatalog.AI_HORDE_ID), connected)
        assertFalse(KeylessDefaults.allOff())
        assertTrue(KeylessDefaults.pending(setOf(ProviderCatalog.KILO_ID)).connect.isEmpty())
    }

    @Test
    fun catalogStatesTheRateLimitsItsTextNames() {
        assertEquals(200, ProviderCatalog.keylessRateLimit(ProviderCatalog.KILO_ID)?.requests)
        assertEquals(2, ProviderCatalog.keylessRateLimit(ProviderCatalog.OVHCLOUD_ID)?.requests)
        assertNull(ProviderCatalog.keylessRateLimit(ProviderCatalog.LLM7_ID))
        assertNotNull(ProviderCatalog.entry(ProviderCatalog.KILO_ID))
    }
}
