package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.CompoundInferencePolicy
import com.hereliesaz.geministrator.domain.EnvironmentPlanningPolicy
import com.hereliesaz.geministrator.domain.ProviderConstraints
import com.hereliesaz.geministrator.domain.RoleAuthority
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.VerificationPolicy
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.effectiveExecutor

/**
 * Materializes Skeleton-of-Thought into the ordinary workflow DAG:
 * skeleton -> independence review -> parallel expansions -> aggregation -> verification.
 *
 * No hidden scheduler is introduced. Every stage is a normal task with durable state, artifacts,
 * retry/escalation policy, condition semantics, and provider selection.
 */
object SkeletonOfThoughtExpander {
    fun expand(
        definition: WorkflowDefinition,
        roles: Collection<RoleDefinition>,
    ): WorkflowDefinition {
        val activeRoles = roles.filter(RoleDefinition::enabled)
        val rolesById = activeRoles.associateBy(RoleDefinition::id)
        if (definition.tasks.none { it.compoundInferencePolicy is CompoundInferencePolicy.SkeletonOfThought }) {
            return definition
        }

        val reservedIds = definition.tasks.mapTo(mutableSetOf(), TaskDefinition::id)
        val verifierByOriginal = linkedMapOf<TaskDefinitionId, TaskDefinitionId>()

        val expanded = buildList {
            for (task in definition.tasks) {
                val policy = task.compoundInferencePolicy
                if (policy !is CompoundInferencePolicy.SkeletonOfThought) {
                    add(task)
                    continue
                }

                val responsibleRole = task.roleId?.let(rolesById::get)
                require(task.effectiveExecutor(responsibleRole) is TaskExecutor.RoleAgent) {
                    "Skeleton-of-Thought requires an Agent execution source (${task.id.value})"
                }

                val skeletonRole = requireNotNull(rolesById[policy.skeletonRoleId]) {
                    "Skeleton-of-Thought skeleton role ${policy.skeletonRoleId.value} is not registered and enabled"
                }
                val independenceReviewer = requireNotNull(rolesById[policy.independenceReviewerRoleId]) {
                    "Skeleton-of-Thought independence reviewer ${policy.independenceReviewerRoleId.value} is not registered and enabled"
                }
                val expansionRoles = policy.expansionRoleIds.map { roleId ->
                    requireNotNull(rolesById[roleId]) {
                        "Skeleton-of-Thought expansion role ${roleId.value} is not registered and enabled"
                    }
                }
                val aggregatorRole = requireNotNull(rolesById[policy.aggregatorRoleId]) {
                    "Skeleton-of-Thought aggregator role ${policy.aggregatorRoleId.value} is not registered and enabled"
                }

                requireReasoningOnly(skeletonRole, "skeleton")
                requireReasoningOnly(independenceReviewer, "independence reviewer")
                expansionRoles.forEachIndexed { index, role ->
                    requireReasoningOnly(role, "expansion ${index + 1}")
                }
                val verifierRole = task.skeletonVerifierRole(activeRoles, rolesById)

                val skeletonId = TaskDefinitionId("${task.id.value}--sot-skeleton").also { id ->
                    require(reservedIds.add(id)) {
                        "Cannot inject Skeleton-of-Thought skeleton because ${id.value} already exists"
                    }
                }
                val independenceId = TaskDefinitionId("${task.id.value}--sot-independence").also { id ->
                    require(reservedIds.add(id)) {
                        "Cannot inject Skeleton-of-Thought independence review because ${id.value} already exists"
                    }
                }
                val expansionIds = expansionRoles.indices.map { index ->
                    TaskDefinitionId("${task.id.value}--sot-expansion-${index + 1}").also { id ->
                        require(reservedIds.add(id)) {
                            "Cannot inject Skeleton-of-Thought expansion because ${id.value} already exists"
                        }
                    }
                }
                val verifierId = TaskDefinitionId("${task.id.value}--sot-verifier").also { id ->
                    require(reservedIds.add(id)) {
                        "Cannot inject Skeleton-of-Thought verifier because ${id.value} already exists"
                    }
                }
                verifierByOriginal[task.id] = verifierId

                add(
                    TaskDefinition(
                        id = skeletonId,
                        name = "Skeleton: ${task.name}",
                        objective = buildString {
                            append("Create a minimal reasoning skeleton for this authorized objective: ")
                            append(task.objective)
                            append(". Produce exactly ${expansionIds.size} numbered branches. Each branch must be independently expandable, avoid sibling conclusions, name its assumptions/evidence needs, and stay within the original authority. Do not mutate the repository or claim verification.")
                        },
                        roleId = skeletonRole.id,
                        dependsOn = task.dependsOn,
                        condition = task.condition,
                        acceptanceCriteria = emptyList(),
                        requiredArtifacts = setOf(ArtifactKind.TaskPlan),
                        approvalPolicy = ApprovalPolicy.None,
                        verificationPolicy = VerificationPolicy.None,
                        retryPolicy = task.retryPolicy,
                        escalationPolicy = task.escalationPolicy,
                        providerConstraints = ProviderConstraints.None,
                        environmentPlanningPolicy = EnvironmentPlanningPolicy.WhenProviderRequires,
                        compoundInferencePolicy = CompoundInferencePolicy.Single,
                        executor = TaskExecutor.RoleAgent(skeletonRole.id),
                    ),
                )

                add(
                    TaskDefinition(
                        id = independenceId,
                        name = "Independence review: ${task.name}",
                        objective = "Review the skeleton for branch overlap, hidden ordering, shared conclusions, and dependencies that would make parallel expansion unsound. Preserve the numbered branch identities. Approve only a decomposition whose branches can be expanded independently; do not solve the branches or mutate the repository.",
                        roleId = independenceReviewer.id,
                        dependsOn = task.dependsOn + skeletonId,
                        condition = task.condition,
                        acceptanceCriteria = emptyList(),
                        requiredArtifacts = setOf(ArtifactKind.Verification),
                        approvalPolicy = ApprovalPolicy.None,
                        verificationPolicy = VerificationPolicy.None,
                        retryPolicy = task.retryPolicy,
                        escalationPolicy = task.escalationPolicy,
                        providerConstraints = ProviderConstraints.None,
                        environmentPlanningPolicy = EnvironmentPlanningPolicy.WhenProviderRequires,
                        compoundInferencePolicy = CompoundInferencePolicy.Single,
                        executor = TaskExecutor.RoleAgent(independenceReviewer.id),
                    ),
                )

                expansionRoles.forEachIndexed { index, role ->
                    add(
                        TaskDefinition(
                            id = expansionIds[index],
                            name = "Expand skeleton branch ${index + 1}: ${task.name}",
                            objective = buildString {
                                append("Expand only numbered skeleton branch ${index + 1} for this objective: ")
                                append(task.objective)
                                append(". Use the approved skeleton and independence-review artifacts. Do not consume or anticipate sibling expansion output. Return a bounded TaskPlan artifact with evidence, assumptions, unresolved uncertainty, and the branch-local conclusion. Do not mutate the repository or claim verification.")
                            },
                            roleId = role.id,
                            dependsOn = task.dependsOn + skeletonId + independenceId,
                            condition = task.condition,
                            acceptanceCriteria = emptyList(),
                            requiredArtifacts = setOf(ArtifactKind.TaskPlan),
                            approvalPolicy = ApprovalPolicy.None,
                            verificationPolicy = VerificationPolicy.None,
                            retryPolicy = task.retryPolicy,
                            escalationPolicy = task.escalationPolicy,
                            providerConstraints = ProviderConstraints.None,
                            environmentPlanningPolicy = EnvironmentPlanningPolicy.WhenProviderRequires,
                            compoundInferencePolicy = CompoundInferencePolicy.Single,
                            executor = TaskExecutor.RoleAgent(role.id),
                        ),
                    )
                }

                add(
                    task.copy(
                        name = "Aggregate skeleton expansions: ${task.name}",
                        objective = buildString {
                            append("Synthesize the approved Skeleton-of-Thought expansions for this authorized objective: ")
                            append(task.objective)
                            append(". Preserve branch-local evidence, disagreements, and uncertainty. Do not treat branch agreement as verification. Produce the original required output and satisfy the original acceptance criteria.")
                        },
                        roleId = aggregatorRole.id,
                        executor = TaskExecutor.RoleAgent(aggregatorRole.id),
                        dependsOn = task.dependsOn + skeletonId + independenceId + expansionIds,
                        condition = task.condition,
                        verificationPolicy = VerificationPolicy.None,
                        compoundInferencePolicy = CompoundInferencePolicy.Single,
                    ),
                )

                val verificationCriteria = (task.verificationPolicy as? VerificationPolicy.Required)?.criteria.orEmpty()
                add(
                    TaskDefinition(
                        id = verifierId,
                        name = "Verify Skeleton-of-Thought result: ${task.name}",
                        objective = buildString {
                            append("Independently verify the aggregated result for: ")
                            append(task.objective)
                            append(". Check the original acceptance criteria and available evidence. Inspect the skeleton, independence review, and branch artifacts, but do not treat agreement itself as proof.")
                            if (verificationCriteria.isNotEmpty()) {
                                append(" Additional verification criteria: ")
                                append(verificationCriteria.joinToString("; "))
                            }
                        },
                        roleId = verifierRole.id,
                        dependsOn = task.dependsOn + skeletonId + independenceId + expansionIds + task.id,
                        condition = task.condition,
                        acceptanceCriteria = task.acceptanceCriteria,
                        requiredArtifacts = setOf(ArtifactKind.Verification),
                        approvalPolicy = ApprovalPolicy.None,
                        verificationPolicy = VerificationPolicy.None,
                        retryPolicy = task.retryPolicy,
                        escalationPolicy = task.escalationPolicy,
                        providerConstraints = ProviderConstraints.None,
                        environmentPlanningPolicy = EnvironmentPlanningPolicy.WhenProviderRequires,
                        compoundInferencePolicy = CompoundInferencePolicy.Single,
                        executor = TaskExecutor.RoleAgent(verifierRole.id),
                    ),
                )
            }
        }

        val rewired = expanded.map { task ->
            val verifierDependencies = task.dependsOn.mapNotNullTo(linkedSetOf()) { dependency ->
                verifierByOriginal[dependency]?.takeUnless { it == task.id }
            }
            if (verifierDependencies.isEmpty()) task
            else task.copy(dependsOn = task.dependsOn + verifierDependencies)
        }

        return definition.copy(tasks = rewired).also(WorkflowGraphValidator::requireValid)
    }
}

private fun requireReasoningOnly(role: RoleDefinition, stage: String) {
    require(RoleAuthority.Implement !in role.authorities) {
        "Skeleton-of-Thought $stage role ${role.name} may not hold implementation authority"
    }
    require(AgentCapability.RepositoryWrite !in role.capabilitiesRequired) {
        "Skeleton-of-Thought $stage role ${role.name} may not require repository-write capability"
    }
}

private fun TaskDefinition.skeletonVerifierRole(
    activeRoles: Collection<RoleDefinition>,
    rolesById: Map<RoleDefinitionId, RoleDefinition>,
): RoleDefinition {
    val explicitVerifier = (verificationPolicy as? VerificationPolicy.Required)?.verifierRoleId
    if (explicitVerifier != null) {
        val role = requireNotNull(rolesById[explicitVerifier]) {
            "Skeleton-of-Thought verifier role ${explicitVerifier.value} is not registered and enabled"
        }
        require(RoleAuthority.Verify in role.authorities) {
            "Skeleton-of-Thought verifier ${role.name} does not have Verify authority"
        }
        return role
    }

    val eligible = activeRoles.filter { RoleAuthority.Verify in it.authorities }
    return eligible.firstOrNull { it.id == BuiltInRoles.QaEngineer.id }
        ?: eligible.firstOrNull()
        ?: error("Skeleton-of-Thought requires an enabled role with Verify authority")
}
