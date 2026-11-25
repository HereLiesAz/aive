package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.RepositorySource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RemoteRepositoryOperationClientsTest {
    // An engine left open outlives the test and can report its failure to whichever test runs next
    // (kotlinx.coroutines.test.UncaughtExceptionsBeforeTest), so every client a test made is closed.
    private val opened = mutableListOf<HttpClient>()

    private fun trackedClient(
        engine: io.ktor.client.engine.HttpClientEngine,
        config: io.ktor.client.HttpClientConfig<*>.() -> Unit = {},
    ): HttpClient = HttpClient(engine, config).also { opened += it }

    @AfterTest
    fun closeOpenedClients() {
        opened.forEach { it.close() }
        opened.clear()
    }

    @Test
    fun hostSnapshotReadsOnlyExactPathsAtOneCommitAndDoesNotWrite() = runBlocking {
        val base = "a".repeat(40)
        val tree = "b".repeat(40)
        val blob = "c".repeat(40)
        val requests = mutableListOf<String>()
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            val path = request.url.encodedPath
            requests += path
            when {
                path.endsWith("/git/ref/heads/main") -> respondJson("""{"object":{"sha":"$base"}}""")
                path.endsWith("/git/commits/$base") -> respondJson("""{"tree":{"sha":"$tree"}}""")
                path.endsWith("/git/trees/$tree") -> respondJson("""{"truncated":false,"tree":[{"path":"README.md","mode":"100644","type":"blob","sha":"$blob"}]}""")
                path.endsWith("/git/blobs/$blob") -> {
                    assertEquals("application/vnd.github.raw+json", request.headers[HttpHeaders.Accept])
                    respondJson("exact file content")
                }
                else -> respondJson("""{"default_branch":"main","html_url":"https://github.com/HereLiesAz/aive"}""")
            }
        }
        val client = GitHubRestRepositoryOperationClient(RepositoryServiceTokenProvider { "github-token" }, trackedClient(engine))
        val result = client.readFileProposalContext(githubProject(), listOf("README.md", "new.txt"))
        assertEquals(ExternalExecutionStatus.Completed, result.status)
        assertEquals(RepositoryFileProposal(base, listOf(RepositoryProposedFile("README.md", blob, "exact file content"),
            RepositoryProposedFile("new.txt", null, ""))), RepositoryFileProposal.parse(result.artifacts.single().textContent!!))
        assertEquals(1, requests.count { "/git/blobs/" in it })
    }

    @Test
    fun hostCommitCreatesTreeCommitAndNewRefWithoutUpdatingTheBase() = runBlocking {
        val base = "a".repeat(40)
        val oldTree = "b".repeat(40)
        val oldBlob = "c".repeat(40)
        val newTree = "d".repeat(40)
        val newCommit = "e".repeat(40)
        val writes = mutableListOf<String>()
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            assertEquals("Bearer github-token", request.headers[HttpHeaders.Authorization])
            if (request.method == HttpMethod.Post) writes += path.substringAfter("/git/")
            when {
                path.endsWith("/git/ref/heads/main") -> respondJson("""{"object":{"sha":"$base"}}""")
                path.endsWith("/git/commits/$base") -> respondJson("""{"tree":{"sha":"$oldTree"}}""")
                path.endsWith("/git/trees/$oldTree") -> respondJson("""{"truncated":false,"tree":[{"path":"README.md","mode":"100755","type":"blob","sha":"$oldBlob"}]}""")
                path.endsWith("/git/trees") -> {
                    val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    assertEquals(oldTree, body.getValue("base_tree").jsonPrimitive.content)
                    assertEquals(Json.parseToJsonElement("""[{"path":"README.md","mode":"100755","type":"blob","content":"replacement"}]"""), body["tree"])
                    respondJson("""{"sha":"$newTree"}""")
                }
                path.endsWith("/git/commits") -> {
                    val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    assertEquals(newTree, body.getValue("tree").jsonPrimitive.content)
                    assertEquals(Json.parseToJsonElement("""["$base"]"""), body["parents"])
                    respondJson("""{"sha":"$newCommit"}""")
                }
                path.endsWith("/git/refs") -> {
                    val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    assertEquals("refs/heads/aive/reviewed", body.getValue("ref").jsonPrimitive.content)
                    assertEquals(newCommit, body.getValue("sha").jsonPrimitive.content)
                    respondJson("""{"object":{"sha":"$newCommit"}}""")
                }
                else -> {
                    assertEquals(HttpMethod.Get, request.method)
                    respondJson("""{"default_branch":"main","html_url":"https://github.com/HereLiesAz/aive"}""")
                }
            }
        }
        val client = GitHubRestRepositoryOperationClient(RepositoryServiceTokenProvider { "github-token" }, trackedClient(engine))
        val proposal = RepositoryFileProposal(base, listOf(RepositoryProposedFile("README.md", oldBlob, "replacement")))
        val result = client.commitFileProposal(githubProject(), "aive/reviewed", proposal)
        assertEquals(listOf("trees", "commits", "refs"), writes)
        assertEquals(ExternalExecutionStatus.Completed, result.status)
        assertEquals(newCommit, result.artifacts.single().metadata["headCommit"])
        assertEquals(result, client.getRun(githubProject(), result.id))
    }

    @Test
    fun hostCommitRejectsUnsafePathsBeforeAnyRequest() = runBlocking {
        val engine = MockEngine { error("No request should be sent") }
        val client = GitHubRestRepositoryOperationClient(RepositoryServiceTokenProvider { "github-token" }, trackedClient(engine))
        for (path in listOf("../escape", "dir/.git/config", "a//b", "/absolute", "a\\b")) {
            val proposal = RepositoryFileProposal("a".repeat(40), listOf(RepositoryProposedFile(path, null, "text")))
            kotlin.test.assertFailsWith<IllegalArgumentException> { client.commitFileProposal(githubProject(), "aive/reviewed", proposal) }
        }
    }

    @Test
    fun gitLabSnapshotPinsReadsToCommitAndCachesDirectoryListings() = runBlocking {
        val base = "a".repeat(40)
        val blob = "b".repeat(40)
        val tree = "c".repeat(40)
        val directories = mutableListOf<String>()
        val reads = mutableListOf<String>()
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            assertEquals("gitlab-token", request.headers["PRIVATE-TOKEN"])
            when {
                request.url.encodedPath.contains("/repository/branches/") ->
                    respondJson("""{"name":"main","commit":{"id":"$base"}}""")
                request.url.encodedPath.endsWith("/repository/tree") -> {
                    assertEquals(base, request.url.parameters["ref"])
                    assertEquals("false", request.url.parameters["recursive"])
                    val dir = request.url.parameters["path"].orEmpty()
                    directories += dir
                    when (dir) {
                        "" -> respondJson("""[{"path":"docs","type":"tree","mode":"040000","id":"$tree"}]""")
                        "docs" -> respondJson("""[{"path":"docs/readme.md","type":"blob","mode":"100644","id":"$blob"}]""")
                        else -> error("Unexpected directory $dir")
                    }
                }
                request.url.encodedPath.endsWith("/raw") -> {
                    assertEquals(base, request.url.parameters["ref"])
                    reads += request.url.encodedPath
                    respondJson("exact GitLab contents")
                }
                else -> respondJson("""{"default_branch":"main","web_url":"https://gitlab.com/team/app"}""")
            }
        }
        val client = GitLabRestRepositoryOperationClient(RepositoryServiceTokenProvider { "gitlab-token" }, trackedClient(engine))
        val result = client.readFileProposalContext(gitLabProject(), listOf("docs/readme.md", "docs/new.md", "absent/child.md"))
        assertEquals(ExternalExecutionStatus.Completed, result.status)
        assertEquals(listOf("", "docs"), directories)
        assertEquals(1, reads.size)
        assertEquals(RepositoryFileProposal(base, listOf(
            RepositoryProposedFile("docs/readme.md", blob, "exact GitLab contents"),
            RepositoryProposedFile("docs/new.md", null, ""),
            RepositoryProposedFile("absent/child.md", null, ""),
        )), RepositoryFileProposal.parse(result.artifacts.single().textContent!!))
        assertTrue(!client.supportsFileProposals(gitLabProject()))
    }

    @Test
    fun gitLabSnapshotRejectsSymlinkTargetsAndAncestors() = runBlocking {
        val base = "a".repeat(40)
        var treePages = 0
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            when {
                request.url.encodedPath.contains("/repository/branches/") ->
                    respondJson("""{"name":"main","commit":{"id":"$base"}}""")
                request.url.encodedPath.endsWith("/repository/tree") -> {
                    treePages++
                    respondJson("""[{"path":"alias","type":"blob","mode":"120000","id":"${"b".repeat(40)}"}]""")
                }
                request.url.encodedPath.endsWith("/raw") -> error("A symlink must never be read")
                else -> respondJson("""{"default_branch":"main","web_url":"https://gitlab.com/team/app"}""")
            }
        }
        val client = GitLabRestRepositoryOperationClient(RepositoryServiceTokenProvider { "gitlab-token" }, trackedClient(engine))
        for (path in listOf("alias", "alias/child")) {
            kotlin.test.assertFailsWith<IllegalArgumentException> {
                client.readFileProposalContext(gitLabProject(), listOf(path))
            }
        }
        assertEquals(2, treePages)
    }

    @Test
    fun gitLabSnapshotFindsTargetOnLaterPageAndFailsClosedWhenNextPageFails() = runBlocking {
        val base = "a".repeat(40)
        val blob = "b".repeat(40)
        val pages = mutableListOf<String>()
        var failSecondPage = false
        val engine = MockEngine { request ->
            assertEquals(HttpMethod.Get, request.method)
            when {
                request.url.encodedPath.contains("/repository/branches/") ->
                    respondJson("""{"name":"main","commit":{"id":"$base"}}""")
                request.url.encodedPath.endsWith("/repository/tree") -> {
                    val page = requireNotNull(request.url.parameters["page"])
                    pages += page
                    when (page) {
                        "1" -> respondJson((0 until 100).joinToString(",", "[", "]") {
                            """{"path":"other-$it.txt","mode":"100644","type":"blob","id":"$blob"}"""
                        })
                        "2" -> if (failSecondPage) {
                            respondJson("""{"message":"unavailable"}""", HttpStatusCode.ServiceUnavailable)
                        } else {
                            respondJson("""[{"path":"target.txt","mode":"100644","type":"blob","id":"$blob"}]""")
                        }
                        else -> error("Unexpected page $page")
                    }
                }
                request.url.encodedPath.endsWith("/raw") -> respondJson("found on page two")
                else -> respondJson("""{"default_branch":"main","web_url":"https://gitlab.com/team/app"}""")
            }
        }
        val client = GitLabRestRepositoryOperationClient(RepositoryServiceTokenProvider { "gitlab-token" }, trackedClient(engine))
        val result = client.readFileProposalContext(gitLabProject(), listOf("target.txt"))
        assertEquals(RepositoryProposedFile("target.txt", blob, "found on page two"),
            RepositoryFileProposal.parse(result.artifacts.single().textContent!!).files.single())
        assertEquals(listOf("1", "2"), pages)
        failSecondPage = true
        pages.clear()
        kotlin.test.assertFailsWith<IllegalStateException> {
            client.readFileProposalContext(gitLabProject(), listOf("target.txt"))
        }
        assertEquals(listOf("1", "2"), pages)
    }

    @Test
    fun gitLabStatusReadsProjectAndBranchWithPrivateToken() = runBlocking {
        val requests = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            when {
                request.url.encodedPath.contains("/repository/branches/") -> respondJson(
                    """{"name":"main","commit":{"id":"abcdef1234567890"},"web_url":"https://gitlab.test/team/app/-/tree/main"}""",
                )
                else -> respondJson(
                    """{"default_branch":"main","web_url":"https://gitlab.test/team/app"}""",
                )
            }
        }
        val client = GitLabRestRepositoryOperationClient(
            tokenProvider = RepositoryServiceTokenProvider { "gitlab-token" },
            httpClient = trackedClient(engine),
        )

        val run = client.start(gitLabProject(), "status")

        assertEquals(ExternalExecutionStatus.Completed, run.status)
        assertEquals("main", run.artifacts.single().metadata["branch"])
        assertEquals("abcdef1234567890", run.artifacts.single().metadata["headCommit"])
        assertEquals("https://gitlab.test/team/app", run.artifacts.single().metadata["repositoryUrl"])
        assertEquals(2, requests.size)
        assertTrue(requests.all { it.headers["PRIVATE-TOKEN"] == "gitlab-token" })
        assertTrue(requests.first().url.encodedPath.contains("/api/v4/projects/"))
    }

    @Test
    fun gitLabCreateBranchPostsBranchAndBaseRef() = runBlocking {
        val requests = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            respondJson(
                """{"name":"feature/repo-links","commit":{"id":"0123456789abcdef"},"web_url":"https://gitlab.test/team/app/-/tree/feature/repo-links"}""",
                status = HttpStatusCode.Created,
            )
        }
        val client = GitLabRestRepositoryOperationClient(
            tokenProvider = RepositoryServiceTokenProvider { "gitlab-token" },
            httpClient = trackedClient(engine),
        )

        val run = client.start(gitLabProject(), "create-branch:feature/repo-links")

        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("feature/repo-links", request.url.parameters["branch"])
        assertEquals("main", request.url.parameters["ref"])
        assertEquals("feature/repo-links", run.artifacts.single().metadata["branch"])
        assertEquals("0123456789abcdef", run.artifacts.single().metadata["headCommit"])
    }

    @Test
    fun gitLabOpenMergeRequestProducesReviewArtifact() = runBlocking {
        val engine = MockEngine { request ->
            val payload = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals("feature/repo-links", payload.getValue("source_branch").jsonPrimitive.content)
            assertEquals("main", payload.getValue("target_branch").jsonPrimitive.content)
            assertEquals("Ship repository links", payload.getValue("title").jsonPrimitive.content)
            respondJson(
                """{"iid":17,"title":"Ship repository links","state":"opened","source_branch":"feature/repo-links","target_branch":"main","web_url":"https://gitlab.test/team/app/-/merge_requests/17"}""",
                status = HttpStatusCode.Created,
            )
        }
        val client = GitLabRestRepositoryOperationClient(
            tokenProvider = RepositoryServiceTokenProvider { "gitlab-token" },
            httpClient = trackedClient(engine),
        )

        val run = client.start(
            gitLabProject(),
            "open-merge-request:feature/repo-links:main:Ship repository links",
        )

        val artifact = run.artifacts.single()
        assertEquals(ArtifactKind.PullRequest, artifact.kind)
        assertEquals("https://gitlab.test/team/app/-/merge_requests/17", artifact.uri)
        assertEquals("merge-request", artifact.metadata["reviewKind"])
        assertEquals("17", artifact.metadata["reviewNumber"])
    }

    @Test
    fun gitHubOpenPullRequestUsesBearerCredentialAndProducesReviewArtifact() = runBlocking {
        val requests = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            val payload = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals("feature/repo-links", payload.getValue("head").jsonPrimitive.content)
            assertEquals("main", payload.getValue("base").jsonPrimitive.content)
            respondJson(
                """{"number":23,"title":"Ship repository links","state":"open","html_url":"https://github.test/HereLiesAz/aive/pull/23"}""",
                status = HttpStatusCode.Created,
            )
        }
        val client = GitHubRestRepositoryOperationClient(
            tokenProvider = RepositoryServiceTokenProvider { "github-token" },
            httpClient = trackedClient(engine),
            baseUrl = "https://api.github.test",
        )

        val run = client.start(
            githubProject(),
            "open-pull-request:feature/repo-links:main:Ship repository links",
        )

        val request = requests.single()
        assertEquals("Bearer github-token", request.headers[HttpHeaders.Authorization])
        assertEquals("2026-03-10", request.headers["X-GitHub-Api-Version"])
        val artifact = run.artifacts.single()
        assertEquals(ArtifactKind.PullRequest, artifact.kind)
        assertEquals("https://github.test/HereLiesAz/aive/pull/23", artifact.uri)
        assertEquals("23", artifact.metadata["reviewNumber"])
    }

    @Test
    fun routingClientSelectsRepositorySource() = runBlocking {
        val github = RecordingRepositoryOperationClient(RepositorySource.GitHub)
        val gitlab = RecordingRepositoryOperationClient(RepositorySource.GitLab)
        val routing = RoutingRepositoryOperationClient(listOf(github, gitlab))

        routing.start(gitLabProject(), "status")

        assertEquals(0, github.starts)
        assertEquals(1, gitlab.starts)
        assertTrue(routing.supports(githubProject()))
        assertTrue(routing.supports(gitLabProject()))
    }

    private fun redirectingEngine(requests: MutableList<io.ktor.client.request.HttpRequestData>) = MockEngine { request ->
        requests += request
        respond(
            content = "",
            status = HttpStatusCode.Found,
            headers = headersOf(HttpHeaders.Location, "https://evil.example/steal"),
        )
    }

    @Test
    fun repositoryClientsNeverFollowARedirectWithTheirToken() = runBlocking {
        val requests = mutableListOf<io.ktor.client.request.HttpRequestData>()
        // Whatever client is handed in, the redirect policy is the class's own.
        val gitHub = GitHubRestRepositoryOperationClient(RepositoryServiceTokenProvider { "gh-token" }, trackedClient(redirectingEngine(requests)))
        val gitLab = GitLabRestRepositoryOperationClient(RepositoryServiceTokenProvider { "gl-token" }, trackedClient(redirectingEngine(requests)))

        runCatching { gitHub.start(githubProject(), "status") }
        runCatching { gitLab.start(gitLabProject(), "status") }

        assertTrue(requests.isNotEmpty())
        assertTrue(requests.none { it.url.host == "evil.example" }, requests.map { it.url.toString() }.toString())
    }

    @Test
    fun actionsClientNeverFollowsARedirectWithItsToken() = runBlocking {
        val requests = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val client = GitHubRestActionsClient(GitHubTokenProvider { "gh-token" }, trackedClient(redirectingEngine(requests)))

        runCatching { client.getRun(githubProject().repository!!, "42") }

        assertTrue(requests.isNotEmpty())
        assertTrue(requests.none { it.url.host == "evil.example" }, requests.map { it.url.toString() }.toString())
    }

    private fun githubProject() = Project(
        id = ProjectId("github-project"),
        name = "GitHub",
        repository = RepositoryRef(
            owner = "HereLiesAz",
            name = "aive",
            defaultBranch = "main",
            source = RepositorySource.GitHub,
            remoteUrl = "https://github.com/HereLiesAz/aive",
        ),
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )

    private fun gitLabProject() = Project(
        id = ProjectId("gitlab-project"),
        name = "GitLab",
        repository = RepositoryRef(
            owner = "team",
            name = "app",
            defaultBranch = "main",
            source = RepositorySource.GitLab,
            remoteUrl = "https://gitlab.com/team/app",
        ),
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )
}

private class RecordingRepositoryOperationClient(
    private val source: RepositorySource,
) : RepositoryOperationClient {
    var starts: Int = 0

    override fun supports(project: Project): Boolean = project.repository?.source == source

    override suspend fun start(project: Project, operation: String): ExternalExecutionRun {
        starts++
        return ExternalExecutionRun("run-$starts", ExternalExecutionStatus.Completed)
    }

    override suspend fun getRun(project: Project, runId: String): ExternalExecutionRun =
        ExternalExecutionRun(runId, ExternalExecutionStatus.Completed)
}

private fun MockRequestHandleScope.respondJson(
    content: String,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respond(
    content = content,
    status = status,
    headers = headersOf(HttpHeaders.ContentType, "application/json"),
)
