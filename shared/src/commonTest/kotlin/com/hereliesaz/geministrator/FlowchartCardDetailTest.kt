package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.BlockingReason
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
import com.hereliesaz.geministrator.domain.WorkflowKeeperPause
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.workflow.GATE_VERDICT_BLOCKED
import com.hereliesaz.geministrator.workflow.HALL_MONITOR_GLOBAL_PAUSE_CODE
import com.hereliesaz.geministrator.workflow.REMOTE_COMPUTE_UNAVAILABLE_CODE
import com.hereliesaz.geministrator.workflow.ROLE_BUDGET_SPENT
import com.hereliesaz.geministrator.workflow.SPEND_CAP_STOP
import com.hereliesaz.geministrator.workflow.WorkflowRunFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlowchartCardDetailTest {
    private val engineer = BuiltInRoles.ImplementationEngineer
    private val roles = listOf(engineer)

    private fun task(id: String, vararg deps: String) = TaskDefinition(
        id = TaskDefinitionId(id),
        name = "Step $id",
        objective = "Do $id",
        roleId = engineer.id,
        dependsOn = deps.map(::TaskDefinitionId).toSet(),
        executor = TaskExecutor.RoleAgent(engineer.id),
    )

    private val definition = WorkflowDefinition(
        id = WorkflowDefinitionId("flow"),
        name = "Flow",
        tasks = listOf(task("plan"), task("build", "plan")),
    )

    private fun run(
        build: TaskRunStatus,
        reason: BlockingReason? = null,
        plan: TaskRunStatus = TaskRunStatus.Completed,
        message: String? = null,
        provider: String? = null,
        question: String? = null,
        paused: Boolean = false,
    ) = WorkflowRun(
        id = WorkflowRunId("run"),
        projectId = ProjectId("p"),
        workflowDefinitionId = definition.id,
        objective = "o",
        status = WorkflowRunStatus.Running,
        taskRuns = mapOf(
            TaskDefinitionId("plan") to TaskRun(
                id = TaskRunId("r-plan"),
                taskDefinitionId = TaskDefinitionId("plan"),
                status = plan,
                assignedRoleId = engineer.id,
            ),
            TaskDefinitionId("build") to TaskRun(
                id = TaskRunId("r-build"),
                taskDefinitionId = TaskDefinitionId("build"),
                status = build,
                assignedRoleId = engineer.id,
                assignedProviderId = provider?.let(::AgentProviderId),
                blockingReason = reason,
                progressMessage = message,
                keeperQuestion = question,
            ),
        ),
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        keeperPause = if (paused) WorkflowKeeperPause(1L) else null,
    )

    private fun detail(run: WorkflowRun, computeAvailable: Boolean = true) =
        flowchartCardDetail(TaskDefinitionId("build"), definition, run, roles, computeAvailable)!!

    @Test
    fun aStepThatIsNotInTheRunHasNoDetail() {
        assertNull(flowchartCardDetail(TaskDefinitionId("nope"), definition, run(TaskRunStatus.Running), roles))
    }

    @Test
    fun anActiveStepSaysWhatItDoesAndOpensItsCreature() {
        for (status in listOf(TaskRunStatus.Planning, TaskRunStatus.Running, TaskRunStatus.Verifying, TaskRunStatus.Retrying)) {
            val withMessage = detail(run(status, message = "Writing the parser"))
            assertEquals(FlowchartSentenceKind.Working, withMessage.sentence)
            assertEquals("Writing the parser", withMessage.subject)
            assertEquals(FlowchartCardAction.OpenCreature("build", CreatureTab.Work), withMessage.action)
        }
        // No progress message: it names the role.
        assertEquals(engineer.name, detail(run(TaskRunStatus.Running)).subject)
    }

    @Test
    fun anApprovalGateLeadsToTheInboxItemForThatTask() {
        val gate = detail(run(TaskRunStatus.AwaitingApproval))
        assertEquals(FlowchartSentenceKind.WaitingForApproval, gate.sentence)
        assertEquals(FlowchartCardAction.OpenInbox("build"), gate.action)
    }

    @Test
    fun anEscalationLeadsToTheInboxItemForThatTask() {
        val escalated = detail(run(TaskRunStatus.Escalated))
        assertEquals(FlowchartSentenceKind.Escalated, escalated.sentence)
        assertEquals(FlowchartCardAction.OpenInbox("build"), escalated.action)
    }

    @Test
    fun aQuestionTheAgentAskedLeadsToTheInboxItem() {
        val asked = detail(run(TaskRunStatus.Running, question = "Which branch?"))
        assertEquals(FlowchartSentenceKind.Question, asked.sentence)
        assertEquals(FlowchartCardAction.OpenInbox("build"), asked.action)
    }

    @Test
    fun aSpendStopLeadsToTheSpendLimits() {
        val stopped = detail(run(TaskRunStatus.Blocked, BlockingReason(SPEND_CAP_STOP, "Run cost cap reached")))
        assertEquals(FlowchartSentenceKind.SpendStopped, stopped.sentence)
        assertEquals(FlowchartCardAction.OpenLimits, stopped.action)
    }

    @Test
    fun aSpentRoleBudgetLeadsToThatCreaturesMind() {
        val spent = detail(run(TaskRunStatus.Blocked, BlockingReason(ROLE_BUDGET_SPENT, "spent")))
        assertEquals(FlowchartSentenceKind.BudgetSpent, spent.sentence)
        assertEquals(engineer.name, spent.subject)
        assertEquals(FlowchartCardAction.OpenCreature("build", CreatureTab.Mind), spent.action)
    }

    @Test
    fun noReachableDeviceLeadsToCompute() {
        val reason = BlockingReason(REMOTE_COMPUTE_UNAVAILABLE_CODE, "No device")
        val blocked = detail(run(TaskRunStatus.Blocked, reason))
        assertEquals(FlowchartSentenceKind.ComputeUnavailable, blocked.sentence)
        assertEquals(FlowchartCardAction.OpenCompute, blocked.action)
        // Where Compute is not offered, it leads to the creature instead of a dead link.
        assertEquals(
            FlowchartCardAction.OpenCreature("build", CreatureTab.Work),
            detail(run(TaskRunStatus.Blocked, reason), computeAvailable = false).action,
        )
    }

    @Test
    fun anUnavailableIntegrationLeadsToConnections() {
        val blocked = detail(
            run(TaskRunStatus.Blocked, BlockingReason(WorkflowRunFactory.EXECUTOR_INTEGRATION_UNAVAILABLE, "GitHub Action executor is not available")),
        )
        assertEquals(FlowchartSentenceKind.IntegrationUnavailable, blocked.sentence)
        assertEquals(FlowchartCardAction.OpenConnections, blocked.action)
    }

    @Test
    fun theHallMonitorsPauseLeadsToTheInboxItself() {
        val paused = detail(run(TaskRunStatus.Blocked, BlockingReason(HALL_MONITOR_GLOBAL_PAUSE_CODE, "Review")))
        assertEquals(FlowchartSentenceKind.HallMonitorPaused, paused.sentence)
        assertEquals(FlowchartCardAction.OpenInbox(null), paused.action)
    }

    @Test
    fun aGateThatTurnedWorkBackLeadsToTheCreature() {
        val turned = detail(run(TaskRunStatus.Blocked, BlockingReason(GATE_VERDICT_BLOCKED, "turned back")))
        assertEquals(FlowchartSentenceKind.GateTurnedBack, turned.sentence)
        assertEquals(FlowchartCardAction.OpenCreature("build", CreatureTab.Work), turned.action)
    }

    @Test
    fun aStepWaitingOnADependencyShowsThatStepsCard() {
        val waiting = BlockingReason(WorkflowRunFactory.WAITING_FOR_DEPENDENCIES, "Waiting for task dependencies.")
        val onRunning = detail(run(TaskRunStatus.Blocked, waiting, plan = TaskRunStatus.Running))
        assertEquals(FlowchartSentenceKind.WaitingOnStep, onRunning.sentence)
        assertEquals("Step plan", onRunning.subject)
        assertEquals(FlowchartCardAction.ShowStep("plan", "Step plan"), onRunning.action)
        // A dependency that is itself stuck is the one named.
        val onFailed = detail(run(TaskRunStatus.Blocked, waiting, plan = TaskRunStatus.Failed))
        assertEquals(FlowchartCardAction.ShowStep("plan", "Step plan"), onFailed.action)
    }

    @Test
    fun aFailureFromTheProviderLeadsToThatProvidersConnection() {
        val rejected = detail(run(TaskRunStatus.Failed, provider = "openai", message = "401 Unauthorized"))
        assertEquals(FlowchartSentenceKind.ProviderFailed, rejected.sentence)
        assertEquals(FlowchartCardAction.FixProvider("openai"), rejected.action)
        assertEquals("openai", rejected.subject)

        val badRequest = detail(run(TaskRunStatus.Failed, provider = "gemini", message = "Executor failed. Last status: HTTP 400 Bad Request"))
        assertEquals(FlowchartCardAction.FixProvider("gemini"), badRequest.action)

        val credit = detail(run(TaskRunStatus.Failed, provider = "claude", message = "Your credit balance is too low"))
        assertEquals(FlowchartCardAction.FixProvider("claude"), credit.action)
    }

    @Test
    fun noConnectedProviderLeadsToConnections() {
        val none = detail(run(TaskRunStatus.Failed, message = "No agent provider satisfies this role"))
        assertEquals(FlowchartSentenceKind.NoProvider, none.sentence)
        assertEquals(FlowchartCardAction.OpenConnections, none.action)
        val blocked = detail(run(TaskRunStatus.Blocked, BlockingReason("OTHER", "No agent provider satisfies this role")))
        assertEquals(FlowchartCardAction.OpenConnections, blocked.action)
    }

    @Test
    fun anyOtherFailureLeadsToTheCreatureWithTheError() {
        val failed = detail(run(TaskRunStatus.Failed, provider = "openai", message = "Tests did not pass"))
        assertEquals(FlowchartSentenceKind.Failed, failed.sentence)
        assertEquals("Tests did not pass", failed.detail)
        assertEquals(FlowchartCardAction.OpenCreature("build", CreatureTab.Work), failed.action)
        assertNull(detail(run(TaskRunStatus.Failed)).detail)
    }

    @Test
    fun anUnknownHoldSaysItsMessageAndOpensTheCreature() {
        val blocked = detail(run(TaskRunStatus.Blocked, BlockingReason("SOMETHING_NEW", "Held for a reason")))
        assertEquals(FlowchartSentenceKind.Blocked, blocked.sentence)
        assertEquals("Held for a reason", blocked.detail)
        assertEquals(FlowchartCardAction.OpenCreature("build", CreatureTab.Work), blocked.action)
    }

    @Test
    fun restingStatesStillLeadToTheCreature() {
        assertEquals(FlowchartSentenceKind.Done, detail(run(TaskRunStatus.Completed)).sentence)
        assertEquals(FlowchartSentenceKind.Skipped, detail(run(TaskRunStatus.Cancelled)).sentence)
        assertEquals(FlowchartSentenceKind.NotStarted, detail(run(TaskRunStatus.Ready)).sentence)
        assertEquals(FlowchartSentenceKind.NotStarted, detail(run(TaskRunStatus.Created)).sentence)
        val paused = detail(run(TaskRunStatus.Ready, paused = true))
        assertEquals(FlowchartSentenceKind.KeeperPaused, paused.sentence)
        for (status in listOf(TaskRunStatus.Completed, TaskRunStatus.Cancelled, TaskRunStatus.Ready)) {
            assertEquals(FlowchartCardAction.OpenCreature("build", CreatureTab.Work), detail(run(status)).action)
        }
    }
}
