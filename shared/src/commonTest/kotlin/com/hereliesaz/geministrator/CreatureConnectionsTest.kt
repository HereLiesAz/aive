package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
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
import kotlin.test.assertTrue

class CreatureConnectionsTest {
    private val engineer = BuiltInRoles.ImplementationEngineer
    private val qa = BuiltInRoles.QaEngineer
    private val roles = listOf(engineer, qa)

    private fun task(id: String, role: RoleDefinition?, vararg deps: String) = TaskDefinition(
        id = TaskDefinitionId(id),
        name = id,
        objective = "Do $id",
        roleId = role?.id,
        dependsOn = deps.map(::TaskDefinitionId).toSet(),
    )

    private val definition = WorkflowDefinition(
        id = WorkflowDefinitionId("connections"),
        name = "Connections",
        tasks = listOf(
            task("plan", engineer),
            task("build", engineer, "plan"),
            task("review", qa, "build"),
            task("docs", null, "build"),
        ),
    )

    private fun run(vararg statuses: Pair<String, TaskRunStatus>) = WorkflowRun(
        id = WorkflowRunId("run"),
        projectId = ProjectId("p"),
        workflowDefinitionId = definition.id,
        objective = "o",
        status = WorkflowRunStatus.Running,
        taskRuns = statuses.associate { (id, status) ->
            val t = definition.tasks.first { it.id.value == id }
            t.id to TaskRun(
                id = TaskRunId("r-$id"),
                taskDefinitionId = t.id,
                status = status,
                attempt = 1,
                assignedRoleId = t.roleId,
            )
        },
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )

    @Test
    fun listsWhoFeedsItAndWhomItFeedsInPlanOrder() {
        val connections = creatureConnections(
            TaskDefinitionId("build"),
            definition,
            run("plan" to TaskRunStatus.Completed, "build" to TaskRunStatus.Completed, "review" to TaskRunStatus.Running),
            roles,
        )
        assertEquals(listOf(TaskDefinitionId("plan")), connections.fedBy.map { it.taskId })
        assertEquals(listOf(engineer.name), connections.fedBy.map { it.name })
        assertEquals(listOf("review", "docs"), connections.feeds.map { it.taskId.value })
        // Named by role, else by the task's own name.
        assertEquals(listOf(qa.name, "docs"), connections.feeds.map { it.name })
    }

    @Test
    fun takeUpReadsTheFedCreaturesRunOnly() {
        val connections = creatureConnections(
            TaskDefinitionId("build"),
            definition,
            run("build" to TaskRunStatus.Completed, "review" to TaskRunStatus.Running, "docs" to TaskRunStatus.Blocked),
            roles,
        )
        assertEquals(listOf(true, false), connections.feeds.map { it.tookOutput })
        // No run yet: not taken.
        val none = creatureConnections(TaskDefinitionId("build"), definition, run(), roles)
        assertEquals(listOf(false, false), none.feeds.map { it.tookOutput })
    }

    @Test
    fun anEndOfThePlanFeedsNothingAndAStartIsFedByNothing() {
        val first = creatureConnections(TaskDefinitionId("plan"), definition, run(), roles)
        assertTrue(first.fedBy.isEmpty())
        val last = creatureConnections(TaskDefinitionId("review"), definition, run(), roles)
        assertTrue(last.feeds.isEmpty())
        val missing = creatureConnections(TaskDefinitionId("deploy"), definition, run(), roles)
        assertTrue(missing.fedBy.isEmpty() && missing.feeds.isEmpty())
    }
}
