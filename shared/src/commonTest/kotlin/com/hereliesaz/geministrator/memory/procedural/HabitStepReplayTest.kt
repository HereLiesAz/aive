package com.hereliesaz.geministrator.memory.procedural

import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.TaskExecutor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HabitStepReplayTest {
    private fun habit(operation: String = "github-action:ci.yml", verified: Int = 3, rejected: Int = 0, version: Int = 1) = FormedHabit(
        executorKind = "GitHubAction", operation = operation, uses = 3, completedUses = 3, failedUses = 0,
        verifiedUses = verified, rejectedUses = rejected, slots = emptyList(), firstUsedAtEpochMillis = 1, lastUsedAtEpochMillis = 2,
        strength = HabitStrength.Low, version = version,
    )

    private fun approved(vararg ops: String) = ReplayLedgerState(approvedOperations = ops.toList())

    @Test
    fun anUnapprovedHabitProposesNothing() {
        assertTrue(HabitStepReplay.plan(listOf(habit()), emptyList(), "Run the ci build", ReplayLedgerState()).isEmpty())
    }

    @Test
    fun anApprovedVerifiedHabitProposesAnOrdinaryGatedStep() {
        val tasks = HabitStepReplay.plan(listOf(habit()), emptyList(), "Run the ci build", approved("github-action:ci.yml@1"))
        val task = tasks.single()
        assertEquals(TaskExecutor.GitHubAction("ci.yml"), task.executor)
        // The step asks for a person; adding it to a workflow puts a human-approval task in front of it.
        assertEquals(ApprovalPolicy.HumanApproval, task.approvalPolicy)
    }

    @Test
    fun theEligibilityMatrixEachFalseConditionMeansNothingIsProposed() {
        val ok = approved("github-action:ci.yml@1")
        fun plan(h: FormedHabit, s: ReplayLedgerState = ok) = HabitStepReplay.plan(listOf(h), emptyList(), "Run the ci build", s)
        assertEquals(1, plan(habit()).size)
        assertTrue(plan(habit(verified = 2)).isEmpty())
        assertTrue(plan(habit(rejected = 1)).isEmpty())
        // A new version needs a new approval.
        assertTrue(plan(habit(version = 2)).isEmpty())
        // Quarantined.
        assertTrue(plan(habit(), ok.copy(quarantinedOperations = listOf("github-action:ci.yml"))).isEmpty())
        assertTrue(plan(habit().copy(executorKind = "Distributed")).isEmpty())
        assertTrue(plan(habit().copy(strength = HabitStrength.Forming)).isEmpty())
    }

    @Test
    fun aSequenceBecomesChainedGatedStepsOnlyWhenEveryStepIsApproved() {
        val a = habit("github-action:ci.yml")
        val b = habit("deployment:staging")
        val sequence = FormedSequence(listOf(a.operation, b.operation), runs = 3, cleanRuns = 3, lastSeenAtEpochMillis = 1, offered = true)
        val both = approved("github-action:ci.yml@1", "deployment:staging@1")
        val tasks = HabitStepReplay.plan(listOf(a, b), listOf(sequence), "Run the ci build", both)
        assertEquals(2, tasks.size)
        assertEquals(setOf(tasks[0].id), tasks[1].dependsOn)
        // With one step unapproved the sequence is not proposed; the approved habit alone still is.
        val one = HabitStepReplay.plan(listOf(a, b), listOf(sequence), "Run the ci build", approved("github-action:ci.yml@1"))
        assertEquals(1, one.size)
    }
}
