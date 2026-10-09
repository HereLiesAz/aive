package com.hereliesaz.geministrator.providers.llm

import com.hereliesaz.geministrator.domain.AcceptanceCriterion
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.providers.AgentEvent
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.ProviderActionResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MoyaiProviderTest {
    private val request = AgentTaskRequest(
        taskRunId = TaskRunId("task-1"),
        objective = "Fix the sync race",
        roleInstructions = "Work only in the affected synchronization code.",
        acceptanceCriteria = listOf(AcceptanceCriterion("Regression test passes")),
        requiredArtifacts = setOf(ArtifactKind.CodeChange, ArtifactKind.PullRequest),
        repository = RepositoryRef("HereLiesAz", "aive", "main"),
        requirePlanApproval = true,
    )

    @Test
    fun planGateIsLocalAndStartsNothingBeforeApproval() = runTest {
        var requests = 0
        val client = jsonClient(MockEngine {
            requests += 1
            error("draftPlan must not contact Moyai")
        })
        try {
            val provider = MoyaiProvider(
                id = AgentProviderId("azphalt:moyai"),
                displayName = "Moyai",
                baseUrl = "https://moyai.example",
                client = client,
                pollIntervalMillis = 250,
            )
            val plan = provider.draftPlan(request)
            assertContains(plan, "isolated Moyai workspace")
            assertContains(plan, "Regression")
            assertEquals(0, requests)
        } finally {
            client.close()
        }
    }

    @Test
    fun logsInStartsReconnectsAndReturnsRemoteArtifactsWithoutRedispatch() = runTest {
        val requests = mutableListOf<String>()
        var sessionReads = 0
        var runCreates = 0
        val client = jsonClient(MockEngine { call ->
            val path = call.url.encodedPath
            requests += "${call.method.value} $path"
            when {
                path == "/api/session" -> {
                    sessionReads += 1
                    if (sessionReads == 1) {
                        json("""{"authenticated":false,"csrf":""}""")
                    } else {
                        json("""{"authenticated":true,"csrf":"csrf-123"}""")
                    }
                }
                path == "/api/login" -> {
                    assertEquals("https://moyai.example", call.headers["Origin"])
                    assertContains((call.body as TextContent).text, "workspace-secret")
                    json("""{"authenticated":true,"role":"admin"}""")
                }
                path == "/api/runs" -> {
                    runCreates += 1
                    assertEquals("https://moyai.example", call.headers["Origin"])
                    assertEquals("csrf-123", call.headers["X-CSRF-Token"])
                    val body = (call.body as TextContent).text
                    assertContains(body, "Fix the sync race")
                    assertContains(body, "https://github.com/HereLiesAz/aive")
                    json("""{"id":"0123456789abcdef0123456789abcdef","status":"queued"}""", HttpStatusCode.Created)
                }
                path == "/api/runs/0123456789abcdef0123456789abcdef" -> json(
                    """{
                      "id":"0123456789abcdef0123456789abcdef",
                      "status":"completed",
                      "events":[{"id":1,"kind":"status","message":"tests passed"}],
                      "messages":[{"id":1,"role":"assistant","content":"Fixed the race and added regression coverage."}],
                      "pull_requests":[{"url":"https://github.com/HereLiesAz/aive/pull/999","title":"Fix sync race"}],
                      "has_artifact":true
                    }""",
                )
                else -> error("Unexpected request ${call.method.value} ${call.url}")
            }
        })
        try {
            val provider = MoyaiProvider(
                id = AgentProviderId("azphalt:moyai"),
                displayName = "Moyai",
                baseUrl = "https://moyai.example",
                workspacePassword = "workspace-secret",
                client = client,
                pollIntervalMillis = 250,
            )

            val handle = provider.start(request.copy(requirePlanApproval = false))
            assertEquals(1, runCreates)

            val reconnect = provider.reconnect(
                runId = handle.providerRunId,
                request = request.copy(requirePlanApproval = false),
                planGenerated = true,
                planApproved = true,
                planPreview = provider.draftPlan(request),
            )
            assertIs<ProviderActionResult.Accepted>(reconnect)
            assertEquals(1, runCreates, "Reconnect must re-use the persisted Moyai run")

            val events = provider.observe(handle.providerRunId).toList()
            assertTrue(events.any { it is AgentEvent.Completed })
            val artifacts = events.filterIsInstance<AgentEvent.ArtifactProduced>().map { it.artifact }
            val code = artifacts.single { it.kind == ArtifactKind.CodeChange }
            assertContains(code.uri.orEmpty(), "/api/runs/0123456789abcdef0123456789abcdef/artifact")
            assertContains(code.textContent.orEmpty(), "Fixed the race")
            val pr = artifacts.single { it.kind == ArtifactKind.PullRequest }
            assertEquals("https://github.com/HereLiesAz/aive/pull/999", pr.uri)
            assertTrue(requests.count { it == "POST /api/runs" } == 1)
        } finally {
            client.close()
        }
    }

    @Test
    fun messagingAndCancellationUseAuthenticatedMutationBoundary() = runTest {
        val mutations = mutableListOf<Pair<String, String?>>()
        val client = jsonClient(MockEngine { call ->
            val path = call.url.encodedPath
            when (path) {
                "/api/session" -> json("""{"authenticated":true,"csrf":"csrf-direct"}""")
                "/api/runs/run-1/messages" -> {
                    mutations += path to call.headers["X-CSRF-Token"]
                    assertEquals("https://moyai.example", call.headers["Origin"])
                    assertContains((call.body as TextContent).text, "Steer toward the failing test")
                    json("""{"id":2,"status":"queued","model":"m","created":true}""", HttpStatusCode.Accepted)
                }
                "/api/runs/run-1/cancel" -> {
                    mutations += path to call.headers["X-CSRF-Token"]
                    json("""{"id":"run-1","status":"cancelled"}""")
                }
                else -> error("Unexpected request ${call.method.value} ${call.url}")
            }
        })
        try {
            val provider = MoyaiProvider(
                id = AgentProviderId("azphalt:moyai"),
                displayName = "Moyai",
                baseUrl = "https://moyai.example",
                client = client,
            )
            assertIs<ProviderActionResult.Accepted>(
                provider.sendMessage(
                    com.hereliesaz.geministrator.domain.ProviderRunId("run-1"),
                    "Steer toward the failing test",
                ),
            )
            assertIs<ProviderActionResult.Accepted>(
                provider.cancel(com.hereliesaz.geministrator.domain.ProviderRunId("run-1")),
            )
            assertEquals(
                listOf<Pair<String, String?>>(
                    "/api/runs/run-1/messages" to "csrf-direct",
                    "/api/runs/run-1/cancel" to "csrf-direct",
                ),
                mutations,
            )
        } finally {
            client.close()
        }
    }

    private fun jsonClient(engine: MockEngine) = HttpClient(engine) {
        expectSuccess = false
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true; explicitNulls = false })
        }
    }

    private fun MockRequestHandleScope.json(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(
        content = body,
        status = status,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )
}
