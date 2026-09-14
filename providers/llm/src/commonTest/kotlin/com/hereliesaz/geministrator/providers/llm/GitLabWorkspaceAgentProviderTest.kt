package com.hereliesaz.geministrator.providers.llm

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.RepositorySource
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.providers.AgentEvent
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.ProviderActionResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GitLabWorkspaceAgentProviderTest {
    @Test
    fun commitsValidatedFileActionsToNestedGitLabRepository() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            when {
                request.method == HttpMethod.Get && request.url.encodedPath.endsWith("/repository/tree") ->
                    respondJson("""[{"path":"README.md","type":"blob"}]""")
                request.method == HttpMethod.Get && request.url.encodedPath.endsWith("/repository/files/README.md/raw") ->
                    respondText("initial\n")
                request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/repository/branches") ->
                    respondJson("""{"name":"haive/task-1-1"}""", HttpStatusCode.Created)
                request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/repository/commits") ->
                    respondJson(
                        """{"id":"abc123def456","web_url":"https://gitlab.test/group/sub/repo/-/commit/abc123def456"}""",
                        HttpStatusCode.Created,
                    )
                request.method == HttpMethod.Get && request.url.encodedPath.endsWith("/repository/commits/abc123def456/diff") ->
                    respondJson(
                        """[{"old_path":"README.md","new_path":"README.md","diff":"@@ -1 +1 @@\n-initial\n+changed"}]""",
                    )
                else -> respondJson(
                    """{"default_branch":"main","web_url":"https://gitlab.test/group/sub/repo"}""",
                )
            }
        }
        val api = QueueTextApi(
            TextGenerationResult(
                text = """
                    Update README.
                    FILES:
                    README.md
                """.trimIndent(),
                inputTokens = 7,
                outputTokens = 3,
            ),
            TextGenerationResult(
                text = """{"commitMessage":"Update README","actions":[{"action":"update","filePath":"README.md","content":"changed\n"}]}""",
                inputTokens = 11,
                outputTokens = 5,
            ),
        )
        val provider = provider(api, HttpClient(engine), "nested-gitlab")

        val handle = provider.start(request(TaskRunId("task-1")))
        val events = provider.observe(handle.providerRunId).toList()

        assertCompleted(events)
        assertTrue(requests.isNotEmpty())
        assertTrue(requests.all { it.headers["PRIVATE-TOKEN"] == "gitlab-token" })
        assertTrue(requests.any { it.url.encodedPath.contains("/api/v4/projects/group%2Fsub%2Frepo") })

        val branchRequest = requests.single {
            it.method == HttpMethod.Post && it.url.encodedPath.endsWith("/repository/branches")
        }
        assertEquals("main", branchRequest.url.parameters["ref"])
        assertTrue(branchRequest.url.parameters["branch"]?.startsWith("haive/task-1-") == true)

        val commitRequest = requests.single {
            it.method == HttpMethod.Post && it.url.encodedPath.endsWith("/repository/commits")
        }
        val payload = Json.parseToJsonElement((commitRequest.body as TextContent).text).jsonObject
        assertEquals("Update README", payload.getValue("commit_message").jsonPrimitive.content)
        val action = payload.getValue("actions").jsonArray.single().jsonObject
        assertEquals("update", action.getValue("action").jsonPrimitive.content)
        assertEquals("README.md", action.getValue("file_path").jsonPrimitive.content)
        assertEquals("changed\n", action.getValue("content").jsonPrimitive.content)

        val artifact = events
            .filterIsInstance<AgentEvent.ArtifactProduced>()
            .map { it.artifact }
            .single { it.kind == ArtifactKind.CodeChange }
        assertEquals("abc123def456", artifact.metadata["headCommit"])
        assertEquals("main", artifact.metadata["baseBranch"])
        assertEquals("https://gitlab.test/group/sub/repo", artifact.metadata["repositoryUrl"])
        assertTrue(artifact.metadata.getValue("compareUrl").contains("/-/compare/main...haive/"))
        assertTrue(artifact.textContent.orEmpty().contains("+changed"))
        assertTrue(events.any { event ->
            event is AgentEvent.UsageReported && event.inputTokens == 18L && event.outputTokens == 8L
        })
    }

    @Test
    fun approvalPreventsBranchAndCommitUntilAccepted() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            when {
                request.method == HttpMethod.Get && request.url.encodedPath.endsWith("/repository/tree") ->
                    respondJson("""[{"path":"README.md","type":"blob"}]""")
                request.method == HttpMethod.Get && request.url.encodedPath.endsWith("/repository/files/README.md/raw") ->
                    respondText("initial\n")
                request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/repository/branches") ->
                    respondJson("""{"name":"haive/approval-task-1"}""", HttpStatusCode.Created)
                request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/repository/commits") ->
                    respondJson("""{"id":"approved123","web_url":"https://gitlab.test/group/sub/repo/-/commit/approved123"}""", HttpStatusCode.Created)
                request.method == HttpMethod.Get && request.url.encodedPath.endsWith("/repository/commits/approved123/diff") ->
                    respondJson("""[{"old_path":"README.md","new_path":"README.md","diff":"@@ -1 +1 @@\n-initial\n+approved"}]""")
                else -> respondJson(
                    """{"default_branch":"main","web_url":"https://gitlab.test/group/sub/repo"}""",
                )
            }
        }
        val api = QueueTextApi(
            TextGenerationResult(
                text = """
                    Update README after approval.
                    FILES:
                    README.md
                """.trimIndent(),
            ),
            TextGenerationResult(
                text = """{"commitMessage":"Approved update","actions":[{"action":"update","filePath":"README.md","content":"approved\n"}]}""",
            ),
        )
        val provider = provider(api, HttpClient(engine), "approval-gitlab")
        val handle = provider.start(request(TaskRunId("approval-task"), requirePlanApproval = true))

        val firstEvent = provider.observe(handle.providerRunId).first()
        assertIs<AgentEvent.PlanGenerated>(firstEvent)
        assertEquals(1, api.callCount)
        assertTrue(requests.none { request ->
            request.method == HttpMethod.Post &&
                (request.url.encodedPath.endsWith("/repository/branches") ||
                    request.url.encodedPath.endsWith("/repository/commits"))
        })

        assertEquals(ProviderActionResult.Accepted, provider.approvePlan(handle.providerRunId))
        val events = provider.observe(handle.providerRunId).toList()

        assertCompleted(events)
        assertEquals(2, api.callCount)
        assertTrue(requests.any {
            it.method == HttpMethod.Post && it.url.encodedPath.endsWith("/repository/branches")
        })
        assertTrue(requests.any {
            it.method == HttpMethod.Post && it.url.encodedPath.endsWith("/repository/commits")
        })
    }

    @Test
    fun unsafeModelPathFailsBeforeAnyRepositoryWrite() = runBlocking {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            when {
                request.method == HttpMethod.Get && request.url.encodedPath.endsWith("/repository/tree") ->
                    respondJson("""[{"path":"README.md","type":"blob"}]""")
                request.method == HttpMethod.Get && request.url.encodedPath.endsWith("/repository/files/README.md/raw") ->
                    respondText("initial\n")
                else -> respondJson(
                    """{"default_branch":"main","web_url":"https://gitlab.test/group/sub/repo"}""",
                )
            }
        }
        val api = QueueTextApi(
            TextGenerationResult(
                text = """
                    Inspect README.
                    FILES:
                    README.md
                """.trimIndent(),
            ),
            TextGenerationResult(
                text = """{"commitMessage":"Escape","actions":[{"action":"create","filePath":"../outside.txt","content":"nope"}]}""",
            ),
        )
        val provider = provider(api, HttpClient(engine), "unsafe-gitlab")

        val handle = provider.start(request(TaskRunId("unsafe-task")))
        val events = provider.observe(handle.providerRunId).toList()

        val failure = assertNotNull(events.filterIsInstance<AgentEvent.Failed>().lastOrNull())
        assertTrue(failure.reason.contains("unsafe path"))
        assertTrue(requests.none { it.method == HttpMethod.Post })
    }

    private fun provider(
        api: TextGenerationApi,
        client: HttpClient,
        id: String,
    ) = GitLabWorkspaceAgentProvider(
        id = AgentProviderId(id),
        displayName = "GitLab test workspace",
        api = api,
        tokenProvider = GitLabWorkspaceTokenProvider { "gitlab-token" },
        httpClient = client,
    )

    private fun request(
        taskRunId: TaskRunId,
        requirePlanApproval: Boolean = false,
    ) = AgentTaskRequest(
        taskRunId = taskRunId,
        objective = "Update README",
        roleInstructions = "Implement the requested repository change.",
        acceptanceCriteria = emptyList(),
        repository = RepositoryRef(
            owner = "group/sub",
            name = "repo",
            defaultBranch = "main",
            source = RepositorySource.GitLab,
            remoteUrl = "https://gitlab.test/group/sub/repo",
        ),
        requirePlanApproval = requirePlanApproval,
    )

    private fun assertCompleted(events: List<AgentEvent>) {
        val failure = events.filterIsInstance<AgentEvent.Failed>().lastOrNull()
        assertTrue(failure == null, failure?.reason ?: "GitLab workspace provider emitted a failure")
        assertIs<AgentEvent.Completed>(events.last())
    }

    private class QueueTextApi(vararg responses: TextGenerationResult) : TextGenerationApi {
        private val queued = responses.toList()
        var callCount: Int = 0
            private set

        override suspend fun generate(prompt: String): TextGenerationResult {
            check(callCount < queued.size) { "Unexpected generation call ${callCount + 1}" }
            return queued[callCount++]
        }
    }
}

private fun MockRequestHandleScope.respondJson(
    content: String,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respond(
    content = content,
    status = status,
    headers = headersOf(HttpHeaders.ContentType, "application/json"),
)

private fun MockRequestHandleScope.respondText(
    content: String,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respond(
    content = content,
    status = status,
    headers = headersOf(HttpHeaders.ContentType, "text/plain"),
)
