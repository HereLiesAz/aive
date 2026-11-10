package com.hereliesaz.geministrator.memory

import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.providers.AgentEvent
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.workflow.ManagedSessionHandle
import com.hereliesaz.geministrator.workflow.ManagedSessionStatus
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemorySessionObserverTest {
    private val key = "ghp_abcdefghijklmnopqrstuvwxyz0123456789"

    private fun fixture(): Triple<InMemoryMemoryStore, QueuedMemorySessionObserver, ManagedSessionHandle> {
        val store = InMemoryMemoryStore()
        val handle = ManagedSessionHandle(TaskRunId("task"), AgentProviderId("p"), ProviderRunId("run"))
        return Triple(store, QueuedMemorySessionObserver(MemoryConsolidationQueue(store), nowEpochMillis = { 5L }), handle)
    }

    private fun request(instructions: String = "Implement the change.") = AgentTaskRequest(
        taskRunId = TaskRunId("task"),
        objective = "Fix the build",
        roleInstructions = instructions,
        acceptanceCriteria = emptyList(),
    )

    private suspend fun bankedText(store: InMemoryMemoryStore): String =
        store.read().episodes.flatMap { e -> e.chunks.map { it.text } + e.userPrompt }.joinToString("\n")

    @Test
    fun keysInRoleInstructionsAndMessagesAreScrubbedBeforeBanking() = runBlocking {
        val (store, observer, handle) = fixture()
        observer.onSessionStarted(handle, request("Use token=$key when calling the API."))
        observer.onSessionEvent(handle, AgentEvent.Message(handle.providerRunId, "I used Authorization: Bearer $key"))
        observer.onSessionFinished(handle, ManagedSessionStatus.Completed)

        val text = bankedText(store)
        assertTrue(text.isNotBlank())
        assertFalse(key in text, text)
    }

    @Test
    fun anAgentMessageThatMerelyStartsWithTheMarkerIsStillBankedButMemorysOwnIsNot() = runBlocking {
        val (store, observer, handle) = fixture()
        observer.onSessionStarted(handle, request())
        observer.noteIssued("$MEMORY_MESSAGE_MARKER #gateway")
        observer.onSessionEvent(handle, AgentEvent.Message(handle.providerRunId, "$MEMORY_MESSAGE_MARKER #gateway"))
        observer.onSessionEvent(handle, AgentEvent.Message(handle.providerRunId, "$MEMORY_MESSAGE_MARKER sneaky agent finding about the cache"))
        observer.onSessionFinished(handle, ManagedSessionStatus.Completed)

        val text = bankedText(store)
        assertTrue("sneaky agent finding" in text, text)
        assertFalse("#gateway" in text, text)
    }
}
