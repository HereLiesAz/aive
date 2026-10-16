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
import com.hereliesaz.geministrator.workflow.ManagedSessionFailure
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertIs

class ApplicationRuntimeTest {
    @Test
    fun emptyPersistencePublishesNoProjectInsteadOfDemoWorkflow() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val runtime = ApplicationRuntime.create(
                providers = emptyList(),
                scope = scope,
                persistence = InMemoryWorkflowPersistence(),
            )

            assertTrue(runtime.state.value is ApplicationRuntimeState.NoProject)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun completedPersistedRunPublishesLivePresentation() = runBlocking {
        val persistence = InMemoryWorkflowPersistence()
        val project = project("project", updatedAt = 2L)
        val taskId = TaskDefinitionId("approval")
        val definition = WorkflowDefinition(
            id = WorkflowDefinitionId("definition"),
            name = "Release",
            tasks = listOf(
                TaskDefinition(
                    id = taskId,
                    name = "Approve",
                    objective = "Approve release",
                    roleId = null,
                    executor = TaskExecutor.HumanApproval(),
                ),
            ),
        )
        val run = WorkflowRun(
            id = WorkflowRunId("run"),
            projectId = project.id,
            workflowDefinitionId = definition.id,
            objective = "Ship",
            status = WorkflowRunStatus.Completed,
            taskRuns = mapOf(
                taskId to TaskRun(
                    id = TaskRunId("task-run"),
                    taskDefinitionId = taskId,
                    status = TaskRunStatus.Completed,
                    assignedRoleId = null,
                    executor = TaskExecutor.HumanApproval(),
                    progress = 1f,
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
                providers = emptyList(),
                scope = scope,
                persistence = persistence,
            )

            val live = assertIs<ApplicationRuntimeState.Live>(runtime.state.value)
            assertEquals(run.id, live.presentation.run.id)
            assertEquals(definition.id, live.presentation.definition.id)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun loadLatestSelectsNewestRunAcrossProjects() = runBlocking {
        val persistence = InMemoryWorkflowPersistence()
        val olderProject = project("older-project", updatedAt = 100L)
        val newerProject = project("newer-project", updatedAt = 10L)
        val olderDefinition = completedDefinition("older-definition")
        val newerDefinition = completedDefinition("newer-definition")
        val olderRun = completedRun(olderProject, olderDefinition, "older-run", updatedAt = 20L)
        val newerRun = completedRun(newerProject, newerDefinition, "newer-run", updatedAt = 200L)

        persistence.projects.put(olderProject)
        persistence.projects.put(newerProject)
        persistence.definitions.put(olderDefinition)
        persistence.definitions.put(newerDefinition)
        persistence.runs.put(olderRun)
        persistence.runs.put(newerRun)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val runtime = ApplicationRuntime.create(
                providers = emptyList(),
                scope = scope,
                persistence = persistence,
            )

            val live = assertIs<ApplicationRuntimeState.Live>(runtime.state.value)
            assertEquals(newerRun.id, live.presentation.run.id)
            assertEquals(newerDefinition.id, live.presentation.definition.id)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun nonterminalProviderWorkflowCyclesPersistsAndPublishesCompletion() = runBlocking {
        val persistence = InMemoryWorkflowPersistence()
        val project = project("project", updatedAt = 1L)
        val taskId = TaskDefinitionId("implement")
        val definition = providerDefinition(taskId)
        val run = providerRun(project, definition, taskId)
        persistence.projects.put(project)
        persistence.definitions.put(definition)
        persistence.runs.put(run)
        val provider = CompletingProvider()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val runtime = ApplicationRuntime.create(
                providers = listOf(provider),
                scope = scope,
                persistence = persistence,
            )

            withTimeout(5_000L) {
                while ((runtime.state.value as? ApplicationRuntimeState.Live)?.presentation?.run?.status != WorkflowRunStatus.Completed) {
                    delay(50L)
                }
            }

            val live = assertIs<ApplicationRuntimeState.Live>(runtime.state.value)
            assertEquals(WorkflowRunStatus.Completed, live.presentation.run.status)
            assertEquals(TaskRunStatus.Completed, live.presentation.run.taskRuns.getValue(taskId).status)
            assertEquals(1, provider.startCount)
            assertEquals(WorkflowRunStatus.Completed, persistence.runs.get(run.id)?.status)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun refreshWaitsForInFlightDispatchAndDoesNotDuplicateProviderRun() = runBlocking {
        val persistence = InMemoryWorkflowPersistence()
        val project = project("project", updatedAt = 1L)
        val taskId = TaskDefinitionId("implement")
        val definition = providerDefinition(taskId)
        val run = providerRun(project, definition, taskId)
        persistence.projects.put(project)
        persistence.definitions.put(definition)
        persistence.runs.put(run)
        val provider = BlockingStartProvider()

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val runtime = ApplicationRuntime.create(
                providers = listOf(provider),
                scope = scope,
                persistence = persistence,
            )

            withTimeout(4_000L) { provider.startEntered.await() }
            val refreshJob = launch { runtime.refresh() }
            delay(100L)
            assertFalse(refreshJob.isCompleted)

            provider.releaseStart.complete(Unit)
            withTimeout(4_000L) { refreshJob.join() }

            assertEquals(1, provider.startCount)
            val persistedTask = persistence.runs.get(run.id)?.taskRuns?.get(taskId)
            assertEquals(ProviderRunId("blocking-run-1"), persistedTask?.providerRunId)
        } finally {
            scope.cancel()
        }
    }


    @Test
    fun persistedNodeCreatureRigBackEdgeIsRemovedWithoutChangingItsRun() = runBlocking {
        val persistence = InMemoryWorkflowPersistence()
        val project = project("rig-project", updatedAt = 1L)
        val preflight = TaskDefinitionId("preflight")
        val review = TaskDefinitionId("batch-review")
        val rig = TaskDefinitionId("rig-01")
        val audit = TaskDefinitionId("qc-audit")
        val tasks = listOf(
            TaskDefinition(preflight, "Preflight", "Validate source-backed batch", roleId = null, executor = TaskExecutor.HumanApproval()),
            TaskDefinition(review, "Batch review", "Review batch provenance", roleId = null, executor = TaskExecutor.HumanApproval(),
                dependsOn = setOf(preflight, audit)),
            TaskDefinition(rig, "Rig 1", "Generate and inspect rig sheet 1", roleId = null, executor = TaskExecutor.HumanApproval(),
                dependsOn = setOf(preflight, review)),
            TaskDefinition(audit, "QC", "Audit completed rig batch", roleId = null, executor = TaskExecutor.HumanApproval(),
                dependsOn = setOf(preflight, rig)),
        )
        val broken = WorkflowDefinition(
            id = WorkflowDefinitionId("node-creature-rig-pipeline"),
            name = "Node Creature Rig Pipeline",
            tasks = tasks,
            testDesignPolicy = TestDesignPolicy.None,
        )
        val run = WorkflowRun(
            id = WorkflowRunId("rig-run"),
            projectId = project.id,
            workflowDefinitionId = broken.id,
            objective = "Generate source-backed rigs",
            status = WorkflowRunStatus.Completed,
            taskRuns = tasks.associate { task ->
                task.id to TaskRun(
                    id = TaskRunId("run-${task.id.value}"),
                    taskDefinitionId = task.id,
                    status = TaskRunStatus.Completed,
                    assignedRoleId = null,
                    executor = TaskExecutor.HumanApproval(),
                    progress = 1f,
                )
            },
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 2L,
        )
        persistence.projects.put(project)
        persistence.definitions.put(broken)
        persistence.runs.put(run)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val runtime = ApplicationRuntime.create(
                providers = emptyList(),
                scope = scope,
                persistence = persistence,
            )
            val repaired = assertIs<ApplicationRuntimeState.Live>(runtime.state.value).presentation.definition
            assertEquals(setOf(preflight), repaired.tasks.single { it.id == review }.dependsOn)
            assertEquals(setOf(preflight, review), repaired.tasks.single { it.id == rig }.dependsOn)
            assertEquals(setOf(preflight, rig), repaired.tasks.single { it.id == audit }.dependsOn)
            assertTrue(com.hereliesaz.geministrator.workflow.WorkflowGraphValidator.validate(repaired).isEmpty())
            assertEquals(repaired, persistence.definitions.get(broken.id))
            assertEquals(run, persistence.runs.get(run.id))
            assertEquals(repaired, repairKnownNodeCreatureRigCycle(repaired))
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun knownBackEdgeRepairPreservesUnrelatedWorkflowsAndOtherCycles() {
        val preflight = TaskDefinitionId("preflight")
        val review = TaskDefinitionId("batch-review")
        val rig = TaskDefinitionId("rig-01")
        val audit = TaskDefinitionId("qc-audit")
        val tasks = listOf(
            TaskDefinition(preflight, "Preflight", "Validate", roleId = null, executor = TaskExecutor.HumanApproval()),
            TaskDefinition(review, "Review", "Review", roleId = null, executor = TaskExecutor.HumanApproval(),
                dependsOn = setOf(preflight, audit)),
            TaskDefinition(rig, "Rig", "Rig", roleId = null, executor = TaskExecutor.HumanApproval(),
                dependsOn = setOf(review)),
            TaskDefinition(audit, "Audit", "Audit", roleId = null, executor = TaskExecutor.HumanApproval(),
                dependsOn = setOf(rig)),
        )
        val userWorkflow = WorkflowDefinition(
            id = WorkflowDefinitionId("user-authored"),
            name = "User workflow",
            tasks = tasks,
        )
        assertEquals(userWorkflow, repairKnownNodeCreatureRigCycle(userWorkflow))

        val differentCycle = userWorkflow.copy(
            id = WorkflowDefinitionId("node-creature-rig-pipeline"),
            tasks = tasks.map { task ->
                if (task.id == rig) task.copy(dependsOn = setOf(review, audit)) else task
            },
        )
        assertEquals(differentCycle, repairKnownNodeCreatureRigCycle(differentCycle))
    }

    @Test
    fun providerUnavailableClassifiesAsDisconnected() {
        val failure = ApplicationRuntime.classifyRuntimeFailure(
            ManagedSessionFailure.ProviderUnavailable("offline"),
        )

        assertIs<ApplicationRuntimeFailure.Disconnected>(failure)
        assertEquals("offline", failure.message)
    }

    @Test
    fun providerOperationFailureClassifiesAsResumeFailure() {
        val failure = ApplicationRuntime.classifyRuntimeFailure(
            ManagedSessionFailure.ProviderOperationFailed("provider rejected operation"),
        )

        assertIs<ApplicationRuntimeFailure.Resume>(failure)
        assertEquals("provider rejected operation", failure.message)
    }

    private fun project(id: String, updatedAt: Long) = Project(
        id = ProjectId(id),
        name = id,
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = updatedAt,
    )

    private fun providerDefinition(taskId: TaskDefinitionId) = WorkflowDefinition(
        id = WorkflowDefinitionId("definition"),
        name = "Live provider workflow",
        tasks = listOf(
            TaskDefinition(
                id = taskId,
                name = "Implement",
                objective = "Implement the change",
                roleId = BuiltInRoles.ImplementationEngineer.id,
                executor = TaskExecutor.RoleAgent(BuiltInRoles.ImplementationEngineer.id),
                environmentPlanningPolicy = EnvironmentPlanningPolicy.NotRequired,
            ),
        ),
        testDesignPolicy = TestDesignPolicy.None,
    )

    private fun providerRun(
        project: Project,
        definition: WorkflowDefinition,
        taskId: TaskDefinitionId,
    ) = WorkflowRun(
        id = WorkflowRunId("run"),
        projectId = project.id,
        workflowDefinitionId = definition.id,
        objective = "Ship",
        status = WorkflowRunStatus.Created,
        taskRuns = mapOf(
            taskId to TaskRun(
                id = TaskRunId("task-run"),
                taskDefinitionId = taskId,
                status = TaskRunStatus.Ready,
                assignedRoleId = BuiltInRoles.ImplementationEngineer.id,
                executor = TaskExecutor.RoleAgent(BuiltInRoles.ImplementationEngineer.id),
            ),
        ),
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )

    private fun completedDefinition(id: String): WorkflowDefinition {
        val taskId = TaskDefinitionId("$id-task")
        return WorkflowDefinition(
            id = WorkflowDefinitionId(id),
            name = id,
            tasks = listOf(
                TaskDefinition(
                    id = taskId,
                    name = "Done",
                    objective = "Done",
                    roleId = null,
                    executor = TaskExecutor.HumanApproval(),
                ),
            ),
            testDesignPolicy = TestDesignPolicy.None,
        )
    }

    private fun completedRun(
        project: Project,
        definition: WorkflowDefinition,
        id: String,
        updatedAt: Long,
    ): WorkflowRun {
        val taskId = definition.tasks.single().id
        return WorkflowRun(
            id = WorkflowRunId(id),
            projectId = project.id,
            workflowDefinitionId = definition.id,
            objective = "Done",
            status = WorkflowRunStatus.Completed,
            taskRuns = mapOf(
                taskId to TaskRun(
                    id = TaskRunId("$id-task-run"),
                    taskDefinitionId = taskId,
                    status = TaskRunStatus.Completed,
                    assignedRoleId = null,
                    executor = TaskExecutor.HumanApproval(),
                    progress = 1f,
                ),
            ),
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = updatedAt,
        )
    }
}

private class CompletingProvider : AgentProvider {
    override val id = AgentProviderId("completing")
    var startCount: Int = 0

    override suspend fun capabilities() = AgentCapabilities(
        supported = setOf(AgentCapability.RepositoryRead, AgentCapability.RepositoryWrite),
    )

    override suspend fun start(request: AgentTaskRequest): AgentRunHandle {
        startCount += 1
        return AgentRunHandle(ProviderRunId("provider-run-$startCount"))
    }

    override fun observe(runId: ProviderRunId): Flow<AgentEvent> = flowOf(AgentEvent.Completed(runId))

    override suspend fun sendMessage(runId: ProviderRunId, message: String) = ProviderActionResult.Accepted

    override suspend fun approvePlan(runId: ProviderRunId) = ProviderActionResult.Accepted

    override suspend fun cancel(runId: ProviderRunId) = ProviderActionResult.Accepted
}

private class BlockingStartProvider : AgentProvider {
    override val id = AgentProviderId("blocking")
    val startEntered = CompletableDeferred<Unit>()
    val releaseStart = CompletableDeferred<Unit>()
    var startCount: Int = 0

    override suspend fun capabilities() = AgentCapabilities(
        supported = setOf(AgentCapability.RepositoryRead, AgentCapability.RepositoryWrite),
    )

    override suspend fun start(request: AgentTaskRequest): AgentRunHandle {
        startCount += 1
        startEntered.complete(Unit)
        releaseStart.await()
        return AgentRunHandle(ProviderRunId("blocking-run-$startCount"))
    }

    override fun observe(runId: ProviderRunId): Flow<AgentEvent> = flowOf(AgentEvent.Completed(runId))

    override suspend fun sendMessage(runId: ProviderRunId, message: String) = ProviderActionResult.Accepted

    override suspend fun approvePlan(runId: ProviderRunId) = ProviderActionResult.Accepted

    override suspend fun cancel(runId: ProviderRunId) = ProviderActionResult.Accepted
}
