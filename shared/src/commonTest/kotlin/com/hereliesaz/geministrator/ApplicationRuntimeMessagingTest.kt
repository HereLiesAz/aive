package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.EnvironmentPlanningPolicy
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.TestDesignPolicy
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.persistence.InMemoryWorkflowPersistence
import com.hereliesaz.geministrator.providers.AgentCapabilities
import com.hereliesaz.geministrator.providers.AgentEvent
import com.hereliesaz.geministrator.providers.AgentProvider
import com.hereliesaz.geministrator.providers.AgentRunHandle
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.ProviderActionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ApplicationRuntimeMessagingTest {
    @Test
    fun messageTaskUsesRestoredProviderHandleAndKeepsRuntimeLiveOnRejection() = runBlocking {
        val persistence = InMemoryWorkflowPersistence()
        val provider = RejectingMessageProvider()
        val taskId = TaskDefinitionId("implementation")
        val project = Project(
            id = ProjectId("project"),
            name = "Project",
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 2L,
        )
        val definition = WorkflowDefinition(
            id = WorkflowDefinitionId("definition"),
            name = "Live messaging",
            tasks = listOf(
                TaskDefinition(
                    id = taskId,
                    name = "Implement",
                    objective = "Implement the requested change",
                    roleId = BuiltInRoles.ImplementationEngineer.id,
                    executor = TaskExecutor.RoleAgent(BuiltInRoles.ImplementationEngineer.id),
                    environmentPlanningPolicy = EnvironmentPlanningPolicy.NotRequired,
                ),
            ),
            testDesignPolicy = TestDesignPolicy.None,
        )
        val providerRunId = ProviderRunId("provider-run")
        val run = WorkflowRun(
            id = WorkflowRunId("run"),
            projectId = project.id,
            workflowDefinitionId = definition.id,
            objective = "Ship",
            status = WorkflowRunStatus.Running,
            taskRuns = mapOf(
                taskId to TaskRun(
                    id = TaskRunId("task-run"),
                    taskDefinitionId = taskId,
                    status = TaskRunStatus.Running,
                    assignedRoleId = BuiltInRoles.ImplementationEngineer.id,
                    assignedProviderId = provider.id,
                    providerRunId = providerRunId,
                    executor = TaskExecutor.RoleAgent(BuiltInRoles.ImplementationEngineer.id),
                ),
            ),
            createdAtEpochMillis = 3L,
            updatedAtEpochMillis = 4L,
        )
        persistence.projects.put(project)
        persistence.definitions.put(definition)
        persistence.runs.put(run)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val runtime = ApplicationRuntime.create(
                providers = listOf(provider),
                scope = scope,
                persistence = persistence,
            )
            assertIs<ApplicationRuntimeState.Live>(runtime.state.value)

            val result = runtime.messageTask(taskId, "  explain the failure  ")

            assertEquals(ProviderActionResult.Rejected("provider does not support chat"), result)
            assertEquals(providerRunId, provider.lastRunId)
            assertEquals("explain the failure", provider.lastMessage)
            val live = assertIs<ApplicationRuntimeState.Live>(runtime.state.value)
            assertEquals(WorkflowRunStatus.Running, live.presentation.run.status)
            assertEquals(TaskRunStatus.Running, live.presentation.run.taskRuns.getValue(taskId).status)
        } finally {
            scope.cancel()
        }
    }
}

private class RejectingMessageProvider : AgentProvider {
    override val id = AgentProviderId("messaging-provider")
    var lastRunId: ProviderRunId? = null
    var lastMessage: String? = null

    override suspend fun capabilities() = AgentCapabilities(
        supported = setOf(AgentCapability.RepositoryRead, AgentCapability.RepositoryWrite),
    )

    override suspend fun start(request: AgentTaskRequest): AgentRunHandle =
        error("Restored run must not start a duplicate provider session")

    override fun observe(runId: ProviderRunId): Flow<AgentEvent> = emptyFlow()

    override suspend fun sendMessage(
        runId: ProviderRunId,
        message: String,
    ): ProviderActionResult {
        lastRunId = runId
        lastMessage = message
        return ProviderActionResult.Rejected("provider does not support chat")
    }

    override suspend fun approvePlan(runId: ProviderRunId) = ProviderActionResult.Accepted

    override suspend fun cancel(runId: ProviderRunId) = ProviderActionResult.Accepted
}
