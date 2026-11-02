package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.agentui.AgentUi
import com.hereliesaz.geministrator.agentui.awaitsKeeperAnswer
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

/**
 * Sends the keeper's [answer] to the question the agent behind [taskDefinitionId] is waiting on
 * (docs/Agent-UI.md), through the same session messaging as any message to it. Once the session
 * accepts it, the question is marked answered and the wait ends on the next cycle.
 *
 * [questionKey] names the question answered (`AgentUi.questionKey`); an answer to a question that
 * is no longer open is refused, so a stale form cannot answer a newer question.
 */
suspend fun ApplicationRuntime.answerKeeperQuestion(
    taskDefinitionId: TaskDefinitionId,
    questionKey: String,
    answer: String,
): ProviderActionResult {
    val cleanAnswer = answer.trim()
    require(cleanAnswer.isNotEmpty()) { "Answer must not be blank" }

    val live = state.value as? ApplicationRuntimeState.Live
        ?: error("There is no live workflow to answer")
    val taskRun = live.presentation.run.taskRuns[taskDefinitionId]
        ?: error("Task ${taskDefinitionId.value} is not part of the live run")
    val question = taskRun.keeperQuestion?.takeIf { taskRun.awaitsKeeperAnswer() }
        ?: return ProviderActionResult.Rejected("Task ${taskDefinitionId.value} is not waiting on an answer")
    if (AgentUi.questionKey(question) != questionKey) {
        return ProviderActionResult.Rejected("The question changed; answer the new one")
    }
    val providerId = taskRun.assignedProviderId
        ?: error("Task ${taskDefinitionId.value} has no assigned provider")
    val providerRunId = taskRun.providerRunId
        ?: error("Task ${taskDefinitionId.value} has no provider session")

    return sessionGateway.answerKeeperQuestion(
        ManagedSessionHandle(
            taskRunId = taskRun.id,
            providerId = providerId,
            providerRunId = providerRunId,
        ),
        questionKey,
        cleanAnswer,
    )
}
