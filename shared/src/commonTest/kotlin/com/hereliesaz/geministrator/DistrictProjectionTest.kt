package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.RepositorySource
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DistrictProjectionTest {
    private fun wf(id: String, vararg tasks: String) = WorkflowDefinition(
        id = WorkflowDefinitionId(id),
        name = id,
        tasks = tasks.map {
            TaskDefinition(
                id = TaskDefinitionId(it),
                name = it,
                objective = it,
                roleId = BuiltInRoles.ImplementationEngineer.id,
                executor = TaskExecutor.RoleAgent(BuiltInRoles.ImplementationEngineer.id),
            )
        },
    )

    private fun run(id: String, wf: String, status: WorkflowRunStatus, at: Long, vararg tasks: Pair<String, TaskRunStatus>) = WorkflowRun(
        id = WorkflowRunId(id),
        projectId = ProjectId("p"),
        workflowDefinitionId = WorkflowDefinitionId(wf),
        objective = "o",
        status = status,
        taskRuns = tasks.associate { (t, s) ->
            TaskDefinitionId(t) to TaskRun(TaskRunId("$id-$t"), TaskDefinitionId(t), s, assignedRoleId = null)
        },
        createdAtEpochMillis = at,
        updatedAtEpochMillis = at,
    )

    @Test
    fun eachWorkflowIsANeighbourhoodShowingItsLatestRun() {
        val district = projectDistrict(
            listOf(wf("ship", "a", "b"), wf("quiet", "x")),
            listOf(
                run("old", "ship", WorkflowRunStatus.Failed, 1),
                run("new", "ship", WorkflowRunStatus.Running, 2, "a" to TaskRunStatus.Completed, "b" to TaskRunStatus.AwaitingApproval),
            ),
        )
        val ship = district.first()
        assertEquals("new", ship.latestRunId)
        assertEquals(NeighbourhoodState.Gestating, ship.state)
        assertEquals(0.5f, ship.growth)
        assertEquals(1, ship.needsYou)
        assertEquals(1, ship.scars)
        val quiet = district.last()
        assertNull(quiet.latestRunId)
        assertEquals(NeighbourhoodState.Idle, quiet.state)
    }

    @Test
    fun scarsAreThisWorkflowsFinishedRunsNewestFirst() {
        val current = run("now", "ship", WorkflowRunStatus.Running, 9)
        val scars = runScars(
            current,
            listOf(
                current,
                run("a", "ship", WorkflowRunStatus.Completed, 1),
                run("b", "ship", WorkflowRunStatus.Failed, 5),
                run("c", "other", WorkflowRunStatus.Completed, 7),
                run("d", "ship", WorkflowRunStatus.Running, 3),
            ),
        )
        assertEquals(listOf("b", "a"), scars.map { it.id.value })
    }
    @Test
    fun directoryUsesHistoricalRepositoriesAndScopedLatestRuns() {
        val project = Project(ProjectId("p"), "Project A", RepositoryRef("Team", "current"), createdAtEpochMillis = 1, updatedAtEpochMillis = 9)
        val other = Project(ProjectId("other"), "Project B", RepositoryRef("team", "OLD"), createdAtEpochMillis = 1, updatedAtEpochMillis = 9)
        val lab = Project(ProjectId("lab"), "Lab", RepositoryRef("Team", "old", source = RepositorySource.GitLab), createdAtEpochMillis = 1, updatedAtEpochMillis = 9)
        val solo = Project(ProjectId("solo"), "Solo", createdAtEpochMillis = 1, updatedAtEpochMillis = 9)
        val groups = projectHiveDirectory(
            listOf(wf("ship"), wf("second"), wf("never")),
            listOf(
                project to listOf(run("old", "ship", WorkflowRunStatus.Completed, 2).copy(repositorySnapshot = RepositoryRef("Team", "old")),
                    run("new", "ship", WorkflowRunStatus.Running, 8), run("deleted", "gone", WorkflowRunStatus.Completed, 9)),
                other to listOf(run("second", "second", WorkflowRunStatus.Completed, 4).copy(projectId = other.id)),
                lab to listOf(run("lab", "ship", WorkflowRunStatus.Failed, 5).copy(projectId = lab.id)),
                solo to listOf(run("solo", "ship", WorkflowRunStatus.Completed, 3).copy(projectId = solo.id)),
            ),
        )
        assertEquals(4, groups.size)
        val old = groups.single { it.location.key == "GitHub:team/old" }
        assertEquals(listOf("Project A", "Project B"), old.projectNames)
        assertEquals(2, old.runCount)
        assertEquals(listOf("old", "second"), old.hives.map { it.latestRunId })
        assertEquals("new", groups.single { it.location.key == "GitHub:team/current" }.hives.single().latestRunId)
        assertEquals("lab", groups.single { it.location.key == "GitLab:Team/old" }.hives.single().latestRunId)
        assertEquals("solo", groups.single { it.location.key == "project:solo" }.hives.single().latestRunId)
        assertNull(groups.single { it.location.key == "project:solo" }.location.owner)
    }

}
