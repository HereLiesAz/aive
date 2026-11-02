package com.hereliesaz.geministrator.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpClientTest {
    private class Seen(val method: String?, val body: JsonObject, val headers: Map<String, String?>)

    private fun HttpRequestData.seen(): Seen {
        val body = Json.parseToJsonElement((this.body as TextContent).text).jsonObject
        val names = listOf(
            McpProtocol.HEADER_PROTOCOL_VERSION, McpProtocol.HEADER_METHOD, McpProtocol.HEADER_NAME,
            McpProtocol.HEADER_SESSION_ID, "Mcp-Param-Region", HttpHeaders.Authorization, HttpHeaders.Accept,
        )
        return Seen(body["method"]?.jsonPrimitive?.content, body, names.associateWith { headers[it] })
    }

    private fun MockRequestHandleScope.json(body: String, status: HttpStatusCode = HttpStatusCode.OK, extra: Map<String, String> = emptyMap()): HttpResponseData =
        respond(
            body,
            status,
            headersOf(*(mapOf(HttpHeaders.ContentType to "application/json") + extra).map { (k, v) -> k to listOf(v) }.toTypedArray()),
        )

    private fun result(id: String, result: String) = """{"jsonrpc":"2.0","id":$id,"result":$result}"""

    @Test
    fun speaksTheModernRevisionWithMetaHeadersAndSseResponses() = runTest {
        withContext(Dispatchers.Default) {
            val seen = mutableListOf<Seen>()
            val engine = MockEngine { request ->
                assertEquals(HttpMethod.Post, request.method)
                val call = request.seen().also { seen += it }
                val id = call.body.getValue("id").toString()
                when (call.method) {
                    "server/discover" -> json(
                        result(id, """{"resultType":"complete","supportedVersions":["2026-07-28"],"capabilities":{"tools":{}},"_meta":{"io.modelcontextprotocol/serverInfo":{"name":"Weather","version":"2"}}}"""),
                    )
                    "tools/list" -> json(
                        result(
                            id,
                            """{"resultType":"complete","tools":[
                              {"name":"get_weather","description":"Weather now","inputSchema":{"type":"object","properties":{"region":{"type":"string","x-mcp-header":"Region"},"city":{"type":"string"}}}},
                              {"name":"broken","inputSchema":{"type":"object","properties":{"xs":{"type":"array","items":{"type":"string","x-mcp-header":"X"}}}}}
                            ]}""",
                        ),
                    )
                    "tools/call" -> respond(
                        "event: message\n" +
                            "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{\"progress\":1}}\n\n" +
                            ": keep-alive\n" +
                            "data: " + result(id, """{"resultType":"complete","content":[{"type":"text","text":"Sunny, 21°C"},{"type":"image","data":"AA==","mimeType":"image/png"}],"isError":false}""") + "\n\n",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, "text/event-stream"),
                    )
                    else -> error("unexpected ${call.method}")
                }
            }
            val base = HttpClient(engine)
            val client = McpClient(McpStreamableHttpTransport(base, "https://weather.example/mcp", bearerToken = "tok"))

            assertEquals(McpEra.Modern(McpProtocol.MODERN_VERSION), client.connect())
            assertEquals("Weather", client.serverInfo?.name)
            val tools = client.listTools()
            assertEquals(listOf("get_weather"), tools.map(McpTool::name), "a tool whose header annotation breaks the rule is dropped")
            val outcome = client.callTool("get_weather", buildJsonObject { put("region", "eu-west"); put("city", "Lyon") })

            assertEquals("Sunny, 21°C\n[image image/png omitted]", outcome.renderText())
            assertEquals(false, outcome.isError)
            assertEquals(listOf("server/discover", "tools/list", "tools/call"), seen.map { it.method })
            seen.forEach { call ->
                assertEquals(McpProtocol.MODERN_VERSION, call.headers[McpProtocol.HEADER_PROTOCOL_VERSION])
                assertEquals(call.method, call.headers[McpProtocol.HEADER_METHOD])
                assertEquals("Bearer tok", call.headers[HttpHeaders.Authorization])
                assertTrue(call.headers[HttpHeaders.Accept].orEmpty().contains("text/event-stream"))
                val meta = call.body.getValue("params").jsonObject.getValue("_meta").jsonObject
                assertEquals(McpProtocol.MODERN_VERSION, meta.getValue(McpProtocol.META_PROTOCOL_VERSION).jsonPrimitive.content)
                assertEquals(
                    McpClient.clientCapabilities(elicitation = false),
                    meta.getValue(McpProtocol.META_CLIENT_CAPABILITIES),
                    "the Tasks extension only: no elicitation without a keeper, never sampling or roots",
                )
                assertEquals("The Aive", meta.getValue(McpProtocol.META_CLIENT_INFO).jsonObject.getValue("name").jsonPrimitive.content)
            }
            val toolCall = seen.last()
            assertEquals("get_weather", toolCall.headers[McpProtocol.HEADER_NAME])
            assertEquals("eu-west", toolCall.headers["Mcp-Param-Region"])
            assertEquals("Lyon", toolCall.body.getValue("params").jsonObject.getValue("arguments").jsonObject.getValue("city").jsonPrimitive.content)
            client.close()
            base.close()
        }
    }

    @Test
    fun fallsBackToTheInitializeHandshakeForALegacyServer() = runTest {
        withContext(Dispatchers.Default) {
            val seen = mutableListOf<Seen>()
            val engine = MockEngine { request ->
                val call = request.seen().also { seen += it }
                val id = call.body["id"]?.toString()
                when (call.method) {
                    "server/discover" -> json(
                        """{"jsonrpc":"2.0","id":"server-error","error":{"code":-32000,"message":"Bad Request: No valid session ID provided"}}""",
                        HttpStatusCode.BadRequest,
                    )
                    "initialize" -> {
                        val params = call.body.getValue("params").jsonObject
                        assertEquals(McpProtocol.LEGACY_VERSIONS.first(), params.getValue("protocolVersion").jsonPrimitive.content)
                        assertEquals(JsonObject(emptyMap()), params.getValue("capabilities"), "a handshake-era server is offered nothing")
                        json(
                            result(id!!, """{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"Old","version":"1"}}"""),
                            extra = mapOf(McpProtocol.HEADER_SESSION_ID to "s-1"),
                        )
                    }
                    "notifications/initialized" -> respond("", HttpStatusCode.Accepted)
                    "tools/list" -> json(result(id!!, """{"tools":[{"name":"echo","inputSchema":{"type":"object"}}]}"""))
                    else -> error("unexpected ${call.method}")
                }
            }
            val base = HttpClient(engine)
            val client = McpClient(McpStreamableHttpTransport(base, "https://old.example/mcp"))

            assertEquals(McpEra.Legacy("2025-06-18", "s-1"), client.connect())
            assertEquals(listOf("echo"), client.listTools().map(McpTool::name))
            assertEquals("Old", client.serverInfo?.name)

            assertEquals(listOf("server/discover", "initialize", "notifications/initialized", "tools/list"), seen.map { it.method })
            val list = seen.last()
            assertEquals("s-1", list.headers[McpProtocol.HEADER_SESSION_ID])
            assertEquals("2025-06-18", list.headers[McpProtocol.HEADER_PROTOCOL_VERSION])
            assertNull(list.body.getValue("params").jsonObject["_meta"], "legacy requests carry no per-request metadata")
            assertNull(list.headers[HttpHeaders.Authorization])
            base.close()
        }
    }

    @Test
    fun aModernVersionRefusalIsNotMistakenForALegacyServer() = runTest {
        withContext(Dispatchers.Default) {
            val methods = mutableListOf<String?>()
            val engine = MockEngine { request ->
                methods += request.seen().method
                json(
                    """{"jsonrpc":"2.0","id":1,"error":{"code":-32022,"message":"Unsupported protocol version","data":{"supported":["2030-01-01"],"requested":"2026-07-28"}}}""",
                    HttpStatusCode.BadRequest,
                )
            }
            val base = HttpClient(engine)
            val client = McpClient(McpStreamableHttpTransport(base, "https://future.example/mcp"))
            val failure = assertFailsWith<McpProtocolException> { client.connect() }
            assertTrue(failure.message.orEmpty().contains("2030-01-01"))
            assertEquals(listOf<String?>("server/discover"), methods, "no initialize fallback after a modern error")
            base.close()
        }
    }

    @Test
    fun anInputRequiredResultEndsACallWithNoKeeperToAsk() = runTest {
        withContext(Dispatchers.Default) {
            val engine = MockEngine { request ->
                val call = request.seen()
                val id = call.body.getValue("id").toString()
                when (call.method) {
                    "server/discover" -> json(result(id, """{"supportedVersions":["2026-07-28"]}"""))
                    else -> json(result(id, """{"resultType":"input_required","inputRequests":{"login":{"method":"elicitation/create","params":{}}}}"""))
                }
            }
            val base = HttpClient(engine)
            val client = McpClient(McpStreamableHttpTransport(base, "https://ask.example/mcp"))
            val failure = assertFailsWith<McpInputRequiredException> { client.callTool("login", JsonObject(emptyMap())) }
            assertIs<JsonObject>(failure.result["inputRequests"])
            base.close()
        }
    }
}
