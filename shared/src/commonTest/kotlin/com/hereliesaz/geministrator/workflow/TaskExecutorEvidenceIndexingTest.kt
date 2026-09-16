package com.hereliesaz.geministrator.workflow

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
import com.hereliesaz.geministrator.inference.InMemoryInferenceDataRegistry
import com.hereliesaz.geministrator.inference.InferenceDataKind
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class TaskExecutorEvidenceIndexingTest {
    @Test
    fun systemArtifactsEnterInferenceRegistryAsToolEvidenceWithoutAgentInvocation() = runBlocking {
        val taskId = TaskDefinitionId("ci")
        val taskRunId = TaskRunId("run-ci")
        val executor = TaskExecutor.GitHubAction("ci.yml")
        val artifact = ArtifactRef(
            id = ArtifactId("artifact-ci"),
            kind = ArtifactKind.TestResult,
            taskRunId = taskRunId,
            label = "CI result",
            textContent = "passed",
            mediaType = "text/plain",
            createdAtEpochMillis = 10L,
        )
        val task = TaskDefinition(
            id = taskId,
            name = "CI",
            objective = "Run CI",
            roleId = null,
            executor = executor,
        )
        val definition = WorkflowDefinition(
            id = WorkflowDefinitionId("workflow"),
            name = "Workflow",
            tasks = listOf(task),
        )
        val project = Project(
            id = ProjectId("project"),
            name = "Project",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
        )
        val taskRun = TaskRun(
            id = taskRunId,
            taskDefinitionId = taskId,
            status = TaskRunStatus.Running,
            assignedRoleId = null,
            executor = executor,
            externalRunId = "ci-1",
        )
        val run = WorkflowRun(
            id = WorkflowRunId("run"),
            projectId = project.id,
            workflowDefinitionId = definition.id,
            objective = "Ship",
            status = WorkflowRunStatus.Running,
            taskRuns = mapOf(taskId to taskRun),
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
        )
        val context = TaskExecutorContext(
            project = project,
            definition = definition,
            run = run,
            task = task,
            taskRun = taskRun,
            executor = executor,
            nowEpochMillis = 10L,
        )
        val dataRegistry = InMemoryInferenceDataRegistry()
        val integration = object : TaskExecutorIntegration {
            override fun supports(executor: TaskExecutor): Boolean = executor is TaskExecutor.GitHubAction

            override suspend fun dispatch(context: TaskExecutorContext) = TaskExecutorExecution(
                status = TaskRunStatus.Running,
                externalRunId = "ci-1",
                artifacts = listOf(artifact),
            )

            override suspend fun reconcile(context: TaskExecutorContext) = TaskExecutorExecution(
                status = TaskRunStatus.Completed,
                externalRunId = "ci-1",
                artifacts = listOf(artifact),
                progress = 1f,
            )
        }
        val registry = TaskExecutorIntegrationRegistry(listOf(integration))
            .withInferenceDataRegistry(dataRegistry)
        val indexed = requireNotNull(registry.integrationFor(executor, project))

        indexed.dispatch(context)
        indexed.reconcile(context)

        val evidence = dataRegistry.all()
        assertEquals(1, evidence.size)
        assertEquals("artifact:${artifact.id.value}", evidence.single().dataId)
        assertEquals(InferenceDataKind.ToolEvidence, evidence.single().kind)
        assertEquals(artifact.id, evidence.single().artifactId)
        assertEquals(taskRunId, evidence.single().producingTaskRunId)
        assertEquals("CI result", evidence.single().label)
        assertEquals("text/plain", evidence.single().mediaType)
    }
}
