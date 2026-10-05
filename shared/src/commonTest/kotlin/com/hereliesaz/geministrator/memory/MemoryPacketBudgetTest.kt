package com.hereliesaz.geministrator.memory

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Packets are sized to the input budget of the stage that receives them, never parked for size. */
class MemoryPacketBudgetTest {
    private val paragraphs = listOf(
        "The build failed because the Gradle daemon ran out of memory while compiling the shared module.",
        "We decided to raise the Gradle daemon heap to 4 GB and to enable the configuration cache for the shared module.",
        "The shared module compiled after the Gradle daemon heap change, and the desktop tests passed.",
        "Lunch was pizza; nobody remembered which toppings were ordered for the team.",
        "Next step: measure Gradle daemon memory on CI so the shared module build stays under the heap limit.",
    )

    private fun twoPartSession(ps: List<String> = paragraphs) = MemorySessionEnvelope(
        sourceSessionId = "s1",
        workflowRunId = "wf",
        userPrompt = "Fix the Gradle build.",
        parts = listOf(
            MemorySessionPart(MemorySourceKind.Plan, "Plan", ps.take(2).joinToString("\n\n")),
            MemorySessionPart(MemorySourceKind.Message, "Agent", ps.drop(2).joinToString("\n\n")),
        ),
        closedAtEpochMillis = 1_000L,
    )

    @Test
    fun twoPartSessionCompletesUnderTheDefaultPolicy() = runBlocking {
        // The TODO repro: this used to park at Associations (cursor=24) with
        // "Primary packet exceeds programmatic/AssociationLinker input budget".
        val store = InMemoryMemoryStore()
        val layer = AgentMemoryLayer.createWithMicroAgents(store, ProgrammaticMemoryClerks.all { 1_000L })
        layer.queue.enqueueSession(twoPartSession())
        val results = drain { layer.consolidateOne(1_000L) }
        assertTrue(results.none { it is MemoryConsolidationResult.Failed }, "failures: $results")
        assertEquals(MemoryQueueStatus.Complete, store.read().queue.single().status)
    }

    @Test
    fun everyStageSplitsUnderATightClerkBudget() = runBlocking {
        val stages = mutableMapOf<MemoryConsolidationStage, Int>()
        val tight = ProgrammaticMemoryClerks.all { 1_000L }.map { agent ->
            object : MemoryMicroAgent {
                override val role = agent.role
                override val model = agent.model.copy(maxInputChars = 3_200, maxInputItems = 6)
                override suspend fun process(packet: MemoryWorkPacket): MemoryMutationBatch {
                    stages[packet.stage] = (stages[packet.stage] ?: 0) + 1
                    return agent.process(packet)
                }
            }
        }
        val store = InMemoryMemoryStore()
        val layer = AgentMemoryLayer.createWithMicroAgents(store, tight, programmaticSemanticFastPaths = false)
        layer.queue.enqueueSession(twoPartSession(paragraphs + paragraphs.map { "Again: $it" }))
        val results = drain { layer.consolidateOne(1_000L) }
        assertTrue(results.none { it is MemoryConsolidationResult.Failed }, "failures: $results")
        assertEquals(MemoryQueueStatus.Complete, store.read().queue.single().status)
        listOf(
            MemoryConsolidationStage.Tags,
            MemoryConsolidationStage.Phrases,
            MemoryConsolidationStage.Summaries,
            MemoryConsolidationStage.Associations,
        ).forEach { stage -> assertTrue((stages[stage] ?: 0) > 1, "$stage was not split: $stages") }
        listOf(MemoryConsolidationStage.Sectioning, MemoryConsolidationStage.Salience)
            .forEach { stage -> assertTrue((stages[stage] ?: 0) >= 1, "$stage never ran: $stages") }
    }

    @Test
    fun routerMeasuresEveryStageTheWayItEnforces() = runBlocking {
        val router = MemoryMicroAgentRouter(ProgrammaticMemoryClerks.all { 1_000L })
        val meta = (0 until 20).associate { "source.$it" to "episode-1000-e1e:node:$it:provenance" }
        fun packet(stage: MemoryConsolidationStage, n: Int) = MemoryWorkPacket(
            queueId = MemoryQueueId("q"),
            episodeId = MemoryEpisodeId("e"),
            stage = stage,
            packetKey = "${stage.name.lowercase()}-0",
            items = (0 until n).map { MemoryWorkItem("node-$it", "node:Phrase", "Gradle daemon heap text $it", meta) },
            instruction = "stage instruction",
        )
        MemoryConsolidationStage.entries.filter { it != MemoryConsolidationStage.Complete }.forEach { stage ->
            assertTrue(router.fitsInput(packet(stage, 1)), "$stage rejects one small item")
            // Text alone is far under the policy's 12,000 chars; ids and metadata push it over.
            assertTrue(packet(stage, 24).items.sumOf { it.text.length } < 1_000)
            assertTrue(!router.fitsInput(packet(stage, 24)), "$stage accepts what it would reject")
        }
    }

    @Test
    fun manySmallItemsGoInOrderedFittingPacketsWithNoneDropped() = runBlocking {
        val chunks = (0 until 10).map { "item $it" }
        val store = storeWith(chunks)
        val seen = mutableListOf<List<String>>()
        val manager = recordingManager(seen, maxItems = 3)
        val consolidator = MemoryConsolidator(store, manager)
        drain { consolidator.processNext(10L) }
        assertEquals(listOf(3, 3, 3, 1), seen.map { it.size })
        assertEquals(chunks.indices.map { "e:chunk:$it" }, seen.flatten())
    }

    @Test
    fun aSingleOversizedItemIsSentInOrderedParagraphParts() = runBlocking {
        val big = (1..4).joinToString("\n\n") { "Paragraph $it carries forty characters." }
        val store = storeWith(listOf("small", big, "tail"))
        val packets = mutableListOf<MemoryWorkPacket>()
        val manager = object : MemoryManagerAgent {
            override suspend fun process(packet: MemoryWorkPacket): MemoryMutationBatch {
                if (packet.stage == MemoryConsolidationStage.Sectioning) packets += packet
                return MemoryMutationBatch()
            }
            override fun fitsInput(packet: MemoryWorkPacket) = packet.items.sumOf { it.text.length } <= 90
        }
        val results = drain { MemoryConsolidator(store, manager).processNext(10L) }
        assertTrue(results.none { it is MemoryConsolidationResult.Failed }, "failures: $results")
        val parts = packets.filter { p -> p.items.any { it.metadata[MEMORY_PART] != null } }
        assertTrue(parts.size >= 2)
        parts.forEach { p ->
            assertEquals(listOf("e:chunk:1"), p.items.map { it.id }, "parts keep the source id")
            assertTrue(p.items.single().text.length <= 90)
        }
        assertEquals(parts.indices.map { "${it + 1}/${parts.size}" }, parts.map { it.items.single().metadata[MEMORY_PART] })
        assertEquals(big.replace("\n\n", " "), parts.joinToString(" ") { it.items.single().text.replace("\n\n", " ") })
        assertEquals(parts.map { it.packetKey }.distinct().size, parts.size)
        assertEquals(listOf("e:chunk:0", "e:chunk:1", "e:chunk:2"), packets.flatMap { p -> p.items.map { it.id } }.distinct())
        assertEquals(MemoryQueueStatus.Complete, store.read().queue.single().status)
    }

    @Test
    fun crashMidSplitResumesAtTheSamePartWithTheSameKey() = runBlocking {
        val big = (1..3).joinToString("\n\n") { "Paragraph $it carries forty characters." }
        val store = storeWith(listOf(big))
        val keys = mutableListOf<String>()
        var crashed = false
        val manager = object : MemoryManagerAgent {
            override suspend fun process(packet: MemoryWorkPacket): MemoryMutationBatch {
                if (packet.stage != MemoryConsolidationStage.Sectioning) return MemoryMutationBatch()
                keys += packet.packetKey
                if (packet.items.single().metadata[MEMORY_PART] == "2/3" && !crashed) {
                    crashed = true
                    error("crash mid-split")
                }
                return MemoryMutationBatch()
            }
            override fun fitsInput(packet: MemoryWorkPacket) = packet.items.sumOf { it.text.length } <= 60
        }
        val consolidator = MemoryConsolidator(store, manager)
        assertIs<MemoryConsolidationResult.Applied>(consolidator.processNext(10L))
        assertIs<MemoryConsolidationResult.Failed>(consolidator.processNext(10L))
        val parked = store.read().queue.single()
        assertEquals(0, parked.cursor)
        assertEquals(1, parked.part)
        drain { consolidator.processNext(10L) }
        assertEquals(listOf("sectioning-0-part0", "sectioning-0-part1", "sectioning-0-part1", "sectioning-0-part2"), keys)
        assertEquals(MemoryQueueStatus.Complete, store.read().queue.single().status)
    }

    private fun recordingManager(seen: MutableList<List<String>>, maxItems: Int) = object : MemoryManagerAgent {
        override suspend fun process(packet: MemoryWorkPacket): MemoryMutationBatch {
            if (packet.stage == MemoryConsolidationStage.Sectioning) seen += packet.items.map { it.id }
            return MemoryMutationBatch()
        }
        override fun fitsInput(packet: MemoryWorkPacket) = packet.items.size <= maxItems
    }

    private suspend fun storeWith(chunks: List<String>): InMemoryMemoryStore {
        val episodeId = MemoryEpisodeId("e")
        val episode = MemoryEpisode(
            id = episodeId,
            sourceSessionId = "session-e",
            userPrompt = "prompt",
            chunks = chunks.mapIndexed { index, text ->
                MemorySourceChunk(
                    id = MemoryChunkId("e:chunk:$index"),
                    episodeId = episodeId,
                    ordinal = index,
                    kind = MemorySourceKind.Message,
                    label = "Agent",
                    text = text,
                )
            },
            createdAtEpochMillis = 1L,
        )
        val store = InMemoryMemoryStore()
        store.commit(
            0,
            MemoryStoreMutation(
                episodesToAdd = listOf(episode),
                queueUpserts = listOf(MemoryQueueEntry(MemoryQueueId("q"), 1, episodeId, createdAtEpochMillis = 1L)),
            ),
        )
        return store
    }

    private suspend fun drain(step: suspend () -> MemoryConsolidationResult): List<MemoryConsolidationResult> {
        val results = mutableListOf<MemoryConsolidationResult>()
        while (true) {
            val result = step()
            if (result == MemoryConsolidationResult.Idle) return results
            results += result
            check(results.size < 2_000) { "consolidation did not settle" }
        }
    }
}
