package com.hereliesaz.aive

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidOpenRouterAccountAuthorizationTest {
    // An engine left open outlives the test and can report its failure to whichever test runs next
    // (kotlinx.coroutines.test.UncaughtExceptionsBeforeTest), so every client a test made is closed.
    private val opened = mutableListOf<HttpClient>()

    private fun trackedClient(
        engine: io.ktor.client.engine.HttpClientEngine,
        config: io.ktor.client.HttpClientConfig<*>.() -> Unit = {},
    ): HttpClient = HttpClient(engine, config).also { opened += it }

    @After
    fun closeOpenedClients() {
        opened.forEach { it.close() }
        opened.clear()
    }

    @Test
    fun signInUsesFreshPkceAndTheCodeIsOneShot() = runBlocking {
        var exchanges = 0
        val client = trackedClient(MockEngine { request ->
            exchanges += 1
            assertEquals("https://openrouter.ai/api/v1/auth/keys", request.url.toString())
            respond("""{"key":"sk-or-v1-test"}""", HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val auth = AndroidOpenRouterAccountAuthorization(client)
        val first = auth.begin()
        val second = auth.begin()
        assertTrue(first.startsWith("https://openrouter.ai/auth?"))
        assertTrue(first.contains("code_challenge_method=S256"))
        assertFalse(first.contains("code_verifier"))
        assertFalse(first.contains("callback_url"))
        assertTrue("Each authorization must generate a fresh challenge", first != second)
        assertEquals("sk-or-v1-test", auth.exchange("one-time-code"))
        assertEquals(1, exchanges)
        try {
            auth.exchange("replayed-code")
            error("Replayed code was accepted")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("Start sign-in"))
        }
        assertEquals(1, exchanges)
    }

    @Test
    fun failedExchangeCannotReplayTheSameVerifier() = runBlocking {
        var requests = 0
        val client = trackedClient(MockEngine {
            requests += 1
            respond("{}", HttpStatusCode.Forbidden)
        })
        val auth = AndroidOpenRouterAccountAuthorization(client)
        auth.begin()
        try {
            auth.exchange("expired")
            error("Forbidden code was accepted")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message.orEmpty().contains("HTTP 403"))
        }
        try {
            auth.exchange("expired")
            error("Failed code was replayed")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("Start sign-in"))
        }
        assertEquals(1, requests)
    }
}
