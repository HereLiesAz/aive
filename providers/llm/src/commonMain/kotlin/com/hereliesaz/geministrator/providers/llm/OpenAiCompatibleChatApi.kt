package com.hereliesaz.geministrator.providers.llm

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

/**
 * Generic transport for hosted services that expose OpenAI Chat Completions semantics.
 *
 * This intentionally implements only the common text-generation subset Haive needs for a
 * governed one-shot [TextLlmProvider]. Provider-specific features remain behind native adapters.
 */
class OpenAiCompatibleChatApi(
    private val apiKeyProvider: LlmApiKeyProvider,
    private val model: String,
    baseUrl: String,
    private val extraHeaders: Map<String, String> = emptyMap(),
    client: HttpClient = openAiCompatibleClient(),
) : TextGenerationApi {
    private val baseUrl = baseUrl.trimEnd('/')
    private val client = client.config { followRedirects = false }

    init {
        require(model.isNotBlank()) { "model must not be blank" }
        require(this.baseUrl.startsWith("https://") || this.baseUrl.startsWith("http://")) {
            "baseUrl must be an absolute HTTP(S) URL"
        }
    }

    override suspend fun generate(prompt: String): TextGenerationResult {
        val apiKey = apiKeyProvider.getApiKey().trim()
        require(apiKey.isNotEmpty()) { "API key is not configured" }

        val response = client.post("$baseUrl/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            extraHeaders.forEach { (name, value) -> header(name, value) }
            contentType(ContentType.Application.Json)
            setBody(
                CompatibleChatRequest(
                    model = model,
                    messages = listOf(CompatibleChatMessage(role = "user", content = prompt)),
                ),
            )
        }

        if (response.status.value !in 200..299) {
            val responseBody = response.bodyAsText().take(800)
            error(
                "Unable to generate compatible chat response: HTTP ${response.status.value}" +
                    responseBody.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty(),
            )
        }

        val payload = response.body<CompatibleChatResponse>()
        val text = payload.choices
            .asSequence()
            .mapNotNull { it.message?.content?.trim()?.takeIf(String::isNotEmpty) }
            .firstOrNull()
            ?: error("Compatible chat response did not contain assistant text")

        return TextGenerationResult(
            text = text,
            inputTokens = payload.usage?.promptTokens,
            outputTokens = payload.usage?.completionTokens,
        )
    }
}

private fun openAiCompatibleClient(): HttpClient = HttpClient {
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

@Serializable
private data class CompatibleChatRequest(
    val model: String,
    val messages: List<CompatibleChatMessage>,
)

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
)

@Serializable
private data class CompatibleChatResponseMessage(
    val content: String? = null,
)

@Serializable
private data class CompatibleChatUsage(
    @SerialName("prompt_tokens") val promptTokens: Long? = null,
    @SerialName("completion_tokens") val completionTokens: Long? = null,
)
