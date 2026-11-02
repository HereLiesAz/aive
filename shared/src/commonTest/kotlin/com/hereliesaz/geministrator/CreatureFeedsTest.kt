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

class CreatureFeedsTest {
    private val engineer = BuiltInRoles.ImplementationEngineer
    private val qa = BuiltInRoles.QaEngineer
    private val roles = listOf(engineer, qa)

    private fun task(id: String, role: RoleDefinition?, vararg deps: String) = TaskDefinition(
        id = TaskDefinitionId(id),
        name = "Task $id",
        objective = "Do $id",
        roleId = role?.id,
        dependsOn = deps.map(::TaskDefinitionId).toSet(),
    )

    private val definition = WorkflowDefinition(
        id = WorkflowDefinitionId("feeds"),
        name = "Feeds",
        tasks = listOf(
            task("plan", engineer),
            task("build", engineer, "plan"),
            task("test", qa, "plan"),
            task("review", qa, "plan", "build"),
            task("notes", null, "plan"),
            task("ship", engineer, "review"),
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
                assignedRoleId = t.roleId,
            )
        },
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )

    @Test
    fun namesEachWaitingDependentByRoleOnceInWorkflowOrder() {
        val waiting = creaturesWaitingOn(
            TaskDefinitionId("plan"),
            definition,
            run("plan" to TaskRunStatus.Running, "build" to TaskRunStatus.Created, "test" to TaskRunStatus.Blocked),
            roles,
        )
        // build (engineer), test and review (qa, once), notes (no role: its task name)
        assertEquals(listOf(engineer.name, qa.name, "Task notes"), waiting)
    }

    @Test
    fun aDependentThatHasStartedIsNoLongerWaiting() {
        val waiting = creaturesWaitingOn(
            TaskDefinitionId("plan"),
            definition,
            run(
                "plan" to TaskRunStatus.Running,
                "build" to TaskRunStatus.Running,
                "test" to TaskRunStatus.Completed,
                "review" to TaskRunStatus.Ready,
                "notes" to TaskRunStatus.Cancelled,
            ),
            roles,
        )
        assertEquals(listOf(qa.name), waiting)
    }

    @Test
    fun nothingWaitsOnALeaf() {
        val waiting = creaturesWaitingOn(TaskDefinitionId("ship"), definition, run("ship" to TaskRunStatus.Running), roles)
        assertEquals(emptyList<String>(), waiting)
    }
}
