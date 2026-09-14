package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.RepositorySource
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.displayName
import io.ktor.client.HttpClient
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

data class GitHubWorkflowDispatchRequest(
    val repository: RepositoryRef,
    val workflow: String,
    val ref: String,
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
) : TaskExecutorIntegration {
    override fun supports(executor: TaskExecutor): Boolean = executor is TaskExecutor.GitHubAction

    override suspend fun dispatch(context: TaskExecutorContext): TaskExecutorExecution {
        val executor = context.executor as TaskExecutor.GitHubAction
        val repository = requireGitHubRepository(context)
        val ref = executor.ref ?: repository.defaultBranch
        require(!ref.isNullOrBlank()) {
            "GitHub Action executor requires an explicit ref or repository default branch"
        }
        return client.dispatch(
            GitHubWorkflowDispatchRequest(repository, executor.workflow, ref),
        ).toExecution(context)
    }

    override suspend fun reconcile(context: TaskExecutorContext): TaskExecutorExecution {
        val repository = requireGitHubRepository(context)
        val runId = requireNotNull(context.taskRun.externalRunId) {
            "GitHub Action task ${context.task.id.value} is missing its external run ID"
        }
        return client.getRun(repository, runId).toExecution(context)
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

    private fun GitHubWorkflowRun.toExecution(context: TaskExecutorContext): TaskExecutorExecution {
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
        return TaskExecutorExecution(
            status = taskStatus,
            externalRunId = id,
            artifacts = artifacts.map { artifact ->
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
            },
            progress = if (taskStatus == TaskRunStatus.Completed) 1f else stepProgress,
            progressMessage = stepMessage,
        )
    }
}
