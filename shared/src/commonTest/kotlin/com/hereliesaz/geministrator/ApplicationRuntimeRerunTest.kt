package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.TestDesignPolicy
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.events.TaskRerunRequested
import com.hereliesaz.geministrator.events.WorkflowCreated
import com.hereliesaz.geministrator.persistence.InMemoryWorkflowPersistence
import com.hereliesaz.geministrator.workflow.TaskRerunRequest
import com.hereliesaz.geministrator.workflow.WorkflowRunFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ApplicationRuntimeRerunTest {
    @Test
    fun rerunFromATaskStartsANewRunInTheLineageAndJournalsIt() = runBlocking<Unit> {
        val persistence = InMemoryWorkflowPersistence()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val first = TaskDefinitionId("first")
            val second = TaskDefinitionId("second")
            fun task(id: TaskDefinitionId, vararg dependsOn: TaskDefinitionId) = TaskDefinition(
                id = id,
                name = id.value,
                objective = "Do ${id.value}",
                roleId = BuiltInRoles.Architect.id,
                dependsOn = dependsOn.toSet(),
            )
            val definition = WorkflowDefinition(
                id = WorkflowDefinitionId("rerun-wf"),
                name = "Rerun",
                tasks = listOf(task(first), task(second, first)),
                testDesignPolicy = TestDesignPolicy.None,
            )
            val project = Project(ProjectId("rerun-project"), "Rerun", createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L)
            val created = WorkflowRunFactory.create(
                definition = definition,
                workflowRunId = WorkflowRunId("source-run"),
                projectId = project.id,
                objective = "Ship it",
                nowEpochMillis = 1L,
                taskRunIdFactory = { TaskRunId("source-${it.value}") },
                roles = BuiltInRoles.all,
            )
            val source = created.copy(
                status = WorkflowRunStatus.Completed,
                taskRuns = created.taskRuns.mapValues { (id, taskRun) ->
                    taskRun.copy(
                        status = TaskRunStatus.Completed,
                        artifacts = listOf(
                            ArtifactRef(
                                id = ArtifactId("${taskRun.id.value}:out"),
                                kind = ArtifactKind.Specification,
                                taskRunId = taskRun.id,
                                label = "Output",
                                textContent = "${id.value} output",
                                createdAtEpochMillis = 2L,
                            ),
                        ),
                    )
                },
            )
            persistence.projects.put(project)
            persistence.definitions.put(definition)
            persistence.runs.put(source)

            val runtime = ApplicationRuntime.create(providers = emptyList(), scope = scope, persistence = persistence)
            runtime.rerunFromTask(source.id, TaskRerunRequest(second, editedObjective = "Do second again"))

            val live = assertIs<ApplicationRuntimeState.Live>(runtime.state.value).presentation
            assertNotEquals(source.id, live.run.id)
            assertEquals(listOf(source.id), live.run.parentWorkflowRunIds)
            assertEquals("Do second again", live.definition.tasks.single { it.id == second }.objective)
            assertEquals(TaskRunStatus.Completed, live.run.taskRuns.getValue(first).status)
            assertTrue(live.run.taskRuns.getValue(second).artifacts.isEmpty())
            assertEquals(source, persistence.runs.get(source.id), "the source run is left as it was")
            assertEquals(definition, persistence.definitions.get(definition.id))
            val events = persistence.events.forRun(live.run.id)
            assertIs<WorkflowCreated>(events[0])
            val rerun = assertIs<TaskRerunRequested>(events[1])
            assertEquals(second, rerun.taskDefinitionId)
            assertEquals(source.id, rerun.fromWorkflowRunId)
            assertEquals(listOf(second), rerun.rerunTaskIds)
        } finally {
            scope.cancel()
        }
    }
}
