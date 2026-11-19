package com.hereliesaz.geministrator.tour

import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
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
import kotlin.test.assertTrue

/** What the hive does that a tour step can wait on: read off two publishes of the run, no model. */
class RunSignalsTest {
    private fun task(id: String, vararg after: String) = TaskDefinition(
        id = TaskDefinitionId(id),
        name = id,
        objective = id,
        roleId = BuiltInRoles.ImplementationEngineer.id,
        executor = TaskExecutor.RoleAgent(BuiltInRoles.ImplementationEngineer.id),
        dependsOn = after.map(::TaskDefinitionId).toSet(),
    )

    private val definition = WorkflowDefinition(WorkflowDefinitionId("tour"), "tour", tasks = listOf(task("scout"), task("write", "scout")))

    private fun taskRun(id: String, status: TaskRunStatus, artifacts: Int = 0) = TaskRun(
        id = TaskRunId("run-$id"),
        taskDefinitionId = TaskDefinitionId(id),
        status = status,
        assignedRoleId = BuiltInRoles.ImplementationEngineer.id,
        artifacts = List(artifacts) { ArtifactRef(ArtifactId("a$it"), ArtifactKind.Requirement, TaskRunId("run-$id"), "made", textContent = "made", createdAtEpochMillis = 1L) },
    )

    private fun run(status: WorkflowRunStatus, vararg tasks: TaskRun) = WorkflowRun(
        id = WorkflowRunId("run"),
        projectId = ProjectId("project"),
        workflowDefinitionId = WorkflowDefinitionId("tour"),
        objective = "Show",
        status = status,
        taskRuns = tasks.associateBy { it.taskDefinitionId },
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
    )

    private val eggs = run(WorkflowRunStatus.Created, taskRun("scout", TaskRunStatus.Created), taskRun("write", TaskRunStatus.Blocked))
    private val scouting = run(WorkflowRunStatus.Running, taskRun("scout", TaskRunStatus.Running), taskRun("write", TaskRunStatus.Blocked))
    private val handed = run(WorkflowRunStatus.Running, taskRun("scout", TaskRunStatus.Completed, artifacts = 1), taskRun("write", TaskRunStatus.Running))
    private val finished = run(WorkflowRunStatus.Completed, taskRun("scout", TaskRunStatus.Completed, 1), taskRun("write", TaskRunStatus.Completed, 1))

    @Test
    fun aCreatureLeavingItsEggIsAHatch() {
        assertEquals(setOf(RunSignal.Hatched), runSignals(definition, eggs, scouting))
    }

    @Test
    fun aTourThatStartsMidRunStillSeesTheCreaturesAlreadyOut() {
        assertTrue(RunSignal.Hatched in runSignals(definition, null, scouting))
    }

    @Test
    fun workChangingHandsNeedsAFinishedCreatureAndASuccessorThatBegan() {
        val signals = runSignals(definition, scouting, handed)
        assertTrue(RunSignal.HandedOff in signals)
        assertTrue(RunSignal.Artifact in signals)
        assertTrue(RunSignal.HandedOff !in runSignals(definition, eggs, scouting))
    }

    @Test
    fun theEndOfTheRunIsSignalledOnce() {
        assertTrue(RunSignal.Done in runSignals(definition, handed, finished))
        assertTrue(RunSignal.Done !in runSignals(definition, finished, finished))
    }

    @Test
    fun theQueenSpeakingIsAGrowingCountOfLines() {
        assertEquals(setOf(RunSignal.QueenSpoke), runSignals(definition, scouting, scouting, queenBefore = 1, queenAfter = 2))
        assertEquals(emptySet(), runSignals(definition, scouting, scouting, queenBefore = 2, queenAfter = 2))
    }

    @Test
    fun aRunSignalMeetsTheStepThatWaitsOnItAndNoOther() {
        val step = TourExpect(ExpectKind.RunHatched, null)
        assertTrue(expectationMet(step, TourEvent.Run(RunSignal.Hatched)))
        assertTrue(!expectationMet(step, TourEvent.Run(RunSignal.Done)))
        assertTrue(!expectationMet(step, TourEvent.Tapped(TourTargetId.GOAL_FIELD)))
    }
}
