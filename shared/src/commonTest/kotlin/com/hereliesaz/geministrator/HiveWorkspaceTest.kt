package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HiveWorkspaceTest {
    private fun run(
        id: String,
        status: WorkflowRunStatus = WorkflowRunStatus.Running,
        definition: String = "hive",
        createdAt: Long = 1,
        tasks: Map<String, TaskRunStatus> = emptyMap(),
    ) = WorkflowRun(
        id = WorkflowRunId(id),
        projectId = ProjectId("p"),
        workflowDefinitionId = WorkflowDefinitionId(definition),
        objective = "Ship it",
        status = status,
        taskRuns = tasks.entries.associate { (task, s) -> TaskDefinitionId(task) to TaskRun(TaskRunId("r-$task"), TaskDefinitionId(task), s, assignedRoleId = null) },
        createdAtEpochMillis = createdAt,
        updatedAtEpochMillis = createdAt,
    )

    @Test
    fun countsOnlyWorkingCreaturesAsActive() {
        val activity = hiveActivity(
            run(
                "a",
                tasks = mapOf(
                    "plan" to TaskRunStatus.Completed,
                    "build" to TaskRunStatus.Running,
                    "check" to TaskRunStatus.Verifying,
                    "review" to TaskRunStatus.AwaitingApproval,
                    "ship" to TaskRunStatus.Created,
                ),
            ),
        )
        assertEquals(HiveActivity(active = 2, total = 5), activity)
    }

    @Test
    fun runNumberCountsTheHivesRunsByStart() {
        val first = run("r1", createdAt = 10)
        val other = run("x", definition = "elsewhere", createdAt = 15)
        val second = run("r2", createdAt = 20)
        val all = listOf(second, other, first)
        assertEquals(1, hiveRunNumber(first, all))
        assertEquals(2, hiveRunNumber(second, all))
        assertEquals(1, hiveRunNumber(other, all))
    }

    @Test
    fun runNumberIsUnknownBeforeTheRunsLoad() {
        assertNull(hiveRunNumber(run("r1"), emptyList()))
    }

    @Test
    fun aQueuedFeedIsOnlyDroppedOnceItsLaunchStartedANewRun() {
        val queued = QueuedFeed("r1", "Write the release notes")
        // Fired but the launch has not produced a new run (refused, failed, still going): keep it.
        assertEquals(false, queuedFeedSettled(queued, "r1", run("r1", WorkflowRunStatus.Completed)))
        // Never fired (the keeper just switched to another run while it waits): keep it.
        assertEquals(false, queuedFeedSettled(queued, null, run("r2", WorkflowRunStatus.Running)))
        assertEquals(false, queuedFeedSettled(null, "r1", run("r2", WorkflowRunStatus.Running)))
        assertEquals(true, queuedFeedSettled(queued, "r1", run("r2", WorkflowRunStatus.Running)))
    }

    @Test
    fun aQueuedObjectiveIsDueOnlyOnceItsOwnRunHasFinished() {
        val queued = QueuedFeed("r1", "Write the release notes")
        assertNull(queuedFeedDue(queued, run("r1", WorkflowRunStatus.Running)))
        assertNull(queuedFeedDue(queued, run("r2", WorkflowRunStatus.Completed)))
        assertNull(queuedFeedDue(null, run("r1", WorkflowRunStatus.Completed)))
        assertNull(queuedFeedDue(queued, null))
        assertEquals("Write the release notes", queuedFeedDue(queued, run("r1", WorkflowRunStatus.Completed)))
        assertEquals("Write the release notes", queuedFeedDue(queued, run("r1", WorkflowRunStatus.Failed)))
    }

    @Test
    fun theFeedBarLaunchesOnlyOverAFinishedSwarm() {
        // Launching over a live run would cancel it, so a live swarm queues instead.
        assertFalse(feedLaunchesNow(WorkflowRunStatus.Running))
        assertFalse(feedLaunchesNow(WorkflowRunStatus.AwaitingHuman))
        assertTrue(feedLaunchesNow(WorkflowRunStatus.Completed))
        assertTrue(feedLaunchesNow(WorkflowRunStatus.Cancelled))
    }

    @Test
    fun statesReadAsTheLegendDoes() {
        assertEquals(CreatureStateTone.Working, creatureStateTone(TaskRunStatus.Running))
        assertEquals(CreatureStateTone.Complete, creatureStateTone(TaskRunStatus.Completed))
        assertEquals(CreatureStateTone.Waiting, creatureStateTone(TaskRunStatus.Blocked))
        assertEquals(CreatureStateTone.Waiting, creatureStateTone(null))
        assertEquals(CreatureStateTone.Gated, creatureStateTone(TaskRunStatus.AwaitingApproval))
        assertEquals(CreatureStateTone.Failed, creatureStateTone(TaskRunStatus.Failed))
    }

    @Test
    fun capabilitiesReadAsPlainWords() {
        assertEquals("Repository write", capabilityName(AgentCapability.RepositoryWrite))
        assertEquals("Research", capabilityName(AgentCapability.Research))
    }

    @Test
    fun theCameraZoomsWithinItsRangeAndSurfacesPastTheWidest() {
        val camera = TerrariumCamera()
        repeat(20) { camera.zoomIn() }
        assertEquals(TerrariumCamera.MAX_ZOOM, camera.zoom)
        var surfaced = false
        repeat(20) { camera.zoomOut { surfaced = true } }
        assertEquals(TerrariumCamera.MIN_ZOOM, camera.zoom)
        assertTrue(surfaced)
        camera.centre()
        assertEquals(1f, camera.zoom)
    }
}
