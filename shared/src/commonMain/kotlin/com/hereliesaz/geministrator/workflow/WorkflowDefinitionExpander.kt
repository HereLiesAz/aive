package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.RoleAuthority
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TestDesignPolicy
import com.hereliesaz.geministrator.domain.WorkflowDefinition

object WorkflowDefinitionExpander {
    fun expand(
        definition: WorkflowDefinition,
        roles: Collection<RoleDefinition> = BuiltInRoles.all,
    ): WorkflowDefinition {
        WorkflowGraphValidator.requireValid(definition)

        val includePreCodeTests = definition.testDesignPolicy == TestDesignPolicy.BeforeImplementation ||
            definition.testDesignPolicy == TestDesignPolicy.BeforeAndAfterImplementation
        val includePostCodeTests = definition.testDesignPolicy == TestDesignPolicy.AfterImplementation ||
            definition.testDesignPolicy == TestDesignPolicy.BeforeAndAfterImplementation

        if (!includePreCodeTests && !includePostCodeTests) return definition

        val activeRoles = roles.filter(RoleDefinition::enabled)
        val rolesById = activeRoles.associateBy(RoleDefinition::id)
        fun taskRole(task: TaskDefinition): RoleDefinition? {
            val roleId = task.roleId ?: (task.executor as? TaskExecutor.RoleAgent)?.roleId
            return roleId?.let(rolesById::get)
        }
        fun testAuthorRole(): RoleDefinition = activeRoles.preferredRole(
            authority = RoleAuthority.AuthorTests,
            preferredId = BuiltInRoles.CrashTestDummy.id,
            purpose = "test authoring",
        )

        val originalIds = definition.tasks.map { it.id }.toSet()
        val postTaskByImplementation = mutableMapOf<TaskDefinitionId, TaskDefinitionId>()
        val injectedPostIds = mutableSetOf<TaskDefinitionId>()

        val expanded = buildList {
            definition.tasks.forEach { task ->
                val role = taskRole(task)
                if (role == null || RoleAuthority.Implement !in role.authorities) {
                    add(task)
                    return@forEach
                }

                var implementation = task

                if (includePreCodeTests) {
                    val preCodeId = TaskDefinitionId("${task.id.value}--pre-code-tests")
                    require(preCodeId !in originalIds) {
                        "Cannot inject pre-code test task because ${preCodeId.value} already exists"
                    }
                    val testAuthor = testAuthorRole()
                    add(
                        TaskDefinition(
                            id = preCodeId,
                            name = "Pre-code tests: ${task.name}",
                            objective = "Derive the verification contract for '${task.name}' from approved specifications, architecture, acceptance criteria, and concepts without inspecting implementation code.",
                            roleId = testAuthor.id,
                            executor = TaskExecutor.RoleAgent(testAuthor.id),
                            dependsOn = task.dependsOn,
                            acceptanceCriteria = task.acceptanceCriteria,
                            requiredArtifacts = setOf(
                                ArtifactKind.AcceptanceTestPlan,
                                ArtifactKind.BehavioralTest,
                                ArtifactKind.ContractTest,
                                ArtifactKind.FailureScenario,
                            ),
                            approvalPolicy = ApprovalPolicy.None,
                            retryPolicy = task.retryPolicy,
                            escalationPolicy = task.escalationPolicy,
                            providerConstraints = task.providerConstraints,
                            environmentPlanningPolicy = task.environmentPlanningPolicy,
                        ),
                    )
                    implementation = implementation.copy(
                        dependsOn = implementation.dependsOn + preCodeId,
                    )
                }

                add(implementation)

                if (includePostCodeTests) {
                    val postCodeId = TaskDefinitionId("${task.id.value}--post-code-tests")
                    require(postCodeId !in originalIds) {
                        "Cannot inject post-code test task because ${postCodeId.value} already exists"
                    }
                    val testAuthor = testAuthorRole()
                    postTaskByImplementation[task.id] = postCodeId
                    injectedPostIds += postCodeId
                    add(
                        TaskDefinition(
                            id = postCodeId,
                            name = "Post-code tests: ${task.name}",
                            objective = "Inspect the approved implementation for '${task.name}' and author regression tests, implementation-specific edge cases, and coverage-gap tests without certifying that the implementation passes them.",
                            roleId = testAuthor.id,
                            executor = TaskExecutor.RoleAgent(testAuthor.id),
                            dependsOn = setOf(task.id),
                            acceptanceCriteria = task.acceptanceCriteria,
                            requiredArtifacts = setOf(
                                ArtifactKind.TestPlan,
                                ArtifactKind.TestCode,
                                ArtifactKind.RegressionTest,
                            ),
                            approvalPolicy = ApprovalPolicy.None,
                            retryPolicy = task.retryPolicy,
                            escalationPolicy = task.escalationPolicy,
                            providerConstraints = task.providerConstraints,
                            environmentPlanningPolicy = task.environmentPlanningPolicy,
                        ),
                    )
                }
            }
        }

        val rewired = if (includePostCodeTests) {
            expanded.map { task ->
                if (task.id in injectedPostIds) {
                    task
                } else {
                    task.copy(
                        dependsOn = task.dependsOn.mapTo(mutableSetOf()) { dependency ->
                            postTaskByImplementation[dependency] ?: dependency
                        },
                    )
                }
            }
        } else {
            expanded
        }

        return definition.copy(tasks = rewired).also(WorkflowGraphValidator::requireValid)
    }
}

private fun Collection<RoleDefinition>.preferredRole(
    authority: RoleAuthority,
    preferredId: RoleDefinitionId,
    purpose: String,
): RoleDefinition {
    val eligible = filter { it.enabled && authority in it.authorities }
    return eligible.firstOrNull { it.id == preferredId }
        ?: eligible.firstOrNull()
        ?: error("The active swarm has no role authorized for $purpose (${authority.name})")
}
