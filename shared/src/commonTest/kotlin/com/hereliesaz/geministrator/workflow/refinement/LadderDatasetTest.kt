package com.hereliesaz.geministrator.workflow.refinement

import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.CompoundInferencePolicy
import com.hereliesaz.geministrator.domain.EnvironmentPlanningPolicy
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.RefinementLevel
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
import com.hereliesaz.geministrator.workflow.RefinementLadderExpander
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LadderDatasetTest {
    private val base = TaskDefinitionId("work")
    private val definition: WorkflowDefinition = RefinementLadderExpander.expand(
        WorkflowDefinition(
            id = WorkflowDefinitionId("ladder"),
            name = "Ladder",
            testDesignPolicy = TestDesignPolicy.None,
            tasks = listOf(
                TaskDefinition(
                    id = base,
                    name = "Work",
                    objective = "Verify the build",
                    roleId = BuiltInRoles.ImplementationEngineer.id,
                    executor = TaskExecutor.RoleAgent(BuiltInRoles.ImplementationEngineer.id),
                    environmentPlanningPolicy = EnvironmentPlanningPolicy.NotRequired,
                    compoundInferencePolicy = CompoundInferencePolicy.RefinementLadder(
                        levels = listOf(
                            RefinementLevel(BuiltInRoles.ProductManager.id, "intent", structured = true),
                            RefinementLevel(BuiltInRoles.Architect.id, "steps", structured = true),
                        ),
                        gateReviewerRoleId = BuiltInRoles.AdversarialReviewer.id,
                        finalRoleId = BuiltInRoles.ImplementationEngineer.id,
                    ),
                ),
            ),
        ),
        BuiltInRoles.all,
    )

    private val level1 = LadderArtifactCodec.encode(
        LadderArtifact(level = 1, of = 2, summary = "s", decisions = listOf(LadderDecision("d1", "Run the build"))),
    )
    private val level2 = LadderArtifactCodec.encode(
        LadderArtifact(
            level = 2,
            of = 2,
            summary = "s",
            decisions = listOf(LadderDecision("e1", "Build", refines = listOf("d1"))),
            steps = listOf(LadderStep("build", "Run the build")),
        ),
    )

    private fun taskRun(id: String, status: TaskRunStatus, kind: ArtifactKind? = null, text: String? = null) = TaskRun(
        id = TaskRunId("r-$id"),
        taskDefinitionId = TaskDefinitionId(id),
        status = status,
        assignedRoleId = null,
        artifacts = if (kind == null) {
            emptyList()
        } else {
            listOf(ArtifactRef(ArtifactId("a-$id"), kind, TaskRunId("r-$id"), "x", textContent = text, createdAtEpochMillis = 1L))
        },
    )

    private fun run(vararg runs: TaskRun) = WorkflowRun(
        id = WorkflowRunId("run"),
        projectId = ProjectId("p"),
        workflowDefinitionId = definition.id,
        objective = "Ship",
        status = WorkflowRunStatus.Running,
        taskRuns = runs.associateBy { it.taskDefinitionId },
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 9L,
    )

    private val finished = run(
        taskRun("work--rl-level-1", TaskRunStatus.Completed, ArtifactKind.TaskPlan, level1),
        taskRun("work--rl-gate-1", TaskRunStatus.Completed, ArtifactKind.Verification, "VERDICT: approve\nok"),
        taskRun("work--rl-level-2", TaskRunStatus.Completed, ArtifactKind.TaskPlan, level2),
        taskRun("work--rl-gate-2", TaskRunStatus.Failed, ArtifactKind.Verification, "VERDICT: reject\nwidens"),
        taskRun("work", TaskRunStatus.Blocked),
        taskRun("work--rl-verifier", TaskRunStatus.Cancelled),
    )

    @Test
    fun extractorLabelsEachLevelWithItsGateAndChecksItAgainstTheLevelAbove() {
        val records = LadderDatasetExtractor.extract(definition, finished)
        val levels = records.filter { it.kind == LadderDatasetRecord.KIND_LEVEL }.sortedBy { it.level }
        assertEquals(listOf("run/work/level-1/a1", "run/work/level-2/a1"), levels.map { it.id })
        assertEquals(listOf("approve", "reject"), levels.map { it.gateVerdict })
        assertTrue(levels.all { it.structured && it.valid })
        assertEquals(2, levels[0].total)
        assertTrue("Verify the build" in levels[0].objective.orEmpty())
        val outcome = records.single { it.kind == LadderDatasetRecord.KIND_OUTCOME }
        assertEquals("run/work/outcome", outcome.id)
        assertEquals("Blocked", outcome.finalStatus)
    }

    @Test
    fun aLevelThatDoesNotCoverTheOneAboveIsRecordedAsInvalid() {
        val shallow = LadderArtifactCodec.encode(
            LadderArtifact(
                level = 2,
                of = 2,
                summary = "s",
                decisions = listOf(LadderDecision("e1", "Other", refines = emptyList())),
                steps = listOf(LadderStep("build", "Run")),
            ),
        )
        val records = LadderDatasetExtractor.extract(
            definition,
            run(
                taskRun("work--rl-level-1", TaskRunStatus.Completed, ArtifactKind.TaskPlan, level1),
                taskRun("work--rl-gate-1", TaskRunStatus.Completed, ArtifactKind.Verification, "VERDICT: approve"),
                taskRun("work--rl-level-2", TaskRunStatus.Completed, ArtifactKind.TaskPlan, shallow),
                taskRun("work--rl-gate-2", TaskRunStatus.Completed, ArtifactKind.Verification, "VERDICT: approve"),
            ),
        )
        val second = records.single { it.level == 2 }
        assertFalse(second.valid)
        assertTrue(second.errors.any { "not refined" in it })
    }

    @Test
    fun proseLevelsAndUnfinishedGatesAreHandledAndKeysAreScrubbed() {
        // Built at run time so no key-shaped literal sits in the source.
        val secret = "sk-" + "a1B2c3D4".repeat(5)
        val records = LadderDatasetExtractor.extract(
            definition,
            run(
                taskRun("work--rl-level-1", TaskRunStatus.Completed, ArtifactKind.TaskPlan, "A prose plan with $secret"),
                taskRun("work--rl-gate-1", TaskRunStatus.Completed, ArtifactKind.Verification, "VERDICT: approve"),
                taskRun("work--rl-level-2", TaskRunStatus.Completed, ArtifactKind.TaskPlan, level2),
                taskRun("work--rl-gate-2", TaskRunStatus.Running),
            ),
        )
        val only = records.single()
        assertFalse(only.structured)
        assertFalse(secret in only.artifact.orEmpty())
        assertNull(records.firstOrNull { it.level == 2 })
        assertTrue(LadderDatasetExtractor.extract(definition, run()).isEmpty())
    }

    @Test
    fun captureRecordsOnlyWhenSwitchedOnAndKeepsEachRecordOnce(): Unit = runBlocking {
        val capture = LadderDatasetCapture(MapSettings())
        capture.observe(definition, finished)
        assertEquals("", capture.exportJsonl())

        capture.enabled = true
        capture.observe(definition, finished)
        capture.observe(definition, finished)
        val lines = capture.exportJsonl().lines()
        assertEquals(3, lines.size)
        assertTrue(lines.all { "aive.ladder.record.v1" in it })

        capture.clear()
        assertEquals("", capture.exportJsonl())
    }

    @Test
    fun storesDropTheOldestPastCapacityAndReadCorruptionAsEmpty(): Unit = runBlocking {
        val store = InMemoryLadderDatasetStore(capacity = 2)
        fun record(id: String) =
            LadderDatasetRecord(id = id, kind = "level", ladder = "l", taskId = "t", capturedAtEpochMillis = 1L)
        assertEquals(3, store.append(listOf(record("a"), record("b"), record("c"))))
        assertEquals(listOf("b", "c"), store.all().map { it.id })
        assertEquals(0, store.append(listOf(record("c"))))

        val settings = MapSettings().apply { putString(SettingsLadderDatasetStore.KEY, "{not json") }
        val durable = SettingsLadderDatasetStore(settings)
        assertTrue(durable.all().isEmpty())
        assertEquals(1, durable.append(listOf(record("x"))))
        assertEquals(listOf("x"), durable.all().map { it.id })
        assertNotNull(settings.getStringOrNull(SettingsLadderDatasetStore.KEY))
    }
}
