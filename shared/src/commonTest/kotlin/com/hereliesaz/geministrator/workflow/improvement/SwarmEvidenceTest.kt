package com.hereliesaz.geministrator.workflow.improvement

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.TaskUsage
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SwarmEvidenceTest {
    private val day = 86_400_000L
    private val now = 100 * day
    private val qa = BuiltInRoles.QaEngineer

    private fun step(id: String, status: TaskRunStatus, attempt: Int = 1, tokens: Long = 10, cost: Double? = 0.5) = TaskRun(
        id = TaskRunId("tr-$id"), taskDefinitionId = TaskDefinitionId(id), status = status, assignedRoleId = qa.id,
        assignedProviderId = AgentProviderId("claude"), executor = TaskExecutor.RoleAgent(qa.id), attempt = attempt,
        usage = TaskUsage(inputTokens = tokens, outputTokens = 0, costUsd = cost),
    )

    private fun run(id: String, at: Long, status: WorkflowRunStatus, vararg steps: TaskRun) = WorkflowRun(
        id = WorkflowRunId(id), projectId = ProjectId("p"), workflowDefinitionId = WorkflowDefinitionId("suite-bug-fix"), objective = "o",
        status = status, taskRuns = steps.associateBy { it.taskDefinitionId }, createdAtEpochMillis = at, updatedAtEpochMillis = at,
    )

    private val runs = listOf(
        run("recent-a", now - 2 * day, WorkflowRunStatus.Completed, step("a", TaskRunStatus.Completed), step("b", TaskRunStatus.Failed, attempt = 3)),
        run("recent-b", now - 1 * day, WorkflowRunStatus.Failed, step("c", TaskRunStatus.Escalated)),
        run("earlier", now - 10 * day, WorkflowRunStatus.Completed, step("d", TaskRunStatus.Completed), step("e", TaskRunStatus.Completed)),
        run("ancient", now - 60 * day, WorkflowRunStatus.Completed, step("f", TaskRunStatus.Completed)),
    )
    private val loopText = SelfImprovementLoop.describe()

    @Test
    fun countsCoverTheWindowAndTheOneBeforeAndLeaveOutOlderRuns() {
        val text = SwarmEvidence.of(runs, listOf(qa), ImprovementState(), now, loopText)

        assertTrue("(2 runs)" in text && "compared with the 7 days before (1 runs)" in text, text)
        assertTrue("QA Engineer (qa-engineer): 3 steps, 1 completed, 1 failed, 1 escalated, 1 retried, 30 tokens, \$1.5" in text, text)
        assertTrue("before: 2 steps, fail rate 0% (now 67%)" in text, "the change is shown, not only the level: $text")
        assertTrue("claude: 3 steps" in text)
        assertTrue("hive suite-bug-fix: 2 runs, 1 completed, 1 failed" in text)
    }

    @Test
    fun isDeterministicAndAlwaysShowsTheLoopToItselfEvenWhenTheHistoryIsHuge() {
        assertEquals(SwarmEvidence.of(runs, listOf(qa), ImprovementState(), now, loopText), SwarmEvidence.of(runs, listOf(qa), ImprovementState(), now, loopText))
        val many = (1..4_000).map { i -> run("r$i", now - (i % 6) * day, WorkflowRunStatus.Completed, step("s$i", TaskRunStatus.Completed)) }
        val text = SwarmEvidence.of(many, listOf(qa), ImprovementState(), now, loopText)
        assertTrue(text.length <= 9_000 + 100, "bounded")
        assertTrue("THE LOOP, AS IT IS NOW" in text && "Self-improvement cycle" in text, "the loop is the part that is never cut")
    }

    @Test
    fun anAdoptionIsFollowedByWhatHappenedToTheRolesFailRate() {
        val adopted = ImprovementProposal(
            id = "p1", cycleRunId = "c", kind = ImprovementKind.RoleInstruction, target = "qa-engineer", summary = "Quote the command",
            newText = "t", expectedEffect = "fewer retries", undo = "u", meta = false, status = ProposalStatus.Adopted,
            createdAtEpochMillis = now - 20 * day, decidedAtEpochMillis = now - 5 * day,
        )
        val text = SwarmEvidence.of(runs, listOf(qa), ImprovementState(proposals = listOf(adopted)), now, loopText)

        assertTrue("\"Quote the command\" (role-instruction, qa-engineer)" in text, text)
        assertTrue("qa-engineer fail rate 0% over 3 steps before, 67% over 3 steps after" in text, text)
        assertTrue("It said: fewer retries" in text, "what it promised is next to what happened")
    }

    @Test
    fun theLoopReadsWhatTheOtherMechanismsNoticedAndWhatTheKeeperMadeOfIt() {
        fun p(id: String, source: ImprovementSource, status: ProposalStatus, summary: String) = ImprovementProposal(
            id = id, cycleRunId = "c", kind = ImprovementKind.Other, target = "t", summary = summary, newText = "", expectedEffect = "e",
            undo = "u", meta = false, status = status, createdAtEpochMillis = 1L, source = source,
        )
        val state = ImprovementState(
            proposals = listOf(
                p("1", ImprovementSource.RoleHistory, ProposalStatus.Proposed, "Add 2 lessons to qa-engineer"),
                p("2", ImprovementSource.RoleHistory, ProposalStatus.Dismissed, "Add a lesson to architect"),
                p("3", ImprovementSource.Habit, ProposalStatus.Adopted, "Make github-action:ci.yml a step"),
            ),
        )

        val text = SwarmEvidence.of(runs, listOf(qa), state, now, loopText)

        assertTrue("run-history heuristics (role instructions): 1 waiting, 0 adopted, 1 dismissed" in text, text)
        assertTrue("recently dismissed: \"Add a lesson to architect\"" in text, text)
        assertTrue("- habits: 0 waiting, 1 adopted" in text, text)
        assertTrue("- the loop: 0 waiting" in text, text)
    }
}
