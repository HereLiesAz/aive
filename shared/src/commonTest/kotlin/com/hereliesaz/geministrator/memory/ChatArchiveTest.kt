package com.hereliesaz.geministrator.memory

import com.russhwolf.settings.MapSettings
import com.hereliesaz.geministrator.queen.QueenModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

/** Archive source kinds, normal queue ingestion, idempotent retry and collective recall. */
class ChatArchiveTest {
    @Test
    fun providerFormatsPreserveTextSpeakerAndTime() {
        val chatgpt = ChatArchiveDocument("conversations.json", """[{"title":"Capacitor","mapping":{"a":{"message":{"author":{"role":"user"},"create_time":1700000000,"content":{"content_type":"text","parts":["Check capacitor"]}}},"b":{"message":{"author":{"role":"assistant"},"content":{"parts":["The capacitor is ceramic."]}}}}}]""")
        val claude = ChatArchiveDocument("claude.json", """[{"name":"Circuit","chat_messages":[{"sender":"human","text":"Inspect circuit","created_at":"2023-11-14T22:13:20Z"},{"sender":"assistant","content":[{"type":"text","text":"Circuit is intact."}]}]}]""")
        val records = ChatArchiveParser.parse(listOf(chatgpt, claude))
        assertEquals(listOf("Check capacitor", "The capacitor is ceramic.", "Inspect circuit", "Circuit is intact."), records.map { it.text })
        assertEquals(listOf(MemorySourceKind.UserPrompt, MemorySourceKind.AgentNote,
            MemorySourceKind.UserPrompt, MemorySourceKind.AgentNote), records.map { it.kind })
        assertEquals(1700000000000L, records.first().occurredAt)
        assertEquals(1700000000000L, records[2].occurredAt)
        assertTrue(records.all { "speaker=" in it.label && "unverified" !in it.label })
        assertEquals(4, ChatArchiveParser.parse(listOf(chatgpt, claude, chatgpt)).size)
    }

    @Test
    fun importedMessagesUseNormalQueueAndRecallWithoutAnyGrant() = runBlocking {
        val banks = MemoryBanks.inMemory()
        val controller = MemoryLayerController(
            banks = banks, settingsStore = MemoryLayerSettingsStore(MapSettings()),
            engineProvider = object : MemoryEngineProvider {},
            scope = CoroutineScope(Dispatchers.Unconfined), nowEpochMillis = { 1000L },
        )
        controller.updateSettings { it.copy(consolidationPaused = true) }
        val document = ChatArchiveDocument("chat.json", """{"messages":[{"role":"user","content":"Inspect the zirconium capacitor."},{"role":"assistant","content":"The zirconium capacitor belongs to the power circuit."}]}""")
        assertEquals(ChatArchiveImportResult(2, 0), controller.importChatArchives(listOf(document)))
        assertEquals(ChatArchiveImportResult(0, 2), controller.importChatArchives(listOf(document)))
        val snapshot = banks.store(banks.known().single()).read()
        assertEquals(2, snapshot.queue.size)
        assertEquals(setOf(MemorySourceKind.UserPrompt, MemorySourceKind.AgentNote),
            snapshot.episodes.flatMap { it.chunks }.map { it.kind }.toSet())
        controller.updateSettings { it.copy(consolidationPaused = false) }
        controller.drain()
        controller.setDefaultAttentionLevel(1f)
        var prompt = ""
        DirectChatMemory(emptyList(), controller.promptContextProvider).voice(QueenModel { prompt = it; "Reply" }, "reviewer")
            .answer("Check the zirconium capacitor")
        assertTrue("#zirconium" in prompt || "#capacitor" in prompt, prompt)
    }

    @Test
    fun oldAssociationsRemainEligibleAcrossHundredsOfSourceBanks() = runBlocking {
        val physical = linkedMapOf<String, InMemoryMemoryStore>()
        repeat(256) { index ->
            val word = if (index == 0) "zirconium" else "unrelated${index}"
            val node = MemoryNode(MemoryNodeId("cue-$index"), MemoryNodeKind.NounTag, word, createdAtEpochMillis = index.toLong())
            physical["source-$index"] = InMemoryMemoryStore(MemorySnapshot(nodes = listOf(node)))
        }
        val controller = MemoryLayerController(
            banks = MemoryBanks.inMemory(physical), settingsStore = MemoryLayerSettingsStore(MapSettings()),
            engineProvider = object : MemoryEngineProvider {},
            scope = CoroutineScope(Dispatchers.Unconfined), nowEpochMillis = { 1000L },
        )
        controller.setDefaultAttentionLevel(1f)
        val tags = controller.promptContextProvider.directChatTags(emptyList(), "reviewer", "zirconium")
        assertTrue("#zirconium" in tags, "$tags")
        assertTrue(tags.none { it.startsWith("#unrelated") }, "$tags")
    }

    @Test
    fun largeImportBatchesRetainEveryUtteranceWithoutDuplicates() = runBlocking {
        val banks = MemoryBanks.inMemory()
        val controller = MemoryLayerController(
            banks = banks, settingsStore = MemoryLayerSettingsStore(MapSettings()),
            engineProvider = object : MemoryEngineProvider {},
            scope = CoroutineScope(Dispatchers.Unconfined), nowEpochMillis = { 1000L },
        )
        controller.updateSettings { it.copy(consolidationPaused = true) }
        val text = (1..1024).joinToString("\n") { """{"role":"assistant","content":"Recorded observation $it"}""" }
        val docs = listOf(ChatArchiveDocument("history.jsonl", text))
        assertEquals(ChatArchiveImportResult(1024, 0), controller.importChatArchives(docs))
        assertEquals(ChatArchiveImportResult(0, 1024), controller.importChatArchives(docs))
        val snapshot = banks.store(banks.known().single()).read()
        assertEquals(1024, snapshot.episodes.size)
        assertEquals(1024, snapshot.queue.size)
        assertEquals(1024, snapshot.episodes.flatMap { it.chunks }.map { it.text }.toSet().size)
        controller.updateSettings { it.copy(enabled = false) }
        assertFailsWith<IllegalStateException> { controller.importChatArchives(docs) }
        Unit // JUnit requires a void test method, not the exception returned by assertFailsWith.
    }
}
