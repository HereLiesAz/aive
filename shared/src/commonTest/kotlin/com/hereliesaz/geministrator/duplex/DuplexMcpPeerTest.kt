package com.hereliesaz.geministrator.duplex

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DuplexMcpPeerTest {
    @Test
    fun sendsProviderNeutralRequestWithOwnCredentialAndDecodesRemoteReceipt() = runBlocking {
        val body = """{"seq":1,"id":"req-4","from":"a","kind":"request","body":"ping","time":42}"""
        val calls = mutableListOf<String>()
        val engine = MockEngine { request ->
            assertEquals("/mcp/a", request.url.encodedPath)
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("Bearer peer-token-a", request.headers[HttpHeaders.Authorization])
            val payload = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals("tools/call", payload.getValue("method").jsonPrimitive.content)
            val params = payload.getValue("params").jsonObject
            calls += params.getValue("name").jsonPrimitive.content
            assertEquals("duplex_send", calls.single())
            val args = params.getValue("arguments").jsonObject
            assertEquals("request", args.getValue("kind").jsonPrimitive.content)
            assertEquals("ping", args.getValue("body").jsonPrimitive.content)
            val reply = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("result", buildJsonObject {
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", body)
                        })
                    })
                })
            }
            respond(reply.toString(), status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val base = HttpClient(engine)
        val peer = DuplexMcpPeer(base, "https://bridge.example/mcp/a", "peer-token-a")
        try {
            val receipt = peer.send(DuplexKind.Request, "ping")
            assertEquals("req-4", receipt.id)
            assertEquals(DuplexKind.Request, receipt.kind)
            assertEquals(DuplexPeer.A, receipt.from)
            assertEquals(42L, receipt.time)
            assertEquals(listOf("duplex_send"), calls)
        } finally {
            peer.close()
            base.close()
        }
    }

    @Test
    fun refusesInsecureRemoteEndpointsAndEmbeddedCredentials() {
        val client = HttpClient(MockEngine { respond("") })
        try {
            assertFailsWith<IllegalArgumentException> {
                DuplexMcpPeer(client, "http://remote.example/mcp/a", "secret")
            }
            assertFailsWith<IllegalArgumentException> {
                DuplexMcpPeer(client, "http://127.0.0.1:8080@evil.example/mcp/a", "secret")
            }
            assertFailsWith<IllegalArgumentException> {
                DuplexMcpPeer(client, "https://user:pass@bridge.example/mcp/b", "secret")
            }
        } finally {
            client.close()
        }
    }
}
