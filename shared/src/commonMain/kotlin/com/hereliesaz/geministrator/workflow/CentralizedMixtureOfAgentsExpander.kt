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

const val GENEALOGY_GOVERNANCE_SERVICE: String = "haive.genealogy-governance"

/**
 * Materializes centralized Mixture-of-Agents policy into ordinary workflow DAG nodes.
 *
 * The original task ID remains the aggregator so its artifacts and durable identity are preserved.
 * Downstream tasks retain that dependency and also wait for the injected verifier gate.
 */
object CentralizedMixtureOfAgentsExpander {
    fun expand(
        definition: WorkflowDefinition,
        roles: Collection<RoleDefinition>,
    ): WorkflowDefinition {
        val activeRoles = roles.filter(RoleDefinition::enabled)
        val rolesById = activeRoles.associateBy(RoleDefinition::id)
        if (definition.tasks.none { it.compoundInferencePolicy is CompoundInferencePolicy.CentralizedMixtureOfAgents }) {
            return definition
        }

        val reservedIds = definition.tasks.mapTo(mutableSetOf(), TaskDefinition::id)
        val verifierByOriginal = linkedMapOf<TaskDefinitionId, TaskDefinitionId>()
        val generatedVerifierIds = mutableSetOf<TaskDefinitionId>()

        val expanded = buildList {
            for (task in definition.tasks) {
                val policy = task.compoundInferencePolicy
                if (policy !is CompoundInferencePolicy.CentralizedMixtureOfAgents) {
                    add(task)
                    continue
                }

                val responsibleRole = task.roleId?.let(rolesById::get)
                require(task.effectiveExecutor(responsibleRole) is TaskExecutor.RoleAgent) {
                    "Centralized MoA requires an Agent execution source (${task.id.value})"
                }

                val proposerRoles = policy.proposerRoleIds.map { roleId ->
                    requireNotNull(rolesById[roleId]) {
                        "Centralized MoA proposer role ${roleId.value} is not registered and enabled"
                    }
                }
                proposerRoles.forEach { role ->
                    require(RoleAuthority.Implement !in role.authorities) {
                        "Centralized MoA proposer ${role.name} may not hold implementation authority in the first safe MoA runtime"
                    }
                    require(AgentCapability.RepositoryWrite !in role.capabilitiesRequired) {
                        "Centralized MoA proposer ${role.name} may not require repository-write capability"
                    }
                }

                val aggregatorRole = requireNotNull(rolesById[policy.aggregatorRoleId]) {
                    "Centralized MoA aggregator role ${policy.aggregatorRoleId.value} is not registered and enabled"
                }
                val verifierRole = task.verifierRole(activeRoles, rolesById)

                val proposerIds = proposerRoles.indices.map { index ->
                    TaskDefinitionId("${task.id.value}--moa-proposer-${index + 1}").also { id ->
                        require(reservedIds.add(id)) {
                            "Cannot inject centralized MoA proposer because ${id.value} already exists"
                        }
                    }
                }
                val governanceId = TaskDefinitionId("${task.id.value}--moa-governance").also { id ->
                    require(reservedIds.add(id)) {
                        "Cannot inject centralized MoA governance task because ${id.value} already exists"
                    }
                }
                val postCodeTestId = TaskDefinitionId("${task.id.value}--post-code-tests")
                    .takeIf(reservedIds::contains)
                val verifierId = TaskDefinitionId("${task.id.value}--moa-verifier").also { id ->
                    require(reservedIds.add(id)) {
                        "Cannot inject centralized MoA verifier because ${id.value} already exists"
                    }
                }
                verifierByOriginal[task.id] = verifierId
                generatedVerifierIds += verifierId

                proposerRoles.forEachIndexed { index, role ->
                    add(
                        TaskDefinition(
                            id = proposerIds[index],
                            name = "MoA candidate ${index + 1}: ${task.name}",
                            objective = buildString {
                                append("Independently propose a candidate approach for the following objective without seeing or relying on sibling proposer output: ")
                                append(task.objective)
                                append(" Produce the candidate as a bounded TaskPlan artifact with evidence and assumptions. Do not mutate the repository or claim verification.")
                            },
                            roleId = role.id,
                            dependsOn = task.dependsOn,
                            condition = task.condition,
                            acceptanceCriteria = task.acceptanceCriteria,
                            requiredArtifacts = setOf(ArtifactKind.TaskPlan),
                            approvalPolicy = ApprovalPolicy.None,
                            verificationPolicy = VerificationPolicy.None,
                            retryPolicy = task.retryPolicy,
                            escalationPolicy = task.escalationPolicy,
                            providerConstraints = task.providerConstraints,
                            environmentPlanningPolicy = EnvironmentPlanningPolicy.WhenProviderRequires,
                            compoundInferencePolicy = CompoundInferencePolicy.Single,
                            executor = TaskExecutor.RoleAgent(role.id),
                        ),
                    )
                }

                add(
                    TaskDefinition(
                        id = governanceId,
                        name = "Genealogy gate: ${task.name}",
                        objective = "Check the MoA candidate lineage for common ancestry, missing genealogy, circular derivation, and sufficient structural independence before aggregation. Do not decide which candidate is true or best.",
                        roleId = null,
                        dependsOn = task.dependsOn + proposerIds,
                        condition = task.condition,
                        acceptanceCriteria = emptyList(),
                        requiredArtifacts = setOf(ArtifactKind.Verification),
                        approvalPolicy = ApprovalPolicy.None,
                        verificationPolicy = VerificationPolicy.None,
                        retryPolicy = task.retryPolicy,
                        escalationPolicy = task.escalationPolicy,
                        providerConstraints = ProviderConstraints.None,
                        environmentPlanningPolicy = EnvironmentPlanningPolicy.NotRequired,
                        compoundInferencePolicy = CompoundInferencePolicy.Single,
                        executor = TaskExecutor.ExternalService(
                            service = GENEALOGY_GOVERNANCE_SERVICE,
                            operation = task.id.value,
                        ),
                    ),
                )

                add(
                    task.copy(
                        name = "Aggregate: ${task.name}",
                        objective = buildString {
                            append("Synthesize the independent MoA candidate artifacts for this authorized objective: ")
                            append(task.objective)
                            append(" Preserve meaningful disagreements and evidence. Do not treat candidate agreement as verification. Use the genealogy-governance artifact as structural provenance guidance, then produce the original required output and satisfy the original acceptance criteria.")
                        },
                        roleId = aggregatorRole.id,
                        executor = TaskExecutor.RoleAgent(aggregatorRole.id),
                        dependsOn = task.dependsOn + proposerIds + governanceId,
                        condition = task.condition,
                        verificationPolicy = VerificationPolicy.None,
                        compoundInferencePolicy = CompoundInferencePolicy.Single,
                    ),
                )

                val verificationCriteria = (task.verificationPolicy as? VerificationPolicy.Required)?.criteria.orEmpty()
                add(
                    TaskDefinition(
                        id = verifierId,
                        name = "Verify MoA result: ${task.name}",
                        objective = buildString {
                            append("Independently verify the aggregated result for: ")
                            append(task.objective)
                            append(". Check the original acceptance criteria and available evidence. Inspect the genealogy-governance artifact and candidate artifacts, but do not treat agreement itself as proof.")
                            if (verificationCriteria.isNotEmpty()) {
                                append(" Additional verification criteria: ")
                                append(verificationCriteria.joinToString("; "))
                            }
                        },
                        roleId = verifierRole.id,
                        dependsOn = task.dependsOn + proposerIds + governanceId + task.id +
                            postCodeTestId?.let(::setOf).orEmpty(),
                        condition = task.condition,
                        acceptanceCriteria = task.acceptanceCriteria,
                        requiredArtifacts = setOf(ArtifactKind.Verification),
                        approvalPolicy = ApprovalPolicy.None,
                        verificationPolicy = VerificationPolicy.None,
                        retryPolicy = task.retryPolicy,
                        escalationPolicy = task.escalationPolicy,
                        providerConstraints = task.providerConstraints,
                        environmentPlanningPolicy = EnvironmentPlanningPolicy.WhenProviderRequires,
                        compoundInferencePolicy = CompoundInferencePolicy.Single,
                        executor = TaskExecutor.RoleAgent(verifierRole.id),
                    ),
                )
            }
        }

        val rewired = expanded.map { task ->
            val additionalVerifierDependencies = task.dependsOn.mapNotNullTo(linkedSetOf()) { dependency ->
                val verifierId = verifierByOriginal[dependency] ?: return@mapNotNullTo null
                val postCodeTaskId = TaskDefinitionId("${dependency.value}--post-code-tests")
                verifierId.takeUnless { it == task.id || task.id == postCodeTaskId }
            }
            val retargetedCondition = when (val condition = task.condition) {
                is com.hereliesaz.geministrator.domain.TaskCondition.OnAnyOutcome ->
                    verifierByOriginal[condition.ofTask]?.let {
                        com.hereliesaz.geministrator.domain.TaskCondition.OnAnyOutcome(it)
                    } ?: condition
                is com.hereliesaz.geministrator.domain.TaskCondition.OnFailure ->
                    verifierByOriginal[condition.ofTask]?.let {
                        com.hereliesaz.geministrator.domain.TaskCondition.OnFailure(it)
                    } ?: condition
                com.hereliesaz.geministrator.domain.TaskCondition.Always -> condition
            }
            if (additionalVerifierDependencies.isEmpty() && retargetedCondition == task.condition) {
                task
            } else {
                task.copy(
                    dependsOn = task.dependsOn + additionalVerifierDependencies,
                    condition = retargetedCondition,
                )
            }
        }

        return definition.copy(tasks = rewired).also(WorkflowGraphValidator::requireValid)
    }
}

private fun TaskDefinition.verifierRole(
    activeRoles: Collection<RoleDefinition>,
    rolesById: Map<RoleDefinitionId, RoleDefinition>,
): RoleDefinition {
    val explicitVerifier = (verificationPolicy as? VerificationPolicy.Required)?.verifierRoleId
    if (explicitVerifier != null) {
        val role = requireNotNull(rolesById[explicitVerifier]) {
            "Centralized MoA verifier role ${explicitVerifier.value} is not registered and enabled"
        }
        require(RoleAuthority.Verify in role.authorities) {
            "Centralized MoA verifier ${role.name} does not have Verify authority"
        }
        return role
    }

    val eligible = activeRoles.filter { RoleAuthority.Verify in it.authorities }
    return eligible.firstOrNull { it.id == BuiltInRoles.QaEngineer.id }
        ?: eligible.firstOrNull()
        ?: error("Centralized MoA requires an enabled role with Verify authority")
}
