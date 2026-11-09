package com.hereliesaz.geministrator.workflow.schedule

import com.russhwolf.settings.Settings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Thrown by a write to a store whose saved list could not be read: nothing is written until the keeper discards it. */
class ScheduleListUnreadableException : IllegalStateException(
    "The saved schedules could not be read, so nothing is written over them. Open Schedules to keep or discard them.",
)

/** Where the keeper's schedules are kept, on this device. */
interface WorkflowScheduleStore {
    suspend fun all(): List<WorkflowSchedule>
    suspend fun put(schedule: WorkflowSchedule)
    suspend fun remove(id: String)

    /**
     * Changes one schedule as one step: [change] gets the schedule as it is stored now, not as the caller saw it earlier, and
     * returns the new one, or null to leave it as it is. A schedule that is gone is not brought back. Returns what was written,
     * or null when nothing was.
     */
    suspend fun update(id: String, change: (WorkflowSchedule) -> WorkflowSchedule?): WorkflowSchedule?

    /** True when a saved list exists but cannot be read: [all] is then empty, and every write throws [ScheduleListUnreadableException]. */
    suspend fun unreadable(): Boolean = false

    /** The keeper's decision to start over: the unreadable list stays under its `.corrupt` copy and writes work again. */
    suspend fun discardUnreadable() {}
}

/**
 * Schedules as one JSON list under a settings key. An unreadable list reads as empty rather than failing the app, but it is not
 * overwritten: its raw text is kept under `<key>.corrupt`, and every write throws [ScheduleListUnreadableException] until the
 * keeper calls [discardUnreadable]. Reads and writes are serialised within this process; two app instances sharing the same
 * storage are not serialised against each other (the settings API has no atomic update), which the ticker narrows by claiming a start
 * in one short read-modify-write before it launches anything.
 */
class SettingsWorkflowScheduleStore(
    private val settings: Settings = Settings(),
    private val storageKey: String = DEFAULT_KEY,
) : WorkflowScheduleStore {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(WorkflowSchedule.serializer())

    override suspend fun all(): List<WorkflowSchedule> = mutex.withLock { read() }

    override suspend fun put(schedule: WorkflowSchedule) = mutex.withLock {
        write(read().filterNot { it.id == schedule.id } + schedule)
    }

    override suspend fun remove(id: String) = mutex.withLock { write(read().filterNot { it.id == id }) }

    override suspend fun update(id: String, change: (WorkflowSchedule) -> WorkflowSchedule?): WorkflowSchedule? = mutex.withLock {
        val list = read()
        val current = list.firstOrNull { it.id == id } ?: return@withLock null
        val changed = change(current) ?: return@withLock null
        write(list.map { if (it.id == id) changed else it })
        changed
    }

    override suspend fun unreadable(): Boolean = mutex.withLock { decoded() == null }

    override suspend fun discardUnreadable() = mutex.withLock {
        val raw = settings.getStringOrNull(storageKey)
        if (raw != null && decoded() == null) {
            settings.putString(corruptKey, raw)
            settings.remove(storageKey)
        }
    }

    private val corruptKey get() = "$storageKey.corrupt"

    /** The stored list, empty when there is none, or null when there is one that cannot be read. */
    private fun decoded(): List<WorkflowSchedule>? {
        val raw = settings.getStringOrNull(storageKey) ?: return emptyList()
        return runCatching { json.decodeFromString(serializer, raw) }.getOrNull()
    }

    private fun read(): List<WorkflowSchedule> {
        val list = decoded()
        if (list == null) {
            // Keep what could not be read before anything can write over it.
            settings.getStringOrNull(storageKey)?.let { raw -> if (settings.getStringOrNull(corruptKey) != raw) settings.putString(corruptKey, raw) }
            return emptyList()
        }
        return list.sortedWith(compareBy({ it.name.lowercase() }, { it.id }))
    }

    private fun write(list: List<WorkflowSchedule>) {
        if (decoded() == null) throw ScheduleListUnreadableException()
        if (list.isEmpty()) settings.remove(storageKey) else settings.putString(storageKey, json.encodeToString(serializer, list))
    }

    companion object {
        const val DEFAULT_KEY = "aive.workflow-schedules.v1"
    }
}

/** For tests and for hosts with nowhere to keep them. */
class InMemoryWorkflowScheduleStore : WorkflowScheduleStore {
    private val items = LinkedHashMap<String, WorkflowSchedule>()
    override suspend fun all() = items.values.sortedWith(compareBy({ it.name.lowercase() }, { it.id }))
    override suspend fun put(schedule: WorkflowSchedule) { items[schedule.id] = schedule }
    override suspend fun remove(id: String) { items.remove(id) }
    override suspend fun update(id: String, change: (WorkflowSchedule) -> WorkflowSchedule?): WorkflowSchedule? {
        val current = items[id] ?: return null
        val changed = change(current) ?: return null
        items[id] = changed
        return changed
    }
}
