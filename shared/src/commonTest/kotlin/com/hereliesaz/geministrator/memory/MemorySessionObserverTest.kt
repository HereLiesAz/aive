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
import kotlin.test.assertEquals
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

    private fun partTexts(episode: MemoryEpisode): List<String> =
        episode.chunks.filter { it.kind != MemorySourceKind.UserPrompt }.map { it.text }

    @Test
    fun remindMeSendsTheSessionSoFarOnceAndTheEndSendsOnlyWhatCameAfter() = runBlocking {
        val (store, observer, handle) = fixture()
        observer.onSessionStarted(handle, request())
        observer.onSessionEvent(handle, AgentEvent.Message(handle.providerRunId, "alpha finding with Authorization: Bearer $key"))
        observer.onRemindMe(handle)

        val early = store.read().episodes
        assertEquals(1, early.size)
        assertTrue("alpha finding" in partTexts(early.single()).joinToString("\n"))
        assertFalse(key in bankedText(store), "the early send is scrubbed like the final one")
        assertFalse("Completed" in partTexts(early.single()), "no terminal status yet")

        // The capture survived: later parts still collect, and the end sends only those plus the status.
        observer.onSessionEvent(handle, AgentEvent.Message(handle.providerRunId, "beta finding"))
        observer.onSessionFinished(handle, ManagedSessionStatus.Completed)

        val episodes = store.read().episodes
        assertEquals(2, episodes.size)
        assertEquals(2, episodes.map { it.id.value }.distinct().size, "same session, same millisecond, still two ids")
        val first = episodes.single { "alpha finding" in partTexts(it).joinToString("\n") }
        val last = episodes.single { "beta finding" in partTexts(it).joinToString("\n") }
        assertFalse("beta finding" in partTexts(first).joinToString("\n"))
        assertFalse("alpha finding" in partTexts(last).joinToString("\n"), "each part reaches memory once")
        assertEquals(listOf("beta finding", "Completed"), partTexts(last))
    }

    @Test
    fun remindMeWithNothingNewDoesNothing() = runBlocking {
        val (store, observer, handle) = fixture()
        observer.onRemindMe(handle) // no capture yet
        assertEquals(0, store.read().episodes.size)
        observer.onSessionStarted(handle, request())
        observer.onRemindMe(handle) // the starting parts go
        observer.onRemindMe(handle) // nothing new
        assertEquals(1, store.read().episodes.size)
        observer.onSessionFinished(handle, ManagedSessionStatus.Failed)
        assertEquals(2, store.read().episodes.size)
        assertEquals(listOf("Failed"), partTexts(store.read().episodes.single { "Failed" in partTexts(it) }))
    }
}
