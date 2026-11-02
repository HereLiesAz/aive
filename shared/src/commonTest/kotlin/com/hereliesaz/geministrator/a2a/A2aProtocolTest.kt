package com.hereliesaz.geministrator.a2a

import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class A2aProtocolTest {
    private val card = A2aAgentCard(
        name = "The Aive",
        description = "Runs a hive",
        version = "1.2.3",
        interfaces = listOf(A2aAgentInterface("http://127.0.0.1:41241/a2a")),
        skills = listOf(A2aSkill("hive", "Run a hive", "Plan and run", listOf("workflow"), listOf("Do it"))),
        bearerAuth = true,
    )

    @Test
    fun agentCardHasTheCurrentSpecShape() {
        val json = A2aCodec.encodeCard(card)
        assertEquals("The Aive", json.string("name"))
        assertEquals("1.2.3", json.string("version"))
        val endpoint = json["supportedInterfaces"]!!.jsonArray.single().jsonObject
        assertEquals("http://127.0.0.1:41241/a2a", endpoint.string("url"))
        assertEquals("JSONRPC", endpoint.string("protocolBinding"))
        assertEquals("1.0", endpoint.string("protocolVersion"))
        val capabilities = json["capabilities"]!!.jsonObject
        assertEquals("false", capabilities["streaming"]!!.jsonPrimitive.content)
        assertEquals("false", capabilities["pushNotifications"]!!.jsonPrimitive.content)
        val scheme = json["securitySchemes"]!!.jsonObject["bearer"]!!.jsonObject["httpAuthSecurityScheme"]!!.jsonObject
        assertEquals("Bearer", scheme.string("scheme"))
        val requirement = json["securityRequirements"]!!.jsonArray.single().jsonObject["schemes"]!!.jsonObject
        assertTrue("bearer" in requirement)
        assertEquals(listOf("text/plain"), json["defaultInputModes"]!!.jsonArray.map { it.jsonPrimitive.content })
        val skill = json["skills"]!!.jsonArray.single().jsonObject
        assertEquals("hive", skill.string("id"))
        assertEquals(listOf("workflow"), skill["tags"]!!.jsonArray.map { it.jsonPrimitive.content })
        // 1.0 has no legacy top-level url or kind.
        assertFalse("url" in json)
        assertFalse("kind" in json)
    }

    @Test
    fun cardRoundTripsAndReadsLegacyCards() {
        val decoded = A2aCodec.decodeCard(A2aCodec.encodeCard(card))
        assertEquals(card, decoded)
        assertEquals("http://127.0.0.1:41241/a2a" to A2aVersion.V1_0, decoded.jsonRpcEndpoint())

        val legacy = Json.parseToJsonElement(
            """
            {"protocolVersion":"0.3.0","name":"Old","description":"d","url":"https://old.example/rpc",
             "preferredTransport":"JSONRPC","version":"1","capabilities":{"streaming":true},
             "securitySchemes":{"auth":{"type":"http","scheme":"bearer"}},
             "defaultInputModes":["text/plain"],"defaultOutputModes":["text/plain"],"skills":[]}
            """.trimIndent(),
        )
        val old = A2aCodec.decodeCard(legacy)
        assertEquals("https://old.example/rpc" to A2aVersion.V0_3, old.jsonRpcEndpoint())
        assertTrue(old.bearerAuth)
        assertTrue(old.streaming)
    }

    @Test
    fun cardWithoutJsonRpcHasNoEndpoint() {
        val grpcOnly = card.copy(interfaces = listOf(A2aAgentInterface("https://x.example", protocolBinding = "GRPC")))
        assertNull(grpcOnly.jsonRpcEndpoint())
    }

    @Test
    fun messagesEncodeInBothVersionsAndDecodeEitherShape() {
        val message = A2aMessage(
            messageId = "m1",
            role = A2aRole.User,
            parts = listOf(
                A2aPart.Text("hello"),
                A2aPart.File(url = "https://files.example/a.txt", mediaType = "text/plain", filename = "a.txt"),
                A2aPart.Data(JsonObject(mapOf("n" to JsonPrimitive(1)))),
            ),
            contextId = "c1",
        )
        val current = A2aCodec.encodeMessage(message, A2aVersion.V1_0)
        assertEquals("ROLE_USER", current.string("role"))
        assertFalse("kind" in current)
        val currentParts = current["parts"]!!.jsonArray
        assertEquals("hello", currentParts[0].jsonObject.string("text"))
        assertEquals("https://files.example/a.txt", currentParts[1].jsonObject.string("url"))
        assertEquals("a.txt", currentParts[1].jsonObject.string("filename"))
        assertTrue("data" in currentParts[2].jsonObject)

        val legacy = A2aCodec.encodeMessage(message, A2aVersion.V0_3)
        assertEquals("message", legacy.string("kind"))
        assertEquals("user", legacy.string("role"))
        val legacyParts = legacy["parts"]!!.jsonArray
        assertEquals("text", legacyParts[0].jsonObject.string("kind"))
        assertEquals("file", legacyParts[1].jsonObject.string("kind"))
        assertEquals("https://files.example/a.txt", legacyParts[1].jsonObject["file"]!!.jsonObject.string("uri"))
        assertEquals("text/plain", legacyParts[1].jsonObject["file"]!!.jsonObject.string("mimeType"))

        assertEquals(message, A2aCodec.decodeMessage(current))
        assertEquals(message, A2aCodec.decodeMessage(legacy))
    }

    @Test
    fun tasksRoundTripInBothVersions() {
        val task = A2aTask(
            id = "t1",
            contextId = "c1",
            status = A2aTaskStatus(A2aTaskState.InputRequired, A2aMessage("s1", A2aRole.Agent, listOf(A2aPart.Text("which file?")))),
            artifacts = listOf(A2aArtifact("a1", listOf(A2aPart.Text("result")), name = "Report")),
        )
        val current = A2aCodec.encodeTask(task, A2aVersion.V1_0)
        assertEquals("TASK_STATE_INPUT_REQUIRED", current["status"]!!.jsonObject.string("state"))
        val legacy = A2aCodec.encodeTask(task, A2aVersion.V0_3)
        assertEquals("input-required", legacy["status"]!!.jsonObject.string("state"))
        assertEquals("task", legacy.string("kind"))
        assertEquals(task, A2aCodec.decodeTask(current))
        assertEquals(task, A2aCodec.decodeTask(legacy))
    }

    @Test
    fun sendResultsAreReadInEitherShape() {
        val task = A2aTask("t1", "c1", A2aTaskStatus(A2aTaskState.Submitted))
        val reply = A2aMessage("m1", A2aRole.Agent, listOf(A2aPart.Text("done")))
        for (version in A2aVersion.entries) {
            val wrappedTask = A2aCodec.encodeSendResult(A2aSendResult.TaskResult(task), version)
            assertEquals(A2aSendResult.TaskResult(task), A2aCodec.decodeSendResult(wrappedTask))
            val wrappedReply = A2aCodec.encodeSendResult(A2aSendResult.MessageResult(reply), version)
            assertEquals(A2aSendResult.MessageResult(reply), A2aCodec.decodeSendResult(wrappedReply))
        }
        assertIs<JsonObject>(A2aCodec.encodeSendResult(A2aSendResult.TaskResult(task), A2aVersion.V1_0)["task"])
    }

    @Test
    fun taskStatesParseBothSpellingsAndUnknownIsNeverSuccess() {
        for (state in A2aTaskState.entries) {
            assertEquals(state, A2aTaskState.parse(state.wire(A2aVersion.V0_3)))
            assertEquals(state, A2aTaskState.parse(state.wire(A2aVersion.V1_0)))
        }
        assertEquals(A2aTaskState.Canceled, A2aTaskState.parse("cancelled"))
        assertEquals(A2aTaskState.Unknown, A2aTaskState.parse("finished-ish"))
        assertEquals(A2aTaskState.Unknown, A2aTaskState.parse(null))
        assertFalse(A2aTaskState.Unknown.isTerminal)
        assertEquals(
            setOf(A2aTaskState.Completed, A2aTaskState.Canceled, A2aTaskState.Failed, A2aTaskState.Rejected),
            A2aTaskState.entries.filter { it.isTerminal }.toSet(),
        )
    }

    @Test
    fun workflowRunStatusesMapToTaskStates() {
        assertEquals(A2aTaskState.Submitted, a2aStateOf(WorkflowRunStatus.Created))
        assertEquals(A2aTaskState.Working, a2aStateOf(WorkflowRunStatus.Running))
        // The remote client cannot answer The Aive's approval gates, so this is not input-required.
        assertEquals(A2aTaskState.Working, a2aStateOf(WorkflowRunStatus.AwaitingHuman))
        assertEquals(A2aTaskState.Completed, a2aStateOf(WorkflowRunStatus.Completed))
        assertEquals(A2aTaskState.Failed, a2aStateOf(WorkflowRunStatus.Failed))
        assertEquals(A2aTaskState.Canceled, a2aStateOf(WorkflowRunStatus.Cancelled))
    }

    @Test
    fun methodNamesResolveForBothVersions() {
        assertEquals(A2aOperation.SendMessage to A2aVersion.V1_0, A2aMethods.resolve("SendMessage"))
        assertEquals(A2aOperation.SendMessage to A2aVersion.V0_3, A2aMethods.resolve("message/send"))
        assertEquals(A2aOperation.GetTask to A2aVersion.V0_3, A2aMethods.resolve("tasks/get"))
        assertEquals(A2aOperation.CancelTask to A2aVersion.V1_0, A2aMethods.resolve("CancelTask"))
        assertEquals("message/send", A2aMethods.name(A2aOperation.SendMessage, A2aVersion.V0_3))
        assertEquals("GetTask", A2aMethods.name(A2aOperation.GetTask, A2aVersion.V1_0))
        assertNull(A2aMethods.resolve("tasks/delete"))
    }

    @Test
    fun rpcErrorsBecomeExceptions() {
        val error = A2aCodec.rpcError(JsonPrimitive(7), A2aErrorCodes.TASK_NOT_FOUND, "nope")
        val failure = assertFailsWith<A2aRpcException> { A2aCodec.rpcResultOf(error) }
        assertEquals(A2aErrorCodes.TASK_NOT_FOUND, failure.code)
        val ok = A2aCodec.rpcResult(JsonPrimitive(7), JsonArray(emptyList()))
        assertEquals(JsonArray(emptyList()), A2aCodec.rpcResultOf(ok))
    }
}
