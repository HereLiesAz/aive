package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.ApprovalGateId
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.SpendCapKind
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowGlobalPause
import com.hereliesaz.geministrator.domain.WorkflowGlobalPauseKind
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.domain.WorkflowSpendPause
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The one Messages list: what it holds for a run, in which order, and what each row can do. */
class MessageRowsTest {
    private val engineer = BuiltInRoles.ImplementationEngineer

    private fun task(id: String) = TaskDefinition(
        id = TaskDefinitionId(id),
        name = "Task $id",
        objective = "Do $id",
        roleId = engineer.id,
        executor = TaskExecutor.RoleAgent(engineer.id),
    )

    private val definition = WorkflowDefinition(
        id = WorkflowDefinitionId("w"),
        name = "Swarm",
        tasks = listOf(task("a"), task("b"), task("c")),
    )

    private fun taskRun(id: String, status: TaskRunStatus) = TaskRun(
        id = TaskRunId("r-$id"),
        taskDefinitionId = TaskDefinitionId(id),
        status = status,
        assignedRoleId = engineer.id,
    )

    private fun presentation(
        vararg runs: TaskRun,
        status: WorkflowRunStatus = WorkflowRunStatus.Running,
        pause: WorkflowGlobalPause? = null,
        stop: WorkflowSpendPause? = null,
        def: WorkflowDefinition = definition,
    ) = LiveWorkflowPresentation(
        project = Project(ProjectId("p"), "P", createdAtEpochMillis = 1, updatedAtEpochMillis = 2),
        definition = def,
        run = WorkflowRun(
            id = WorkflowRunId("r1"),
            projectId = ProjectId("p"),
            workflowDefinitionId = def.id,
            objective = "Ship",
            status = status,
            taskRuns = runs.associateBy { it.taskDefinitionId },
            createdAtEpochMillis = 1,
            updatedAtEpochMillis = 2,
            globalPause = pause,
            spendPause = stop,
        ),
        roles = listOf(engineer),
    )

    private val pause = WorkflowGlobalPause(
        kind = WorkflowGlobalPauseKind.HallMonitorReview,
        reason = "Plateau",
        reportArtifactId = ArtifactId("report"),
        antagonistReviewArtifactId = ArtifactId("antagonist"),
        orchestratorGateId = ApprovalGateId("o"),
        humanGateId = ApprovalGateId("h"),
        taskSnapshots = emptyList(),
        pausedAtEpochMillis = 3,
    )

    private val stop = WorkflowSpendPause(
        kind = SpendCapKind.RunCost,
        reason = "Cap",
        capUsd = 1.0,
        spentUsd = 1.02,
        taskSnapshots = emptyList(),
        pausedAtEpochMillis = 4,
    )

    @Test
    fun waitsAreListedInPlanOrderAndCountToTheBadge() {
        val live = presentation(taskRun("c", TaskRunStatus.Escalated), taskRun("a", TaskRunStatus.AwaitingApproval), taskRun("b", TaskRunStatus.Running))
        val rows = messageRows(live)
        assertEquals(listOf("a", "c"), rows.map { (it as MessageRow.Wait).entry.taskId })
        assertTrue(rows.all { it.countsToBadge })
        assertEquals(inboxItemKeys(live.run), rows.map { it.key })
    }

    @Test
    fun aFreezeSpeaksForTheWholeSwarmAndIsListedAlone() {
        val live = presentation(taskRun("a", TaskRunStatus.Blocked), taskRun("b", TaskRunStatus.Escalated), pause = pause)
        val rows = messageRows(live)
        assertEquals(1, rows.size)
        assertTrue(rows.single() is MessageRow.Freeze)
        assertEquals(inboxItemKeys(live.run), rows.map { it.key })
    }

    @Test
    fun aSpendStopIsListedAloneAndWinsOverAFreeze() {
        val live = presentation(taskRun("a", TaskRunStatus.Blocked), pause = pause, stop = stop)
        val rows = messageRows(live)
        assertTrue(rows.single() is MessageRow.AllowanceStop)
        assertTrue(rows.single().countsToBadge)
    }

    @Test
    fun aFinishedRunIgnoresItsOldSpendStop() {
        val live = presentation(taskRun("b", TaskRunStatus.Escalated), status = WorkflowRunStatus.Failed, stop = stop)
        assertTrue(messageRows(live).single() is MessageRow.Wait)
    }

    @Test
    fun aPracticeRunLeadsItsOwnWaitsAndDoesNotCount() {
        val trial = definition.copy(description = "Isolated Nanny counterfactual for a solution")
        val live = presentation(taskRun("a", TaskRunStatus.AwaitingApproval), def = trial)
        val rows = messageRows(live)
        assertTrue(rows[0] is MessageRow.PracticeRun)
        assertFalse(rows[0].countsToBadge)
        assertTrue(rows[1] is MessageRow.Wait)
        // Only what the keeper decides counts: the badge's keys are the counting rows' keys.
        assertEquals(inboxItemKeys(live.run), rows.filter { it.countsToBadge }.map { it.key })
    }

    @Test
    fun noWaitsMeansNoRows() {
        assertTrue(messageRows(presentation(taskRun("a", TaskRunStatus.Running))).isEmpty())
    }

    @Test
    fun theOpenRowIsTheRememberedOneWhileItIsListedElseTheFirst() {
        val rows = messageRows(presentation(taskRun("a", TaskRunStatus.AwaitingApproval), taskRun("c", TaskRunStatus.Escalated)))
        assertEquals(rows[1], selectedMessageRow(rows, rows[1].key))
        assertEquals(rows[0], selectedMessageRow(rows, "gone"))
        assertEquals(rows[0], selectedMessageRow(rows, null))
    }

    @Test
    fun eachKindOffersTheButtonsItAlwaysHad() {
        assertEquals(listOf(MessageAction.ApprovePlan, MessageAction.RejectPlan), messageActions(InboxEntryKind.PlanApproval))
        assertEquals(listOf(MessageAction.ApproveTask), messageActions(InboxEntryKind.TaskApproval))
        assertEquals(listOf(MessageAction.Approve), messageActions(InboxEntryKind.NestedApproval))
        assertEquals(listOf(MessageAction.Retry, MessageAction.Stop), messageActions(InboxEntryKind.Escalation))
        assertEquals(listOf(MessageAction.Answer), messageActions(InboxEntryKind.Question))
        assertEquals(listOf(MessageAction.RaiseBudget), messageActions(InboxEntryKind.BudgetSpent))
    }
}
