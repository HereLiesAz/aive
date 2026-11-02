package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.agentui.AgentUi
import com.hereliesaz.geministrator.domain.AgentCapability
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.memory.MEMORY_MESSAGE_MARKER
import com.hereliesaz.geministrator.persistence.InMemorySettings
import com.hereliesaz.geministrator.providers.AgentCapabilities
import com.hereliesaz.geministrator.providers.AgentEvent
import com.hereliesaz.geministrator.providers.AgentProvider
import com.hereliesaz.geministrator.providers.AgentRunHandle
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.ProviderActionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A session's question to the keeper, from its agent's message to the answer (docs/Agent-UI.md). */
class KeeperQuestionGatewayTest {
    private fun request(id: String) = ManagedSessionRequest(
        providerSelection = ProviderSelectionRequest(),
        taskRequest = AgentTaskRequest(
            taskRunId = TaskRunId(id),
            objective = "Deploy the service",
            roleInstructions = "Deploy it.",
            acceptanceCriteria = emptyList(),
        ),
    )

    @Test
    fun aMessagingAgentIsToldHowToAskAndItsQuestionWaitsUntilAnswered() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val provider = AskingProvider(messaging = true)
        try {
            val gateway = ProviderBackedManagedSessionGateway(
                AgentProviderRegistry(listOf(provider), inferenceSettings = InMemorySettings()),
                scope,
            )
            val handle = gateway.createSession(request("ask-task"))

            val block = requireNotNull(provider.startedRequest).promptContext.stablePrefix
                .single { it.label == AgentUi.PROMPT_LABEL }
            assertEquals(AgentUi.promptInstruction, block.content)

            withTimeout(2_000L) {
                while (gateway.keeperQuestion(handle)?.pending == null) delay(10L)
            }
            // Ordinary chatter after the question does not replace it.
            assertEquals(AskingProvider.QUESTION, gateway.keeperQuestion(handle)?.pending)

            val key = AgentUi.questionKey(AskingProvider.QUESTION)
            assertEquals(ProviderActionResult.Accepted, gateway.answerKeeperQuestion(handle, key, "the answer"))
            // The answer goes to the agent as it is, through its session's messaging.
            assertEquals(listOf("the answer"), provider.sent.filterNot { it.startsWith(MEMORY_MESSAGE_MARKER) })
            val answered = gateway.keeperQuestion(handle)
            assertNull(answered?.pending)
            assertEquals(key, answered?.answeredKey)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun anAgentThatCannotBeRepliedToIsNotToldToAsk() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val provider = AskingProvider(messaging = false)
        try {
            val gateway = ProviderBackedManagedSessionGateway(
                AgentProviderRegistry(listOf(provider), inferenceSettings = InMemorySettings()),
                scope,
            )
            gateway.createSession(request("quiet-task"))
            assertTrue(requireNotNull(provider.startedRequest).promptContext.stablePrefix.none { it.label == AgentUi.PROMPT_LABEL })
        } finally {
            scope.cancel()
        }
    }
}

private class AskingProvider(private val messaging: Boolean) : AgentProvider {
    override val id = AgentProviderId("asking-provider")
    var startedRequest: AgentTaskRequest? = null
    val sent = mutableListOf<String>()

    override suspend fun capabilities() = AgentCapabilities(
        supported = if (messaging) setOf(AgentCapability.Messaging) else setOf(AgentCapability.RepositoryRead),
    )

    override suspend fun start(request: AgentTaskRequest): AgentRunHandle {
        startedRequest = request
        return AgentRunHandle(ProviderRunId("asking-run"))
    }

    override fun observe(runId: ProviderRunId): Flow<AgentEvent> = flow {
        emit(AgentEvent.Message(runId, QUESTION))
        emit(AgentEvent.Message(runId, "Waiting for your answer."))
    }

    override suspend fun sendMessage(runId: ProviderRunId, message: String): ProviderActionResult {
        sent += message
        return ProviderActionResult.Accepted
    }

    override suspend fun approvePlan(runId: ProviderRunId) = ProviderActionResult.Accepted

    override suspend fun cancel(runId: ProviderRunId) = ProviderActionResult.Accepted

    companion object {
        const val QUESTION = "Which plan?\n```a2ui\n{\"surfaceUpdate\": {\"surfaceId\": \"s\", \"components\": [" +
            "{\"id\": \"root\", \"component\": {\"Text\": {\"text\": {\"literalString\": \"Small or large?\"}}}}]}}\n```"
    }
}
