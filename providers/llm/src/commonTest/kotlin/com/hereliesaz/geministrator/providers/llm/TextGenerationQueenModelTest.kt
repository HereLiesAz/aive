package com.hereliesaz.geministrator.providers.llm

import com.hereliesaz.geministrator.ProviderCatalog
import com.hereliesaz.geministrator.queen.QueenCallContext
import com.hereliesaz.geministrator.workflow.KeylessPacer
import com.hereliesaz.geministrator.workflow.KeylessPacing
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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

    private val oneAnHour = ProviderCatalog.KeylessRateLimit(requests = 1, windowMillis = 60 * 60_000L)
    private val twoAMinute = ProviderCatalog.KeylessRateLimit(requests = 2, windowMillis = 60_000L)

    @Test
    fun aCallWaitsItsTurnOnATightlyLimitedSourceInsteadOfFailing() = runTest {
        val pacer = KeylessPacer(now = { testScheduler.currentTime }, limitOf = { twoAMinute })
        val tight = FakeApi { "ok" }
        val model = TextGenerationQueenModel(listOf(entry("ovh", tight)))
        withContext(QueenCallContext(KeylessPacing(pacer, Mutex()))) {
            repeat(6) { assertEquals("ok", model.answer("q")) }
        }
        assertEquals(6, tight.prompts.size)
        // Six calls at two a minute: the sixth starts in the third window.
        assertEquals(120_000L, testScheduler.currentTime)
    }

    @Test
    fun automaticMovesOnWhenASourcesTurnIsTooFarOff() = runTest {
        val pacer = KeylessPacer(now = { testScheduler.currentTime }, limitOf = { if (it == "slow") oneAnHour else null })
        val slow = FakeApi { "slow" }
        val next = FakeApi { "next" }
        val context = QueenCallContext(KeylessPacing(pacer, Mutex()))
        val model = TextGenerationQueenModel(listOf(entry("slow", slow), entry("next", next)))
        withContext(context) {
            assertEquals("slow", model.answer("q"))
            assertEquals("next", model.answer("q"))
        }
        assertEquals(1, slow.prompts.size)
        assertEquals(1, next.prompts.size)
    }

    @Test
    fun pinnedWaitsAndNeverMovesWhenItsTurnIsTooFarOff() = runTest {
        val pacer = KeylessPacer(now = { testScheduler.currentTime }, limitOf = { if (it == "slow") oneAnHour else null })
        val slow = FakeApi { "slow" }
        val other = FakeApi { "other" }
        val context = QueenCallContext(KeylessPacing(pacer, Mutex()))
        val model = TextGenerationQueenModel(listOf(entry("other", other), entry("slow", slow)), pinnedProviderId = "slow")
        withContext(context) {
            assertEquals("slow", model.answer("q"))
            assertFailsWith<IllegalStateException> { model.answer("q") }
        }
        assertTrue(other.prompts.isEmpty())
    }

    @Test
    fun noLinkedSourceMeansNoModel() {
        assertNull(configuredQueenModel(emptyMap()))
        assertNull(configuredQueenModel(emptyMap(), pinnedProviderId = "kilo"))
    }
}
