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
