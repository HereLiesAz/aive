package com.hereliesaz.geministrator.memory

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Workflow banks and lineage: continuation, merge, project access, provenance, migration,
 * deliberation-resolved consolidation, the size curve and time ranges.
 */
class MemoryLineageBanksTest {
    private inner class World {
        val banks = MemoryBanks.inMemory()
        val lineage = MemoryLineage()
        val memory = MemoryLayerController(
            banks = banks,
            settingsStore = MemoryLayerSettingsStore(MapSettings()),
            engineProvider = object : MemoryEngineProvider {},
            scope = CoroutineScope(Dispatchers.Unconfined),
            lineage = lineage,
            nowEpochMillis = { 1_000L },
        )

        fun view(workflow: String) = LineageMemoryStore(workflow, runBlocking { banks.store(workflow) }) {
            lineage.ancestorsOf(workflow).map { banks.store(it) }
        }

        /** Writes Context memories (one episode each) into [workflow]'s bank through its lineage view. */
        suspend fun write(workflow: String, vararg memories: Pair<String, String>, at: Long = 1L) {
            val view = view(workflow)
            val snapshot = view.read()
            assertTrue(
                view.commit(
                    snapshot.revision,
                    MemoryStoreMutation(
                        episodesToAdd = memories.map { (id, _) -> episode(id, workflow, at) },
                        nodesToAdd = memories.map { (id, text) -> node(id, text, at) },
                    ),
                ),
            )
        }

        suspend fun texts(hits: List<MemoryRecallHit>) = hits.map { it.node.text }
    }

    private fun episode(id: String, workflow: String?, at: Long = 1L, task: String? = null) = MemoryEpisode(
        id = MemoryEpisodeId("e-$id"),
        sourceSessionId = "s-$id",
        workflowRunId = workflow,
        taskRunId = task,
        userPrompt = "prompt",
        chunks = emptyList(),
        createdAtEpochMillis = at,
    )

    private fun node(id: String, text: String, at: Long = 1L, vararg episodes: String = arrayOf(id)) =
        MemoryNode(MemoryNodeId(id), MemoryNodeKind.Context, text, sourceEpisodeIds = episodes.mapTo(linkedSetOf()) { MemoryEpisodeId("e-$it") }, createdAtEpochMillis = at)

    // ---- lineage ----------------------------------------------------------------------------------

    @Test
    fun aContinuationInheritsItsParentsBankAndContributesToItsOwn() = runBlocking<Unit> {
        val w = World()
        w.memory.registerWorkflow("root", "P")
        w.write("root", "r1" to "The keystore lives in the vault.")
        w.memory.registerWorkflow("next", "P", parents = listOf("root"))
        w.write("next", "n1" to "The keystore password rotates monthly.")

        val hits = w.memory.recallFor("next", "keystore")
        assertEquals(setOf("r1", "n1"), hits.map { it.node.id.value }.toSet())
        val inherited = hits.single { it.node.id.value == "r1" }.provenance!!
        assertEquals("root", inherited.producedByWorkflow)
        assertEquals(listOf("root", "next"), inherited.lineagePath)
        assertEquals("s-r1", inherited.producedBySession)
        // Nothing was copied: each bank holds only its own records.
        assertEquals(listOf("r1"), w.banks.store("root").read().nodes.map { it.id.value })
        assertEquals(listOf("n1"), w.banks.store("next").read().nodes.map { it.id.value })
        // The parent does not see its continuation's records as lineage.
        assertTrue(w.memory.recallFor("root", "keystore").filter { it.provenance?.readOnlyFromWorkflow == null }.none { it.node.id.value == "n1" })
    }

    @Test
    fun aMergeReadsBothAncestriesAndFlagsTheirContrastsWithoutResolvingThem() = runBlocking<Unit> {
        val w = World()
        w.memory.registerWorkflow("a", "P")
        w.memory.registerWorkflow("b", "P")
        w.write("a", "a1" to "I chose Postgres for the database.")
        w.write("b", "b1" to "I chose MySQL for the database.")
        val aBefore = w.banks.store("a").read()
        val bBefore = w.banks.store("b").read()

        w.memory.registerWorkflow("m", "P", parents = listOf("a", "b"))

        val merged = w.banks.store("m").read()
        assertEquals(listOf("diverges:a1|b1"), merged.edges.filter { it.relation == MemoryRelationKind.Diverges }.map { it.id.value })
        assertEquals(aBefore, w.banks.store("a").read())
        assertEquals(bBefore, w.banks.store("b").read())
        val hits = w.memory.recallFor("m", "database")
        val a1 = hits.single { it.node.id.value == "a1" }
        assertEquals(listOf("b1"), a1.conflicts.map { it.id.value })
        assertEquals(listOf("a", "m"), a1.provenance!!.lineagePath)
        assertEquals(listOf("b", "m"), hits.single { it.node.id.value == "b1" }.provenance!!.lineagePath)
        // Neither side is superseded: nothing was resolved.
        assertTrue(w.view("m").read().edges.none { it.relation == MemoryRelationKind.Supersedes })
    }

    @Test
    fun otherWorkflowsOfTheProjectAreReadOnlyAndLabelledAndOtherProjectsInvisible() = runBlocking<Unit> {
        val w = World()
        w.memory.registerWorkflow("mine", "P")
        w.memory.registerWorkflow("sibling", "P")
        w.memory.registerWorkflow("elsewhere", "Q")
        w.write("mine", "m1" to "The release keystore is in the vault.")
        w.write("sibling", "s1" to "The staging keystore is in the bucket.")
        w.write("elsewhere", "x1" to "The other keystore is on a laptop.")
        val siblingBefore = w.banks.store("sibling").read()

        val hits = w.memory.recallFor("mine", "keystore")
        assertEquals(listOf("m1", "s1"), hits.map { it.node.id.value })
        val sibling = hits.last()
        assertEquals("sibling", sibling.provenance!!.readOnlyFromWorkflow)
        assertNull(sibling.provenance!!.lineagePath)
        assertTrue(sibling.sourceLabel().contains("read-only from workflow sibling"))
        assertEquals(siblingBefore, w.banks.store("sibling").read())

        // The project expands to incorporate Q: its workflows become readable too (append-only).
        w.memory.expandProject("P", "Q", by = "user", reason = "Q was folded into P")
        assertTrue(w.memory.recallFor("mine", "keystore").any { it.node.id.value == "x1" && it.provenance?.readOnlyFromWorkflow == "elsewhere" })
        assertEquals(1, w.lineage.log().expansions.size)
    }

    @Test
    fun aBankRefusesAnotherWorkflowsEpisodesAndForeignTags() = runBlocking<Unit> {
        val w = World()
        val store = w.view("a")
        assertFailsWith<MemoryCrossBankException> {
            store.commit(0, MemoryStoreMutation(episodesToAdd = listOf(episode("x", "b"))))
        }
        assertFailsWith<IllegalArgumentException> {
            store.commit(0, MemoryStoreMutation(nodesToAdd = listOf(node("n", "text").copy(sourceEpisodeIds = emptySet(), metadata = mapOf(PRODUCED_BY_WORKFLOW to "b")))))
        }
        assertEquals("session:t1", memoryWorkflowOf(null, "t1", "s1"))
    }

    @Test
    fun aCyclicContinuationIsRefused() = runBlocking<Unit> {
        val lineage = MemoryLineage()
        lineage.registerWorkflow("a", "P", atEpochMillis = 1)
        lineage.registerWorkflow("b", "P", listOf("a"), 2)
        assertFailsWith<IllegalArgumentException> { lineage.registerWorkflow("a", "P", listOf("b"), 3) }
    }

    @Test
    fun aCondensationAfterAMergeIsTaggedWithTheMergeAndReachesBothLineages() = runBlocking<Unit> {
        val w = World()
        w.memory.registerWorkflow("a", "P")
        w.memory.registerWorkflow("b", "P")
        w.write("a", "a1" to "The connection pool is shared.")
        w.write("b", "b1" to "The connection pool is shared.")
        w.memory.registerWorkflow("m", "P", parents = listOf("a", "b"))
        val view = w.view("m")
        val snapshot = view.read()
        val em = episode("m1", "m")
        assertTrue(
            view.commit(
                snapshot.revision,
                MemoryStoreMutation(
                    episodesToAdd = listOf(em),
                    nodesToAdd = listOf(node("m1", "The connection pool is shared.")),
                    edgesToAdd = listOf("a1" to "b1", "a1" to "m1", "b1" to "m1").map { (x, y) ->
                        MemoryEdge(MemoryEdgeId("sim-$x-$y"), MemoryNodeId(x), MemoryNodeId(y), MemoryRelationKind.SimilarTo, 0.95f, 5L)
                    },
                    queueUpserts = listOf(MemoryQueueEntry(MemoryQueueId("q"), 1L, em.id, stage = MemoryConsolidationStage.Condensation, createdAtEpochMillis = 1L)),
                ),
            ),
        )
        val policy = MemoryConsolidationPolicy(maxSimilarPerKind = MemoryConsolidationPolicy().maxSimilarPerKind + (MemoryNodeKind.Context to 2))
        val consolidator = MemoryConsolidator(view, condenser(), policy)
        repeat(20) { if (consolidator.processNext(10L) is MemoryConsolidationResult.Idle) return@repeat }

        val after = view.read()
        val generalized = after.nodes.single { it.id.value.startsWith("g-") }
        assertEquals("m", generalized.metadata[PRODUCED_BY_WORKFLOW])
        val sources = after.edges.filter { it.relation == MemoryRelationKind.CondensedFrom && it.from == generalized.id }.map { it.to }
        val sourceWorkflows = sources.mapNotNull { id -> after.nodes.single { it.id == id }.metadata[PRODUCED_BY_WORKFLOW] }.toSet()
        assertEquals(setOf("a", "b", "m"), sourceWorkflows)
        // The lineage is derived from the DAG, not stored on the memory.
        assertEquals(setOf("a", "b"), w.lineage.ancestorsOf("m").toSet())
        assertTrue(generalized.metadata.keys.none { "lineage" in it.lowercase() })
        // The ancestors' banks hold nothing new.
        assertEquals(listOf("a1"), w.banks.store("a").read().nodes.map { it.id.value })
    }

    // ---- migration --------------------------------------------------------------------------------

    @Test
    fun theSharedStoreSplitsIntoWorkflowBanksAndReportsWhatSpansWorkflows() = runBlocking<Unit> {
        val legacy = MemorySnapshot(
            revision = 7,
            episodes = listOf(
                episode("a", "wa").copy(projectId = "P"),
                episode("b", "wb").copy(projectId = "P"),
                episode("n", null, task = "t1"),
            ),
            nodes = listOf(node("a", "Alpha uses Postgres."), node("b", "Beta uses MySQL."), node("n", "A note outside any workflow."), node("ab", "Condensed from both.", 1L, "a", "b")),
            edges = listOf(MemoryEdge(MemoryEdgeId("a-b"), MemoryNodeId("a"), MemoryNodeId("b"), MemoryRelationKind.AssociatedWith, createdAtEpochMillis = 1L)),
        )
        val legacyStore = InMemoryMemoryStore(legacy)
        val banks = MemoryBanks.inMemory()
        val lineage = MemoryLineage()
        val report = assertNotNull(migrateSharedMemoryToBanks(legacyStore, banks, lineage))

        assertEquals(listOf("session:t1", "wa", "wb"), banks.known())
        assertEquals(listOf("a"), banks.store("wa").read().nodes.map { it.id.value })
        assertEquals("wa", banks.store("wa").read().nodes.single().metadata[PRODUCED_BY_WORKFLOW])
        assertEquals("session:t1", banks.store("session:t1").read().episodes.single().workflowRunId)
        assertEquals(setOf("node:ab", "edge:a-b"), report.excluded.map { "${it.kind}:${it.id}" }.toSet())
        assertEquals(mapOf("wa" to "P", "wb" to "P"), report.projects)
        assertEquals(listOf("wa", "wb"), lineage.projectWorkflows("P"))
        assertTrue(lineage.log().parents.isEmpty(), "the old store records no lineage")
        // The old store is the untouched backup, and a second run changes nothing.
        assertEquals(legacy, legacyStore.read())
        val revisions = banks.known().associateWith { banks.store(it).read().revision }
        assertEquals(report, migrateSharedMemoryToBanks(legacyStore, banks, lineage))
        assertEquals(revisions, banks.known().associateWith { banks.store(it).read().revision })
    }

    // ---- contrasts resolved by deliberation, then absorbed --------------------------------------

    private suspend fun contrastingPair(w: World): LexicalMemoryTool {
        w.memory.registerWorkflow("wf", "P")
        w.write("wf", "c1" to "I chose Postgres for the database.", "c2" to "I chose MySQL for the database.")
        val view = w.view("wf")
        val snapshot = view.read()
        view.commit(snapshot.revision, MemoryVariantRegister.mutationFor(snapshot, MemoryEpisodeId("e-c1"), 2L))
        return LexicalMemoryTool(GraphMemoryTool(view, MemoryConsolidationQueue(view)))
    }

    @Test
    fun anUnresolvedDeliberationAbsorbsNothing() = runBlocking<Unit> {
        val w = World()
        val tool = contrastingPair(w)
        tool.deliberate(MemoryDeliberationRequest("s", "Unclear which.", listOf(MemoryNodeId("c1"), MemoryNodeId("c2")), deliberatedAtEpochMillis = 3L))
        val layer = AgentMemoryLayer.create(w.view("wf"))
        assertEquals(0, layer.absorbDeliberations(4L))
        val hit = w.memory.recallFor("wf", "Postgres").single { it.node.id.value == "c1" }
        assertEquals(listOf("c2"), hit.conflicts.map { it.id.value })
    }

    @Test
    fun aResolvedContrastIsAbsorbedIntoOneCurrentMemoryWhoseHistoryFadesWithAccess() = runBlocking<Unit> {
        val w = World()
        val tool = contrastingPair(w)
        val deliberation = tool.deliberate(
            MemoryDeliberationRequest(
                "s", "Postgres: the migration notes show MySQL was dropped.", listOf(MemoryNodeId("c1"), MemoryNodeId("c2")),
                evidence = listOf("migration notes"), deliberatedAtEpochMillis = 3L, chosen = MemoryNodeId("c1"),
            ),
        )
        val view = w.view("wf")
        assertEquals(1, AgentMemoryLayer.create(view).absorbDeliberations(4L))

        val hits = w.memory.recallFor("wf", "database")
        val current = hits.single()
        assertEquals("resolved:${deliberation.id.value}", current.node.id.value)
        assertTrue(current.conflicts.isEmpty(), "a resolved contrast is recalled as one memory")
        val resolution = assertNotNull(current.resolution)
        assertEquals(listOf("c2"), resolution.notChosen.map { it.id.value })
        assertEquals("wf", current.node.metadata[PRODUCED_BY_WORKFLOW])
        // History: the chosen and the not-chosen memories and the deliberation, all kept.
        assertEquals(setOf("c1", "c2", deliberation.id.value), view.read().historyOf(current.node.id).map { it.id.value }.toSet())

        // Rendering of the contradiction history decays with access.
        assertTrue("previously contested" in current.divergenceLines())
        suspend fun recall(times: Int) = repeat(times) {
            val snapshot = view.read()
            val n = snapshot.edges.count { it.relation == MemoryRelationKind.Recalled }
            view.commit(snapshot.revision, MemoryStoreMutation(edgesToAdd = listOf(MemoryEdge(MemoryEdgeId("recalled:${current.node.id.value}:${n + 1}"), deliberation.id, current.node.id, MemoryRelationKind.Recalled, createdAtEpochMillis = 5L))))
        }
        recall(RESOLVED_NOTE_RECALLS)
        val linkOnly = w.memory.recallFor("wf", "database").single().divergenceLines()
        assertTrue("previously contested" !in linkOnly && "memory-history:" in linkOnly, linkOnly)
        recall(RESOLVED_LINK_RECALLS - RESOLVED_NOTE_RECALLS)
        assertEquals("", w.memory.recallFor("wf", "database").single().divergenceLines())

        // New contrasting evidence reopens it as a live divergence.
        w.write("wf", "c3" to "I chose SQLite for the database.", at = 6L)
        val snapshot = view.read()
        view.commit(snapshot.revision, MemoryVariantRegister.mutationFor(snapshot, MemoryEpisodeId("e-c3"), 7L))
        val reopened = w.memory.recallFor("wf", "Postgres").single { it.node.id == current.node.id }
        assertEquals(listOf("c3"), reopened.conflicts.map { it.id.value })
    }

    // ---- the size curve -----------------------------------------------------------------------------

    @Test
    fun theBudgetFollowsAnSCurveToARelativeFloor() {
        val budgets = (0..MEMORY_LIFESPAN_PASSES + 4).map { memorySizeBudget(1000, it.toDouble()) }
        assertEquals(1000, budgets.first())
        assertTrue(budgets.zipWithNext().all { (a, b) -> b <= a }, "monotonic: $budgets")
        assertEquals(memoryFloor(1000), 200)
        assertTrue(budgets.all { it >= 200 })
        val deltas = budgets.zipWithNext().map { (a, b) -> a - b }
        val steepest = deltas.indexOf(deltas.max())
        val midpoint = (MEMORY_MIDPOINT_FRACTION * MEMORY_LIFESPAN_PASSES).toInt()
        assertTrue(steepest >= midpoint - 1 && steepest <= midpoint + 1, "steepest at $steepest: $deltas")
        assertTrue(deltas.first() < deltas.max() / 4 && deltas.last() < deltas.max() / 4, "$deltas")
    }

    @Test
    fun successiveRewritesStrictlyShrinkToTheFloor() {
        var state = MemorySizeState(1000, 1000, 0.0)
        val sizes = mutableListOf(state.size)
        repeat(40) {
            val limit = memoryRewriteLimit(listOf(state))
            state = MemorySizeState(limit, 1000, state.pass + 1)
            sizes += limit
        }
        assertTrue(sizes.zipWithNext().all { (a, b) -> b < a || a == memoryFloor(1000) }, "$sizes")
        assertEquals(200, sizes.last())
    }

    @Test
    fun combiningReBasesTheCurveByWeight() {
        val old = MemorySizeState(600, 600, 4.0, weight = 3.0)
        val new = MemorySizeState(200, 200, 0.0, weight = 1.0)
        val (original, pass) = memoryRewriteState(listOf(old, new))
        assertEquals(500, original)
        assertEquals(100, memoryFloor(original))
        assertEquals(4.0, pass) // weighted position 3, plus this pass
        assertEquals(minOf(memorySizeBudget(500, 4.0), 599), memoryRewriteLimit(listOf(old, new)))
        // A young memory joining pulls the life position back up the curve.
        val young = memoryRewriteState(listOf(old, MemorySizeState(200, 200, 0.0, weight = 3.0))).second
        assertTrue(young < pass)
    }

    @Test
    fun theValidatorRejectsARewriteThatDoesNotShrink() {
        val long = "x".repeat(900)
        val base = MemorySnapshot(episodes = listOf(episode("a", "w")), nodes = listOf(node("a", long)))
        val grown = node("v", long + " more").copy(sourceEpisodeIds = setOf(MemoryEpisodeId("e-a")))
        assertFailsWith<IllegalArgumentException> {
            base.applyMutation(
                MemoryStoreMutation(
                    nodesToAdd = listOf(grown),
                    edgesToAdd = listOf(MemoryEdge(MemoryEdgeId("v-s-a"), grown.id, MemoryNodeId("a"), MemoryRelationKind.Supersedes, createdAtEpochMillis = 1L)),
                ),
                MemoryIdIndex(base),
            )
        }
    }

    @Test
    fun fittingKeepsHigherWeightSentencesAndSpillsTheRest() {
        val text = "Filler sentence about nothing much at all here. The timeout is 30 seconds. More filler about the weather today."
        val weight = { s: String -> if ("30" in s) 3.0 else 1.0 }
        val fitted = MemoryRewrite.compress(text, 40, weight)
        assertEquals("The timeout is 30 seconds.", fitted.text)
        assertEquals(2, fitted.dropped.size)
        val spill = MemoryRewrite.spillFor(node("v", fitted.text), fitted.dropped)
        assertEquals("detail:v", spill!!.first.id.value)
    }

    // ---- time ranges ------------------------------------------------------------------------------

    @Test
    fun consolidatedVersionsCarryAWideningTimeRangeAndHistoryKeepsExactTimes() {
        val snapshot = MemorySnapshot(
            episodes = listOf(episode("a", "w", 100L), episode("b", "w", 300L), episode("c", "w", 50L)),
            nodes = listOf(node("a", "First."), node("b", "Second."), node("c", "Third, on 2024-03-01.")),
        )
        val first = MemoryTimeRange.metadataFor(snapshot, listOf(MemoryNodeId("a"), MemoryNodeId("b")))
        assertEquals("100", first[MEMORY_TIME_FROM])
        assertEquals("300", first[MEMORY_TIME_TO])
        val version = node("v", "First. Second.").copy(metadata = first)
        val wider = MemoryTimeRange.metadataFor(snapshot.copy(nodes = snapshot.nodes + version), listOf(version.id, MemoryNodeId("c")))
        assertEquals("100", wider[MEMORY_TIME_FROM])
        assertEquals(MemoryTimeRange.exactTime(snapshot, snapshot.nodes[2]).toString(), wider[MEMORY_TIME_TO])
        assertEquals("2024-03-01", MemoryTimeRange.isoDate(wider.getValue(MEMORY_TIME_TO).toLong()))
        assertNull(MemoryTimeRange.label(snapshot.nodes.first()), "a raw memory keeps its exact time")
        assertEquals(100L, MemoryTimeRange.exactTime(snapshot, snapshot.nodes.first()))
    }

    // ---- raw retention ----------------------------------------------------------------------------

    private suspend fun rawWorld(): World {
        val w = World()
        w.memory.registerWorkflow("wf", "P")
        val view = w.view("wf")
        val raw = { id: String, at: Long ->
            episode(id, "wf", at).copy(
                userPrompt = "prompt $id",
                chunks = listOf(MemorySourceChunk(MemoryChunkId("c-$id"), MemoryEpisodeId("e-$id"), 0, MemorySourceKind.Message, "m", "raw chat of $id")),
            )
        }
        val snapshot = view.read()
        view.commit(
            snapshot.revision,
            MemoryStoreMutation(
                episodesToAdd = listOf(raw("old", 0L), raw("new", 900L)),
                nodesToAdd = listOf(node("old", "Old memory.", 0L).copy(metadata = mapOf(MEMORY_TIME_FROM to "0", MEMORY_TIME_TO to "0", MEMORY_OCCURRENCES to "3"))),
            ),
        )
        return w
    }

    @Test
    fun rawHistoryIsKeptByDefault() = runBlocking<Unit> {
        val w = rawWorld()
        assertTrue(w.memory.applyRawRetention().isEmpty())
        assertTrue(w.banks.store("wf").read().episodes.all { it.purged == null && it.chunks.isNotEmpty() })
    }

    @Test
    fun aRetentionCapPurgesRawHistoryWithATombstoneAndLeavesMemoryIntact() = runBlocking<Unit> {
        val w = rawWorld()
        val before = w.banks.store("wf").read().nodes
        w.memory.updateSettings { it.copy(rawRetention = MemoryRawRetention(MemoryRawRetention.Mode.CapByAge, maxAgeMillis = 500L)) }
        w.memory.applyRawRetention()
        val episodes = w.banks.store("wf").read().episodes.associateBy { it.id.value }
        val old = episodes.getValue("e-old")
        assertTrue(old.chunks.isEmpty() && old.userPrompt.isEmpty())
        val tombstone = assertNotNull(old.purged)
        assertEquals("global", tombstone.setting)
        assertEquals(1, tombstone.chunks)
        assertTrue("days" in tombstone.reason)
        assertNull(episodes.getValue("e-new").purged)
        // Consolidated memory, its range and count are untouched.
        assertEquals(before, w.banks.store("wf").read().nodes)
        assertEquals("~3 times, 1970-01-01", MemoryTimeRange.label(before.single()))
    }

    private fun condenser() = object : MemoryManagerAgent {
        override suspend fun process(packet: MemoryWorkPacket): MemoryMutationBatch {
            val generalized = MemoryNode(
                id = MemoryNodeId("g-${packet.packetKey}"),
                kind = MemoryNodeKind.Context,
                text = packet.items.first().text,
                sourceEpisodeIds = packet.items.flatMapTo(linkedSetOf()) { item ->
                    item.metadata["sourceEpisodeIds"].orEmpty().split(',').filter(String::isNotBlank).map(::MemoryEpisodeId)
                },
                createdAtEpochMillis = 10L,
            )
            return MemoryMutationBatch(
                nodesToAdd = listOf(generalized),
                edgesToAdd = packet.items.map { MemoryEdge(MemoryEdgeId("${generalized.id.value}-${it.id}"), generalized.id, MemoryNodeId(it.id), MemoryRelationKind.CondensedFrom, createdAtEpochMillis = 10L) },
            )
        }
    }
}
