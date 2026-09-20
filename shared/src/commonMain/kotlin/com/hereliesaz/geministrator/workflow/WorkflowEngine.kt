package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ApprovalPolicy
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.Project
import com.hereliesaz.geministrator.domain.ProviderConstraints
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.RetryReason
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunStatus
import com.hereliesaz.geministrator.domain.effectiveExecutor
import com.hereliesaz.geministrator.events.AgentAssigned
import com.hereliesaz.geministrator.events.ArtifactCreated
import com.hereliesaz.geministrator.events.ExecutorAssigned
import com.hereliesaz.geministrator.events.HumanDecisionRequired
import com.hereliesaz.geministrator.events.NoOpWorkflowEventSink
import com.hereliesaz.geministrator.events.ProviderUsageRecorded
import com.hereliesaz.geministrator.events.RetryScheduled
import com.hereliesaz.geministrator.events.TaskCompleted
import com.hereliesaz.geministrator.events.TaskEscalated
import com.hereliesaz.geministrator.events.TaskFailed
import com.hereliesaz.geministrator.events.TaskStarted
import com.hereliesaz.geministrator.events.TaskCancelled
import com.hereliesaz.geministrator.events.WorkflowCancelled
import com.hereliesaz.geministrator.events.WorkflowCompleted
import com.hereliesaz.geministrator.events.WorkflowEventSink
import com.hereliesaz.geministrator.events.WorkflowFailed
import com.hereliesaz.geministrator.providers.AgentOrchestrationContext
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.PromptContext
import com.hereliesaz.geministrator.providers.PromptContextBlock
import com.hereliesaz.geministrator.providers.ProviderArtifact
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

class WorkflowEngine(
    private val sessionGateway: ManagedSessionGateway,
    roles: Collection<RoleDefinition>,
    private val eventSink: WorkflowEventSink = NoOpWorkflowEventSink,
) {
    private val rolesById: Map<RoleDefinitionId, RoleDefinition> = roles.associateBy { it.id }