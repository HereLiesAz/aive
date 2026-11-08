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

    fun plan(proposal: ImprovementProposal, roles: Collection<RoleDefinition>, hives: Collection<WorkflowDefinition>): Plan = when (proposal.kind) {
        ImprovementKind.RoleInstruction -> {
            val role = roles.firstOrNull { it.id.value == proposal.target }
            when {
                proposal.newText.isBlank() -> Plan.Cannot("The proposal has no text to add.")
                role == null -> Plan.Cannot("No role '${proposal.target}' is installed.")
                else -> Plan.RoleInstructions(role.id.value, role.instructions, role.instructions.trimEnd() + "\n\n$ADOPTED_MARK ${proposal.newText}")
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
        ImprovementKind.Schedule, ImprovementKind.EvidenceRule, ImprovementKind.Other ->
            Plan.Manual("The app does not apply this kind by itself; it is recorded as noted, and you change it by hand.")
    }

    /** The hive with [plan]'s step objective replaced. */
    fun applyTo(hive: WorkflowDefinition, plan: Plan.HiveStepObjective): WorkflowDefinition =
        hive.copy(tasks = hive.tasks.map { if (it.id.value == plan.taskId) it.copy(objective = plan.after) else it })

    /** Whether the text is still as it was when adopted, so a revert will not undo a later edit. */
    fun canRevert(proposal: ImprovementProposal, currentText: String?): Boolean =
        proposal.status == ProposalStatus.Adopted && proposal.appliedAfter != null && currentText == proposal.appliedAfter
}
