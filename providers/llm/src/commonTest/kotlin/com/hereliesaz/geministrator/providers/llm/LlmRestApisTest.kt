package com.hereliesaz.geministrator.providers.llm

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LlmRestApisTest {
    @Test
    fun openAiParsesResponseTextAndUsage() = runBlocking {
        var authorization: String? = null
        val client = mockJsonClient { request ->
            authorization = request.headers[HttpHeaders.Authorization]
            respond(
                content = """{
                    "output":[{"type":"message","content":[{"type":"output_text","text":"review complete"}]}],
                    "usage":{"input_tokens":17,"output_tokens":5}
                }""".trimIndent(),
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        try {
            val result = OpenAiResponsesApi(
                apiKeyProvider = LlmApiKeyProvider { "openai-key" },
                client = client,
            ).generate("Review this")

            assertEquals("Bearer openai-key", authorization)
            assertEquals("review complete", result.text)
            assertEquals(17L, result.inputTokens)
            assertEquals(5L, result.outputTokens)
        } finally {
            client.close()
        }
    }

    @Test
    fun anthropicSendsApiKeyAndParsesText() = runBlocking {
        var apiKey: String? = null
        val client = mockJsonClient { request ->
            apiKey = request.headers["x-api-key"]
            respond(
                content = """{
                    "content":[{"type":"text","text":"architecture ready"}],
                    "usage":{"input_tokens":9,"output_tokens":4}
                }""".trimIndent(),
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        try {
            val result = AnthropicMessagesApi(
                apiKeyProvider = LlmApiKeyProvider { "anthropic-key" },
                client = client,
            ).generate("Design it")

            assertEquals("anthropic-key", apiKey)
            assertEquals("architecture ready", result.text)
            assertEquals(9L, result.inputTokens)
            assertEquals(4L, result.outputTokens)
        } finally {
            client.close()
        }
    }

    @Test
    fun geminiSendsApiKeyAndParsesText() = runBlocking {
        var apiKey: String? = null
        val client = mockJsonClient { request ->
            apiKey = request.headers["x-goog-api-key"]
            respond(
                content = """{
                    "candidates":[{"content":{"parts":[{"text":"verification notes"}]}}],
                    "usageMetadata":{"promptTokenCount":12,"candidatesTokenCount":6}
                }""".trimIndent(),
                status = HttpStatusCode.OK,
                headers = jsonHeaders,
            )
        }
        try {
            val result = GeminiGenerateContentApi(
                apiKeyProvider = LlmApiKeyProvider { "gemini-key" },
                client = client,
            ).generate("Verify it")

            assertEquals("gemini-key", apiKey)
            assertEquals("verification notes", result.text)
            assertEquals(12L, result.inputTokens)
            assertEquals(6L, result.outputTokens)
        } finally {
            client.close()
        }
    }

    @Test
    fun providerHttpErrorPreservesStatusAndBody() = runBlocking {
        val client = mockJsonClient {
            respond(
                content = "{\"error\":{\"message\":\"quota exhausted\"}}",
                status = HttpStatusCode.TooManyRequests,
                headers = jsonHeaders,
            )
        }
        try {
            val failure = assertFailsWith<IllegalStateException> {
                OpenAiResponsesApi(
                    apiKeyProvider = LlmApiKeyProvider { "key" },
                    client = client,
                ).generate("hello")
            }
            assertContains(failure.message.orEmpty(), "HTTP 429")
            assertContains(failure.message.orEmpty(), "quota exhausted")
        } finally {
            client.close()
        }
    }

    private fun mockJsonClient(
        handler: suspend io.ktor.client.request.HttpRequestData.() -> io.ktor.client.request.HttpResponseData,
    ): HttpClient = HttpClient(MockEngine { request -> handler(request) }) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    private companion object {
        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    }
}
