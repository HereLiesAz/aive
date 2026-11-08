package com.hereliesaz.geministrator.workflow.improvement

import com.hereliesaz.geministrator.memory.procedural.FormedHabit
import com.hereliesaz.geministrator.memory.procedural.HabitStrength
import com.hereliesaz.geministrator.memory.procedural.RoleInstructionProposalState
import com.hereliesaz.geministrator.memory.procedural.RoleInstructionProposalStatus

/**
 * Keeps the one ledger in step with the other two mechanisms that propose (docs/architecture/SELF_IMPROVEMENT.md
 * §3): the role-instruction proposals made from run history, and the habits the swarm formed. Each is
 * mirrored in as an [ImprovementProposal] with its [ImprovementSource], so all three are read and decided in
 * one place, and the loop reads them as evidence. A mirror only copies what the mechanism already decided
 * or proposed: it never decides anything, and the mechanism stays the place that makes its own proposals.
 * Pure.
 */
object ProposalMirrors {
    private const val EVIDENCE_CHARS = 600

    fun roleHistoryId(proposalId: String) = "role-history:$proposalId"

    fun habitId(bank: String, operation: String) = "habit:$bank:$operation"

    /** [current] with the role-instruction proposals of [roles] mirrored in, and every mirrored one's status brought up to date. */
    fun roleHistory(current: ImprovementState, roles: RoleInstructionProposalState, nowEpochMillis: Long): ImprovementState {
        val byId = current.proposals.associateBy { it.id }.toMutableMap()
        val order = current.proposals.map { it.id }.toMutableList()
        roles.proposals.forEach { p ->
            val id = roleHistoryId(p.id)
            val existing = byId[id]
            if (existing == null) {
                if (p.status != RoleInstructionProposalStatus.Pending) return@forEach
                val evidence = p.changes.joinToString("; ") { c ->
                    c.kind.name + ": " + c.evidence.take(3).joinToString(" / ") { o -> o.detail.ifBlank { o.kind.name } }
                }
                byId[id] = ImprovementProposal(
                    id = id, cycleRunId = "role-history", kind = ImprovementKind.RoleInstruction, target = p.roleId.value,
                    summary = "Add ${p.changes.size} lesson${if (p.changes.size == 1) "" else "s"} to ${p.roleId.value}'s instructions",
                    newText = p.changes.joinToString("\n") { it.line }.take(1_200),
                    expectedEffect = "Fewer repeats of the same failures; measure the role's failed and retried steps over the next seven days",
                    undo = "Revert restores the instructions as they were",
                    meta = ImprovementParser.isMeta(ImprovementKind.RoleInstruction, p.roleId.value),
                    createdAtEpochMillis = p.createdAtEpochMillis,
                    source = ImprovementSource.RoleHistory, sourceRef = p.id, evidence = evidence.take(EVIDENCE_CHARS),
                    appliedBefore = null,
                )
                order += id
            } else if (existing.status == ProposalStatus.Proposed) {
                // Decided, or replaced, in the mechanism itself (the Mind tab): follow it.
                val next = when (p.status) {
                    RoleInstructionProposalStatus.Pending -> existing
                    RoleInstructionProposalStatus.Approved -> existing.copy(
                        status = ProposalStatus.Adopted, decidedAtEpochMillis = nowEpochMillis,
                        appliedBefore = p.baseInstructions, appliedAfter = p.proposedInstructions,
                        note = "Approved on the creature's Mind tab.",
                    )
                    RoleInstructionProposalStatus.Dismissed -> existing.copy(status = ProposalStatus.Dismissed, decidedAtEpochMillis = nowEpochMillis, note = "Dismissed on the creature's Mind tab.")
                    RoleInstructionProposalStatus.Superseded -> existing.copy(
                        status = ProposalStatus.Superseded, decidedAtEpochMillis = nowEpochMillis,
                        note = "The role changed, or new evidence replaced it.",
                    )
                }
                byId[id] = next
            }
        }
        return current.copy(proposals = order.mapNotNull { byId[it] })
    }

    /** [current] with each formed habit offered once as a step to add to a hive; one already offered is never offered again. */
    fun habits(current: ImprovementState, habits: List<Pair<String, FormedHabit>>, nowEpochMillis: Long): ImprovementState {
        val have = current.proposals.mapTo(hashSetOf()) { it.id }
        val added = habits.mapNotNull { (bank, habit) ->
            // Only a habit used often enough to be offered; one still forming is not a proposal.
            if (habit.strength == HabitStrength.Forming) return@mapNotNull null
            val id = habitId(bank, habit.operation)
            if (id in have) return@mapNotNull null
            have += id
            ImprovementProposal(
                id = id, cycleRunId = "habits", kind = ImprovementKind.HabitTask, target = habit.operation,
                summary = "Make ${habit.operation} a step in a hive: done ${habit.uses} times",
                newText = "",
                expectedEffect = "The operation becomes a governed step with an approval in front of it instead of being done by hand; measure its uses outside a hive",
                undo = "Remove the added step, and the approval step in front of it, from the hive",
                meta = false, createdAtEpochMillis = nowEpochMillis,
                source = ImprovementSource.Habit, sourceRef = bank,
                evidence = "${habit.uses} uses (${habit.completedUses} completed, ${habit.failedUses} failed), last used ${habit.lastUsedAtEpochMillis}.".take(EVIDENCE_CHARS),
            )
        }
        return if (added.isEmpty()) current else current.copy(proposals = current.proposals + added)
    }
}
