package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.BlockingReason
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.RoleDefinitionId
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
import com.hereliesaz.geministrator.workflow.WorkflowRunFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CreatureRoamTest {
    private val engineer = BuiltInRoles.ImplementationEngineer.id
    private val reviewer = BuiltInRoles.AdversarialReviewer.id

    private fun task(id: String, role: RoleDefinitionId, vararg dependsOn: String, programmatic: Boolean = false) = TaskDefinition(
        id = TaskDefinitionId(id),
        name = id,
        objective = "Do $id",
        roleId = role,
        dependsOn = dependsOn.map(::TaskDefinitionId).toSet(),
        executor = if (programmatic) TaskExecutor.GitHubAction("ci.yml") else TaskExecutor.RoleAgent(role),
    )

    private fun definition(vararg tasks: TaskDefinition) = WorkflowDefinition(
        id = WorkflowDefinitionId("roam"),
        name = "Roam",
        tasks = tasks.toList(),
    )

    private fun taskRun(id: String, status: TaskRunStatus, role: RoleDefinitionId = engineer, programmatic: Boolean = false) = TaskRun(
        id = TaskRunId("run-$id"),
        taskDefinitionId = TaskDefinitionId(id),
        status = status,
        assignedRoleId = role,
        assignedProviderId = if (status == TaskRunStatus.Created || status == TaskRunStatus.Blocked || status == TaskRunStatus.Ready) null else AgentProviderId("a"),
        blockingReason = if (status == TaskRunStatus.Blocked) BlockingReason(WorkflowRunFactory.WAITING_FOR_DEPENDENCIES, "waiting") else null,
        executor = if (programmatic) TaskExecutor.GitHubAction("ci.yml") else TaskExecutor.RoleAgent(role),
    )

    private fun run(status: WorkflowRunStatus, vararg tasks: TaskRun) = WorkflowRun(
        id = WorkflowRunId("run"),
        projectId = ProjectId("project"),
        workflowDefinitionId = WorkflowDefinitionId("roam"),
        objective = "Roam",
        status = status,
        taskRuns = tasks.associateBy { it.taskDefinitionId },
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )

    /** build1 -> test (reviewer) -> build2: the engineer does build1 then build2. */
    private val sequence = definition(
        task("build1", engineer),
        task("review", reviewer, "build1"),
        task("build2", engineer, "review"),
    )

    @Test
    fun aHiveThatHasNotStartedOrIsBeingEditedKeepsACreatureForEveryTask() {
        val created = run(
            WorkflowRunStatus.Created,
            taskRun("build1", TaskRunStatus.Created), taskRun("review", TaskRunStatus.Created, reviewer), taskRun("build2", TaskRunStatus.Created),
        )
        assertEquals(RoamPlan(), projectRoaming(sequence, created))
        val running = run(
            WorkflowRunStatus.Running,
            taskRun("build1", TaskRunStatus.Running), taskRun("review", TaskRunStatus.Blocked, reviewer), taskRun("build2", TaskRunStatus.Blocked),
        )
        assertEquals(RoamPlan(), projectRoaming(sequence, running, building = true))
    }

    @Test
    fun orderedTasksOfOneRoleShareACreatureAndOtherRolesDoNot() {
        val running = run(
            WorkflowRunStatus.Running,
            taskRun("build1", TaskRunStatus.Running), taskRun("review", TaskRunStatus.Blocked, reviewer), taskRun("build2", TaskRunStatus.Blocked),
        )
        val plan = projectRoaming(sequence, running)

        assertEquals(listOf(RoamGroup(engineer.value, listOf("build1", "build2"), rider = "build1")), plan.groups)
        assertEquals(CreatureRoam.Rider(from = null), plan.roam["build1"])
        assertEquals(CreatureRoam.Site, plan.roam["build2"])
        assertNull(plan.roam["review"])
    }

    @Test
    fun tasksThatCanRunAtTheSameTimeKeepTheirOwnCreatures() {
        val parallel = definition(
            task("plan", engineer),
            task("a", reviewer, "plan"),
            task("b", reviewer, "plan"),
            task("join", engineer, "a", "b"),
        )
        val running = run(
            WorkflowRunStatus.Running,
            taskRun("plan", TaskRunStatus.Completed), taskRun("a", TaskRunStatus.Running, reviewer), taskRun("b", TaskRunStatus.Running, reviewer),
            taskRun("join", TaskRunStatus.Blocked),
        )
        val plan = projectRoaming(parallel, running)

        // The two reviewers have no path either way: two creatures. The engineer's plan and join are ordered: one.
        assertEquals(listOf(listOf("plan", "join")), plan.groups.map { it.members })
        assertNull(plan.roam["a"])
        assertNull(plan.roam["b"])
    }

    @Test
    fun aTaskJoinsTheFirstCreatureWhoseEveryTaskIsItsAncestor() {
        // x and y are parallel engineers; z waits on both, so it can join either only if both lead to it.
        val fork = definition(
            task("x", engineer),
            task("y", engineer),
            task("z", engineer, "x", "y"),
        )
        val running = run(
            WorkflowRunStatus.Running,
            taskRun("x", TaskRunStatus.Completed), taskRun("y", TaskRunStatus.Running), taskRun("z", TaskRunStatus.Blocked),
        )
        val plan = projectRoaming(fork, running)

        assertEquals(listOf(listOf("x", "z")), plan.groups.map { it.members })
        assertNull(plan.roam["y"])
    }

    @Test
    fun programmaticWorkNeverSharesACreature() {
        val mixed = definition(
            task("ci1", engineer, programmatic = true),
            task("ci2", engineer, "ci1", programmatic = true),
        )
        val running = run(
            WorkflowRunStatus.Running,
            taskRun("ci1", TaskRunStatus.Completed, programmatic = true), taskRun("ci2", TaskRunStatus.Running, programmatic = true),
        )
        assertEquals(RoamPlan(), projectRoaming(mixed, running))
    }

    @Test
    fun theCreatureIsAtTheTaskItWorksOnThenTheNextReleasedThenTheLastItWasBornInto() {
        fun riderWith(build1: TaskRunStatus, build2: TaskRunStatus): String {
            val state = run(
                WorkflowRunStatus.Running,
                taskRun("build1", build1), taskRun("review", TaskRunStatus.Running, reviewer), taskRun("build2", build2),
            )
            return projectRoaming(sequence, state).groups.single().rider
        }

        assertEquals("build1", riderWith(TaskRunStatus.Running, TaskRunStatus.Blocked))
        // The first is done and the second not yet released: it stays where it last worked.
        assertEquals("build1", riderWith(TaskRunStatus.Completed, TaskRunStatus.Blocked))
        // The second is released and waiting: the creature is already at it.
        assertEquals("build2", riderWith(TaskRunStatus.Completed, TaskRunStatus.Ready))
        assertEquals("build2", riderWith(TaskRunStatus.Completed, TaskRunStatus.Running))
        // Both done: it rests at the last.
        assertEquals("build2", riderWith(TaskRunStatus.Completed, TaskRunStatus.Completed))
    }

    @Test
    fun aRiderThatHasMovedOnComesFromThePlaceBefore() {
        val state = run(
            WorkflowRunStatus.Running,
            taskRun("build1", TaskRunStatus.Completed), taskRun("review", TaskRunStatus.Completed, reviewer), taskRun("build2", TaskRunStatus.Running),
        )
        val plan = projectRoaming(sequence, state)

        assertEquals(CreatureRoam.Rider(from = "build1"), plan.roam["build2"])
        assertEquals(CreatureRoam.Site, plan.roam["build1"])
        assertIs<CreatureRoam.Site>(plan.roam["build1"])
        assertTrue(plan.groupOf("build1") === plan.groupOf("build2"))
    }
}
