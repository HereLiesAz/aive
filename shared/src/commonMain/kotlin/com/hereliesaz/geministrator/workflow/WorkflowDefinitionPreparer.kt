package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.EnvironmentPlanningPolicy
import com.hereliesaz.geministrator.domain.ProviderConstraints
import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.RoleAuthority
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
    private val activeRoles: List<RoleDefinition> = roles.filter(RoleDefinition::enabled)
    private val rolesById: Map<RoleDefinitionId, RoleDefinition> = activeRoles.associateBy { it.id }

    suspend fun prepare(
        definition: WorkflowDefinition,
        repository: RepositoryRef? = null,
    ): WorkflowDefinition {
        val withTestDesign = WorkflowDefinitionExpander.expand(definition, activeRoles)
        val withSelectedInferenceTopology = ResourceAwareCompoundInferencePolicyResolver.resolve(
            definition = withTestDesign,
            roles = activeRoles,
            providerRegistry = providerRegistry,
            repository = repository,
        )
        val withCentralizedInference = CentralizedMixtureOfAgentsExpander.expand(
            withSelectedInferenceTopology,
            activeRoles,
        )
        val withCompoundInference = SkeletonOfThoughtExpander.expand(
            withCentralizedInference,
            activeRoles,
        )
        val originalIds = withCompoundInference.tasks.mapTo(mutableSetOf()) { it.id }
        val prepared = buildList {
            for (task in withCompoundInference.tasks) {
                val executor = task.effectiveExecutor()
                if (executor !is TaskExecutor.RoleAgent || task.environmentPlanningPolicy == EnvironmentPlanningPolicy.NotRequired) {
                    add(task)
                    continue
                }

                val role = requireNotNull(rolesById[executor.roleId]) {
                    "Role ${executor.roleId.value} is not registered in the active swarm"
                }
                if (RoleAuthority.SelectEnvironment in role.authorities) {
                    add(task)
                    continue
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

                val environmentPlanner = activeRoles.preferredEnvironmentPlanner()
                val epaTaskId = TaskDefinitionId("${task.id.value}--environment-plan")
                require(epaTaskId !in originalIds) {
                    "Cannot inject environment-planning task because ${epaTaskId.value} already exists"
                }
                originalIds += epaTaskId

                add(
                    TaskDefinition(
                        id = epaTaskId,
                        name = "Environment plan: ${task.name}",
                        objective = "Determine the smallest safe reproducible execution environment for '${task.name}' using provider '${selectedProvider.id.value}', repository constraints, required tools, services, secrets, network access, isolation, and resource needs.",
                        roleId = environmentPlanner.id,
                        executor = TaskExecutor.RoleAgent(environmentPlanner.id),
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

        return withCompoundInference.copy(tasks = prepared).also(WorkflowGraphValidator::requireValid)
    }
}

private fun Collection<RoleDefinition>.preferredEnvironmentPlanner(): RoleDefinition {
    val eligible = filter { it.enabled && RoleAuthority.SelectEnvironment in it.authorities }
    return eligible.firstOrNull { it.id == BuiltInRoles.EpaRepresentative.id }
        ?: eligible.firstOrNull()
        ?: error("The active swarm has no role authorized to select execution environments")
}
