package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.EnvironmentPlanningPolicy
import com.hereliesaz.geministrator.domain.ProviderConstraints
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.effectiveExecutor

class WorkflowDefinitionPreparer(
    private val providerRegistry: AgentProviderRegistry,
    roles: Collection<RoleDefinition>,
) {
    private val rolesById: Map<RoleDefinitionId, RoleDefinition> = roles.associateBy { it.id }

    suspend fun prepare(
        definition: WorkflowDefinition,
        repository: RepositoryRef? = null,
    ): WorkflowDefinition {
        val withTestDesign = WorkflowDefinitionExpander.expand(definition)
        val originalIds = withTestDesign.tasks.mapTo(mutableSetOf()) { it.id }
        val prepared = buildList {
            for (task in withTestDesign.tasks) {
                val executor = task.effectiveExecutor()
                if (executor !is TaskExecutor.RoleAgent ||
                    executor.roleId == BuiltInRoles.EpaRepresentative.id ||
                    task.environmentPlanningPolicy == EnvironmentPlanningPolicy.NotRequired
                ) {
                    add(task)
                    continue
                }

                val role = requireNotNull(rolesById[executor.roleId]) {
                    "Role ${executor.roleId.value} is not registered"
                }
                val selectedProvider = providerRegistry.select(
                    ProviderSelectionRequest(
                        preferredProviderId = role.preferredProviderId,
                        requiredCapabilities = role.capabilitiesRequired,
                        constraints = task.providerConstraints,
                        repository = repository,
                    ),
                )
                val providerRequiresPlanning = selectedProvider.capabilities().requiresEnvironmentPlanning
                val needsEpa = task.environmentPlanningPolicy == EnvironmentPlanningPolicy.Always ||
                    (task.environmentPlanningPolicy == EnvironmentPlanningPolicy.WhenProviderRequires && providerRequiresPlanning)

                if (!needsEpa) {
                    add(task)
                    continue
                }

                val epaTaskId = TaskDefinitionId("${task.id.value}--environment-plan")
                require(epaTaskId !in originalIds) {
                    "Cannot inject EPA task because ${epaTaskId.value} already exists"
                }
                originalIds += epaTaskId

                add(
                    TaskDefinition(
                        id = epaTaskId,
                        name = "Environment plan: ${task.name}",
                        objective = "Determine the smallest safe reproducible execution environment for '${task.name}' using provider '${selectedProvider.id.value}', repository constraints, required tools, services, secrets, network access, isolation, and resource needs.",
                        roleId = BuiltInRoles.EpaRepresentative.id,
                        executor = TaskExecutor.RoleAgent(BuiltInRoles.EpaRepresentative.id),
                        dependsOn = task.dependsOn,
                        acceptanceCriteria = emptyList(),
                        requiredArtifacts = setOf(ArtifactKind.EnvironmentSpecification),
                        approvalPolicy = ApprovalPolicy.None,
                        retryPolicy = task.retryPolicy,
                        escalationPolicy = task.escalationPolicy,
                        providerConstraints = ProviderConstraints.RequireCapabilities(
                            setOf(AgentCapability.EnvironmentPlanning),
                        ),
                        environmentPlanningPolicy = EnvironmentPlanningPolicy.NotRequired,
                    ),
                )
                add(
                    task.copy(
                        dependsOn = task.dependsOn + epaTaskId,
                        environmentPlanningPolicy = EnvironmentPlanningPolicy.NotRequired,
                    ),
                )
            }
        }

        return withTestDesign.copy(tasks = prepared).also(WorkflowGraphValidator::requireValid)
    }
}
