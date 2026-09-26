package com.hereliesaz.geministrator.providers.llm

import com.hereliesaz.geministrator.ProviderCatalog
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
import kotlin.test.assertNull

class OpenAiCompatibleChatApiTest {
    @Test
    fun sendsBearerModelAndParsesUsage() = runBlocking {
        var authorization: String? = null
        var requestBody = ""
        var requestUrl = ""
        val client = HttpClient(MockEngine { request ->
            authorization = request.headers[HttpHeaders.Authorization]
            requestBody = request.body.toString()
            requestUrl = request.url.toString()
            respond(
                content = """{
                    "choices":[{"message":{"role":"assistant","content":"hosted answer"}}],
                    "usage":{"prompt_tokens":21,"completion_tokens":8}
                }""".trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

        try {
            val result = OpenAiCompatibleChatApi(
                apiKeyProvider = LlmApiKeyProvider { "provider-key" },
                model = "example/model",
                baseUrl = "https://example.test/v1/",
                extraHeaders = mapOf("X-Title" to "Haive"),
                client = client,
            ).generate("Do the work")

            assertEquals("Bearer provider-key", authorization)
            assertEquals("https://example.test/v1/chat/completions", requestUrl)
            assertContains(requestBody, "example/model")
            assertContains(requestBody, "Do the work")
            assertEquals("hosted answer", result.text)
            assertEquals(21L, result.inputTokens)
            assertEquals(8L, result.outputTokens)
        } finally {
            client.close()
        }
    }

    @Test
    fun preservesProviderHttpFailure() = runBlocking {
        val client = HttpClient(MockEngine {
            respond(
                content = "{\"error\":{\"message\":\"model unavailable\"}}",
                status = HttpStatusCode.ServiceUnavailable,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

        try {
            val failure = assertFailsWith<IllegalStateException> {
                OpenAiCompatibleChatApi(
                    apiKeyProvider = LlmApiKeyProvider { "key" },
                    model = "model",
                    baseUrl = "https://example.test/v1",
                    client = client,
                ).generate("hello")
            }
            assertContains(failure.message.orEmpty(), "HTTP 503")
            assertContains(failure.message.orEmpty(), "model unavailable")
        } finally {
            client.close()
        }
    }

    @Test
    fun hostedRegistryHasUniqueStableIds() {
        val entries = HostedLlmProviders.entries
        assertEquals(entries.size, entries.map { it.id }.toSet().size)
        assertEquals(18, entries.size)
        entries.forEach { spec ->
            check(spec.id.isNotBlank())
            check(spec.displayName.isNotBlank())
            check(spec.defaultModel.isNotBlank())
            check(spec.baseUrl.startsWith("https://"))
        }
    }

    @Test
    fun keylessEndpointSendsNoAuthorization() = runBlocking {
        var sawAuthorization = true
        val client = HttpClient(MockEngine { request ->
            sawAuthorization = request.headers[HttpHeaders.Authorization] != null
            respond(
                content = """{"choices":[{"message":{"role":"assistant","content":"free answer"}}]}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }

        try {
            val result = OpenAiCompatibleChatApi(
                apiKeyProvider = LlmApiKeyProvider { "" },
                model = "free/model",
                baseUrl = "https://example.test/v1",
                requireApiKey = false,
                client = client,
            ).generate("Plan")

            assertEquals(false, sawAuthorization)
            assertEquals("free answer", result.text)
        } finally {
            client.close()
        }
    }

    @Test
    fun anonymousCredentialOnlyBuildsKeylessProviders() {
        val kilo = HostedLlmProviders.entry(HostedLlmProviders.KILO_ID)!!
        val groq = HostedLlmProviders.entry(HostedLlmProviders.GROQ_ID)!!

        HostedLlmProviders.textApi(kilo, ProviderCatalog.ANONYMOUS_CREDENTIAL)
        assertFailsWith<IllegalArgumentException> {
            HostedLlmProviders.textApi(groq, ProviderCatalog.ANONYMOUS_CREDENTIAL)
        }
    }

    @Test
    fun cloudflareCredentialNeedsAccountAndToken() {
        val cloudflare = HostedLlmProviders.entry(HostedLlmProviders.CLOUDFLARE_ID)!!

        HostedLlmProviders.textApi(cloudflare, "abc123:token")
        assertFailsWith<IllegalArgumentException> { HostedLlmProviders.textApi(cloudflare, "token-only") }
        assertNull(
            HostedLlmProviders.configured(mapOf(HostedLlmProviders.CLOUDFLARE_ID to "token-only")).firstOrNull(),
        )
    }
}
