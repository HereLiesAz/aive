package com.hereliesaz.geministrator.memory

import com.russhwolf.settings.MapSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val WORKFLOW = "wf"

class MemoryLayerControllerTest {
    private fun controller(
        store: MemoryStore = InMemoryMemoryStore(),
        provider: MemoryEngineProvider = object : MemoryEngineProvider {},
        pause: suspend (Long) -> Unit = {},
    ) =
        MemoryLayerController(
            banks = MemoryBanks.inMemory(mapOf(WORKFLOW to store)),
            settingsStore = MemoryLayerSettingsStore(MapSettings()),
            engineProvider = provider,
            scope = CoroutineScope(Dispatchers.Unconfined),
            nowEpochMillis = { 1_000L },
            pause = pause,
        )

    private suspend fun MemoryStore.bank(id: String, text: String) {
        MemoryConsolidationQueue(this).enqueueSession(
            MemorySessionEnvelope(id, workflowRunId = WORKFLOW, userPrompt = text, parts = emptyList(), closedAtEpochMillis = id.hashCode().toLong() and 0xffff),
        )
    }

    @Test
    fun drainConsolidatesAndPublishes() = runBlocking {
        val store = InMemoryMemoryStore()
        store.bank("a", "Replace the settings store with SQLDelight because commits re-encode everything.")
        val controller = controller(store)
        controller.drain()
        assertTrue(controller.snapshot.value.queue.all { it.status == MemoryQueueStatus.Complete })
        assertTrue(controller.snapshot.value.nodes.isNotEmpty())
        assertEquals(null, controller.activity.value.working)
        assertEquals(MemoryConsolidationResult.Idle, controller.activity.value.last)
    }

    @Test
    fun pausedAndDisabledDoNotConsolidate() = runBlocking {
        val store = InMemoryMemoryStore()
        store.bank("a", "Something worth remembering about the gradle build.")
        val controller = controller(store)
        controller.updateSettings { it.copy(consolidationPaused = true) }
        controller.drain()
        assertTrue(store.read().queue.single().status != MemoryQueueStatus.Complete)
        controller.updateSettings { it.copy(consolidationPaused = false, enabled = false) }
        controller.drain()
        assertTrue(store.read().queue.single().status != MemoryQueueStatus.Complete)
    }

    @Test
    fun aFailingClerkIsAskedAgainAfterAGrowingWaitThatHonoursRetryAfter() = runBlocking {
        val store = InMemoryMemoryStore()
        store.bank("a", "First episode that will fail sectioning.")
        val provider = object : MemoryEngineProvider {
            override fun hostedAgent(role: MemoryMicroAgentRole, engine: MemoryStageEngine) =
                HostedMemoryGenerativeRuntime.agent(role, engine, MemoryMicroAgentPlatform.Linux) {
                    throw com.hereliesaz.geministrator.net.HttpStatusFailure(429, "Unable to generate: HTTP 429", retryAfterMillis = 30_000L)
                }
        }
        val pauses = mutableListOf<Long>()
        val controller = controller(store, provider) { pauses += it }
        controller.updateSettings { it.withEngine(MemoryMicroAgentRole.Sectioner, MemoryStageEngine(MemoryEngineKind.HostedModel)) }
        controller.drain()
        assertTrue(pauses.size >= 2, "every failed attempt waits before the next: $pauses")
        assertTrue(pauses.all { it >= 30_000L }, "never sooner than the provider asked: $pauses")
        assertTrue(pauses.zipWithNext().all { (a, b) -> b >= a }, "the wait does not shrink while it keeps failing: $pauses")
    }

    @Test
    fun parkedEntriesCanBeRetriedOrDiscarded() = runBlocking {
        val store = InMemoryMemoryStore()
        store.bank("a", "First episode that will fail sectioning.")
        store.bank("b", "Second episode that will fail sectioning.")
        var failing = true
        val provider = object : MemoryEngineProvider {
            override fun hostedAgent(role: MemoryMicroAgentRole, engine: MemoryStageEngine) =
                HostedMemoryGenerativeRuntime.agent(role, engine, MemoryMicroAgentPlatform.Linux) {
                    if (failing) error("provider down") else """{"sections":[],"nodes":[],"links":[]}"""
                }
        }
        val controller = controller(store, provider)
        controller.updateSettings { it.withEngine(MemoryMicroAgentRole.Sectioner, MemoryStageEngine(MemoryEngineKind.HostedModel)) }
        controller.drain()
        val parked = store.read().parked(controller.settings.value.policy)
        assertEquals(2, parked.size)

        failing = false
        controller.retry(parked[0].id)
        controller.drain()
        controller.discard(parked[1].id)
        val queue = store.read().queue.associateBy { it.id }
        assertEquals(MemoryQueueStatus.Complete, queue.getValue(parked[0].id).status)
        assertEquals(MemoryQueueStatus.Complete, queue.getValue(parked[1].id).status)
        assertTrue(queue.getValue(parked[1].id).lastError.orEmpty().startsWith("Discarded"))
    }

    @Test
    fun forgetRemovesOnlyWhatCameFromThatEpisode() = runBlocking {
        val store = InMemoryMemoryStore()
        store.bank("a", "The gradle build fails on Windows because of path length.")
        store.bank("b", "SettingsMemoryStore commits re-encode the whole snapshot.")
        val controller = controller(store)
        controller.drain()
        val before = store.read()
        val forgotten = before.episodes.first().id
        controller.forgetEpisode(forgotten)
        val after = store.read()
        assertTrue(after.episodes.none { it.id == forgotten })
        assertTrue(after.nodes.none { it.sourceEpisodeIds == setOf(forgotten) })
        assertTrue(after.nodes.isNotEmpty())
        val nodeIds = after.nodes.map { it.id }.toSet()
        assertTrue(after.edges.all { it.from in nodeIds && it.to in nodeIds })
    }

    /** A store that banks one more episode right after the first read, as a deposit landing mid-operation would. */
    private class DepositingStore(val inner: InMemoryMemoryStore, val deposit: suspend () -> Unit) : MemoryStore {
        var fired = true
        override suspend fun read(): MemorySnapshot = inner.read().also {
            if (!fired) { fired = true; deposit() }
        }
        override suspend fun commit(expectedRevision: Long, mutation: MemoryStoreMutation) = inner.commit(expectedRevision, mutation)
        override suspend fun replace(snapshot: MemorySnapshot) = inner.replace(snapshot)
        override suspend fun replaceIfRevision(expectedRevision: Long, snapshot: MemorySnapshot) =
            inner.replaceIfRevision(expectedRevision, snapshot)
    }

    @Test
    fun anEpisodeBankedWhileForgettingIsNotLost() = runBlocking {
        val inner = InMemoryMemoryStore()
        inner.bank("a", "The gradle build fails on Windows because of path length.")
        inner.bank("b", "SettingsMemoryStore commits re-encode the whole snapshot.")
        val depositing = DepositingStore(inner) { inner.bank("late", "A late deposit that must survive forgetting.") }
        val controller = controller(depositing)
        val forgotten = inner.read().episodes.first().id
        depositing.fired = false
        controller.forgetEpisode(forgotten)
        val after = inner.read()
        assertTrue(after.episodes.none { it.id == forgotten })
        assertTrue(after.episodes.any { it.sourceSessionId == "late" }, "the deposit made during forgetting was lost")
    }

    @Test
    fun theOldSharedStoreIsOfferedForDeletionOnlyAfterTheSplitCompletes() = runBlocking {
        var exists = true
        val backup = object : LegacyMemoryBackup {
            override suspend fun present() = exists
            override suspend fun delete() { exists = false }
        }
        val banks = MemoryBanks.inMemory(mapOf(WORKFLOW to InMemoryMemoryStore()))
        val controller = MemoryLayerController(
            banks = banks,
            settingsStore = MemoryLayerSettingsStore(MapSettings()),
            engineProvider = object : MemoryEngineProvider {},
            scope = CoroutineScope(Dispatchers.Unconfined),
            nowEpochMillis = { 1_000L },
            legacyBackup = backup,
        )
        controller.drain()
        assertEquals(false, controller.legacyBackupOffered.value, "no completed split yet")
        assertEquals(false, controller.deleteLegacyBackup())
        assertTrue(exists)

        banks.migrationState.recordMigration(MemoryBankMigrationReport(0L, emptyMap(), emptyMap(), emptyList()))
        controller.drain()
        assertEquals(true, controller.legacyBackupOffered.value)
        assertEquals(true, controller.deleteLegacyBackup())
        assertEquals(false, exists)
        assertEquals(false, controller.legacyBackupOffered.value)
    }

    @Test
    fun forgettingAnEpisodeAlsoDropsACondensedTextThatRestatesIt() {
        val a = MemoryEpisodeId("a")
        val b = MemoryEpisodeId("b")
        fun node(id: String, text: String, vararg episodes: MemoryEpisodeId) =
            MemoryNode(MemoryNodeId(id), MemoryNodeKind.Context, text, sourceEpisodeIds = episodes.toSet(), createdAtEpochMillis = 1L)
        fun edge(from: String, to: String, relation: MemoryRelationKind) =
            MemoryEdge(MemoryEdgeId("$from-$to-$relation"), MemoryNodeId(from), MemoryNodeId(to), relation, createdAtEpochMillis = 1L)
        val snapshot = MemorySnapshot(
            nodes = listOf(
                node("a1", "The staging host is zebra-17.", a),
                node("b1", "Deploys run from the main branch.", b),
                node("m", "Deploys run from main and the staging host is zebra-17.", a, b),
            ),
            edges = listOf(
                edge("m", "a1", MemoryRelationKind.CondensedFrom),
                edge("m", "b1", MemoryRelationKind.CondensedFrom),
                edge("m", "a1", MemoryRelationKind.Supersedes),
                edge("m", "b1", MemoryRelationKind.Supersedes),
            ),
        )

        val after = snapshot.without(a)

        assertTrue(after.nodes.none { "zebra-17" in it.text }, after.nodes.joinToString { it.text })
        // The surviving source is current again: nothing supersedes it any more.
        assertEquals(listOf("b1"), after.nodes.map { it.id.value })
        assertTrue(after.edges.isEmpty())
    }

    @Test
    fun exportImportRoundTripsAndClearEmpties() = runBlocking {
        val store = InMemoryMemoryStore()
        store.bank("a", "Memory export must round-trip exactly.")
        val controller = controller(store)
        controller.drain()
        val exported = controller.exportJson()
        controller.clearAll()
        assertEquals(0, store.read().episodes.size)
        controller.importJson(exported)
        assertEquals(1, store.read().episodes.size)
        assertEquals(exported, controller.exportJson())
    }

    @Test
    fun partsLeftBehindByACrashAreSentOnceAtTheNextStart() = runBlocking {
        val settings = MapSettings()
        val store = InMemoryMemoryStore()
        SettingsMemoryCaptureJournal(settings).record(
            "run-1",
            MemorySessionEnvelope(
                sourceSessionId = "run-1",
                workflowRunId = WORKFLOW,
                userPrompt = "Fix the build",
                parts = listOf(MemorySessionPart(MemorySourceKind.Message, "Agent message", "gamma finding about the gradle cache")),
                closedAtEpochMillis = 7L,
                sequence = 1,
            ),
        )
        val controller = MemoryLayerController(
            banks = MemoryBanks.inMemory(mapOf(WORKFLOW to store)),
            settingsStore = MemoryLayerSettingsStore(settings),
            engineProvider = object : MemoryEngineProvider {},
            scope = CoroutineScope(Dispatchers.Unconfined),
            nowEpochMillis = { 1_000L },
        )
        assertEquals(1, controller.recoverCapturedSessions())
        val episode = store.read().episodes.single()
        assertTrue(episode.chunks.any { "gamma finding about the gradle cache" in it.text })
        assertTrue(SettingsMemoryCaptureJournal(settings).pending().isEmpty(), "the journal let go of it")
        assertEquals(0, controller.recoverCapturedSessions(), "nothing is sent twice")
        assertEquals(1, store.read().episodes.size)
    }
}
