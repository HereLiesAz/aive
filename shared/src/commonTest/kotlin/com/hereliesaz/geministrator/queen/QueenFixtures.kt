package com.hereliesaz.geministrator.queen

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.domain.ProjectId
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.RoleDefinitionId
import com.hereliesaz.geministrator.domain.TaskDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.TaskExecutor
import com.hereliesaz.geministrator.domain.TaskRun
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.TaskUsage
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.domain.WorkflowRunStatus

internal val engineerRole: RoleDefinition = BuiltInRoles.ImplementationEngineer

internal fun qTask(id: String, role: RoleDefinitionId = engineerRole.id) = TaskDefinition(
    id = TaskDefinitionId(id),
    name = "Task $id",
    objective = "Do $id",
    roleId = role,
    executor = TaskExecutor.RoleAgent(role),
)

internal fun qDefinition(vararg tasks: TaskDefinition) =
    WorkflowDefinition(id = WorkflowDefinitionId("w"), name = "Swarm", tasks = tasks.toList())

internal fun qTaskRun(
    id: String,
    status: TaskRunStatus,
    attempt: Int = 1,
    role: RoleDefinitionId = engineerRole.id,
    failedProviders: List<String> = emptyList(),
    held: Int = 0,
    tokens: Long = 0,
    cost: Double? = null,
    costUnknown: Boolean = false,
    artifacts: List<com.hereliesaz.geministrator.domain.ArtifactRef> = emptyList(),
) = TaskRun(
    id = TaskRunId("r-$id"),
    taskDefinitionId = TaskDefinitionId(id),
    status = status,
    attempt = attempt,
    assignedRoleId = role,
    failedProviders = failedProviders.map(::AgentProviderId),
    heldAttempts = held,
    usage = if (tokens > 0 || cost != null) TaskUsage(inputTokens = tokens, costUsd = cost, costUnknown = costUnknown) else null,
    artifacts = artifacts,
)

internal fun qRun(
    vararg runs: TaskRun,
    status: WorkflowRunStatus = WorkflowRunStatus.Running,
) = WorkflowRun(
    id = WorkflowRunId("r1"),
    projectId = ProjectId("p"),
    workflowDefinitionId = WorkflowDefinitionId("w"),
    objective = "Ship",
    status = status,
    taskRuns = runs.associateBy { it.taskDefinitionId },
    createdAtEpochMillis = 1,
    updatedAtEpochMillis = 2,
)
