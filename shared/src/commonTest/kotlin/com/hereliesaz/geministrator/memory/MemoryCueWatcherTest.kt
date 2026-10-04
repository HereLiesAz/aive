package com.hereliesaz.geministrator.memory

import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.orchestration.MemoryQueryPlan
import com.hereliesaz.geministrator.providers.AgentEvent
import com.hereliesaz.geministrator.domain.AgentProviderId
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.ProviderActionResult
import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.workflow.ManagedSessionHandle
import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MemoryCueWatcherTest {
    private val never: suspend (String) -> Boolean = { false }

    @Test
    fun explicitTagsAndTheRecallPhraseAreDeliberate() = runBlocking {
        val triggers = MemoryCueWatcher().read("Checking #gradle-cache before PR #123. Let me see what I remember about the release signing.", never)
        assertEquals(
            listOf(MemoryRecallTrigger.Explicit("gradle cache"), MemoryRecallTrigger.Phrase("the release signing")),
            triggers.filter { it.deliberate },
        )
    }

    @Test
    fun echoingAJustOfferedCueFollowsIt() = runBlocking {
        val watcher = MemoryCueWatcher()
        watcher.offered(listOf("rollback"))
        assertTrue(MemoryRecallTrigger.Echo("rollback") in watcher.read("A rollback might be safer here.", never))
        // Long after the cue, the same word is no longer an echo.
        watcher.read(List(60) { "filler$it" }.joinToString(" "), never)
        assertTrue(watcher.read("rollback again", never).none { it is MemoryRecallTrigger.Echo })
    }

    @Test
    fun aWordUsedTwiceCallsItUpUnlessItIsCommon() = runBlocking {
        val doubled = MemoryCueWatcher().read("The keystore path is wrong; the keystore was moved.", never)
        assertTrue(MemoryRecallTrigger.Doubled("keystore") in doubled, "$doubled")
        val common: suspend (String) -> Boolean = { it == "build" }
        val filtered = MemoryCueWatcher().read("The build failed, so the build must be rerun.", common)
        assertTrue(filtered.none { it.subject == "build" }, "$filtered")
        // Explicit tags ignore the frequency filter.
        assertTrue(MemoryCueWatcher().read("#build", common).single() == MemoryRecallTrigger.Explicit("build"))
    }

    @Test
    fun aTagInTheAgentsThoughtRecallsSummariesAndFallsBackToTheNextTurn() = runBlocking {
        val store = InMemoryMemoryStore()
        MemoryConsolidationQueue(store).enqueueSession(
            MemorySessionEnvelope(
                "s1",
                userPrompt = "Move the memory store off JSON settings.",
                parts = listOf(
                    MemorySessionPart(
                        MemorySourceKind.AgentNote,
                        "Agent",
                        "We decided to replace SettingsMemoryStore with an append-only log in MemoryLog.kt because every commit re-encoded the snapshot.",
                    ),
                ),
                closedAtEpochMillis = 1L,
            ),
        )
        val controller = MemoryLayerController(
            store = store,
            settingsStore = MemoryLayerSettingsStore(MapSettings()),
            engineProvider = object : MemoryEngineProvider {},
            scope = CoroutineScope(Dispatchers.Unconfined),
            nowEpochMillis = { 1_000L },
        )
        controller.drain()
        val handle = ManagedSessionHandle(TaskRunId("agent-1"), AgentProviderId("p"), ProviderRunId("r"))
        val sent = mutableListOf<String>()
        controller.observer.onSessionEvent(handle, AgentEvent.Thinking(ProviderRunId("r"), "Now about #SettingsMemoryStore and its storage.")) {
            sent += it
            ProviderActionResult.Rejected("no mid-session messages")
        }
        val recall = sent.single()
        assertTrue(recall.startsWith(MEMORY_MESSAGE_MARKER) && "#settingsmemorystore" in recall, recall)
        assertTrue("append-only log" in recall, recall)

        // Rejected mid-session: it arrives with the next turn, under the protocol block.
        val request = AgentTaskRequest(TaskRunId("agent-1"), "Continue", "", emptyList())
        val blocks = controller.promptContextProvider.recallFor(request, MemoryQueryPlan(emptyList(), enoughEvidence = false)).blocks
        assertEquals("Memory protocol", blocks.first().label)
        assertTrue(blocks.any { it.label == "Recalled on request" && "append-only log" in it.content }, "$blocks")
        // The protocol is starting context only; later prompts and cue clouds do not repeat it.
        val later = controller.promptContextProvider.recallFor(request, MemoryQueryPlan(emptyList(), enoughEvidence = false)).blocks
        assertTrue(later.none { it.label == "Memory protocol" }, "$later")
    }

    @Test
    fun userPromptsDrawACueCloudToo() = runBlocking {
        val store = InMemoryMemoryStore()
        MemoryConsolidationQueue(store).enqueueSession(
            MemorySessionEnvelope(
                "s1",
                userPrompt = "Fix the keystore signing for the release build.",
                parts = listOf(MemorySessionPart(MemorySourceKind.AgentNote, "Agent", "The keystore password moved to the CI secrets; signing reads KEYSTORE_PASSWORD.")),
                closedAtEpochMillis = 1L,
            ),
        )
        val controller = MemoryLayerController(
            store = store,
            settingsStore = MemoryLayerSettingsStore(MapSettings()),
            engineProvider = object : MemoryEngineProvider {},
            scope = CoroutineScope(Dispatchers.Unconfined),
            nowEpochMillis = { 1_000L },
        )
        controller.drain()
        controller.setDefaultAttentionLevel(1f)
        // A message typed into a running session carries its cloud.
        val handle = ManagedSessionHandle(TaskRunId("agent-2"), AgentProviderId("p"), ProviderRunId("r"))
        val annotated = controller.observer.annotateUserMessage(handle, "What happened with the keystore?")
        assertTrue(annotated.startsWith("What happened with the keystore?") && "$MEMORY_MESSAGE_MARKER #" in annotated, annotated)
        // The task prompt carries one in its starting context.
        val request = AgentTaskRequest(TaskRunId("agent-3"), "Check the keystore signing", "", emptyList())
        val blocks = controller.promptContextProvider.recallFor(request, MemoryQueryPlan(emptyList(), enoughEvidence = false)).blocks
        assertTrue(blocks.any { it.label == "Memory" && it.content.startsWith("$MEMORY_MESSAGE_MARKER #") }, "$blocks")
    }
}
