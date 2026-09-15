package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.EnvironmentPlanningPolicy
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TestDesignPolicy
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.orchestration.OrchestrationPlan
import com.hereliesaz.geministrator.orchestration.OrchestrationPacket
import com.hereliesaz.geministrator.orchestration.validateAgainst

object OrchestratedWorkflowFactory {
    fun create(
        id: WorkflowDefinitionId,
        objective: String,
        plan: OrchestrationPlan,
        packet: OrchestrationPacket,
        roles: Collection<RoleDefinition>,
    ): WorkflowDefinition {
        val validated = plan.validateAgainst(packet)
        val rolesById = roles.associateBy { it.id.value }
        val stepIds = validated.steps.associate { it.id to TaskDefinitionId(it.id) }
        return WorkflowDefinition(
            id = id,
            name = objective.take(80),
            description = "Workflow planned by Haive's local orchestration planner.",
            tasks = validated.steps.map { step ->
                val role = requireNotNull(rolesById[step.roleId]) {
                    "Planner selected unknown role ${step.roleId}"
                }
                TaskDefinition(
                    id = stepIds.getValue(step.id),
                    name = step.name,
                    objective = step.objective,
                    roleId = RoleDefinitionId(step.roleId),
                    executor = TaskExecutor.RoleAgent(role.id),
                    dependsOn = step.dependsOn.mapTo(mutableSetOf()) { dependency ->
                        stepIds.getValue(dependency)
                    },
                    approvalPolicy = if (step.requiresHumanApproval) {
                        ApprovalPolicy.HumanApproval
                    } else {
                        ApprovalPolicy.None
                    },
                    environmentPlanningPolicy = EnvironmentPlanningPolicy.NotRequired,
                )
            },
            testDesignPolicy = TestDesignPolicy.BeforeAndAfterImplementation,
        )
    }
}
