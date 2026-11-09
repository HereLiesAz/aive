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

    /**
     * One habit is one proposal, however many banks (workflows) can see it: a bank also reads its neighbours' runs,
     * so keying by bank offered the same operation once per bank. Ledgers written before that keep their
     * `habit:<bank>:<operation>` ids; [habits] finds those by their target, not their id.
     */
    fun habitId(operation: String) = "habit:$operation"

    /** A habit offered while this share of its uses failed is mostly a habit of failing, and is not offered as a step. */
    const val MAX_HABIT_FAILURE_SHARE = 1.0 / 3.0

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
                        status = ProposalStatus.Adopted, decidedAtEpochMillis = p.decidedAtEpochMillis ?: nowEpochMillis,
                        appliedBefore = p.baseInstructions, appliedAfter = p.proposedInstructions,
                        note = "Approved on the creature's Mind tab.",
                    )
                    RoleInstructionProposalStatus.Dismissed -> existing.copy(status = ProposalStatus.Dismissed, decidedAtEpochMillis = p.decidedAtEpochMillis ?: nowEpochMillis, note = "Dismissed on the creature's Mind tab.")
                    RoleInstructionProposalStatus.Superseded -> existing.copy(
                        status = ProposalStatus.Superseded, decidedAtEpochMillis = p.decidedAtEpochMillis ?: nowEpochMillis,
                        note = "The role changed, or new evidence replaced it.",
                    )
                }
                byId[id] = next
            }
        }
        return current.copy(proposals = order.mapNotNull { byId[it] })
    }

    /**
     * [current] with each formed habit offered once as a step to add to a hive; one already offered (by any bank, under
     * any id the ledger has used) is never offered again, and one that mostly failed is not offered at all.
     */
    fun habits(current: ImprovementState, habits: List<Pair<String, FormedHabit>>, nowEpochMillis: Long): ImprovementState {
        val have = current.proposals.filter { it.source == ImprovementSource.Habit }.mapTo(hashSetOf()) { it.target }
        val added = habits.mapNotNull { (bank, habit) ->
            // Only a habit used often enough to be offered; one still forming is not a proposal.
            if (habit.strength == HabitStrength.Forming) return@mapNotNull null
            // "Done N times" must not describe an operation that mostly failed.
            if (habit.failedUses > habit.uses * MAX_HABIT_FAILURE_SHARE) return@mapNotNull null
            if (!have.add(habit.operation)) return@mapNotNull null
            val id = habitId(habit.operation)
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
