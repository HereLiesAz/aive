package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.CompoundInferencePolicy
import com.hereliesaz.geministrator.domain.EnvironmentPlanningPolicy
import com.hereliesaz.geministrator.domain.ProviderConstraints
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.VerificationPolicy
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.effectiveExecutor
import com.hereliesaz.geministrator.workflow.refinement.LadderArtifactContract
import com.hereliesaz.geministrator.workflow.refinement.RefinementLadderIds

/**
 * Materializes a refinement ladder into the ordinary workflow DAG:
 * level 1 -> gate 1 -> level 2 -> gate 2 -> ... -> original task -> verification.
 *
 * Each reasoning level is worked coarse to fine and must pass an independent gate before the next
 * level is spent, so a rejected coarse plan stops the run before finer work is paid for. Finer
 * levels and the original task depend on every coarser level and gate, so approved coarse artifacts
 * reach them. This is Skeleton-of-Thought generalized from one skeleton level to N gated levels,
 * and it adds no scheduler: every stage is a normal task with durable state, artifacts, retry and
 * escalation policy, condition semantics, and provider selection.
 */
object RefinementLadderExpander {
    fun expand(
        definition: WorkflowDefinition,
        roles: Collection<RoleDefinition>,
    ): WorkflowDefinition {
        val activeRoles = roles.filter(RoleDefinition::enabled)
        val rolesById = activeRoles.associateBy(RoleDefinition::id)
        if (definition.tasks.none { it.compoundInferencePolicy is CompoundInferencePolicy.RefinementLadder }) {
            return definition
        }

        val reservedIds = definition.tasks.mapTo(mutableSetOf(), TaskDefinition::id)
        val verifierByOriginal = linkedMapOf<TaskDefinitionId, TaskDefinitionId>()

        val expanded = buildList {
            for (task in definition.tasks) {
                val policy = task.compoundInferencePolicy
                if (policy !is CompoundInferencePolicy.RefinementLadder) {
                    add(task)
                    continue
                }

                val responsibleRole = task.roleId?.let(rolesById::get)
                require(task.effectiveExecutor(responsibleRole) is TaskExecutor.RoleAgent) {
                    "$LABEL requires an Agent execution source (${task.id.value})"
                }

                val levelRoles = policy.levels.mapIndexed { index, level ->
                    requireNotNull(rolesById[level.roleId]) {
                        "$LABEL level ${index + 1} role ${level.roleId.value} is not registered and enabled"
                    }.also { requireReasoningOnly(it, "level ${index + 1}", LABEL) }
                }
                val gateReviewer = requireNotNull(rolesById[policy.gateReviewerRoleId]) {
                    "$LABEL gate reviewer ${policy.gateReviewerRoleId.value} is not registered and enabled"
                }.also { requireReasoningOnly(it, "gate reviewer", LABEL) }
                val finalRole = requireNotNull(rolesById[policy.finalRoleId]) {
                    "$LABEL final role ${policy.finalRoleId.value} is not registered and enabled"
                }
                val verifierRole = task.skeletonVerifierRole(activeRoles, rolesById, LABEL)

                fun reserve(id: String, what: String): TaskDefinitionId =
                    TaskDefinitionId(id).also {
                        require(reservedIds.add(it)) { "Cannot inject $LABEL $what because ${it.value} already exists" }
                    }

                val total = policy.levels.size
                val levelIds = policy.levels.indices.map {
                    reserve(RefinementLadderIds.level(task.id.value, it + 1), "level ${it + 1}")
                }
                val gateIds = policy.levels.indices.map {
                    reserve(RefinementLadderIds.gate(task.id.value, it + 1), "gate ${it + 1}")
                }
                val verifierId = reserve(RefinementLadderIds.verifier(task.id.value), "verifier")
                verifierByOriginal[task.id] = verifierId

                policy.levels.forEachIndexed { index, level ->
                    val earlier = (0 until index).flatMap { listOf(levelIds[it], gateIds[it]) }
                    add(
                        TaskDefinition(
                            id = levelIds[index],
                            name = "Refine level ${index + 1}/$total: ${task.name}",
                            objective = buildString {
                                append("Refine level ${index + 1} of $total (1 is coarsest) for this authorized objective: ")
                                append(task.objective)
                                append(". Decide only: ${level.focus}. ")
                                if (index > 0) {
                                    append("Use the approved artifacts of the coarser levels and stay consistent with them; do not reopen their decisions. ")
                                }
                                if (index < total - 1) {
                                    append("Leave finer levels undecided; make the next level's work narrower. ")
                                }
                                if (level.structured) {
                                    append(LadderArtifactContract.instruction(index + 1, total))
                                } else {
                                    append("Produce a bounded TaskPlan artifact stating assumptions and unresolved uncertainty. ")
                                }
                                append("Stay within the original authority. Do not mutate the repository or claim verification.")
                            },
                            roleId = levelRoles[index].id,
                            dependsOn = task.dependsOn + earlier,
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
                            executor = TaskExecutor.RoleAgent(levelRoles[index].id),
                        ),
                    )
                    add(
                        TaskDefinition(
                            id = gateIds[index],
                            name = "Gate level ${index + 1}/$total: ${task.name}",
                            objective = buildString {
                                append("Review refinement level ${index + 1} of $total for this objective: ")
                                append(task.objective)
                                append(". Approve only if the level decides what it was asked to (${level.focus}), is consistent with every coarser approved level, and leaves the finer work narrower rather than wider. Reject otherwise so no finer level is spent on it. ")
                                if (level.structured) {
                                    append("The level must be a valid aive.ladder.level.v1 document that covers every decision of the coarser level. ")
                                }
                                append(ArtifactVerdicts.INSTRUCTION)
                                append(" Do not decide the finer levels or mutate the repository.")
                            },
                            roleId = gateReviewer.id,
                            dependsOn = task.dependsOn + earlier + levelIds[index],
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
                            executor = TaskExecutor.RoleAgent(gateReviewer.id),
                        ),
                    )
                }

                val ladderIds = levelIds + gateIds
                add(
                    task.copy(
                        name = "Finest level: ${task.name}",
                        objective = buildString {
                            append("Carry out the finest level of this authorized objective: ")
                            append(task.objective)
                            append(". Work within the approved coarser levels and do not contradict them. Produce the original required output and satisfy the original acceptance criteria.")
                        },
                        roleId = finalRole.id,
                        executor = TaskExecutor.RoleAgent(finalRole.id),
                        dependsOn = task.dependsOn + ladderIds,
                        condition = task.condition,
                        verificationPolicy = VerificationPolicy.None,
                        compoundInferencePolicy = CompoundInferencePolicy.Single,
                    ),
                )

                val verificationCriteria = (task.verificationPolicy as? VerificationPolicy.Required)?.criteria.orEmpty()
                add(
                    TaskDefinition(
                        id = verifierId,
                        name = "Verify refinement result: ${task.name}",
                        objective = buildString {
                            append("Independently verify the result for: ")
                            append(task.objective)
                            append(". Check the original acceptance criteria and available evidence. Inspect each level and gate, but do not treat their approval as proof.")
                            if (verificationCriteria.isNotEmpty()) {
                                append(" Additional verification criteria: ")
                                append(verificationCriteria.joinToString("; "))
                            }
                        },
                        roleId = verifierRole.id,
                        dependsOn = task.dependsOn + ladderIds + task.id,
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

    private const val LABEL = "Refinement ladder"
}
