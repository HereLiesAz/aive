package com.hereliesaz.geministrator.persistence

import com.russhwolf.settings.Settings

import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.events.WorkflowEvent
import com.hereliesaz.geministrator.workflow.ApprovalGate
import com.hereliesaz.geministrator.workflow.ApprovalGateRepository
import com.hereliesaz.geministrator.workflow.FailureEscalationDecisionStore

interface ProjectRepository {
    suspend fun put(project: Project)
    suspend fun get(id: ProjectId): Project?
    suspend fun all(): List<Project>
}

interface WorkflowDefinitionRepository {
    suspend fun put(definition: WorkflowDefinition)
    suspend fun get(id: WorkflowDefinitionId): WorkflowDefinition?
    suspend fun all(): List<WorkflowDefinition>

    /** Removes this one definition and nothing else: runs, events and roles are left as they are. */
    suspend fun delete(id: WorkflowDefinitionId)
}

interface WorkflowRunRepository {
    suspend fun put(run: WorkflowRun)
    suspend fun get(id: WorkflowRunId): WorkflowRun?
    suspend fun byProject(projectId: ProjectId): List<WorkflowRun>
}

interface WorkflowEventRepository {
    suspend fun append(event: WorkflowEvent)
    suspend fun forRun(workflowRunId: WorkflowRunId): List<WorkflowEvent>
}

interface RoleRepository {
    suspend fun put(role: RoleDefinition)
    suspend fun get(id: RoleDefinitionId): RoleDefinition?
    suspend fun all(): List<RoleDefinition>
}

interface ArtifactRepository {
    suspend fun put(artifact: ArtifactRef)
    suspend fun get(id: ArtifactId): ArtifactRef?
    suspend fun forRun(run: WorkflowRun): List<ArtifactRef>
}

interface WorkflowPersistence : FailureEscalationDecisionStore {
    val projects: ProjectRepository
    val definitions: WorkflowDefinitionRepository
    val runs: WorkflowRunRepository
    val events: WorkflowEventRepository
    val roles: RoleRepository
    val artifacts: ArtifactRepository
    val approvalGates: ApprovalGateRepository

    /**
     * Every run changed at or after [sinceEpochMillis], across every project. A store that can ask for
     * this without reading every run (SQL) overrides it; this reads them all.
     */
    suspend fun runsUpdatedSince(sinceEpochMillis: Long): List<WorkflowRun> =
        projects.all().flatMap { project -> runs.byProject(project.id) }.filter { it.updatedAtEpochMillis >= sinceEpochMillis }

    /** [runId]'s events that happened at or after [sinceEpochMillis], in order. */
    suspend fun eventsSince(runId: WorkflowRunId, sinceEpochMillis: Long): List<WorkflowEvent> =
        events.forRun(runId).filter { it.occurredAtEpochMillis >= sinceEpochMillis }

    /**
     * Where the compound-inference state belonging to this workflow store lives. Defaults to the
     * platform's durable store; a store that is not the app's own (a test's, a scratch import)
     * overrides it so its inference bindings never leak into the app's.
     */
    val inferenceSettings: Settings get() = com.hereliesaz.geministrator.workflow.durableInferenceSettings()

    /**
     * Replace the workflow-definition and role catalogs.
     * NOTE: the default implementation is NOT atomic — override this method for atomic replacement.
     */
    suspend fun replaceCatalog(
        definitions: List<WorkflowDefinition>,
        roles: List<RoleDefinition>,
    ) {
        definitions.forEach { this.definitions.put(it) }
        roles.forEach { this.roles.put(it) }
    }
}
