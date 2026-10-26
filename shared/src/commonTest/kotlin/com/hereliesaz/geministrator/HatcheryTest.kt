package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HatcheryTest {
    private val engineer = BuiltInRoles.ImplementationEngineer
    private val qa = BuiltInRoles.QaEngineer.copy(preferredProviderId = AgentProviderId("b"))

    private fun run(status: WorkflowRunStatus, vararg tasks: Pair<String, RoleDefinition>) = WorkflowRun(
        id = WorkflowRunId("r1"),
        projectId = ProjectId("p"),
        workflowDefinitionId = WorkflowDefinitionId("w"),
        objective = "o",
        status = status,
        taskRuns = tasks.associate { (t, role) ->
            TaskDefinitionId(t) to TaskRun(TaskRunId("r-$t"), TaskDefinitionId(t), TaskRunStatus.Completed, assignedRoleId = role.id)
        },
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 1,
    )

    @Test
    fun aClearedRunRecombinesItsTwoBusiestContributors() {
        val child = assertNotNull(hatchFromRun(run(WorkflowRunStatus.Completed, "a" to engineer, "b" to engineer, "c" to qa), listOf(engineer, qa)))
        assertEquals(listOf(engineer.id, qa.id), child.parents)
        assertEquals("hatch-r1", child.id.value)
        assertEquals(AgentProviderId("b"), child.preferredProviderId)
        assertEquals(engineer.capabilitiesRequired + qa.capabilitiesRequired, child.capabilitiesRequired)
        assertFalse(child.enabled)
    }

    @Test
    fun oneContributorHatchesAClone() {
        val child = assertNotNull(hatchFromRun(run(WorkflowRunStatus.Completed, "a" to engineer), listOf(engineer)))
        assertEquals(listOf(engineer.id), child.parents)
        assertEquals(engineer.instructions, child.instructions)
    }

    @Test
    fun aRunThatHasNotClearedHatchesNothing() {
        assertNull(hatchFromRun(run(WorkflowRunStatus.Failed, "a" to engineer), listOf(engineer)))
    }
}
