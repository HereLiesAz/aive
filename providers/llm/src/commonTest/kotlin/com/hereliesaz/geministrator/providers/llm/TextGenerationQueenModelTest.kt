package com.hereliesaz.geministrator.providers.llm

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The queen's model on the linked Sources: Automatic falls down the routing order, Pinned stays pinned, none means away. */
class TextGenerationQueenModelTest {
    private class FakeApi(private val reply: () -> String) : TextGenerationApi {
        val prompts = mutableListOf<String>()
        override suspend fun generate(prompt: String): TextGenerationResult {
            prompts += prompt
            return TextGenerationResult(text = reply())
        }
    }

    private fun entry(id: String, api: TextGenerationApi) = PlanningApiEntry(id, id, api)

    @Test
    fun automaticUsesTheFirstSourceInTheRoutingOrder() = runTest {
        val first = FakeApi { "one" }
        val second = FakeApi { "two" }
        assertEquals("one", TextGenerationQueenModel(listOf(entry("kilo", first), entry("llm7", second))).answer("q"))
        assertTrue(second.prompts.isEmpty())
    }

    @Test
    fun automaticFallsDownTheOrderWhenASourceFails() = runTest {
        val down = FakeApi { error("HTTP 503") }
        val good = FakeApi { "ok" }
        assertEquals("ok", TextGenerationQueenModel(listOf(entry("kilo", down), entry("llm7", good))).answer("q"))
        assertEquals(1, down.prompts.size)
    }

    @Test
    fun pinnedAsksOnlyThePinnedSourceAndNeverMoves() = runTest {
        val pinned = FakeApi { error("HTTP 503") }
        val other = FakeApi { "ok" }
        val model = TextGenerationQueenModel(listOf(entry("kilo", other), entry("llm7", pinned)), pinnedProviderId = "llm7")
        assertFailsWith<IllegalStateException> { model.answer("q") }
        assertTrue(other.prompts.isEmpty())
    }

    @Test
    fun aPinToASourceThatIsNotLinkedMeansAway() = runTest {
        val model = TextGenerationQueenModel(listOf(entry("kilo", FakeApi { "ok" })), pinnedProviderId = "gone")
        assertFailsWith<IllegalStateException> { model.answer("q") }
    }

    @Test
    fun noLinkedSourceMeansNoModel() {
        assertNull(configuredQueenModel(emptyMap()))
        assertNull(configuredQueenModel(emptyMap(), pinnedProviderId = "kilo"))
    }
}
