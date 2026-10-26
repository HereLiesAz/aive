package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.BuiltInRoles
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
}
