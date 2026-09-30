package com.hereliesaz.geministrator.distributed

import com.hereliesaz.geministrator.domain.DistributedComputeRequirements
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.RepositoryRef
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
import com.hereliesaz.geministrator.workflow.TaskExecutorExecution
import com.hereliesaz.geministrator.workflow.TaskExecutorIntegration
import com.hereliesaz.geministrator.workflow.TaskExecutorIntegrationRegistry
import com.hereliesaz.geministrator.workflow.WorkflowGraphValidator
import com.hereliesaz.geministrator.workflow.WorkflowValidationError
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DistributedLeaseValidationTest {
    private val repository = RepositoryRef(owner = "az", name = "app")
    private val approvalId = TaskDefinitionId("approve")
    private val pushId = TaskDefinitionId("push")
    private val push = TaskExecutor.RepositoryOperation("push")

    private fun definition(withApproval: Boolean = true) = WorkflowDefinition(
        id = WorkflowDefinitionId("wf"),
        name = "Workflow",
        tasks = listOfNotNull(
            TaskDefinition(
                id = approvalId,
                name = "Approve",
                objective = "Approve push",
                roleId = null,
                executor = TaskExecutor.HumanApproval(),
            ).takeIf { withApproval },
            TaskDefinition(
                id = pushId,
                name = "Push",
                objective = "Push the branch",
                roleId = null,
                dependsOn = if (withApproval) setOf(approvalId) else emptySet(),
                executor = TaskExecutor.Distributed(push, DistributedComputeRequirements()),
            ),
        ),
    )

    private fun taskRun(id: TaskDefinitionId, status: TaskRunStatus) =
        TaskRun(id = TaskRunId(id.value), taskDefinitionId = id, status = status, assignedRoleId = null)

    private fun envelope(
        definition: WorkflowDefinition = definition(),
        approvalStatus: TaskRunStatus = TaskRunStatus.Completed,
        projectRepository: RepositoryRef = repository,
    ): DistributedTaskEnvelope {
        val task = definition.tasks.first { it.id == pushId }
        val runs = buildMap {
            if (definition.tasks.any { it.id == approvalId }) put(approvalId, taskRun(approvalId, approvalStatus))
            put(pushId, taskRun(pushId, TaskRunStatus.Running))
        }
        return DistributedTaskEnvelope(
            leaseId = "lease-1",
            originNodeId = "origin",
            project = Project(ProjectId("p"), "Project", projectRepository, createdAtEpochMillis = 0, updatedAtEpochMillis = 0),
            definition = definition,
            run = WorkflowRun(
                id = WorkflowRunId("run"),
                projectId = ProjectId("p"),
                workflowDefinitionId = definition.id,
                objective = "Ship",
                status = WorkflowRunStatus.Running,
                taskRuns = runs,
                createdAtEpochMillis = 0,
                updatedAtEpochMillis = 0,
            ),
            task = task,
            taskRun = runs.getValue(pushId),
            delegatedExecutor = push,
            requirements = DistributedComputeRequirements(),
            submittedAtEpochMillis = 0,
        )
    }

    private class RecordingIntegration : TaskExecutorIntegration {
        var dispatched = 0
        override fun supports(executor: TaskExecutor) = executor is TaskExecutor.RepositoryOperation
        override suspend fun dispatch(context: TaskExecutorContext): TaskExecutorExecution {
            dispatched += 1
            return TaskExecutorExecution(status = TaskRunStatus.Completed)
        }
        override suspend fun reconcile(context: TaskExecutorContext) = dispatch(context)
    }

    private fun runner(integration: RecordingIntegration, trusted: List<RepositoryRef> = listOf(repository)) =
        SystemExecutorDistributedWorkloadRunner(
            integrations = TaskExecutorIntegrationRegistry(listOf(integration)),
            nowEpochMillis = { 0 },
            pollIntervalMillis = 1,
            trustedRepositories = { trusted },
        )

    @Test
    fun validatorRequiresApprovalForDistributedRepositoryMutation() {
        val errors = WorkflowGraphValidator.validate(definition(withApproval = false))
        assertTrue(errors.any { it is WorkflowValidationError.MissingRepositoryMutationApproval })
        assertTrue(WorkflowGraphValidator.validate(definition()).isEmpty())
    }

    @Test
    fun approvedLeaseForLinkedRepositoryRuns() = runBlocking<Unit> {
        val integration = RecordingIntegration()
        val result = runner(integration).run(envelope()) {}

        assertEquals(TaskRunStatus.Completed, result.status)
        assertEquals(1, integration.dispatched)
    }

    @Test
    fun leaseWithPendingApprovalIsRefused() = runBlocking<Unit> {
        val integration = RecordingIntegration()
        val result = runner(integration).run(envelope(approvalStatus = TaskRunStatus.AwaitingApproval)) {}

        assertEquals(TaskRunStatus.Failed, result.status)
        assertContains(result.failureMessage.orEmpty(), "no completed human approval")
        assertEquals(0, integration.dispatched)
    }

    @Test
    fun leaseWithoutApprovalGateIsRefused() = runBlocking<Unit> {
        val integration = RecordingIntegration()
        val result = runner(integration).run(envelope(definition = definition(withApproval = false))) {}

        assertEquals(TaskRunStatus.Failed, result.status)
        assertEquals(0, integration.dispatched)
    }

    @Test
    fun leaseForRepositoryNotLinkedOnThisDeviceIsRefused() = runBlocking<Unit> {
        val integration = RecordingIntegration()
        val result = runner(integration).run(
            envelope(projectRepository = RepositoryRef(owner = "someone-else", name = "app")),
        ) {}

        assertEquals(TaskRunStatus.Failed, result.status)
        assertContains(result.failureMessage.orEmpty(), "not linked to a project on this device")
        assertEquals(0, integration.dispatched)
    }
}
