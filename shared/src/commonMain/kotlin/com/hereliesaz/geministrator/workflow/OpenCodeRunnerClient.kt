package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.RepositoryRef
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import kotlin.io.encoding.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A dispatched run of the OpenCode agent workflow, found by its `run-name`. */
data class OpenCodeWorkflowRunRef(
    val id: String,
    val runName: String,
    val headSha: String?,
)

/** The GitHub calls the OpenCode agent provider makes beyond plain workflow dispatch. */
interface OpenCodeRunnerClient {
    suspend fun defaultBranch(repository: RepositoryRef): String

    /** The workflow file's text on [branch], or null when it is not installed. */
    suspend fun workflowFile(repository: RepositoryRef, branch: String): String?

    /** Creates or replaces the workflow file on [branch] with [content]. */
    suspend fun writeWorkflowFile(repository: RepositoryRef, branch: String, content: String, message: String)

    suspend fun dispatch(repository: RepositoryRef, branch: String, taskJson: String): String

    /** Recent dispatches of the workflow whose run name is [runName], newest first. */
    suspend fun findRun(repository: RepositoryRef, runName: String): OpenCodeWorkflowRunRef?

    suspend fun getRun(repository: RepositoryRef, runId: String): GitHubWorkflowRun

    /** The agent's step log: the named check run's `output.text` on [headSha]. */
    suspend fun checkRunText(repository: RepositoryRef, headSha: String, checkName: String): String?

    suspend fun downloadArtifact(repository: RepositoryRef, artifactId: String): ByteArray

    suspend fun cancelRun(repository: RepositoryRef, runId: String)
}

class GitHubRestOpenCodeRunnerClient(
    private val tokenProvider: GitHubTokenProvider,
    private val httpClient: HttpClient,
    private val baseUrl: String = "https://api.github.com",
    private val json: Json = Json { ignoreUnknownKeys = true },
) : OpenCodeRunnerClient {
    private val actions = GitHubRestActionsClient(tokenProvider, httpClient, baseUrl, json)

    override suspend fun defaultBranch(repository: RepositoryRef): String {
        val token = token()
        val body = httpClient.get(repositoryUrl(repository)) { githubHeaders(token) }
            .requireSuccessBody("read repository ${repository.owner}/${repository.name}")
        return json.decodeFromString<RepositoryResponse>(body).defaultBranch
    }

    override suspend fun workflowFile(repository: RepositoryRef, branch: String): String? {
        val token = token()
        val response = httpClient.get(contentsUrl(repository)) {
            githubHeaders(token)
            parameter("ref", branch)
        }
        if (response.status.value == 404) return null
        val body = response.requireSuccessBody("read ${OpenCodeAgentWorkflow.PATH}")
        val encoded = json.decodeFromString<ContentResponse>(body).content.filterNot(Char::isWhitespace)
        return Base64.decode(encoded).decodeToString()
    }

    override suspend fun writeWorkflowFile(
        repository: RepositoryRef,
        branch: String,
        content: String,
        message: String,
    ) {
        val token = token()
        val existingSha = httpClient.get(contentsUrl(repository)) {
            githubHeaders(token)
            parameter("ref", branch)
        }.let { response ->
            if (response.status.value == 404) {
                null
            } else {
                json.decodeFromString<ContentResponse>(response.requireSuccessBody("read ${OpenCodeAgentWorkflow.PATH}")).sha
            }
        }
        httpClient.put(contentsUrl(repository)) {
            githubHeaders(token)
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    ContentWrite(
                        message = message,
                        content = Base64.encode(content.encodeToByteArray()),
                        branch = branch,
                        sha = existingSha,
                    ),
                ),
            )
        }.requireSuccessBody(
            "install ${OpenCodeAgentWorkflow.PATH} (the GitHub token needs Contents and Workflows write access)",
        )
    }

    override suspend fun dispatch(repository: RepositoryRef, branch: String, taskJson: String): String =
        actions.dispatch(
            GitHubWorkflowDispatchRequest(
                repository = repository,
                workflow = OpenCodeAgentWorkflow.FILE_NAME,
                ref = branch,
                inputs = mapOf(OpenCodeAgentWorkflow.TASK_INPUT to taskJson),
            ),
        ).id

    override suspend fun findRun(repository: RepositoryRef, runName: String): OpenCodeWorkflowRunRef? {
        val token = token()
        val response = httpClient.get(
            "${repositoryUrl(repository)}/actions/workflows/${OpenCodeAgentWorkflow.FILE_NAME.encodeURLPathPart()}/runs",
        ) {
            githubHeaders(token)
            parameter("event", "workflow_dispatch")
            parameter("per_page", 50)
        }
        if (response.status.value == 404) return null
        val runs = json.decodeFromString<RunsResponse>(response.requireSuccessBody("list OpenCode agent runs"))
        return runs.workflowRuns.firstOrNull { it.displayTitle == runName }?.let { run ->
            OpenCodeWorkflowRunRef(id = run.id.toString(), runName = runName, headSha = run.headSha)
        }
    }

    override suspend fun getRun(repository: RepositoryRef, runId: String): GitHubWorkflowRun =
        actions.getRun(repository, runId)

    override suspend fun checkRunText(repository: RepositoryRef, headSha: String, checkName: String): String? {
        val token = token()
        val body = httpClient.get("${repositoryUrl(repository)}/commits/${headSha.encodeURLPathPart()}/check-runs") {
            githubHeaders(token)
            parameter("check_name", checkName)
        }.requireSuccessBody("read the OpenCode agent's progress")
        return json.decodeFromString<CheckRunsResponse>(body).checkRuns.firstOrNull()?.output?.text
    }

    override suspend fun downloadArtifact(repository: RepositoryRef, artifactId: String): ByteArray =
        actions.downloadArtifact(repository, artifactId)

    override suspend fun cancelRun(repository: RepositoryRef, runId: String) {
        val token = token()
        httpClient.post("${repositoryUrl(repository)}/actions/runs/${runId.encodeURLPathPart()}/cancel") {
            githubHeaders(token)
        }.requireSuccessBody("cancel OpenCode agent run $runId")
    }

    private fun repositoryUrl(repository: RepositoryRef) =
        "$baseUrl/repos/${repository.owner.encodeURLPathPart()}/${repository.name.encodeURLPathPart()}"

    private fun contentsUrl(repository: RepositoryRef) =
        "${repositoryUrl(repository)}/contents/${OpenCodeAgentWorkflow.PATH}"

    private suspend fun token(): String = tokenProvider.getToken().trim().also {
        require(it.isNotEmpty()) { "GitHub token is not configured" }
    }

    private fun HttpRequestBuilder.githubHeaders(token: String) {
        header(HttpHeaders.Accept, "application/vnd.github+json")
        header(HttpHeaders.Authorization, "Bearer $token")
        header("X-GitHub-Api-Version", "2026-03-10")
    }

    private suspend fun HttpResponse.requireSuccessBody(operation: String): String {
        val body = bodyAsText()
        if (status.value !in 200..299) {
            error(
                "Unable to $operation: HTTP ${status.value}" +
                    body.take(500).takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty(),
            )
        }
        return body
    }

    @Serializable
    private data class RepositoryResponse(@SerialName("default_branch") val defaultBranch: String)

    @Serializable
    private data class ContentResponse(val sha: String, val content: String = "")

    @Serializable
    private data class ContentWrite(
        val message: String,
        val content: String,
        val branch: String,
        val sha: String? = null,
    )

    @Serializable
    private data class RunsResponse(@SerialName("workflow_runs") val workflowRuns: List<RunSummary> = emptyList())

    @Serializable
    private data class RunSummary(
        val id: Long,
        @SerialName("display_title") val displayTitle: String? = null,
        @SerialName("head_sha") val headSha: String? = null,
    )

    @Serializable
    private data class CheckRunsResponse(@SerialName("check_runs") val checkRuns: List<CheckRun> = emptyList())

    @Serializable
    private data class CheckRun(val output: CheckRunOutput? = null)

    @Serializable
    private data class CheckRunOutput(val text: String? = null)
}
