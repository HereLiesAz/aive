package com.hereliesaz.geministrator.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpJsonRpcTest {
    @Test
    fun framesRequestsAndNotifications() {
        val request = McpJsonRpc.request(7, "tools/list", buildJsonObject { put("cursor", "c1") })
        assertEquals("2.0", request.getValue("jsonrpc").jsonPrimitive.content)
        assertEquals(7L, request.getValue("id").jsonPrimitive.content.toLong())
        assertEquals("tools/list", request.getValue("method").jsonPrimitive.content)
        assertEquals("c1", request.getValue("params").jsonObject.getValue("cursor").jsonPrimitive.content)
        assertTrue(McpJsonRpc.isRequest(request))

        val notification = McpJsonRpc.notification("notifications/initialized")
        assertNull(notification["id"])
        assertNull(notification["params"])
        assertFalse(McpJsonRpc.isRequest(notification))
    }

    @Test
    fun matchesResponsesByNumericOrStringIdAndReadsErrors() {
        val numeric = Json.parseToJsonElement("""{"jsonrpc":"2.0","id":3,"result":{}}""") as JsonObject
        val string = Json.parseToJsonElement("""{"jsonrpc":"2.0","id":"3","error":{"code":-32022,"message":"Unsupported protocol version","data":{"supported":["2025-11-25"]}}}""") as JsonObject
        val notification = Json.parseToJsonElement("""{"jsonrpc":"2.0","method":"notifications/progress","params":{}}""") as JsonObject

        assertTrue(McpJsonRpc.isResponseTo(numeric, 3))
        assertTrue(McpJsonRpc.isResponseTo(string, 3))
        assertFalse(McpJsonRpc.isResponseTo(numeric, 4))
        assertFalse(McpJsonRpc.isResponseTo(notification, 3))
        assertNull(McpJsonRpc.errorOf(numeric))
        val error = McpJsonRpc.errorOf(string)!!
        assertEquals(McpProtocol.UNSUPPORTED_PROTOCOL_VERSION, error.code)
        assertNull(McpJsonRpc.parse("not json"))
        assertNull(McpJsonRpc.parse("[1,2]"))
    }

    @Test
    fun encodesHeaderValuesAsTheSpecificationsTableDoes() {
        assertEquals("us-west1", McpJsonRpc.encodeHeaderValue("us-west1"))
        assertEquals("=?base64?SGVsbG8sIOS4lueVjA==?=", McpJsonRpc.encodeHeaderValue("Hello, 世界"))
        assertEquals("=?base64?IHBhZGRlZCA=?=", McpJsonRpc.encodeHeaderValue(" padded "))
        assertEquals("=?base64?bGluZTEKbGluZTI=?=", McpJsonRpc.encodeHeaderValue("line1\nline2"))
        assertEquals("=?base64?PT9iYXNlNjQ/bGl0ZXJhbD89?=", McpJsonRpc.encodeHeaderValue("=?base64?literal?="))
    }

    @Test
    fun splitsCommandLinesWithoutAShell() {
        assertEquals(listOf("npx", "-y", "@scope/server", "--root", "/my dir"), McpCommandLine.split("npx -y @scope/server --root \"/my dir\""))
        assertEquals(listOf("echo", "\$HOME", "a|b", ""), McpCommandLine.split("echo '\$HOME' a|b ''"))
        assertFailsWith<IllegalArgumentException> { McpCommandLine.split("node \"unclosed") }
        assertFailsWith<IllegalArgumentException> { McpCommandLine.requireUsable(emptyList()) }
        assertFailsWith<IllegalArgumentException> { McpCommandLine.requireUsable(listOf("node", "a\nb")) }
    }

    @Test
    fun refusesUnsafeServerAddresses() {
        McpStreamableHttpTransport.requireUsableEndpoint("https://mcp.example.com/mcp")
        McpStreamableHttpTransport.requireUsableEndpoint("http://127.0.0.1:3000/mcp")
        assertFailsWith<IllegalArgumentException> { McpStreamableHttpTransport.requireUsableEndpoint("http://mcp.example.com/mcp") }
        assertFailsWith<IllegalArgumentException> { McpStreamableHttpTransport.requireUsableEndpoint("https://user:pass@mcp.example.com/mcp") }
        assertFailsWith<IllegalArgumentException> { McpStreamableHttpTransport.requireUsableEndpoint("ftp://mcp.example.com") }
    }

    @Test
    fun validatesParamHeaderAnnotationsAndMirrorsTheirValues() {
        val schema = Json.parseToJsonElement(
            """{"type":"object","properties":{"region":{"type":"string","x-mcp-header":"Region"},
               "opts":{"type":"object","properties":{"tenant":{"type":"integer","x-mcp-header":"Tenant"}}},
               "query":{"type":"string"}}}""",
        ) as JsonObject
        assertNull(McpParamHeaders.problem(schema))
        val arguments = Json.parseToJsonElement("""{"region":"us-west1","opts":{"tenant":42},"query":"SELECT 1"}""") as JsonObject
        assertEquals(
            mapOf("Mcp-Param-Region" to "us-west1", "Mcp-Param-Tenant" to "42"),
            McpParamHeaders.headersFor(schema, arguments),
        )

        val inArray = Json.parseToJsonElement(
            """{"type":"object","properties":{"list":{"type":"array","items":{"type":"string","x-mcp-header":"Item"}}}}""",
        ) as JsonObject
        val onNumber = Json.parseToJsonElement(
            """{"type":"object","properties":{"n":{"type":"number","x-mcp-header":"N"}}}""",
        ) as JsonObject
        val repeated = Json.parseToJsonElement(
            """{"type":"object","properties":{"a":{"type":"string","x-mcp-header":"X"},"b":{"type":"string","x-mcp-header":"x"}}}""",
        ) as JsonObject
        assertTrue(McpParamHeaders.problem(inArray) != null)
        assertTrue(McpParamHeaders.problem(onNumber) != null)
        assertTrue(McpParamHeaders.problem(repeated) != null)
    }
}
