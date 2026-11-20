package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.EnvironmentPlanningPolicy
import com.hereliesaz.geministrator.domain.EscalationPolicy
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
        /** Resolves the version a templated step is made at; the built-in version when null. */
        templates: com.hereliesaz.geministrator.template.TemplateCatalog? = null,
    ): WorkflowDefinition {
        val validated = plan.validateAgainst(packet)
        val rolesById = roles.associateBy { it.id.value }
        val stepIds = validated.steps.associate { it.id to TaskDefinitionId(it.id) }
        // What the queen assigned each creature to work from: read leniently, bounded, and only to steps that exist.
        val stepResources: (com.hereliesaz.geministrator.orchestration.OrchestrationPlanStep) -> List<com.hereliesaz.geministrator.domain.TaskResource> = { planStep ->
            com.hereliesaz.geministrator.domain.TaskResources.fromJson(planStep.resources) { written ->
                stepIds[written]?.takeIf { it.value != planStep.id }
            }
        }
        return WorkflowDefinition(
            id = id,
            name = objective.take(80),
            description = "Workflow planned by Aive's local orchestration planner.",
            tasks = withTutorialDocs(objective, validated.steps.map { step ->
                val templateId = step.templateId
                if (templateId != null) {
                    val template = requireNotNull(com.hereliesaz.geministrator.template.BuiltInTemplates.byId[templateId]) {
                        "Planner selected unknown template $templateId"
                    }
                    val base = templates?.current(com.hereliesaz.geministrator.template.TemplateKind.Step, templateId)
                        ?: template.base()
                    val made = com.hereliesaz.geministrator.template.StepInstantiation.task(
                        template, base, stepIds.getValue(step.id), step.name, step.templateInputs,
                    )
                    requireNotNull(rolesById[made.roleId?.value]) { "No role is installed for template $templateId" }
                    return@map made.copy(
                        dependsOn = made.dependsOn + step.dependsOn.map { stepIds.getValue(it) },
                        approvalPolicy = if (step.requiresHumanApproval) ApprovalPolicy.HumanApproval else made.approvalPolicy,
                        environmentPlanningPolicy = EnvironmentPlanningPolicy.NotRequired,
                        resources = stepResources(step),
                    )
                }
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
                    resources = stepResources(step),
                    optional = teachingReview(objective, step.roleId),
                    escalationPolicy = teachingEscalation(objective, step.roleId),
                )
            }),
            testDesignPolicy = TestDesignPolicy.BeforeAndAfterImplementation,
        )
    }

    /**
     * In a swarm that teaches the app, a creature that only reviews is optional: a reviewer that rejects (or cannot judge) skips itself
     * and the swarm goes on, instead of failing the whole swarm, which is what ended the first hive twice. The lessons stay required.
     */
    internal fun teachingReview(objective: String, roleId: String): Boolean =
        com.hereliesaz.geministrator.intro.TutorialRouter.isTutorialGoal(objective) && roleId.lowercase().contains("review")

    /**
     * In a swarm that teaches the app, a lesson that cannot be done does not fail the swarm: it waits as an escalation, and the queen
     * rebuilds the remainder herself ([mendTeachingSwarms]). Every other swarm keeps failing the swarm, and a reviewing creature
     * skips itself ([teachingReview]).
     */
    internal fun teachingEscalation(objective: String, roleId: String): EscalationPolicy =
        if (com.hereliesaz.geministrator.intro.TutorialRouter.isTutorialGoal(objective) && !teachingReview(objective, roleId)) {
            EscalationPolicy.RequireHumanDecision
        } else {
            EscalationPolicy.FailWorkflow
        }

    /**
     * A safety net only: a plan for a goal to be taught the app that gave no creature the app's docs gets them on its first
     * research-like creature (else its first). The planner is told to assign them itself; nothing else is special-cased.
     */
    internal fun withTutorialDocs(objective: String, tasks: List<TaskDefinition>): List<TaskDefinition> {
        if (tasks.isEmpty() || !com.hereliesaz.geministrator.intro.TutorialRouter.isTutorialGoal(objective)) return tasks
        if (tasks.any { task -> com.hereliesaz.geministrator.domain.TaskResources.hasAiveDocs(task.resources) }) return tasks
        val target = tasks.firstOrNull { task ->
            val role = task.roleId?.value.orEmpty().lowercase()
            role.contains("research") || role.contains("survey")
        } ?: tasks.first()
        return tasks.map { task ->
            if (task.id == target.id) {
                task.copy(resources = com.hereliesaz.geministrator.domain.TaskResources.bounded(task.resources + com.hereliesaz.geministrator.domain.TaskResource.AiveDocs()))
            } else {
                task
            }
        }
    }
}
