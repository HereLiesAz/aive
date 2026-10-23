package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.ApprovalGateId
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowGlobalPause
import com.hereliesaz.geministrator.domain.WorkflowGlobalPauseKind
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InboxBadgeTest {
    private fun taskRun(id: String, status: TaskRunStatus, attempt: Int = 1) = TaskRun(
        id = TaskRunId("run-$id"),
        taskDefinitionId = TaskDefinitionId(id),
        status = status,
        attempt = attempt,
        assignedRoleId = null,
    )

    private fun run(vararg taskRuns: TaskRun, pause: WorkflowGlobalPause? = null) = WorkflowRun(
        id = WorkflowRunId("r1"),
        projectId = ProjectId("p"),
        workflowDefinitionId = WorkflowDefinitionId("w"),
        objective = "Build",
        status = WorkflowRunStatus.Running,
        taskRuns = taskRuns.associateBy { it.taskDefinitionId },
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 2L,
        globalPause = pause,
    )

    private fun pause() = WorkflowGlobalPause(
        kind = WorkflowGlobalPauseKind.HallMonitorReview,
        reason = "Plateau",
        reportArtifactId = ArtifactId("report"),
        antagonistReviewArtifactId = ArtifactId("antagonist"),
        orchestratorGateId = ApprovalGateId("orchestrator"),
        humanGateId = ApprovalGateId("human"),
        taskSnapshots = emptyList(),
        pausedAtEpochMillis = 3L,
    )

    @Test
    fun onlyTasksWaitingOnAHumanAreListed() {
        val keys = inboxItemKeys(
            run(
                taskRun("plan", TaskRunStatus.AwaitingApproval),
                taskRun("fail", TaskRunStatus.Escalated),
                taskRun("busy", TaskRunStatus.Running),
                taskRun("done", TaskRunStatus.Completed),
            ),
        )
        assertEquals(2, keys.size)
        assertTrue(keys.any { it.startsWith("approval|r1|plan") })
        assertTrue(keys.any { it.startsWith("escalation|r1|fail") })
    }

    @Test
    fun aPausedRunListsOnlyItsPauseReport() {
        val keys = inboxItemKeys(run(taskRun("plan", TaskRunStatus.AwaitingApproval), pause = pause()))
        assertEquals(listOf("pause|r1|report"), keys)
    }

    @Test
    fun noLiveRunMeansNoItems() {
        assertTrue(inboxItemKeys(ApplicationRuntimeState.Loading).isEmpty())
        assertTrue(inboxItemKeys(ApplicationRuntimeState.Disconnected("offline")).isEmpty())
    }

    @Test
    fun aRepeatEscalationIsANewItem() {
        val first = inboxItemKeys(run(taskRun("fail", TaskRunStatus.Escalated, attempt = 1)))
        val second = inboxItemKeys(run(taskRun("fail", TaskRunStatus.Escalated, attempt = 2)))
        assertTrue(first.single() != second.single())
        assertEquals(1, unreadInboxCount(second, read = first.toSet()))
    }

    @Test
    fun unreadCountIgnoresSeenItemsAndDuplicates() {
        assertEquals(0, unreadInboxCount(emptyList(), emptySet()))
        assertEquals(3, unreadInboxCount(listOf("a", "b", "c"), emptySet()))
        assertEquals(1, unreadInboxCount(listOf("a", "b", "c"), setOf("a", "c", "stale")))
        assertEquals(1, unreadInboxCount(listOf("a", "a"), emptySet()))
    }

    @Test
    fun badgeStaysQuietAtZeroAndCapsAtNinetyNinePlus() {
        assertNull(inboxBadgeLabel(0))
        assertNull(inboxBadgeLabel(-4))
        assertEquals("1", inboxBadgeLabel(1))
        assertEquals("99", inboxBadgeLabel(99))
        assertEquals("99+", inboxBadgeLabel(100))
        assertEquals("99+", inboxBadgeLabel(5000))
    }

    @Test
    fun openingTheInboxMarksItemsReadAndTheRecordSurvivesARestart() {
        val settings = MapSettings()
        val keys = listOf("approval|r1|plan|1", "escalation|r1|fail|1")
        val log = InboxReadLog(settings)
        assertEquals(2, unreadInboxCount(keys, log.read))

        log.markRead(keys)
        assertEquals(0, unreadInboxCount(keys, log.read))

        val restarted = InboxReadLog(settings)
        assertEquals(0, unreadInboxCount(keys, restarted.read))
        assertEquals(1, unreadInboxCount(keys + "approval|r1|new|1", restarted.read))
    }

    @Test
    fun markingAlreadyReadItemsDoesNotRewriteTheRecord() {
        val settings = MapSettings()
        val log = InboxReadLog(settings)
        log.markRead(listOf("a"))
        val stored = settings.getStringOrNull("aive.inbox.read.v1")
        log.markRead(listOf("a"))
        assertEquals(stored, settings.getStringOrNull("aive.inbox.read.v1"))
        assertFalse(stored.isNullOrEmpty())
    }

    @Test
    fun theReadRecordKeepsOnlyTheMostRecentKeys() {
        val log = InboxReadLog(MapSettings(), capacity = 3)
        log.markRead(listOf("a", "b"))
        log.markRead(listOf("c", "d"))
        assertEquals(setOf("b", "c", "d"), log.read)
    }
}
