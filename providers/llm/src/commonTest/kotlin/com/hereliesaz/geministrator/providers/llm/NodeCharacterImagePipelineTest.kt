package com.hereliesaz.geministrator.providers.llm

import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.workflow.NODE_CHARACTER_IMAGE_PIPELINE_SERVICE
import com.hereliesaz.geministrator.workflow.NodeCharacterAssetRecord
import com.hereliesaz.geministrator.workflow.NodeCharacterAssetStore
import com.hereliesaz.geministrator.workflow.TaskExecutorContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

class NodeCharacterImagePipelineTest {
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

    private val noStore = object : NodeCharacterAssetStore {
        override suspend fun get(roleId: com.hereliesaz.geministrator.domain.RoleDefinitionId): NodeCharacterAssetRecord? = null
        override suspend fun all(): List<NodeCharacterAssetRecord> = emptyList()
        override suspend fun put(record: NodeCharacterAssetRecord) = Unit
        override suspend fun remove(roleId: com.hereliesaz.geministrator.domain.RoleDefinitionId) = Unit
    }

    private fun context(): TaskExecutorContext {
        val gen = TaskDefinition(
            id = TaskDefinitionId("gen"),
            name = "gen",
            objective = "gen",
            roleId = null,
            dependsOn = setOf(TaskDefinitionId("contract")),
            executor = TaskExecutor.ExternalService(NODE_CHARACTER_IMAGE_PIPELINE_SERVICE, "generate|wf|role-1"),
        )
        val definition = WorkflowDefinition(WorkflowDefinitionId("wf"), "wf", tasks = listOf(gen))
        val contract = TaskRun(
            id = TaskRunId("tr-contract"),
            taskDefinitionId = TaskDefinitionId("contract"),
            status = TaskRunStatus.Completed,
            assignedRoleId = null,
            artifacts = listOf(
                ArtifactRef(ArtifactId("a"), ArtifactKind.Specification, TaskRunId("tr-contract"), "contract", textContent = "A round creature.", createdAtEpochMillis = 0),
            ),
        )
        val genRun = TaskRun(id = TaskRunId("tr-gen"), taskDefinitionId = gen.id, status = TaskRunStatus.Ready, assignedRoleId = null)
        val run = WorkflowRun(
            id = WorkflowRunId("run"),
            projectId = ProjectId("p"),
            workflowDefinitionId = definition.id,
            objective = "o",
            status = WorkflowRunStatus.Running,
            taskRuns = mapOf(TaskDefinitionId("contract") to contract, gen.id to genRun),
            createdAtEpochMillis = 0,
            updatedAtEpochMillis = 0,
        )
        return TaskExecutorContext(
            project = Project(ProjectId("p"), "P", createdAtEpochMillis = 0, updatedAtEpochMillis = 0),
            definition = definition,
            run = run,
            task = gen,
            taskRun = genRun,
            executor = gen.executor!!,
            nowEpochMillis = 1_000,
        )
    }

    private class Calls(val qcPasses: Boolean) {
        var count = 0
    }

    private fun integration(
        calls: Calls,
        budget: Int = NODE_CHARACTER_CALL_BUDGET_DEFAULT,
        imageStore: com.hereliesaz.geministrator.workflow.NodeCharacterImageStore? = null,
    ): NodeCharacterImagePipelineExecutorIntegration {
        val engine = MockEngine { request ->
            calls.count += 1
            val body = (request.body as TextContent).text
            val payload = if ("image_generation" in body) {
                """{"output":[{"type":"image_generation_call","result":"QUJD"}],"usage":{"input_tokens":1000,"output_tokens":500}}"""
            } else {
                val pass = calls.qcPasses
                """{"output":[{"type":"message","content":[{"type":"output_text","text":"{\"pass\":$pass,\"summary\":\"s\",\"issues\":[\"bad hands\"]}"}]}],"usage":{"input_tokens":2000,"output_tokens":100}}"""
            }
            respond(payload, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = trackedClient(engine) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        val api = OpenAiNodeCharacterImageApi(LlmApiKeyProvider { "k" }, client = client)
        return NodeCharacterImagePipelineExecutorIntegration(
            apiKeyProvider = LlmApiKeyProvider { "k" },
            assetStore = noStore,
            api = api,
            maxCallsPerDispatch = budget,
            imageStore = imageStore,
        )
    }

    @Test
    fun paidImageCallsAreNeverRetriedByTheWorkflow() {
        assertFalse(integration(Calls(true)).retryDispatchFailures)
    }

    @Test
    fun aFinishedDispatchReportsItsSpendAndFailsClosedOnTheImageCalls() = runTest {
        val calls = Calls(qcPasses = true)
        val execution = integration(calls).dispatch(context())
        assertEquals(TaskRunStatus.Completed, execution.status)
        assertEquals(4, calls.count, "source and rig, each drawn and inspected once")
        val usage = assertNotNull(execution.usage)
        assertEquals(2 * 1000L + 2 * 2000L, usage.inputTokens)
        assertTrue(usage.costUnknown, "the image model is not on the price list, so the cost is not free")
    }

    @Test
    fun theCallBudgetStopsTheLoopAndTheBilledCallsAreStillCounted() = runTest {
        val calls = Calls(qcPasses = false)
        val execution = integration(calls, budget = 3).dispatch(context())
        assertEquals(TaskRunStatus.Failed, execution.status)
        assertEquals(3, calls.count, "no fourth paid call is made")
        assertTrue(execution.progressMessage.orEmpty().contains("call budget"), execution.progressMessage)
        val usage = assertNotNull(execution.usage)
        assertEquals(1000L + 2000L + 1000L, usage.inputTokens)
        assertTrue(usage.costUnknown)
    }

    @Test
    fun withAnImageStoreTheArtifactsKeepAReferenceAndNotTheImage() = runTest {
        val store = com.hereliesaz.geministrator.workflow.InMemoryNodeCharacterImageStore()
        val execution = integration(Calls(true), imageStore = store).dispatch(context())
        val media = execution.artifacts.filter { it.kind == ArtifactKind.Media }
        assertEquals(2, media.size)
        media.forEach { assertTrue(it.uri.orEmpty().startsWith("memory:"), "a reference, not base64: ${it.uri?.take(40)}") }
        assertEquals(2, store.images.size)
        assertEquals("ABC", store.images.values.first().decodeToString(), "the stored bytes are the decoded PNG")
    }

    @Test
    fun withoutAnImageStoreTheArtifactsStillCarryTheDataUri() = runTest {
        val execution = integration(Calls(true)).dispatch(context())
        execution.artifacts.filter { it.kind == ArtifactKind.Media }.forEach {
            assertTrue(it.uri.orEmpty().startsWith("data:image/png;base64,"))
        }
    }
}
