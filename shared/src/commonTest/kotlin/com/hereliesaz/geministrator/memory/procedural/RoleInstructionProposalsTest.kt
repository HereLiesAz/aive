package com.hereliesaz.geministrator.memory.procedural

import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.events.AgentAssigned
import com.hereliesaz.geministrator.events.TaskCompleted
import com.hereliesaz.geministrator.events.TaskStarted
import com.hereliesaz.geministrator.events.VerificationFailed
import com.hereliesaz.geministrator.events.WorkflowEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RoleInstructionProposalsTest {
    private val engineer = BuiltInRoles.ImplementationEngineer
    private val qa = BuiltInRoles.QaEngineer
    private val roles: List<RoleDefinition> = listOf(engineer, qa)

    private fun run(id: String = "r1", vararg tasks: String) = WorkflowRun(
        id = WorkflowRunId(id),
        projectId = ProjectId("p"),
        workflowDefinitionId = WorkflowDefinitionId("w"),
        objective = "o",
        status = WorkflowRunStatus.Running,
        taskRuns = tasks.associate { t ->
            TaskDefinitionId(t) to TaskRun(TaskRunId("$id-$t"), TaskDefinitionId(t), TaskRunStatus.Running, assignedRoleId = engineer.id)
        },
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 1,
    )

    private fun failed(run: String, task: String, reason: String, at: Long): WorkflowEvent =
        VerificationFailed(WorkflowRunId(run), TaskDefinitionId(task), reason, at)

    private fun started(run: String, task: String, attempt: Int, at: Long): WorkflowEvent =
        TaskStarted(WorkflowRunId(run), TaskDefinitionId(task), attempt, at)

    /** Two tasks of the engineer failing the same check (differing only in a count). */
    private fun recurringFailure(): List<RoleObservation> = RoleInstructionObservations.fromRun(
        run("r1", "a", "b"),
        listOf(
            started("r1", "a", 1, 10),
            failed("r1", "a", "Unit tests failed: 3 failures", 11),
            started("r1", "b", 1, 20),
            failed("r1", "b", "unit tests failed:  5 failures", 21),
        ),
    )

    @Test
    fun aRecurringFailureYieldsAPendingProposalWithItsEvidence() {
        val ledger = RoleInstructionProposalLedger()
        ledger.observe(recurringFailure(), roles, nowEpochMillis = 100)

        val pending = ledger.pendingFor(engineer.id)
        assertEquals(1, pending.size)
        val proposal = pending.single()
        assertEquals(engineer.instructions, proposal.baseInstructions)
        assertTrue(proposal.proposedInstructions.startsWith(engineer.instructions))
        assertTrue(RoleInstructionProposer.LESSONS_HEADER in proposal.proposedInstructions)
        val change = proposal.changes.single()
        assertEquals(RoleInstructionEvidenceKind.RepeatedFailure, change.kind)
        assertEquals(2, change.evidence.size)
        assertTrue("- ${change.line}" in proposal.proposedInstructions)
        assertTrue(proposal.diff().lines().any { it == "+- ${change.line}" })
        assertTrue(proposal.diff().lines().any { it == " ${engineer.instructions}" })
        // Nobody else's mind is touched, and a single failure is not a pattern.
        assertTrue(ledger.pendingFor(qa.id).isEmpty())
    }

    @Test
    fun aKeyInAFailureReasonOrKeeperMessageNeverReachesAProposedInstructionLine() {
        val key = "ghp_abcdefghijklmnopqrstuvwxyz0123456789"
        val observations = RoleInstructionObservations.fromRun(
            run("r1", "a", "b"),
            listOf(
                started("r1", "a", 1, 10),
                failed("r1", "a", "Deploy failed: Authorization: Bearer $key", 11),
                started("r1", "b", 1, 20),
                failed("r1", "b", "Deploy failed: Authorization: Bearer $key", 21),
            ),
        ) + RoleInstructionObservations.keeperMessage(engineer.id, "r1", "a", "no, use token=$key instead", 30)
        assertTrue(observations.none { key in it.detail }, observations.joinToString { it.detail })

        val ledger = RoleInstructionProposalLedger()
        ledger.observe(observations, roles, nowEpochMillis = 100)
        val text = ledger.pendingFor(engineer.id).joinToString { it.proposedInstructions }
        assertTrue(text.isNotEmpty())
        assertFalse(key in text)
    }

    @Test
    fun aSingleFailureProposesNothing() {
        val ledger = RoleInstructionProposalLedger()
        ledger.observe(
            RoleInstructionObservations.fromRun(run("r1", "a"), listOf(failed("r1", "a", "lint failed", 1))),
            roles,
            nowEpochMillis = 2,
        )
        assertTrue(ledger.state.value.proposals.isEmpty())
    }

    @Test
    fun repeatedFailuresThenAFixYieldAProposal() {
        val observations = RoleInstructionObservations.fromRun(
            run("r1", "a"),
            listOf(
                AgentAssigned(WorkflowRunId("r1"), TaskDefinitionId("a"), engineer.id, 1),
                started("r1", "a", 1, 2),
                failed("r1", "a", "missing migration", 3),
                started("r1", "a", 2, 4),
                failed("r1", "a", "schema mismatch", 5),
                started("r1", "a", 3, 6),
                TaskCompleted(WorkflowRunId("r1"), TaskDefinitionId("a"), 7),
            ),
        )
        val change = RoleInstructionProposer.changesFor(engineer.id, observations).single()
        assertEquals(RoleInstructionEvidenceKind.FailuresThenFix, change.kind)
        assertTrue("schema mismatch" in change.line)
        assertEquals(listOf(1, 2, 3), change.evidence.map { it.attempt })
    }

    @Test
    fun executorReceiptsOfFailuresThenCompletionYieldAProposal() {
        fun trace(attempt: Int, outcome: ProcedureTraceOutcome, at: Long) = ProcedureTrace(
            workflowId = "r1",
            projectId = "p",
            taskRunId = "r1-a",
            taskDefinitionId = "a",
            attempt = attempt,
            phase = ProcedureTracePhase.Reconcile,
            executorKind = "TestRunner",
            outcome = outcome,
            recordedAtEpochMillis = at,
        )
        val observations = RoleInstructionObservations.fromRun(
            run("r1", "a"),
            events = emptyList(),
            traces = listOf(
                trace(1, ProcedureTraceOutcome.Failed, 1),
                trace(2, ProcedureTraceOutcome.Threw, 2),
                trace(3, ProcedureTraceOutcome.Completed, 3),
            ),
        )
        val change = RoleInstructionProposer.changesFor(engineer.id, observations).single()
        assertEquals(RoleInstructionEvidenceKind.FailuresThenFix, change.kind)
        assertTrue("TestRunner" in change.line)
    }

    @Test
    fun repeatedKeeperCorrectionsYieldAProposalButQuestionsDoNot() {
        val question = RoleInstructionObservations.keeperMessage(engineer.id, "r1", "a", "How is it going?", 1)
        val first = RoleInstructionObservations.keeperMessage(engineer.id, "r1", "a", "Don't edit generated files.", 2)
        val ledger = RoleInstructionProposalLedger()
        ledger.observe(listOf(question, first), roles, nowEpochMillis = 3)
        assertTrue(ledger.pendingFor(engineer.id).isEmpty())

        val second = RoleInstructionObservations.keeperMessage(engineer.id, "r2", "b", "Always run the formatter first.", 4)
        ledger.observe(listOf(second), roles, nowEpochMillis = 5)
        val proposal = ledger.pendingFor(engineer.id).single()
        assertEquals(2, proposal.changes.size)
        assertTrue(proposal.changes.all { it.kind == RoleInstructionEvidenceKind.KeeperCorrection })
        assertTrue("Don't edit generated files." in proposal.proposedInstructions)
        assertFalse("How is it going?" in proposal.proposedInstructions)
    }

    @Test
    fun nothingIsAppliedWithoutApproval() {
        val saved = mutableListOf<String>()
        val ledger = RoleInstructionProposalLedger(save = { saved += it })
        val before = roles.toList()
        repeat(3) { ledger.observe(recurringFailure(), roles, nowEpochMillis = 100L + it) }

        // Observing again redrafts nothing and changes no role; the proposal just waits.
        assertEquals(before, roles)
        val pending = ledger.pendingFor(engineer.id).single()
        assertEquals(RoleInstructionProposalStatus.Pending, pending.status)
        assertEquals(1, ledger.state.value.proposals.size)
        assertTrue(saved.isNotEmpty())
    }

    @Test
    fun approvingAppliesTheProposalToThatRoleOnly() {
        val ledger = RoleInstructionProposalLedger()
        ledger.observe(recurringFailure(), roles, nowEpochMillis = 100)
        val proposal = ledger.pendingFor(engineer.id).single()

        val applied = assertIs<RoleInstructionApproval.Applied>(ledger.approve(proposal.id, roles))
        assertEquals(proposal.proposedInstructions, applied.role.instructions)
        assertEquals(proposal.proposedInstructions, applied.roles.single { it.id == engineer.id }.instructions)
        assertEquals(qa, applied.roles.single { it.id == qa.id })
        assertEquals(roles.map { it.id }, applied.roles.map { it.id })
        assertEquals(RoleInstructionProposalStatus.Approved, ledger.state.value.proposals.single().status)
        assertTrue(ledger.pendingFor(engineer.id).isEmpty())

        // Neither the saved role nor the not-yet-saved one brings the same lines back.
        ledger.observe(recurringFailure(), applied.roles, nowEpochMillis = 200)
        ledger.observe(recurringFailure(), roles, nowEpochMillis = 201)
        assertTrue(ledger.pendingFor(engineer.id).isEmpty())
        // A second approval of the same proposal does nothing.
        assertEquals(RoleInstructionApproval.NotFound, ledger.approve(proposal.id, roles))
    }

    @Test
    fun dismissingAppliesNothingAndIsNotProposedAgain() {
        val ledger = RoleInstructionProposalLedger()
        ledger.observe(recurringFailure(), roles, nowEpochMillis = 100)
        val proposal = ledger.pendingFor(engineer.id).single()

        assertTrue(ledger.dismiss(proposal.id))
        assertEquals(RoleInstructionProposalStatus.Dismissed, ledger.state.value.proposals.single().status)
        assertEquals(RoleInstructionApproval.NotFound, ledger.approve(proposal.id, roles))
        ledger.observe(recurringFailure(), roles, nowEpochMillis = 200)
        assertTrue(ledger.pendingFor(engineer.id).isEmpty())
        assertFalse(ledger.dismiss(proposal.id))
    }

    @Test
    fun aProposalForInstructionsThatChangedSinceIsNotApplied() {
        val ledger = RoleInstructionProposalLedger()
        ledger.observe(recurringFailure(), roles, nowEpochMillis = 100)
        val proposal = ledger.pendingFor(engineer.id).single()
        val edited = roles.map { if (it.id == engineer.id) it.copy(instructions = "Something new.") else it }

        assertEquals(RoleInstructionApproval.Stale, ledger.approve(proposal.id, edited))
        assertEquals(RoleInstructionProposalStatus.Superseded, ledger.state.value.proposals.single().status)
        // The next observation drafts against the current text.
        ledger.observe(recurringFailure(), edited, nowEpochMillis = 200)
        assertEquals("Something new.", ledger.pendingFor(engineer.id).single().baseInstructions)
    }

    @Test
    fun theQueueSurvivesARestart() {
        var stored: String? = null
        val first = RoleInstructionProposalLedger(load = { stored }, save = { stored = it })
        first.observe(recurringFailure(), roles, nowEpochMillis = 100)
        val proposal = first.pendingFor(engineer.id).single()

        val second = RoleInstructionProposalLedger(load = { stored }, save = { stored = it })
        assertEquals(proposal, second.pendingFor(engineer.id).single())
    }

    @Test
    fun linesAreAppendedUnderOneHeader() {
        val once = RoleInstructionProposer.applyLines("Base.", listOf("one"))
        assertEquals("Base.\n\n${RoleInstructionProposer.LESSONS_HEADER}\n- one", once)
        assertEquals("$once\n- two", RoleInstructionProposer.applyLines(once, listOf("two")))
        assertEquals("${RoleInstructionProposer.LESSONS_HEADER}\n- one", RoleInstructionProposer.applyLines("", listOf("one")))
    }

    @Test
    fun pendingCountCountsWaitingProposalsAcrossEveryRole() {
        fun proposal(id: String, role: RoleDefinition, status: RoleInstructionProposalStatus) =
            RoleInstructionProposal(id, role.id, "Base.", "Base.\n- x", emptyList(), 0L, status)
        val state = RoleInstructionProposalState(
            proposals = listOf(
                proposal("1", engineer, RoleInstructionProposalStatus.Pending),
                proposal("2", qa, RoleInstructionProposalStatus.Pending),
                proposal("3", qa, RoleInstructionProposalStatus.Dismissed),
                proposal("4", engineer, RoleInstructionProposalStatus.Superseded),
            ),
        )
        assertEquals(2, state.pendingCount)
        assertEquals(1, state.pendingFor(qa.id).size)
    }

    @Test
    fun aDecisionIsStampedWithWhenItWasMadeAndAnApprovalMarkedAfterTheSaveLeavesAFailedSavePending() {
        var clock = 500L
        val ledger = RoleInstructionProposalLedger(clock = { clock })
        ledger.observe(recurringFailure(), roles, nowEpochMillis = 100)
        val proposal = ledger.pendingFor(engineer.id).single()

        // Evaluating changes nothing: a save that then fails leaves the proposal pending, so it is proposed again later.
        val applied = assertIs<RoleInstructionApproval.Applied>(ledger.evaluate(proposal.id, roles))
        assertEquals(proposal.proposedInstructions, applied.role.instructions)
        assertEquals(RoleInstructionProposalStatus.Pending, ledger.state.value.proposals.single().status)
        assertEquals(null, ledger.state.value.proposals.single().decidedAtEpochMillis)

        clock = 777L
        assertTrue(ledger.markApproved(proposal.id))
        val approved = ledger.state.value.proposals.single()
        assertEquals(RoleInstructionProposalStatus.Approved, approved.status)
        assertEquals(777L, approved.decidedAtEpochMillis)
        assertFalse(ledger.markApproved(proposal.id), "once")
    }

    @Test
    fun aRolesLessonsSectionDoesNotGrowPastItsCapAsProposalsAreAdopted() {
        val ledger = RoleInstructionProposalLedger()
        // Lessons adopted earlier have already filled the section to within a line of its cap.
        val lessons = RoleInstructionProposer.LESSONS_HEADER + "\n" + "- an earlier lesson ".repeat(95).trim()
        val full = engineer.copy(instructions = engineer.instructions + "\n\n" + lessons)
        assertTrue(RoleInstructionProposer.lessonsChars(full.instructions) in 1_800..RoleInstructionProposer.MAX_LESSONS_CHARS)

        ledger.observe(recurringFailure(), listOf(full, qa), nowEpochMillis = 100)

        assertTrue(ledger.pendingFor(engineer.id).isEmpty(), "a new lesson that would take the section past its cap is not proposed")

        // A role with room still gets it, and what is proposed never takes the section past the cap.
        ledger.observe(recurringFailure(), roles, nowEpochMillis = 200)
        val proposal = ledger.pendingFor(engineer.id).single()
        assertTrue(RoleInstructionProposer.lessonsChars(proposal.proposedInstructions) <= RoleInstructionProposer.MAX_LESSONS_CHARS)
    }
}
