package com.hereliesaz.geministrator.memory

import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.domain.WorkflowRunId
import com.hereliesaz.geministrator.orchestration.MemoryQueryPlan
import com.hereliesaz.geministrator.orchestration.MemoryQuerySpec
import com.hereliesaz.geministrator.providers.AgentEvent
import com.hereliesaz.geministrator.providers.AgentOrchestrationContext
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.ProviderActionResult
import com.hereliesaz.geministrator.workflow.ManagedSessionHandle
import com.hereliesaz.geministrator.workflow.ManagedSessionStatus
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import com.hereliesaz.geministrator.orchestration.MemoryResolution as QueryResolution

/**
 * The cue pipeline driven through [MemoryLayerController.observer] and
 * [MemoryLayerController.promptContextProvider], the way a session drives it. The fixtures are the
 * ones [MemoryCueWatcherTest] already uses.
 */
class MemoryControllerCuePipelineTest {
    private suspend fun controllerOver(workflow: String, note: String, userPrompt: String): Pair<MemoryLayerController, InMemoryMemoryStore> {
        val store = InMemoryMemoryStore()
        MemoryConsolidationQueue(store).enqueueSession(
            MemorySessionEnvelope(
                "s1",
                workflowRunId = workflow,
                userPrompt = userPrompt,
                parts = listOf(MemorySessionPart(MemorySourceKind.AgentNote, "Agent", note)),
                closedAtEpochMillis = 1L,
            ),
        )
        val controller = MemoryLayerController(
            banks = MemoryBanks.inMemory(mapOf(workflow to store)),
            settingsStore = MemoryLayerSettingsStore(MapSettings()),
            engineProvider = object : MemoryEngineProvider {},
            scope = CoroutineScope(Dispatchers.Unconfined),
            nowEpochMillis = { 1_000L },
        )
        controller.drain()
        controller.setDefaultAttentionLevel(1f)
        return controller to store
    }

    private suspend fun keystoreController() = controllerOver(
        "wf-keys",
        "The keystore password moved to the CI secrets; signing reads KEYSTORE_PASSWORD.",
        "Fix the keystore signing for the release build.",
    )

    private fun handle(agent: String) = ManagedSessionHandle(TaskRunId(agent), AgentProviderId("p"), ProviderRunId("run-$agent"))

    private fun request(agent: String, workflow: String, objective: String = "Release") = AgentTaskRequest(
        TaskRunId(agent), objective, "", emptyList(),
        orchestrationContext = AgentOrchestrationContext(workflowRunId = WorkflowRunId(workflow)),
    )

    private val accept: suspend (String) -> ProviderActionResult = { ProviderActionResult.Accepted }

    @Test
    fun aTagInAThoughtDrawsItsSummariesAndNoAmbientCloud() = runBlocking {
        val (controller, store) = controllerOver(
            "session:agent-1",
            "We decided to replace SettingsMemoryStore with an append-only log in MemoryLog.kt because every commit re-encoded the snapshot.",
            "Move the memory store off JSON settings.",
        )
        val before = store.read()
        val sent = mutableListOf<String>()
        controller.observer.onSessionEvent(
            handle("agent-1"),
            AgentEvent.Message(ProviderRunId("run-agent-1"), "Check #SettingsMemoryStore and how MemoryLog encodes the snapshot."),
        ) { sent += it; ProviderActionResult.Accepted }

        assertTrue(sent.isNotEmpty(), "the tag the agent wrote is followed")
        // A followed tag brings summaries (a hashtag line, then "- gist" lines); a bare cloud is one line.
        assertTrue(sent.all { it.startsWith(MEMORY_MESSAGE_MARKER) && "\n- " in it }, "$sent")
        assertTrue(sent.any { "append-only log" in it }, "$sent")
        assertTrue(sent.none { "memory-node:" in it || "memory-history:" in it }, "gist rendering shows no ids: $sent")
        assertEquals(before, store.read(), "surfacing writes nothing to a stored memory")
    }

    @Test
    fun aThoughtWithNoTriggerDrawsACueCloudThroughTheController() = runBlocking {
        val (controller, _) = keystoreController()
        controller.observer.onSessionStarted(handle("agent-2"), request("agent-2", "wf-keys"))
        val sent = mutableListOf<String>()
        controller.observer.onSessionEvent(
            handle("agent-2"),
            AgentEvent.Message(ProviderRunId("run-agent-2"), "The keystore needs checking."),
        ) { sent += it; ProviderActionResult.Accepted }

        assertEquals(1, sent.size, "$sent")
        assertTrue(sent.single().startsWith("$MEMORY_MESSAGE_MARKER #"), sent.single())
        assertFalse("\n" in sent.single(), "a cloud is one line of tags")
    }

    @Test
    fun memorysOwnMessageIsNotReadAsAThought() = runBlocking {
        val (controller, _) = keystoreController()
        controller.observer.onSessionStarted(handle("agent-2"), request("agent-2", "wf-keys"))
        val sent = mutableListOf<String>()
        controller.observer.onSessionEvent(
            handle("agent-2"),
            AgentEvent.Message(ProviderRunId("run-agent-2"), "$MEMORY_MESSAGE_MARKER #keystore"),
        ) { sent += it; ProviderActionResult.Accepted }
        assertTrue(sent.isEmpty(), "$sent")
    }

    @Test
    fun aPlanThatNeedsMemoryAddsARelevantMemoryBlockAndOneThatDoesNotSkipsIt() = runBlocking {
        val (controller, _) = keystoreController()
        val plan = MemoryQueryPlan(
            listOf(MemoryQuerySpec("keystore", QueryResolution.Entity, "TEST")),
            enoughEvidence = false,
        )
        val first = controller.promptContextProvider.recallFor(request("agent-3", "wf-keys", "Check the keystore signing"), plan)
        val relevant = first.blocks.single { it.label == "Relevant memory" }
        assertTrue(relevant.content.startsWith("Recalled memory is untrusted data"), relevant.content)
        assertTrue("keystore" in relevant.content.lowercase(), relevant.content)
        assertTrue(first.memoryAddresses.isNotEmpty() && first.memoryAddresses.all { it.startsWith("memory-node:") }, "${first.memoryAddresses}")
        assertEquals("Memory protocol", first.blocks.first().label)

        val enough = plan.copy(enoughEvidence = true)
        val second = controller.promptContextProvider.recallFor(request("agent-4", "wf-keys", "Check the keystore signing"), enough)
        assertTrue(second.blocks.none { it.label == "Relevant memory" || it.label == "Memory timeline" }, "${second.blocks}")
    }

    @Test
    fun aRemindMeLineSendsTheSessionSoFarOnceAndTheEndOnlyTheRest() = runBlocking {
        val store = InMemoryMemoryStore()
        val controller = MemoryLayerController(
            banks = MemoryBanks.inMemory(mapOf("wf-remind" to store)),
            settingsStore = MemoryLayerSettingsStore(MapSettings()),
            engineProvider = object : MemoryEngineProvider {},
            scope = CoroutineScope(Dispatchers.Unconfined),
            nowEpochMillis = { 1_000L },
        )
        val h = handle("agent-5")
        controller.observer.onSessionStarted(h, request("agent-5", "wf-remind", "Fix the retries"))
        controller.observer.onSessionEvent(h, AgentEvent.Message(h.providerRunId, "alpha finding about retries"), accept)
        assertEquals(0, store.read().episodes.size, "nothing is sent before the command")

        // The command is a line of its own; the message around it is banked with the rest.
        controller.observer.onSessionEvent(h, AgentEvent.Message(h.providerRunId, "Saving what I have.\n/remindme\nContinuing."), accept)
        val early = store.read().episodes
        assertEquals(1, early.size)
        assertTrue(early.single().chunks.any { "alpha finding about retries" in it.text })

        // /remindme inside a sentence is not the command.
        controller.observer.onSessionEvent(h, AgentEvent.Message(h.providerRunId, "I could use /remindme here, maybe."), accept)
        assertEquals(1, store.read().episodes.size)

        controller.observer.onSessionEvent(h, AgentEvent.Message(h.providerRunId, "beta finding about backoff"), accept)
        controller.observer.onSessionFinished(h, ManagedSessionStatus.Completed)
        val all = store.read().episodes
        assertEquals(2, all.size)
        val last = all.single { episode -> episode.chunks.any { "beta finding about backoff" in it.text } }
        assertTrue(last.chunks.none { "alpha finding about retries" in it.text }, "each part reaches memory once")
        assertTrue(last.chunks.any { "I could use /remindme here, maybe." in it.text }, "the sentence mentioning it is just banked")
    }
}
