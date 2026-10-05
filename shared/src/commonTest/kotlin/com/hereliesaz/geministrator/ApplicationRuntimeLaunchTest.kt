package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.RepositorySource
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.parseRepositoryRef
import com.hereliesaz.geministrator.persistence.InMemoryWorkflowPersistence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import com.hereliesaz.geministrator.domain.WorkflowRunId
import kotlinx.coroutines.delay

class ApplicationRuntimeLaunchTest {
    @Test
    fun launchesRecordContinuationAndMergeParentsAndStartRootsWithout() = runBlocking<Unit> {
        val persistence = InMemoryWorkflowPersistence()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val runtime = ApplicationRuntime.create(providers = emptyList(), scope = scope, persistence = persistence)
            suspend fun launch(objective: String, parents: List<WorkflowRunId> = emptyList()): WorkflowRunId {
                delay(3) // run ids are millisecond-stamped
                runtime.launchStarterWorkflow("Lineage", objective, continuesWorkflowRunIds = parents)
                return assertIs<ApplicationRuntimeState.Live>(runtime.state.value).presentation.run.id
            }
            val a = launch("Root A")
            val b = launch("Root B")
            assertTrue(persistence.runs.get(a)!!.parentWorkflowRunIds.isEmpty(), "a plain launch is a root")
            val continuation = launch("Continue A", listOf(a, a))
            assertEquals(listOf(a), persistence.runs.get(continuation)!!.parentWorkflowRunIds)
            val merge = launch("Merge A and B", listOf(b, a))
            assertEquals(listOf(b, a), persistence.runs.get(merge)!!.parentWorkflowRunIds)
            assertFailsWith<IllegalArgumentException> { runtime.launchStarterWorkflow("Lineage", "Ghost", continuesWorkflowRunIds = listOf(WorkflowRunId("missing"))) }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun starterLaunchPersistsRepositoryObjectiveAndImplementationPlanGate() = runBlocking {
        val persistence = InMemoryWorkflowPersistence()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = RepositoryRef(
            owner = " HereLiesAz ",
            name = " aive ",
            defaultBranch = " main ",
        )

        try {
            val runtime = ApplicationRuntime.create(
                providers = emptyList(),
                scope = scope,
                persistence = persistence,
            )

            runtime.launchStarterWorkflow(
                projectName = " The Aive ",
                objective = " Ship one complete workflow ",
                repository = repository,
            )

            val live = assertIs<ApplicationRuntimeState.Live>(runtime.state.value)
            val project = persistence.projects.get(live.presentation.run.projectId)
                ?: error("Project was not persisted")
            assertEquals("The Aive", project.name)
            assertEquals(RepositoryRef("HereLiesAz", "aive", "main"), project.repository)
            assertEquals(project, live.presentation.project)
            assertEquals("Ship one complete workflow", live.presentation.run.objective)
            assertEquals(
                ApprovalPolicy.HumanApproval,
                live.presentation.definition.tasks
                    .single { it.id == TaskDefinitionId("implementation") }
                    .approvalPolicy,
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun persistedStarterRunAndRepositoryResumeAfterRuntimeRecreation() = runBlocking {
        val persistence = InMemoryWorkflowPersistence()
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = RepositoryRef("HereLiesAz", "aive", "main")
        val firstRuntime = ApplicationRuntime.create(
            providers = emptyList(),
            scope = firstScope,
            persistence = persistence,
        )

        val runId = try {
            firstRuntime.launchStarterWorkflow(
                projectName = "The Aive",
                objective = "Resume this run",
                repository = repository,
            )
            assertIs<ApplicationRuntimeState.Live>(firstRuntime.state.value).presentation.run.id
        } finally {
            firstRuntime.close()
            firstScope.cancel()
        }

        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val resumedRuntime = ApplicationRuntime.create(
                providers = emptyList(),
                scope = secondScope,
                persistence = persistence,
            )

            val resumed = assertIs<ApplicationRuntimeState.Live>(resumedRuntime.state.value)
            assertEquals(runId, resumed.presentation.run.id)
            assertEquals("Resume this run", resumed.presentation.run.objective)
            val project = persistence.projects.get(resumed.presentation.run.projectId)
                ?: error("Project was not persisted")
            assertEquals(repository, project.repository)
            assertEquals(project, resumed.presentation.project)
        } finally {
            secondScope.cancel()
        }
    }

    @Test
    fun localRepositoryLinkSurvivesLaunchAndLiveProjection() = runBlocking {
        val persistence = InMemoryWorkflowPersistence()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = parseRepositoryRef(
            source = RepositorySource.Local,
            locator = "/workspace/aive",
            defaultBranch = "main",
        )

        try {
            val runtime = ApplicationRuntime.create(
                providers = emptyList(),
                scope = scope,
                persistence = persistence,
            )

            runtime.launchStarterWorkflow(
                projectName = "Local Aive",
                objective = "Inspect the local checkout",
                repository = repository,
            )

            val live = assertIs<ApplicationRuntimeState.Live>(runtime.state.value)
            assertEquals(repository, live.presentation.project.repository)
            assertEquals(RepositorySource.Local, live.presentation.project.repository?.source)
            assertEquals("/workspace/aive", live.presentation.project.repository?.localPath)
        } finally {
            scope.cancel()
        }
    }
}
