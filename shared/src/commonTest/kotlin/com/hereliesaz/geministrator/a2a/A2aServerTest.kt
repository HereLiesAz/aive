package com.hereliesaz.geministrator.a2a

import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class A2aServerTest {
    private class FakeHost : A2aWorkflowHost {
        var busy = false
        val runs = mutableMapOf<String, Pair<WorkflowRun, WorkflowDefinition?>>()
        val cancelled = mutableListOf<String>()

        override suspend fun run(runId: String) = runs[runId]
        override suspend fun busy() = busy
        override suspend fun cancel(runId: String): Boolean {
            val (run, definition) = runs[runId] ?: return false
            if (run.status == WorkflowRunStatus.Completed) return false
            cancelled += runId
            runs[runId] = run.copy(status = WorkflowRunStatus.Cancelled) to definition
            return true
        }
        override suspend fun roles(): List<RoleDefinition> = listOf(BuiltInRoles.Researcher, BuiltInRoles.Architect.copy(enabled = false))
    }

    private val host = FakeHost()
    private var ids = 0
    private val server = A2aServer(
        host = host,
        token = { TOKEN },
        baseUrl = { "http://127.0.0.1:41241" },
        agentVersion = "9.9",
        nowIso = { "2026-11-02T00:00:00Z" },
        idFactory = { "id-${++ids}" },
    )
    private val auth = mapOf("Host" to "127.0.0.1:41241", "Authorization" to "Bearer $TOKEN")

    private suspend fun rpc(method: String, params: JsonObject, headers: Map<String, String> = auth): JsonObject {
        val body = A2aCodec.rpcRequest(JsonPrimitive(1), method, params).toString()
        val response = server.handle("POST", "/a2a", headers, body)
        assertEquals(200, response.status)
        return Json.parseToJsonElement(response.body).jsonObject
    }

    private fun sendParams(text: String, version: A2aVersion = A2aVersion.V1_0) = buildJsonObject {
        put("message", A2aCodec.encodeMessage(A2aMessage("m-1", A2aRole.User, listOf(A2aPart.Text(text))), version))
    }

    @Test
    fun agentCardIsPublicAndListsTheHiveAndEnabledRoles() = runTest {
        val response = server.handle("GET", A2aCodec.WELL_KNOWN_CARD_PATH, mapOf("Host" to "localhost:41241"), "")
        assertEquals(200, response.status)
        val card = A2aCodec.decodeCard(Json.parseToJsonElement(response.body))
        assertEquals("The Aive", card.name)
        assertEquals("9.9", card.version)
        assertEquals("http://127.0.0.1:41241/a2a" to A2aVersion.V1_0, card.jsonRpcEndpoint())
        assertTrue(card.bearerAuth)
        assertEquals(listOf("hive", "role:researcher"), card.skills.map { it.id })
    }

    @Test
    fun rpcWithoutTheRightTokenIsRefused() = runTest {
        val body = A2aCodec.rpcRequest(JsonPrimitive(1), "SendMessage", sendParams("do it")).toString()
        val missing = server.handle("POST", "/a2a", mapOf("Host" to "127.0.0.1:41241"), body)
        assertEquals(401, missing.status)
        assertEquals("Bearer", missing.headers["WWW-Authenticate"])
        val wrong = server.handle("POST", "/a2a", mapOf("Host" to "127.0.0.1:41241", "Authorization" to "Bearer nope"), body)
        assertEquals(401, wrong.status)
        val basic = server.handle("POST", "/a2a", mapOf("Host" to "127.0.0.1:41241", "Authorization" to "Basic $TOKEN"), body)
        assertEquals(401, basic.status)
        assertTrue(server.pendingLaunches.value.isEmpty())
    }

    @Test
    fun noTokenConfiguredRefusesEverything() = runTest {
        val closed = A2aServer(host, token = { null }, baseUrl = { "http://127.0.0.1:1" }, agentVersion = "1")
        val body = A2aCodec.rpcRequest(JsonPrimitive(1), "GetTask", buildJsonObject { put("id", "x") }).toString()
        assertEquals(401, closed.handle("POST", "/a2a", mapOf("Host" to "127.0.0.1:1", "Authorization" to "Bearer "), body).status)
    }

    @Test
    fun foreignHostHeaderIsRefusedOnLoopback() = runTest {
        val response = server.handle("GET", A2aCodec.WELL_KNOWN_CARD_PATH, mapOf("Host" to "evil.example"), "")
        assertEquals(403, response.status)
    }

    @Test
    fun sendMessageQueuesALaunchAndGetTaskFollowsTheRun() = runTest {
        val sent = rpc("SendMessage", sendParams("Write a design note"))
        val task = A2aCodec.decodeTask(sent["result"]!!.jsonObject["task"])!!
        assertEquals(A2aTaskState.Submitted, task.status.state)
        val queued = server.pendingLaunches.value.single()
        assertEquals("Write a design note", queued.objective)
        assertEquals("A2A: Write a design note", queued.projectName)

        server.launchHandled(queued.requestId, failure = null, runIdAfter = "run-1")
        assertTrue(server.pendingLaunches.value.isEmpty())
        host.runs["run-1"] = run(WorkflowRunStatus.Running) to definition()
        val working = A2aCodec.decodeTask(rpc("GetTask", buildJsonObject { put("id", task.id) })["result"])!!
        assertEquals(A2aTaskState.Working, working.status.state)
        assertTrue(working.artifacts.isEmpty())

        host.runs["run-1"] = run(WorkflowRunStatus.Completed) to definition()
        val done = A2aCodec.decodeTask(rpc("GetTask", buildJsonObject { put("id", task.id) })["result"])!!
        assertEquals(A2aTaskState.Completed, done.status.state)
        // Only the final task's output, as "What it made" shows it.
        assertEquals(listOf("The note"), done.artifacts.flatMap { it.parts }.map { (it as A2aPart.Text).text })
        assertEquals("Write a design note", done.history.single().text)
    }

    @Test
    fun legacyMethodNamesGetLegacyShapes() = runTest {
        val sent = rpc("message/send", sendParams("Legacy please", A2aVersion.V0_3))
        val result = sent["result"]!!.jsonObject
        assertEquals("task", result["kind"]!!.jsonPrimitive.content)
        assertEquals("submitted", result["status"]!!.jsonObject["state"]!!.jsonPrimitive.content)
        val got = rpc("tasks/get", buildJsonObject { put("id", result["id"]!!.jsonPrimitive.content) })
        assertEquals("submitted", got["result"]!!.jsonObject["status"]!!.jsonObject["state"]!!.jsonPrimitive.content)
    }

    @Test
    fun busyRuntimeRejectsInsteadOfReplacingTheRun() = runTest {
        host.busy = true
        val task = A2aCodec.decodeTask(rpc("SendMessage", sendParams("Another thing"))["result"])!!
        assertEquals(A2aTaskState.Rejected, task.status.state)
        assertTrue(server.pendingLaunches.value.isEmpty())
    }

    @Test
    fun oneQueuedLaunchAtATime() = runTest {
        rpc("SendMessage", sendParams("First"))
        val second = A2aCodec.decodeTask(rpc("SendMessage", sendParams("Second"))["result"])!!
        assertEquals(A2aTaskState.Rejected, second.status.state)
        assertEquals(1, server.pendingLaunches.value.size)
    }

    @Test
    fun failedOrEmptyLaunchFailsTheTask() = runTest {
        server.runtimeShowsRun("run-old")
        val first = A2aCodec.decodeTask(rpc("SendMessage", sendParams("One"))["result"])!!
        server.launchHandled(server.pendingLaunches.value.single().requestId, failure = null, runIdAfter = "run-old")
        val stillOld = A2aCodec.decodeTask(rpc("GetTask", buildJsonObject { put("id", first.id) })["result"])!!
        assertEquals(A2aTaskState.Failed, stillOld.status.state)

        val second = A2aCodec.decodeTask(rpc("SendMessage", sendParams("Two"))["result"])!!
        server.launchHandled(server.pendingLaunches.value.single().requestId, failure = "No provider", runIdAfter = null)
        val failed = A2aCodec.decodeTask(rpc("GetTask", buildJsonObject { put("id", second.id) })["result"])!!
        assertEquals(A2aTaskState.Failed, failed.status.state)
        assertEquals("No provider", failed.status.message?.text)
    }

    @Test
    fun cancelTaskCancelsTheQueuedLaunchOrTheRun() = runTest {
        val queued = A2aCodec.decodeTask(rpc("SendMessage", sendParams("Queued"))["result"])!!
        val cancelledQueued = A2aCodec.decodeTask(rpc("CancelTask", buildJsonObject { put("id", queued.id) })["result"])!!
        assertEquals(A2aTaskState.Canceled, cancelledQueued.status.state)
        assertTrue(server.pendingLaunches.value.isEmpty())

        val running = A2aCodec.decodeTask(rpc("SendMessage", sendParams("Running"))["result"])!!
        server.launchHandled(server.pendingLaunches.value.single().requestId, null, "run-2")
        host.runs["run-2"] = run(WorkflowRunStatus.Running, id = "run-2") to definition()
        val cancelled = A2aCodec.decodeTask(rpc("CancelTask", buildJsonObject { put("id", running.id) })["result"])!!
        assertEquals(A2aTaskState.Canceled, cancelled.status.state)
        assertEquals(listOf("run-2"), host.cancelled)

        val again = rpc("CancelTask", buildJsonObject { put("id", running.id) })
        assertEquals(A2aErrorCodes.TASK_NOT_CANCELABLE, again["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun errorsUseA2aCodes() = runTest {
        val missing = rpc("GetTask", buildJsonObject { put("id", "nope") })
        assertEquals(A2aErrorCodes.TASK_NOT_FOUND, missing["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
        val unknown = rpc("tasks/delete", buildJsonObject {})
        assertEquals(A2aErrorCodes.METHOD_NOT_FOUND, unknown["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
        val stream = rpc("SendStreamingMessage", sendParams("x"))
        assertEquals(A2aErrorCodes.UNSUPPORTED_OPERATION, stream["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
        val push = rpc("tasks/pushNotificationConfig/set", buildJsonObject {})
        assertEquals(A2aErrorCodes.PUSH_NOTIFICATION_NOT_SUPPORTED, push["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
        val noText = rpc(
            "SendMessage",
            buildJsonObject {
                put("message", A2aCodec.encodeMessage(A2aMessage("m", A2aRole.User, listOf(A2aPart.Data(JsonPrimitive(1)))), A2aVersion.V1_0))
            },
        )
        assertEquals(A2aErrorCodes.CONTENT_TYPE_NOT_SUPPORTED, noText["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
        val badJson = server.handle("POST", "/a2a", auth, "{nope")
        assertEquals(A2aErrorCodes.PARSE_ERROR, Json.parseToJsonElement(badJson.body).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
        val version = server.handle("POST", "/a2a", auth + ("A2A-Version" to "7.0"), A2aCodec.rpcRequest(JsonPrimitive(1), "GetTask", buildJsonObject { put("id", "x") }).toString())
        assertEquals(A2aErrorCodes.VERSION_NOT_SUPPORTED, Json.parseToJsonElement(version.body).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
        assertTrue(server.pendingLaunches.value.isEmpty())
    }

    @Test
    fun followUpMessagesToATaskAreNotSupported() = runTest {
        val params = buildJsonObject {
            put("message", A2aCodec.encodeMessage(A2aMessage("m", A2aRole.User, listOf(A2aPart.Text("more")), taskId = "t"), A2aVersion.V1_0))
        }
        val reply = rpc("SendMessage", params)
        assertNotNull(reply["error"])
        assertNull(reply["result"])
        assertFalse(server.pendingLaunches.value.isNotEmpty())
    }

    @Test
    fun constantTimeEqualsComparesWholeStrings() {
        assertTrue(A2aServer.constantTimeEquals("abc", "abc"))
        assertFalse(A2aServer.constantTimeEquals("abc", "abd"))
        assertFalse(A2aServer.constantTimeEquals("abc", "abcd"))
        assertFalse(A2aServer.constantTimeEquals("", "a"))
    }

    private fun definition() = WorkflowDefinition(
        id = WorkflowDefinitionId("wf"),
        name = "wf",
        tasks = listOf(
            TaskDefinition(TaskDefinitionId("research"), "Research", "Look", null),
            TaskDefinition(TaskDefinitionId("write"), "Write", "Write", null, dependsOn = setOf(TaskDefinitionId("research"))),
        ),
    )

    private fun run(status: WorkflowRunStatus, id: String = "run-1") = WorkflowRun(
        id = WorkflowRunId(id),
        projectId = ProjectId("p"),
        workflowDefinitionId = WorkflowDefinitionId("wf"),
        objective = "Write a design note",
        status = status,
        taskRuns = mapOf(
            TaskDefinitionId("research") to taskRun("research", "Background"),
            TaskDefinitionId("write") to taskRun("write", "The note"),
        ),
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 2,
    )

    private fun taskRun(id: String, text: String) = TaskRun(
        id = TaskRunId("tr-$id"),
        taskDefinitionId = TaskDefinitionId(id),
        status = TaskRunStatus.Completed,
        assignedRoleId = null,
        artifacts = listOf(
            ArtifactRef(
                id = ArtifactId("art-$id"),
                kind = ArtifactKind.Research,
                taskRunId = TaskRunId("tr-$id"),
                label = id,
                textContent = text,
                createdAtEpochMillis = 1,
            ),
        ),
    )

    private companion object {
        const val TOKEN = "s3cret-token-for-tests"
    }
}
