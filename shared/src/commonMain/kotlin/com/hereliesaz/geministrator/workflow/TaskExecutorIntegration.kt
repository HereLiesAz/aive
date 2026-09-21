package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import kotlinx.serialization.Serializable
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.inference.InferenceDataDescriptor
import com.hereliesaz.geministrator.inference.InferenceDataKind
import com.hereliesaz.geministrator.inference.InferenceDataRegistry
import com.hereliesaz.geministrator.inference.SettingsCompoundInferenceFabric
import com.hereliesaz.geministrator.inference.SettingsInferenceGenealogyGraph
import com.hereliesaz.geministrator.inference.SettingsInferenceStateStore
import com.hereliesaz.geministrator.orchestration.DeterministicLocalOrchestrationUtilities
import com.hereliesaz.geministrator.orchestration.LocalOrchestrationUtilityFamily
import com.hereliesaz.geministrator.orchestration.ToolCapability
import com.hereliesaz.geministrator.orchestration.ToolRouteDecision
import com.hereliesaz.geministrator.orchestration.ToolRoutingInput
import com.hereliesaz.geministrator.persistence.ChunkedStringSettings
import com.russhwolf.settings.Settings

/**
 * Runtime driver for executor kinds that are not agent sessions or human approval gates.
 *
 * Implementations own the external system lifecycle and return normalized task state so the
 * workflow runtime never has to pretend an undriven executor is running.
 */
interface TaskExecutorIntegration {
    /** Stable human-readable identifier exposed to the local Tool Router. */
    val orchestrationToolId: String
        get() = "task-executor-integration"

    fun supports(executor: TaskExecutor): Boolean

    fun supports(executor: TaskExecutor, project: Project): Boolean = supports(executor)

    /**
     * Whether a dispatch failure can safely be sent through the workflow retry policy.
     * Integrations that perform non-idempotent remote mutations must opt out.
     */
    val retryDispatchFailures: Boolean
        get() = true

    suspend fun dispatch(context: TaskExecutorContext): TaskExecutorExecution

    suspend fun reconcile(context: TaskExecutorContext): TaskExecutorExecution
}

data class TaskExecutorContext(
    val project: Project,
    val definition: WorkflowDefinition,
    val run: WorkflowRun,
    val task: TaskDefinition,
    val taskRun: TaskRun,
    val executor: TaskExecutor,
    val nowEpochMillis: Long,
    val role: RoleDefinition? = null,
)

@Serializable
data class AiveExecutorArtifactEnvelope(
    val id: String,
    val kind: String,
    val label: String,
    val uri: String? = null,
    val textContent: String? = null,
    val mediaType: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
data class AiveExecutorRoleEnvelope(
    val id: String,
    val name: String,
    val description: String,
    val instructions: String,
    val requiredCapabilities: List<String> = emptyList(),
    val authorities: List<String> = emptyList(),
)

@Serializable
data class AiveExecutorRepositoryEnvelope(
    val source: String,
    val owner: String,
    val name: String,
    val defaultBranch: String? = null,
    val remoteUrl: String? = null,
)

@Serializable
@Serializable
data class AiveScriptArtifactResult(
    val label: String,
    val kind: String = "CommandOutput",
    val uri: String? = null,
    val textContent: String? = null,
    val mediaType: String? = null,
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
data class AiveScriptResult(
    val status: String = "completed",
    val message: String? = null,
    val output: String? = null,
    val artifacts: List<AiveScriptArtifactResult> = emptyList(),
)

data class AiveTaskEnvelope(
    val version: Int = 1,
    val projectId: String,
    val projectName: String,
    val repository: AiveExecutorRepositoryEnvelope? = null,
    val workflowDefinitionId: String,
    val workflowRunId: String,
    val workflowObjective: String,
    val taskId: String,
    val taskRunId: String,
    val taskName: String,
    val taskObjective: String,
    val role: AiveExecutorRoleEnvelope? = null,
    val acceptanceCriteria: List<String> = emptyList(),
    val attempt: Int,
    val dependencyArtifacts: List<AiveExecutorArtifactEnvelope> = emptyList(),
)

fun TaskExecutorContext.toAiveTaskEnvelope(): AiveTaskEnvelope {
    val redaction = definition.payloadRedactionPolicy
    val artifacts = task.dependsOn
        .flatMap { dependencyId -> run.taskRuns[dependencyId]?.artifacts.orEmpty() }
        .filterNot { it.kind in redaction.excludedArtifactKinds }
        .map { artifact ->
            AiveExecutorArtifactEnvelope(
                id = artifact.id.value,
                kind = artifact.kind.name,
                label = artifact.label,
                uri = artifact.uri,
                textContent = artifact.textContent,
                mediaType = artifact.mediaType,
                metadata = artifact.metadata,
            )
        }
    return AiveTaskEnvelope(
        projectId = project.id.value,
        projectName = project.name,
        repository = project.repository?.let { repository ->
            AiveExecutorRepositoryEnvelope(
                source = repository.source.name,
                owner = repository.owner,
                name = repository.name,
                defaultBranch = repository.defaultBranch,
                remoteUrl = repository.remoteUrl,
            )
        },
        workflowDefinitionId = definition.id.value,
        workflowRunId = run.id.value,
        workflowObjective = run.objective,
        taskId = task.id.value,
        taskRunId = taskRun.id.value,
        taskName = task.name,
        taskObjective = if (redaction.redactObjective) "[REDACTED]" else task.objective,
        role = role?.let { role ->
            AiveExecutorRoleEnvelope(
                id = role.id.value,
                name = role.name,
                description = role.description,
                instructions = if (redaction.redactRoleInstructions) "[REDACTED]" else role.instructions,
                requiredCapabilities = role.capabilitiesRequired.map { it.name }.sorted(),
                authorities = role.authorities.map { it.name }.sorted(),
            )
        },
        acceptanceCriteria = task.acceptanceCriteria.map { it.description },
        attempt = taskRun.attempt,
        dependencyArtifacts = artifacts,
    )
}

data class TaskExecutorExecution(
    val status: TaskRunStatus,
    val externalRunId: String? = null,
    val artifacts: List<ArtifactRef> = emptyList(),
    val progress: Float? = null,
    val progressMessage: String? = null,
) {
    init {
        require(status in supportedStatuses) {
            "Executor integrations may only report Running, Verifying, Completed, or Failed"
        }
        require(progress == null || progress in 0f..1f) { "Task progress must be normalized 0f..1f" }
    }

    private companion object {
        val supportedStatuses = setOf(
            TaskRunStatus.Running,
            TaskRunStatus.Verifying,
            TaskRunStatus.Completed,
            TaskRunStatus.Failed,
        )
    }
}

class TaskExecutorIntegrationRegistry(
    integrations: Collection<TaskExecutorIntegration> = emptyList(),
    private val inferenceDataRegistry: InferenceDataRegistry? =
        integrations.takeIf(Collection<TaskExecutorIntegration>::isNotEmpty)?.let { durableExecutorEvidenceRegistry() },
    private val orchestrationUtilities: LocalOrchestrationUtilityFamily =
        DeterministicLocalOrchestrationUtilities,
) {
    private val integrations = integrations.toList()

    fun integrationFor(executor: TaskExecutor): TaskExecutorIntegration? =
        routeIntegration(executor, integrations.filter { it.supports(executor) })?.withEvidenceIndexing()

    fun integrationFor(executor: TaskExecutor, project: Project): TaskExecutorIntegration? =
        routeIntegration(executor, integrations.filter { it.supports(executor, project) })?.withEvidenceIndexing()

    fun isAvailable(executor: TaskExecutor): Boolean =
        integrations.any { it.supports(executor) }

    fun isAvailable(executor: TaskExecutor, project: Project): Boolean =
        integrations.any { it.supports(executor, project) }

    fun withIntegration(integration: TaskExecutorIntegration): TaskExecutorIntegrationRegistry =
        TaskExecutorIntegrationRegistry(
            integrations + integration,
            inferenceDataRegistry ?: durableExecutorEvidenceRegistry(),
            orchestrationUtilities,
        )

    fun withPriorityIntegration(integration: TaskExecutorIntegration): TaskExecutorIntegrationRegistry =
        TaskExecutorIntegrationRegistry(
            listOf(integration) + integrations,
            inferenceDataRegistry ?: durableExecutorEvidenceRegistry(),
            orchestrationUtilities,
        )

    /**
     * Return the same executor set with direct inference-evidence indexing enabled.
     *
     * The workflow artifact repository remains authoritative for payloads. This merely registers
     * symbolic ToolEvidence references in the inference data registry as soon as a system executor
     * reports them, without fabricating an agent/provider invocation.
     */
    fun withInferenceDataRegistry(registry: InferenceDataRegistry): TaskExecutorIntegrationRegistry =
        TaskExecutorIntegrationRegistry(integrations, registry, orchestrationUtilities)

    fun withOrchestrationUtilities(
        utilities: LocalOrchestrationUtilityFamily,
    ): TaskExecutorIntegrationRegistry =
        TaskExecutorIntegrationRegistry(integrations, inferenceDataRegistry, utilities)

    private fun routeIntegration(
        executor: TaskExecutor,
        candidates: List<TaskExecutorIntegration>,
    ): TaskExecutorIntegration? {
        if (candidates.isEmpty()) return null
        val operationClass = executor.orchestrationOperationClass()
        val ids = candidates.mapIndexed { index, integration ->
            "tool-${index.toString().padStart(4, '0')}:${integration.orchestrationToolId}"
        }
        val route = orchestrationUtilities.routeTool(
            ToolRoutingInput(
                operationClass = operationClass,
                capabilities = ids.mapIndexed { index, id ->
                    ToolCapability(
                        id = id,
                        operationClasses = setOf(operationClass),
                        preferenceRank = index,
                    )
                },
            ),
        )
        if (route.decision != ToolRouteDecision.Tool) return null
        val selectedIndex = ids.indexOf(route.tool)
        return candidates.getOrNull(selectedIndex)
    }

    private fun TaskExecutorIntegration.withEvidenceIndexing(): TaskExecutorIntegration {
        val registry = inferenceDataRegistry ?: return this
        return EvidenceIndexingTaskExecutorIntegration(this, registry)
    }

    companion object {
        val Empty = TaskExecutorIntegrationRegistry(
            emptyList(),
            null,
            DeterministicLocalOrchestrationUtilities,
        )
    }
}

private class EvidenceIndexingTaskExecutorIntegration(
    private val delegate: TaskExecutorIntegration,
    private val dataRegistry: InferenceDataRegistry,
) : TaskExecutorIntegration {
    override fun supports(executor: TaskExecutor): Boolean = delegate.supports(executor)

    override fun supports(executor: TaskExecutor, project: Project): Boolean =
        delegate.supports(executor, project)

    override val retryDispatchFailures: Boolean
        get() = delegate.retryDispatchFailures

    override suspend fun dispatch(context: TaskExecutorContext): TaskExecutorExecution =
        delegate.dispatch(context).indexEvidence()

    override suspend fun reconcile(context: TaskExecutorContext): TaskExecutorExecution =
        delegate.reconcile(context).indexEvidence()

    private suspend fun TaskExecutorExecution.indexEvidence(): TaskExecutorExecution {
        artifacts.forEach { artifact ->
            dataRegistry.register(
                InferenceDataDescriptor(
                    dataId = "artifact:${artifact.id.value}",
                    kind = InferenceDataKind.ToolEvidence,
                    artifactId = artifact.id,
                    producingTaskRunId = artifact.taskRunId,
                    label = artifact.label,
                    mediaType = artifact.mediaType,
                ),
            )
        }
        return this
    }
}


private fun TaskExecutor.orchestrationOperationClass(): String = when (this) {
    is TaskExecutor.GitHubAction -> "github-action"
    is TaskExecutor.Script -> "script"
    is TaskExecutor.TestRunner -> "test"
    is TaskExecutor.Deployment -> "deploy"
    is TaskExecutor.RepositoryOperation -> "repository-operation"
    is TaskExecutor.ExternalService -> "external-service"
    is TaskExecutor.NestedWorkflow -> "nested-workflow"
    is TaskExecutor.Distributed -> "distributed"
    is TaskExecutor.RoleAgent -> "agent"
    is TaskExecutor.HumanApproval -> "human-approval"
}

private fun durableExecutorEvidenceRegistry(): InferenceDataRegistry {
    val settings = ChunkedStringSettings(
        delegate = Settings(),
        chunkedKeys = setOf(
            SettingsInferenceStateStore.DEFAULT_STORAGE_KEY,
            SettingsInferenceGenealogyGraph.DEFAULT_STORAGE_KEY,
        ),
    )
    return SettingsCompoundInferenceFabric(SettingsInferenceStateStore(settings)).dataRegistry
}

fun TaskExecutor.isSystemExecutor(): Boolean = when (this) {
    is TaskExecutor.GitHubAction,
    is TaskExecutor.Script,
    is TaskExecutor.TestRunner,
    is TaskExecutor.Deployment,
    is TaskExecutor.RepositoryOperation,
    is TaskExecutor.ExternalService,
    is TaskExecutor.NestedWorkflow,
    is TaskExecutor.Distributed,
    -> true
    is TaskExecutor.RoleAgent,
    is TaskExecutor.HumanApproval,
    -> false
}
