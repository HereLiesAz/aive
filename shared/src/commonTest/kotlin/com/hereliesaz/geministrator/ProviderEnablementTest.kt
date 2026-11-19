package com.hereliesaz.geministrator

import com.russhwolf.settings.MapSettings
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A Source switched off keeps its key and is used by nothing; switching it on needs no key. */
class ProviderEnablementTest {
    private val original = ProviderEnablement.store

    @AfterTest
    fun restore() {
        ProviderEnablement.store = original
    }

    private fun fresh() = DurableUiStateStore(MapSettings()).also { ProviderEnablement.store = it }

    @Test
    fun everySourceIsOnUntilItIsSwitchedOff() {
        fresh()
        assertTrue(ProviderEnablement.isEnabled("kilo"))
        ProviderEnablement.set("kilo", false)
        assertFalse(ProviderEnablement.isEnabled("kilo"))
        assertTrue(ProviderEnablement.isEnabled("openai"))
    }

    @Test
    fun switchedOffSourcesAreLeftOutOfWhatIsUsedButTheLinkIsKept() {
        fresh()
        val linked = mapOf("kilo" to "anonymous", "openai" to "sk-secret")
        ProviderEnablement.set("openai", false)
        assertEquals(mapOf("kilo" to "anonymous"), ProviderEnablement.usable(linked))
        // The map the keeper's stored links come from is untouched: switching on again needs no key.
        assertEquals(2, linked.size)
        ProviderEnablement.set("openai", true)
        assertEquals(linked, ProviderEnablement.usable(linked))
    }

    @Test
    fun theSwitchesSurviveARestart() {
        val store = fresh()
        ProviderEnablement.set("kilo", false)
        ProviderEnablement.set("llm7", false)
        // A new session over the same store reads them back.
        ProviderEnablement.store = store
        assertEquals(setOf("kilo", "llm7"), ProviderEnablement.disabled)
    }

    @Test
    fun switchingOnForgetsTheFlag() {
        fresh()
        ProviderEnablement.set("kilo", false)
        ProviderEnablement.set("kilo", true)
        assertEquals(emptySet(), ProviderEnablement.disabled)
    }
}
