package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.BlockingReason
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.TaskCondition
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CreatureBirthTest {
    private fun task(id: String, vararg dependsOn: String, condition: TaskCondition = TaskCondition.Always) = TaskDefinition(
        id = TaskDefinitionId(id),
        name = id,
        objective = "Do $id",
        roleId = BuiltInRoles.ImplementationEngineer.id,
        dependsOn = dependsOn.map(::TaskDefinitionId).toSet(),
        condition = condition,
        executor = TaskExecutor.RoleAgent(BuiltInRoles.ImplementationEngineer.id),
    )

    private fun definition(vararg tasks: TaskDefinition) = WorkflowDefinition(
        id = WorkflowDefinitionId("birth"),
        name = "Birth",
        tasks = tasks.toList(),
    )

    private fun waiting() = BlockingReason(WorkflowRunFactory.WAITING_FOR_DEPENDENCIES, "Waiting for task dependencies.")

    private fun taskRun(id: String, status: TaskRunStatus, reason: BlockingReason? = null, started: Boolean = false) = TaskRun(
        id = TaskRunId("run-$id"),
        taskDefinitionId = TaskDefinitionId(id),
        status = status,
        assignedRoleId = BuiltInRoles.ImplementationEngineer.id,
        assignedProviderId = if (started) AgentProviderId("a") else null,
        blockingReason = reason ?: if (status == TaskRunStatus.Blocked) waiting() else null,
    )

    private fun run(status: WorkflowRunStatus, vararg tasks: TaskRun) = WorkflowRun(
        id = WorkflowRunId("run"),
        projectId = ProjectId("project"),
        workflowDefinitionId = WorkflowDefinitionId("birth"),
        objective = "Be born",
        status = status,
        taskRuns = tasks.associateBy { it.taskDefinitionId },
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )

    private val chain = definition(task("plan"), task("build", "plan"), task("review", "build"))

    @Test
    fun aHiveThatHasNeverRunOrIsBeingEditedShowsEveryCreature() {
        val created = run(
            WorkflowRunStatus.Created,
            taskRun("plan", TaskRunStatus.Created), taskRun("build", TaskRunStatus.Created), taskRun("review", TaskRunStatus.Created),
        )
        assertEquals(emptyMap(), projectBirths(chain, created))
        val running = run(
            WorkflowRunStatus.Running,
            taskRun("plan", TaskRunStatus.Running, started = true), taskRun("build", TaskRunStatus.Blocked), taskRun("review", TaskRunStatus.Blocked),
        )
        assertEquals(emptyMap(), projectBirths(chain, running, building = true))
    }

    @Test
    fun onlyTheNextGenerationIsAnEggAndAnEggBeginsEmpty() {
        val ready = run(
            WorkflowRunStatus.Running,
            taskRun("plan", TaskRunStatus.Ready), taskRun("build", TaskRunStatus.Blocked), taskRun("review", TaskRunStatus.Blocked),
        )
        val births = projectBirths(chain, ready)
        val egg = assertIs<CreatureBirth.Egg>(births["build"])
        assertEquals(0f, egg.growth)
        assertEquals(listOf(EggGestator("plan", 0f)), egg.gestators)
        assertEquals(CreatureBirth.Hidden, births["review"])
        assertFalse("plan" in births, "a root is born at launch")
    }

    @Test
    fun anEggGrowsAsItsGestatorWorksAndHatchesWhenTheEngineReleasesIt() {
        fun growth(status: TaskRunStatus): Float {
            val births = projectBirths(
                chain,
                run(
                    WorkflowRunStatus.Running,
                    taskRun("plan", status, started = true), taskRun("build", TaskRunStatus.Blocked), taskRun("review", TaskRunStatus.Blocked),
                ),
            )
            return assertIs<CreatureBirth.Egg>(births["build"]).growth
        }
        assertEquals(0.25f, growth(TaskRunStatus.Planning))
        assertEquals(0.5f, growth(TaskRunStatus.Running))
        assertEquals(0.8f, growth(TaskRunStatus.Verifying))
        assertEquals(1f, growth(TaskRunStatus.Completed))

        val released = run(
            WorkflowRunStatus.Running,
            taskRun("plan", TaskRunStatus.Completed, started = true), taskRun("build", TaskRunStatus.Ready), taskRun("review", TaskRunStatus.Blocked),
        )
        val births = projectBirths(chain, released)
        assertFalse("build" in births, "released means born")
        assertIs<CreatureBirth.Egg>(births["review"])
    }

    @Test
    fun aProducerAndItsReviewerGestateTheEggOfWhatTheyFeedTogether() {
        val pair = definition(task("build"), task("review", "build"), task("release", "build", "review"))
        val births = projectBirths(
            pair,
            run(
                WorkflowRunStatus.Running,
                taskRun("build", TaskRunStatus.Completed, started = true),
                taskRun("review", TaskRunStatus.Running, started = true),
                taskRun("release", TaskRunStatus.Blocked),
            ),
        )
        val egg = assertIs<CreatureBirth.Egg>(births["release"])
        assertEquals(listOf(EggGestator("build", 1f), EggGestator("review", 0.5f)), egg.gestators)
        assertEquals(0.75f, egg.growth)
    }

    @Test
    fun parallelFeedersOfOneJoinShareItsEgg() {
        val join = definition(task("a"), task("b"), task("join", "a", "b"))
        val births = projectBirths(
            join,
            run(
                WorkflowRunStatus.Running,
                taskRun("a", TaskRunStatus.Completed, started = true),
                taskRun("b", TaskRunStatus.Planning, started = true),
                taskRun("join", TaskRunStatus.Blocked),
            ),
        )
        val egg = assertIs<CreatureBirth.Egg>(births["join"])
        assertEquals(2, egg.gestators.size)
        assertEquals(0.625f, egg.growth)
    }

    @Test
    fun aFailedGestatorWithersTheEgg() {
        val births = projectBirths(
            chain,
            run(
                WorkflowRunStatus.Running,
                taskRun("plan", TaskRunStatus.Failed, started = true), taskRun("build", TaskRunStatus.Blocked), taskRun("review", TaskRunStatus.Blocked),
            ),
        )
        assertEquals(CreatureBirth.Withered, births["build"])
        assertEquals(CreatureBirth.Hidden, births["review"], "nothing past the failure ever began")
    }

    @Test
    fun aRunThatEndsWithersTheEggsItHadAndHidesTheRest() {
        val births = projectBirths(
            chain,
            run(
                WorkflowRunStatus.Cancelled,
                taskRun("plan", TaskRunStatus.Completed, started = true),
                taskRun("build", TaskRunStatus.Cancelled),
                taskRun("review", TaskRunStatus.Cancelled),
            ),
        )
        assertEquals(CreatureBirth.Withered, births["build"])
        assertEquals(CreatureBirth.Hidden, births["review"])
    }

    @Test
    fun aBranchNotTakenIsSkippedNotWithered() {
        val branches = definition(task("check"), task("fix", "check", condition = TaskCondition.OnFailure(TaskDefinitionId("check"))))
        val births = projectBirths(
            branches,
            run(
                WorkflowRunStatus.Running,
                taskRun("check", TaskRunStatus.Completed, started = true),
                taskRun("fix", TaskRunStatus.Cancelled, BlockingReason(WorkflowRunFactory.CONDITION_NOT_MET, "Not met.")),
            ),
        )
        assertEquals(CreatureBirth.Hidden, births["fix"])
    }

    @Test
    fun aCreatureHeldForABudgetWasAlreadyBorn() {
        val births = projectBirths(
            chain,
            run(
                WorkflowRunStatus.Running,
                taskRun("plan", TaskRunStatus.Completed, started = true),
                taskRun("build", TaskRunStatus.Blocked, BlockingReason("ROLE_BUDGET_SPENT", "Spent.")),
                taskRun("review", TaskRunStatus.Blocked),
            ),
        )
        assertFalse("build" in births)
        assertTrue(births["review"] is CreatureBirth.Egg)
    }

    @Test
    fun aCancelledTaskThatNeverStartedHatchedNever() {
        val born = projectBirths(
            chain,
            run(
                WorkflowRunStatus.Running,
                taskRun("plan", TaskRunStatus.Completed, started = true),
                taskRun("build", TaskRunStatus.Cancelled, started = true),
                taskRun("review", TaskRunStatus.Blocked),
            ),
        )
        assertFalse("build" in born, "cancelled after it started: a creature that curled up")
        val unborn = projectBirths(
            chain,
            run(
                WorkflowRunStatus.Running,
                taskRun("plan", TaskRunStatus.Completed, started = true),
                taskRun("build", TaskRunStatus.Cancelled),
                taskRun("review", TaskRunStatus.Blocked),
            ),
        )
        assertEquals(CreatureBirth.Withered, unborn["build"])
    }
}
