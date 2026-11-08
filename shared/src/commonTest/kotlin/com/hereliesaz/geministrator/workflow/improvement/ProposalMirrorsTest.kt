package com.hereliesaz.geministrator.workflow.improvement

import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.memory.procedural.FormedHabit
import com.hereliesaz.geministrator.memory.procedural.HabitStrength
import com.hereliesaz.geministrator.memory.procedural.RoleInstructionChange
import com.hereliesaz.geministrator.memory.procedural.RoleInstructionEvidenceKind
import com.hereliesaz.geministrator.memory.procedural.RoleInstructionProposal
import com.hereliesaz.geministrator.memory.procedural.RoleInstructionProposalState
import com.hereliesaz.geministrator.memory.procedural.RoleInstructionProposalStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProposalMirrorsTest {
    private fun roleProposal(id: String, role: String, status: RoleInstructionProposalStatus = RoleInstructionProposalStatus.Pending) = RoleInstructionProposal(
        id = id, roleId = RoleDefinitionId(role), baseInstructions = "Be careful.",
        proposedInstructions = "Be careful.\n\nLearned from past work:\n- Quote the failing command.",
        changes = listOf(RoleInstructionChange("- Quote the failing command.", RoleInstructionEvidenceKind.RepeatedFailure, emptyList())),
        createdAtEpochMillis = 10L, status = status,
    )

    private fun habit(operation: String, strength: HabitStrength = HabitStrength.Low, uses: Int = 4) = FormedHabit(
        executorKind = "GitHubAction", operation = operation, uses = uses, completedUses = uses - 1, failedUses = 1, verifiedUses = 0,
        rejectedUses = 0, slots = emptyList(), firstUsedAtEpochMillis = 1L, lastUsedAtEpochMillis = 2L, strength = strength,
    )

    @Test
    fun aPendingRoleProposalIsMirroredOnceWithItsSourceAndATargetThatMakesItAboutTheLoopWhenItIs() {
        val roles = RoleInstructionProposalState(proposals = listOf(roleProposal("rip-1", "qa-engineer"), roleProposal("rip-2", "hall-monitor")))

        val first = ProposalMirrors.roleHistory(ImprovementState(), roles, 100L)
        val again = ProposalMirrors.roleHistory(first, roles, 200L)

        assertEquals(first, again, "mirroring twice changes nothing")
        val qa = first.proposals.single { it.target == "qa-engineer" }
        assertEquals(ImprovementSource.RoleHistory, qa.source)
        assertEquals("rip-1", qa.sourceRef)
        assertEquals("- Quote the failing command.", qa.newText)
        assertTrue(!qa.meta)
        assertTrue(first.proposals.single { it.target == "hall-monitor" }.meta, "the Hall Monitor's own instructions are the loop's")
    }

    @Test
    fun aDecisionMadeOnTheMindTabIsFollowedAndAnOldDecidedProposalIsNotResurrected() {
        val pending = RoleInstructionProposalState(proposals = listOf(roleProposal("rip-1", "qa-engineer")))
        val mirrored = ProposalMirrors.roleHistory(ImprovementState(), pending, 100L)

        val approved = ProposalMirrors.roleHistory(mirrored, RoleInstructionProposalState(proposals = listOf(roleProposal("rip-1", "qa-engineer", RoleInstructionProposalStatus.Approved))), 300L)
        val p = approved.proposals.single()
        assertEquals(ProposalStatus.Adopted, p.status)
        assertEquals("Be careful.", p.appliedBefore)
        assertTrue(p.appliedAfter!!.contains("Quote the failing command"))
        assertTrue(ImprovementApplier.canRevert(p, p.appliedAfter), "and it can be reverted from here")

        val dismissed = ProposalMirrors.roleHistory(mirrored, RoleInstructionProposalState(proposals = listOf(roleProposal("rip-1", "qa-engineer", RoleInstructionProposalStatus.Dismissed))), 300L)
        assertEquals(ProposalStatus.Dismissed, dismissed.proposals.single().status)
        val superseded = ProposalMirrors.roleHistory(mirrored, RoleInstructionProposalState(proposals = listOf(roleProposal("rip-1", "qa-engineer", RoleInstructionProposalStatus.Superseded))), 300L)
        assertEquals(ProposalStatus.Superseded, superseded.proposals.single().status)

        val old = ProposalMirrors.roleHistory(ImprovementState(), RoleInstructionProposalState(proposals = listOf(roleProposal("rip-9", "qa-engineer", RoleInstructionProposalStatus.Dismissed))), 300L)
        assertEquals(emptyList(), old.proposals, "a proposal that was already decided before the ledger saw it is history, not a proposal")
    }

    @Test
    fun aFormedHabitIsOfferedOnceAndAFormingOneNeverAndADecisionSticks() {
        val habits = listOf("bank" to habit("github-action:ci.yml"), "bank" to habit("deployment:production", HabitStrength.Forming, uses = 1))

        val offered = ProposalMirrors.habits(ImprovementState(), habits, 100L)

        val p = offered.proposals.single()
        assertEquals(ImprovementKind.HabitTask, p.kind)
        assertEquals(ImprovementSource.Habit, p.source)
        assertEquals("bank", p.sourceRef)
        assertEquals("github-action:ci.yml", p.target)
        assertNotNull(p.evidence.takeIf { "4 uses" in it })
        val dismissed = offered.copy(proposals = offered.proposals.map { it.copy(status = ProposalStatus.Dismissed) })
        assertEquals(dismissed, ProposalMirrors.habits(dismissed, habits, 200L), "a dismissed habit is not offered again")
    }
}
