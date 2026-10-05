package com.hereliesaz.geministrator.memory

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Contrast detection, the variant register, divergence markers, coverage-only supersession and deliberations. */
class MemoryContrastRegisterTest {
    // ---- contrast detection -------------------------------------------------------------------

    @Test
    fun sameFrameDifferentFillerIsAContrast() {
        val contrast = assertNotNull(MemoryContrast.between("I chose Postgres for the database.", "I chose MySQL for the database."))
        assertEquals("i chose _ for the database", contrast.frameKey)
        assertEquals("postgresql", contrast.leftFiller)
        assertEquals("mysql", contrast.rightFiller)
        assertEquals("Postgres", contrast.leftFillerText)
    }

    @Test
    fun aliasesOfOneNameAreNotAContrast() {
        assertNull(MemoryContrast.between("I chose Postgres for the database.", "I chose PostgreSQL for the database."))
    }

    @Test
    fun repeatsAndParaphrasesWithoutAFillerChangeAreNotContrasts() {
        assertNull(MemoryContrast.between("The connection pool is shared.", "The connection pool is shared."))
        assertNull(MemoryContrast.between("We fixed the flaky cache test today.", "We fixed the flaky cache test."))
        assertNull(MemoryContrast.between("The router retries twice.", "Deploy notes live in the wiki."))
    }

    @Test
    fun valuesIdentifiersAndPolarityAreFillers() {
        assertNotNull(MemoryContrast.between("API timeout is 30 seconds.", "API timeout is 60 seconds."))
        assertNotNull(MemoryContrast.between("Set retries in config_a.yaml.", "Set retries in config_b.yaml."))
        assertNotNull(MemoryContrast.between("Indent with tabs.", "Indent with spaces."))
        assertNotNull(MemoryContrast.between("The cache is enabled in release builds.", "The cache is not enabled in release builds."))
    }

    @Test
    fun condensationGuardRefusesContrastingTexts() {
        assertTrue(condensationWouldAdjudicate(listOf("I chose Postgres for the database.", "I chose MySQL for the database.")))
        assertFalse(condensationWouldAdjudicate(listOf("I chose Postgres for the database.", "I chose PostgreSQL for the database.")))
    }

    // ---- engine: condensation, register, divergence ---------------------------------------------

    @Test
    fun aContrastingClusterIsNeverOfferedAndIsRegisteredAndMarked() = runBlocking<Unit> {
        val store = store(
            "c-1" to "I chose Postgres for the database.",
            "c-2" to "I chose MySQL for the database.",
            "c-3" to "I chose Postgres for the database.",
        )
        val offered = mutableListOf<List<String>>()
        val manager = condenser { packet -> offered += packet.items.map { it.id } }

        drain(MemoryConsolidator(store, manager, policy))

        val snapshot = store.read()
        // The two Postgres memories may fold; the MySQL one is never in a cluster with them.
        assertTrue(offered.none { "c-2" in it }, "a contrasting memory reached a condensation clerk: $offered")
        val divergences = snapshot.edges.filter { it.relation == MemoryRelationKind.Diverges }.map { "${it.from.value}|${it.to.value}" }.toSet()
        assertTrue(divergences.containsAll(setOf("c-1|c-2", "c-2|c-3")), "$divergences")
        // The generalized Postgres memory inherits its sources' marker, so recalling it brings MySQL too.
        val generalized = snapshot.nodes.single { it.id.value.startsWith("g-") }
        assertTrue("c-2|${generalized.id.value}" in divergences, "$divergences")

        val register = snapshot.variantRegister().single()
        assertEquals("I chose _ for the database", register.frame)
        val byFiller = register.variants.associateBy { it.filler }
        assertEquals(setOf("Postgres", "MySQL"), byFiller.keys)
        assertEquals(
            setOf(MemoryNodeId("c-1"), MemoryNodeId("c-3"), generalized.id),
            byFiller.getValue("Postgres").attestations.map { it.memoryId }.toSet(),
        )
        assertEquals(2, byFiller.getValue("Postgres").occurrences)
        val mysql = byFiller.getValue("MySQL").attestations.single()
        assertEquals(2L, mysql.recordedAtEpochMillis)
        assertEquals("I chose", mysql.subject)
        assertEquals("session-e-c-2", mysql.context["sessions"])
        assertEquals("project", mysql.context["projectId"])
        assertEquals(listOf("e-c-2"), mysql.sourceEpisodeIds)
        // Nothing is ranked or hidden: no node in the register carries a verdict, and nothing is superseded.
        assertTrue(snapshot.edges.none { it.relation == MemoryRelationKind.Supersedes && it.to.value == "c-2" })
    }

    @Test
    fun registerIsAddOnlyAndIdempotent() = runBlocking<Unit> {
        val store = store("c-1" to "The release ships on Tuesday 2026-05-04.", "c-2" to "The release ships on Friday 2026-05-07.")
        val snapshot = store.read()
        val first = MemoryVariantRegister.mutationFor(snapshot, MemoryEpisodeId("e-c-1"), 10L)
        assertTrue(store.commit(snapshot.revision, first))
        val again = MemoryVariantRegister.mutationFor(store.read(), MemoryEpisodeId("e-c-2"), 11L)
        assertTrue(again.nodesToAdd.isEmpty() && again.edgesToAdd.isEmpty())
        val attestation = store.read().variantRegister().single().variants.first().attestations.single()
        assertEquals("2026-05-04", attestation.statedEventDate)
    }

    @Test
    fun recallReturnsDivergentPartnersTogetherEvenWhenThePartnerIsHidden() = runBlocking<Unit> {
        val store = store("c-1" to "I chose Postgres for the database.", "c-2" to "I chose MySQL for the database.")
        val snapshot = store.read()
        val register = MemoryVariantRegister.mutationFor(snapshot, MemoryEpisodeId("e-c-1"), 10L)
        // Hide c-2 from ranking entirely (as a covered source would be) to prove the unit holds anyway.
        val hider = MemoryNode(MemoryNodeId("cover"), MemoryNodeKind.Context, "Unrelated cover text.", createdAtEpochMillis = 1L)
        assertTrue(
            store.commit(
                snapshot.revision,
                register.copy(
                    nodesToAdd = register.nodesToAdd + hider,
                    edgesToAdd = register.edgesToAdd + MemoryEdge(MemoryEdgeId("hide"), hider.id, MemoryNodeId("c-2"), MemoryRelationKind.Supersedes, createdAtEpochMillis = 1L),
                ),
            ),
        )
        val tool = GraphMemoryTool(store)

        // Default query: divergence surfacing is on.
        val hit = tool.grip(MemoryQuery("Postgres database", resolution = MemoryResolution.Context)).hits.single { it.node.id.value == "c-1" }
        assertEquals(listOf("c-2"), hit.conflicts.map { it.id.value })

        // Rendering never cuts a unit in two, whatever the budget.
        val units = listOf("first${hit.divergenceLines()}", "second unit that does not fit")
        val rendered = units.joinWholeUnits(maxChars = units.first().length + 3)
        assertTrue("MySQL" in rendered && "second" !in rendered)
    }

    @Test
    fun aDeliberationIsRecordedBesideTheDivergenceAndNeverReplacesIt() = runBlocking<Unit> {
        val store = store("c-1" to "I chose Postgres for the database.", "c-2" to "I chose MySQL for the database.")
        val snapshot = store.read()
        assertTrue(store.commit(snapshot.revision, MemoryVariantRegister.mutationFor(snapshot, MemoryEpisodeId("e-c-1"), 10L)))
        val tool = GraphMemoryTool(store)

        val deliberation = tool.deliberate(
            MemoryDeliberationRequest(
                sourceSessionId = "agent-session",
                conclusion = "MySQL was the earlier prototype; production uses Postgres.",
                citedNodeIds = listOf(MemoryNodeId("c-1"), MemoryNodeId("c-2")),
                evidence = listOf("deploy manifest names postgres"),
                deliberatedAtEpochMillis = 20L,
            ),
        )

        val after = store.read()
        assertEquals(MemoryNodeKind.Deliberation, deliberation.kind)
        assertTrue(after.queue.none { it.episodeId in deliberation.sourceEpisodeIds }, "deliberations are not consolidated")
        assertTrue(after.edges.any { it.relation == MemoryRelationKind.Diverges }, "the marker stays")
        assertTrue(after.edges.none { it.relation == MemoryRelationKind.Supersedes })
        val hit = tool.grip(MemoryQuery("MySQL database", resolution = MemoryResolution.Context)).hits.single { it.node.id.value == "c-2" }
        assertEquals(listOf("c-1"), hit.conflicts.map { it.id.value })
        assertEquals(listOf(deliberation.id), hit.deliberations.map { it.id })
        assertTrue("earlier deliberation" in hit.divergenceLines())

        assertFailsWith<IllegalArgumentException> {
            tool.deliberate(MemoryDeliberationRequest("s", "x", listOf(MemoryNodeId("missing")), deliberatedAtEpochMillis = 1L))
        }
    }

    // ---- supersession is coverage only -----------------------------------------------------------

    @Test
    fun condensationSupersedesOnlyCoveredSources() = runBlocking<Unit> {
        val store = store(
            "c-1" to "The connection pool is shared.",
            "c-2" to "The connection pool is shared.",
            "c-3" to "The connection pool is shared. It is sized by the gateway.",
        )
        // Generalized memory: covers c-1 and c-2 (identical) but drops c-3's second sentence.
        val manager = condenser(text = "The connection pool is shared.")

        drain(MemoryConsolidator(store, manager, policy))

        val snapshot = store.read()
        val superseded = snapshot.edges.filter { it.relation == MemoryRelationKind.Supersedes }.map { it.to.value }.toSet()
        assertEquals(setOf("c-1", "c-2"), superseded)
        val condensedFrom = snapshot.edges.filter { it.relation == MemoryRelationKind.CondensedFrom }.map { it.to.value }.toSet()
        assertEquals(setOf("c-1", "c-2", "c-3"), condensedFrom)
        // c-3 stays recallable beside the generalized entry.
        val hits = GraphMemoryTool(store).grip(MemoryQuery("gateway", resolution = MemoryResolution.Context)).hits
        assertTrue(hits.any { it.node.id.value == "c-3" })
    }

    @Test
    fun aClerkThatWritesSupersedesIsRejected() = runBlocking<Unit> {
        val store = store("c-1" to "The connection pool is shared.", "c-2" to "The connection pool is shared.", "c-3" to "The connection pool is shared.", queueAll = false)
        val manager = condenser(text = "The connection pool is shared.", alsoSupersede = true)

        val result = MemoryConsolidator(store, manager, policy).processNext(10L)

        assertIs<MemoryConsolidationResult.Failed>(result)
        assertTrue(store.read().edges.none { it.relation == MemoryRelationKind.Supersedes })
    }

    @Test
    fun modelDecodersRefuseEngineOwnedRelations() = runBlocking<Unit> {
        val packet = MemoryWorkPacket(
            queueId = MemoryQueueId("q"),
            episodeId = MemoryEpisodeId("e"),
            stage = MemoryConsolidationStage.Associations,
            packetKey = "a0",
            items = listOf(
                MemoryWorkItem("n1", "node:Context", "one"),
                MemoryWorkItem("n2", "node:Context", "two"),
            ),
            instruction = "associate",
        )
        listOf("Supersedes", "ResolvesConflict", "ConflictsWith", "Diverges").forEach { relation ->
            val agent = StructuredMemoryMicroAgent(
                role = MemoryMicroAgentRole.CondensationRewriter,
                model = MemoryMicroAgentModelSpec("portable-memory"),
                runtime = FixedRuntime("""{"links":[{"from":"n1","to":"n2","relation":"$relation"}]}"""),
                nowEpochMillis = { 1L },
            )
            assertFailsWith<IllegalArgumentException>(relation) { agent.process(packet) }
        }
        assertFalse(MemoryRelationKind.Supersedes in MODEL_WRITABLE_RELATIONS)
        assertFalse(MemoryRelationKind.ResolvesConflict in MODEL_WRITABLE_RELATIONS)
    }

    @Test
    fun forgettingTheOnlyAttestingEpisodeRemovesItsVariant() = runBlocking<Unit> {
        val store = store("c-1" to "I chose Postgres for the database.", "c-2" to "I chose MySQL for the database.")
        val snapshot = store.read()
        assertTrue(store.commit(snapshot.revision, MemoryVariantRegister.mutationFor(snapshot, MemoryEpisodeId("e-c-1"), 10L)))

        val forgotten = store.read().without(MemoryEpisodeId("e-c-2"))

        assertTrue(forgotten.nodes.none { it.kind == MemoryNodeKind.Variant && it.text == "MySQL" })
        assertEquals(listOf("Postgres"), forgotten.variantRegister().single().variants.map { it.filler })
    }

    // ---- helpers -------------------------------------------------------------------------------

    private val policy = MemoryConsolidationPolicy(
        maxSimilarPerKind = MemoryConsolidationPolicy().maxSimilarPerKind + (MemoryNodeKind.Context to 2),
    )

    /** One Context node per pair, each from its own episode in one project, chained by SimilarTo; queued at Condensation. */
    private suspend fun store(vararg texts: Pair<String, String>, queueAll: Boolean = true): InMemoryMemoryStore {
        val episodes = texts.map { (id, _) ->
            MemoryEpisode(
                id = MemoryEpisodeId("e-$id"),
                sourceSessionId = "session-e-$id",
                projectId = "project",
                userPrompt = "prompt",
                chunks = emptyList(),
                createdAtEpochMillis = 1L,
            )
        }
        val nodes = texts.mapIndexed { index, (id, text) ->
            MemoryNode(MemoryNodeId(id), MemoryNodeKind.Context, text, sourceEpisodeIds = setOf(MemoryEpisodeId("e-$id")), createdAtEpochMillis = index + 1L)
        }
        val edges = nodes.flatMapIndexed { i, a ->
            nodes.drop(i + 1).map { b -> MemoryEdge(MemoryEdgeId("sim-${a.id.value}-${b.id.value}"), a.id, b.id, MemoryRelationKind.SimilarTo, 0.95f, 5L) }
        }
        return InMemoryMemoryStore().apply {
            commit(
                0,
                MemoryStoreMutation(
                    episodesToAdd = episodes,
                    nodesToAdd = nodes,
                    edgesToAdd = edges,
                    queueUpserts = (if (queueAll) episodes else episodes.take(1)).mapIndexed { index, episode ->
                        MemoryQueueEntry(MemoryQueueId("q-$index"), index + 1L, episode.id, stage = MemoryConsolidationStage.Condensation, createdAtEpochMillis = 1L)
                    },
                ),
            )
        }
    }

    private fun condenser(
        text: String? = null,
        alsoSupersede: Boolean = false,
        onPacket: (MemoryWorkPacket) -> Unit = {},
    ) = object : MemoryManagerAgent {
        override suspend fun process(packet: MemoryWorkPacket): MemoryMutationBatch {
            onPacket(packet)
            val generalized = MemoryNode(
                id = MemoryNodeId("g-${packet.packetKey}"),
                kind = MemoryNodeKind.Context,
                text = text ?: packet.items.first().text,
                sourceEpisodeIds = packet.items.flatMapTo(linkedSetOf()) { item ->
                    item.metadata["sourceEpisodeIds"].orEmpty().split(',').filter(String::isNotBlank).map(::MemoryEpisodeId)
                },
                createdAtEpochMillis = 10L,
            )
            val relations = if (alsoSupersede) listOf(MemoryRelationKind.CondensedFrom, MemoryRelationKind.Supersedes) else listOf(MemoryRelationKind.CondensedFrom)
            val edges = packet.items.flatMap { item ->
                relations.map { MemoryEdge(MemoryEdgeId("${generalized.id.value}-${item.id}-$it"), generalized.id, MemoryNodeId(item.id), it, createdAtEpochMillis = 10L) }
            }
            return MemoryMutationBatch(nodesToAdd = listOf(generalized), edgesToAdd = edges)
        }
    }

    private suspend fun drain(consolidator: MemoryConsolidator) {
        repeat(50) { if (consolidator.processNext(10L) is MemoryConsolidationResult.Idle) return }
        error("consolidation did not finish")
    }

    private class FixedRuntime(private val answer: String) : MemoryGenerativeInferenceRuntime {
        override val platform = MemoryMicroAgentPlatform.entries.first()
        override val computePreference = MemoryComputePreference.AUTO
        override val capabilityDetector = object : HardwareCapabilityDetector {
            override suspend fun discover(): List<MemoryComputeDevice> = listOf(
                MemoryComputeDevice("CPUExecutionProvider", "CPU", MemoryComputeDeviceType.CPU),
            )
        }

        override suspend fun isAvailable(model: MemoryMicroAgentModelSpec, artifact: MemoryMicroAgentArtifact) = true

        override suspend fun generate(request: MemoryGenerativeInferenceRequest) = MemoryGenerativeInferenceResult(answer)
    }
}
