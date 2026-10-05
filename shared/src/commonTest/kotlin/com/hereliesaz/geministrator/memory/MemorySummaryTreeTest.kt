package com.hereliesaz.geministrator.memory

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MemorySummaryTreeTest {
    private val paragraphs = listOf(
        "The build failed because the Gradle daemon ran out of memory while compiling the shared module.",
        "We decided to raise the Gradle daemon heap to 4 GB and to enable the configuration cache for the shared module.",
        "The shared module compiled after the Gradle daemon heap change, and the desktop tests passed.",
        "Lunch was pizza; nobody remembered which toppings were ordered for the team.",
        "Next step: measure Gradle daemon memory on CI so the shared module build stays under the heap limit.",
    )

    private fun request(limit: Int, ps: List<String> = paragraphs) = MemorySummaryRequest(ps, limit, ps.map { 0.5 })

    private fun embedderOf(calls: IntArray = IntArray(1)) = MemoryTextEmbedder { texts ->
        calls[0] += 1
        // A toy embedding: counts of a few topic words, so related paragraphs point the same way.
        texts.map { t ->
            val l = t.lowercase()
            listOf("gradle", "daemon", "heap", "shared", "module", "pizza").map { w -> Regex(w).findAll(l).count().toFloat() + 0.01f }
        }
    }

    @Test
    fun chainFallsBackModelThenCentroidThenLexRankThenLuhn() = runBlocking {
        val limit = 220
        val good = MemoryAbstractiveSummarizer { _, _, _ -> paragraphs[1] }
        assertEquals(MemorySummarizerChain.ENGINE_MODEL, MemorySummarizerChain(model = good, embedder = embedderOf()).summarize(request(limit)).engine)

        val unavailable = MemoryAbstractiveSummarizer { _, _, _ -> error("model failed to load") }
        val chain = MemorySummarizerChain(model = unavailable, embedder = embedderOf())
        val centroid = chain.summarize(request(limit))
        assertEquals(MemorySummarizerChain.ENGINE_CENTROID, centroid.engine)
        assertEquals(1, chain.metrics.modelFailures)
        assertEquals(1, centroid.modelCalls)
        assertTrue("pizza" !in centroid.text, centroid.text)

        val noEmbedder = MemoryTextEmbedder { error("embedder unavailable") }
        val lexChain = MemorySummarizerChain(model = null, embedder = noEmbedder)
        assertEquals(MemorySummarizerChain.ENGINE_LEXRANK, lexChain.summarize(request(limit)).engine)
        assertEquals(1, lexChain.metrics.embeddingFailures)

        val short = listOf("Gradle heap 4 GB.", "Daemon restarted; heap ok.")
        assertEquals(MemorySummarizerChain.ENGINE_LUHN, MemorySummarizerChain().summarize(request(20, short)).engine)
    }

    @Test
    fun modelOutputThatInventsAValueOrBreaksTheBudgetIsRejected() = runBlocking {
        val inventing = MemoryAbstractiveSummarizer { _, _, _ -> "Raised the Gradle heap to 16 GB." }
        val tooLong = MemoryAbstractiveSummarizer { _, _, _ -> paragraphs.joinToString(" ") }
        listOf(inventing, tooLong).forEach { model ->
            val out = MemorySummarizerChain(model = model).summarize(request(220))
            assertNotEquals(MemorySummarizerChain.ENGINE_MODEL, out.engine)
            assertTrue(out.text.length <= 220)
        }
    }

    @Test
    fun lexRankIsDeterministicAndRanksTheCentralParagraphFirst() {
        val first = lexRank(paragraphs)
        repeat(5) { assertEquals(first, lexRank(paragraphs)) }
        assertEquals(1.0, first.max())
        assertTrue(first[3] < first.max(), "the off-topic paragraph is not the most central: $first")
        assertEquals(lexRankEdmundsonWeights(paragraphs, setOf("gradle")), lexRankEdmundsonWeights(paragraphs, setOf("gradle")))
    }

    @Test
    fun extractiveOutputKeepsWholeParagraphsInSourceOrder() = runBlocking {
        listOf(null, embedderOf()).forEach { embedder ->
            listOf(120, 220, 320).forEach { limit ->
                val out = MemorySummarizerChain(embedder = embedder).summarize(request(limit))
                assertEquals(out.selected.sorted(), out.selected)
                assertFalse(out.lastResort)
                assertEquals(out.selected.joinToString(PARAGRAPH_SEPARATOR) { paragraphs[it] }, out.text)
                assertTrue(out.text.length <= limit)
            }
        }
        // Only when not one paragraph fits does the last-resort rule cut inside a paragraph.
        val tiny = MemorySummarizerChain().summarize(request(40))
        assertTrue(tiny.lastResort)
        assertTrue(tiny.text.length <= 40)
    }

    @Test
    fun episodeTreeHasLevelsLeavesBudgetsCuesAndWorkflowTag() = runBlocking {
        val store = InMemoryMemoryStore()
        val layer = AgentMemoryLayer.createWithMicroAgents(store, ProgrammaticMemoryClerks.all { 1_000L })
        layer.queue.enqueueSession(
            MemorySessionEnvelope(
                sourceSessionId = "s1",
                workflowRunId = "wf",
                userPrompt = "Fix the Gradle build.",
                parts = listOf(
                    MemorySessionPart(MemorySourceKind.Plan, "Plan", paragraphs.take(2).joinToString("\n\n")),
                    MemorySessionPart(MemorySourceKind.Message, "Agent", paragraphs.drop(2).joinToString("\n\n")),
                ),
                closedAtEpochMillis = 1_000L,
            ),
        )
        drain(layer)
        val snapshot = store.read()
        val outline = snapshot.nodes.filter { it.kind == MemoryNodeKind.Outline }
        val byId = outline.associateBy(MemoryNode::id)
        val children = snapshot.edges.filter { it.relation == MemoryRelationKind.Outlines }.groupBy({ it.from }, { byId.getValue(it.to) })
        val episode = snapshot.episodes.single().id
        val root = byId[MemorySummaryTree.rootId(episode)] ?: error("no root; outline=${byId.keys} queue=${snapshot.queue} sections=${snapshot.sections.size}")
        assertEquals("0", root.metadata[OUTLINE_LEVEL])
        assertTrue(children.getValue(root.id).size >= 3, "prompt, plan and message are the root's phases")
        assertNotNull(root.metadata[TREE_MODEL_CALLS])
        assertNotNull(root.metadata[TREE_MILLIS])

        outline.forEach { node ->
            val kids = children[node.id].orEmpty()
            if (node.metadata[OUTLINE_LEAF] == "true") {
                assertTrue(kids.isEmpty())
                assertEquals(1, node.text.typedBlocks().size, "a leaf is one paragraph: ${node.text}")
            } else {
                assertTrue(kids.size >= 2)
                kids.forEach { assertEquals(node.metadata[OUTLINE_LEVEL]!!.toInt() + 1, it.metadata[OUTLINE_LEVEL]!!.toInt()) }
                val states = kids.sortedBy { it.metadata[OUTLINE_PATH]!!.split('.').map(String::toInt).joinToString(".") { n -> n.toString().padStart(4, '0') } }.map {
                    MemorySizeState(it.text.length, it.metadata[MEMORY_ORIGINAL_SIZE]!!.toInt(), it.metadata[MEMORY_REWRITE_PASS]!!.toDouble(), memoryContributorWeight(it.salience.toDouble(), 1.0, 0.0))
                }
                assertTrue(node.text.length <= memoryRewriteLimit(states), "budget broken at ${node.metadata[OUTLINE_PATH]}")
                assertNotNull(node.metadata[SUMMARIZER])
            }
        }
        // Leaves in path order are the episode's paragraphs in source order.
        val leaves = outline.filter { it.metadata[OUTLINE_LEAF] == "true" }
            .sortedBy { it.metadata[OUTLINE_PATH]!!.split('.').joinToString(".") { n -> n.padStart(4, '0') } }
        assertEquals(paragraphs, leaves.map { it.text }.filter { it in paragraphs })
        assertTrue(root.metadata[OUTLINE_CUES].orEmpty().isNotBlank())
        assertTrue(snapshot.edges.any { it.relation == MemoryRelationKind.Indexes && byId.containsKey(it.to) }, "tags cue tree nodes")

        // Written through a workflow's lineage store, every tree node carries the producing workflow.
        val bank = LineageMemoryStore("wf", InMemoryMemoryStore()) { emptyList() }
        assertTrue(bank.commit(0, MemoryStoreMutation(episodesToAdd = snapshot.episodes, sectionsToAdd = snapshot.sections)))
        val view = bank.read()
        assertTrue(bank.commit(view.revision, MemorySummaryTree.mutationFor(view, episode, 5L, MemorySummarizerChain())))
        val banked = bank.read().nodes.filter { it.kind == MemoryNodeKind.Outline }
        assertEquals(outline.size, banked.size)
        assertTrue(banked.all { it.metadata[PRODUCED_BY_WORKFLOW] == "wf" })

        // Recall shows the hit's tree levels.
        val recall = layer.tool.grip(MemoryQuery("Gradle daemon heap", resolution = MemoryResolution.Context))
        assertTrue(recall.hits.any { hit -> hit.outline.firstOrNull()?.id == root.id }, "outline: ${recall.hits.map { it.outline.map(MemoryNode::id) }}")
    }

    @Test
    fun everyLinkHasItsPairSummaryBeforeRecallAndTheSafetyNetCounts() = runBlocking {
        val store = InMemoryMemoryStore()
        val layer = AgentMemoryLayer.createWithMicroAgents(store, ProgrammaticMemoryClerks.all { 1_000L })
        listOf("s1" to 1_000L, "s2" to 2_000L).forEach { (session, at) ->
            layer.queue.enqueueSession(
                MemorySessionEnvelope(session, projectId = "p", userPrompt = "Fix the Gradle daemon heap for the shared module.", parts = listOf(MemorySessionPart(MemorySourceKind.Message, "Agent", paragraphs.joinToString("\n\n"))), closedAtEpochMillis = at),
            )
        }
        drain(layer)
        val snapshot = store.read()
        val nodesById = snapshot.nodes.associateBy(MemoryNode::id)
        val links = snapshot.edges.filter { MemoryPairSummaries.covers(it, nodesById) }
        assertTrue(links.isNotEmpty())
        links.forEach { edge ->
            val pair = assertNotNull(nodesById[MemoryPairSummaries.idFor(edge.id)], "no pair summary for ${edge.id.value} (${edge.relation})")
            assertEquals(edge.id.value, pair.metadata[PAIR_OF])
        }
        assertTrue(snapshot.edges.none { it.relation in setOf(MemoryRelationKind.Outlines, MemoryRelationKind.Supersedes) && MemoryPairSummaries.idFor(it.id) in nodesById })

        val hits = layer.tool.grip(MemoryQuery("Gradle daemon heap shared module", resolution = MemoryResolution.Context, maxResults = 6)).hits
        assertEquals(0, layer.summaryMetrics.pairSummaryViolations)
        assertTrue(hits.any { it.pairSummaries.isNotEmpty() }, "recall shows pair summaries")

        // A link made after consolidation and recalled before the next pass: generated at recall, counted.
        val (a, b) = hits.take(2).map { it.node.id }
        val late = MemoryEdge(MemoryEdgeId("late-link"), a, b, MemoryRelationKind.AssociatedWith, 0.3f, createdAtEpochMillis = 3_000L)
        val before = store.read()
        assertTrue(store.commit(before.revision, MemoryStoreMutation(edgesToAdd = listOf(late))))
        val again = layer.tool.grip(MemoryQuery("Gradle daemon heap shared module", resolution = MemoryResolution.Context, maxResults = 6)).hits
        assertEquals(1, layer.summaryMetrics.pairSummaryViolations)
        assertTrue(store.read().nodes.any { it.id == MemoryPairSummaries.idFor(late.id) })
        assertTrue(again.any { hit -> hit.pairSummaries.any { it.edgeId == late.id } })
    }

    @Test
    fun pairSummariesAreBoundedPerPassAndCarryOver() = runBlocking {
        val store = InMemoryMemoryStore()
        val nodes = (0 until 10).map { MemoryNode(MemoryNodeId("n$it"), MemoryNodeKind.Context, "Memory number $it about the Gradle heap.", createdAtEpochMillis = 1L) }
        val edges = (0 until 9).flatMap { i -> (i + 1 until 10).map { j -> MemoryEdge(MemoryEdgeId("e$i-$j"), nodes[i].id, nodes[j].id, MemoryRelationKind.AssociatedWith, createdAtEpochMillis = 1L) } }
        assertTrue(store.commit(0, MemoryStoreMutation(nodesToAdd = nodes, edgesToAdd = edges)))
        val chain = MemorySummarizerChain()
        assertEquals(PAIR_SUMMARIES_PER_PASS, MemoryPairSummaries.step(store, 2L, chain))
        assertEquals(edges.size - PAIR_SUMMARIES_PER_PASS, MemoryPairSummaries.step(store, 2L, chain))
        assertEquals(0, MemoryPairSummaries.step(store, 2L, chain))
        assertTrue(MemoryPairSummaries.pending(store.read()).isEmpty())
    }

    @Test
    fun shortPairSummariesAlwaysShowBothSides() = runBlocking {
        listOf(
            "Heap set to 4 GB." to "CI runs nightly.",
            "Use Gradle." to "Pizza.",
            // Two short memories, one of them several lines of chatter: both kept, on one line.
            "sound thanks" to "ok\n\nthanks!",
            paragraphs[0] to paragraphs[3],
            paragraphs.take(3).joinToString("\n\n") to "Cache on.",
        ).forEachIndexed { i, (left, right) ->
            val a = MemoryNode(MemoryNodeId("a$i"), MemoryNodeKind.Context, left, createdAtEpochMillis = 1L)
            val b = MemoryNode(MemoryNodeId("b$i"), MemoryNodeKind.Context, right, createdAtEpochMillis = 2L)
            val edge = MemoryEdge(MemoryEdgeId("e$i"), a.id, b.id, MemoryRelationKind.AssociatedWith, createdAtEpochMillis = 3L)
            val pair = MemoryPairSummaries.summaryFor(MemorySnapshot(nodes = listOf(a, b), edges = listOf(edge)), edge, 4L, MemorySummarizerChain())
            assertTrue(MemoryPairSummaries.validatePair(pair.text, maxOf(pair.text.length, left.length + right.length - 1)), pair.text)
            val body = pair.text.lines().let { ls -> if (ls.any { it.startsWith("A: ") }) ls.filter { it.startsWith("A: ") || it.startsWith("B: ") }.map { it.drop(3) } else ls.last().split(" / ", limit = 2) }
            assertEquals(2, body.size, pair.text)
            val words = { t: String -> Regex("[A-Za-z0-9]+").findAll(t.lowercase()).map { it.value }.toSet() }
            assertTrue(words(body[0]).isNotEmpty() && words(left).containsAll(words(body[0])), "side A from A: ${pair.text}")
            assertTrue(words(body[1]).isNotEmpty() && words(right).containsAll(words(body[1])), "side B from B: ${pair.text}")
        }
        assertFalse(MemoryPairSummaries.validatePair("A: only one side", 100))
        assertFalse(MemoryPairSummaries.validatePair("A: x\nB: ", 100))
    }

    @Test
    fun divergencePairSummaryDescribesBothSidesWithoutJudging() = runBlocking {
        val a = MemoryNode(MemoryNodeId("a"), MemoryNodeKind.Context, "For the memory store we chose Postgres as the database engine because it supports JSON columns well.", createdAtEpochMillis = 1L)
        val b = MemoryNode(MemoryNodeId("b"), MemoryNodeKind.Context, "For the memory store we chose MySQL as the database engine because the hosting plan includes it.", createdAtEpochMillis = 2L)
        val edge = MemoryEdge(MemoryEdgeId("diverges:a|b"), a.id, b.id, MemoryRelationKind.Diverges, createdAtEpochMillis = 3L)
        val snapshot = MemorySnapshot(nodes = listOf(a, b), edges = listOf(edge))
        val judging = MemoryAbstractiveSummarizer { ps, _, _ -> if ("Postgres" in ps.first()) "Postgres is the correct database." else "MySQL is wrong." }
        listOf(MemorySummarizerChain(), MemorySummarizerChain(model = judging)).forEach { chain ->
            val pair = MemoryPairSummaries.summaryFor(snapshot, edge, 4L, chain)
            assertEquals("divergence", pair.metadata[PAIR_KIND])
            assertTrue(pair.text.startsWith(MemoryPairSummaries.DIVERGENCE_HEADER), pair.text)
            assertTrue("Postgres" in pair.text && "MySQL" in pair.text, pair.text)
            assertFalse(MemoryPairSummaries.JUDGING.containsMatchIn(pair.text.lowercase()), pair.text)
            assertFalse(pair.metadata.keys.any { it == DELIBERATION_CHOSEN })
            assertFalse(pair.metadata[SUMMARIZER].orEmpty().contains(MemorySummarizerChain.ENGINE_MODEL))
            assertTrue(pair.text.length < a.text.length + b.text.length)
        }
        // A model's wording that adds a judgement is rejected; the side falls back to an extractive engine.
        val side = MemorySummaryRequest(listOf(a.text, "It was picked in March."), 60, listOf(0.5, 0.5), forbidden = MemoryPairSummaries.JUDGING)
        val out = MemorySummarizerChain(model = judging).summarize(side)
        assertNotEquals(MemorySummarizerChain.ENGINE_MODEL, out.engine)
    }

    private suspend fun drain(layer: AgentMemoryLayer) {
        var guard = 0
        while (layer.consolidateOne(1_000L) != MemoryConsolidationResult.Idle) check(++guard < 500) { "consolidation did not settle" }
    }
}
