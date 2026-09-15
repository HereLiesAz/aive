package com.hereliesaz.geministrator.persistence

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
}
