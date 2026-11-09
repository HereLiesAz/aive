package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.AcceptanceCriterion
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.RetryPolicy
import com.hereliesaz.geministrator.domain.TaskCondition
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.TestDesignPolicy
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.events.WorkflowCancelled
import com.hereliesaz.geministrator.persistence.InMemoryWorkflowPersistence
import com.hereliesaz.geministrator.workflow.LiveDefinitionEdit
import com.hereliesaz.geministrator.workflow.LiveEditMerge
import com.hereliesaz.geministrator.workflow.TaskRerunRequest
import com.hereliesaz.geministrator.workflow.WorkflowGraphValidator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * A hive saved while its run is live (docs/Keepers-Room.md, Pause, change, resume): the unstarted
 * creatures take the job and criteria now, everything else stays as the run has it, and the saved
 * copy in the library is the keeper's, not something the running copy writes over.
 */
class ApplicationRuntimeLiveEditTest {
    private val gate = TaskDefinitionId("gate")
    private val build = TaskDefinitionId("build")
    private val ship = TaskDefinitionId("ship")

    private fun task(id: TaskDefinitionId, vararg dependsOn: TaskDefinitionId, objective: String = "Do ${id.value}") =
        TaskDefinition(
            id = id,
            name = id.value,
            objective = objective,
            roleId = BuiltInRoles.Architect.id,
            dependsOn = dependsOn.toSet(),
        )

    /** A gate waiting on the keeper, with two creatures that have not started behind it. */
    private fun hive() = WorkflowDefinition(
        id = WorkflowDefinitionId("live-wf"),
        name = "Live",
        tasks = listOf(
            TaskDefinition(
                id = gate,
                name = "Gate",
                objective = "Approve the start",
                roleId = null,
                executor = TaskExecutor.HumanApproval("Approve the start"),
            ),
            task(build, gate),
            task(ship, build),
        ),
        testDesignPolicy = TestDesignPolicy.None,
    )

    private fun liveRun(definition: WorkflowDefinition, project: Project) = WorkflowRun(
        id = WorkflowRunId("live-run"),
        projectId = project.id,
        workflowDefinitionId = definition.id,
        objective = "Ship it",
        status = WorkflowRunStatus.AwaitingHuman,
        taskRuns = definition.tasks.associate { task ->
            task.id to TaskRun(
                id = TaskRunId("live-run-${task.id.value}"),
                taskDefinitionId = task.id,
                status = if (task.id == gate) TaskRunStatus.AwaitingApproval else TaskRunStatus.Created,
                assignedRoleId = task.roleId,
                executor = task.executor,
            )
        },
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )

    private inner class Fixture {
        val persistence = InMemoryWorkflowPersistence()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val project = Project(ProjectId("live-project"), "Live", createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L)
        val definition = hive()
        val run = liveRun(definition, project)
        lateinit var runtime: ApplicationRuntime

        suspend fun start(): Fixture {
            persistence.projects.put(project)
            persistence.definitions.put(definition)
            persistence.runs.put(run)
            runtime = ApplicationRuntime.create(providers = emptyList(), scope = scope, persistence = persistence)
            return this
        }

        fun live() = assertIs<ApplicationRuntimeState.Live>(runtime.state.value).presentation
    }

    @Test
    fun aRejectedRerunLeavesTheLiveRunUntouched() = runBlocking<Unit> {
        val fixture = Fixture().start()
        try {
            // Both requests are refused by the planner; neither may stop the live run on the way.
            assertFailsWith<IllegalArgumentException> {
                fixture.runtime.rerunFromTask(
                    fixture.run.id,
                    TaskRerunRequest(build, editedObjective = "Build it again", replacementOutput = "My output"),
                )
            }
            assertFailsWith<IllegalArgumentException> {
                fixture.runtime.rerunFromTask(fixture.run.id, TaskRerunRequest(gate, replacementOutput = "My output"))
            }

            assertEquals(WorkflowRunStatus.AwaitingHuman, fixture.live().run.status)
            assertEquals(fixture.run.id, fixture.live().run.id)
            assertEquals(WorkflowRunStatus.AwaitingHuman, fixture.persistence.runs.get(fixture.run.id)?.status)
            assertTrue(
                fixture.persistence.events.forRun(fixture.run.id).none { it is WorkflowCancelled },
                "a refused request does not journal a cancellation",
            )
        } finally {
            fixture.scope.cancel()
        }
    }

    @Test
    fun aValidRerunOfALiveRunStillStopsItAndContinuesFromIt() = runBlocking<Unit> {
        val fixture = Fixture().start()
        try {
            fixture.runtime.rerunFromTask(fixture.run.id, TaskRerunRequest(gate))

            assertEquals(WorkflowRunStatus.Cancelled, fixture.persistence.runs.get(fixture.run.id)?.status)
            assertNotEquals(fixture.run.id, fixture.live().run.id)
            assertEquals(listOf(fixture.run.id), fixture.live().run.parentWorkflowRunIds)
        } finally {
            fixture.scope.cancel()
        }
    }

    @Test
    fun aSavedEditIsNotWrittenOverByTheRunningCopy() = runBlocking<Unit> {
        val fixture = Fixture().start()
        try {
            val extra = task(TaskDefinitionId("extra"), ship)
            val saved = fixture.definition.copy(
                name = "Renamed",
                tasks = fixture.definition.tasks.map {
                    when (it.id) {
                        gate -> it.copy(objective = "Approve it, carefully")
                        build -> it.copy(objective = "Build it twice")
                        else -> it
                    }
                } + extra,
            )
            // What a save does: the library copy first, then the live run is offered the edit.
            fixture.persistence.definitions.put(saved)
            assertEquals(1, fixture.runtime.applyLiveDefinitionEdit(saved), "only the creature that had not started took it")

            // Anything the runtime persists afterwards must leave the saved copy as the keeper saved it.
            fixture.runtime.rewindToTask(gate)

            assertEquals(saved, fixture.persistence.definitions.get(saved.id))
            val running = fixture.live().definition
            assertEquals("Approve the start", running.tasks.single { it.id == gate }.objective, "a started creature keeps its job in this run")
            assertEquals("Build it twice", running.tasks.single { it.id == build }.objective, "an unstarted creature takes the job now")
            assertTrue(running.tasks.none { it.id == extra.id }, "the hive's shape changes from the next run")
        } finally {
            fixture.scope.cancel()
        }
    }

    @Test
    fun aLiveEditTakesOnlyTheJobAndCriteria() = runBlocking<Unit> {
        val fixture = Fixture().start()
        try {
            // Ship waits on Build; making Build run after Ship (any outcome) would be a cycle both
            // could never leave. Only the job and criteria may reach a live run, so it never gets there.
            val edited = fixture.definition.copy(
                tasks = fixture.definition.tasks.map {
                    if (it.id == build) {
                        it.copy(
                            objective = "Build it carefully",
                            acceptanceCriteria = listOf(AcceptanceCriterion("It builds")),
                            condition = TaskCondition.OnAnyOutcome(ship),
                            retryPolicy = RetryPolicy(maxAttempts = 9),
                            holdOutput = true,
                        )
                    } else {
                        it
                    }
                },
            )

            assertEquals(1, fixture.runtime.applyLiveDefinitionEdit(edited))

            val merged = fixture.live().definition
            val taken = merged.tasks.single { it.id == build }
            assertEquals("Build it carefully", taken.objective)
            assertEquals(listOf(AcceptanceCriterion("It builds")), taken.acceptanceCriteria)
            assertEquals(TaskCondition.Always, taken.condition)
            assertEquals(RetryPolicy(), taken.retryPolicy)
            assertEquals(false, taken.holdOutput)
            assertEquals(emptyList(), WorkflowGraphValidator.validate(merged))

            // An edit to nothing but what stays as the run has it changes nothing.
            val structuralOnly = merged.copy(
                tasks = merged.tasks.map {
                    if (it.id == build) it.copy(condition = TaskCondition.OnAnyOutcome(ship)) else it
                },
            )
            assertEquals(0, fixture.runtime.applyLiveDefinitionEdit(structuralOnly))
        } finally {
            fixture.scope.cancel()
        }
    }

    @Test
    fun aMergeThatWouldBreakTheGraphIsRejectedAndTheRunKeepsItsDefinition() {
        val current = hive()
        val project = Project(ProjectId("p"), "P", createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L)
        val run = liveRun(current, project)
        val edited = current.copy(
            tasks = current.tasks.map {
                if (it.id == build) it.copy(condition = TaskCondition.OnAnyOutcome(ship)) else it
            },
        )

        // The documented fields never reach the graph. A merge that took the whole task would
        // make Build and Ship wait on each other, and is refused rather than applied.
        val rejected = LiveDefinitionEdit.merge(current, edited, run, take = { _, new -> new })
        val reason = assertIs<LiveEditMerge.Rejected>(rejected).reason
        assertTrue("Circular dependency" in reason, reason)

        assertEquals(LiveEditMerge.Nothing, LiveDefinitionEdit.merge(current, edited, run))
    }
}
