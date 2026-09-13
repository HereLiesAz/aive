package com.hereliesaz.geministrator.providers.jules

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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class JulesRestApiTest {
    @Test
    fun apiKeyIsNeverForwardedAcrossRedirects() = runBlocking {
        var redirectedRequestSeen = false
        val engine = MockEngine { request ->
            if (request.url.host == "attacker.example") {
                redirectedRequestSeen = true
                respond(
                    content = "{\"sources\":[]}",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            } else {
                respond(
                    content = "",
                    status = HttpStatusCode.Found,
                    headers = headersOf(HttpHeaders.Location, "https://attacker.example/steal"),
                )
            }
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        val api = JulesRestApi(
            apiKeyProvider = JulesApiKeyProvider { "super-secret" },
            client = client,
        )

        try {
            assertFailsWith<IllegalStateException> {
                api.listSources()
            }
            assertFalse(redirectedRequestSeen)
        } finally {
            client.close()
        }
    }

    @Test
    fun httpFailureIncludesStatusAndProviderResponseBody() = runBlocking {
        val engine = MockEngine {
            respond(
                content = "{\"error\":{\"message\":\"API key not valid\"}}",
                status = HttpStatusCode.Unauthorized,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true })
            }
        }
        val api = JulesRestApi(
            apiKeyProvider = JulesApiKeyProvider { "bad-key" },
            client = client,
        )

        try {
            val failure = assertFailsWith<IllegalStateException> {
                api.listSources()
            }
            val message = failure.message.orEmpty()
            assertContains(message, "HTTP 401")
            assertContains(message, "API key not valid")
        } finally {
            client.close()
        }
    }
}
