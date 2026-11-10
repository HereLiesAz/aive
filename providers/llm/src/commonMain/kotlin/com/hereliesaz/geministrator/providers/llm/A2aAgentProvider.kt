package com.hereliesaz.geministrator.providers.llm

import com.hereliesaz.geministrator.a2a.A2aAgentCard
import com.hereliesaz.geministrator.a2a.A2aArtifact
import com.hereliesaz.geministrator.a2a.A2aCodec
import com.hereliesaz.geministrator.a2a.A2aErrorCodes
import com.hereliesaz.geministrator.a2a.A2aMessage
import com.hereliesaz.geministrator.a2a.A2aMethods
import com.hereliesaz.geministrator.a2a.A2aOperation
import com.hereliesaz.geministrator.a2a.A2aPart
import com.hereliesaz.geministrator.a2a.A2aRole
import com.hereliesaz.geministrator.a2a.A2aRpcException
import com.hereliesaz.geministrator.a2a.A2aSendResult
import com.hereliesaz.geministrator.a2a.A2aTask
import com.hereliesaz.geministrator.a2a.A2aTaskState
import com.hereliesaz.geministrator.a2a.A2aVersion
import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.CONTEXT_PROVENANCE_METADATA_KEY
import com.hereliesaz.geministrator.domain.ContextProvenance
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.net.CleartextPolicy
import com.hereliesaz.geministrator.net.ResponseTooLargeException
import com.hereliesaz.geministrator.net.boundedText
import com.hereliesaz.geministrator.net.boundedTextOrTruncated
import com.hereliesaz.geministrator.providers.AgentCapabilities
import com.hereliesaz.geministrator.providers.AgentEvent
import com.hereliesaz.geministrator.providers.AgentProvider
import com.hereliesaz.geministrator.providers.AgentRunHandle
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.ProviderActionResult
import com.hereliesaz.geministrator.providers.ProviderArtifact
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.random.Random
import kotlin.time.TimeSource

/**
 * Delegates a task to a remote agent over Google's Agent2Agent protocol (docs/A2A.md).
 *
 * The user connects the agent in the Keeper's room by its Agent Card URL, with an optional bearer
 * token. It is [explicitOnly]: the engine never routes work to it unless a role prefers it.
 * `SendMessage` starts a remote task, whose id is the [ProviderRunId] so a restart reconnects
 * instead of sending the task twice; `GetTask` is polled until the task ends. The task completes
 * here only when a poll reads `completed` (THREAT_MODEL invariant 1); its artifacts become the
 * task's artifacts as opaque text and links, never executed.
 */
class A2aAgentProvider(
    override val id: AgentProviderId,
    private val displayName: String,
    cardUrl: String,
    bearerToken: String? = null,
    client: HttpClient = a2aClient(),
    private val pollIntervalMillis: Long = 2_000L,
    /** A remote task still unfinished after this long is reported as failed, not polled forever. */
    private val maxObserveMillis: Long = 6L * 60L * 60L * 1_000L,
    private val idFactory: () -> String = ::randomA2aId,
) : AgentProvider {
    private val api = A2aClientApi(cardUrl, bearerToken, client, idFactory)
    private val sessionsMutex = Mutex()
    private val sessions = mutableMapOf<ProviderRunId, Session>()

    private data class Session(
        val request: AgentTaskRequest,
        val contextId: String?,
        /** Set when the agent answered `SendMessage` with a message instead of a task. */
        val directReply: A2aMessage? = null,
    )

    init {
        require(pollIntervalMillis >= 250L) { "pollIntervalMillis must be at least 250ms" }
        require(maxObserveMillis >= pollIntervalMillis) { "maxObserveMillis must be at least pollIntervalMillis" }
    }

    override val explicitOnly: Boolean get() = true

    override suspend fun capabilities(): AgentCapabilities = AgentCapabilities(
        supported = setOf(
            AgentCapability.PlanGeneration,
            AgentCapability.PlanApproval,
            AgentCapability.Messaging,
            AgentCapability.Research,
        ),
    )

    /** Aive owns the plan gate; drafting is local, so nothing reaches the remote agent before approval. */
    override suspend fun draftPlan(request: AgentTaskRequest): String = buildString {
        appendLine("Objective: ${request.objective.trim()}")
        appendLine("1. Send this task, its role instructions and the approved upstream context to $displayName over A2A.")
        appendLine("2. Follow the remote task until it completes, fails or asks for more input.")
        if (request.acceptanceCriteria.isNotEmpty()) {
            appendLine("   Acceptance checks:")
            request.acceptanceCriteria.forEach { appendLine("   - ${it.description.trim()}") }
        }
        appendLine("3. Return the agent's artifacts to Aive; Aive decides acceptance and what runs next.")
    }.trim()

    override suspend fun start(request: AgentTaskRequest): AgentRunHandle {
        val message = A2aMessage(
            messageId = idFactory(),
            role = A2aRole.User,
            parts = listOf(A2aPart.Text(request.toA2aPrompt())),
            metadata = buildJsonObject { put("aiveTaskRunId", request.taskRunId.value) },
        )
        return when (val result = api.send(message)) {
            is A2aSendResult.TaskResult -> {
                val runId = ProviderRunId(result.task.id)
                sessionsMutex.withLock { sessions[runId] = Session(request, result.task.contextId.ifBlank { null }) }
                AgentRunHandle(runId)
            }
            is A2aSendResult.MessageResult -> {
                val runId = ProviderRunId(DIRECT_REPLY_PREFIX + result.message.messageId.ifBlank { idFactory() })
                sessionsMutex.withLock {
                    sessions[runId] = Session(request, result.message.contextId, directReply = result.message)
                }
                AgentRunHandle(runId)
            }
        }
    }

    override suspend fun reconnect(
        runId: ProviderRunId,
        request: AgentTaskRequest,
        planGenerated: Boolean,
        planApproved: Boolean,
        planPreview: String?,
    ): ProviderActionResult {
        if (runId.value.startsWith(DIRECT_REPLY_PREFIX)) {
            return ProviderActionResult.Rejected("$displayName answered directly; the reply did not survive the restart")
        }
        val task = try {
            api.getTask(runId.value)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return ProviderActionResult.Rejected("A2A task ${runId.value} is unavailable: ${failure.message ?: "unknown error"}")
        }
        sessionsMutex.withLock { sessions[runId] = Session(request, task.contextId.ifBlank { null }) }
        return ProviderActionResult.Accepted
    }

    override fun observe(runId: ProviderRunId): Flow<AgentEvent> = flow {
        val session = sessionsMutex.withLock { sessions[runId] }
            ?: error("A2A task ${runId.value} has no restored Aive request")
        session.directReply?.let { reply ->
            if (reply.text.isNotBlank()) emit(AgentEvent.Message(runId, reply.text))
            artifactsFor(session.request, emptyList(), reply, runId).forEach { emit(AgentEvent.ArtifactProduced(runId, it)) }
            emit(AgentEvent.Completed(runId))
            sessionsMutex.withLock { sessions.remove(runId) }
            return@flow
        }
        val startedAt = TimeSource.Monotonic.markNow()
        var consecutiveFailures = 0
        var lastState: A2aTaskState? = null
        val seenMessages = HashSet<String>()
        emit(AgentEvent.Progress(runId, "Connected to $displayName task ${runId.value.take(8)}"))
        while (true) {
            if (startedAt.elapsedNow().inWholeMilliseconds > maxObserveMillis) {
                emit(AgentEvent.Failed(runId, "$displayName did not finish in time"))
                sessionsMutex.withLock { sessions.remove(runId) }
                return@flow
            }
            val task = try {
                api.getTask(runId.value).also { consecutiveFailures = 0 }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: A2aRpcException) {
                if (failure.code == A2aErrorCodes.TASK_NOT_FOUND) {
                    emit(AgentEvent.Failed(runId, "$displayName no longer knows task ${runId.value.take(8)}"))
                    sessionsMutex.withLock { sessions.remove(runId) }
                    return@flow
                }
                if (++consecutiveFailures >= MAX_CONSECUTIVE_POLL_FAILURES) {
                    emit(AgentEvent.Failed(runId, "Lost contact with $displayName: ${failure.message}"))
                    sessionsMutex.withLock { sessions.remove(runId) }
                    return@flow
                }
                delay(pollIntervalMillis)
                continue
            } catch (failure: Exception) {
                // One failed poll (a dropped connection, a 5xx) does not end a long-running task.
                if (++consecutiveFailures >= MAX_CONSECUTIVE_POLL_FAILURES) {
                    emit(AgentEvent.Failed(runId, "Lost contact with $displayName: ${failure.message ?: "poll failed"}"))
                    sessionsMutex.withLock { sessions.remove(runId) }
                    return@flow
                }
                delay(pollIntervalMillis)
                continue
            }
            for (event in a2aTaskEvents(runId, task, lastState, seenMessages, displayName)) emit(event)
            lastState = task.status.state
            if (task.status.state.isTerminal) {
                if (task.status.state == A2aTaskState.Completed) {
                    artifactsFor(session.request, task.artifacts, task.status.message, runId).forEach {
                        emit(AgentEvent.ArtifactProduced(runId, it))
                    }
                    emit(AgentEvent.Completed(runId))
                } else {
                    emit(AgentEvent.Failed(runId, failureReason(task)))
                }
                sessionsMutex.withLock { sessions.remove(runId) }
                return@flow
            }
            delay(pollIntervalMillis)
        }
    }

    override suspend fun sendMessage(runId: ProviderRunId, message: String): ProviderActionResult {
        val session = sessionsMutex.withLock { sessions[runId] }
            ?: return ProviderActionResult.Rejected("A2A task ${runId.value} is not active")
        if (session.directReply != null) {
            return ProviderActionResult.Rejected("$displayName answered directly; there is no task to message")
        }
        return try {
            api.send(
                A2aMessage(
                    messageId = idFactory(),
                    role = A2aRole.User,
                    parts = listOf(A2aPart.Text(message)),
                    taskId = runId.value,
                    contextId = session.contextId,
                ),
            )
            ProviderActionResult.Accepted
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            ProviderActionResult.Rejected(failure.message ?: "Unable to message $displayName")
        }
    }

    override suspend fun approvePlan(runId: ProviderRunId): ProviderActionResult =
        ProviderActionResult.Rejected("A2A agents use Aive's engine-owned plan gate; no remote plan approval is expected")

    override suspend fun cancel(runId: ProviderRunId): ProviderActionResult {
        val session = sessionsMutex.withLock { sessions.remove(runId) }
        if (session?.directReply != null || runId.value.startsWith(DIRECT_REPLY_PREFIX)) return ProviderActionResult.Accepted
        return try {
            api.cancel(runId.value)
            ProviderActionResult.Accepted
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: A2aRpcException) {
            // Already over (or gone) remotely: there is nothing left to stop.
            if (failure.code == A2aErrorCodes.TASK_NOT_CANCELABLE || failure.code == A2aErrorCodes.TASK_NOT_FOUND) {
                ProviderActionResult.Accepted
            } else {
                ProviderActionResult.Rejected(failure.message)
            }
        } catch (failure: Exception) {
            ProviderActionResult.Rejected(failure.message ?: "Unable to cancel the $displayName task")
        }
    }

    private fun failureReason(task: A2aTask): String {
        val detail = task.status.message?.text?.takeIf(String::isNotBlank)
        val what = when (task.status.state) {
            A2aTaskState.Canceled -> "$displayName cancelled the task"
            A2aTaskState.Rejected -> "$displayName rejected the task"
            else -> "$displayName reported the task failed"
        }
        return if (detail == null) what else "$what: $detail"
    }

    private fun artifactsFor(
        request: AgentTaskRequest,
        artifacts: List<A2aArtifact>,
        finalMessage: A2aMessage?,
        runId: ProviderRunId,
    ): List<ProviderArtifact> =
        a2aProviderArtifacts(request.requiredArtifacts, artifacts, finalMessage, runId, displayName)

    companion object {
        const val DIRECT_REPLY_PREFIX = "a2a-message:"
    }
}

/** Events for one poll: a state change as progress, and each new agent message once. */
internal fun a2aTaskEvents(
    runId: ProviderRunId,
    task: A2aTask,
    previousState: A2aTaskState?,
    seenMessages: MutableSet<String>,
    displayName: String,
): List<AgentEvent> = buildList {
    val state = task.status.state
    if (state != previousState && !state.isTerminal) {
        add(
            AgentEvent.Progress(
                runId,
                when (state) {
                    A2aTaskState.Submitted -> "$displayName accepted the task"
                    A2aTaskState.Working -> "$displayName is working"
                    A2aTaskState.InputRequired -> "$displayName is waiting for your reply"
                    A2aTaskState.AuthRequired -> "$displayName needs credentials it cannot get from Aive"
                    A2aTaskState.Unknown -> "$displayName reports an unknown state"
                    A2aTaskState.Completed, A2aTaskState.Canceled, A2aTaskState.Failed, A2aTaskState.Rejected ->
                        "$displayName finished"
                },
            ),
        )
    }
    val message = task.status.message
    if (message != null && message.role == A2aRole.Agent && message.text.isNotBlank() && !state.isTerminal) {
        val key = message.messageId.ifBlank { message.text }
        if (seenMessages.add(key)) add(AgentEvent.Message(runId, message.text))
    }
}

/**
 * A finished remote task's output as provider artifacts. Each kind the workflow requires (or
 * [ArtifactKind.CommandOutput] when it requires none) gets the agent's text; links in file parts
 * become further artifacts. Inline file bytes are not kept: they are named, not stored.
 */
internal fun a2aProviderArtifacts(
    requiredKinds: Set<ArtifactKind>,
    artifacts: List<A2aArtifact>,
    finalMessage: A2aMessage?,
    runId: ProviderRunId,
    displayName: String,
): List<ProviderArtifact> {
    // Made outside the keeper's control: external content (docs/THREAT_MODEL.md, Context provenance).
    // A producer may lower trust, so this label survives intake whatever the role says.
    val metadata = mapOf(
        "a2aTaskId" to runId.value,
        CONTEXT_PROVENANCE_METADATA_KEY to ContextProvenance.ExternalContent.name,
    )
    val text = buildString {
        artifacts.forEach { artifact ->
            val body = artifact.parts.mapNotNull { part ->
                when (part) {
                    is A2aPart.Text -> part.text
                    is A2aPart.Data -> part.data.toString()
                    is A2aPart.File -> if (part.url == null) "[file ${part.filename ?: "without a name"} not kept]" else null
                }
            }.joinToString("\n\n").trim()
            if (body.isEmpty()) return@forEach
            if (isNotEmpty()) append("\n\n")
            artifact.name?.takeIf(String::isNotBlank)?.let { append("## ").append(it).append("\n\n") }
            append(body)
        }
        if (isEmpty()) finalMessage?.text?.takeIf(String::isNotBlank)?.let { append(it) }
    }
    val kinds = requiredKinds.ifEmpty { setOf(ArtifactKind.CommandOutput) }
    return buildList {
        if (text.isNotBlank()) {
            kinds.sortedBy { it.ordinal }.forEach { kind ->
                add(
                    ProviderArtifact(
                        kind = kind,
                        label = "$displayName ${kind.name}",
                        textContent = text,
                        mediaType = "text/markdown",
                        metadata = metadata,
                    ),
                )
            }
        }
        val firstKind = kinds.minByOrNull { it.ordinal } ?: ArtifactKind.CommandOutput
        artifacts.forEach { artifact ->
            artifact.parts.filterIsInstance<A2aPart.File>().forEach { file ->
                val url = file.url ?: return@forEach
                if (!url.startsWith("https://", ignoreCase = true) && !url.startsWith("http://", ignoreCase = true)) return@forEach
                add(
                    ProviderArtifact(
                        kind = firstKind,
                        label = file.filename ?: artifact.name ?: "$displayName file",
                        uri = url,
                        mediaType = file.mediaType,
                        metadata = metadata + ("a2aArtifactId" to artifact.artifactId),
                    ),
                )
            }
        }
    }
}

private fun AgentTaskRequest.toA2aPrompt(): String = buildString {
    appendLine(objective.trim())
    if (roleInstructions.isNotBlank()) {
        appendLine()
        appendLine("Role instructions:")
        appendLine(roleInstructions.trim())
    }
    if (acceptanceCriteria.isNotEmpty()) {
        appendLine()
        appendLine("Acceptance criteria:")
        acceptanceCriteria.forEach { appendLine("- ${it.description.trim()}") }
    }
    val context = contextArtifacts.filter { !it.textContent.isNullOrBlank() }
    if (context.isNotEmpty()) {
        appendLine()
        appendLine("Approved upstream context:")
        context.forEach { artifact ->
            appendLine("### ${artifact.kind}: ${artifact.label}")
            appendLine(artifact.textContent.orEmpty())
        }
    }
}.trim()

/** The JSON-RPC calls, against the endpoint the Agent Card names. */
internal class A2aClientApi(
    private val cardUrl: String,
    private val bearerToken: String?,
    private val client: HttpClient,
    private val idFactory: () -> String,
) {
    private val cardMutex = Mutex()
    private var resolvedEndpoint: Pair<String, A2aVersion>? = null

    init {
        require(CleartextPolicy.allowsSecret(cardUrl)) {
            "The Agent Card URL must use https:// (plain http:// only for this computer or the local network)"
        }
    }

    suspend fun card(): A2aAgentCard {
        val response = client.get(cardUrl) { header(HttpHeaders.Accept, "application/json") }
        if (response.status.value !in 200..299) {
            val detail = response.boundedTextOrTruncated(ERROR_BODY_BYTES).redactSecrets().take(300)
            error("Unable to read the Agent Card: HTTP ${response.status.value}" + if (detail.isBlank()) "" else ": $detail")
        }
        val body = try {
            response.boundedText(MAX_CARD_BYTES)
        } catch (_: ResponseTooLargeException) {
            error("The Agent Card is larger than ${MAX_CARD_BYTES / 1024} KB; Aive does not read it")
        }
        return A2aCodec.decodeCard(Json.parseToJsonElement(body))
    }

    /** Reads the card once and checks that its JSON-RPC endpoint may receive this connection's prompts and token. */
    private suspend fun endpoint(): Pair<String, A2aVersion> = cardMutex.withLock {
        resolvedEndpoint?.let { return@withLock it }
        val resolved = card().jsonRpcEndpoint()
            ?: error("The Agent Card offers no JSON-RPC interface, the only A2A binding Aive speaks")
        val (url, _) = resolved
        require(CleartextPolicy.allowsSecret(url)) {
            "The agent's endpoint must use https:// (plain http:// only for this computer or the local network)"
        }
        // A card must not be able to send the prompts it guards (or the token, when there is one) to another origin.
        require(originOf(url) == originOf(cardUrl)) {
            "The agent's endpoint (${originOf(url)}) is not on the Agent Card's origin (${originOf(cardUrl)}); " +
                "Aive sends a task only to the origin it was given for"
        }
        resolved.also { resolvedEndpoint = it }
    }

    suspend fun send(message: A2aMessage): A2aSendResult {
        val (_, version) = endpoint()
        val params = buildJsonObject {
            put("message", A2aCodec.encodeMessage(message, version))
            put(
                "configuration",
                buildJsonObject {
                    put("acceptedOutputModes", JsonArray(ACCEPTED_OUTPUT_MODES.map(::JsonPrimitive)))
                    if (version == A2aVersion.V0_3) put("blocking", false)
                },
            )
        }
        val result = call(A2aOperation.SendMessage, params)
        return A2aCodec.decodeSendResult(result)
            ?: throw A2aRpcException(A2aErrorCodes.INVALID_AGENT_RESPONSE, "The agent answered SendMessage with neither a task nor a message")
    }

    suspend fun getTask(taskId: String): A2aTask {
        val result = call(A2aOperation.GetTask, buildJsonObject { put("id", taskId) })
        return A2aCodec.decodeTask(result)
            ?: throw A2aRpcException(A2aErrorCodes.INVALID_AGENT_RESPONSE, "The agent answered GetTask without a task")
    }

    suspend fun cancel(taskId: String) {
        call(A2aOperation.CancelTask, buildJsonObject { put("id", taskId) })
    }

    private suspend fun call(operation: A2aOperation, params: kotlinx.serialization.json.JsonObject): JsonElement {
        val (url, version) = endpoint()
        val body = A2aCodec.rpcRequest(JsonPrimitive(idFactory()), A2aMethods.name(operation, version), params).toString()
        val response = client.post(url) {
            contentType(ContentType.Application.Json)
            header(HttpHeaders.Accept, "application/json")
            if (version == A2aVersion.V1_0) header(A2A_VERSION_HEADER, version.header)
            bearerToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            setBody(body)
        }
        return A2aCodec.rpcResultOf(Json.parseToJsonElement(response.requireSuccess()))
    }

    private suspend fun HttpResponse.requireSuccess(): String {
        if (status.value in 200..299) {
            return try {
                boundedText(MAX_RESPONSE_BYTES)
            } catch (_: ResponseTooLargeException) {
                throw A2aRpcException(A2aErrorCodes.INVALID_AGENT_RESPONSE, "The agent's response is larger than ${MAX_RESPONSE_BYTES / 1_000_000} MB")
            }
        }
        val detail = boundedTextOrTruncated(ERROR_BODY_BYTES).redactSecrets().take(300)
        val reason = when (status.value) {
            401, 403 -> "The A2A agent refused the request (HTTP ${status.value}); check its token in Connections"
            else -> "A2A request failed: HTTP ${status.value}"
        }
        error(reason + if (detail.isBlank()) "" else ": $detail")
    }

    private fun originOf(url: String): String {
        val lower = url.trim().lowercase()
        val scheme = lower.substringBefore("://")
        val authority = lower.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
        val withPort = if (':' in authority.substringAfterLast(']')) {
            authority
        } else {
            authority + if (scheme == "https") ":443" else ":80"
        }
        return "$scheme://$withPort"
    }

    companion object {
        const val A2A_VERSION_HEADER = "A2A-Version"
        const val MAX_CARD_BYTES = 256 * 1024
        const val MAX_RESPONSE_BYTES = 4_000_000
        const val ERROR_BODY_BYTES = 4096
        val ACCEPTED_OUTPUT_MODES = listOf("text/plain", "text/markdown", "application/json")
    }
}

/** Polls that may fail in a row (dropped connection, 5xx) before the task is reported lost. */
private const val MAX_CONSECUTIVE_POLL_FAILURES = 5

private fun randomA2aId(): String = Random.nextBytes(16).joinToString("") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}

private fun a2aClient(): HttpClient = HttpClient {
    expectSuccess = false
    // A redirect would carry the token somewhere the card did not name.
    followRedirects = false
    install(HttpTimeout) {
        requestTimeoutMillis = 60_000L
        connectTimeoutMillis = 15_000L
    }
}
