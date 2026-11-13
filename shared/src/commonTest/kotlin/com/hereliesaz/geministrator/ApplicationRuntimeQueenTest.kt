package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
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
import com.hereliesaz.geministrator.persistence.InMemoryWorkflowPersistence
import com.hereliesaz.geministrator.queen.QueenAction
import com.hereliesaz.geministrator.workflow.improvement.ImprovementKind
import com.hereliesaz.geministrator.workflow.improvement.ImprovementProposal
import com.hereliesaz.geministrator.workflow.improvement.ImprovementSource
import com.hereliesaz.geministrator.workflow.improvement.InMemoryImprovementStore
import com.hereliesaz.geministrator.workflow.improvement.ProposalStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The keeper accepting or dismissing one of the queen's suggestions: it applies only with the calls the keeper already
 * has, only while it still stands, and only once.
 */
class ApplicationRuntimeQueenTest {
    private val gate = TaskDefinitionId("gate")
    private val build = TaskDefinitionId("build")
    private val ship = TaskDefinitionId("ship")

    private fun task(id: TaskDefinitionId, vararg dependsOn: TaskDefinitionId) = TaskDefinition(
        id = id,
        name = id.value,
        objective = "Do ${id.value}",
        roleId = BuiltInRoles.Architect.id,
        dependsOn = dependsOn.toSet(),
    )

    /** A gate waiting on the keeper, with two creatures that have not started behind it (the gate is the approval they sit behind). */
    private val definition = WorkflowDefinition(
        id = WorkflowDefinitionId("queen-wf"),
        name = "Queen",
        tasks = listOf(
            TaskDefinition(
                id = gate, name = "Gate", objective = "Approve the start", roleId = null,
                executor = TaskExecutor.HumanApproval("Approve the start"),
            ),
            task(build, gate),
            task(ship, build),
        ),
        testDesignPolicy = TestDesignPolicy.None,
    )

    private inner class Fixture {
        val persistence = InMemoryWorkflowPersistence()
        val ledger = InMemoryImprovementStore()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val project = Project(ProjectId("queen-project"), "Queen", createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L)
        val run = WorkflowRun(
            id = WorkflowRunId("queen-run"),
            projectId = project.id,
            workflowDefinitionId = definition.id,
            objective = "Ship it",
            status = WorkflowRunStatus.AwaitingHuman,
            taskRuns = definition.tasks.associate { task ->
                task.id to TaskRun(
                    id = TaskRunId("queen-run-${task.id.value}"),
                    taskDefinitionId = task.id,
                    status = if (task.id == gate) TaskRunStatus.AwaitingApproval else TaskRunStatus.Created,
                    assignedRoleId = task.roleId,
                    executor = task.executor,
                )
            },
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            definitionSnapshot = definition,
        )
        lateinit var runtime: ApplicationRuntime

        suspend fun start(): Fixture {
            persistence.projects.put(project)
            persistence.definitions.put(definition)
            persistence.runs.put(run)
            runtime = ApplicationRuntime.create(providers = emptyList(), scope = scope, persistence = persistence, queenLedger = ledger)
            return this
        }

        suspend fun offer(id: String, action: QueenAction, target: String, newText: String = "", outside: Boolean = false) {
            ledger.merge {
                it.copy(
                    proposals = it.proposals + ImprovementProposal(
                        id = id, cycleRunId = run.id.value, kind = ImprovementKind.Other, target = target, summary = "s", newText = newText,
                        expectedEffect = "", undo = "", meta = false, createdAtEpochMillis = 1L, source = ImprovementSource.Queen,
                        sourceRef = definition.id.value, action = action.name, voice = "SuggestRestNanny", outside = outside,
                    ),
                )
            }
        }

        suspend fun status(id: String) = ledger.state().proposals.first { it.id == id }.status

        fun live() = assertIs<ApplicationRuntimeState.Live>(runtime.state.value).presentation
    }

    @Test
    fun acceptRewordIsALiveEditThenAdopted() = runBlocking<Unit> {
        val fixture = Fixture().start()
        try {
            fixture.offer("q1", QueenAction.RewordStep, build.value, newText = "A narrower job")
            assertEquals(ApplicationRuntime.QueenAccept.Applied, fixture.runtime.acceptQueenSuggestion("q1"))
            assertEquals("A narrower job", fixture.live().definition.tasks.first { it.id == build }.objective)
            assertEquals(ProposalStatus.Adopted, fixture.status("q1"))
        } finally {
            fixture.scope.cancel()
        }
    }

    @Test
    fun acceptTwiceChangesOnce() = runBlocking<Unit> {
        val fixture = Fixture().start()
        try {
            fixture.offer("q1", QueenAction.RewordStep, build.value, newText = "A narrower job")
            fixture.runtime.acceptQueenSuggestion("q1")
            fixture.runtime.dismissQueenSuggestion("q1")
            assertEquals(ApplicationRuntime.QueenAccept.NothingChanged, fixture.runtime.acceptQueenSuggestion("q1"))
            assertEquals(ProposalStatus.Adopted, fixture.status("q1"), "a decided row is not decided again")
        } finally {
            fixture.scope.cancel()
        }
    }

    @Test
    fun acceptAfterTheStepStartedChangesNothing() = runBlocking<Unit> {
        val fixture = Fixture().start()
        try {
            // The gate is waiting on the keeper: it has started, so it cannot take a new job.
            fixture.offer("q1", QueenAction.RewordStep, gate.value, newText = "A narrower job")
            assertEquals(ApplicationRuntime.QueenAccept.NothingChanged, fixture.runtime.acceptQueenSuggestion("q1"))
            assertEquals("Approve the start", fixture.live().definition.tasks.first { it.id == gate }.objective)
            assertEquals(ProposalStatus.Proposed, fixture.status("q1"))
        } finally {
            fixture.scope.cancel()
        }
    }

    @Test
    fun rewordFromOutsideContentIsNeverApplied() = runBlocking<Unit> {
        val fixture = Fixture().start()
        try {
            fixture.offer("q1", QueenAction.RewordStep, build.value, newText = "A narrower job", outside = true)
            assertEquals(ApplicationRuntime.QueenAccept.NothingChanged, fixture.runtime.acceptQueenSuggestion("q1"))
            assertEquals("Do build", fixture.live().definition.tasks.first { it.id == build }.objective)
        } finally {
            fixture.scope.cancel()
        }
    }

    @Test
    fun acceptRestIsTheKeepersOwnPause() = runBlocking<Unit> {
        val fixture = Fixture().start()
        try {
            fixture.offer("q1", QueenAction.Rest, "swarm")
            assertNull(fixture.live().run.keeperPause)
            assertEquals(ApplicationRuntime.QueenAccept.Applied, fixture.runtime.acceptQueenSuggestion("q1"))
            assertNotNull(fixture.live().run.keeperPause)
            assertEquals(ProposalStatus.Adopted, fixture.status("q1"))
        } finally {
            fixture.scope.cancel()
        }
    }

    @Test
    fun aLinkOnlySuggestionIsNeverAppliedHere() = runBlocking<Unit> {
        val fixture = Fixture().start()
        try {
            fixture.offer("q1", QueenAction.RaiseBudget, build.value)
            assertEquals(ApplicationRuntime.QueenAccept.NothingChanged, fixture.runtime.acceptQueenSuggestion("q1"))
            assertEquals(ProposalStatus.Proposed, fixture.status("q1"))
        } finally {
            fixture.scope.cancel()
        }
    }

    @Test
    fun dismissMarksItDismissedAndOnlyOnce() = runBlocking<Unit> {
        val fixture = Fixture().start()
        try {
            fixture.offer("q1", QueenAction.Rest, "swarm")
            assertEquals(true, fixture.runtime.dismissQueenSuggestion("q1"))
            assertEquals(ProposalStatus.Dismissed, fixture.status("q1"))
            assertEquals(false, fixture.runtime.dismissQueenSuggestion("q1"))
            assertEquals(ApplicationRuntime.QueenAccept.NothingChanged, fixture.runtime.acceptQueenSuggestion("q1"))
        } finally {
            fixture.scope.cancel()
        }
    }

    @Test
    fun withoutALedgerShePromisesNothing() = runBlocking<Unit> {
        val persistence = InMemoryWorkflowPersistence()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val runtime = ApplicationRuntime.create(providers = emptyList(), scope = scope, persistence = persistence)
            assertEquals(ApplicationRuntime.QueenAccept.NothingChanged, runtime.acceptQueenSuggestion("anything"))
            assertEquals(false, runtime.dismissQueenSuggestion("anything"))
        } finally {
            scope.cancel()
        }
    }
}
