package com.hereliesaz.geministrator.workflow.improvement

import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinition

/**
 * What adopting a proposal would change, worked out purely so the keeper can see the exact text before
 * approving it. It takes **no account of whether the proposal is about the loop**: the loop's own hive, role
 * and schedule go through the same code as everything else (the amendment clause, SELF_IMPROVEMENT.md §4).
 */
object ImprovementApplier {
    sealed interface Plan {
        /** Replace a role's instructions. */
        data class RoleInstructions(val roleId: String, val before: String, val after: String) : Plan

        /** Replace one creature's objective in a hive. */
        data class HiveStepObjective(val hiveId: String, val taskId: String, val before: String, val after: String) : Plan

        /** Nothing the app can do by itself: it is recorded as noted and the keeper does it by hand. */
        data class Manual(val why: String) : Plan

        /** The proposal cannot be applied as written (target not found, no text). Says why; never about who it targets. */
        data class Cannot(val why: String) : Plan
    }

    const val ADOPTED_MARK = "Adopted improvement:"

    /**
     * The most characters adopted improvements may add to one role's instructions in total (everything from the first
     * [ADOPTED_MARK] on), so a role's prompt does not grow without limit as proposals are adopted over time.
     */
    const val MAX_ADOPTED_CHARS = 4_000

    /** The characters adopted improvements have added to [instructions] so far. */
    fun adoptedChars(instructions: String): Int {
        val at = instructions.indexOf("\n\n$ADOPTED_MARK")
        return if (at < 0) 0 else instructions.length - at
    }

    fun plan(proposal: ImprovementProposal, roles: Collection<RoleDefinition>, hives: Collection<WorkflowDefinition>): Plan = when (proposal.kind) {
        ImprovementKind.RoleInstruction -> {
            val role = roles.firstOrNull { it.id.value == proposal.target }
            when {
                proposal.newText.isBlank() -> Plan.Cannot("The proposal has no text to add.")
                role == null -> Plan.Cannot("No role '${proposal.target}' is installed.")
                else -> {
                    val after = role.instructions.trimEnd() + "\n\n$ADOPTED_MARK ${proposal.newText}"
                    if (adoptedChars(after) > MAX_ADOPTED_CHARS) {
                        Plan.Cannot("Adopted improvements already add ${adoptedChars(role.instructions)} characters to '${role.id.value}'; this would take it past $MAX_ADOPTED_CHARS. Remove some of what was added first.")
                    } else {
                        Plan.RoleInstructions(role.id.value, role.instructions, after)
                    }
                }
            }
        }
        ImprovementKind.HiveStep, ImprovementKind.LoopDefinition -> {
            val hiveId = proposal.target.substringBefore('/')
            val taskId = proposal.target.substringAfter('/', "")
            val hive = hives.firstOrNull { it.id.value == hiveId }
            val task = hive?.tasks?.firstOrNull { it.id.value == taskId }
            when {
                proposal.newText.isBlank() -> Plan.Cannot("The proposal has no text.")
                taskId.isEmpty() -> Plan.Manual("It names a hive, not one of its steps (<hive id>/<task id>); change it by hand.")
                hive == null -> Plan.Cannot("No hive '$hiveId' is on this device.")
                task == null -> Plan.Cannot("Hive '$hiveId' has no step '$taskId'.")
                else -> Plan.HiveStepObjective(hiveId, taskId, task.objective, proposal.newText)
            }
        }
        ImprovementKind.HabitTask -> Plan.Manual("Choose the hive to add it to.")
        ImprovementKind.Schedule, ImprovementKind.EvidenceRule, ImprovementKind.Other ->
            Plan.Manual("The app does not apply this kind by itself; it is recorded as noted, and you change it by hand.")
    }

    /**
     * What the card shows beside the exact new text: the text it replaces and, for the loop's own hive, what the
     * change would break. Null for a proposal that is not a replacement of one step's objective.
     */
    data class Preview(val before: String, val after: String, val loopViolations: List<String>)

    fun preview(proposal: ImprovementProposal, roles: Collection<RoleDefinition>, hives: Collection<WorkflowDefinition>): Preview? {
        val plan = plan(proposal, roles, hives) as? Plan.HiveStepObjective ?: return null
        return Preview(plan.before, plan.after, loopViolations(hives.first { it.id.value == plan.hiveId }, plan, roles))
    }

    /** What the loop's integrity ([LoopIntegrity]) says of [hive] once [plan] is applied; empty for any hive that is not the loop. */
    fun loopViolations(hive: WorkflowDefinition, plan: Plan.HiveStepObjective, roles: Collection<RoleDefinition>): List<String> =
        if (hive.id.value == SelfImprovementLoop.HIVE_ID) LoopIntegrity.violations(applyTo(hive, plan), roles) else emptyList()

    /** The hive with [plan]'s step objective replaced. */
    fun applyTo(hive: WorkflowDefinition, plan: Plan.HiveStepObjective): WorkflowDefinition =
        hive.copy(tasks = hive.tasks.map { if (it.id.value == plan.taskId) it.copy(objective = plan.after) else it })

    /** Whether the text is still as it was when adopted, so a revert will not undo a later edit. */
    fun canRevert(proposal: ImprovementProposal, currentText: String?): Boolean =
        proposal.status == ProposalStatus.Adopted && proposal.appliedAfter != null && currentText == proposal.appliedAfter
}
