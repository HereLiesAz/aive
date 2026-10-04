package com.hereliesaz.geministrator.providers.llm

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
)

interface TextGenerationApi {
    suspend fun generate(prompt: String): TextGenerationResult

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

private fun defaultClient(): HttpClient = HttpClient {
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

class OpenAiResponsesApi(
    private val apiKeyProvider: LlmApiKeyProvider,
    private val model: String = "gpt-5.6",
    private val baseUrl: String = "https://api.openai.com/v1",
    client: HttpClient = defaultClient(),
) : TextGenerationApi {
    private val client = client.config { followRedirects = false }

    override suspend fun generate(prompt: String): TextGenerationResult {
        val response = client.post("$baseUrl/responses") {
            bearer(apiKeyProvider)
            contentType(ContentType.Application.Json)
            setBody(OpenAiResponseRequest(model = model, input = prompt))
        }
        response.requireSuccess("generate OpenAI response")
        val payload = response.body<OpenAiResponse>()
        val text = payload.output
            .asSequence()
            .flatMap { it.content.asSequence() }
            .firstOrNull { it.type == "output_text" && !it.text.isNullOrBlank() }
            ?.text
            ?: error("OpenAI response did not contain output text")
        return TextGenerationResult(
            text = text,
            inputTokens = payload.usage?.inputTokens,
            outputTokens = payload.usage?.outputTokens,
        )
    }

    /** Asks for a reasoning summary until the API refuses it once for this model or account. */
    private var requestReasoning = true

    override suspend fun stream(prompt: String, onChunk: suspend (TextGenerationChunk) -> Unit): TextGenerationResult =
        withReasoningFallback(requestReasoning, { requestReasoning = false }) { reasoning ->
            val text = StringBuilder()
            val thinking = StringBuilder()
            var usage: OpenAiUsage? = null
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
                    "response.completed" -> usage = event.response?.usage
                    "response.failed", "response.incomplete" -> error(
                        "OpenAI response ${event.type.substringAfter('.')}: " +
                            (event.response?.error?.message ?: event.response?.incompleteDetails?.reason ?: "no detail"),
                    )
                    "error" -> error("OpenAI stream error: ${event.message ?: data.take(300)}")
                }
            }
            if (text.isBlank()) error("OpenAI response did not contain output text")
            TextGenerationResult(
                text = text.toString(),
                inputTokens = usage?.inputTokens,
                outputTokens = usage?.outputTokens,
                thinking = thinking.toString().trim().ifEmpty { null },
            )
        }
}

class XaiResponsesApi(
    private val apiKeyProvider: LlmApiKeyProvider,
    private val model: String = "grok-4.6",
    private val baseUrl: String = "https://api.x.ai/v1",
    client: HttpClient = defaultClient(),
) : TextGenerationApi {
    private val delegate = OpenAiResponsesApi(
        apiKeyProvider = apiKeyProvider,
        model = model,
        baseUrl = baseUrl,
        client = client,
    )

    override suspend fun generate(prompt: String): TextGenerationResult = delegate.generate(prompt)

    override suspend fun stream(prompt: String, onChunk: suspend (TextGenerationChunk) -> Unit): TextGenerationResult =
        delegate.stream(prompt, onChunk)
}

class AnthropicMessagesApi(
    private val apiKeyProvider: LlmApiKeyProvider,
    private val model: String = "claude-sonnet-5",
    private val baseUrl: String = "https://api.anthropic.com/v1",
    client: HttpClient = defaultClient(),
) : TextGenerationApi {
    private val client = client.config { followRedirects = false }

    override suspend fun generate(prompt: String): TextGenerationResult {
        val response = client.post("$baseUrl/messages") {
            val key = apiKeyProvider.requireKey("Anthropic")
            header("x-api-key", key)
            header("anthropic-version", "2023-06-01")
            contentType(ContentType.Application.Json)
            setBody(
                AnthropicMessageRequest(
                    model = model,
                    maxTokens = 8_192,
                    messages = listOf(AnthropicInputMessage(role = "user", content = prompt)),
                ),
            )
        }
        response.requireSuccess("generate Claude response")
        val payload = response.body<AnthropicMessageResponse>()
        if (payload.stopReason == "max_tokens") {
            error("Claude response was truncated at the token limit; output is incomplete")
        }
        val text = payload.content
            .firstOrNull { it.type == "text" && !it.text.isNullOrBlank() }
            ?.text
            ?: error("Claude response did not contain text")
        return TextGenerationResult(
            text = text,
            inputTokens = payload.usage?.inputTokens,
            outputTokens = payload.usage?.outputTokens,
        )
    }

    /**
     * Thinking modes tried in order until the model accepts one: adaptive with summarized display
     * (current models), a fixed budget (Haiku 4.5 and older), then none.
     */
    private var thinkingMode = 0

    override suspend fun stream(prompt: String, onChunk: suspend (TextGenerationChunk) -> Unit): TextGenerationResult {
        while (true) {
            val mode = THINKING_MODES.getOrNull(thinkingMode)
            try {
                return streamOnce(prompt, mode, onChunk)
            } catch (rejected: StreamRejected) {
                if (mode == null || !rejected.rejectsReasoning()) throw rejected
                thinkingMode++
            }
        }
    }

    private suspend fun streamOnce(
        prompt: String,
        thinking: AnthropicThinking?,
        onChunk: suspend (TextGenerationChunk) -> Unit,
    ): TextGenerationResult {
        val text = StringBuilder()
        val reasoning = StringBuilder()
        var inputTokens: Long? = null
        var outputTokens: Long? = null
        var stopReason: String? = null
        client.postServerSentEvents(
            url = "$baseUrl/messages",
            operation = "stream Claude response",
            configure = {
                header("x-api-key", apiKeyProvider.requireKey("Anthropic"))
                header("anthropic-version", "2023-06-01")
                contentType(ContentType.Application.Json)
                setBody(
                    AnthropicMessageRequest(
                        model = model,
                        maxTokens = STREAM_MAX_TOKENS,
                        messages = listOf(AnthropicInputMessage(role = "user", content = prompt)),
                        stream = true,
                        thinking = thinking,
                    ),
                )
            },
        ) { _, data ->
            val event = streamJson.decodeFromString<AnthropicStreamEvent>(data)
            when (event.type) {
                "message_start" -> inputTokens = event.message?.usage?.inputTokens
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
                "error" -> error("Claude stream error: ${event.error?.message ?: data.take(300)}")
            }
        }
        when (stopReason) {
            "max_tokens" -> error("Claude response was truncated at the token limit; output is incomplete")
            "refusal" -> error("Claude declined this request")
        }
        if (text.isBlank()) error("Claude response did not contain text")
        return TextGenerationResult(
            text = text.toString(),
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            thinking = reasoning.toString().trim().ifEmpty { null },
        )
    }

    private companion object {
        const val STREAM_MAX_TOKENS = 32_000
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
    client: HttpClient = defaultClient(),
) : TextGenerationApi {
    private val client = client.config { followRedirects = false }

    override suspend fun generate(prompt: String): TextGenerationResult {
        val response = client.post("$baseUrl/models/$model:generateContent") {
            header("x-goog-api-key", apiKeyProvider.requireKey("Gemini"))
            contentType(ContentType.Application.Json)
            setBody(
                GeminiGenerateContentRequest(
                    contents = listOf(
                        GeminiContent(parts = listOf(GeminiPart(text = prompt))),
                    ),
                ),
            )
        }
        response.requireSuccess("generate Gemini response")
        val payload = response.body<GeminiGenerateContentResponse>()
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
        )
    }

    /** Asks for thought summaries until the model refuses them once. */
    private var requestThoughts = true

    override suspend fun stream(prompt: String, onChunk: suspend (TextGenerationChunk) -> Unit): TextGenerationResult =
        withReasoningFallback(requestThoughts, { requestThoughts = false }) { thoughts ->
            val text = StringBuilder()
            val reasoning = StringBuilder()
            var usage: GeminiUsageMetadata? = null
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
            if (text.isBlank()) error("Gemini response did not contain text")
            TextGenerationResult(
                text = text.toString(),
                inputTokens = usage?.promptTokenCount,
                outputTokens = usage?.candidatesTokenCount,
                thinking = reasoning.toString().trim().ifEmpty { null },
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
    val responseBody = bodyAsText().take(800)
    error(
        "Unable to $operation: HTTP ${status.value}" +
            responseBody.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty(),
    )
}

@Serializable
private data class OpenAiResponseRequest(
    val model: String,
    val input: String,
    val stream: Boolean? = null,
    val reasoning: OpenAiReasoning? = null,
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
)

@Serializable
private data class AnthropicMessageRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val messages: List<AnthropicInputMessage>,
    val stream: Boolean? = null,
    val thinking: AnthropicThinking? = null,
)

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
    @SerialName("input_tokens") val inputTokens: Long? = null,
    @SerialName("output_tokens") val outputTokens: Long? = null,
)

@Serializable
private data class GeminiGenerateContentRequest(
    val contents: List<GeminiContent>,
    val generationConfig: GeminiGenerationConfig? = null,
)

@Serializable
private data class GeminiGenerationConfig(val thinkingConfig: GeminiThinkingConfig)

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
)

@Serializable
private data class GeminiUsageMetadata(
    val promptTokenCount: Long? = null,
    val candidatesTokenCount: Long? = null,
)
