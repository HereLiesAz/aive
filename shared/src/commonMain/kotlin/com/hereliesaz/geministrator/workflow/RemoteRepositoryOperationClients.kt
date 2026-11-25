package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.net.boundedBodyText
import com.hereliesaz.geministrator.net.boundedBytes
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

    override fun supportsFileProposalContext(project: Project): Boolean =
        clients.any { it.supports(project) && it.supportsFileProposalContext(project) }

    override suspend fun readFileProposalContext(project: Project, paths: List<String>): ExternalExecutionRun =
        (clients.firstOrNull { it.supports(project) && it.supportsFileProposalContext(project) }
            ?: error("No host file-snapshot client for the linked repository")).readFileProposalContext(project, paths)

    override fun supportsFileProposals(project: Project): Boolean =
        clients.any { it.supports(project) && it.supportsFileProposals(project) }

    override suspend fun commitFileProposal(project: Project, branch: String, proposal: RepositoryFileProposal): ExternalExecutionRun =
        (clients.firstOrNull { it.supports(project) && it.supportsFileProposals(project) }
            ?: error("No host file-commit client for the linked repository")).commitFileProposal(project, branch, proposal)

    override suspend fun start(project: Project, operation: String): ExternalExecutionRun =
        clientFor(project).start(project, operation)

    override suspend fun start(
        project: Project,
        operation: String,
        operationIdentity: String,
    ): ExternalExecutionRun =
        clientFor(project).start(project, operation, operationIdentity)

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
    httpClient: HttpClient = defaultRepositoryHttpClient(),
    private val baseUrl: String = "https://api.github.com",
    private val json: Json = repositoryJson,
) : RepositoryOperationClient {
    // A redirect would carry the token to another host; none is followed, whatever client was given.
    private val httpClient: HttpClient = httpClient.config { followRedirects = false }
    private val runs = RemoteRunStore()

    override fun supports(project: Project): Boolean =
        project.repository?.source == RepositorySource.GitHub

    override fun supportsFileProposals(project: Project): Boolean = supports(project)
    override fun supportsFileProposalContext(project: Project): Boolean = supports(project)

    /**
     * Read complete regular UTF-8 files at one immutable base commit, without a model or any write.
     * An absent path gets a null expected blob; a truncated tree is never treated as absence.
     */
    override suspend fun readFileProposalContext(project: Project, paths: List<String>): ExternalExecutionRun {
        RepositoryFileProposal.validateProposalPaths(paths)
        require(paths.none { path -> path.any { it in "*?[" } }) { "Snapshot requires literal file paths, not globs" }
        val repository = requireRepository(project, RepositorySource.GitHub)
        repository.requirePublicRemoteHost("github.com", "GitHub")
        val token = tokenProvider.requireToken("GitHub")
        val repo = getRepository(repository, token)
        val url = "$baseUrl/repos/${repository.owner.encodeURLPathPart()}/${repository.name.encodeURLPathPart()}"
        val base = readFileProposalBase(url, repository.defaultBranch ?: repo.defaultBranch, token)
        var remaining = RepositoryFileProposal.MAX_TOTAL_BYTES
        val files = paths.map { path ->
            val existing = base.entries[path]
            validateFileTarget(path, base.entries)
            if (existing == null) {
                RepositoryProposedFile(path, null, "")
            } else {
                require(existing.type == "blob" && existing.mode in setOf("100644", "100755") && isGitObjectId(existing.sha)) {
                    "Snapshot target is not a regular file"
                }
                val response = httpClient.get("$url/git/blobs/${existing.sha}") {
                    githubHeaders(token, "application/vnd.github.raw+json")
                }
                check(response.status.value in 200..299) { "Unable to read proposal file: HTTP ${response.status.value}" }
                val bytes = response.boundedBytes(minOf(remaining, RepositoryFileProposal.MAX_FILE_BYTES))
                remaining -= bytes.size
                RepositoryProposedFile(path, existing.sha, bytes.decodeToString(throwOnInvalidSequence = true))
            }
        }
        val snapshot = RepositoryFileProposal(base.sha, files).also { it.validate() }
        return completedRun(
            source = RepositorySource.GitHub,
            operation = RepositoryFileProposal.READ_CONTEXT_OPERATION,
            label = "Repository file proposal context",
            text = Json.encodeToString(snapshot),
            metadata = mapOf("baseCommit" to base.sha),
        ).also { runs.put(it) }
    }

    /**
     * Prepare a Git tree and commit against an exact base, then create (never update) a dedicated ref.
     * All HTTP requests use the host's repository credential. A failed ref creation never overwrites a branch.
     */
    override suspend fun commitFileProposal(project: Project, branch: String, proposal: RepositoryFileProposal): ExternalExecutionRun {
        proposal.validate()
        require(GitRefNames.requireSafe(branch) == branch && branch.startsWith("aive/") && branch.length <= 200) {
            "Host commit requires a dedicated aive/ branch"
        }
        val repository = requireRepository(project, RepositorySource.GitHub)
        repository.requirePublicRemoteHost("github.com", "GitHub")
        val token = tokenProvider.requireToken("GitHub")
        val repo = getRepository(repository, token)
        val baseBranch = repository.defaultBranch ?: repo.defaultBranch
        require(branch != baseBranch && branch != repo.defaultBranch) { "Cannot commit to the default or linked base branch" }
        val url = "$baseUrl/repos/${repository.owner.encodeURLPathPart()}/${repository.name.encodeURLPathPart()}"
        val base = readFileProposalBase(url, baseBranch, token, expectedCommit = proposal.baseCommit)
        val entries = base.entries
        val changes = proposal.files.map { file ->
            val existing = entries[file.path]
            if (file.expectedBlob == null) {
                require(existing == null) { "A proposed new file already exists" }
            } else {
                require(existing != null && existing.type == "blob" && existing.mode in setOf("100644", "100755") &&
                    existing.sha == file.expectedBlob) { "A proposed replacement does not match an existing regular file" }
            }
            validateFileTarget(file.path, entries)
            GitHubFileTreeChange(file.path, existing?.mode ?: "100644", type = "blob", content = file.content)
        }
        val newTree = json.decodeFromString<GitHubCommitPointer>(httpClient.post("$url/git/trees") {
            githubHeaders(token)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(GitHubCreateFileTreeBody(base.treeSha, changes)))
        }.requireSuccessBody("prepare repository tree"))
        require(isGitObjectId(newTree.sha)) { "GitHub returned an invalid new tree SHA" }
        val newCommit = json.decodeFromString<GitHubCommitPointer>(httpClient.post("$url/git/commits") {
            githubHeaders(token)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(GitHubCreateFileCommitBody("Apply approved Aive file proposal", newTree.sha, listOf(proposal.baseCommit))))
        }.requireSuccessBody("prepare repository commit"))
        require(isGitObjectId(newCommit.sha)) { "GitHub returned an invalid commit SHA" }
        val created = json.decodeFromString<GitHubRefResponse>(httpClient.post("$url/git/refs") {
            githubHeaders(token)
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(GitHubCreateRefBody("refs/heads/$branch", newCommit.sha)))
        }.requireSuccessBody("create dedicated commit branch"))
        require(created.obj.sha == newCommit.sha) { "GitHub returned a different commit than requested" }
        return completedRun(
            source = RepositorySource.GitHub,
            operation = "commit-files",
            label = "Commit approved files",
            text = "BRANCH: $branch\nFILES: ${proposal.files.joinToString(", ") { it.path }}\nCOMMIT: ${newCommit.sha}",
            artifactKind = ArtifactKind.CodeChange,
            metadata = mapOf("branch" to branch, "headCommit" to newCommit.sha, "baseCommit" to proposal.baseCommit),
        ).also { runs.put(it) }
    }

    /** Both readers and writers validate the same complete immutable tree. */
    private suspend fun readFileProposalBase(url: String, branch: String, token: String, expectedCommit: String? = null): GitHubFileProposalBase {
        val base = json.decodeFromString<GitHubRefResponse>(httpClient.get(
            "$url/git/ref/heads/${branch.encodeURLPathPart()}",
        ) { githubHeaders(token) }.requireSuccessBody("read linked base ref"))
        require(isGitObjectId(base.obj.sha)) { "GitHub returned an invalid base commit SHA" }
        require(expectedCommit == null || base.obj.sha == expectedCommit) { "Linked base changed; regenerate and approve the proposal" }
        val commit = json.decodeFromString<GitHubFileCommitResponse>(httpClient.get(
            "$url/git/commits/${base.obj.sha}",
        ) { githubHeaders(token) }.requireSuccessBody("read base commit"))
        require(isGitObjectId(commit.tree.sha)) { "GitHub returned an invalid tree SHA" }
        val tree = json.decodeFromString<GitHubFileTreeResponse>(httpClient.get(
            "$url/git/trees/${commit.tree.sha}",
        ) {
            githubHeaders(token)
            parameter("recursive", "1")
        }.requireSuccessBody("read complete base tree"))
        require(!tree.truncated) { "Cannot safely validate changes against a truncated repository tree" }
        return GitHubFileProposalBase(base.obj.sha, commit.tree.sha, tree.tree.associateBy { it.path })
    }

    /** No target may replace a directory or cross a symlink, submodule or file. */
    private fun validateFileTarget(path: String, entries: Map<String, GitHubFileTreeEntry>) {
        require(entries[path]?.type != "tree" && entries.keys.none { it.startsWith(path + "/") }) { "Cannot replace a directory with a file" }
        var parent = path.substringBeforeLast('/', "")
        while (parent.isNotEmpty()) {
            require(entries[parent]?.type.let { it == null || it == "tree" }) { "A file path crosses a non-directory" }
            parent = parent.substringBeforeLast('/', "")
        }
    }

    override suspend fun start(project: Project, operation: String): ExternalExecutionRun {
        val repository = requireRepository(project, RepositorySource.GitHub)
        repository.requirePublicRemoteHost("github.com", "GitHub")
        val token = tokenProvider.requireToken("GitHub")
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

    private fun HttpRequestBuilder.githubHeaders(token: String, accept: String = "application/vnd.github+json") {
        header(HttpHeaders.Accept, accept)
        header(HttpHeaders.Authorization, "Bearer $token")
        header("X-GitHub-Api-Version", "2026-03-10")
    }
}

class GitLabRestRepositoryOperationClient(
    private val tokenProvider: RepositoryServiceTokenProvider,
    httpClient: HttpClient = defaultRepositoryHttpClient(),
    private val json: Json = repositoryJson,
) : RepositoryOperationClient {
    private val httpClient: HttpClient = httpClient.config { followRedirects = false }
    private val runs = RemoteRunStore()

    override fun supports(project: Project): Boolean =
        project.repository?.source == RepositorySource.GitLab

    override fun supportsFileProposalContext(project: Project): Boolean = supports(project)

    /**
     * Read literal regular-file paths from a single GitLab commit. Directory pages are cached and
     * exhausted before declaring a path absent; the page budget fails closed on oversized trees.
     * No workspace checkout, model credential, or write request is needed.
     */
    override suspend fun readFileProposalContext(project: Project, paths: List<String>): ExternalExecutionRun {
        RepositoryFileProposal.validateProposalPaths(paths)
        require(paths.none { path -> path.any { it in "*?[" } }) { "Snapshot requires literal file paths, not globs" }
        val repository = requireRepository(project, RepositorySource.GitLab)
        val apiBase = repository.gitLabApiBase()
        val token = tokenProvider.requireToken("GitLab")
        val url = "$apiBase/projects/${repository.displayName().encodeURLPathPart()}"
        val info = json.decodeFromString<GitLabProjectResponse>(httpClient.get(url) {
            gitlabHeaders(token)
        }.requireSuccessBody("read GitLab project"))
        val baseBranch = repository.defaultBranch ?: info.defaultBranch
        val base = json.decodeFromString<GitLabBranchResponse>(httpClient.get(
            "$url/repository/branches/${baseBranch.encodeURLPathPart()}",
        ) { gitlabHeaders(token) }.requireSuccessBody("read GitLab base branch")).commit.id
        require(isGitObjectId(base)) { "GitLab returned an invalid base commit SHA" }
        val directories = mutableMapOf<String, Map<String, GitLabSnapshotTreeEntry>>()
        var pagesRemaining = 100
        suspend fun directory(path: String): Map<String, GitLabSnapshotTreeEntry> {
            directories[path]?.let { return it }
            val entries = linkedMapOf<String, GitLabSnapshotTreeEntry>()
            var page = 1
            while (true) {
                require(pagesRemaining-- > 0) { "GitLab snapshot directory page limit exceeded" }
                val body = httpClient.get("$url/repository/tree") {
                    gitlabHeaders(token)
                    parameter("ref", base)
                    parameter("recursive", false)
                    if (path.isNotEmpty()) parameter("path", path)
                    parameter("per_page", 100)
                    parameter("page", page)
                }.requireSuccessBody("read GitLab snapshot tree")
                val items = json.decodeFromString<List<GitLabSnapshotTreeEntry>>(body)
                require(items.size <= 100) { "GitLab returned an oversized tree page" }
                for (item in items) {
                    require(item.path.substringBeforeLast('/', "") == path && entries.put(item.path, item) == null) {
                        "GitLab returned an invalid or repeated tree entry"
                    }
                }
                if (items.size < 100) break
                page++
            }
            directories[path] = entries
            return entries
        }
        var remaining = RepositoryFileProposal.MAX_TOTAL_BYTES
        val files = paths.map { path ->
            val parts = path.split('/')
            var parent = ""
            var absent = false
            for (part in parts.dropLast(1)) {
                val next = if (parent.isEmpty()) part else "$parent/$part"
                val entry = directory(parent)[next]
                if (entry == null) {
                    absent = true
                    break
                }
                require(entry.type == "tree" && entry.mode == "040000") { "A file path crosses a non-directory" }
                parent = next
            }
            val entry = if (absent) null else directory(parent)[path]
            if (entry == null) {
                RepositoryProposedFile(path, null, "")
            } else {
                require(entry.type == "blob" && entry.mode in setOf("100644", "100755") && isGitObjectId(entry.id)) {
                    "Snapshot target is not a regular file"
                }
                val response = httpClient.get("$url/repository/files/${path.encodeURLPathPart()}/raw") {
                    gitlabHeaders(token)
                    parameter("ref", base)
                }
                check(response.status.value in 200..299) { "Unable to read GitLab snapshot file: HTTP ${response.status.value}" }
                val bytes = response.boundedBytes(minOf(remaining, RepositoryFileProposal.MAX_FILE_BYTES))
                remaining -= bytes.size
                RepositoryProposedFile(path, entry.id, bytes.decodeToString(throwOnInvalidSequence = true))
            }
        }
        val snapshot = RepositoryFileProposal(base, files).also { it.validate() }
        return completedRun(
            source = RepositorySource.GitLab, operation = RepositoryFileProposal.READ_CONTEXT_OPERATION,
            label = "Repository file proposal context", text = Json.encodeToString(snapshot),
            metadata = mapOf("baseCommit" to base),
        ).also { runs.put(it) }
    }

    override suspend fun start(project: Project, operation: String): ExternalExecutionRun {
        val repository = requireRepository(project, RepositorySource.GitLab)
        repository.requirePublicRemoteHost("gitlab.com", "GitLab")
        val token = tokenProvider.requireToken("GitLab")
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

        private fun requireSafeBranch(raw: String): String = GitRefNames.requireSafe(raw)
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
    requirePublicRemoteHost("gitlab.com", "GitLab")
    return "https://gitlab.com/api/v4"
}

private fun RepositoryRef.requirePublicRemoteHost(expectedHost: String, service: String) {
    val remote = remoteUrl?.trim().orEmpty()
    if (remote.isBlank()) return

    val host = when {
        remote.startsWith("https://", ignoreCase = true) ->
            remote.substringAfter("://").substringBefore('/').substringAfter('@').substringBefore(':')
        remote.startsWith("git@", ignoreCase = true) ->
            remote.substringAfter('@').substringBefore(':')
        remote.startsWith("ssh://", ignoreCase = true) ->
            remote.substringAfter("://").substringBefore('/').substringAfter('@').substringBefore(':')
        else -> error("$service repository credentials require an HTTPS or SSH locator")
    }
    require(host.equals(expectedHost, ignoreCase = true)) {
        "$service repository credentials are configured only for $expectedHost"
    }
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
    followRedirects = false
    install(HttpTimeout) {
        requestTimeoutMillis = 30_000L
        connectTimeoutMillis = 10_000L
    }
}

private suspend fun HttpResponse.requireSuccessBody(operation: String): String {
    val body = boundedBodyText()
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

private data class GitHubFileProposalBase(val sha: String, val treeSha: String, val entries: Map<String, GitHubFileTreeEntry>)

@Serializable
private data class GitHubFileCommitResponse(val tree: GitHubCommitPointer)

@Serializable
private data class GitHubFileTreeResponse(val tree: List<GitHubFileTreeEntry>, val truncated: Boolean)

@Serializable
private data class GitHubFileTreeEntry(val path: String, val mode: String, val type: String, val sha: String)

@Serializable
private data class GitHubFileTreeChange(val path: String, val mode: String, val type: String, val content: String)

@Serializable
private data class GitHubCreateFileTreeBody(@SerialName("base_tree") val baseTree: String, val tree: List<GitHubFileTreeChange>)

@Serializable
private data class GitHubCreateFileCommitBody(val message: String, val tree: String, val parents: List<String>)

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
private data class GitLabSnapshotTreeEntry(val path: String, val type: String, val mode: String, val id: String)

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
