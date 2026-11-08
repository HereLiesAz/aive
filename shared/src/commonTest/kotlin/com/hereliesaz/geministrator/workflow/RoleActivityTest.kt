package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.TaskUsage
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoleActivityTest {
    private val engineer = RoleDefinitionId("engineer")
    private val qa = RoleDefinitionId("qa")

    private fun task(id: String, name: String, role: RoleDefinitionId) = TaskDefinition(
        id = TaskDefinitionId(id), name = name, objective = name, roleId = role, executor = TaskExecutor.RoleAgent(role),
    )

    private val ship = WorkflowDefinition(id = WorkflowDefinitionId("ship"), name = "Ship", tasks = listOf(task("build", "Build", engineer), task("test", "Test", qa)))
    private val docs = WorkflowDefinition(id = WorkflowDefinitionId("docs"), name = "Docs", tasks = listOf(task("write", "Write", engineer)))

    private fun run(
        id: String, definition: WorkflowDefinition, at: Long,
        vararg steps: Triple<String, TaskRunStatus, TaskUsage?>,
        assigned: RoleDefinitionId? = null,
    ) = WorkflowRun(
        id = WorkflowRunId(id), projectId = ProjectId("p"), workflowDefinitionId = definition.id, objective = "o",
        status = WorkflowRunStatus.Completed, createdAtEpochMillis = 1, updatedAtEpochMillis = at,
        taskRuns = steps.associate { (task, status, usage) ->
            val def = definition.tasks.first { it.id.value == task }
            def.id to TaskRun(TaskRunId("$id-$task"), def.id, status, assignedRoleId = assigned ?: def.roleId, usage = usage)
        },
    )

    @Test
    fun aRolesStepsAcrossRunsAreCountedAndListedNewestFirst() {
        val runs = listOf(
            run("r1", ship, 100, Triple("build", TaskRunStatus.Completed, TaskUsage(100, 50, 0.25)), Triple("test", TaskRunStatus.Completed, null)),
            run("r2", docs, 300, Triple("write", TaskRunStatus.Failed, TaskUsage(10, 5, null))),
            run("r3", ship, 200, Triple("build", TaskRunStatus.Escalated, null)),
        )
        val activity = RoleActivities.of(engineer, listOf(ship, docs), runs)
        assertEquals(3, activity.runs)
        assertEquals(3, activity.steps)
        assertEquals(1, activity.completed)
        assertEquals(2, activity.failed)
        assertEquals(165L, activity.tokens)
        assertEquals(0.25, activity.costUsd)
        assertEquals(listOf("r2", "r3", "r1"), activity.entries.map { it.runId })
        assertEquals("Docs", activity.entries.first().hive)
        assertEquals("Write", activity.entries.first().task)
        assertTrue("Docs · Write · failed · 15 tokens" in activity.entries.first().line(), activity.entries.first().line())
        // The other role sees only its own step.
        assertEquals(listOf("Test"), RoleActivities.of(qa, listOf(ship, docs), runs).entries.map { it.task })
    }

    @Test
    fun theLimitCutsTheListNotTheCountsAndAMissingDefinitionStillNamesItsSteps() {
        val runs = (1..5).map { run("r$it", ship, it * 10L, Triple("build", TaskRunStatus.Completed, null)) }
        val activity = RoleActivities.of(engineer, listOf(ship), runs, limit = 2)
        assertEquals(5, activity.steps)
        assertEquals(2, activity.entries.size)
        assertEquals(null, activity.costUsd)

        val noDefinition = RoleActivities.of(engineer, emptyList(), listOf(run("r9", ship, 1, Triple("build", TaskRunStatus.Completed, null), assigned = engineer)))
        assertEquals("ship", noDefinition.entries.single().hive)
        assertEquals("build", noDefinition.entries.single().task)
        assertEquals(RoleActivity(0, 0, 0, 0, 0, null, emptyList()), RoleActivities.of(qa, listOf(ship), emptyList()))
    }
}
