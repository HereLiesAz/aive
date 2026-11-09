package com.hereliesaz.geministrator.providers

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The shipped price list: only the prices read from a provider's page, each the highest standard rate that page
 * gives (so a dollar cap trips early), and the models with no verified price stay out of it.
 */
class ModelPriceTableTest {
    private val table = ModelPriceTable.Default
    private val million = 1_000_000L

    private fun cost(provider: String, model: String, input: Long, output: Long, cached: Long = 0L): Double =
        assertNotNull(table.priceFor(provider, model), "$provider/$model should be priced").costUsd(input, output, cached)

    @Test
    fun eachVerifiedModelCostsItsUpperBoundRatePerMillionTokens() {
        assertEquals(12.00, cost("anthropic", "claude-sonnet-5", million, million), 1e-9)
        assertEquals(12.00, cost("anthropic", "claude-sonnet-5-5", million, million), 1e-9)
        assertEquals(95.00, cost("openai", "gpt-6-astra", million, million), 1e-9)
        assertEquals(9.00, cost("gemini", "gemini-3.8-flash", million, million), 1e-9)
        assertEquals(16.00, cost("xai", "grok-4.6", million, million), 1e-9)
        assertEquals(1.50, cost("deepseek", "deepseek-flash", million, million), 1e-9)
    }

    @Test
    fun cachedInputIsBilledAtTheCacheHitRateOnlyForTheTokensTheProviderSaidWereCached() {
        // Half of a 1M-token prompt from the cache: half at the input rate, half at the cache-hit rate.
        assertEquals(0.5 * 2.00 + 0.5 * 0.20, cost("anthropic", "claude-sonnet-5", million, 0L, cached = million / 2), 1e-9)
        assertEquals(0.5 * 2.00 + 0.5 * 0.10, cost("anthropic", "claude-sonnet-5-5", million, 0L, cached = million / 2), 1e-9)
        // A report with no cache split is billed whole at the input rate, never less.
        assertEquals(2.00, cost("anthropic", "claude-sonnet-5", million, 0L), 1e-9)
        // More cached tokens than the prompt cannot make it cheaper than an all-cached prompt.
        assertEquals(0.20, cost("anthropic", "claude-sonnet-5", million, 0L, cached = 5 * million), 1e-9)
    }

    @Test
    fun theModelsNoVendorPageGaveAPriceForStayUnpricedSoADollarCapFailsClosed() {
        assertNull(table.priceFor("openai", "gpt-5.6"), "the page lists gpt-5.6-sol, -terra and -luna, not gpt-5.6")
        assertNull(table.priceFor("cerebras", "gpt-oss-120b"))
        assertNull(table.priceFor("huggingface", "openai/gpt-oss-120b:fastest"))
        assertNull(table.priceFor("cohere", "command-a-plus-05-2026"))
        assertNull(table.priceFor("mistral", "mistral-small-latest"))
        assertNull(table.priceFor("anthropic", "some-unlisted-model"))
        assertNull(table.priceFor("anthropic", null))
    }

    @Test
    fun aPriceIsLookedUpByTheProviderThatServesTheModelNotByTheModelName() {
        assertNull(table.priceFor("openrouter", "claude-sonnet-5"), "another provider's route to the same name is not priced here")
        assertNotNull(table.priceFor("Anthropic", " Claude-Sonnet-5 "), "spelling case and padding do not matter")
    }
}
