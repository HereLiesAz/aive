package com.hereliesaz.geministrator.providers.llm

import com.hereliesaz.geministrator.net.boundedBodyText
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.hereliesaz.geministrator.net.HttpStatusFailure
import com.hereliesaz.geministrator.net.parseRetryAfterMillis
import com.hereliesaz.geministrator.domain.PromptReusePolicy
import com.hereliesaz.geministrator.providers.PromptCacheCapabilities
import com.hereliesaz.geministrator.providers.PromptCacheMode
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

fun interface LlmApiKeyProvider {
    suspend fun getApiKey(): String
}

data class TextGenerationResult(
    val text: String,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    /** The model's visible reasoning, when the API returned any. */
    val thinking: String? = null,
    /**
     * Of [inputTokens], how many the provider served from its prompt cache; null when the response
     * did not say. [inputTokens] always counts the whole prompt, cached or not.
     */
    val cachedInputTokens: Long? = null,
) {
    /** [cachedInputTokens] over [inputTokens], normalized 0f..1f; null when either is unknown. */
    val cacheHitFraction: Float?
        get() {
            val cached = cachedInputTokens ?: return null
            val total = inputTokens?.takeIf { it > 0 } ?: return null
            return (cached.toDouble() / total).coerceIn(0.0, 1.0).toFloat()
        }
}

/**
 * A prompt in two ordered regions: [stablePrefix], expected to repeat across calls (role
 * instructions, standing context), then [dynamicSuffix], the volatile rest. [text] is the whole
 * prompt with the prefix first, which is all an API without cache controls needs; one with explicit
 * breakpoints marks the end of the prefix when [reusePolicy] asks for reuse.
 */
data class CacheablePrompt(
    val stablePrefix: String = "",
    val dynamicSuffix: String,
    val reusePolicy: PromptReusePolicy = PromptReusePolicy.ProviderDefault,
) {
    val text: String get() = (stablePrefix + dynamicSuffix).trim()
}

/** Automatic provider-side prefix caching, reported when the response carries a cached-token count. */
internal val IMPLICIT_PREFIX_CACHING = PromptCacheCapabilities(
    modes = setOf(PromptCacheMode.ImplicitPrefix),
    reportsCacheUsage = true,
)

interface TextGenerationApi {
    /**
     * The model this API sends requests to; null when it does not say (a test double). The price of its
     * usage is looked up by it ([com.hereliesaz.geministrator.providers.ModelPriceTable]).
     */
    val modelId: String? get() = null

    /**
     * False for an endpoint used without a key (the free tiers): it cannot bill, so its usage costs nothing.
     * True for every other API, whose usage is billed per token and must be priced to count against a dollar cap.
     */
    val billsPerToken: Boolean get() = true

    /** The prompt-cache mechanisms this API uses; none unless it says otherwise. */
    val promptCaching: PromptCacheCapabilities get() = PromptCacheCapabilities()

    /**
     * The most output tokens [generateWithBudget] may be given, as documented for this API's model;
     * null when it takes no per-call budget (it then ignores one). See [PlanOutputBudget].
     */
    val outputTokenCap: Int? get() = null

    suspend fun generate(prompt: String): TextGenerationResult

    /**
     * Generates with an output limit of [maxOutputTokens] (callers pass [PlanOutputBudget] values,
     * already clamped to [outputTokenCap]). An API without a budget option makes a plain [generate].
     */
    suspend fun generateWithBudget(prompt: String, maxOutputTokens: Int): TextGenerationResult = generate(prompt)

    /** Generates from a prefix-structured prompt; APIs without cache controls send [CacheablePrompt.text]. */
    suspend fun generate(prompt: CacheablePrompt): TextGenerationResult = generate(prompt.text)

    /** Streams a prefix-structured prompt; APIs without cache controls send [CacheablePrompt.text]. */
    suspend fun stream(prompt: CacheablePrompt, onChunk: suspend (TextGenerationChunk) -> Unit): TextGenerationResult =
        stream(prompt.text, onChunk)

    /**
     * Streams the generation: reasoning and answer text reach [onChunk] as they arrive, and the
     * whole result is returned. APIs that cannot stream make one [generate] call and report its
     * reasoning, then its answer. An exception thrown by [onChunk] abandons the stream.
     */
    suspend fun stream(prompt: String, onChunk: suspend (TextGenerationChunk) -> Unit): TextGenerationResult {
        val result = generate(prompt)
        result.thinking?.takeIf(String::isNotBlank)?.let { onChunk(TextGenerationChunk.Thinking(it)) }
        onChunk(TextGenerationChunk.Text(result.text))
        return result
    }
}

/**
 * Runs [attempt] with reasoning requested; if the API refuses the reasoning option itself (an
 * unsupported model, an unverified organization) before any output, retries once without it and
 * remembers, through [disable], not to ask again.
 */
internal suspend fun <T> withReasoningFallback(
    requestReasoning: Boolean,
    disable: () -> Unit,
    attempt: suspend (reasoning: Boolean) -> T,
): T {
    if (!requestReasoning) return attempt(false)
    return try {
        attempt(true)
    } catch (rejected: StreamRejected) {
        if (!rejected.rejectsReasoning()) throw rejected
        disable()
        attempt(false)
    }
}

/**
 * The one HTTP client the hosted text APIs share when they are not given their own. Each API used to build a client of its
 * own, and the memory clerks build an API for every call, so clients (and their connection pools and threads) piled up
 * and were never closed. The client lives as long as the process; each API derives its own view of it with [config].
 */
internal fun sharedLlmHttpClient(): HttpClient = sharedClient

private val sharedClient: HttpClient by lazy {
    HttpClient {
        expectSuccess = false
        followRedirects = false
        install(HttpTimeout) {
            requestTimeoutMillis = 90_000L
            connectTimeoutMillis = 15_000L
        }
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                },
            )
        }
    }
}

class OpenAiResponsesApi(
    private val apiKeyProvider: LlmApiKeyProvider,
    private val model: String = "gpt-5.6",
    private val baseUrl: String = "https://api.openai.com/v1",
    client: HttpClient = sharedLlmHttpClient(),
) : TextGenerationApi {
    private val client = client.config { followRedirects = false }

    override val modelId: String get() = model

    /** OpenAI caches long prompt prefixes automatically and reports `input_tokens_details.cached_tokens`. */
    override val promptCaching: PromptCacheCapabilities get() = IMPLICIT_PREFIX_CACHING

    override val outputTokenCap: Int get() = PlanOutputBudget.NATIVE_CAP

    override suspend fun generate(prompt: String): TextGenerationResult = generate(prompt, maxOutputTokens = null)

    override suspend fun generateWithBudget(prompt: String, maxOutputTokens: Int): TextGenerationResult =
        generate(prompt, maxOutputTokens = minOf(maxOutputTokens, outputTokenCap))

    private suspend fun generate(prompt: String, maxOutputTokens: Int?): TextGenerationResult {
        val response = client.post("$baseUrl/responses") {
            bearer(apiKeyProvider)
            contentType(ContentType.Application.Json)
            setBody(OpenAiResponseRequest(model = model, input = prompt, maxOutputTokens = maxOutputTokens))
        }
        response.requireSuccess("generate OpenAI response")
        val payload = response.body<OpenAiResponse>()
        // A response cut off by max_output_tokens (or a content filter) is `incomplete`: partial
        // text must never be promoted to a completed result.
        val spent = payload.usage
        if (payload.incompleteDetails?.reason == "max_output_tokens") {
            throw OutputTruncatedException(
                "OpenAI response was truncated at the output-token limit (max_output_tokens); output is incomplete",
                spent?.inputTokens, spent?.outputTokens, spent?.inputTokensDetails?.cachedTokens,
            )
        }
        if (payload.status == "incomplete" || payload.status == "failed") {
            throw SpentGenerationFailure(
                "OpenAI response ${payload.status}: " +
                    (payload.error?.message ?: payload.incompleteDetails?.reason ?: "no detail") +
                    "; output is incomplete",
                null, spent?.inputTokens, spent?.outputTokens, spent?.inputTokensDetails?.cachedTokens,
            )
        }
        val text = payload.output
            .asSequence()
            .flatMap { it.content.asSequence() }
            .firstOrNull { it.type == "output_text" && !it.text.isNullOrBlank() }
            ?.text
            ?: throw SpentGenerationFailure(
                "OpenAI response did not contain output text", null,
                spent?.inputTokens, spent?.outputTokens, spent?.inputTokensDetails?.cachedTokens,
            )
        return TextGenerationResult(
            text = text,
            inputTokens = payload.usage?.inputTokens,
            outputTokens = payload.usage?.outputTokens,
            cachedInputTokens = payload.usage?.inputTokensDetails?.cachedTokens,
        )
    }

    /** Asks for a reasoning summary until the API refuses it once for this model or account. */
    private var requestReasoning = true

    override suspend fun stream(prompt: String, onChunk: suspend (TextGenerationChunk) -> Unit): TextGenerationResult =
        withReasoningFallback(requestReasoning, { requestReasoning = false }) { reasoning ->
            val onChunk = onChunk.cappedOutput("OpenAI")
            val text = StringBuilder()
            val thinking = StringBuilder()
            var usage: OpenAiUsage? = null
            var completed = false
            client.postServerSentEvents(
                url = "$baseUrl/responses",
                operation = "stream OpenAI response",
                configure = {
                    bearer(apiKeyProvider)
                    contentType(ContentType.Application.Json)
                    setBody(
                        OpenAiResponseRequest(
                            model = model,
                            input = prompt,
                            stream = true,
                            reasoning = if (reasoning) OpenAiReasoning(summary = "auto") else null,
                        ),
                    )
                },
            ) { _, data ->
                val event = streamJson.decodeFromString<OpenAiStreamEvent>(data)
                when (event.type) {
                    "response.reasoning_summary_text.delta" -> event.delta?.takeIf(String::isNotEmpty)?.let {
                        thinking.append(it)
                        onChunk(TextGenerationChunk.Thinking(it))
                    }
                    "response.reasoning_summary_part.done" -> thinking.takeIf { it.isNotEmpty() }?.let {
                        thinking.append("\n\n")
                        onChunk(TextGenerationChunk.Thinking("\n\n"))
                    }
                    "response.output_text.delta" -> event.delta?.takeIf(String::isNotEmpty)?.let {
                        text.append(it)
                        onChunk(TextGenerationChunk.Text(it))
                    }
                    "response.completed" -> {
                        usage = event.response?.usage
                        completed = true
                    }
                    "response.incomplete" -> {
                        val spent = event.response?.usage
                        if (event.response?.incompleteDetails?.reason == "max_output_tokens") {
                            throw OutputTruncatedException(
                                "OpenAI response was truncated at the output-token limit (max_output_tokens); output is incomplete",
                                spent?.inputTokens, spent?.outputTokens, spent?.inputTokensDetails?.cachedTokens,
                            )
                        } else {
                            throw SpentGenerationFailure(
                                "OpenAI response incomplete: " + (event.response?.error?.message ?: event.response?.incompleteDetails?.reason ?: "no detail"),
                                null, spent?.inputTokens, spent?.outputTokens, spent?.inputTokensDetails?.cachedTokens,
                            )
                        }
                    }
                    "response.failed" -> error(
                        "OpenAI response ${event.type.substringAfter('.')}: " +
                            (event.response?.error?.message ?: event.response?.incompleteDetails?.reason ?: "no detail"),
                    )
                    "error" -> error("OpenAI stream error: ${event.message ?: data.take(300)}")
                }
            }
            requireStreamTerminated(completed, "OpenAI")
            if (text.isBlank()) {
                throw SpentGenerationFailure(
                    "OpenAI response did not contain output text", null,
                    usage?.inputTokens, usage?.outputTokens, usage?.inputTokensDetails?.cachedTokens,
                )
            }
            TextGenerationResult(
                text = text.toString(),
                inputTokens = usage?.inputTokens,
                outputTokens = usage?.outputTokens,
                thinking = thinking.toString().trim().ifEmpty { null },
                cachedInputTokens = usage?.inputTokensDetails?.cachedTokens,
            )
        }
}

class XaiResponsesApi(
    private val apiKeyProvider: LlmApiKeyProvider,
    private val model: String = "grok-4.6",
    private val baseUrl: String = "https://api.x.ai/v1",
    client: HttpClient = sharedLlmHttpClient(),
) : TextGenerationApi {
    private val delegate = OpenAiResponsesApi(
        apiKeyProvider = apiKeyProvider,
        model = model,
        baseUrl = baseUrl,
        client = client,
    )

    override val modelId: String get() = model

    override val promptCaching: PromptCacheCapabilities get() = delegate.promptCaching

    override val outputTokenCap: Int? get() = delegate.outputTokenCap

    override suspend fun generate(prompt: String): TextGenerationResult = delegate.generate(prompt)

    override suspend fun generateWithBudget(prompt: String, maxOutputTokens: Int): TextGenerationResult =
        delegate.generateWithBudget(prompt, maxOutputTokens)

    override suspend fun stream(prompt: String, onChunk: suspend (TextGenerationChunk) -> Unit): TextGenerationResult =
        delegate.stream(prompt, onChunk)
}

class AnthropicMessagesApi(
    private val apiKeyProvider: LlmApiKeyProvider,
    private val model: String = "claude-sonnet-5",
    private val baseUrl: String = "https://api.anthropic.com/v1",
    client: HttpClient = sharedLlmHttpClient(),
) : ToolCallingTextGenerationApi {
    private val client = client.config { followRedirects = false }

    override suspend fun converse(
        prompt: String,
        tools: List<com.hereliesaz.geministrator.mcp.McpToolOffer>,
        rounds: List<ToolRound>,
        allowTools: Boolean,
    ): ToolTurn {
        val key = apiKeyProvider.requireKey("Anthropic")
        val body = AnthropicToolWire.request(model, 16_000, prompt, tools, rounds, allowTools)
        return AnthropicToolWire.turn(
            client.postJson("$baseUrl/messages", body, "generate Claude response") {
                header("x-api-key", key)
                header("anthropic-version", "2023-06-01")
            },
        )
    }

    /** A `cache_control` breakpoint ends the stable prefix; usage reports cache reads and writes. */
    override val promptCaching: PromptCacheCapabilities = PromptCacheCapabilities(
        modes = setOf(PromptCacheMode.ExplicitBreakpoints),
        reportsCacheUsage = true,
    )

    override val outputTokenCap: Int get() = PlanOutputBudget.ANTHROPIC_CAP

    override suspend fun generate(prompt: String): TextGenerationResult = generate(CacheablePrompt(dynamicSuffix = prompt))

    override suspend fun generateWithBudget(prompt: String, maxOutputTokens: Int): TextGenerationResult =
        generate(CacheablePrompt(dynamicSuffix = prompt), minOf(maxOutputTokens, outputTokenCap))

    override suspend fun generate(prompt: CacheablePrompt): TextGenerationResult = generate(prompt, DEFAULT_MAX_TOKENS)

    private suspend fun generate(prompt: CacheablePrompt, maxTokens: Int): TextGenerationResult {
        val response = client.post("$baseUrl/messages") {
            val key = apiKeyProvider.requireKey("Anthropic")
            header("x-api-key", key)
            header("anthropic-version", "2023-06-01")
            contentType(ContentType.Application.Json)
            setBody(messageRequest(prompt, maxTokens = maxTokens))
        }
        response.requireSuccess("generate Claude response")
        val payload = response.body<AnthropicMessageResponse>()
        if (payload.stopReason == "max_tokens") {
            throw OutputTruncatedException(
                "Claude response was truncated at the token limit; output is incomplete",
                payload.usage?.promptTokens(), payload.usage?.outputTokens, payload.usage?.cacheReadInputTokens,
            )
        }
        val text = payload.content
            .firstOrNull { it.type == "text" && !it.text.isNullOrBlank() }
            ?.text
            ?: throw SpentGenerationFailure(
                "Claude response did not contain text", null,
                payload.usage?.promptTokens(), payload.usage?.outputTokens, payload.usage?.cacheReadInputTokens,
            )
        return TextGenerationResult(
            text = text,
            inputTokens = payload.usage?.promptTokens(),
            outputTokens = payload.usage?.outputTokens,
            cachedInputTokens = payload.usage?.cacheReadInputTokens,
        )
    }

    /**
     * The stable prefix goes in `system`, ahead of the task in the user turn, so it is the cached
     * prefix. Under [PromptReusePolicy.PreferCache] the prefix block carries an ephemeral
     * `cache_control` breakpoint; otherwise the request has the same shape without one, and
     * Anthropic caches nothing. A prompt without both regions is sent as one user message.
     */
    private fun messageRequest(
        prompt: CacheablePrompt,
        maxTokens: Int,
        stream: Boolean? = null,
        thinking: AnthropicThinking? = null,
    ): AnthropicMessageRequest {
        val prefix = prompt.stablePrefix.trim()
        val rest = prompt.dynamicSuffix.trim()
        if (prefix.isEmpty() || rest.isEmpty()) {
            return AnthropicMessageRequest(
                model = model,
                maxTokens = maxTokens,
                messages = listOf(AnthropicInputMessage(role = "user", content = prompt.text)),
                stream = stream,
                thinking = thinking,
            )
        }
        val cacheControl = when (prompt.reusePolicy) {
            PromptReusePolicy.PreferCache -> AnthropicCacheControl(type = "ephemeral")
            PromptReusePolicy.ProviderDefault, PromptReusePolicy.DisableCache -> null
        }
        return AnthropicMessageRequest(
            model = model,
            maxTokens = maxTokens,
            system = listOf(AnthropicTextBlock(type = "text", text = prefix, cacheControl = cacheControl)),
            messages = listOf(AnthropicInputMessage(role = "user", content = rest)),
            stream = stream,
            thinking = thinking,
        )
    }

    /**
     * Thinking modes tried in order until the model accepts one: adaptive with summarized display
     * (current models), a fixed budget (Haiku 4.5 and older), then none.
     */
    private var thinkingMode = 0
    private val thinkingModeLock = Mutex()

    /**
     * Moves past [rejected], the mode a refusal came from, and only if nobody has already: two streams refused at once for
     * the same mode must advance one step between them, not two (which skipped the budget mode straight to none).
     */
    private suspend fun advancePast(rejected: Int) = thinkingModeLock.withLock {
        if (thinkingMode == rejected) thinkingMode = rejected + 1
    }

    override suspend fun stream(prompt: String, onChunk: suspend (TextGenerationChunk) -> Unit): TextGenerationResult =
        stream(CacheablePrompt(dynamicSuffix = prompt), onChunk)

    override suspend fun stream(prompt: CacheablePrompt, onChunk: suspend (TextGenerationChunk) -> Unit): TextGenerationResult {
        while (true) {
            val index = thinkingModeLock.withLock { thinkingMode }
            val mode = THINKING_MODES.getOrNull(index)
            try {
                return streamOnce(prompt, mode, onChunk)
            } catch (rejected: StreamRejected) {
                if (mode == null || !rejected.rejectsReasoning()) throw rejected
                advancePast(index)
            }
        }
    }

    private suspend fun streamOnce(
        prompt: CacheablePrompt,
        thinking: AnthropicThinking?,
        onChunk: suspend (TextGenerationChunk) -> Unit,
    ): TextGenerationResult {
        val onChunk = onChunk.cappedOutput("Claude")
        val text = StringBuilder()
        val reasoning = StringBuilder()
        var inputUsage: AnthropicUsage? = null
        var outputTokens: Long? = null
        var stopReason: String? = null
        var stopped = false
        client.postServerSentEvents(
            url = "$baseUrl/messages",
            operation = "stream Claude response",
            configure = {
                header("x-api-key", apiKeyProvider.requireKey("Anthropic"))
                header("anthropic-version", "2023-06-01")
                contentType(ContentType.Application.Json)
                setBody(messageRequest(prompt, maxTokens = STREAM_MAX_TOKENS, stream = true, thinking = thinking))
            },
        ) { _, data ->
            val event = streamJson.decodeFromString<AnthropicStreamEvent>(data)
            when (event.type) {
                "message_start" -> inputUsage = event.message?.usage
                "content_block_start" -> if (event.contentBlock?.type == "thinking" && reasoning.isNotEmpty()) {
                    reasoning.append("\n\n")
                    onChunk(TextGenerationChunk.Thinking("\n\n"))
                }
                "content_block_delta" -> when (event.delta?.type) {
                    "thinking_delta" -> event.delta.thinking?.takeIf(String::isNotEmpty)?.let {
                        reasoning.append(it)
                        onChunk(TextGenerationChunk.Thinking(it))
                    }
                    "text_delta" -> event.delta.text?.takeIf(String::isNotEmpty)?.let {
                        text.append(it)
                        onChunk(TextGenerationChunk.Text(it))
                    }
                }
                "message_delta" -> {
                    event.delta?.stopReason?.let { stopReason = it }
                    event.usage?.outputTokens?.let { outputTokens = it }
                }
                "message_stop" -> stopped = true
                "error" -> error("Claude stream error: ${event.error?.message ?: data.take(300)}")
            }
        }
        requireStreamTerminated(stopped, "Claude")
        when (stopReason) {
            "max_tokens" -> throw OutputTruncatedException(
                "Claude response was truncated at the token limit; output is incomplete",
                inputUsage?.promptTokens(), outputTokens, inputUsage?.cacheReadInputTokens,
            )
            "refusal" -> throw SpentGenerationFailure(
                "Claude declined this request", null,
                inputUsage?.promptTokens(), outputTokens, inputUsage?.cacheReadInputTokens,
            )
        }
        if (text.isBlank()) {
            throw SpentGenerationFailure(
                "Claude response did not contain text", null,
                inputUsage?.promptTokens(), outputTokens, inputUsage?.cacheReadInputTokens,
            )
        }
        return TextGenerationResult(
            text = text.toString(),
            inputTokens = inputUsage?.promptTokens(),
            outputTokens = outputTokens,
            thinking = reasoning.toString().trim().ifEmpty { null },
            cachedInputTokens = inputUsage?.cacheReadInputTokens,
        )
    }

    private companion object {
        const val STREAM_MAX_TOKENS = 32_000
        const val DEFAULT_MAX_TOKENS = 8_192
        val THINKING_MODES = listOf(
            AnthropicThinking(type = "adaptive", display = "summarized"),
            AnthropicThinking(type = "enabled", budgetTokens = 8_000),
        )
    }
}

class GeminiGenerateContentApi(
    private val apiKeyProvider: LlmApiKeyProvider,
    private val model: String = "gemini-3.8-flash",
    private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
    client: HttpClient = sharedLlmHttpClient(),
) : ToolCallingTextGenerationApi {
    private val client = client.config { followRedirects = false }

    override val modelId: String get() = model

    /** Gemini caches repeated prompt prefixes implicitly and reports `cachedContentTokenCount`. */
    override val promptCaching: PromptCacheCapabilities get() = IMPLICIT_PREFIX_CACHING

    override suspend fun converse(
        prompt: String,
        tools: List<com.hereliesaz.geministrator.mcp.McpToolOffer>,
        rounds: List<ToolRound>,
        allowTools: Boolean,
    ): ToolTurn {
        val key = apiKeyProvider.requireKey("Gemini")
        val body = GeminiToolWire.request(prompt, tools, rounds, allowTools)
        return GeminiToolWire.turn(
            client.postJson("$baseUrl/models/$model:generateContent", body, "generate Gemini response") {
                header("x-goog-api-key", key)
            },
        )
    }

    init {
        // The model id becomes a URL path segment, so it may not carry '/', '?', '#' or '..'.
        require(model.matches(Regex("[A-Za-z0-9._-]+")) && model != "." && model != "..") {
            "Gemini model id contains characters that are not allowed"
        }
    }

    override val outputTokenCap: Int get() = PlanOutputBudget.NATIVE_CAP

    override suspend fun generate(prompt: String): TextGenerationResult = generate(prompt, maxOutputTokens = null)

    override suspend fun generateWithBudget(prompt: String, maxOutputTokens: Int): TextGenerationResult =
        generate(prompt, maxOutputTokens = minOf(maxOutputTokens, outputTokenCap))

    private suspend fun generate(prompt: String, maxOutputTokens: Int?): TextGenerationResult {
        val response = client.post("$baseUrl/models/$model:generateContent") {
            header("x-goog-api-key", apiKeyProvider.requireKey("Gemini"))
            contentType(ContentType.Application.Json)
            setBody(
                GeminiGenerateContentRequest(
                    contents = listOf(
                        GeminiContent(parts = listOf(GeminiPart(text = prompt))),
                    ),
                    generationConfig = maxOutputTokens?.let { GeminiGenerationConfig(maxOutputTokens = it) },
                ),
            )
        }
        response.requireSuccess("generate Gemini response")
        val payload = response.body<GeminiGenerateContentResponse>()
        payload.candidates.firstOrNull()?.finishReason?.let { requireCompleteGeminiFinish(it, payload.usageMetadata) }
        val text = payload.candidates
            .asSequence()
            .flatMap { it.content?.parts.orEmpty().asSequence() }
            .firstOrNull { !it.text.isNullOrBlank() }
            ?.text
            ?: error("Gemini response did not contain text")
        return TextGenerationResult(
            text = text,
            inputTokens = payload.usageMetadata?.promptTokenCount,
            outputTokens = payload.usageMetadata?.candidatesTokenCount,
            cachedInputTokens = payload.usageMetadata?.cachedContentTokenCount,
        )
    }

    /** Asks for thought summaries until the model refuses them once. */
    private var requestThoughts = true

    override suspend fun stream(prompt: String, onChunk: suspend (TextGenerationChunk) -> Unit): TextGenerationResult =
        withReasoningFallback(requestThoughts, { requestThoughts = false }) { thoughts ->
            val onChunk = onChunk.cappedOutput("Gemini")
            val text = StringBuilder()
            val reasoning = StringBuilder()
            var usage: GeminiUsageMetadata? = null
            var finishReason: String? = null
            client.postServerSentEvents(
                url = "$baseUrl/models/$model:streamGenerateContent?alt=sse",
                operation = "stream Gemini response",
                configure = {
                    header("x-goog-api-key", apiKeyProvider.requireKey("Gemini"))
                    contentType(ContentType.Application.Json)
                    setBody(
                        GeminiGenerateContentRequest(
                            contents = listOf(GeminiContent(parts = listOf(GeminiPart(text = prompt)))),
                            generationConfig = if (thoughts) GeminiGenerationConfig(GeminiThinkingConfig(includeThoughts = true)) else null,
                        ),
                    )
                },
            ) { _, data ->
                val chunk = streamJson.decodeFromString<GeminiGenerateContentResponse>(data)
                chunk.error?.let { error("Gemini stream error: ${it.message ?: data.take(300)}") }
                chunk.usageMetadata?.let { usage = it }
                chunk.candidates.firstOrNull()?.finishReason?.let { finishReason = it }
                chunk.candidates.flatMap { it.content?.parts.orEmpty() }.forEach { part ->
                    val piece = part.text?.takeIf(String::isNotEmpty) ?: return@forEach
                    if (part.thought == true) {
                        reasoning.append(piece)
                        onChunk(TextGenerationChunk.Thinking(piece))
                    } else {
                        text.append(piece)
                        onChunk(TextGenerationChunk.Text(piece))
                    }
                }
            }
            // The last chunk carries the finish reason; a stream without one was cut off.
            requireStreamTerminated(finishReason != null, "Gemini")
            finishReason?.let { requireCompleteGeminiFinish(it, usage) }
            if (text.isBlank()) {
                throw SpentGenerationFailure(
                    "Gemini response did not contain text", null,
                    usage?.promptTokenCount, usage?.candidatesTokenCount, usage?.cachedContentTokenCount,
                )
            }
            TextGenerationResult(
                text = text.toString(),
                inputTokens = usage?.promptTokenCount,
                outputTokens = usage?.candidatesTokenCount,
                thinking = reasoning.toString().trim().ifEmpty { null },
                cachedInputTokens = usage?.cachedContentTokenCount,
            )
        }
}

/** Lenient decoding for stream events, whose shapes carry many fields this client does not read. */
internal val streamJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
}

private suspend fun io.ktor.client.request.HttpRequestBuilder.bearer(apiKeyProvider: LlmApiKeyProvider) {
    val key = apiKeyProvider.requireKey("OpenAI-compatible")
    header(HttpHeaders.Authorization, "Bearer $key")
}

private suspend fun LlmApiKeyProvider.requireKey(providerName: String): String =
    getApiKey().trim().takeIf(String::isNotEmpty)
        ?: error("$providerName API key is not configured")

private suspend fun HttpResponse.requireSuccess(operation: String) {
    if (status.value in 200..299) return
    val responseBody = boundedBodyText().redactSecrets().take(800)
    throw HttpStatusFailure(
        status = status.value,
        message = "Unable to $operation: HTTP ${status.value}" +
            responseBody.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty(),
        retryAfterMillis = parseRetryAfterMillis(headers[HttpHeaders.RetryAfter]),
    )
}

@Serializable
private data class OpenAiResponseRequest(
    val model: String,
    val input: String,
    val stream: Boolean? = null,
    val reasoning: OpenAiReasoning? = null,
    @SerialName("max_output_tokens") val maxOutputTokens: Int? = null,
)

@Serializable
private data class OpenAiReasoning(val summary: String)

@Serializable
private data class OpenAiStreamEvent(
    val type: String = "",
    val delta: String? = null,
    val message: String? = null,
    val response: OpenAiStreamResponse? = null,
)

@Serializable
private data class OpenAiStreamResponse(
    val usage: OpenAiUsage? = null,
    val error: OpenAiStreamError? = null,
    @SerialName("incomplete_details") val incompleteDetails: OpenAiIncompleteDetails? = null,
)

@Serializable
private data class OpenAiStreamError(val message: String? = null)

@Serializable
private data class OpenAiIncompleteDetails(val reason: String? = null)

@Serializable
private data class OpenAiResponse(
    val output: List<OpenAiOutputItem> = emptyList(),
    val usage: OpenAiUsage? = null,
    val status: String? = null,
    val error: OpenAiStreamError? = null,
    @SerialName("incomplete_details") val incompleteDetails: OpenAiIncompleteDetails? = null,
)

@Serializable
private data class OpenAiOutputItem(
    val type: String? = null,
    val content: List<OpenAiOutputContent> = emptyList(),
)

@Serializable
private data class OpenAiOutputContent(
    val type: String? = null,
    val text: String? = null,
)

@Serializable
private data class OpenAiUsage(
    @SerialName("input_tokens") val inputTokens: Long? = null,
    @SerialName("output_tokens") val outputTokens: Long? = null,
    @SerialName("input_tokens_details") val inputTokensDetails: OpenAiInputTokensDetails? = null,
)

@Serializable
private data class OpenAiInputTokensDetails(@SerialName("cached_tokens") val cachedTokens: Long? = null)

@Serializable
private data class AnthropicMessageRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val messages: List<AnthropicInputMessage>,
    val system: List<AnthropicTextBlock>? = null,
    val stream: Boolean? = null,
    val thinking: AnthropicThinking? = null,
)

/** A text content block. `type` has no default value, so it is always encoded. */
@Serializable
private data class AnthropicTextBlock(
    val type: String,
    val text: String,
    @SerialName("cache_control") val cacheControl: AnthropicCacheControl? = null,
)

@Serializable
private data class AnthropicCacheControl(val type: String)

@Serializable
private data class AnthropicThinking(
    val type: String,
    val display: String? = null,
    @SerialName("budget_tokens") val budgetTokens: Int? = null,
)

@Serializable
private data class AnthropicStreamEvent(
    val type: String = "",
    val message: AnthropicStreamMessage? = null,
    @SerialName("content_block") val contentBlock: AnthropicContentBlock? = null,
    val delta: AnthropicStreamDelta? = null,
    val usage: AnthropicUsage? = null,
    val error: AnthropicStreamError? = null,
)

@Serializable
private data class AnthropicStreamMessage(val usage: AnthropicUsage? = null)

@Serializable
private data class AnthropicStreamDelta(
    val type: String? = null,
    val text: String? = null,
    val thinking: String? = null,
    @SerialName("stop_reason") val stopReason: String? = null,
)

@Serializable
private data class AnthropicStreamError(val message: String? = null)

@Serializable
private data class AnthropicInputMessage(
    val role: String,
    val content: String,
)

@Serializable
private data class AnthropicMessageResponse(
    val content: List<AnthropicContentBlock> = emptyList(),
    val usage: AnthropicUsage? = null,
    @SerialName("stop_reason") val stopReason: String? = null,
)

@Serializable
private data class AnthropicContentBlock(
    val type: String? = null,
    val text: String? = null,
)

@Serializable
private data class AnthropicUsage(
    /** Input after the last cache breakpoint only; cache reads and writes are counted apart. */
    @SerialName("input_tokens") val inputTokens: Long? = null,
    @SerialName("output_tokens") val outputTokens: Long? = null,
    @SerialName("cache_creation_input_tokens") val cacheCreationInputTokens: Long? = null,
    @SerialName("cache_read_input_tokens") val cacheReadInputTokens: Long? = null,
)

/** The whole prompt: uncached input plus the tokens written to and read from the cache. */
private fun AnthropicUsage.promptTokens(): Long? =
    inputTokens?.let { it + (cacheCreationInputTokens ?: 0L) + (cacheReadInputTokens ?: 0L) }

@Serializable
private data class GeminiGenerateContentRequest(
    val contents: List<GeminiContent>,
    val generationConfig: GeminiGenerationConfig? = null,
)

@Serializable
private data class GeminiGenerationConfig(
    val thinkingConfig: GeminiThinkingConfig? = null,
    val maxOutputTokens: Int? = null,
)

@Serializable
private data class GeminiThinkingConfig(val includeThoughts: Boolean)

@Serializable
private data class GeminiError(val message: String? = null)

@Serializable
private data class GeminiContent(
    val parts: List<GeminiPart> = emptyList(),
)

@Serializable
private data class GeminiPart(
    val text: String? = null,
    val thought: Boolean? = null,
)

@Serializable
private data class GeminiGenerateContentResponse(
    val candidates: List<GeminiCandidate> = emptyList(),
    val usageMetadata: GeminiUsageMetadata? = null,
    val error: GeminiError? = null,
)

@Serializable
private data class GeminiCandidate(
    val content: GeminiContent? = null,
    val finishReason: String? = null,
)

/** Only a natural stop is a complete answer; MAX_TOKENS, SAFETY, RECITATION and the like are not. */
private fun requireCompleteGeminiFinish(finishReason: String, usage: GeminiUsageMetadata? = null) {
    when (finishReason) {
        "STOP", "FINISH_REASON_UNSPECIFIED" -> Unit
        "MAX_TOKENS" -> throw OutputTruncatedException(
            "Gemini response was truncated at the token limit; output is incomplete",
            usage?.promptTokenCount, usage?.candidatesTokenCount, usage?.cachedContentTokenCount,
        )
        else -> throw SpentGenerationFailure(
            "Gemini response stopped early ($finishReason); output is incomplete", null,
            usage?.promptTokenCount, usage?.candidatesTokenCount, usage?.cachedContentTokenCount,
        )
    }
}

@Serializable
private data class GeminiUsageMetadata(
    /** The whole prompt, [cachedContentTokenCount] included. */
    val promptTokenCount: Long? = null,
    val candidatesTokenCount: Long? = null,
    val cachedContentTokenCount: Long? = null,
)
