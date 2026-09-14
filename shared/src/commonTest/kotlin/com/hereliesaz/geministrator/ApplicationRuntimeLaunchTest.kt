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

class ApplicationRuntimeLaunchTest {
    @Test
    fun starterLaunchPersistsRepositoryObjectiveAndImplementationPlanGate() = runBlocking {
        val persistence = InMemoryWorkflowPersistence()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val repository = RepositoryRef(
            owner = " HereLiesAz ",
            name = " haive ",
            defaultBranch = " main ",
        )

        try {
            val runtime = ApplicationRuntime.create(
                providers = emptyList(),
                scope = scope,
                persistence = persistence,
            )

            runtime.launchStarterWorkflow(
                projectName = " The Haive ",
                objective = " Ship one complete workflow ",
                repository = repository,
            )

            val live = assertIs<ApplicationRuntimeState.Live>(runtime.state.value)
            val project = persistence.projects.get(live.presentation.run.projectId)
                ?: error("Project was not persisted")
            assertEquals("The Haive", project.name)
            assertEquals(RepositoryRef("HereLiesAz", "haive", "main"), project.repository)
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
        val repository = RepositoryRef("HereLiesAz", "haive", "main")
        val firstRuntime = ApplicationRuntime.create(
            providers = emptyList(),
            scope = firstScope,
            persistence = persistence,
        )

        val runId = try {
            firstRuntime.launchStarterWorkflow(
                projectName = "The Haive",
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
            locator = "/workspace/haive",
            defaultBranch = "main",
        )

        try {
            val runtime = ApplicationRuntime.create(
                providers = emptyList(),
                scope = scope,
                persistence = persistence,
            )

            runtime.launchStarterWorkflow(
                projectName = "Local Haive",
                objective = "Inspect the local checkout",
                repository = repository,
            )

            val live = assertIs<ApplicationRuntimeState.Live>(runtime.state.value)
            assertEquals(repository, live.presentation.project.repository)
            assertEquals(RepositorySource.Local, live.presentation.project.repository?.source)
            assertEquals("/workspace/haive", live.presentation.project.repository?.localPath)
        } finally {
            scope.cancel()
        }
    }
}
