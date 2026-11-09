package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.persistence.InMemoryWorkflowPersistence
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/** Two launches in one millisecond, or after the clock stepped back, never share an id. */
class LaunchStampTest {
    private fun run(stamp: String) = WorkflowRun(
        id = WorkflowRunId("run-$stamp"),
        projectId = ProjectId("project"),
        workflowDefinitionId = WorkflowDefinitionId("workflow-$stamp"),
        objective = "Ship",
        status = WorkflowRunStatus.Completed,
        taskRuns = emptyMap(),
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )

    @Test
    fun aMillisecondThatIsTakenGetsACounterInsteadOfOverwritingTheEarlierLaunch() = runBlocking<Unit> {
        val persistence = InMemoryWorkflowPersistence()
        assertEquals("5000", persistence.uniqueLaunchStamp(5_000L))

        persistence.runs.put(run("5000"))
        assertEquals("5000-2", persistence.uniqueLaunchStamp(5_000L), "the same millisecond, or a clock stepped back to it")

        persistence.runs.put(run("5000-2"))
        assertEquals("5000-3", persistence.uniqueLaunchStamp(5_000L))
        assertEquals("5001", persistence.uniqueLaunchStamp(5_001L))
    }

    @Test
    fun aTakenHiveOrNewProjectIdAlsoMovesTheStamp() = runBlocking<Unit> {
        val persistence = InMemoryWorkflowPersistence()
        persistence.definitions.put(
            WorkflowDefinition(
                id = WorkflowDefinitionId("workflow-7000"),
                name = "Earlier",
                tasks = listOf(
                    TaskDefinition(
                        id = com.hereliesaz.geministrator.domain.TaskDefinitionId("a"),
                        name = "a",
                        objective = "a",
                        roleId = null,
                    ),
                ),
            ),
        )
        persistence.projects.put(Project(ProjectId("project-7001"), "Earlier", createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L))

        assertEquals("7000-2", persistence.uniqueLaunchStamp(7_000L))
        assertEquals("7001", persistence.uniqueLaunchStamp(7_001L, newProject = false), "a project that exists is reused, not re-made")
        assertEquals("7001-2", persistence.uniqueLaunchStamp(7_001L, newProject = true))
    }
}
