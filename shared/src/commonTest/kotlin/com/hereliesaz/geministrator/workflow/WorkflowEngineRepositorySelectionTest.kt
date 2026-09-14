package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.RepositorySource
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TestDesignPolicy
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.ProviderActionResult
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkflowEngineRepositorySelectionTest {
    @Test
    fun linkedRepositoryIsIncludedInInitialProviderSelection() = runBlocking {
        val repository = RepositoryRef(
            owner = "team",
            name = "project",
            source = RepositorySource.GitLab,
            remoteUrl = "https://gitlab.com/team/project",
        )
        val gateway = CapturingSelectionGateway()
        val engine = WorkflowEngine(
            sessionGateway = gateway,
            roles = BuiltInRoles.all,
        )
        val taskId = TaskDefinitionId("implementation")
        val task = TaskDefinition(
            id = taskId,
            name = "Implement",
            objective = "Implement the change",
            roleId = BuiltInRoles.ImplementationEngineer.id,
            executor = TaskExecutor.RoleAgent(BuiltInRoles.ImplementationEngineer.id),
        )
        val definition = WorkflowDefinition(
            id = WorkflowDefinitionId("workflow"),
            name = "Workflow",
            tasks = listOf(task),
            testDesignPolicy = TestDesignPolicy.None,
        )
        val project = Project(
            id = ProjectId("project"),
            name = "Project",
            repository = repository,
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
        )
        val run = WorkflowRunFactory.create(
            definition = definition,
            workflowRunId = WorkflowRunId("run"),
            projectId = project.id,
            objective = "Ship",
            nowEpochMillis = 1L,
            taskRunIdFactory = { TaskRunId("task-${it.value}") },
        )

        engine.dispatchReadyTasks(
            project = project,
            definition = definition,
            run = run,
            nowEpochMillis = 2L,
        )

        assertEquals(repository, gateway.lastSelection?.repository)
        assertEquals(
            setOf(AgentCapability.RepositoryRead, AgentCapability.RepositoryWrite),
            gateway.lastSelection?.requiredCapabilities,
        )
    }
}

private class CapturingSelectionGateway : ManagedSessionGateway {
    var lastSelection: ProviderSelectionRequest? = null

    override suspend fun resolveProvider(selection: ProviderSelectionRequest): AgentProviderId {
        lastSelection = selection
        return AgentProviderId("capture")
    }

    override suspend fun createSession(request: ManagedSessionRequest): ManagedSessionHandle =
        ManagedSessionHandle(
            taskRunId = request.taskRequest.taskRunId,
            providerId = AgentProviderId("capture"),
            providerRunId = com.hereliesaz.geministrator.domain.ProviderRunId("provider-run"),
        )

    override suspend fun reconnect(handle: ManagedSessionHandle, initialStatus: ManagedSessionStatus) = Unit

    override suspend fun status(handle: ManagedSessionHandle): ManagedSessionStatus = ManagedSessionStatus.Running

    override suspend fun progress(handle: ManagedSessionHandle): ManagedSessionProgress? = null

    override suspend fun message(handle: ManagedSessionHandle, message: String): ProviderActionResult =
        ProviderActionResult.Accepted

    override suspend fun approvePlan(handle: ManagedSessionHandle): ProviderActionResult =
        ProviderActionResult.Accepted

    override suspend fun cancel(handle: ManagedSessionHandle): ProviderActionResult =
        ProviderActionResult.Accepted

    override suspend fun artifacts(handle: ManagedSessionHandle) = emptyList<com.hereliesaz.geministrator.providers.ProviderArtifact>()

    override suspend fun usageReport(handle: ManagedSessionHandle): ManagedSessionUsage? = null
}
