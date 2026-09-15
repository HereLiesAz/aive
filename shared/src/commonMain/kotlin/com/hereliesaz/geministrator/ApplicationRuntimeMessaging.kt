package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.providers.ProviderActionResult
import com.hereliesaz.geministrator.workflow.ManagedSessionHandle

/** Send a user message to the live provider session backing [taskDefinitionId]. */
suspend fun ApplicationRuntime.messageTask(
    taskDefinitionId: TaskDefinitionId,
    message: String,
): ProviderActionResult {
    val cleanMessage = message.trim()
    require(cleanMessage.isNotEmpty()) { "Message must not be blank" }

    val live = state.value as? ApplicationRuntimeState.Live
        ?: error("There is no live workflow to message")
    val taskRun = live.presentation.run.taskRuns[taskDefinitionId]
        ?: error("Task ${taskDefinitionId.value} is not part of the live run")
    val providerId = taskRun.assignedProviderId
        ?: error("Task ${taskDefinitionId.value} has no assigned provider")
    val providerRunId = taskRun.providerRunId
        ?: error("Task ${taskDefinitionId.value} has no provider session")

    return sessionGateway.message(
        ManagedSessionHandle(
            taskRunId = taskRun.id,
            providerId = providerId,
            providerRunId = providerRunId,
        ),
        cleanMessage,
    )
}
