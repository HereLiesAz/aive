package com.hereliesaz.geministrator.workflow.improvement

import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.persistence.InMemorySettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CycleIngestTest {
    private val line = "IMPROVEMENT | role-instruction | qa-engineer | Quote the command | Always quote it. | Fewer retries; retried steps | Remove it"
    private val meta = "IMPROVEMENT | loop-definition | suite-self-improvement/examine-loop | Start from retries | Start from retried steps. | Findings cite retry counts | Restore it"

    private fun finalStep(text: String?) = TaskRun(
        id = TaskRunId("tr"), taskDefinitionId = TaskDefinitionId(SelfImprovementLoop.FINAL_STEP), status = TaskRunStatus.Completed, assignedRoleId = null,
        artifacts = if (text == null) emptyList() else listOf(ArtifactRef(id = ArtifactId("a"), kind = ArtifactKind.Review, taskRunId = TaskRunId("tr"), label = "t", textContent = text, createdAtEpochMillis = 1L)),
    )

    private fun cycle(id: String, at: Long, text: String?, status: WorkflowRunStatus = WorkflowRunStatus.Completed, hive: String = SelfImprovementLoop.HIVE_ID) = WorkflowRun(
        id = WorkflowRunId(id), projectId = ProjectId("p"), workflowDefinitionId = WorkflowDefinitionId(hive), objective = "o", status = status,
        taskRuns = mapOf(TaskDefinitionId(SelfImprovementLoop.FINAL_STEP) to finalStep(text)), createdAtEpochMillis = at, updatedAtEpochMillis = at,
    )

    @Test
    fun m14_aCycleThatFinishedWhileTheAppWasClosedIsReadFromTheRunHistoryAtStartupAndOnlyOnce() = runTest {
        val store = SettingsImprovementStore(InMemorySettings())
        val history = listOf(
            cycle("old", 1_000L, "$line\n$meta"),
            cycle("newer", 2_000L, line),
            cycle("failed", 3_000L, line, status = WorkflowRunStatus.Failed),
            cycle("other-hive", 4_000L, line, hive = "suite-bug-fix"),
            cycle("no-text", 5_000L, null),
        )

        val added = CycleIngest.backfill(store, history)

        assertEquals(listOf("old", "newer"), added.map { it.id.value }, "finished loop cycles with something written, oldest first")
        val state = store.state()
        assertEquals(listOf("old", "newer"), state.cycles.map { it.runId })
        assertEquals(listOf(1_000L, 2_000L), state.cycles.map { it.atEpochMillis }, "stamped with when each finished, not with now")
        assertEquals(3, state.proposals.size)
        assertTrue(state.cycles.first().examinedLoop)

        // Starting again, or ingesting what the live view already ingested, adds nothing.
        assertEquals(emptyList(), CycleIngest.backfill(store, history))
        assertEquals(false, CycleIngest.ingest(store, history[0]))
        assertEquals(3, store.state().proposals.size)
    }

    @Test
    fun m14_aLongHistoryReadsOnlyTheNewestFewCycles() = runTest {
        val store = InMemoryImprovementStore()
        val history = (1..25).map { cycle("c$it", it * 1_000L, line) }

        val added = CycleIngest.backfill(store, history)

        assertEquals(CycleIngest.MAX_BACKFILL, added.size)
        assertEquals("c16", added.first().id.value)
        assertEquals("c25", added.last().id.value)
    }
}
