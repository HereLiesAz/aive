package com.hereliesaz.geministrator.mcp

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The Tasks extension (SEP-2663): a `tools/call` answered with a task handle. */
@OptIn(ExperimentalCoroutinesApi::class)
class McpTaskTest {
    private val taskId = "786512e2-9e0d-44bd-8f29-789f320fe840"

    private fun task(status: String, extra: String = "", resultType: String = "complete") =
        """{"resultType":"$resultType","taskId":"$taskId","status":"$status","createdAt":"2026-11-02T10:30:00Z","lastUpdatedAt":"2026-11-02T10:30:00Z","ttlMs":3600000,"pollIntervalMs":2000$extra}"""

    @Test
    fun aTaskIsPolledToItsResultAndItsInputIsAskedOnce() = runTest {
        var polls = 0
        val server = ScriptedMcpServer { method, params ->
            when (method) {
                "tools/call" -> task("working", resultType = "task")
                "tasks/get" -> {
                    assertEquals(taskId, params.getValue("taskId").jsonPrimitive.content)
                    when (++polls) {
                        1 -> task("working")
                        // The same outstanding request on two polls: the keeper is asked once.
                        2, 3 -> task(
                            "input_required",
                            ""","inputRequests":{"name":{"method":"elicitation/create","params":{"mode":"form","message":"Your name?","requestedSchema":{"type":"object","properties":{"name":{"type":"string"}},"required":["name"]}}}}""",
                        )
                        4 -> task("working")
                        else -> task("completed", ""","result":{"content":[{"type":"text","text":"Hello, Luca!"}],"isError":false}""")
                    }
                }
                "tasks/update" -> """{"resultType":"complete"}"""
                else -> error("unexpected $method")
            }
        }
        var asked = 0
        val result = McpClient(server).callTool("hello_world", JsonObject(emptyMap())) {
            asked++
            McpElicitationResponse(McpElicitationAction.Accept, buildJsonObject { put("name", "Luca") }).toJson()
        }

        assertEquals("Hello, Luca!", result.renderText())
        assertFalse(result.isError)
        assertEquals(1, asked)
        assertEquals(listOf("server/discover", "tools/call", "tasks/get", "tasks/get", "tasks/update", "tasks/get", "tasks/get", "tasks/get"), server.methods)
        assertEquals(
            Json.parseToJsonElement("""{"name":{"action":"accept","content":{"name":"Luca"}}}"""),
            server.params("tasks/update").single().getValue("inputResponses"),
        )
        assertFalse("tasks/cancel" in server.methods, "a finished task is not cancelled")
        listOf("tasks/get", "tasks/update").forEach { method ->
            server.headers(method).forEach { headers ->
                assertEquals(taskId, headers[McpProtocol.HEADER_NAME], "tasks are routed by their id")
                assertEquals(method, headers[McpProtocol.HEADER_METHOD])
                assertEquals(McpProtocol.MODERN_VERSION, headers[McpProtocol.HEADER_PROTOCOL_VERSION])
            }
            server.params(method).forEach { params ->
                val capabilities = params.getValue("_meta").jsonObject.getValue(McpProtocol.META_CLIENT_CAPABILITIES).jsonObject
                assertNotNull(capabilities.getValue("extensions").jsonObject[McpProtocol.EXTENSION_TASKS], "every task request declares the extension")
            }
        }
    }

    @Test
    fun pollingWaitsAtLeastTheServersIntervalAndBacksOff() = runTest {
        val polledAt = mutableListOf<Long>()
        val server = ScriptedMcpServer { method, _ ->
            when (method) {
                "tools/call" -> task("working", resultType = "task")
                else -> {
                    polledAt += currentTime
                    if (polledAt.size < 6) task("working") else task("completed", ""","result":{"content":[]}""")
                }
            }
        }
        McpClient(server).callTool("slow", JsonObject(emptyMap()))
        val gaps = polledAt.zipWithNext { a, b -> b - a }
        assertEquals(2_000L, polledAt.first(), "the server's 2 s interval beats the 1 s first backoff")
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 15_000L, 15_000L), gaps, "then the backoff doubles up to its ceiling")
    }

    @Test
    fun aFailedTaskSurfacesItsJsonRpcError() = runTest {
        val server = ScriptedMcpServer { method, _ ->
            when (method) {
                "tools/call" -> task("working", resultType = "task")
                else -> task("failed", ""","statusMessage":"rate limited","error":{"code":-32603,"message":"API rate limit exceeded"}""")
            }
        }
        val failure = assertFailsWith<McpRpcException> { McpClient(server).callTool("t", JsonObject(emptyMap())) }
        assertEquals(-32603, failure.code)
        assertFalse("tasks/cancel" in server.methods)
    }

    @Test
    fun aRunCancelledWhileWaitingCancelsTheTask() = runTest {
        val server = ScriptedMcpServer { method, _ ->
            when (method) {
                "tools/call" -> task("working", resultType = "task")
                "tasks/get" -> task("working")
                "tasks/cancel" -> """{"resultType":"complete"}"""
                else -> error("unexpected $method")
            }
        }
        val client = McpClient(server)
        val run = launch { client.callTool("t", JsonObject(emptyMap())) }
        advanceTimeBy(10_000)
        run.cancelAndJoin()

        assertEquals("tasks/cancel", server.methods.last())
        assertEquals(taskId, server.params("tasks/cancel").single().getValue("taskId").jsonPrimitive.content)
        assertEquals(taskId, server.headers("tasks/cancel").single()[McpProtocol.HEADER_NAME])
    }

    @Test
    fun aTaskThatNeverEndsIsGivenUpAndCancelled() = runTest {
        val server = ScriptedMcpServer { method, _ ->
            when (method) {
                "tools/call" -> task("working", resultType = "task")
                "tasks/cancel" -> """{"resultType":"complete"}"""
                else -> task("working")
            }
        }
        val failure = assertFailsWith<McpTaskException> {
            McpClient(server, taskTimeoutMillis = 120_000).callTool("t", JsonObject(emptyMap()))
        }
        assertTrue(failure.message.orEmpty().contains("did not finish"))
        assertEquals("tasks/cancel", server.methods.last())
        assertTrue(server.methods.count { it == "tasks/get" } < 20, "bounded polling, not a busy loop")
    }

    @Test
    fun inputATaskCannotGetCancelsIt() = runTest {
        val server = ScriptedMcpServer { method, _ ->
            when (method) {
                "tools/call" -> task("working", resultType = "task")
                "tasks/cancel" -> """{"resultType":"complete"}"""
                else -> task("input_required", ""","inputRequests":{"s":{"method":"sampling/createMessage","params":{"messages":[],"maxTokens":5}}}""")
            }
        }
        assertFailsWith<McpInputRequiredException> {
            McpClient(server).callTool("t", JsonObject(emptyMap())) { McpElicitationResponse.Cancel.toJson() }
        }
        assertEquals("tasks/cancel", server.methods.last())
    }

    @Test
    fun aServerCancelledTaskEndsTheCallWithoutAnotherCancel() = runTest {
        val server = ScriptedMcpServer { method, _ ->
            when (method) {
                "tools/call" -> task("working", resultType = "task")
                else -> task("cancelled")
            }
        }
        assertFailsWith<McpTaskException> { McpClient(server).callTool("t", JsonObject(emptyMap())) }
        assertFalse("tasks/cancel" in server.methods)
    }

    @Test
    fun aTaskFromAHandshakeEraServerIsRefused() = runTest {
        val legacy = object : McpTransport {
            override val kind = McpTransportKind.Stdio
            override suspend fun exchange(outgoing: McpOutgoing): McpIncoming {
                val message = outgoing.message
                val id = message["id"]?.jsonPrimitive?.content ?: return McpIncoming(null, null)
                val reply = when (message.getValue("method").jsonPrimitive.content) {
                    "server/discover" -> """{"jsonrpc":"2.0","id":$id,"error":{"code":-32601,"message":"Method not found"}}"""
                    "initialize" -> """{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":"2025-11-25","capabilities":{},"serverInfo":{"name":"Old","version":"1"}}}"""
                    else -> """{"jsonrpc":"2.0","id":$id,"result":${task("working", resultType = "task")}}"""
                }
                return McpIncoming(null, Json.parseToJsonElement(reply).jsonObject)
            }

            override suspend fun close() = Unit
        }
        assertFailsWith<McpProtocolException> { McpClient(legacy).callTool("t", JsonObject(emptyMap())) }
    }
}
