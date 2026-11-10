package com.hereliesaz.geministrator.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class McpTransportBoundsTest {
    private val request = McpOutgoing(buildJsonObject { put("jsonrpc", "2.0"); put("id", 1); put("method", "ping") })

    private fun transport(body: String, type: String, maxBytes: Int) = McpStreamableHttpTransport(
        HttpClient(MockEngine { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, type)) }),
        "https://mcp.example/mcp", maxResponseBytes = maxBytes,
    )

    @Test
    fun theCapCountsBytesNotCharacters() = runTest {
        // 60 characters of 3 bytes each is 180 bytes of text: over a 100 byte cap, under a 100 character one.
        val body = """{"jsonrpc":"2.0","id":1,"result":{"p":"${"€".repeat(60)}"}}"""
        assertFailsWith<McpProtocolException> { transport(body, "application/json", 100).exchange(request) }
        assertFailsWith<McpProtocolException> {
            transport("data: $body\n\n", "text/event-stream", 100).exchange(request)
        }
    }

    @Test
    fun aSingleEndlessLineIsRefusedWithoutAssemblingIt() = runTest {
        val noNewline = "x".repeat(2_000_000)
        assertFailsWith<McpProtocolException> { transport(noNewline, "application/json", 10_000).exchange(request) }
        assertFailsWith<McpProtocolException> { transport("data: $noNewline", "text/event-stream", 10_000).exchange(request) }
    }

    @Test
    fun aResponseWithinTheCapIsStillRead() = runTest {
        val body = """{"jsonrpc":"2.0","id":1,"result":{}}"""
        val incoming = transport(body, "application/json", 1_000).exchange(request)
        assertEquals(200, incoming.status)
        assertNotNull(incoming.message)
        val sse = transport("event: message\ndata: $body\n\n", "text/event-stream", 1_000).exchange(request)
        assertNotNull(sse.message)
    }
}
