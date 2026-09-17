package com.hereliesaz.geministrator.azphalt

import com.hereliesaz.geministrator.domain.WorkflowDefinitionId
import com.russhwolf.settings.Settings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface AuthoredWorkflowStore {
    suspend fun all(): Set<WorkflowDefinitionId>
    suspend fun add(id: WorkflowDefinitionId)
    suspend fun remove(id: WorkflowDefinitionId)
}

class SettingsAuthoredWorkflowStore(
    private val settings: Settings = Settings(),
    private val storageKey: String = DEFAULT_STORAGE_KEY,
) : AuthoredWorkflowStore {
    private val mutex = Mutex()

    override suspend fun all(): Set<WorkflowDefinitionId> = mutex.withLock {
        settings.getStringOrNull(storageKey)
            ?.lineSequence()
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            ?.map(::WorkflowDefinitionId)
            ?.toCollection(linkedSetOf())
            .orEmpty()
    }

    override suspend fun add(id: WorkflowDefinitionId) {
        mutex.withLock {
            val ids = readUnlocked().toMutableSet()
            ids += id.value
            writeUnlocked(ids)
        }
    }

    override suspend fun remove(id: WorkflowDefinitionId) {
        mutex.withLock {
            val ids = readUnlocked().toMutableSet()
            ids -= id.value
            writeUnlocked(ids)
        }
    }

    private fun readUnlocked(): Set<String> = settings.getStringOrNull(storageKey)
        ?.lineSequence()
        ?.map(String::trim)
        ?.filter(String::isNotBlank)
        ?.toCollection(linkedSetOf())
        .orEmpty()

    private fun writeUnlocked(ids: Set<String>) {
        if (ids.isEmpty()) {
            settings.remove(storageKey)
        } else {
            settings.putString(storageKey, ids.sorted().joinToString("\n"))
        }
    }

    companion object {
        const val DEFAULT_STORAGE_KEY: String = "haive.workflows.authored.v1"
    }
}
