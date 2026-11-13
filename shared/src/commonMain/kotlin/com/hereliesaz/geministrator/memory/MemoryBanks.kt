package com.hereliesaz.geministrator.memory

import com.hereliesaz.geministrator.persistence.refuseIfUnreadable
import com.hereliesaz.geministrator.persistence.readQuarantining
import com.hereliesaz.geministrator.persistence.ChunkedStringSettings
import com.russhwolf.settings.Settings
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * The physical memory banks: one store per workflow run, holding only the records that workflow
 * appended (its own SQLite database, or its own Settings keys). A workflow reads its ancestors' banks
 * through [LineageMemoryStore]; nothing is copied between banks. See [MemoryLineage] for the model.
 *
 * Each bank's store is opened with external references allowed (its records may cite its ancestors'),
 * and [store] wraps it in [WorkflowBankStore], which refuses any other workflow's episode.
 */
class MemoryBanks(
    private val openBank: suspend (workflowId: String) -> MemoryStore,
    private val registry: MemoryBankRegistry = InMemoryMemoryBankRegistry(),
    /** How many banks that hold a connection are kept open; the least recently used idle ones are closed past it. */
    private val maxOpen: Int = DEFAULT_MAX_OPEN_BANKS,
) {
    private class Slot(val store: MemoryStore) {
        var inUse = 0
    }

    private val mutex = Mutex()

    /** Open banks, least recently used first. */
    private val open = LinkedHashMap<String, Slot>()
    private val handles = HashMap<String, MemoryStore>()

    /** [workflowId]'s own bank, opened on first use and reopened if it was closed while idle. */
    suspend fun store(workflowId: String): MemoryStore {
        val key = requireNotNull(memoryBankKey(workflowId)) { "A memory bank belongs to a workflow" }
        return mutex.withLock {
            handles.getOrPut(key) {
                registry.add(key)
                WorkflowBankStore(key, LeasedBankStore(key))
            }
        }
    }

    /**
     * Runs [block] on the bank's open store. The bank counts as in use meanwhile, so it is never closed under
     * the call; once idle it may be closed (only a store with a connection to release) when too many are open.
     */
    private suspend fun <T> withBank(key: String, block: suspend (MemoryStore) -> T): T {
        val slot = mutex.withLock {
            val found = open.remove(key) ?: Slot(openBank(key))
            open[key] = found
            found.inUse++
            found
        }
        try {
            return block(slot.store)
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    slot.inUse--
                    closeIdleBeyondLimit()
                }
            }
        }
    }

    private fun closeIdleBeyondLimit() {
        if (open.size <= maxOpen) return
        val iterator = open.entries.iterator()
        while (open.size > maxOpen && iterator.hasNext()) {
            val entry = iterator.next()
            val store = entry.value.store
            // A store with no connection to release (Settings, in memory) keeps its data in the slot: never dropped.
            if (entry.value.inUse == 0 && store is ClosableMemoryStore) {
                store.close()
                iterator.remove()
            }
        }
    }

    private inner class LeasedBankStore(private val key: String) : MemoryStore {
        override suspend fun read(): MemorySnapshot = withBank(key) { it.read() }

        override suspend fun commit(expectedRevision: Long, mutation: MemoryStoreMutation): Boolean =
            withBank(key) { it.commit(expectedRevision, mutation) }

        override suspend fun replace(snapshot: MemorySnapshot) = withBank(key) { it.replace(snapshot) }

        override suspend fun replaceIfRevision(expectedRevision: Long, snapshot: MemorySnapshot): Boolean =
            withBank(key) { it.replaceIfRevision(expectedRevision, snapshot) }
    }

    /** Every workflow with a bank, by id. */
    suspend fun known(): List<String> =
        (registry.known() + mutex.withLock { handles.keys.toList() }).distinct().sorted()

    val migrationState: MemoryBankRegistry get() = registry

    companion object {
        const val DEFAULT_MAX_OPEN_BANKS: Int = 8

        /** Banks held in memory only (tests, previews). [initial] seeds given banks. */
        fun inMemory(initial: Map<String, MemoryStore> = emptyMap()): MemoryBanks =
            MemoryBanks({ key -> initial[key] ?: InMemoryMemoryStore(allowExternalReferences = true) }, InMemoryMemoryBankRegistry(initial.keys))

        /** One [SettingsMemoryStore] per workflow bank, under its own storage key. */
        fun settings(settings: Settings = Settings()): MemoryBanks = MemoryBanks(
            openBank = { key -> SettingsMemoryStore(settings, settingsBankStorageKey(key), allowExternalReferences = true) },
            registry = SettingsMemoryBankRegistry(settings),
        )

        fun settingsBankStorageKey(workflowId: String): String =
            "${SettingsMemoryStore.DEFAULT_STORAGE_KEY}.bank.${memoryBankFileStem(workflowId)}"
    }
}

/** A non-blank id, or null. */
fun memoryBankKey(id: String?): String? = id?.takeIf(String::isNotBlank)

/** Prefix of the workflow identity a session outside any workflow run gets. */
const val SESSION_WORKFLOW_PREFIX: String = "session:"

/**
 * The workflow a session's memory belongs to: its workflow run, or, outside any workflow, the
 * session itself as a root workflow (`session:` plus its task run id, else its session id).
 */
fun memoryWorkflowOf(workflowRunId: String?, taskRunId: String?, sessionId: String? = null): String =
    memoryBankKey(workflowRunId)
        ?: SESSION_WORKFLOW_PREFIX + requireNotNull(memoryBankKey(taskRunId) ?: memoryBankKey(sessionId)) {
            "A session outside any workflow needs a task run or session id"
        }

/** A file- and key-safe, collision-free stem: `w-` plus the id's stable hash and a readable prefix. */
fun memoryBankFileStem(workflowId: String): String {
    val key = requireNotNull(memoryBankKey(workflowId)) { "A memory bank belongs to a workflow" }
    val readable = key.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(32)
    return "w-${MemoryVariantRegister.stableHash(key)}" + if (readable.isEmpty()) "" else "-$readable"
}

/** One workflow's own bank. Refuses to store an episode of any other workflow (commit and replace). */
class WorkflowBankStore(
    val workflowId: String,
    private val delegate: MemoryStore,
) : MemoryStore {
    override suspend fun read(): MemorySnapshot = delegate.read()

    override suspend fun commit(expectedRevision: Long, mutation: MemoryStoreMutation): Boolean {
        requireOwn(mutation.episodesToAdd)
        return delegate.commit(expectedRevision, mutation)
    }

    override suspend fun replace(snapshot: MemorySnapshot) {
        requireOwn(snapshot.episodes)
        delegate.replace(snapshot)
    }

    override suspend fun replaceIfRevision(expectedRevision: Long, snapshot: MemorySnapshot): Boolean {
        requireOwn(snapshot.episodes)
        return delegate.replaceIfRevision(expectedRevision, snapshot)
    }

    private fun requireOwn(episodes: List<MemoryEpisode>) {
        val foreign = episodes.filter { it.workflowRunId != workflowId }
        if (foreign.isNotEmpty()) {
            throw MemoryCrossBankException(
                "Workflow $workflowId's bank cannot hold episodes of another workflow: " +
                    foreign.joinToString { "${it.id.value} (${it.workflowRunId ?: "no workflow"})" },
            )
        }
    }
}

class MemoryCrossBankException(message: String) : IllegalArgumentException(message)

/**
 * The old pre-banks shared memory store, left on disk untouched as the backup after the split. It
 * holds every workflow's raw text in one place that retention, Forget and Clear-all never reach, so
 * once the split is verified the keeper is offered its deletion ([MemoryLayerController.legacyBackupOffered]).
 */
interface LegacyMemoryBackup {
    /** True while the old store still exists on this device. */
    suspend fun present(): Boolean

    /** Permanently removes the old store (every file/record of it). */
    suspend fun delete()
}

/** Which banks exist, and the state of the one-time split of the old shared store. */
interface MemoryBankRegistry {
    suspend fun known(): Set<String>

    suspend fun add(workflowId: String)

    /** The stored report of the completed split, or null when the split has not completed. */
    suspend fun migrationReport(): MemoryBankMigrationReport?

    suspend fun recordMigration(report: MemoryBankMigrationReport)
}

class InMemoryMemoryBankRegistry(initial: Set<String> = emptySet()) : MemoryBankRegistry {
    private val mutex = Mutex()
    private val banks = initial.toMutableSet()
    private var report: MemoryBankMigrationReport? = null

    override suspend fun known(): Set<String> = mutex.withLock { banks.toSet() }

    override suspend fun add(workflowId: String) {
        mutex.withLock { memoryBankKey(workflowId)?.let { banks += it } }
    }

    override suspend fun migrationReport(): MemoryBankMigrationReport? = mutex.withLock { report }

    override suspend fun recordMigration(report: MemoryBankMigrationReport) {
        mutex.withLock { this.report = report }
    }
}

class SettingsMemoryBankRegistry(
    settings: Settings,
    private val key: String = "${SettingsMemoryStore.DEFAULT_STORAGE_KEY}.banks",
) : MemoryBankRegistry {
    private val settings: Settings = ChunkedStringSettings.unbounded(settings)
    private val mutex = Mutex()
    private val serializer = ListSerializer(String.serializer())

    override suspend fun known(): Set<String> = mutex.withLock { load() }

    override suspend fun add(workflowId: String) {
        mutex.withLock {
            val current = load()
            val key = memoryBankKey(workflowId) ?: return@withLock
            if (key in current) return@withLock
            settings.refuseIfUnreadable(this.key) { SettingsMemoryStore.defaultJson.decodeFromString(serializer, it) }
            settings.putString(this.key, SettingsMemoryStore.defaultJson.encodeToString(serializer, (current + key).toList()))
        }
    }

    override suspend fun migrationReport(): MemoryBankMigrationReport? = mutex.withLock {
        settings.getStringOrNull("$key.migration")?.let {
            runCatching { SettingsMemoryStore.defaultJson.decodeFromString(MemoryBankMigrationReport.serializer(), it) }.getOrNull()
        }
    }

    override suspend fun recordMigration(report: MemoryBankMigrationReport) {
        mutex.withLock {
            settings.putString("$key.migration", SettingsMemoryStore.defaultJson.encodeToString(MemoryBankMigrationReport.serializer(), report))
        }
    }

    private fun load(): Set<String> =
        settings.readQuarantining(key) { SettingsMemoryStore.defaultJson.decodeFromString(serializer, it) }
            .orEmpty()
            .mapNotNullTo(linkedSetOf(), ::memoryBankKey)
}

// ---- one-time split of the old shared store -------------------------------------------------------

/** One record left out of every bank because it came from more than one workflow. */
@Serializable
data class MemoryBankMigrationExclusion(
    /** `node`, `edge` or `condensation-decline`. */
    val kind: String,
    val id: String,
    /** The workflows its provenance touches. */
    val workflows: List<String>,
    val reason: String,
)

@Serializable
data class MemoryBankMigrationReport(
    val sourceRevision: Long,
    /** Episodes moved into each workflow's bank. */
    val episodesPerWorkflow: Map<String, Int>,
    /** Workflow -> project, as registered in the lineage (absent: no project). */
    val projects: Map<String, String>,
    val excluded: List<MemoryBankMigrationExclusion>,
    /**
     * Lineage found in the old store. It records no continuation or merge, so every workflow starts
     * as a root; parents are added later, add-only, when runs declare them.
     */
    val lineage: String = "none recorded: every workflow is a root",
)

/** The deterministic split: one snapshot per workflow, plus what belongs to no single workflow. */
data class MemoryBankSplit(
    val banks: Map<String, MemorySnapshot>,
    val projects: Map<String, String>,
    val report: MemoryBankMigrationReport,
)

/**
 * Splits one shared graph into per-workflow banks. Each episode belongs to its workflow run, or,
 * without one, to its session as a root workflow ([memoryWorkflowOf]). A memory goes with its source
 * episodes and is tagged with that workflow; a memory with no source episodes (a register frame or
 * variant) goes with the memories it is linked to. An edge goes to a bank only when both ends do.
 * Anything drawn from several workflows — the old store records no lineage between them — is placed
 * in no bank and listed in the report (the old store keeps it as the backup).
 */
fun MemorySnapshot.splitIntoBanks(): MemoryBankSplit {
    val excluded = mutableListOf<MemoryBankMigrationExclusion>()
    val episodeBank = episodes.associate { it.id to memoryWorkflowOf(it.workflowRunId, it.taskRunId, it.sourceSessionId) }
    val projects = linkedMapOf<String, String>()
    episodes.forEach { episode -> memoryBankKey(episode.projectId)?.let { projects.getOrPut(episodeBank.getValue(episode.id)) { it } } }
    val nodeBank = HashMap<MemoryNodeId, String>()
    val unplaced = mutableListOf<MemoryNode>()
    nodes.forEach { node ->
        val banks = node.sourceEpisodeIds.mapNotNull(episodeBank::get).distinct()
        when {
            banks.size == 1 -> nodeBank[node.id] = banks.single()
            banks.size > 1 -> excluded += MemoryBankMigrationExclusion("node", node.id.value, banks, "drawn from more than one workflow")
            node.sourceEpisodeIds.isNotEmpty() -> excluded += MemoryBankMigrationExclusion("node", node.id.value, emptyList(), "its source episodes are missing")
            else -> unplaced += node
        }
    }
    var remaining = unplaced.toList()
    while (remaining.isNotEmpty()) {
        val placedNow = mutableListOf<MemoryNode>()
        remaining.forEach { node ->
            val banks = edges.asSequence()
                .filter { it.from == node.id || it.to == node.id }
                .map { if (it.from == node.id) it.to else it.from }
                .mapNotNull(nodeBank::get)
                .distinct()
                .toList()
            if (banks.size == 1) {
                nodeBank[node.id] = banks.single()
                placedNow += node
            } else if (banks.size > 1) {
                excluded += MemoryBankMigrationExclusion("node", node.id.value, banks, "linked to memories of more than one workflow")
                placedNow += node
            }
        }
        if (placedNow.isEmpty()) break
        remaining = remaining - placedNow.toSet()
    }
    remaining.forEach { node ->
        excluded += MemoryBankMigrationExclusion("node", node.id.value, emptyList(), "no provenance and no link to any workflow's memory")
    }

    val edgeBank = HashMap<MemoryEdgeId, String>()
    edges.forEach { edge ->
        val from = nodeBank[edge.from]
        val to = nodeBank[edge.to]
        if (from != null && from == to) {
            edgeBank[edge.id] = from
        } else {
            excluded += MemoryBankMigrationExclusion(
                "edge", edge.id.value, listOfNotNull(from, to).distinct(),
                if (from != null && to != null) "links memories of different workflows" else "an end is placed in no bank",
            )
        }
    }

    val declinedBank = HashMap<String, String>()
    declinedCondensations.forEach { key ->
        val banks = key.split('|').filter(String::isNotEmpty).map { nodeBank[MemoryNodeId(it)] }.distinct()
        val single = banks.singleOrNull()
        if (single != null) {
            declinedBank[key] = single
        } else {
            excluded += MemoryBankMigrationExclusion("condensation-decline", key, banks.filterNotNull(), "members span workflows or are placed in no bank")
        }
    }

    val sessions = episodes.associate { it.id to it.sourceSessionId }
    val split = (episodeBank.values + nodeBank.values).distinct().sorted().associateWith { bank ->
        val bankEpisodes = episodes.filter { episodeBank[it.id] == bank }.map { it.copy(workflowRunId = bank) }
        val episodeIds = bankEpisodes.mapTo(hashSetOf()) { it.id }
        MemorySnapshot(
            revision = revision,
            episodes = bankEpisodes,
            sections = sections.filter { it.episodeId in episodeIds },
            nodes = nodes.filter { nodeBank[it.id] == bank }.map { node ->
                val session = node.sourceEpisodeIds.singleOrNull()?.let(sessions::get)
                node.copy(metadata = node.metadata + (PRODUCED_BY_WORKFLOW to bank) + listOfNotNull(session?.let { PRODUCED_BY_SESSION to it }))
            },
            edges = edges.filter { edgeBank[it.id] == bank },
            queue = queue.filter { it.episodeId in episodeIds },
            declinedCondensations = declinedCondensations.filter { declinedBank[it] == bank },
        )
    }
    return MemoryBankSplit(
        banks = split,
        projects = projects,
        report = MemoryBankMigrationReport(
            sourceRevision = revision,
            episodesPerWorkflow = split.mapValues { it.value.episodes.size },
            projects = projects,
            excluded = excluded,
        ),
    )
}

/**
 * Splits the old shared store [legacy] into per-workflow [banks] once, registering every workflow
 * (as a root) and its project in [lineage]. Crash-safe and idempotent: the split is deterministic; a
 * bank that does not yet hold exactly its part is (re)written whole in one replace; every bank is
 * read back and verified before the report is recorded, and only a recorded report marks the split
 * done. [legacy] is never written: it stays as the backup.
 */
suspend fun migrateSharedMemoryToBanks(legacy: MemoryStore, banks: MemoryBanks, lineage: MemoryLineage): MemoryBankMigrationReport? {
    banks.migrationState.migrationReport()?.let { return it }
    val source = legacy.read()
    if (source.episodes.isEmpty() && source.nodes.isEmpty()) return null
    val split = source.splitIntoBanks()
    split.banks.forEach { (bank, part) ->
        lineage.registerWorkflow(bank, split.projects[bank], atEpochMillis = part.episodes.minOfOrNull { it.createdAtEpochMillis } ?: 0L)
        val store = banks.store(bank)
        // A first launch killed before the report was recorded leaves live banks behind; a re-run must add
        // only what is missing, never replace a bank with the older split and lose what was banked since.
        val existing = store.read()
        if (!existing.containsContent(part)) store.replace(existing.withMissingFrom(part))
    }
    split.banks.forEach { (bank, part) ->
        check(banks.store(bank).read().containsContent(part)) { "Memory bank of workflow $bank did not verify after the split" }
    }
    banks.migrationState.recordMigration(split.report)
    return split.report
}

private fun MemorySnapshot.containsContent(other: MemorySnapshot): Boolean =
    episodes.map { it.id }.toSet().containsAll(other.episodes.map { it.id }) &&
        sections.map { it.id }.toSet().containsAll(other.sections.map { it.id }) &&
        nodes.map { it.id }.toSet().containsAll(other.nodes.map { it.id }) &&
        edges.map { it.id }.toSet().containsAll(other.edges.map { it.id }) &&
        queue.toSet().containsAll(other.queue) &&
        declinedCondensations.toSet().containsAll(other.declinedCondensations)

private fun MemorySnapshot.withMissingFrom(other: MemorySnapshot): MemorySnapshot {
    val episodeIds = episodes.mapTo(hashSetOf()) { it.id }
    val sectionIds = sections.mapTo(hashSetOf()) { it.id }
    val nodeIds = nodes.mapTo(hashSetOf()) { it.id }
    val edgeIds = edges.mapTo(hashSetOf()) { it.id }
    return copy(
        episodes = episodes + other.episodes.filter { it.id !in episodeIds },
        sections = sections + other.sections.filter { it.id !in sectionIds },
        nodes = nodes + other.nodes.filter { it.id !in nodeIds },
        edges = edges + other.edges.filter { it.id !in edgeIds },
        queue = queue + other.queue.filter { it !in queue },
        declinedCondensations = declinedCondensations + other.declinedCondensations.filter { it !in declinedCondensations },
    )
}
