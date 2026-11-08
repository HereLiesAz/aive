package com.hereliesaz.geministrator.workflow.schedule

import com.russhwolf.settings.Settings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Where the keeper's schedules are kept, on this device. */
interface WorkflowScheduleStore {
    suspend fun all(): List<WorkflowSchedule>
    suspend fun put(schedule: WorkflowSchedule)
    suspend fun remove(id: String)
}

/** Schedules as one JSON list under a settings key; an unreadable list reads as empty rather than failing the app. */
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

    private fun read(): List<WorkflowSchedule> = settings.getStringOrNull(storageKey)
        ?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
        .orEmpty()
        .sortedWith(compareBy({ it.name.lowercase() }, { it.id }))

    private fun write(list: List<WorkflowSchedule>) {
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
}
