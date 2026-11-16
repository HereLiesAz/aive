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

/** Which banks exist. */
interface MemoryBankRegistry {
    suspend fun known(): Set<String>

    suspend fun add(workflowId: String)
}

class InMemoryMemoryBankRegistry(initial: Set<String> = emptySet()) : MemoryBankRegistry {
    private val mutex = Mutex()
    private val banks = initial.toMutableSet()

    override suspend fun known(): Set<String> = mutex.withLock { banks.toSet() }

    override suspend fun add(workflowId: String) {
        mutex.withLock { memoryBankKey(workflowId)?.let { banks += it } }
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

    private fun load(): Set<String> =
        settings.readQuarantining(key) { SettingsMemoryStore.defaultJson.decodeFromString(serializer, it) }
            .orEmpty()
            .mapNotNullTo(linkedSetOf(), ::memoryBankKey)
}
