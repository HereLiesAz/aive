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
)

interface TextGenerationApi {
    suspend fun generate(prompt: String): TextGenerationResult
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
            header("anthropic-dangerous-direct-browser-access", "true")
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
)

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
)

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
)

@Serializable
private data class GeminiContent(
    val parts: List<GeminiPart> = emptyList(),
)

@Serializable
private data class GeminiPart(
    val text: String? = null,
)

@Serializable
private data class GeminiGenerateContentResponse(
    val candidates: List<GeminiCandidate> = emptyList(),
    val usageMetadata: GeminiUsageMetadata? = null,
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
