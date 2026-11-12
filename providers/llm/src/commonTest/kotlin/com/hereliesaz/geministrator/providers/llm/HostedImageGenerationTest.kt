package com.hereliesaz.geministrator.providers.llm

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
import com.hereliesaz.geministrator.workflow.TaskExecutorContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class HostedImageGenerationTest {
    private fun context(
        operation: String,
        objective: String = "Draw one butterfly",
        runId: String? = null,
    ): TaskExecutorContext {
        val taskId = TaskDefinitionId("draw")
        val executor = TaskExecutor.ExternalService(HostedImageModels.SERVICE, operation)
        val task = TaskDefinition(taskId, "Draw", objective, null, executor = executor)
        val definition = WorkflowDefinition(WorkflowDefinitionId("image"), "Image", tasks = listOf(task))
        val taskRun = TaskRun(
            TaskRunId("image-task"), taskId,
            if (runId == null) TaskRunStatus.Ready else TaskRunStatus.Running,
            assignedRoleId = null,
            externalRunId = runId,
        )
        val run = WorkflowRun(
            WorkflowRunId("run"), ProjectId("project"), definition.id, objective,
            WorkflowRunStatus.Running, mapOf(taskId to taskRun), 1, 1,
        )
        return TaskExecutorContext(
            Project(ProjectId("project"), "Project", createdAtEpochMillis = 1, updatedAtEpochMillis = 1),
            definition, run, task, taskRun, executor, 2,
        )
    }

    @Test
    fun theCatalogDoesNotAdvertiseEditingOrTransparentBackground() {
        assertEquals(2, HostedImageModels.available.size)
        assertTrue(HostedImageModels.available.any { it.anonymous })
        assertTrue(HostedImageModels.available.any { !it.anonymous })
        for (model in HostedImageModels.available) {
            assertFalse(HostedImageModels.supports(model, editing = true, transparency = false))
            assertFalse(HostedImageModels.supports(model, editing = false, transparency = true))
        }
        assertEquals(null, HostedImageModels.resolve("fal|https://attacker.invalid"))
    }

    @Test
    fun anonymousHordeCanRunWithoutCredentialsButFalCannot() {
        val executor = HostedImageGenerationExecutorIntegration()
        assertTrue(executor.supports(context("aihorde|auto").executor))
        assertFalse(executor.supports(context("fal|fal-ai/flux/schnell").executor))
        assertFalse(executor.supports(TaskExecutor.ExternalService("node-character-image-pipeline", "generate|a|b")))
    }

    @Test
    fun hordeDispatchAndReconcileReturnVerifiedRemoteArtifacts() = runTest {
        val requests = mutableListOf<String>()
        val http = HttpClient(MockEngine { request ->
            requests += request.url.toString()
            val content = when {
                request.url.encodedPath.endsWith("/generate/async") -> """{"id":"abcd-1234"}"""
                request.url.encodedPath.endsWith("/check/abcd-1234") -> """{"done":true,"faulted":false}"""
                request.url.encodedPath.endsWith("/status/abcd-1234") -> """{"generations":[{"img":"https://example.org/generated.png"}]}"""
                else -> error("Unexpected request: ${request.url}")
            }
            respond(content, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val executor = HostedImageGenerationExecutorIntegration(client = http)
        val started = executor.dispatch(context("aihorde|auto"))
        assertEquals(TaskRunStatus.Running, started.status)
        assertEquals("horde:abcd-1234", started.externalRunId)
        val finished = executor.reconcile(context("aihorde|auto", runId = "horde:abcd-1234"))
        assertEquals(TaskRunStatus.Completed, finished.status)
        assertEquals("https://example.org/generated.png", finished.artifacts.single().uri)
        assertEquals("aihorde", finished.artifacts.single().metadata["provider"])
        assertEquals(3, requests.size)
    }

    @Test
    fun falRequiresKeyAndReturnsItsImageWithoutPretendingItSupportsEditing() = runTest {
        val http = HttpClient(MockEngine { request ->
            assertEquals("https://fal.run/fal-ai/flux/schnell", request.url.toString())
            assertEquals("Key test", request.headers[HttpHeaders.Authorization])
            respond("""{"images":[{"url":"https://example.org/image.png"}]}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val executor = HostedImageGenerationExecutorIntegration(falKey = { "test" }, client = http)
        val result = executor.dispatch(context("fal|fal-ai/flux/schnell"))
        assertEquals(TaskRunStatus.Completed, result.status, result.progressMessage)
        assertEquals("https://example.org/image.png", result.artifacts.single().uri)
        assertFalse(executor.retryDispatchFailures)
    }

    @Test
    fun unsafeOrInvalidProviderResultsDoNotBecomeArtifacts() = runTest {
        val http = HttpClient(MockEngine {
            respond("""{"images":[{"url":"file:///private/credentials"}]}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val executor = HostedImageGenerationExecutorIntegration(falKey = { "test" }, client = http)
        val result = executor.dispatch(context("fal|fal-ai/flux/schnell"))
        assertEquals(TaskRunStatus.Failed, result.status)
        assertTrue(result.artifacts.isEmpty())
    }
}
