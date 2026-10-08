package com.hereliesaz.geministrator.providers.llm

import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.RepositorySource
import com.hereliesaz.geministrator.providers.AgentCapabilities
import com.hereliesaz.geministrator.providers.AgentEvent
import com.hereliesaz.geministrator.providers.AgentProvider
import com.hereliesaz.geministrator.providers.AgentRunHandle
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.ProviderActionResult
import com.hereliesaz.geministrator.providers.ProviderArtifact
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Native adapter for an Azphalt `moyai-session` LLM endpoint.
 *
 * Aive remains authoritative for workflow state and acceptance gates. Moyai owns only the remote
 * coding session/workspace. The persisted Moyai run id is the [ProviderRunId], so process restarts
 * reconnect instead of dispatching duplicate work.
 */
class MoyaiProvider(
    override val id: AgentProviderId,
    private val displayName: String,
    baseUrl: String,
    workspacePassword: String? = null,
    client: HttpClient = moyaiClient(),
    private val pollIntervalMillis: Long = 1_000L,
) : AgentProvider {
    private val api = MoyaiApi(baseUrl, workspacePassword, client)
    private val sessionsMutex = Mutex()
    private val sessions = mutableMapOf<ProviderRunId, AgentTaskRequest>()

    init {
        require(pollIntervalMillis >= 250L) { "pollIntervalMillis must be at least 250ms" }
    }

    override suspend fun capabilities(): AgentCapabilities = AgentCapabilities(
        supported = setOf(
            AgentCapability.RepositoryRead,
            AgentCapability.RepositoryWrite,
            AgentCapability.PlanGeneration,
            AgentCapability.PlanApproval,
            AgentCapability.Messaging,
            AgentCapability.ShellExecution,
            AgentCapability.Testing,
            AgentCapability.TestAuthoring,
            AgentCapability.PullRequestCreation,
        ),
        requiresEnvironmentPlanning = true,
    )

    override suspend fun supportsRepository(repository: RepositoryRef?): Boolean =
        repository == null || repository.source == RepositorySource.GitHub

    /**
     * Aive owns this approval gate. Drafting is deliberately local and side-effect free so a Moyai
     * workspace does not start before the user approves the execution shape.
     */
    override suspend fun draftPlan(request: AgentTaskRequest): String = buildString {
        appendLine("1. Start an isolated Moyai workspace for this Aive task.")
        if (request.repository != null) {
            appendLine("2. Inspect the linked repository and the approved upstream context without changing unrelated work.")
            appendLine("3. Perform the assigned role work against the task objective and acceptance criteria.")
        } else {
            appendLine("2. Perform the assigned role work in a repoless isolated workspace.")
            appendLine("3. Keep all generated work scoped to the task objective and approved context.")
        }
        appendLine("4. Run the verification available in the workspace and preserve its evidence.")
        val outputs = request.requiredArtifacts
            .map { it.name }
            .sorted()
            .joinToString()
            .ifBlank { "the final result" }
        appendLine("5. Return $outputs to Aive; Aive decides acceptance, integration, and downstream execution.")
    }.trim()

    override suspend fun start(request: AgentTaskRequest): AgentRunHandle {
        require(supportsRepository(request.repository)) {
            "$displayName currently accepts repoless work or GitHub repositories"
        }
        val run = api.start(
            prompt = request.toMoyaiPrompt(),
            repositoryUrl = request.repository?.let { "https://github.com/${it.owner}/${it.name}" }.orEmpty(),
        )
        val runId = ProviderRunId(run.id)
        sessionsMutex.withLock { sessions[runId] = request }
        return AgentRunHandle(runId)
    }

    override suspend fun reconnect(
        runId: ProviderRunId,
        request: AgentTaskRequest,
        planGenerated: Boolean,
        planApproved: Boolean,
        planPreview: String?,
    ): ProviderActionResult {
        val available = runCatching { api.getRun(runId.value) }.isSuccess
        if (!available) return ProviderActionResult.Rejected("Moyai session ${runId.value} is unavailable")
        sessionsMutex.withLock { sessions[runId] = request }
        return ProviderActionResult.Accepted
    }

    override fun observe(runId: ProviderRunId): Flow<AgentEvent> = flow {
        val request = sessionsMutex.withLock { sessions[runId] }
            ?: error("Moyai session ${runId.value} has no restored Aive request")

        var initialized = false
        var lastEventId = 0L
        var lastMessageId = 0L
        while (true) {
            val snapshot = api.getRun(runId.value)
            if (!initialized) {
                lastEventId = snapshot.events.maxOfOrNull { it.id } ?: 0L
                lastMessageId = snapshot.messages.maxOfOrNull { it.id } ?: 0L
                initialized = true
                emit(AgentEvent.Progress(runId, "Connected to Moyai session ${runId.value.take(8)}"))
            } else {
                snapshot.events.filter { it.id > lastEventId }.forEach { event ->
                    lastEventId = maxOf(lastEventId, event.id)
                    when (event.kind) {
                        "chat" -> emit(AgentEvent.Message(runId, event.message))
                        "error" -> emit(AgentEvent.Progress(runId, event.message))
                        else -> emit(AgentEvent.Progress(runId, event.message))
                    }
                }
                snapshot.messages
                    .filter { it.id > lastMessageId && it.role == "assistant" && it.content.isNotBlank() }
                    .forEach { message ->
                        lastMessageId = maxOf(lastMessageId, message.id)
                        emit(AgentEvent.Message(runId, message.content))
                    }
            }

            when (snapshot.status.lowercase()) {
                "completed", "idle" -> {
                    terminalArtifacts(request, snapshot, runId).forEach {
                        emit(AgentEvent.ArtifactProduced(runId, it))
                    }
                    emit(AgentEvent.Completed(runId))
                    sessionsMutex.withLock { sessions.remove(runId) }
                    return@flow
                }
                "failed" -> {
                    val reason = snapshot.events.lastOrNull { it.kind == "error" }?.message
                        ?: "Moyai session failed"
                    emit(AgentEvent.Failed(runId, reason))
                    sessionsMutex.withLock { sessions.remove(runId) }
                    return@flow
                }
                "cancelled", "interrupted" -> {
                    emit(AgentEvent.Failed(runId, "Moyai session ${snapshot.status.lowercase()}"))
                    sessionsMutex.withLock { sessions.remove(runId) }
                    return@flow
                }
            }
            delay(pollIntervalMillis)
        }
    }

    override suspend fun sendMessage(runId: ProviderRunId, message: String): ProviderActionResult =
        runCatching {
            api.sendMessage(runId.value, message, clientId(runId, message))
            ProviderActionResult.Accepted
        }.getOrElse { failure ->
            ProviderActionResult.Rejected(failure.message ?: "Unable to message Moyai session")
        }

    override suspend fun approvePlan(runId: ProviderRunId): ProviderActionResult =
        ProviderActionResult.Rejected(
            "Moyai uses Aive's engine-owned pre-dispatch plan gate; no provider-side plan approval is expected",
        )

    override suspend fun cancel(runId: ProviderRunId): ProviderActionResult =
        runCatching {
            api.cancel(runId.value)
            sessionsMutex.withLock { sessions.remove(runId) }
            ProviderActionResult.Accepted
        }.getOrElse { failure ->
            ProviderActionResult.Rejected(failure.message ?: "Unable to cancel Moyai session")
        }

    private fun terminalArtifacts(
        request: AgentTaskRequest,
        snapshot: MoyaiRun,
        runId: ProviderRunId,
    ): List<ProviderArtifact> {
        val finalText = snapshot.messages
            .lastOrNull { it.role == "assistant" && it.content.isNotBlank() }
            ?.content
        val required = request.requiredArtifacts.ifEmpty { setOf(ArtifactKind.CommandOutput) }
        return buildList {
            required.forEach { kind ->
                when (kind) {
                    ArtifactKind.PullRequest -> snapshot.pullRequests.firstOrNull()?.let { pr ->
                        add(
                            ProviderArtifact(
                                kind = kind,
                                label = pr.title.ifBlank { "Moyai pull request" },
                                uri = pr.url,
                                metadata = mapOf("moyaiRunId" to runId.value),
                            ),
                        )
                    }
                    ArtifactKind.CodeChange -> if (snapshot.hasArtifact) {
                        add(
                            ProviderArtifact(
                                kind = kind,
                                label = "Moyai result archive",
                                uri = api.artifactUrl(runId.value),
                                textContent = finalText,
                                metadata = mapOf("moyaiRunId" to runId.value),
                            ),
                        )
                    } else if (!finalText.isNullOrBlank()) {
                        add(textArtifact(kind, finalText, runId))
                    }
                    else -> if (!finalText.isNullOrBlank()) add(textArtifact(kind, finalText, runId))
                }
            }
        }
    }

    private fun textArtifact(kind: ArtifactKind, text: String, runId: ProviderRunId) = ProviderArtifact(
        kind = kind,
        label = "Moyai ${kind.name}",
        textContent = text,
        mediaType = "text/markdown",
        metadata = mapOf("moyaiRunId" to runId.value),
    )

    private fun clientId(runId: ProviderRunId, message: String): String =
        "aive-${runId.value.hashCode().toUInt().toString(16)}-${message.hashCode().toUInt().toString(16)}"
}

private fun AgentTaskRequest.toMoyaiPrompt(): String = buildString {
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
}

private class MoyaiApi(
    baseUrl: String,
    private val workspacePassword: String?,
    private val client: HttpClient,
) {
    private val baseUrl = baseUrl.trimEnd('/')
    private val parsed = Url(this.baseUrl)
    private val origin = buildString {
        append(parsed.protocol.name)
        append("://")
        append(parsed.host)
        if (parsed.port != parsed.protocol.defaultPort) append(':').append(parsed.port)
    }
    private var csrf: String? = null

    init {
        require(this.baseUrl.startsWith("https://", ignoreCase = true)) { "Moyai baseUrl must use HTTPS" }
    }

    suspend fun start(prompt: String, repositoryUrl: String): MoyaiRun {
        ensureSession()
        return client.post("$baseUrl/api/runs") {
            mutate()
            contentType(ContentType.Application.Json)
            setBody(
                MoyaiNewRun(
                    prompt = prompt,
                    repoUrl = repositoryUrl,
                    mode = "modal",
                    chatEnabled = true,
                ),
            )
        }.requireSuccess("start Moyai session").body()
    }

    suspend fun getRun(runId: String): MoyaiRun {
        ensureSession()
        return client.get("$baseUrl/api/runs/$runId").requireSuccess("read Moyai session").body()
    }

    suspend fun sendMessage(runId: String, message: String, clientId: String) {
        ensureSession()
        client.post("$baseUrl/api/runs/$runId/messages") {
            mutate()
            contentType(ContentType.Application.Json)
            setBody(MoyaiChatMessage(content = message, clientId = clientId, sendNow = true))
        }.requireSuccess("message Moyai session")
    }

    suspend fun cancel(runId: String) {
        ensureSession()
        client.post("$baseUrl/api/runs/$runId/cancel") { mutate() }
            .requireSuccess("cancel Moyai session")
    }

    fun artifactUrl(runId: String): String = "$baseUrl/api/runs/$runId/artifact"

    private suspend fun ensureSession() {
        if (csrf != null) return
        var session = client.get("$baseUrl/api/session").requireSuccess("read Moyai login session").body<MoyaiSession>()
        if (!session.authenticated) {
            val password = workspacePassword?.trim().orEmpty()
            require(password.isNotEmpty()) {
                "Moyai requires workspace authentication; connect this provider with the workspace password"
            }
            client.post("$baseUrl/api/login") {
                header(HttpHeaders.Origin, origin)
                contentType(ContentType.Application.Json)
                setBody(MoyaiLogin(password))
            }.requireSuccess("sign in to Moyai")
            session = client.get("$baseUrl/api/session").requireSuccess("confirm Moyai login session").body()
        }
        require(session.authenticated && session.csrf.isNotBlank()) { "Moyai workspace did not establish an authenticated session" }
        csrf = session.csrf
    }

    private fun io.ktor.client.request.HttpRequestBuilder.mutate() {
        header(HttpHeaders.Origin, origin)
        header("X-CSRF-Token", requireNotNull(csrf))
    }

    private suspend fun HttpResponse.requireSuccess(operation: String): HttpResponse {
        if (status.value in 200..299) return this
        val detail = bodyAsText().take(800)
        error("Unable to $operation: HTTP ${status.value}" + detail.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty())
    }
}

private fun moyaiClient(): HttpClient = HttpClient {
    expectSuccess = false
    followRedirects = false
    install(HttpTimeout) {
        requestTimeoutMillis = 60_000L
        connectTimeoutMillis = 15_000L
    }
    install(HttpCookies) { storage = AcceptAllCookiesStorage() }
    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; explicitNulls = false })
    }
}

@Serializable
private data class MoyaiSession(
    val authenticated: Boolean = false,
    val csrf: String = "",
)

@Serializable
private data class MoyaiLogin(val password: String)

@Serializable
private data class MoyaiNewRun(
    val prompt: String,
    @SerialName("repo_url") val repoUrl: String = "",
    val mode: String = "modal",
    @SerialName("chat_enabled") val chatEnabled: Boolean = true,
)

@Serializable
private data class MoyaiChatMessage(
    val content: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("send_now") val sendNow: Boolean = true,
)

@Serializable
private data class MoyaiRun(
    val id: String,
    val status: String = "",
    val events: List<MoyaiEvent> = emptyList(),
    val messages: List<MoyaiMessage> = emptyList(),
    @SerialName("pull_requests") val pullRequests: List<MoyaiPullRequest> = emptyList(),
    @SerialName("has_artifact") val hasArtifact: Boolean = false,
)

@Serializable
private data class MoyaiEvent(
    val id: Long = 0,
    val kind: String = "",
    val message: String = "",
)

@Serializable
private data class MoyaiMessage(
    val id: Long = 0,
    val role: String = "",
    val content: String = "",
)

@Serializable
private data class MoyaiPullRequest(
    val url: String = "",
    val title: String = "",
)
