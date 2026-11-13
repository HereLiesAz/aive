package com.hereliesaz.geministrator.providers.llm

import com.hereliesaz.geministrator.net.boundedBodyText
import com.hereliesaz.geministrator.net.HttpStatusFailure
import com.hereliesaz.geministrator.net.parseRetryAfterMillis
import com.hereliesaz.geministrator.net.CleartextPolicy
import com.hereliesaz.geministrator.providers.PromptCacheCapabilities
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Generic transport for hosted services that expose OpenAI Chat Completions semantics.
 *
 * This intentionally implements only the common text-generation subset Aive needs for a
 * governed one-shot [TextLlmProvider]. Provider-specific features remain behind native adapters.
 * A response finished because of an output-token limit (`finish_reason: "length"`) is rejected:
 * partial text must never be promoted to a completed workflow result.
 */
class OpenAiCompatibleChatApi(
    private val apiKeyProvider: LlmApiKeyProvider,
    private val model: String,
    baseUrl: String,
    private val extraHeaders: Map<String, String> = emptyMap(),
    /** False for keyless free endpoints: a blank key then sends no Authorization header. */
    private val requireApiKey: Boolean = true,
    /** The most output tokens a plan may ask this service for (its model's documented cap); see [PlanOutputBudget]. */
    private val maxOutputTokenCap: Int = PlanOutputBudget.COMPATIBLE_CAP,
    /** True for a keyless endpoint used anonymously: it cannot bill, so its usage costs nothing. */
    private val freeOfCharge: Boolean = false,
    client: HttpClient = sharedLlmHttpClient(),
) : ToolCallingTextGenerationApi {
    private val baseUrl = baseUrl.trimEnd('/')
    private val client = client.config { followRedirects = false }

    /**
     * Services that cache prompt prefixes do so automatically. Cache hits are read from
     * `prompt_tokens_details.cached_tokens` (DeepSeek: `prompt_cache_hit_tokens`) and reported only
     * when the response carries one.
     */
    override val promptCaching: PromptCacheCapabilities get() = IMPLICIT_PREFIX_CACHING

    override val outputTokenCap: Int get() = maxOutputTokenCap

    override val modelId: String get() = model

    override val billsPerToken: Boolean get() = !freeOfCharge

    init {
        require(model.isNotBlank()) { "model must not be blank" }
        require(this.baseUrl.startsWith("https://") || this.baseUrl.startsWith("http://")) {
            "baseUrl must be an absolute HTTP(S) URL"
        }
    }

    /** The key to send, checked to be present (when required) and safe to send to [baseUrl]. */
    private suspend fun sendableApiKey(): String {
        val apiKey = apiKeyProvider.getApiKey().trim()
        require(apiKey.isNotEmpty() || !requireApiKey) { "API key is not configured" }
        require(apiKey.isEmpty() || CleartextPolicy.allowsSecret(baseUrl)) {
            "Refusing to send an API key over plain HTTP to a public host; use an https:// base URL"
        }
        return apiKey
    }

    override suspend fun converse(
        prompt: String,
        tools: List<com.hereliesaz.geministrator.mcp.McpToolOffer>,
        rounds: List<ToolRound>,
        allowTools: Boolean,
    ): ToolTurn {
        val apiKey = sendableApiKey()
        val body = CompatibleToolWire.request(model, prompt, tools, rounds, allowTools)
        return CompatibleToolWire.turn(
            client.postJson("$baseUrl/chat/completions", body, "generate compatible chat response") {
                if (apiKey.isNotEmpty()) header(HttpHeaders.Authorization, "Bearer $apiKey")
                extraHeaders.forEach { (name, value) -> header(name, value) }
            },
        )
    }

    override suspend fun generate(prompt: String): TextGenerationResult = generate(prompt, maxOutputTokens = null)

    override suspend fun generateWithBudget(prompt: String, maxOutputTokens: Int): TextGenerationResult =
        generate(prompt, maxOutputTokens = minOf(maxOutputTokens, maxOutputTokenCap))

    private suspend fun generate(prompt: String, maxOutputTokens: Int?): TextGenerationResult {
        val apiKey = sendableApiKey()

        val response = client.post("$baseUrl/chat/completions") {
            if (apiKey.isNotEmpty()) header(HttpHeaders.Authorization, "Bearer $apiKey")
            extraHeaders.forEach { (name, value) -> header(name, value) }
            contentType(ContentType.Application.Json)
            setBody(
                CompatibleChatRequest(
                    model = model,
                    messages = listOf(CompatibleChatMessage(role = "user", content = prompt)),
                    maxTokens = maxOutputTokens,
                ),
            )
        }

        if (response.status.value !in 200..299) {
            val responseBody = response.boundedBodyText().redactSecrets().take(800)
            throw HttpStatusFailure(
                status = response.status.value,
                message = "Unable to generate compatible chat response: HTTP ${response.status.value}" +
                    responseBody.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty(),
                retryAfterMillis = parseRetryAfterMillis(response.headers[HttpHeaders.RetryAfter]),
            )
        }

        val payload = response.body<CompatibleChatResponse>()
        val choice = payload.choices
            .firstOrNull { !it.message?.content?.trim().isNullOrEmpty() }
            ?: throw SpentGenerationFailure(
                "Compatible chat response did not contain assistant text", null,
                payload.usage?.promptTokens, payload.usage?.completionTokens, payload.usage?.cachedTokens(),
            )
        requireCompleteFinishReason(choice.finishReason, payload.usage)
        val text = requireNotNull(choice.message).content!!.trim()

        return TextGenerationResult(
            text = text,
            inputTokens = payload.usage?.promptTokens,
            outputTokens = payload.usage?.completionTokens,
            cachedInputTokens = payload.usage?.cachedTokens(),
        )
    }

    /** Asks for a final usage chunk until an endpoint refuses `stream_options` once. */
    private var requestUsage = true

    /**
     * Streams Chat Completions. There is no common switch for reasoning across these services, so
     * none is sent; reasoning that arrives is read from `reasoning_content` (DeepSeek, Kimi and
     * others), `reasoning` (OpenRouter, Groq) or inline `<think>` spans.
     */
    override suspend fun stream(prompt: String, onChunk: suspend (TextGenerationChunk) -> Unit): TextGenerationResult {
        val apiKey = sendableApiKey()
        return withReasoningFallback(requestUsage, { requestUsage = false }) { usageOption ->
            val onChunk = onChunk.cappedOutput("Compatible chat")
            val text = StringBuilder()
            val reasoning = StringBuilder()
            val splitter = ThinkTagSplitter()
            var usage: CompatibleChatUsage? = null
            var finishReason: String? = null
            var done = false
            suspend fun deliver(chunks: List<TextGenerationChunk>) = chunks.forEach { chunk ->
                when (chunk) {
                    is TextGenerationChunk.Thinking -> reasoning.append(chunk.text)
                    is TextGenerationChunk.Text -> text.append(chunk.text)
                }
                onChunk(chunk)
            }
            client.postServerSentEvents(
                url = "$baseUrl/chat/completions",
                operation = "stream compatible chat response",
                configure = {
                    if (apiKey.isNotEmpty()) header(HttpHeaders.Authorization, "Bearer $apiKey")
                    extraHeaders.forEach { (name, value) -> header(name, value) }
                    contentType(ContentType.Application.Json)
                    setBody(
                        CompatibleChatRequest(
                            model = model,
                            messages = listOf(CompatibleChatMessage(role = "user", content = prompt)),
                            stream = true,
                            streamOptions = if (usageOption) CompatibleStreamOptions(includeUsage = true) else null,
                        ),
                    )
                },
            ) { _, data ->
                if (data.trim() == "[DONE]") {
                    done = true
                    return@postServerSentEvents
                }
                val chunk = streamJson.decodeFromString<CompatibleChatStreamChunk>(data)
                chunk.error?.let { error("Compatible chat stream error: ${it.message ?: data.take(300)}") }
                chunk.usage?.let { usage = it }
                chunk.choices.forEach { choice ->
                    choice.finishReason?.let { finishReason = it }
                    val delta = choice.delta ?: return@forEach
                    val thought = delta.reasoningContent ?: (delta.reasoning as? JsonPrimitive)?.takeIf { it.isString }?.content
                    thought?.takeIf(String::isNotEmpty)?.let { deliver(listOf(TextGenerationChunk.Thinking(it))) }
                    delta.content?.takeIf(String::isNotEmpty)?.let { deliver(splitter.accept(it)) }
                }
            }
            deliver(splitter.finish())
            requireStreamTerminated(done || finishReason != null, "Compatible chat")
            requireCompleteFinishReason(finishReason, usage)
            if (text.isBlank()) {
                throw SpentGenerationFailure(
                    "Compatible chat response did not contain assistant text", null,
                    usage?.promptTokens, usage?.completionTokens, usage?.cachedTokens(),
                )
            }
            TextGenerationResult(
                text = text.toString().trim(),
                inputTokens = usage?.promptTokens,
                outputTokens = usage?.completionTokens,
                thinking = reasoning.toString().trim().ifEmpty { null },
                cachedInputTokens = usage?.cachedTokens(),
            )
        }
    }
}


private fun requireCompleteFinishReason(finishReason: String?, usage: CompatibleChatUsage? = null) {
    if (finishReason.equals("length", ignoreCase = true)) {
        // Still the bare-error type in effect (it is an IllegalStateException): what the provider billed rides on it.
        throw SpentGenerationFailure(
            "Compatible chat response was truncated because the provider reached its output-token limit", null,
            usage?.promptTokens, usage?.completionTokens, usage?.cachedTokens(),
        )
    }
}

@Serializable
private data class CompatibleChatRequest(
    val model: String,
    val messages: List<CompatibleChatMessage>,
    val stream: Boolean? = null,
    @SerialName("stream_options") val streamOptions: CompatibleStreamOptions? = null,
    @SerialName("max_tokens") val maxTokens: Int? = null,
)

@Serializable
private data class CompatibleStreamOptions(@SerialName("include_usage") val includeUsage: Boolean)

@Serializable
private data class CompatibleChatStreamChunk(
    val choices: List<CompatibleChatStreamChoice> = emptyList(),
    val usage: CompatibleChatUsage? = null,
    val error: CompatibleChatError? = null,
)

@Serializable
private data class CompatibleChatStreamChoice(
    val delta: CompatibleChatDelta? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
private data class CompatibleChatDelta(
    val content: String? = null,
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    val reasoning: JsonElement? = null,
)

@Serializable
private data class CompatibleChatError(val message: String? = null)

@Serializable
private data class CompatibleChatMessage(
    val role: String,
    val content: String,
)

@Serializable
private data class CompatibleChatResponse(
    val choices: List<CompatibleChatChoice> = emptyList(),
    val usage: CompatibleChatUsage? = null,
)

@Serializable
private data class CompatibleChatChoice(
    val message: CompatibleChatResponseMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
private data class CompatibleChatResponseMessage(
    val content: String? = null,
)

@Serializable
private data class CompatibleChatUsage(
    @SerialName("prompt_tokens") val promptTokens: Long? = null,
    @SerialName("completion_tokens") val completionTokens: Long? = null,
    @SerialName("prompt_tokens_details") val promptTokensDetails: CompatiblePromptTokensDetails? = null,
    /** DeepSeek's count of prompt tokens served from its context cache. */
    @SerialName("prompt_cache_hit_tokens") val promptCacheHitTokens: Long? = null,
)

@Serializable
private data class CompatiblePromptTokensDetails(@SerialName("cached_tokens") val cachedTokens: Long? = null)

private fun CompatibleChatUsage.cachedTokens(): Long? = promptTokensDetails?.cachedTokens ?: promptCacheHitTokens
