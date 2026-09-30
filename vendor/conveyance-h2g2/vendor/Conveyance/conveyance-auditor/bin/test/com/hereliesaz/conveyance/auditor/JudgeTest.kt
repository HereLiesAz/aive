package com.hereliesaz.conveyance.auditor

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The wire, verified without a model.
 *
 * A stub server proves the request shape and the response parsing, which is the part that breaks
 * silently. Whether a given model gives a good answer is a separate question and not one a unit
 * test can settle.
 */
class JudgeTest {

    private val json = ObjectMapper()

    private fun serving(reply: String, capture: (String) -> Unit = {}): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/chat/completions") { exchange ->
                capture(exchange.requestBody.readBytes().decodeToString())
                val bytes = reply.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }

    private fun completion(text: String) =
        """{"choices":[{"message":{"role":"assistant","content":${json.writeValueAsString(text)}}}]}"""

    @Test
    fun `an image is sent as a data url and the reply is read back`() {
        var seen = ""
        val server = serving(completion("looks like a button")) { seen = it }
        try {
            val judge = OpenAiCompatibleJudge(
                label = "stub",
                baseUrl = "http://127.0.0.1:${server.address.port}/v1",
                model = "test-model",
                apiKey = null,
            )
            val answer = judge.look(byteArrayOf(1, 2, 3), "what is this")

            assertEquals("looks like a button", answer)

            val body = json.readTree(seen)
            assertEquals("test-model", body.path("model").asText())
            val content = body.path("messages").first().path("content")
            assertEquals("image_url", content[0].path("type").asText())
            assertTrue(
                content[0].path("image_url").path("url").asText().startsWith("data:image/png;base64,"),
                "The image must go as a data URL; a bare base64 string is silently ignored.",
            )
            assertEquals("what is this", content[1].path("text").asText())
            assertEquals(
                0,
                body.path("temperature").asInt(-1),
                "A judge that invents differently every run cannot be compared against itself.",
            )
        } finally {
            server.stop(0)
        }
    }

    /** The keyless case is the default, so it has to work with no header at all. */
    @Test
    fun `no key means no authorization header`() {
        var authorization: String? = "unset"
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/chat/completions") { exchange ->
                authorization = exchange.requestHeaders.getFirst("authorization")
                val bytes = completion("ok").toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        try {
            OpenAiCompatibleJudge("local", "http://127.0.0.1:${server.address.port}/v1", "m", null)
                .look(null, "hello")
            assertNull(authorization, "A keyless judge must not send an empty bearer token.")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `a key is sent as a bearer token`() {
        var authorization: String? = null
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/chat/completions") { exchange ->
                authorization = exchange.requestHeaders.getFirst("authorization")
                val bytes = completion("ok").toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        try {
            OpenAiCompatibleJudge("provider", "http://127.0.0.1:${server.address.port}/v1", "m", "sk-test")
                .look(null, "hello")
            assertEquals("Bearer sk-test", authorization)
        } finally {
            server.stop(0)
        }
    }

    /**
     * An unreachable judge is not a clean audit.
     *
     * The distinction matters more than it looks: "nobody was there to look" and "somebody looked
     * and found nothing wrong" are opposite results, and collapsing them would let a broken setup
     * read as a passing grade.
     */
    @Test
    fun `an unreachable judge fails loudly rather than reporting nothing wrong`() {
        val judge = OpenAiCompatibleJudge("local", "http://127.0.0.1:1/v1", "m", null)
        assertFailsWith<Exception> { judge.look(null, "hello") }
    }

    @Test
    fun `a refusing judge names who refused and why`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/chat/completions") { exchange ->
                val bytes = """{"error":"model not found"}""".toByteArray()
                exchange.sendResponseHeaders(404, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            start()
        }
        try {
            val judge = OpenAiCompatibleJudge(
                "local",
                "http://127.0.0.1:${server.address.port}/v1",
                "missing-model",
                null,
            )
            val failure = assertFailsWith<JudgeUnreachable> { judge.look(null, "hello") }
            assertTrue(failure.message!!.contains("local/missing-model"))
            assertTrue(failure.message!!.contains("404"))
        } finally {
            server.stop(0)
        }
    }
}
