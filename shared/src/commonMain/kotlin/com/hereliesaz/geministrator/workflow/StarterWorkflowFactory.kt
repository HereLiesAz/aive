package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.EnvironmentPlanningPolicy
import com.hereliesaz.geministrator.domain.IntegrationPolicy
import com.hereliesaz.geministrator.domain.RoleExecutionSource
import com.hereliesaz.geministrator.domain.RoleAuthority
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskResource
import com.hereliesaz.geministrator.domain.TestDesignPolicy
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.intro.TutorialRouter
import com.hereliesaz.geministrator.template.StepTemplateAssignments

object StarterWorkflowFactory {
    /**
     * Repository-free objectives and lessons use a generic worker; the coding starter remains available
     * for a linked repository. The live caller supplies the actual linkage, not a provider capability.
     * [repositoryLinked] defaults to true for callers constructing the existing coding starter directly.
     */
    fun create(
        id: WorkflowDefinitionId,
        objective: String,
        roles: Collection<RoleDefinition> = BuiltInRoles.all,
        repositoryLinked: Boolean = true,
    ): WorkflowDefinition {
        val tutorial = TutorialRouter.isTutorialGoal(objective)
        if (!repositoryLinked || tutorial) {
            val eligible = roles.filter {
                it.enabled && it.executionSource is RoleExecutionSource.Agent &&
                    it.capabilitiesRequired.isEmpty() && it.authorities.isEmpty()
            }
            val worker = eligible.firstOrNull { it.id == BuiltInRoles.Worker.id }
                ?: error("The active swarm has no enabled generic Worker without execution requirements")
            val task = TaskDefinition(
                id = TaskDefinitionId("objective"),
                name = "Complete objective",
                objective = objective,
                roleId = worker.id,
                executor = TaskExecutor.RoleAgent(worker.id),
                environmentPlanningPolicy = EnvironmentPlanningPolicy.NotRequired,
                resources = if (tutorial) listOf(TaskResource.AiveDocs()) else emptyList(),
            )
            return StepTemplateAssignments.apply(
                WorkflowDefinition(
                    id = id,
                    name = objective.take(80),
                    description = "General-purpose starter workflow.",
                    tasks = listOf(task),
                    integrationPolicy = IntegrationPolicy.Manual,
                    testDesignPolicy = TestDesignPolicy.None,
                ),
                StepTemplateAssignments.STARTER_KEY,
            )
        }
        val implementationRole = roles.roleFor(
            authority = RoleAuthority.Implement,
            preferredId = BuiltInRoles.ImplementationEngineer.id,
            purpose = "implementation",
        )
        val verificationRole = roles.roleFor(
            authority = RoleAuthority.Verify,
            preferredId = BuiltInRoles.QaEngineer.id,
            purpose = "verification",
        )
        val reviewRole = roles.roleFor(
            authority = RoleAuthority.ReviewCode,
            preferredId = BuiltInRoles.CodeReviewer.id,
            purpose = "code review",
        )
        val releaseRole = roles.roleFor(
            authority = RoleAuthority.ApproveRelease,
            preferredId = BuiltInRoles.ReleaseEngineer.id,
            purpose = "release approval",
        )

        val implementationId = TaskDefinitionId("implementation")
        val verificationId = TaskDefinitionId("verification")
        val reviewId = TaskDefinitionId("review")
        val releaseId = TaskDefinitionId("release-approval")

        val definition = WorkflowDefinition(
            id = id,
            name = objective.take(80),
            description = "Starter workflow created from the live runtime empty state.",
            tasks = listOf(
                TaskDefinition(
                    id = implementationId,
                    name = "Implement objective",
                    objective = objective,
                    roleId = implementationRole.id,
                    requiredRoleAuthority = RoleAuthority.Implement,
                    executor = TaskExecutor.RoleAgent(implementationRole.id),
                    approvalPolicy = ApprovalPolicy.HumanApproval,
                    environmentPlanningPolicy = EnvironmentPlanningPolicy.NotRequired,
                ),
                TaskDefinition(
                    id = verificationId,
                    name = "Verify objective",
                    objective = "Independently verify the implementation satisfies the objective: $objective",
                    roleId = verificationRole.id,
                    requiredRoleAuthority = RoleAuthority.Verify,
                    executor = TaskExecutor.RoleAgent(verificationRole.id),
                    dependsOn = setOf(implementationId),
                    environmentPlanningPolicy = EnvironmentPlanningPolicy.NotRequired,
                ),
                TaskDefinition(
                    id = reviewId,
                    name = "Review implementation",
                    objective = "Independently review the verified implementation for correctness and unintended side effects.",
                    roleId = reviewRole.id,
                    requiredRoleAuthority = RoleAuthority.ReviewCode,
                    executor = TaskExecutor.RoleAgent(reviewRole.id),
                    dependsOn = setOf(verificationId),
                    environmentPlanningPolicy = EnvironmentPlanningPolicy.NotRequired,
                ),
                TaskDefinition(
                    id = releaseId,
                    name = "Release approval",
                    objective = "Approve the verified and reviewed result for release.",
                    roleId = releaseRole.id,
                    requiredRoleAuthority = RoleAuthority.ApproveRelease,
                    executor = TaskExecutor.HumanApproval("Approve release"),
                    dependsOn = setOf(reviewId),
                    environmentPlanningPolicy = EnvironmentPlanningPolicy.NotRequired,
                ),
            ),
            testDesignPolicy = TestDesignPolicy.BeforeAndAfterImplementation,
        )
        return StepTemplateAssignments.apply(definition, StepTemplateAssignments.STARTER_KEY)
    }
}

private fun Collection<RoleDefinition>.roleFor(
    authority: RoleAuthority,
    preferredId: RoleDefinitionId,
    purpose: String,
): RoleDefinition {
    val eligible = filter { it.enabled && authority in it.authorities }
    return eligible.firstOrNull { it.id == preferredId }
        ?: eligible.firstOrNull()
        ?: error("The active swarm has no role authorized for $purpose (${authority.name})")
}
