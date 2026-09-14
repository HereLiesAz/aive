package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.RepositorySource
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.displayName
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

fun interface RepositoryServiceTokenProvider {
    suspend fun getToken(): String
}

class RoutingRepositoryOperationClient(
    private val clients: List<RepositoryOperationClient>,
) : RepositoryOperationClient {
    override fun supports(project: Project): Boolean = clients.any { it.supports(project) }

    override suspend fun start(project: Project, operation: String): ExternalExecutionRun =
        clientFor(project).start(project, operation)

    override suspend fun getRun(project: Project, runId: String): ExternalExecutionRun =
        clientFor(project).getRun(project, runId)

    private fun clientFor(project: Project): RepositoryOperationClient =
        clients.firstOrNull { it.supports(project) }
            ?: error(
                "No repository-operation client is configured for " +
                    (project.repository?.source?.name ?: "repoless project"),
            )
}

class GitHubRestRepositoryOperationClient(
    private val tokenProvider: RepositoryServiceTokenProvider,
    private val httpClient: HttpClient = defaultRepositoryHttpClient(),
    private val baseUrl: String = "https://api.github.com",
    private val json: Json = repositoryJson,
) : RepositoryOperationClient {
    private val runs = RemoteRunStore()

    override fun supports(project: Project): Boolean =
        project.repository?.source == RepositorySource.GitHub

    override suspend fun start(project: Project, operation: String): ExternalExecutionRun {
        val repository = requireRepository(project, RepositorySource.GitHub)
        require(repository.remoteUrl == null || repository.remoteUrl.contains("github.com")) {
            "GitHub Enterprise repository operations require an explicit enterprise API configuration"
        }
        val token = requireToken("GitHub")
        val parsed = RemoteRepositoryOperation.parse(operation)
        val run = when (parsed) {
            RemoteRepositoryOperation.Status -> status(repository, token, operation)
            is RemoteRepositoryOperation.CreateBranch -> createBranch(repository, token, operation, parsed.branch)
            is RemoteRepositoryOperation.OpenReview -> openPullRequest(repository, token, operation, parsed)
        }
        runs.put(run)
        return run
    }

    override suspend fun getRun(project: Project, runId: String): ExternalExecutionRun =
        runs.get(runId) ?: ExternalExecutionRun(
            id = runId,
            status = ExternalExecutionStatus.Failed,
            message = "GitHub repository operation $runId is no longer available in this process",
        )

    private suspend fun status(repository: RepositoryRef, token: String, operation: String): ExternalExecutionRun {
        val repo = getRepository(repository, token)
        val branchName = repository.defaultBranch ?: repo.defaultBranch
        val branchBody = httpClient.get(
            "$baseUrl/repos/${repository.owner.encodeURLPathPart()}/${repository.name.encodeURLPathPart()}/branches/${branchName.encodeURLPathPart()}",
        ) { githubHeaders(token) }.requireSuccessBody("read GitHub branch $branchName")
        val branch = json.decodeFromString<GitHubBranchResponse>(branchBody)
        return completedRun(
            source = RepositorySource.GitHub,
            operation = operation,
            label = "Repository status",
            text = "${repository.displayName()} · ${branch.name} @ ${branch.commit.sha.take(12)}",
            metadata = mapOf(
                "branch" to branch.name,
                "headCommit" to branch.commit.sha,
                "repositoryUrl" to repo.htmlUrl,
            ),
        )
    }

    private suspend fun createBranch(
        repository: RepositoryRef,
        token: String,
        operation: String,
        branch: String,
    ): ExternalExecutionRun {
        val repo = getRepository(repository, token)
        val baseBranch = repository.defaultBranch ?: repo.defaultBranch
        val refBody = httpClient.get(
            "$baseUrl/repos/${repository.owner.encodeURLPathPart()}/${repository.name.encodeURLPathPart()}/git/ref/heads/${baseBranch.encodeURLPathPart()}",
        ) { githubHeaders(token) }.requireSuccessBody("read GitHub base ref $baseBranch")
        val baseRef = json.decodeFromString<GitHubRefResponse>(refBody)
        val createdBody = httpClient.post(
            "$baseUrl/repos/${repository.owner.encodeURLPathPart()}/${repository.name.encodeURLPathPart()}/git/refs",
        ) {
            githubHeaders(token)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(GitHubCreateRefBody("refs/heads/$branch", baseRef.obj.sha)))
        }.requireSuccessBody("create GitHub branch $branch")
        val created = json.decodeFromString<GitHubRefResponse>(createdBody)
        return completedRun(
            source = RepositorySource.GitHub,
            operation = operation,
            label = "Create branch $branch",
            text = "Created $branch from $baseBranch at ${created.obj.sha.take(12)}",
            metadata = mapOf(
                "branch" to branch,
                "headCommit" to created.obj.sha,
                "baseBranch" to baseBranch,
                "repositoryUrl" to repo.htmlUrl,
            ),
        )
    }

    private suspend fun openPullRequest(
        repository: RepositoryRef,
        token: String,
        operation: String,
        review: RemoteRepositoryOperation.OpenReview,
    ): ExternalExecutionRun {
        val body = httpClient.post(
            "$baseUrl/repos/${repository.owner.encodeURLPathPart()}/${repository.name.encodeURLPathPart()}/pulls",
        ) {
            githubHeaders(token)
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    GitHubCreatePullRequestBody(
                        title = review.title,
                        head = review.sourceBranch,
                        base = review.targetBranch,
                    ),
                ),
            )
        }.requireSuccessBody("create GitHub pull request")
        val pullRequest = json.decodeFromString<GitHubPullRequestResponse>(body)
        return completedRun(
            source = RepositorySource.GitHub,
            operation = operation,
            label = pullRequest.title,
            text = "Pull request #${pullRequest.number}: ${pullRequest.title}",
            artifactKind = ArtifactKind.PullRequest,
            uri = pullRequest.htmlUrl,
            metadata = mapOf(
                "reviewNumber" to pullRequest.number.toString(),
                "state" to pullRequest.state,
                "sourceBranch" to review.sourceBranch,
                "targetBranch" to review.targetBranch,
            ),
        )
    }

    private suspend fun getRepository(repository: RepositoryRef, token: String): GitHubRepositoryResponse {
        val body = httpClient.get(
            "$baseUrl/repos/${repository.owner.encodeURLPathPart()}/${repository.name.encodeURLPathPart()}",
        ) { githubHeaders(token) }.requireSuccessBody("read GitHub repository ${repository.displayName()}")
        return json.decodeFromString(body)
    }

    private fun HttpRequestBuilder.githubHeaders(token: String) {
        header(HttpHeaders.Accept, "application/vnd.github+json")
        header(HttpHeaders.Authorization, "Bearer $token")
        header("X-GitHub-Api-Version", "2026-03-10")
    }
}

class GitLabRestRepositoryOperationClient(
    private val tokenProvider: RepositoryServiceTokenProvider,
    private val httpClient: HttpClient = defaultRepositoryHttpClient(),
    private val json: Json = repositoryJson,
) : RepositoryOperationClient {
    private val runs = RemoteRunStore()

    override fun supports(project: Project): Boolean =
        project.repository?.source == RepositorySource.GitLab

    override suspend fun start(project: Project, operation: String): ExternalExecutionRun {
        val repository = requireRepository(project, RepositorySource.GitLab)
        val token = requireToken("GitLab")
        val parsed = RemoteRepositoryOperation.parse(operation)
        val run = when (parsed) {
            RemoteRepositoryOperation.Status -> status(repository, token, operation)
            is RemoteRepositoryOperation.CreateBranch -> createBranch(repository, token, operation, parsed.branch)
            is RemoteRepositoryOperation.OpenReview -> openMergeRequest(repository, token, operation, parsed)
        }
        runs.put(run)
        return run
    }

    override suspend fun getRun(project: Project, runId: String): ExternalExecutionRun =
        runs.get(runId) ?: ExternalExecutionRun(
            id = runId,
            status = ExternalExecutionStatus.Failed,
            message = "GitLab repository operation $runId is no longer available in this process",
        )

    private suspend fun status(repository: RepositoryRef, token: String, operation: String): ExternalExecutionRun {
        val apiBase = repository.gitLabApiBase()
        val projectPath = repository.displayName().encodeURLPathPart()
        val projectBody = httpClient.get("$apiBase/projects/$projectPath") {
            gitlabHeaders(token)
        }.requireSuccessBody("read GitLab project ${repository.displayName()}")
        val project = json.decodeFromString<GitLabProjectResponse>(projectBody)
        val branchName = repository.defaultBranch ?: project.defaultBranch
        val branchBody = httpClient.get("$apiBase/projects/$projectPath/repository/branches/${branchName.encodeURLPathPart()}") {
            gitlabHeaders(token)
        }.requireSuccessBody("read GitLab branch $branchName")
        val branch = json.decodeFromString<GitLabBranchResponse>(branchBody)
        return completedRun(
            source = RepositorySource.GitLab,
            operation = operation,
            label = "Repository status",
            text = "${repository.displayName()} · ${branch.name} @ ${branch.commit.id.take(12)}",
            metadata = mapOf(
                "branch" to branch.name,
                "headCommit" to branch.commit.id,
                "repositoryUrl" to project.webUrl,
            ),
        )
    }

    private suspend fun createBranch(
        repository: RepositoryRef,
        token: String,
        operation: String,
        branch: String,
    ): ExternalExecutionRun {
        val apiBase = repository.gitLabApiBase()
        val projectPath = repository.displayName().encodeURLPathPart()
        val baseBranch = repository.defaultBranch ?: run {
            val projectBody = httpClient.get("$apiBase/projects/$projectPath") {
                gitlabHeaders(token)
            }.requireSuccessBody("read GitLab project ${repository.displayName()}")
            json.decodeFromString<GitLabProjectResponse>(projectBody).defaultBranch
        }
        val createdBody = httpClient.post("$apiBase/projects/$projectPath/repository/branches") {
            gitlabHeaders(token)
            parameter("branch", branch)
            parameter("ref", baseBranch)
        }.requireSuccessBody("create GitLab branch $branch")
        val created = json.decodeFromString<GitLabBranchResponse>(createdBody)
        return completedRun(
            source = RepositorySource.GitLab,
            operation = operation,
            label = "Create branch $branch",
            text = "Created $branch from $baseBranch at ${created.commit.id.take(12)}",
            uri = created.webUrl,
            metadata = mapOf(
                "branch" to created.name,
                "headCommit" to created.commit.id,
                "baseBranch" to baseBranch,
            ),
        )
    }

    private suspend fun openMergeRequest(
        repository: RepositoryRef,
        token: String,
        operation: String,
        review: RemoteRepositoryOperation.OpenReview,
    ): ExternalExecutionRun {
        val apiBase = repository.gitLabApiBase()
        val projectPath = repository.displayName().encodeURLPathPart()
        val body = httpClient.post("$apiBase/projects/$projectPath/merge_requests") {
            gitlabHeaders(token)
            contentType(ContentType.Application.Json)
            setBody(
                json.encodeToString(
                    GitLabCreateMergeRequestBody(
                        sourceBranch = review.sourceBranch,
                        targetBranch = review.targetBranch,
                        title = review.title,
                    ),
                ),
            )
        }.requireSuccessBody("create GitLab merge request")
        val mergeRequest = json.decodeFromString<GitLabMergeRequestResponse>(body)
        return completedRun(
            source = RepositorySource.GitLab,
            operation = operation,
            label = mergeRequest.title,
            text = "Merge request !${mergeRequest.iid}: ${mergeRequest.title}",
            artifactKind = ArtifactKind.PullRequest,
            uri = mergeRequest.webUrl,
            metadata = mapOf(
                "reviewNumber" to mergeRequest.iid.toString(),
                "state" to mergeRequest.state,
                "sourceBranch" to mergeRequest.sourceBranch,
                "targetBranch" to mergeRequest.targetBranch,
                "reviewKind" to "merge-request",
            ),
        )
    }

    private fun HttpRequestBuilder.gitlabHeaders(token: String) {
        header("PRIVATE-TOKEN", token)
        header(HttpHeaders.Accept, ContentType.Application.Json.toString())
    }
}

private sealed interface RemoteRepositoryOperation {
    data object Status : RemoteRepositoryOperation
    data class CreateBranch(val branch: String) : RemoteRepositoryOperation
    data class OpenReview(
        val sourceBranch: String,
        val targetBranch: String,
        val title: String,
    ) : RemoteRepositoryOperation

    companion object {
        fun parse(raw: String): RemoteRepositoryOperation {
            val operation = raw.trim()
            return when {
                operation == "status" -> Status
                operation.startsWith("create-branch:") ->
                    CreateBranch(requireSafeBranch(operation.substringAfter(':')))
                operation.startsWith("open-pull-request:") || operation.startsWith("open-merge-request:") -> {
                    val payload = operation.substringAfter(':')
                    val parts = payload.split(':', limit = 3)
                    require(parts.size == 3) {
                        "Review operation must be '<source>:<target>:<title>'"
                    }
                    OpenReview(
                        sourceBranch = requireSafeBranch(parts[0]),
                        targetBranch = requireSafeBranch(parts[1]),
                        title = parts[2].trim().also { require(it.isNotEmpty()) { "Review title is required" } },
                    )
                }
                else -> error(
                    "Unsupported remote repository operation '$operation'. Supported operations: " +
                        "status, create-branch:<branch>, " +
                        "open-pull-request:<source>:<target>:<title>, " +
                        "open-merge-request:<source>:<target>:<title>",
                )
            }
        }

        private fun requireSafeBranch(raw: String): String {
            val branch = raw.trim()
            require(branch.isNotEmpty()) { "Branch name is required" }
            require(!branch.startsWith('-')) { "Branch names may not begin with '-'" }
            require(branch.none { it == '\u0000' || it == '\n' || it == '\r' }) {
                "Branch name contains invalid control characters"
            }
            return branch
        }
    }
}

private class RemoteRunStore {
    private val mutex = Mutex()
    private val runs = mutableMapOf<String, ExternalExecutionRun>()

    suspend fun put(run: ExternalExecutionRun) {
        mutex.withLock { runs[run.id] = run }
    }

    suspend fun get(id: String): ExternalExecutionRun? = mutex.withLock { runs[id] }
}

private fun requireRepository(project: Project, source: RepositorySource): RepositoryRef {
    val repository = requireNotNull(project.repository) { "Repository operation requires a linked project repository" }
    require(repository.source == source) {
        "${source.name} repository operation cannot run against ${repository.source.name}"
    }
    return repository
}

private suspend fun RepositoryServiceTokenProvider.requireToken(service: String): String =
    getToken().trim().also { require(it.isNotEmpty()) { "$service repository credential is not configured" } }

private fun RepositoryRef.gitLabApiBase(): String {
    val remote = remoteUrl.orEmpty().trim()
    val host = when {
        remote.startsWith("http://") -> remote.substringAfter("http://").substringBefore('/')
        remote.startsWith("https://") -> remote.substringAfter("https://").substringBefore('/')
        remote.startsWith("git@") -> remote.substringAfter("git@").substringBefore(':')
        remote.startsWith("ssh://") -> remote.substringAfter("ssh://").substringAfter('@').substringBefore('/').substringBefore(':')
        else -> "gitlab.com"
    }.ifBlank { "gitlab.com" }
    val scheme = if (remote.startsWith("http://")) "http" else "https"
    return "$scheme://$host/api/v4"
}

@OptIn(ExperimentalTime::class)
private fun completedRun(
    source: RepositorySource,
    operation: String,
    label: String,
    text: String,
    artifactKind: ArtifactKind = ArtifactKind.CommandOutput,
    uri: String? = null,
    metadata: Map<String, String> = emptyMap(),
): ExternalExecutionRun {
    val timestamp = Clock.System.now().toEpochMilliseconds()
    val runId = "${source.name.lowercase()}-${timestamp}-${operation.hashCode().toUInt()}"
    val artifact = ArtifactRef(
        id = ArtifactId("$runId:repository-output"),
        kind = artifactKind,
        taskRunId = TaskRunId("$runId:external"),
        label = label,
        uri = uri,
        textContent = text,
        mediaType = if (artifactKind == ArtifactKind.PullRequest) "text/markdown" else "text/plain",
        metadata = buildMap {
            put("repositorySource", source.name)
            put("operation", operation)
            putAll(metadata)
        },
        createdAtEpochMillis = timestamp,
    )
    return ExternalExecutionRun(
        id = runId,
        status = ExternalExecutionStatus.Completed,
        artifacts = listOf(artifact),
        progress = 1f,
        message = "$label · complete",
    )
}

private fun defaultRepositoryHttpClient(): HttpClient = HttpClient {
    install(HttpTimeout) {
        requestTimeoutMillis = 30_000L
        connectTimeoutMillis = 10_000L
    }
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

private val repositoryJson = Json { ignoreUnknownKeys = true }

@Serializable
private data class GitHubRepositoryResponse(
    @SerialName("default_branch") val defaultBranch: String,
    @SerialName("html_url") val htmlUrl: String,
)

@Serializable
private data class GitHubBranchResponse(
    val name: String,
    val commit: GitHubCommitPointer,
)

@Serializable
private data class GitHubCommitPointer(
    val sha: String,
)

@Serializable
private data class GitHubRefResponse(
    @SerialName("object") val obj: GitHubRefObject,
)

@Serializable
private data class GitHubRefObject(
    val sha: String,
)

@Serializable
private data class GitHubCreateRefBody(
    val ref: String,
    val sha: String,
)

@Serializable
private data class GitHubCreatePullRequestBody(
    val title: String,
    val head: String,
    val base: String,
)

@Serializable
private data class GitHubPullRequestResponse(
    val number: Long,
    val title: String,
    val state: String,
    @SerialName("html_url") val htmlUrl: String,
)

@Serializable
private data class GitLabProjectResponse(
    @SerialName("default_branch") val defaultBranch: String,
    @SerialName("web_url") val webUrl: String,
)

@Serializable
private data class GitLabBranchResponse(
    val name: String,
    val commit: GitLabCommitResponse,
    @SerialName("web_url") val webUrl: String? = null,
)

@Serializable
private data class GitLabCommitResponse(
    val id: String,
)

@Serializable
private data class GitLabCreateMergeRequestBody(
    @SerialName("source_branch") val sourceBranch: String,
    @SerialName("target_branch") val targetBranch: String,
    val title: String,
)

@Serializable
private data class GitLabMergeRequestResponse(
    val iid: Long,
    val title: String,
    val state: String,
    @SerialName("source_branch") val sourceBranch: String,
    @SerialName("target_branch") val targetBranch: String,
    @SerialName("web_url") val webUrl: String,
)
