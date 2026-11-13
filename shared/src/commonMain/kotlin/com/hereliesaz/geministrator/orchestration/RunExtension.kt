package com.hereliesaz.geministrator.orchestration

import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.workflow.WorkflowGraphValidator
import com.hereliesaz.geministrator.workflow.humanReadable

/*
 * Extending a run that has begun (docs/Keepers-Room.md): a swarm that asked for it begins with the queen's plan step behind the
 * keeper's approval, and once the keeper approves, the staffed steps are added to the same run. The pure rules are here; the run is
 * changed by ApplicationRuntime.extendRun, which also puts the extension through the same validation a plan always meets
 * (OrchestrationPlan.validateAgainst, the requirements checklist's rules, WorkflowGraphValidator).
 *
 * A swarm opts in by having the plan-approval step, [RunExtension.GATE_STEP_ID], with the keeper's approval on it. Nothing else can be
 * extended, nothing added can be reached without that approval, and a step already in the run is never changed.
 */
object RunExtension {
    /** The id of the plan-approval step that opts a swarm in; every added step has it as an ancestor. */
    const val GATE_STEP_ID = "queen-plan-approval"

    /** The most steps one extension may add. */
    const val MAX_ADDED = 12

    /** The steps of [definition] as a plan reads them (the same reading the repair path uses); null when a step has no role. */
    fun stepsOf(definition: WorkflowDefinition): List<OrchestrationPlanStep>? =
        definition.tasks.map { task ->
            val role = task.roleId ?: return null
            OrchestrationPlanStep(
                id = task.id.value,
                name = task.name,
                objective = task.objective,
                roleId = role.value,
                dependsOn = task.dependsOn.map { it.value }.sorted(),
                requiresHumanApproval = task.approvalPolicy != ApprovalPolicy.None,
            )
        }

    /**
     * Why [extension] may not be added to a run whose steps are [current] (pure), or null. [gateApproved] is whether the keeper
     * has approved the plan-approval step. Refuses: a swarm that did not opt in; a gate with no approval on it or not yet approved;
     * nothing to add, or too many; an id already used; a dependency that is not in the run or the extension; a step the plan-approval
     * step does not come before.
     */
    fun refusal(current: List<OrchestrationPlanStep>, extension: List<OrchestrationPlanStep>, gateApproved: Boolean): String? {
        val gate = current.firstOrNull { it.id == GATE_STEP_ID }
            ?: return "This swarm did not ask for the queen to plan inside the run."
        if (!gate.requiresHumanApproval) return "The plan-approval step has no approval on it."
        if (!gateApproved) return "The keeper has not approved the plan yet."
        if (extension.isEmpty()) return "There is nothing to add."
        if (extension.size > MAX_ADDED) return "At most $MAX_ADDED steps can be added at once."
        val used = current.map { it.id }.toSet()
        val added = extension.map { it.id }
        if (added.size != added.toSet().size || added.any { it in used }) return "A step id is already used."
        val all = (current + extension).associateBy { it.id }
        extension.firstOrNull { step -> step.dependsOn.any { it !in all } }?.let { return "Step ${it.id} depends on a step that is not there." }
        extension.firstOrNull { !comesAfterGate(it, all) }?.let { return "Step ${it.id} is not behind the plan approval." }
        return null
    }

    private fun comesAfterGate(step: OrchestrationPlanStep, all: Map<String, OrchestrationPlanStep>): Boolean {
        val seen = mutableSetOf<String>()
        fun walk(id: String): Boolean {
            if (id == GATE_STEP_ID) return true
            if (!seen.add(id)) return false
            return all[id]?.dependsOn?.any { walk(it) } == true
        }
        return step.dependsOn.any { walk(it) }
    }

    /**
     * The first rule of the requirements checklist that the extension breaks and the run's own steps did not already break (pure):
     * a step that can change something with no approval before it, a role not available, a step limit, a retired word. A rule the run
     * already failed before the extension is not held against it. Null when the extension introduces none.
     */
    fun introducedProblem(inputs: RequirementInputs, current: List<OrchestrationPlanStep>, extension: List<OrchestrationPlanStep>): String? {
        val checklist = Requirements.extract(inputs)
        val before = Requirements.checkPure(inputs, OrchestrationPlan(current), checklist)
            .filter { it.verdict == RequirementVerdict.Unmet }.map { it.id }.toSet()
        return Requirements.checkPure(inputs, OrchestrationPlan(current + extension), checklist)
            .firstOrNull { it.verdict == RequirementVerdict.Unmet && it.id !in before }
            ?.let { "${it.id}: ${it.evidence}" }
    }

    /** The result of adding steps: the run's definition with them appended, or why not. */
    sealed interface Appended {
        data class Ready(val definition: WorkflowDefinition, val added: List<TaskDefinition>) : Appended

        data class Refused(val reason: String) : Appended
    }

    /**
     * [current] with the [built] tasks named in [addedIds] appended, every task already in the run left exactly as it is. The graph
     * is checked as a whole, and a problem the extension introduces refuses it (nothing is applied).
     */
    fun append(current: WorkflowDefinition, built: WorkflowDefinition, addedIds: Set<String>): Appended {
        val added = built.tasks.filter { it.id.value in addedIds && current.tasks.none { old -> old.id == it.id } }
        if (added.size != addedIds.size) return Appended.Refused("A step to add is missing from the plan.")
        val merged = current.copy(tasks = current.tasks + added)
        val before = WorkflowGraphValidator.validate(current).toSet()
        val introduced = WorkflowGraphValidator.validate(merged).filterNot { it in before }
        if (introduced.isNotEmpty()) return Appended.Refused(introduced.joinToString(" ") { it.humanReadable() })
        return Appended.Ready(merged, added)
    }

}
