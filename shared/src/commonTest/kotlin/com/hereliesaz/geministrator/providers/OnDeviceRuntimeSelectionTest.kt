package com.hereliesaz.geministrator.providers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class OnDeviceRuntimeSelectionTest {
    private class Fake(var ready: Boolean, val result: () -> OnDeviceTextGeneration) : OnDeviceTextGenerator {
        var calls = 0

        override fun isReady() = ready

        override suspend fun generate(system: String, user: String, maxNewTokens: Int): OnDeviceTextGeneration {
            calls++
            return result()
        }
    }

    private val cool = OnDeviceResourceProbe { OnDeviceResourceSnapshot(availableMemoryBytes = Long.MAX_VALUE) }
    private val hot = OnDeviceResourceProbe { OnDeviceResourceSnapshot(availableMemoryBytes = Long.MAX_VALUE, thermalHot = true) }

    private fun select(lite: Fake, ort: Fake, probe: OnDeviceResourceProbe = cool) = SelectingOnDeviceTextGenerator(
        listOf(
            SelectingOnDeviceTextGenerator.Candidate(OnDeviceTextRuntime.LiteRtLm, lite),
            SelectingOnDeviceTextGenerator.Candidate(OnDeviceTextRuntime.OnnxRuntime, ort),
        ),
        probe,
    )

    private fun ok(text: String) = { OnDeviceTextGeneration(text) }

    @Test
    fun liteRtLmWinsWhenBothAreInstalled() = runTest {
        val s = select(Fake(true, ok("lite")), Fake(true, ok("ort")))
        assertEquals(OnDeviceTextRuntime.LiteRtLm, s.activeRuntime())
        assertEquals("lite", s.generate("s", "u", 8).text)
    }

    @Test
    fun onnxIsUsedWhenOnlyItIsInstalled() = runTest {
        val s = select(Fake(false, ok("lite")), Fake(true, ok("ort")))
        assertEquals(OnDeviceTextRuntime.OnnxRuntime, s.activeRuntime())
        assertEquals("ort", s.generate("s", "u", 8).text)
    }

    @Test
    fun noRuntimeMeansNotReady() {
        val s = select(Fake(false, ok("")), Fake(false, ok("")))
        assertFalse(s.isReady())
        assertNull(s.activeRuntime())
    }

    @Test
    fun anUnavailableRuntimeHandsOverToTheNext() = runTest {
        val lite = Fake(true) { throw OnDeviceRuntimeUnavailableException("no native") }
        val ort = Fake(true, ok("ort"))
        assertEquals("ort", select(lite, ort).generate("s", "u", 8).text)
        assertEquals(1, lite.calls)
    }

    @Test
    fun aRefusalDoesNotFallBack() = runTest {
        val lite = Fake(true) { throw OnDeviceGenerationDeclined("memory") }
        val ort = Fake(true, ok("ort"))
        assertFailsWith<OnDeviceGenerationDeclined> { select(lite, ort).generate("s", "u", 8) }
        assertEquals(0, ort.calls)
    }

    @Test
    fun aHotDeviceDeclinesEveryRuntime() = runTest {
        val lite = Fake(true, ok("lite"))
        val ort = Fake(true, ok("ort"))
        val s = select(lite, ort, hot)
        assertFalse(s.isReady())
        assertFailsWith<OnDeviceGenerationDeclined> { s.generate("s", "u", 8) }
        assertEquals(0, lite.calls + ort.calls)
    }

    @Test
    fun chooseFollowsPreferenceOrder() {
        val order = listOf(OnDeviceTextRuntime.LiteRtLm, OnDeviceTextRuntime.OnnxRuntime)
        assertEquals(OnDeviceTextRuntime.LiteRtLm, SelectingOnDeviceTextGenerator.choose(order, OnDeviceTextRuntime.entries.toSet()))
        assertEquals(OnDeviceTextRuntime.OnnxRuntime, SelectingOnDeviceTextGenerator.choose(order, setOf(OnDeviceTextRuntime.OnnxRuntime)))
        assertNull(SelectingOnDeviceTextGenerator.choose(order, emptySet()))
    }

    @Test
    fun theProviderRoutesByTheActiveRuntimesLimit() {
        val lite = select(Fake(true, ok("")), Fake(true, ok("")))
        assertEquals(LITERT_LM_MAX_INPUT_TOKENS, assertNotNull(onDeviceTextProviderOrNull(true, lite)).maxInputTokens)
        val ort = select(Fake(false, ok("")), Fake(true, ok("")))
        assertEquals(OnDeviceTextAgentProvider.DEFAULT_MAX_INPUT_TOKENS, assertNotNull(onDeviceTextProviderOrNull(true, ort)).maxInputTokens)
        assertTrue(LITERT_LM_MAX_INPUT_TOKENS < OnDeviceTextAgentProvider.DEFAULT_MAX_INPUT_TOKENS)
    }

    @Test
    fun gateDecisions() {
        val gate = OnDeviceResourceGate
        val need = gate.LITERT_LM_MIN_AVAILABLE_MEMORY_BYTES
        assertNull(gate.memoryRefusal(OnDeviceResourceSnapshot(need), need))
        assertNotNull(gate.memoryRefusal(OnDeviceResourceSnapshot(need - 1), need))
        assertNotNull(gate.memoryRefusal(OnDeviceResourceSnapshot(null), need))
        assertNotNull(gate.memoryRefusal(OnDeviceResourceSnapshot(need * 2, memoryLow = true), need))
        assertNull(gate.thermalHold(OnDeviceResourceSnapshot(need)))
        assertNotNull(gate.thermalHold(OnDeviceResourceSnapshot(need, thermalHot = true)))
    }
}
