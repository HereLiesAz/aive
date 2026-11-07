package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskCondition
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.effectiveExecutor
import com.hereliesaz.geministrator.orchestration.OrchestrationPacket
import com.hereliesaz.geministrator.orchestration.OrchestrationPlan
import com.hereliesaz.geministrator.orchestration.OrchestrationPlanStep
import com.hereliesaz.geministrator.workflow.OrchestratedWorkflowFactory

/*
 * A saved hive with an Orchestrator in it is a rough design (docs/Keepers-Room.md, the plan reviewed): the
 * keeper drew what they want done, and the Orchestrator still decides exactly how best to carry it out. So
 * when it is launched, the Orchestrator plans from that design before any run exists, the reviewer reads the
 * plan, and the keeper has the final say, exactly as for an objective typed into Feed it. The run is of the
 * planned hive; the saved design is left as drawn.
 */

/** A saved hive's rough design: the Orchestrators in it, and its other tasks as the plan steps they are. */
internal class RoughDesign(
    val orchestratorIds: Set<TaskDefinitionId>,
    val steps: List<OrchestrationPlanStep>,
)

/**
 * The rough design in [definition], or null when it is not one the planner can carry: it has no Orchestrator,
 * or it holds anything but agents' tasks (a script, an action, a repository change, a person's approval task, a
 * test run), a task that waits on an outcome rather than success, or an approval other than the keeper's. A plan
 * is only agents' steps, so planning such a hive would silently drop the rest; it launches as it always did.
 */
internal fun savedHiveRoughDesign(definition: WorkflowDefinition): RoughDesign? {
    val orchestratorIds = definition.tasks.filter { it.agentRoleId() == BuiltInRoles.Orchestrator.id }.mapTo(linkedSetOf()) { it.id }
    if (orchestratorIds.isEmpty()) return null
    val steps = mutableListOf<OrchestrationPlanStep>()
    for (task in definition.tasks) {
        if (task.id in orchestratorIds) continue
        val role = task.agentRoleId() ?: return null
        if (task.condition != TaskCondition.Always) return null
        val needsKeeper = when (task.approvalPolicy) {
            ApprovalPolicy.None -> false
            ApprovalPolicy.HumanApproval -> true
            else -> return null
        }
        steps += OrchestrationPlanStep(
            id = task.id.value,
            name = task.name,
            objective = task.objective,
            roleId = role.value,
            dependsOn = task.dependsOn.filter { it !in orchestratorIds }.map { it.value },
            requiresHumanApproval = needsKeeper,
        )
    }
    return RoughDesign(orchestratorIds, steps)
}

/** The role an agent's task is done by; null for any other kind of task. */
private fun TaskDefinition.agentRoleId(): RoleDefinitionId? =
    runCatching { effectiveExecutor() }.getOrNull()?.let { (it as? TaskExecutor.RoleAgent)?.roleId }

/** What the Orchestrator is asked of a rough design. */
internal const val ROUGH_DESIGN_INSTRUCTION =
    "A keeper drew a rough design, in currentPlan (it may be empty). Decide exactly how best to carry out the objective: " +
        "keep the steps that are sound, change, add or remove steps so the plan is executable, and keep every dependency valid. " +
        "Return the whole plan."

/**
 * The hive to run: [design] with its tasks replaced by [plan]'s, under a new id so the saved design stays as drawn.
 * A step that kept a task's id keeps everything else about that task (retries, escalation, provider choice, its
 * environment); a new step is made the way any planned step is. The Orchestrator task has done its work and is not in it.
 */
internal fun plannedHiveDefinition(
    design: WorkflowDefinition,
    plan: OrchestrationPlan,
    packet: OrchestrationPacket,
    roles: Collection<RoleDefinition>,
    id: WorkflowDefinitionId,
): WorkflowDefinition {
    val planned = OrchestratedWorkflowFactory.create(id, design.name, plan, packet, roles)
    // The Orchestrator has done its work: a step that reuses its id is a new step, not that task.
    val original = design.tasks.filter { it.agentRoleId() != BuiltInRoles.Orchestrator.id }.associateBy { it.id.value }
    val tasks = planned.tasks.map { task ->
        val kept = original[task.id.value] ?: return@map task
        kept.copy(
            name = task.name,
            objective = task.objective,
            roleId = task.roleId,
            executor = task.executor,
            dependsOn = task.dependsOn,
            approvalPolicy = if (task.approvalPolicy == ApprovalPolicy.HumanApproval) ApprovalPolicy.HumanApproval else kept.approvalPolicy,
        )
    }
    return design.copy(id = id, tasks = tasks)
}
