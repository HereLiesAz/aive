package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowKeeperPause
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class RunScarsAllRunsTest {
    private fun run(id: String, status: WorkflowRunStatus, at: Long, paused: Boolean = false) = WorkflowRun(
        id = WorkflowRunId(id),
        projectId = ProjectId("p-$id"),
        workflowDefinitionId = WorkflowDefinitionId("wf-$id"),
        objective = "objective of $id, written out in full and never cut",
        status = status,
        taskRuns = emptyMap(),
        createdAtEpochMillis = at,
        updatedAtEpochMillis = at,
        keeperPause = if (paused) WorkflowKeeperPause(at) else null,
    )

    private val runs = listOf(
        run("a", WorkflowRunStatus.Completed, 1),
        run("b", WorkflowRunStatus.Running, 5),
        run("c", WorkflowRunStatus.Running, 3, paused = true),
        run("d", WorkflowRunStatus.Failed, 4),
        run("e", WorkflowRunStatus.Cancelled, 2),
        run("f", WorkflowRunStatus.AwaitingHuman, 6),
    )

    @Test
    fun everyRunOfEveryProjectIsListedNewestFirstIncludingUnfinishedOnes() {
        assertEquals(listOf("f", "b", "d", "c", "e", "a"), allRunsFor(runs, RunStatusFilter.All).map { it.id.value })
    }

    @Test
    fun theFilterNarrowsByStatusGroup() {
        assertEquals(listOf("f", "b"), allRunsFor(runs, RunStatusFilter.Running).map { it.id.value })
        assertEquals(listOf("c"), allRunsFor(runs, RunStatusFilter.Paused).map { it.id.value })
        assertEquals(listOf("a"), allRunsFor(runs, RunStatusFilter.Done).map { it.id.value })
        assertEquals(listOf("d"), allRunsFor(runs, RunStatusFilter.Failed).map { it.id.value })
        assertEquals(listOf("e"), allRunsFor(runs, RunStatusFilter.Stopped).map { it.id.value })
    }
}
