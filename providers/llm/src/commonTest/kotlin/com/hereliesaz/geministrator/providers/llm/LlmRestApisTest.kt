package com.hereliesaz.geministrator.providers.llm

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.content.OutgoingContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class LlmRestApisTest {
    @Test
    fun openAiParsesResponseTextAndUsage() = runTest {
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
    fun reservedNativeAdvisoryUsesWireLimitAndNoAmbientSpendEvenWhenTruncated() = runTest {
        val requests = mutableListOf<String>()
        var ambient = 0
        val client = mockJsonClient { request ->
            requests += (request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()
            respond(
                content = if (requests.size == 1)
                    """{"content":[{"type":"text","text":"advice"}],"usage":{"input_tokens":37,"output_tokens":8}}"""
                else """{"stop_reason":"max_tokens","content":[{"type":"text","text":"partial"}],"usage":{"input_tokens":41,"output_tokens":512}}""",
                status = HttpStatusCode.OK, headers = jsonHeaders)
        }
        try {
            val native = AnthropicMessagesApi(LlmApiKeyProvider { "test" }, client = client)
            val metered = MeteredTextGenerationApi(native, "anthropic", sink = SpendSink { _, _ -> ambient++ })
            val entry = PlanningApiEntry("anthropic", "Claude", metered)
            val model = TextGenerationQueenModel(listOf(entry), "anthropic")
            assertEquals(1_000_000L, model.advisoryInputUpperBound)
            assertEquals(512, model.advisoryOutputLimit)
            val result = model.answerBounded("Question", 512)
            assertEquals("advice", result.text)
            assertEquals(37L, result.inputTokens)
            assertEquals(8L, result.outputTokens)
            val truncated = model.answerBounded("Question two", 512)
            assertEquals("", truncated.text)
            assertEquals(41L, truncated.inputTokens)
            assertEquals(512L, truncated.outputTokens)
            assertEquals(0, ambient)
            assertEquals(2, requests.size)
            requests.forEach {
                val body = Json.parseToJsonElement(it).jsonObject
                assertEquals("512", body.getValue("max_tokens").jsonPrimitive.content)
                assertNull(body["tools"])
                assertNull(body["system"])
            }
            assertFailsWith<IllegalArgumentException> { model.answerBounded("Too large", 513) }
            assertEquals(2, requests.size)
            assertNull(TextGenerationQueenModel(listOf(entry)).advisoryInputUpperBound)
            assertNull(AnthropicMessagesApi(LlmApiKeyProvider { "test" },
                baseUrl = "https://example.invalid/v1", client = client).advisoryInputUpperBound)
            assertNull(OpenAiResponsesApi(LlmApiKeyProvider { "test" }, client = client).advisoryInputUpperBound)
        } finally { client.close() }
    }

    @Test
    fun anthropicSendsApiKeyAndParsesText() = runTest {
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
    fun geminiSendsApiKeyAndParsesText() = runTest {
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
    fun providerHttpErrorPreservesStatusAndBody() = runTest {
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
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): HttpClient = HttpClient(MockEngine(handler)) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }

    private companion object {
        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    }
}
