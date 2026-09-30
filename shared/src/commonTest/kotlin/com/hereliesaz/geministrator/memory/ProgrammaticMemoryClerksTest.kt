package com.hereliesaz.geministrator.memory

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProgrammaticMemoryClerksTest {
    @Test
    fun wholePipelineRunsWithoutAModelAndRecallFindsIt() = runBlocking {
        val store = InMemoryMemoryStore()
        val layer = AgentMemoryLayer.createWithMicroAgents(store, ProgrammaticMemoryClerks.all { 1_000L })
        layer.queue.enqueueSession(
            MemorySessionEnvelope(
                sourceSessionId = "s1",
                projectId = "aive",
                userPrompt = "Move the memory store off JSON settings because every commit re-encodes it.",
                parts = listOf(
                    MemorySessionPart(
                        MemorySourceKind.Message,
                        "Agent",
                        """
                        # Findings
                        SettingsMemoryStore.commit() serializes the whole snapshot on every write.
                        We decided to replace it with an append-only log in `MemoryLog.kt`.

                        ok

                        The Gradle build and unit tests pass after the change.
                        """.trimIndent(),
                    ),
                ),
                closedAtEpochMillis = 1_000L,
            ),
        )

        var guard = 0
        while (layer.consolidateOne(1_000L) != MemoryConsolidationResult.Idle) {
            check(++guard < 200) { "consolidation did not settle" }
        }

        val snapshot = store.read()
        assertTrue(snapshot.queue.all { it.status == MemoryQueueStatus.Complete }, "queue: ${snapshot.queue}")
        val kinds = snapshot.nodes.groupingBy { it.kind }.eachCount()
        listOf(
            MemoryNodeKind.Context, MemoryNodeKind.NounTag, MemoryNodeKind.VerbTag,
            MemoryNodeKind.Phrase, MemoryNodeKind.Summary, MemoryNodeKind.Category,
        ).forEach { assertTrue((kinds[it] ?: 0) > 0, "no $it nodes: $kinds") }
        assertFalse(snapshot.nodes.any { it.text.trim() == "ok" }, "acknowledgement kept as memory")
        assertTrue(snapshot.nodes.any { it.kind == MemoryNodeKind.Category && it.text == "data" })

        val recall = layer.tool.grip(MemoryQuery("SettingsMemoryStore commit", resolution = MemoryResolution.Context))
        assertTrue(recall.hits.any { "append-only log" in it.node.text }, "recall: ${recall.hits.map { it.node.text }}")
    }

    @Test
    fun condensationDeclinesWhenMembersDisagreeOnAValue() {
        assertTrue(condensationWouldAdjudicate(listOf("Max connections: 50.", "Max connections: 100.")))
        assertTrue(condensationWouldAdjudicate(listOf("Cache is enabled.", "Cache is not enabled.")))
        assertFalse(condensationWouldAdjudicate(listOf("Build uses Gradle 9.", "The build uses Gradle 9.")))
    }

    @Test
    fun structuralBlocksKeepCodeFencesWhole() {
        val blocks = "## Title\nIntro line.\n\n```kotlin\nval a = 1\n\nval b = 2\n```\n\nTail.".structuralBlocks()
        assertEquals(listOf("## Title\nIntro line.", "```kotlin\nval a = 1\n\nval b = 2\n```", "Tail."), blocks)
    }

    @Test
    fun tagsFindCodeEntitiesKeyphrasesAndActions() {
        val text = "We decided to replace SettingsMemoryStore with an append-only log and fixed the flaky tests."
        val nouns = text.nounTags()
        val verbs = text.verbTags()
        assertTrue("SettingsMemoryStore" in nouns, "nouns: $nouns")
        assertTrue(nouns.any { "log" in it }, "nouns: $nouns")
        assertTrue("replace" in verbs && "decide" in verbs && "fix" in verbs, "verbs: $verbs")
    }
}
