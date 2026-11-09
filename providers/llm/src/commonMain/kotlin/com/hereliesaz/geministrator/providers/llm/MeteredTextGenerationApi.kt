package com.hereliesaz.geministrator.providers.llm

import com.hereliesaz.geministrator.providers.ModelPriceTable
import com.hereliesaz.geministrator.providers.spentTokens
import com.hereliesaz.geministrator.workflow.AmbientSpendLedger
import kotlinx.coroutines.CancellationException

/**
 * The cost of [inputTokens] and [outputTokens] spent through [api] as [providerId]: zero for an anonymous
 * keyless endpoint, the list price for a priced model, and otherwise no cost with `true` (unknown, never
 * free). Null when there are no tokens to price.
 */
internal fun pricedUsage(
    api: TextGenerationApi,
    providerId: String,
    pricing: ModelPriceTable,
    inputTokens: Long?,
    outputTokens: Long?,
    cachedInputTokens: Long?,
): Pair<Double?, Boolean>? {
    if (inputTokens == null && outputTokens == null) return null
    if (!api.billsPerToken) return 0.0 to false
    val price = pricing.priceFor(providerId, api.modelId) ?: return null to true
    if (inputTokens == null || outputTokens == null) return null to true
    return price.costUsd(inputTokens, outputTokens, cachedInputTokens ?: 0L) to false
}

/** Where a billed generation outside any run reports what it cost: its cost, or that the cost is unknown. */
fun interface SpendSink {
    suspend fun record(costUsd: Double?, costUnknown: Boolean)
}

/**
 * [delegate] with every generation it makes reported to [sink]. The planner that drafts a hive, the plan
 * reviewer and the memory clerks call the model outside any workflow run, so no creature's token budget
 * sees them; this is how the keeper's hourly spend cap does ([AmbientSpendLedger]). A generation that
 * failed after it was billed (cut off at the limit) is reported too. [providerId] is the catalog id the
 * model's price is looked up under.
 */
class MeteredTextGenerationApi(
    private val delegate: TextGenerationApi,
    private val providerId: String,
    private val pricing: ModelPriceTable = ModelPriceTable.Default,
    private val sink: SpendSink = SpendSink { cost, unknown -> AmbientSpendLedger.Shared.record(cost, unknown) },
) : TextGenerationApi by delegate {
    override suspend fun generate(prompt: String): TextGenerationResult = metered { delegate.generate(prompt) }

    override suspend fun generateWithBudget(prompt: String, maxOutputTokens: Int): TextGenerationResult =
        metered { delegate.generateWithBudget(prompt, maxOutputTokens) }

    override suspend fun generate(prompt: CacheablePrompt): TextGenerationResult = metered { delegate.generate(prompt) }

    override suspend fun stream(prompt: CacheablePrompt, onChunk: suspend (TextGenerationChunk) -> Unit): TextGenerationResult =
        metered { delegate.stream(prompt, onChunk) }

    override suspend fun stream(prompt: String, onChunk: suspend (TextGenerationChunk) -> Unit): TextGenerationResult =
        metered { delegate.stream(prompt, onChunk) }

    private suspend fun metered(block: suspend () -> TextGenerationResult): TextGenerationResult {
        val result = try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failure.spentTokens()?.let { spent ->
                report(spent.spentInputTokens, spent.spentOutputTokens, spent.spentCachedInputTokens)
            }
            throw failure
        }
        report(result.inputTokens, result.outputTokens, result.cachedInputTokens)
        return result
    }

    private suspend fun report(inputTokens: Long?, outputTokens: Long?, cachedInputTokens: Long?) {
        val (cost, unknown) = pricedUsage(delegate, providerId, pricing, inputTokens, outputTokens, cachedInputTokens) ?: return
        // Reporting is telemetry for the cap: a sink that fails never fails the generation.
        try {
            sink.record(cost, unknown)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
        }
    }
}
