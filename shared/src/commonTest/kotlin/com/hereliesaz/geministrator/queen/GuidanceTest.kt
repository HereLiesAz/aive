package com.hereliesaz.geministrator.queen

import com.hereliesaz.geministrator.CreatureTab
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.orchestration.OrchestrationPlan
import com.hereliesaz.geministrator.orchestration.OrchestrationPlanStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The hive is reached when the run shows the signs the planner named: typed and observable, never a model's opinion. */
class GuidanceTest {
    private val definition = qDefinition(qTask("a"), qTask("b").copy(dependsOn = setOf(TaskDefinitionId("a"))))
    private val artifact = ArtifactRef(ArtifactId("x"), ArtifactKind.entries.first(), TaskRunId("r-b"), "Result", textContent = "done", createdAtEpochMillis = 1)

    private fun done(withArtifact: Boolean = true, bStatus: TaskRunStatus = TaskRunStatus.Completed, status: WorkflowRunStatus = WorkflowRunStatus.Completed) =
        qRun(
            qTaskRun("a", TaskRunStatus.Completed),
            qTaskRun("b", bStatus, artifacts = if (withArtifact) listOf(artifact) else emptyList()),
            status = status,
        )

    @Test
    fun theSignsAreReadFromTheWordsSheKnowsAndNothingElse() {
        val signs = HiveSigns.parse(listOf("done:a", "artifact:b", "nothing-failed", "DONE:a", "done:zzz", "bogus", "artifact:"), setOf("a", "b"))
        assertEquals(listOf(HiveSign.StepDone("a"), HiveSign.StepLeftArtifact("b"), HiveSign.NothingFailed), signs)
        assertEquals(HiveSigns.MAX, HiveSigns.parse(List(20) { "done:a" } + (1..20).map { "done:s$it" }, (1..20).map { "s$it" }.toSet() + "a").size)
    }

    @Test
    fun thePlannersNominationIsReadFromThePlan() {
        val plan = OrchestrationPlan(
            listOf(OrchestrationPlanStep("a", "A", "Do a", "r"), OrchestrationPlanStep("b", "B", "Do b", "r", listOf("a"))),
            hiveSigns = listOf("artifact:b"),
        )
        assertEquals(listOf(HiveSign.StepLeftArtifact("b")), HiveSigns.nominated(plan))
        assertEquals(emptyList(), HiveSigns.nominated(OrchestrationPlan(plan.steps)))
    }

    @Test
    fun withNoNominationTheLastStepsAreDoneAndNothingFailed() {
        assertEquals(listOf(HiveSign.StepDone("b"), HiveSign.NothingFailed), HiveSigns.defaultFor(definition))
        assertEquals(setOf(HiveSign.StepDone("a"), HiveSign.StepDone("c"), HiveSign.NothingFailed), HiveSigns.defaultFor(qDefinition(qTask("a"), qTask("c"))).toSet())
    }

    @Test
    fun theHiveIsReachedOnlyWhenTheRunIsCompleteAndEverySignShows() {
        assertTrue(hiveReached(definition, done()))
        assertTrue(hiveReached(definition, done(), listOf(HiveSign.StepLeftArtifact("b"))))
        assertFalse(hiveReached(definition, done(status = WorkflowRunStatus.Running)))
        assertFalse(hiveReached(definition, done(withArtifact = false), listOf(HiveSign.StepLeftArtifact("b"))))
        assertFalse(hiveReached(definition, done(bStatus = TaskRunStatus.Failed)))
    }

    @Test
    fun aFailedStepBreaksNothingFailedEvenWhenTheNamedStepIsDone() {
        val run = qRun(qTaskRun("a", TaskRunStatus.Failed), qTaskRun("b", TaskRunStatus.Completed), status = WorkflowRunStatus.Completed)
        val checks = checkHive(definition, run, listOf(HiveSign.StepDone("b"), HiveSign.NothingFailed))
        assertEquals(listOf(true, false), checks.map { it.met })
        assertEquals("a", firstUnmetStep(definition, run, checks)?.id?.value)
    }

    @Test
    fun aSignForAStepTheSwarmNoLongerHasOrHoldsNoStateForIsLeftOut() {
        val run = qRun(qTaskRun("a", TaskRunStatus.Completed), status = WorkflowRunStatus.Completed)
        assertEquals(listOf(HiveSign.StepDone("a")), checkHive(definition, run, listOf(HiveSign.StepDone("a"), HiveSign.StepDone("gone"), HiveSign.StepDone("b"))).map { it.sign })
        assertTrue(hiveReached(definition, run, listOf(HiveSign.StepDone("gone"))))
    }

    @Test
    fun finishedButNotReachedIsSaidWithWhereToLook() {
        val note = queenUpdate(definition, done(withArtifact = false), listOf(engineerRole), QueenLevel.Quiet, hiveSigns = listOf(HiveSign.StepLeftArtifact("b"))).single()
        assertEquals(QueenVoice.FinishedNotReached, note.voice)
        assertEquals(listOf("1", "1", "2", "Task b"), note.args)
        assertEquals(QueenLink.Creature("b", CreatureTab.Work), note.link)
    }

    @Test
    fun aReachedHiveKeepsTheOrdinaryFinishNote() {
        val note = queenUpdate(definition, done(), listOf(engineerRole), QueenLevel.Quiet, hiveSigns = listOf(HiveSign.StepLeftArtifact("b"))).single()
        assertEquals(QueenVoice.FinishedAll, note.voice)
    }
}
