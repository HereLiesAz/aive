package com.hereliesaz.geministrator.providers

/**
 * What a hosted model charges, in US dollars per million tokens. [cachedInputUsdPerMillion] is the price
 * of input tokens the provider served from its prompt cache; null means cached input costs the same as
 * the rest.
 */
data class ModelPrice(
    val inputUsdPerMillion: Double,
    val outputUsdPerMillion: Double,
    val cachedInputUsdPerMillion: Double? = null,
) {
    init {
        require(inputUsdPerMillion.isFinite() && inputUsdPerMillion >= 0.0) { "Input price must be a non-negative number" }
        require(outputUsdPerMillion.isFinite() && outputUsdPerMillion >= 0.0) { "Output price must be a non-negative number" }
        require(cachedInputUsdPerMillion == null || (cachedInputUsdPerMillion.isFinite() && cachedInputUsdPerMillion >= 0.0)) {
            "Cached input price must be a non-negative number"
        }
    }

    /**
     * The cost of one generation. [inputTokens] counts the whole prompt, cached or not, as every API reports
     * it; [cachedInputTokens] of it are billed at the cached price. Cache-write surcharges are not modelled,
     * so a provider that bills them (Anthropic) is a little under-counted.
     */
    fun costUsd(inputTokens: Long, outputTokens: Long, cachedInputTokens: Long = 0L): Double {
        val cached = cachedInputTokens.coerceIn(0L, inputTokens.coerceAtLeast(0L))
        val fresh = (inputTokens - cached).coerceAtLeast(0L)
        val cachedRate = cachedInputUsdPerMillion ?: inputUsdPerMillion
        return (fresh * inputUsdPerMillion + cached * cachedRate + outputTokens.coerceAtLeast(0L) * outputUsdPerMillion) / 1_000_000.0
    }
}

/**
 * The one price list the app prices usage with, so a keeper's dollar caps (Keeper's room) can count what
 * a hosted API provider spent: those APIs report tokens, not dollars.
 *
 * An entry is a promise that the number is the provider's published list price for that model. A model that
 * is not here has an unknown price and its usage is never counted as free: it is reported as an unpriced
 * cost, and a dollar cap stops the run that spent it ([com.hereliesaz.geministrator.workflow.SpendCaps]).
 * Add a line only for a price that has been read from the provider's own price page; never estimate one.
 *
 * Prices change. The list is as right as [CHECKED] says and wrong after a provider changes a price, so
 * check it against the providers' pages before trusting a dollar cap with it (docs/Keepers-Room.md,
 * Spend caps). Token budgets (a creature's role budget) do not depend on it.
 */
class ModelPriceTable(private val prices: Map<String, ModelPrice> = emptyMap()) {
    /** The price of [model] served by [providerId], or null when it is not on the list. */
    fun priceFor(providerId: String, model: String?): ModelPrice? {
        val name = model?.trim()?.takeIf(String::isNotEmpty) ?: return null
        return prices[key(providerId, name)]
    }

    companion object {
        /** The day the entries of [Default] were last checked against the providers' price pages. */
        const val CHECKED: String = "2026-11-09"

        /**
         * Entries are `provider id/model id`, as the provider catalog and the model setting spell them.
         *
         * None of the models the app offers today (the native OpenAI, Claude, Gemini and Grok defaults, and the
         * hosted catalog's defaults) has a price that could be read back from a provider's price page when this
         * list was made, so none is listed and every one of them fails closed under a dollar cap. A keyless,
         * anonymous endpoint is free and needs no entry ([com.hereliesaz.geministrator.providers.llm.TextGenerationApi.billsPerToken]).
         */
        val Default: ModelPriceTable = ModelPriceTable(emptyMap())

        fun key(providerId: String, model: String): String = providerId.trim().lowercase() + "/" + model.trim().lowercase()
    }
}

/** What a provider already spent when an attempt failed or was cut off: tokens it was billed for and got nothing from. */
interface SpentTokens {
    val spentInputTokens: Long?
    val spentOutputTokens: Long?
    val spentCachedInputTokens: Long? get() = null

    /** What those tokens cost when the provider priced them; null when it could not (the cost is unknown, not free). */
    val spentCostUsd: Double? get() = null
}

/** The tokens a failure carries ([SpentTokens]) on it or on its cause chain, or null when it carries none. */
fun Throwable.spentTokens(): SpentTokens? {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < 8) {
        if (current is SpentTokens && (current.spentInputTokens != null || current.spentOutputTokens != null)) return current
        current = current.cause
        depth++
    }
    return null
}

/** A plan a provider drafted before any run exists, and what drafting it cost ([AgentProvider.draftPlanWithUsage]). */
data class ProviderPlanDraft(
    val text: String,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val costUsd: Double? = null,
    val cacheHitFraction: Float? = null,
    val costUnknown: Boolean = false,
)
