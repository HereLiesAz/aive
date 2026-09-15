package com.hereliesaz.geministrator.persistence

import com.hereliesaz.geministrator.domain.ApprovalGateId
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
import com.hereliesaz.geministrator.workflow.ApprovalGateStatus
import com.hereliesaz.geministrator.workflow.FailureEscalationDecisionCommit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class InMemoryWorkflowPersistence : WorkflowPersistence {
    private val mutex = Mutex()
    private val projectItems = mutableMapOf<ProjectId, Project>()
    private val definitionItems = mutableMapOf<WorkflowDefinitionId, WorkflowDefinition>()
    private val runItems = mutableMapOf<WorkflowRunId, WorkflowRun>()
    private val eventItems = mutableMapOf<WorkflowRunId, MutableList<WorkflowEvent>>()
    private val roleItems = mutableMapOf<RoleDefinitionId, RoleDefinition>()
    private val artifactItems = mutableMapOf<ArtifactId, ArtifactRef>()
    private val gateItems = mutableMapOf<ApprovalGateId, ApprovalGate>()

    override val projects: ProjectRepository = object : ProjectRepository {
        override suspend fun put(project: Project) {
            mutex.withLock { projectItems[project.id] = project }
        }

        override suspend fun get(id: ProjectId): Project? = mutex.withLock { projectItems[id] }
        override suspend fun all(): List<Project> = mutex.withLock { projectItems.values.toList() }
    }

    override val definitions: WorkflowDefinitionRepository = object : WorkflowDefinitionRepository {
        override suspend fun put(definition: WorkflowDefinition) {
            mutex.withLock { definitionItems[definition.id] = definition }
        }

        override suspend fun get(id: WorkflowDefinitionId): WorkflowDefinition? =
            mutex.withLock { definitionItems[id] }

        override suspend fun all(): List<WorkflowDefinition> =
            mutex.withLock { definitionItems.values.toList() }
    }

    override val runs: WorkflowRunRepository = object : WorkflowRunRepository {
        override suspend fun put(run: WorkflowRun) {
            mutex.withLock { runItems[run.id] = run }
        }

        override suspend fun get(id: WorkflowRunId): WorkflowRun? = mutex.withLock { runItems[id] }

        override suspend fun byProject(projectId: ProjectId): List<WorkflowRun> = mutex.withLock {
            runItems.values.filter { it.projectId == projectId }
        }
    }

    override val events: WorkflowEventRepository = object : WorkflowEventRepository {
        override suspend fun append(event: WorkflowEvent) {
            mutex.withLock {
                eventItems.getOrPut(event.workflowRunId) { mutableListOf() }.add(event)
            }
        }

        override suspend fun forRun(workflowRunId: WorkflowRunId): List<WorkflowEvent> =
            mutex.withLock { eventItems[workflowRunId]?.toList().orEmpty() }
    }

    override val roles: RoleRepository = object : RoleRepository {
        override suspend fun put(role: RoleDefinition) {
            mutex.withLock { roleItems[role.id] = role }
        }

        override suspend fun get(id: RoleDefinitionId): RoleDefinition? = mutex.withLock { roleItems[id] }
        override suspend fun all(): List<RoleDefinition> = mutex.withLock { roleItems.values.toList() }
    }

    override val artifacts: ArtifactRepository = object : ArtifactRepository {
        override suspend fun put(artifact: ArtifactRef) {
            mutex.withLock { artifactItems[artifact.id] = artifact }
        }

        override suspend fun get(id: ArtifactId): ArtifactRef? = mutex.withLock { artifactItems[id] }

        override suspend fun forRun(run: WorkflowRun): List<ArtifactRef> = mutex.withLock {
            val taskRunIds = run.taskRuns.values.map { it.id }.toSet()
            artifactItems.values.filter { it.taskRunId in taskRunIds }
        }
    }

    override val approvalGates: ApprovalGateRepository = object : ApprovalGateRepository {
        override suspend fun put(gate: ApprovalGate) {
            mutex.withLock { gateItems[gate.id] = gate }
        }

        override suspend fun get(id: ApprovalGateId): ApprovalGate? = mutex.withLock { gateItems[id] }

        override suspend fun unresolved(workflowRunId: WorkflowRunId): List<ApprovalGate> =
            mutex.withLock {
                gateItems.values.filter {
                    it.workflowRunId == workflowRunId &&
                        (it.status == ApprovalGateStatus.Pending || it.status == ApprovalGateStatus.Applying)
                }
            }
    }

    override suspend fun commitFailureEscalationDecision(
        commit: FailureEscalationDecisionCommit,
    ): Boolean = mutex.withLock {
        val current = gateItems[commit.expectedGateId] ?: return@withLock false
        if (current.status != ApprovalGateStatus.Pending) return@withLock false
        require(commit.decidedGate.id == current.id) {
            "Escalation decision gate ${commit.decidedGate.id.value} does not match ${current.id.value}"
        }
        require(commit.nextRun.id == current.workflowRunId) {
            "Escalation decision run ${commit.nextRun.id.value} does not match ${current.workflowRunId.value}"
        }
        require(commit.decisionEvent.gateId == current.id) {
            "Escalation decision event does not match gate ${current.id.value}"
        }

        gateItems[current.id] = commit.decidedGate
        runItems[commit.nextRun.id] = commit.nextRun
        eventItems.getOrPut(commit.nextRun.id) { mutableListOf() }.add(commit.decisionEvent)
        true
    }
}
