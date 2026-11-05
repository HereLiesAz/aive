package com.hereliesaz.geministrator.providers.llm

/**
 * A reply that stopped because the provider reached its output-token limit (`finish_reason: length`,
 * `stop_reason: max_tokens`, `MAX_TOKENS`, an `incomplete` response cut by `max_output_tokens`).
 * Partial text is never promoted to a result, so this is thrown instead. It is an
 * [IllegalStateException] so callers that already catch the old bare `error(...)` still do; the
 * message keeps the word "truncated" so `classifyError` places it as `ErrorKind.OutputTooLong`.
 */
class OutputTruncatedException(message: String) : IllegalStateException(message)

/**
 * How many output tokens a plan or swarm draft may ask for. This is the one place to tune it.
 *
 * A plan is a JSON DAG, so it is small when the planner is terse, but a verbose model on a broad
 * prompt can overrun a provider's default (many compatible services default to about 4k when no
 * limit is sent). The planner asks for [FIRST] tokens and, if the reply is cut off, once more for
 * [RETRY] tokens; both are clamped to the most the API is documented to accept
 * ([TextGenerationApi.outputTokenCap]), so a request never exceeds a model's cap. Ordinary chat and
 * creature sessions do not use this and keep their own limits.
 */
object PlanOutputBudget {
    /** First ask for a plan. */
    const val FIRST = 12_000

    /** The one larger ask after a cut-off reply. */
    const val RETRY = 24_000

    /** Most that Anthropic Messages is asked for in a non-streamed call (within every current Claude model's cap). */
    const val ANTHROPIC_CAP = 16_000

    /** Most that Gemini, OpenAI Responses and xAI Responses are asked for in a non-streamed call. */
    const val NATIVE_CAP = 32_000

    /** Most that a compatible chat service is asked for unless its catalog entry says more; accepted by nearly every model. */
    const val COMPATIBLE_CAP = 8_192

    fun first(api: TextGenerationApi): Int? = clamp(FIRST, api)

    fun retry(api: TextGenerationApi): Int? = clamp(RETRY, api)

    /** [wanted] limited to [TextGenerationApi.outputTokenCap]; null when the API takes no per-call budget. */
    fun clamp(wanted: Int, api: TextGenerationApi): Int? = api.outputTokenCap?.let { minOf(wanted, it) }
}
