package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.RepositorySource
import com.hereliesaz.geministrator.domain.ScriptLanguage
import com.hereliesaz.geministrator.domain.ScriptRunner
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.displayName
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import no.synth.kmpzip.zip.ZipInputStream

data class GitHubWorkflowDispatchRequest(
    val repository: RepositoryRef,
    val workflow: String,
    val ref: String,
    val inputs: Map<String, String> = emptyMap(),
)

data class GitHubWorkflowArtifact(
    val id: String,
    val name: String,
    val archiveDownloadUrl: String?,
)

data class GitHubWorkflowJob(
    val id: String,
    val name: String,
    val status: GitHubWorkflowRunStatus,
    val currentStep: String? = null,
    val completedSteps: Int = 0,
    val totalSteps: Int = 0,
)

data class GitHubWorkflowRun(
    val id: String,
    val status: GitHubWorkflowRunStatus,
    val artifacts: List<GitHubWorkflowArtifact> = emptyList(),
    val jobs: List<GitHubWorkflowJob> = emptyList(),
    val progressMessage: String? = null,
)

enum class GitHubWorkflowRunStatus { Queued, Running, Completed, Failed }

fun interface GitHubTokenProvider {
    suspend fun getToken(): String
}

interface GitHubActionsClient {
    suspend fun dispatch(request: GitHubWorkflowDispatchRequest): GitHubWorkflowRun
    suspend fun getRun(repository: RepositoryRef, runId: String): GitHubWorkflowRun
    suspend fun downloadArtifact(repository: RepositoryRef, artifactId: String): ByteArray
}

class GitHubRestActionsClient(
    private val tokenProvider: GitHubTokenProvider,
    private val httpClient: HttpClient = HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000L
            connectTimeoutMillis = 10_000L
        }
    },
    private val baseUrl: String = "https://api.github.com",
    private val json: Json = Json { ignoreUnknownKeys = true },
) : GitHubActionsClient {
    override suspend fun dispatch(request: GitHubWorkflowDispatchRequest): GitHubWorkflowRun {
        val token = requireToken()
        val repository = request.repository
        val responseBody = httpClient.post(
            "$baseUrl/repos/${repository.owner.encodeURLPathPart()}/${repository.name.encodeURLPathPart()}/actions/workflows/${request.workflow.encodeURLPathPart()}/dispatches",
        ) {
            githubHeaders(token)
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    DispatchBody(
                        ref = request.ref,
                        inputs = request.inputs,
                        returnRunDetails = true,
                    ),
                ),
            )
        }.requireSuccessBody("dispatch GitHub Actions workflow ${request.workflow}")
        require(responseBody.isNotBlank()) {
            "GitHub accepted workflow dispatch ${request.workflow} without returning the requested run details"
        }
        val dispatch = json.decodeFromString<DispatchResponse>(responseBody)
        return GitHubWorkflowRun(
            id = dispatch.workflowRunId.toString(),
            status = GitHubWorkflowRunStatus.Queued,
            progressMessage = "Workflow dispatch accepted",
        )
    }

    override suspend fun getRun(repository: RepositoryRef, runId: String): GitHubWorkflowRun {
        val token = requireToken()
        val baseRepositoryUrl = "$baseUrl/repos/${repository.owner.encodeURLPathPart()}/${repository.name.encodeURLPathPart()}"
        val runBody = httpClient.get("$baseRepositoryUrl/actions/runs/${runId.encodeURLPathPart()}") {
            githubHeaders(token)
        }.requireSuccessBody("read GitHub Actions run $runId")
        val run = json.decodeFromString<RunResponse>(runBody)
        val artifactsBody = httpClient.get("$baseRepositoryUrl/actions/runs/${runId.encodeURLPathPart()}/artifacts") {
            githubHeaders(token)
        }.requireSuccessBody("read artifacts for GitHub Actions run $runId")
        val artifacts = json.decodeFromString<ArtifactsResponse>(artifactsBody).artifacts
            .filterNot(ArtifactResponse::expired)
            .map { artifact ->
                GitHubWorkflowArtifact(
                    artifact.id.toString(),
                    artifact.name,
                    artifact.archiveDownloadUrl,
                )
            }
        val jobsBody = httpClient.get("$baseRepositoryUrl/actions/runs/${runId.encodeURLPathPart()}/jobs") {
            githubHeaders(token)
        }.requireSuccessBody("read jobs for GitHub Actions run $runId")
        val jobs = json.decodeFromString<JobsResponse>(jobsBody).jobs.map { job ->
            val steps = job.steps
            val completedSteps = steps.count { it.status == "completed" }
            val currentStep = steps.firstOrNull { it.status == "in_progress" }?.name
            GitHubWorkflowJob(
                id = job.id.toString(),
                name = job.name,
                status = when (job.status) {
                    "completed" -> if (job.conclusion == "success") {
                        GitHubWorkflowRunStatus.Completed
                    } else {
                        GitHubWorkflowRunStatus.Failed
                    }
                    "queued", "waiting" -> GitHubWorkflowRunStatus.Queued
                    else -> GitHubWorkflowRunStatus.Running
                },
                currentStep = currentStep,
                completedSteps = completedSteps,
                totalSteps = steps.size,
            )
        }
        return run.toWorkflowRun(artifacts, jobs)
    }

    private fun RunResponse.toWorkflowRun(
        artifacts: List<GitHubWorkflowArtifact> = emptyList(),
        jobs: List<GitHubWorkflowJob> = emptyList(),
    ) = GitHubWorkflowRun(
        id = id.toString(),
        status = toStatus(),
        artifacts = artifacts,
        jobs = jobs,
        progressMessage = when {
            conclusion == "cancelled" -> "Run was cancelled"
            conclusion == "timed_out" -> "Run timed out"
            conclusion == "stale" -> "Run became stale and was abandoned"
            conclusion == "action_required" -> "Run requires manual action"
            conclusion == "skipped" -> "Run was skipped"
            conclusion != null -> conclusion
            else -> status
        },
    )

    override suspend fun downloadArtifact(
        repository: RepositoryRef,
        artifactId: String,
    ): ByteArray {
        val token = requireToken()
        val response = httpClient.get(
            "$baseUrl/repos/${repository.owner.encodeURLPathPart()}/${repository.name.encodeURLPathPart()}/actions/artifacts/${artifactId.encodeURLPathPart()}/zip",
        ) {
            githubHeaders(token)
        }
        if (response.status.value !in 200..299) {
            error("Unable to download GitHub Actions artifact $artifactId: HTTP ${response.status.value}")
        }
        return response.body()
    }

    private suspend fun requireToken(): String = tokenProvider.getToken().trim().also {
        require(it.isNotEmpty()) { "GitHub Actions token is not configured" }
    }

    private fun HttpRequestBuilder.githubHeaders(token: String) {
        header(HttpHeaders.Accept, "application/vnd.github+json")
        header(HttpHeaders.Authorization, "Bearer $token")
        header("X-GitHub-Api-Version", API_VERSION)
    }

    private suspend fun HttpResponse.requireSuccessBody(operation: String): String {
        val body = bodyAsText()
        if (status.value !in 200..299) {
            error(
                "Unable to $operation: HTTP ${status.value}${body.take(500).takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}",
            )
        }
        return body
    }

    private fun RunResponse.toStatus(): GitHubWorkflowRunStatus = when (status) {
        "completed" -> when (conclusion) {
            "success" -> GitHubWorkflowRunStatus.Completed
            "cancelled", "skipped", "stale", "timed_out", "action_required",
            "neutral", "failure",
            -> GitHubWorkflowRunStatus.Failed
            else -> GitHubWorkflowRunStatus.Failed
        }
        "queued", "waiting", "pending", "requested" -> GitHubWorkflowRunStatus.Queued
        else -> GitHubWorkflowRunStatus.Running
    }

    @Serializable
    private data class DispatchBody(
        val ref: String,
        val inputs: Map<String, String> = emptyMap(),
        @SerialName("return_run_details") val returnRunDetails: Boolean,
    )

    @Serializable
    private data class DispatchResponse(
        @SerialName("workflow_run_id") val workflowRunId: Long,
    )

    @Serializable
    private data class RunResponse(
        val id: Long,
        val status: String,
        val conclusion: String? = null,
    )

    @Serializable
    private data class ArtifactsResponse(
        val artifacts: List<ArtifactResponse> = emptyList(),
    )

    @Serializable
    private data class ArtifactResponse(
        val id: Long,
        val name: String,
        val expired: Boolean = false,
        @SerialName("archive_download_url") val archiveDownloadUrl: String? = null,
    )

    @Serializable
    private data class JobsResponse(
        val jobs: List<JobResponse> = emptyList(),
    )

    @Serializable
    private data class JobResponse(
        val id: Long,
        val name: String,
        val status: String,
        val conclusion: String? = null,
        val steps: List<StepResponse> = emptyList(),
    )

    @Serializable
    private data class StepResponse(
        val name: String,
        val status: String,
        val conclusion: String? = null,
    )

    private companion object {
        const val API_VERSION = "2026-03-10"
    }
}

class GitHubActionsExecutorIntegration(
    private val client: GitHubActionsClient,
    private val surfaceRuntime: RoleSurfaceRuntimeRegistry = RoleSurfaceRuntimeRegistry.Empty,
    private val json: Json = Json { encodeDefaults = true; ignoreUnknownKeys = true },
) : TaskExecutorIntegration {
    override fun supports(executor: TaskExecutor): Boolean = when (executor) {
        is TaskExecutor.GitHubAction -> true
        is TaskExecutor.Script -> executor.runner is ScriptRunner.GitHubActions
        else -> false
    }

    override suspend fun dispatch(context: TaskExecutorContext): TaskExecutorExecution {
        val repository = requireGitHubRepository(context)
        val surfaces = surfaceRuntime.resolve(context.role?.surfaces.orEmpty())
        val envelope = json.encodeToString(
            AiveTaskEnvelope.serializer(),
            context.toAiveTaskEnvelope(surfaces),
        )
        val request = when (val executor = context.executor) {
            is TaskExecutor.GitHubAction -> {
                val ref = executor.ref ?: repository.defaultBranch
                require(!ref.isNullOrBlank()) {
                    "GitHub Action executor requires an explicit ref or repository default branch"
                }
                val inputs = executor.inputs.toMutableMap()
                executor.contextInput
                    ?.trim()
                    ?.takeIf(String::isNotEmpty)
                    ?.let { inputs[it] = envelope }
                GitHubWorkflowDispatchRequest(repository, executor.workflow, ref, inputs)
            }
            is TaskExecutor.Script -> {
                val runner = executor.runner as? ScriptRunner.GitHubActions
                    ?: error("Script executor is not configured for GitHub Actions")
                val ref = runner.ref ?: repository.defaultBranch
                require(!ref.isNullOrBlank()) {
                    "GitHub script runner requires an explicit ref or repository default branch"
                }
                require(runner.workflow.isNotBlank()) { "GitHub script runner workflow is required" }
                require(executor.source.length <= MAX_SCRIPT_INPUT_CHARS) {
                    "Inline script exceeds GitHub workflow input limit; keep it under $MAX_SCRIPT_INPUT_CHARS characters"
                }
                GitHubWorkflowDispatchRequest(
                    repository = repository,
                    workflow = runner.workflow,
                    ref = ref,
                    inputs = mapOf(
                        runner.contextInput to envelope,
                        runner.scriptInput to executor.source,
                        runner.languageInput to when (executor.language) {
                            ScriptLanguage.JavaScript -> "javascript"
                            ScriptLanguage.Python -> "python"
                        },
                    ),
                )
            }
            else -> error("Unsupported GitHub executor")
        }
        return client.dispatch(request).toExecution(context)
    }

    override suspend fun reconcile(context: TaskExecutorContext): TaskExecutorExecution {
        val repository = requireGitHubRepository(context)
        val runId = requireNotNull(context.taskRun.externalRunId) {
            "GitHub Action task ${context.task.id.value} is missing its external run ID"
        }
        val run = client.getRun(repository, runId)
        val scriptResult = if (
            run.status == GitHubWorkflowRunStatus.Completed
        ) {
            run.artifacts
                .firstOrNull { it.name == AIVE_RESULT_ARTIFACT }
                ?.let { artifact ->
                    parseScriptResultArchive(client.downloadArtifact(repository, artifact.id))
                }
        } else {
            null
        }
        scriptResult?.surfaceMutations
            ?.takeIf { it.isNotEmpty() }
            ?.let { mutations ->
                surfaceRuntime.apply(
                    surfaces = context.role?.surfaces.orEmpty(),
                    mutations = mutations,
                    executionKey = "github:${context.taskRun.id.value}:${context.taskRun.attempt}",
                )
            }
        return run.toExecution(context, scriptResult)
    }

    private fun requireGitHubRepository(context: TaskExecutorContext): RepositoryRef {
        val repository = requireNotNull(context.project.repository) {
            "GitHub Action executor requires a project repository"
        }
        require(repository.source == RepositorySource.GitHub) {
            "GitHub Action executor requires a GitHub repository; project is linked to ${repository.source.displayName()}."
        }
        return repository
    }

    private fun GitHubWorkflowRun.toExecution(
        context: TaskExecutorContext,
        scriptResult: AiveScriptResult? = null,
    ): TaskExecutorExecution {
        val taskStatus = when (status) {
            GitHubWorkflowRunStatus.Queued,
            GitHubWorkflowRunStatus.Running,
            -> TaskRunStatus.Running
            GitHubWorkflowRunStatus.Completed -> TaskRunStatus.Completed
            GitHubWorkflowRunStatus.Failed -> TaskRunStatus.Failed
        }
        val totalSteps = jobs.sumOf { it.totalSteps }
        val completedSteps = jobs.sumOf { it.completedSteps }
        val stepProgress = if (totalSteps > 0) completedSteps.toFloat() / totalSteps else null
        val activeJob = jobs.firstOrNull { it.status == GitHubWorkflowRunStatus.Running }
        val stepMessage = activeJob?.currentStep?.let { step ->
            if (activeJob.totalSteps > 0) {
                "${activeJob.name}: $step (${activeJob.completedSteps + 1}/${activeJob.totalSteps})"
            } else {
                "${activeJob.name}: $step"
            }
        } ?: progressMessage
        val scriptFailed = scriptResult?.status?.let { status ->
            status.equals("failed", ignoreCase = true) || status.equals("error", ignoreCase = true)
        } == true
        val resultArtifacts = scriptResult?.toArtifactRefs(context).orEmpty()
        val workflowArtifacts = artifacts
            .filterNot { scriptResult != null && it.name == AIVE_RESULT_ARTIFACT }
            .map { artifact ->
                ArtifactRef(
                    ArtifactId(
                        "${context.taskRun.id.value}:github-action:${context.taskRun.attempt}:${artifact.id}",
                    ),
                    ArtifactKind.CommandOutput,
                    context.taskRun.id,
                    artifact.name,
                    uri = artifact.archiveDownloadUrl,
                    createdAtEpochMillis = context.nowEpochMillis,
                )
            }
        return TaskExecutorExecution(
            status = if (scriptFailed) TaskRunStatus.Failed else taskStatus,
            externalRunId = id,
            artifacts = resultArtifacts + workflowArtifacts,
            progress = if (taskStatus == TaskRunStatus.Completed && !scriptFailed) 1f else stepProgress,
            progressMessage = scriptResult?.message ?: stepMessage,
        )
    }
    private fun parseScriptResultArchive(bytes: ByteArray): AiveScriptResult {
        require(bytes.size <= MAX_RESULT_ARCHIVE_BYTES) {
            "Aive result artifact exceeds $MAX_RESULT_ARCHIVE_BYTES bytes"
        }
        ZipInputStream(bytes).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && entry.name.substringAfterLast('/') == AIVE_RESULT_FILE) {
                    require(entry.size < 0 || entry.size <= MAX_RESULT_JSON_BYTES) {
                        "Aive result JSON exceeds $MAX_RESULT_JSON_BYTES bytes"
                    }
                    val resultBytes = zip.readBytes()
                    require(resultBytes.size <= MAX_RESULT_JSON_BYTES) {
                        "Aive result JSON exceeds $MAX_RESULT_JSON_BYTES bytes"
                    }
                    return json.decodeFromString(AiveScriptResult.serializer(), resultBytes.decodeToString())
                }
                zip.closeEntry()
            }
        }
        error("GitHub script runner artifact does not contain $AIVE_RESULT_FILE")
    }

    private fun AiveScriptResult.toArtifactRefs(context: TaskExecutorContext): List<ArtifactRef> = buildList {
        artifacts.forEachIndexed { index, artifact ->
            require(artifact.uri != null || artifact.textContent != null) {
                "Script artifact ${artifact.label} must return uri or textContent"
            }
            add(
                ArtifactRef(
                    id = ArtifactId(
                        "${context.taskRun.id.value}:script:${context.taskRun.attempt}:$index",
                    ),
                    kind = ArtifactKind.entries.firstOrNull {
                        it.name.equals(artifact.kind, ignoreCase = true)
                    } ?: ArtifactKind.CommandOutput,
                    taskRunId = context.taskRun.id,
                    label = artifact.label,
                    uri = artifact.uri,
                    textContent = artifact.textContent,
                    mediaType = artifact.mediaType,
                    metadata = artifact.metadata,
                    createdAtEpochMillis = context.nowEpochMillis,
                ),
            )
        }
        output?.takeIf(String::isNotBlank)?.let { value ->
            add(
                ArtifactRef(
                    id = ArtifactId(
                        "${context.taskRun.id.value}:script:${context.taskRun.attempt}:output",
                    ),
                    kind = ArtifactKind.CommandOutput,
                    taskRunId = context.taskRun.id,
                    label = "Script output",
                    textContent = value,
                    mediaType = "text/plain",
                    createdAtEpochMillis = context.nowEpochMillis,
                ),
            )
        }
    }

    private companion object {
        const val MAX_SCRIPT_INPUT_CHARS = 50_000
        const val AIVE_RESULT_ARTIFACT = "aive-result"
        const val AIVE_RESULT_FILE = "aive-result.json"
        const val MAX_RESULT_ARCHIVE_BYTES = 2 * 1024 * 1024
        const val MAX_RESULT_JSON_BYTES = 1024 * 1024
    }

}
