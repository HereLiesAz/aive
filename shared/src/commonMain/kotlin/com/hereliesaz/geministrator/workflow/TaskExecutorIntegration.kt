package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.inference.InferenceDataDescriptor
import com.hereliesaz.geministrator.inference.InferenceDataKind
import com.hereliesaz.geministrator.inference.InferenceDataRegistry
import com.hereliesaz.geministrator.inference.SettingsCompoundInferenceFabric
import com.hereliesaz.geministrator.inference.SettingsInferenceGenealogyGraph
import com.hereliesaz.geministrator.inference.SettingsInferenceStateStore
import com.hereliesaz.geministrator.persistence.ChunkedStringSettings
import com.russhwolf.settings.Settings

/**
 * Runtime driver for executor kinds that are not agent sessions or human approval gates.
 *
 * Implementations own the external system lifecycle and return normalized task state so the
 * workflow runtime never has to pretend an undriven executor is running.
 */
interface TaskExecutorIntegration {
    fun supports(executor: TaskExecutor): Boolean

    fun supports(executor: TaskExecutor, project: Project): Boolean = supports(executor)

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
)

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
) {
    private val integrations = integrations.toList()

    fun integrationFor(executor: TaskExecutor): TaskExecutorIntegration? =
        integrations.firstOrNull { it.supports(executor) }?.withEvidenceIndexing()

    fun integrationFor(executor: TaskExecutor, project: Project): TaskExecutorIntegration? =
        integrations.firstOrNull { it.supports(executor, project) }?.withEvidenceIndexing()

    fun isAvailable(executor: TaskExecutor): Boolean =
        integrations.any { it.supports(executor) }

    fun isAvailable(executor: TaskExecutor, project: Project): Boolean =
        integrations.any { it.supports(executor, project) }

    fun withIntegration(integration: TaskExecutorIntegration): TaskExecutorIntegrationRegistry =
        TaskExecutorIntegrationRegistry(integrations + integration, inferenceDataRegistry ?: durableExecutorEvidenceRegistry())

    /**
     * Return the same executor set with direct inference-evidence indexing enabled.
     *
     * The workflow artifact repository remains authoritative for payloads. This merely registers
     * symbolic ToolEvidence references in the inference data registry as soon as a system executor
     * reports them, without fabricating an agent/provider invocation.
     */
    fun withInferenceDataRegistry(registry: InferenceDataRegistry): TaskExecutorIntegrationRegistry =
        TaskExecutorIntegrationRegistry(integrations, registry)

    private fun TaskExecutorIntegration.withEvidenceIndexing(): TaskExecutorIntegration {
        val registry = inferenceDataRegistry ?: return this
        return EvidenceIndexingTaskExecutorIntegration(this, registry)
    }

    companion object {
        val Empty = TaskExecutorIntegrationRegistry(emptyList(), null)
    }
}

private class EvidenceIndexingTaskExecutorIntegration(
    private val delegate: TaskExecutorIntegration,
    private val dataRegistry: InferenceDataRegistry,
) : TaskExecutorIntegration {
    override fun supports(executor: TaskExecutor): Boolean = delegate.supports(executor)

    override fun supports(executor: TaskExecutor, project: Project): Boolean =
        delegate.supports(executor, project)

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
