package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.TaskCondition
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RehearsalTest {
    private fun task(id: String, vararg deps: String) = TaskDefinition(
        id = TaskDefinitionId(id),
        name = id,
        objective = id,
        roleId = BuiltInRoles.ImplementationEngineer.id,
        dependsOn = deps.map(::TaskDefinitionId).toSet(),
        executor = TaskExecutor.RoleAgent(BuiltInRoles.ImplementationEngineer.id),
    )

    @Test
    fun tasksRunInDependencyOrderAndTheRehearsalClears() {
        val definition = WorkflowDefinition(
            id = WorkflowDefinitionId("r"),
            name = "r",
            tasks = listOf(
                task("a"),
                task("b", "a"),
                task("fix", "b").copy(condition = TaskCondition.OnFailure(TaskDefinitionId("b"))),
            ),
        )
        val frames = rehearsalFrames(definition, restingWorkflowRun(definition))
        fun at(i: Int, id: String) = frames[i].taskRuns[TaskDefinitionId(id)]?.status
        assertEquals(TaskRunStatus.Running, at(0, "a"))
        assertEquals(TaskRunStatus.Created, at(0, "b"))
        assertEquals(TaskRunStatus.Running, at(1, "b"))
        assertTrue(frames.any { it.taskRuns[TaskDefinitionId("fix")]?.status == TaskRunStatus.Cancelled })
        assertEquals(WorkflowRunStatus.Completed, frames.last().status)
    }
}
