package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.RepositoryRef
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GitHubRestActionsClientTest {
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
    fun dispatchUsesReturnedWorkflowRunIdWithoutGuessingFromRunList() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            when (request.method) {
                HttpMethod.Post -> {
                    val payload = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    assertEquals("main", payload.getValue("ref").jsonPrimitive.content)
                    assertTrue(payload.getValue("return_run_details").jsonPrimitive.boolean)
                    respond(
                        content = """{"workflow_run_id":42,"run_url":"https://api.github.test/runs/42","html_url":"https://github.test/runs/42"}""",
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "application/json"),
                    )
                }
                else -> error("Unexpected request: ${request.method} ${request.url}")
            }
        }
        val client = GitHubRestActionsClient(
            httpClient = trackedClient(engine),
            tokenProvider = GitHubTokenProvider { "token-123" },
            baseUrl = "https://api.github.test",
        )

        val run = client.dispatch(
            GitHubWorkflowDispatchRequest(
                repository = RepositoryRef("HereLiesAz", "aive", "main"),
                workflow = "ci.yml",
                ref = "main",
            ),
        )

        assertEquals("42", run.id)
        assertEquals(GitHubWorkflowRunStatus.Queued, run.status)
        assertEquals(1, requests.size)
        val dispatch = requests.single()
        assertEquals(HttpMethod.Post, dispatch.method)
        assertEquals("/repos/HereLiesAz/aive/actions/workflows/ci.yml/dispatches", dispatch.url.encodedPath)
        assertEquals("Bearer token-123", dispatch.headers[HttpHeaders.Authorization])
        assertEquals("2026-03-10", dispatch.headers["X-GitHub-Api-Version"])
    }

    @Test
    fun getRunMapsSuccessAndNonExpiredArtifacts() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            when {
                request.url.encodedPath.endsWith("/artifacts") -> respond(
                    content = """{"artifacts":[{"id":7,"name":"results","expired":false,"archive_download_url":"https://api.github.test/artifacts/7.zip"},{"id":8,"name":"old","expired":true,"archive_download_url":"https://api.github.test/artifacts/8.zip"}]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                request.url.encodedPath.endsWith("/jobs") -> respond(
                    content = """{"jobs":[]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> respond(
                    content = """{"id":42,"status":"completed","conclusion":"success"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val client = GitHubRestActionsClient(
            httpClient = trackedClient(engine),
            tokenProvider = GitHubTokenProvider { "token-123" },
            baseUrl = "https://api.github.test",
        )

        val run = client.getRun(RepositoryRef("HereLiesAz", "aive", "main"), "42")

        assertEquals(GitHubWorkflowRunStatus.Completed, run.status)
        assertEquals("success", run.progressMessage)
        assertEquals(
            listOf(
                GitHubWorkflowArtifact(
                    "7",
                    "results",
                    "https://api.github.test/artifacts/7.zip",
                ),
            ),
            run.artifacts,
        )
        assertEquals(3, requests.size)
        assertTrue(requests.all { it.method == HttpMethod.Get })
    }

    @Test
    fun getRunMapsCompletedNonSuccessToFailure() = runBlocking {
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/artifacts") -> respond(
                    content = """{"artifacts":[]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                request.url.encodedPath.endsWith("/jobs") -> respond(
                    content = """{"jobs":[]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> respond(
                    content = """{"id":42,"status":"completed","conclusion":"failure"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val client = GitHubRestActionsClient(
            httpClient = trackedClient(engine),
            tokenProvider = GitHubTokenProvider { "token" },
            baseUrl = "https://api.github.test",
        )

        val run = client.getRun(RepositoryRef("HereLiesAz", "aive", "main"), "42")

        assertEquals(GitHubWorkflowRunStatus.Failed, run.status)
    }

    @Test
    fun getRunReturnsJobStepProgress() = runBlocking {
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/artifacts") -> respond(
                    content = """{"artifacts":[]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                request.url.encodedPath.endsWith("/jobs") -> respond(
                    content = """{"jobs":[{"id":1,"name":"Build","status":"in_progress","conclusion":null,"steps":[{"name":"Checkout","status":"completed","conclusion":"success"},{"name":"Compile","status":"in_progress","conclusion":null},{"name":"Package","status":"queued","conclusion":null}]}]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> respond(
                    content = """{"id":42,"status":"in_progress","conclusion":null}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val client = GitHubRestActionsClient(
            httpClient = trackedClient(engine),
            tokenProvider = GitHubTokenProvider { "token" },
            baseUrl = "https://api.github.test",
        )

        val run = client.getRun(RepositoryRef("HereLiesAz", "aive", "main"), "42")

        assertEquals(GitHubWorkflowRunStatus.Running, run.status)
        assertEquals(1, run.jobs.size)
        val job = run.jobs.single()
        assertEquals("Build", job.name)
        assertEquals(GitHubWorkflowRunStatus.Running, job.status)
        assertEquals("Compile", job.currentStep)
        assertEquals(1, job.completedSteps)
        assertEquals(3, job.totalSteps)
    }

    @Test
    fun getRunMapsCancelledConclusionToFailedWithMessage() = runBlocking {
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/artifacts") -> respond(
                    content = """{"artifacts":[]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                request.url.encodedPath.endsWith("/jobs") -> respond(
                    content = """{"jobs":[]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> respond(
                    content = """{"id":42,"status":"completed","conclusion":"cancelled"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val client = GitHubRestActionsClient(
            httpClient = trackedClient(engine),
            tokenProvider = GitHubTokenProvider { "token" },
            baseUrl = "https://api.github.test",
        )

        val run = client.getRun(RepositoryRef("HereLiesAz", "aive", "main"), "42")

        assertEquals(GitHubWorkflowRunStatus.Failed, run.status)
        assertEquals("Run was cancelled", run.progressMessage)
    }

    @Test
    fun getRunMapsTimedOutToFailedWithMessage() = runBlocking {
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/artifacts") -> respond(
                    content = """{"artifacts":[]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                request.url.encodedPath.endsWith("/jobs") -> respond(
                    content = """{"jobs":[]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
                else -> respond(
                    content = """{"id":42,"status":"completed","conclusion":"timed_out"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
        }
        val client = GitHubRestActionsClient(
            httpClient = trackedClient(engine),
            tokenProvider = GitHubTokenProvider { "token" },
            baseUrl = "https://api.github.test",
        )

        val run = client.getRun(RepositoryRef("HereLiesAz", "aive", "main"), "42")

        assertEquals(GitHubWorkflowRunStatus.Failed, run.status)
        assertEquals("Run timed out", run.progressMessage)
    }

    @Test
    fun cancelPostsToTheRunCancelEndpointAndTreatsAFinishedRunAsDone() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        var status = HttpStatusCode.Accepted
        val engine = MockEngine { request ->
            requests += request
            respond(content = "{}", status = status, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = GitHubRestActionsClient(
            httpClient = trackedClient(engine),
            tokenProvider = GitHubTokenProvider { "token-123" },
            baseUrl = "https://api.github.test",
        )
        val repository = RepositoryRef("HereLiesAz", "aive", "main")

        client.cancel(repository, "42")
        // 409: the run already finished. Cancelling it again is not an error.
        status = HttpStatusCode.Conflict
        client.cancel(repository, "42")

        assertEquals(2, requests.size)
        requests.forEach { request ->
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("/repos/HereLiesAz/aive/actions/runs/42/cancel", request.url.encodedPath)
            assertEquals("Bearer token-123", request.headers[HttpHeaders.Authorization])
        }

        status = HttpStatusCode.Forbidden
        val failure = assertFailsWith<IllegalStateException> { client.cancel(repository, "42") }
        assertTrue(failure.message.orEmpty().contains("HTTP 403"), failure.message)
    }
}
