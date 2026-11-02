package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.BlockingReason
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.SpendCapKind
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.domain.WorkflowSpendPause
import com.hereliesaz.geministrator.workflow.ROLE_BUDGET_SPENT
import com.hereliesaz.geministrator.workflow.SpendCaps
import com.russhwolf.settings.MapSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpendCapsKeeperTest {
    private fun taskRun(id: String, status: TaskRunStatus, reason: BlockingReason? = null) = TaskRun(
        id = TaskRunId("run-$id"),
        taskDefinitionId = TaskDefinitionId(id),
        status = status,
        assignedRoleId = RoleDefinitionId("implementation-engineer"),
        blockingReason = reason,
    )

    private fun run(vararg taskRuns: TaskRun, spendPause: WorkflowSpendPause? = null) = WorkflowRun(
        id = WorkflowRunId("r1"),
        projectId = ProjectId("p"),
        workflowDefinitionId = WorkflowDefinitionId("w"),
        objective = "Build",
        status = WorkflowRunStatus.Running,
        taskRuns = taskRuns.associateBy { it.taskDefinitionId },
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 2L,
        spendPause = spendPause,
    )

    @Test
    fun aCreatureWhoseBudgetIsSpentWaitsOnTheKeeper() {
        val spent = taskRun("build", TaskRunStatus.Blocked, BlockingReason(ROLE_BUDGET_SPENT, "Budget spent"))
        val waitingOnDeps = taskRun("review", TaskRunStatus.Blocked, BlockingReason("WAITING_FOR_DEPENDENCIES", "Waiting"))
        assertTrue(creatureNeedsYou(spent))
        assertFalse(creatureNeedsYou(waitingOnDeps))
        val keys = inboxItemKeys(run(spent, waitingOnDeps))
        assertEquals(1, keys.size)
        assertTrue(keys.single().startsWith("budget|r1|build"))
    }

    @Test
    fun aSpendStopSpeaksForTheWholeHive() {
        val pause = WorkflowSpendPause(
            kind = SpendCapKind.RunCost,
            reason = "Cap reached",
            capUsd = 1.0,
            spentUsd = 1.2,
            taskSnapshots = emptyList(),
            pausedAtEpochMillis = 5L,
        )
        val keys = inboxItemKeys(run(taskRun("plan", TaskRunStatus.AwaitingApproval), spendPause = pause))
        assertEquals(listOf("spend-stop|r1|5"), keys)
    }

    @Test
    fun capsAreKeptOnTheDeviceAndBlankMeansNone() {
        val settings = MapSettings()
        val store = SpendCapStore(settings)
        assertEquals(SpendCaps(), store.caps)

        store.setRunCostCap(2.5)
        store.setHourlySpendCap(10.0)
        assertEquals(SpendCaps(runCostCapUsd = 2.5, hourlySpendCapUsd = 10.0), SpendCapStore(settings).caps)

        store.setRunCostCap(null)
        store.setHourlySpendCap(0.0)
        assertEquals(SpendCaps(), store.caps)
    }

    @Test
    fun typedCapsParseAsDollars() {
        assertEquals(SpendCapInput.Off, parseSpendCapInput("  "))
        assertEquals(SpendCapInput.Cap(5.0), parseSpendCapInput("5"))
        assertEquals(SpendCapInput.Cap(2.5), parseSpendCapInput("\$2.50"))
        assertEquals(SpendCapInput.Invalid, parseSpendCapInput("0"))
        assertEquals(SpendCapInput.Invalid, parseSpendCapInput("-1"))
        assertEquals(SpendCapInput.Invalid, parseSpendCapInput("five"))
        assertEquals("5", spendCapText(5.0))
        assertEquals("2.5", spendCapText(2.5))
        assertEquals("", spendCapText(null))
    }
}
