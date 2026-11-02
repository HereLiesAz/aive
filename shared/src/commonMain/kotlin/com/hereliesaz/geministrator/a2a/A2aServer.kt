package com.hereliesaz.geministrator.a2a

import com.hereliesaz.geministrator.ExternalWorkflowLaunchRequest
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.domain.isTerminal
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random

/** What the A2A server needs from The Aive's runtime. */
interface A2aWorkflowHost {
    /** The run and its definition, read from the workflow store; null when unknown. */
    suspend fun run(runId: String): Pair<WorkflowRun, WorkflowDefinition?>?

    /** Whether a run is still going; a new launch would take its place, so the server refuses one. */
    suspend fun busy(): Boolean

    /** Cancels [runId] if it is the active run; false when it is not (finished, or another run). */
    suspend fun cancel(runId: String): Boolean

    /** The swarm's roles, listed as skills on the Agent Card. */
    suspend fun roles(): List<RoleDefinition>
}

data class A2aHttpResponse(
    val status: Int,
    val body: String,
    val contentType: String = "application/json",
    val headers: Map<String, String> = emptyMap(),
)

/**
 * The Aive as an A2A server (docs/A2A.md): an Agent Card listing the hive and its roles as skills,
 * and a token-protected JSON-RPC endpoint where `SendMessage` launches a workflow (through the
 * same queue as other apps' launches), `GetTask` reports its run and `CancelTask` stops it.
 *
 * Platform-neutral; the desktop serves it over HTTP on loopback. Every JSON-RPC call needs
 * `Authorization: Bearer <token>`; the card is public, as A2A discovery expects. Nothing here
 * approves a gate or touches a provider: a launched run waits at its gates for the person at
 * the keyboard, like any other run.
 */
class A2aServer(
    private val host: A2aWorkflowHost,
    /** The bearer token clients must present; null or blank refuses every call. */
    private val token: () -> String?,
    /** `http://127.0.0.1:41241`, without a trailing slash. */
    private val baseUrl: () -> String,
    private val agentVersion: String,
    /** When true, a request whose Host header is not a loopback name is refused (DNS rebinding). */
    private val loopbackOnly: Boolean = true,
    private val nowIso: () -> String = { "" },
    private val idFactory: () -> String = ::randomServerId,
) {
    private class Record(
        val taskId: String,
        val contextId: String,
        val requestId: Long,
        val userMessage: A2aMessage,
        /** The run id the runtime showed before this launch; a launch that leaves it unchanged started nothing. */
        val runIdBefore: String?,
        var runId: String? = null,
        var state: A2aTaskState = A2aTaskState.Submitted,
        var note: String? = null,
        var timestamp: String = "",
    )

    private val mutex = Mutex()
    private val records = LinkedHashMap<String, Record>()
    private var nextRequestId = 1L
    private var lastKnownRunId: String? = null
    private val pending = MutableStateFlow<List<ExternalWorkflowLaunchRequest>>(emptyList())

    /** Launches waiting for the UI's runtime; App takes them as `externalWorkflowLaunchRequests`. */
    val pendingLaunches: StateFlow<List<ExternalWorkflowLaunchRequest>> = pending.asStateFlow()

    /** The id of the run the runtime shows now; the host reports it whenever it changes. */
    suspend fun runtimeShowsRun(runId: String?) = mutex.withLock { lastKnownRunId = runId }

    /**
     * App finished launching [requestId]: [failure] is null when it started, and [runIdAfter] is
     * the run the runtime shows right after (the new run, unless nothing was started).
     */
    suspend fun launchHandled(requestId: Long, failure: String?, runIdAfter: String?) = mutex.withLock {
        pending.value = pending.value.filterNot { it.requestId == requestId }
        val record = records.values.firstOrNull { it.requestId == requestId } ?: return@withLock
        if (record.state.isTerminal) return@withLock
        record.timestamp = nowIso()
        when {
            failure != null -> {
                record.state = A2aTaskState.Failed
                record.note = failure
            }
            runIdAfter == null || runIdAfter == record.runIdBefore -> {
                record.state = A2aTaskState.Failed
                record.note = "The Aive did not start a run for this request"
            }
            else -> {
                record.runId = runIdAfter
                record.state = A2aTaskState.Working
                lastKnownRunId = runIdAfter
            }
        }
    }

    /** Ends every queued launch (the server was turned off). */
    suspend fun failPending(reason: String) = mutex.withLock {
        val ids = pending.value.map { it.requestId }.toSet()
        pending.value = emptyList()
        records.values.filter { it.requestId in ids && !it.state.isTerminal }.forEach {
            it.state = A2aTaskState.Failed
            it.note = reason
            it.timestamp = nowIso()
        }
    }

    suspend fun card(): A2aAgentCard {
        val roles = runCatching { host.roles() }.getOrDefault(emptyList()).filter { it.enabled }
        return A2aAgentCard(
            name = "The Aive",
            description = "Runs a governed hive of AI agents on an objective. Send the objective as text; " +
                "The Aive plans a workflow, staffs it with the roles below and returns what it made. " +
                "Approvals stay with the person at The Aive.",
            version = agentVersion,
            interfaces = listOf(A2aAgentInterface(url = baseUrl() + RPC_PATH)),
            skills = listOf(
                A2aSkill(
                    id = HIVE_SKILL_ID,
                    name = "Run a hive",
                    description = "Plan and run a workflow on an objective, with human approval gates.",
                    tags = listOf("workflow", "orchestration"),
                    examples = listOf("Write a short design note for offline sync"),
                ),
            ) + roles.map { role ->
                A2aSkill(
                    id = "role:${role.id.value}",
                    name = role.name,
                    description = role.description.ifBlank { role.name },
                    tags = listOf("role") + role.capabilitiesRequired.map { it.name.lowercase() }.sorted(),
                )
            },
            defaultInputModes = listOf("text/plain"),
            defaultOutputModes = listOf("text/plain", "text/markdown"),
            bearerAuth = true,
        )
    }

    /**
     * One HTTP request. [headers] keys are compared case-insensitively. Bodies over
     * [MAX_BODY_BYTES] are refused by the platform layer before they get here.
     */
    suspend fun handle(method: String, path: String, headers: Map<String, String>, body: String): A2aHttpResponse {
        val header = { name: String -> headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value }
        if (loopbackOnly && !isLoopbackHost(header("Host"))) {
            return A2aHttpResponse(403, """{"error":"forbidden"}""")
        }
        val route = path.substringBefore('?')
        return when (route) {
            A2aCodec.WELL_KNOWN_CARD_PATH, A2aCodec.LEGACY_WELL_KNOWN_CARD_PATH ->
                if (method.equals("GET", ignoreCase = true)) {
                    A2aHttpResponse(200, A2aCodec.encodeCard(card()).toString())
                } else {
                    A2aHttpResponse(405, """{"error":"method not allowed"}""", headers = mapOf("Allow" to "GET"))
                }
            RPC_PATH -> when {
                !method.equals("POST", ignoreCase = true) ->
                    A2aHttpResponse(405, """{"error":"method not allowed"}""", headers = mapOf("Allow" to "POST"))
                !authorized(header("Authorization")) ->
                    A2aHttpResponse(401, """{"error":"unauthorized"}""", headers = mapOf("WWW-Authenticate" to "Bearer"))
                body.encodeToByteArray().size > MAX_BODY_BYTES ->
                    A2aHttpResponse(413, """{"error":"request too large"}""")
                else -> A2aHttpResponse(200, rpc(body, header(A2A_VERSION_HEADER)).toString())
            }
            else -> A2aHttpResponse(404, """{"error":"not found"}""")
        }
    }

    private fun authorized(authorization: String?): Boolean {
        val expected = token()?.trim()?.takeIf(String::isNotEmpty) ?: return false
        val presented = authorization?.trim()?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substring(7)?.trim() ?: return false
        return constantTimeEquals(presented, expected)
    }

    private suspend fun rpc(body: String, versionHeader: String?): JsonObject {
        val element = try {
            Json.parseToJsonElement(body)
        } catch (_: Exception) {
            return A2aCodec.rpcError(null, A2aErrorCodes.PARSE_ERROR, "Invalid JSON")
        }
        val request = element as? JsonObject
            ?: return A2aCodec.rpcError(null, A2aErrorCodes.INVALID_REQUEST, "Send one JSON-RPC request object")
        val id: JsonElement = request["id"] ?: JsonNull
        if (request.string("jsonrpc") != "2.0") return A2aCodec.rpcError(id, A2aErrorCodes.INVALID_REQUEST, "jsonrpc must be \"2.0\"")
        val methodName = request.string("method")
            ?: return A2aCodec.rpcError(id, A2aErrorCodes.INVALID_REQUEST, "method is required")
        val (operation, version) = A2aMethods.resolve(methodName)
            ?: return A2aCodec.rpcError(id, A2aErrorCodes.METHOD_NOT_FOUND, "Unknown method $methodName")
        if (versionHeader != null && versionHeader.trim() !in SUPPORTED_VERSIONS) {
            return A2aCodec.rpcError(id, A2aErrorCodes.VERSION_NOT_SUPPORTED, "A2A version ${versionHeader.trim()} is not supported")
        }
        val params = request["params"] as? JsonObject ?: JsonObject(emptyMap())
        return try {
            val result: JsonElement = when (operation) {
                A2aOperation.SendMessage -> A2aCodec.encodeSendResult(A2aSendResult.TaskResult(sendMessage(params)), version)
                A2aOperation.GetTask -> A2aCodec.encodeTask(getTask(params), version)
                A2aOperation.CancelTask -> A2aCodec.encodeTask(cancelTask(params), version)
                A2aOperation.SendStreamingMessage, A2aOperation.SubscribeToTask ->
                    throw A2aRpcException(A2aErrorCodes.UNSUPPORTED_OPERATION, "Streaming is not supported; poll GetTask")
                A2aOperation.ListTasks, A2aOperation.GetExtendedAgentCard ->
                    throw A2aRpcException(A2aErrorCodes.UNSUPPORTED_OPERATION, "$methodName is not supported")
                A2aOperation.PushNotificationConfig ->
                    throw A2aRpcException(A2aErrorCodes.PUSH_NOTIFICATION_NOT_SUPPORTED, "Push notifications are not supported")
            }
            A2aCodec.rpcResult(id, result)
        } catch (failure: A2aRpcException) {
            A2aCodec.rpcError(id, failure.code, failure.message)
        }
    }

    private suspend fun sendMessage(params: JsonObject): A2aTask {
        val message = A2aCodec.decodeMessage(params["message"])
            ?: throw A2aRpcException(A2aErrorCodes.INVALID_PARAMS, "params.message with parts is required")
        if (message.taskId != null) {
            throw A2aRpcException(
                A2aErrorCodes.UNSUPPORTED_OPERATION,
                "The Aive takes one objective per task; replies to a running task are not supported",
            )
        }
        val objective = message.text
        if (objective.isBlank()) {
            throw A2aRpcException(A2aErrorCodes.CONTENT_TYPE_NOT_SUPPORTED, "Send the objective as a text part")
        }
        if (objective.length > MAX_OBJECTIVE_CHARS) {
            throw A2aRpcException(A2aErrorCodes.INVALID_PARAMS, "The objective is longer than $MAX_OBJECTIVE_CHARS characters")
        }
        val busy = runCatching { host.busy() }.getOrDefault(true)
        return mutex.withLock {
            val record = Record(
                taskId = idFactory(),
                contextId = message.contextId?.takeIf(String::isNotBlank) ?: idFactory(),
                requestId = nextRequestId++,
                userMessage = message.copy(messageId = message.messageId.ifBlank { idFactory() }),
                runIdBefore = lastKnownRunId,
                timestamp = nowIso(),
            )
            if (busy || pending.value.isNotEmpty()) {
                record.state = A2aTaskState.Rejected
                record.note = "The Aive is busy with another run; try again when it has finished"
            } else {
                pending.value = pending.value + ExternalWorkflowLaunchRequest(
                    requestId = record.requestId,
                    projectName = projectNameFor(objective),
                    objective = objective,
                )
            }
            remember(record)
            snapshot(record, null)
        }
    }

    private suspend fun getTask(params: JsonObject): A2aTask {
        val taskId = params.string("id") ?: throw A2aRpcException(A2aErrorCodes.INVALID_PARAMS, "params.id is required")
        val runId = mutex.withLock { records[taskId] ?: throw taskNotFound(taskId) }.runId
        val run = runId?.let { runCatching { host.run(it) }.getOrNull() }
        return mutex.withLock {
            val record = records[taskId] ?: throw taskNotFound(taskId)
            run?.let { (workflowRun, _) ->
                val state = a2aStateOf(workflowRun.status)
                if (!record.state.isTerminal || record.state == state) {
                    if (record.state != state) record.timestamp = nowIso()
                    record.state = state
                }
            }
            snapshot(record, run)
        }
    }

    private suspend fun cancelTask(params: JsonObject): A2aTask {
        val taskId = params.string("id") ?: throw A2aRpcException(A2aErrorCodes.INVALID_PARAMS, "params.id is required")
        val (runId, queued) = mutex.withLock {
            val record = records[taskId] ?: throw taskNotFound(taskId)
            if (record.state.isTerminal) {
                throw A2aRpcException(A2aErrorCodes.TASK_NOT_CANCELABLE, "Task $taskId is already ${record.state.wire(A2aVersion.V0_3)}")
            }
            val queued = pending.value.any { it.requestId == record.requestId }
            if (queued) {
                pending.value = pending.value.filterNot { it.requestId == record.requestId }
                record.state = A2aTaskState.Canceled
                record.timestamp = nowIso()
            }
            record.runId to queued
        }
        if (!queued) {
            val cancelled = runId != null && runCatching { host.cancel(runId) }.getOrDefault(false)
            if (!cancelled) {
                throw A2aRpcException(A2aErrorCodes.TASK_NOT_CANCELABLE, "Task $taskId is not the active run, or its launch is under way")
            }
            mutex.withLock {
                records[taskId]?.let {
                    it.state = A2aTaskState.Canceled
                    it.timestamp = nowIso()
                }
            }
        }
        return getTask(buildJsonObject { put("id", taskId) })
    }

    private fun remember(record: Record) {
        records[record.taskId] = record
        while (records.size > MAX_TASKS) {
            val oldest = records.values.firstOrNull { it.state.isTerminal } ?: records.values.first()
            records.remove(oldest.taskId)
        }
    }

    private fun snapshot(record: Record, run: Pair<WorkflowRun, WorkflowDefinition?>?): A2aTask {
        val workflowRun = run?.first
        val statusText = when {
            workflowRun != null -> runStatusText(workflowRun)
            else -> record.note ?: when (record.state) {
                A2aTaskState.Submitted -> "Queued; The Aive starts it shortly"
                else -> null
            }
        }
        return A2aTask(
            id = record.taskId,
            contextId = record.contextId,
            status = A2aTaskStatus(
                state = record.state,
                message = statusText?.let {
                    A2aMessage(
                        messageId = "${record.taskId}-status-${record.state.name.lowercase()}",
                        role = A2aRole.Agent,
                        parts = listOf(A2aPart.Text(it)),
                        taskId = record.taskId,
                        contextId = record.contextId,
                    )
                },
                timestamp = record.timestamp.ifBlank { null },
            ),
            artifacts = if (record.state == A2aTaskState.Completed && workflowRun != null) {
                a2aArtifactsOf(workflowRun, run?.second)
            } else {
                emptyList()
            },
            history = listOf(record.userMessage.copy(taskId = record.taskId, contextId = record.contextId)),
        )
    }

    private fun taskNotFound(taskId: String) = A2aRpcException(A2aErrorCodes.TASK_NOT_FOUND, "Task $taskId not found")

    companion object {
        const val RPC_PATH = "/a2a"
        const val HIVE_SKILL_ID = "hive"
        const val A2A_VERSION_HEADER = "A2A-Version"
        const val MAX_BODY_BYTES = 1_000_000
        const val MAX_OBJECTIVE_CHARS = 20_000
        const val MAX_TASKS = 64
        val SUPPORTED_VERSIONS = setOf("0.3", "1.0")

        fun projectNameFor(objective: String): String {
            val words = objective.trim().lineSequence().first().take(48).trim()
            return "A2A: ${words.ifBlank { "request" }}"
        }

        fun isLoopbackHost(hostHeader: String?): Boolean {
            val value = hostHeader?.trim()?.lowercase() ?: return false
            val host = if (value.startsWith("[")) value.substringBefore(']').removePrefix("[") else value.substringBefore(':')
            return host == "localhost" || host == "127.0.0.1" || host == "::1"
        }

        fun constantTimeEquals(a: String, b: String): Boolean {
            val left = a.encodeToByteArray()
            val right = b.encodeToByteArray()
            var diff = left.size xor right.size
            for (i in 0 until maxOf(left.size, right.size)) {
                val l = if (i < left.size) left[i].toInt() else 0
                val r = if (i < right.size) right[i].toInt() else 0
                diff = diff or (l xor r)
            }
            return diff == 0
        }
    }
}

/**
 * A workflow run's status as an A2A task state. A run waiting on a person at The Aive is still
 * `working` for the remote client: it cannot answer that gate, so it is not `input-required`.
 */
fun a2aStateOf(status: WorkflowRunStatus): A2aTaskState = when (status) {
    WorkflowRunStatus.Created -> A2aTaskState.Submitted
    WorkflowRunStatus.Running -> A2aTaskState.Working
    WorkflowRunStatus.AwaitingHuman -> A2aTaskState.Working
    WorkflowRunStatus.Completed -> A2aTaskState.Completed
    WorkflowRunStatus.Failed -> A2aTaskState.Failed
    WorkflowRunStatus.Cancelled -> A2aTaskState.Canceled
}

internal fun runStatusText(run: WorkflowRun): String {
    val tasks = run.taskRuns.values
    val done = tasks.count { it.status.isTerminal() }
    val head = when (run.status) {
        WorkflowRunStatus.AwaitingHuman -> "Waiting for approval at The Aive"
        WorkflowRunStatus.Created -> "Starting"
        WorkflowRunStatus.Running -> "Working"
        WorkflowRunStatus.Completed -> "Done"
        WorkflowRunStatus.Failed -> "The run failed"
        WorkflowRunStatus.Cancelled -> "The run was cancelled"
    }
    val progress = tasks.lastOrNull { it.progressMessage != null && !it.status.isTerminal() }?.progressMessage
    return "$head · $done/${tasks.size} tasks done" + (progress?.let { " · $it" } ?: "")
}

/**
 * What a completed run made: the text and web-link artifacts of its final tasks (those no other
 * task depends on), as the hive shows them under "What it made". Opaque content only.
 */
internal fun a2aArtifactsOf(run: WorkflowRun, definition: WorkflowDefinition?): List<A2aArtifact> {
    val finalTaskIds = definition?.tasks?.let { tasks ->
        tasks.filter { task -> tasks.none { task.id in it.dependsOn } }.map { it.id }.toSet()
    }
    return run.taskRuns
        .filterKeys { finalTaskIds == null || it in finalTaskIds }
        .values
        .flatMap { it.artifacts }
        .mapNotNull { artifact ->
            val text = artifact.textContent?.takeIf(String::isNotBlank)
            val link = artifact.uri?.takeIf { it.startsWith("https://", ignoreCase = true) }
            val parts = listOfNotNull(
                text?.let { A2aPart.Text(it, artifact.mediaType?.takeIf { type -> type.startsWith("text/") }) },
                link?.let { A2aPart.File(url = it, mediaType = artifact.mediaType, filename = artifact.label) },
            )
            if (parts.isEmpty()) null else A2aArtifact(artifactId = artifact.id.value, parts = parts, name = artifact.label, description = artifact.kind.name)
        }
}

private fun randomServerId(): String = Random.nextBytes(16).joinToString("") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}
