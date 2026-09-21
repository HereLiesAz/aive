package com.hereliesaz.geministrator.addons

import com.russhwolf.settings.Settings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AddonPersistenceCorruptionException(message: String, cause: Throwable) : IllegalStateException(message, cause)

@Serializable
private data class AddonPersistenceSnapshot(
    val installations: List<AddonInstallation> = emptyList(),
    val history: List<AddonInstallation> = emptyList(),
)

private val settingsAddonPersistenceMutex = Mutex()

class SettingsAddonPersistence(private val settings: Settings) : AddonPersistence {
    private val json = Json { ignoreUnknownKeys = true }
    private val snapshotKey = "addon_state"

    private fun readSnapshot(): AddonPersistenceSnapshot {
        val raw = settings.getStringOrNull(snapshotKey) ?: return AddonPersistenceSnapshot()
        return try {
            json.decodeFromString<AddonPersistenceSnapshot>(raw)
        } catch (failure: Exception) {
            throw AddonPersistenceCorruptionException("Stored add-on data is corrupted.", failure)
        }
    }

    private fun writeSnapshot(snapshot: AddonPersistenceSnapshot) {
        settings.putString(snapshotKey, json.encodeToString(snapshot))
    }

    override suspend fun getInstallations(): List<AddonInstallation> =
        settingsAddonPersistenceMutex.withLock { readSnapshot().installations }

    override suspend fun saveInstallation(installation: AddonInstallation) {
        settingsAddonPersistenceMutex.withLock {
            val snapshot = readSnapshot()
            val updated = snapshot.installations.filterNot { it.id == installation.id }.toMutableList()
            updated.add(installation)
            writeSnapshot(snapshot.copy(installations = updated))
        }
    }

    override suspend fun removeInstallation(id: String) {
        settingsAddonPersistenceMutex.withLock {
            val snapshot = readSnapshot()
            val toRemove = snapshot.installations.find { it.id == id } ?: return@withLock
            val remaining = snapshot.installations.filterNot { it.id == id }
            val history = snapshot.history.toMutableList()
            history.removeAll { it.id == id && it.version == toRemove.version }
            history.add(toRemove.copy(enabled = false))
            writeSnapshot(snapshot.copy(installations = remaining, history = history))
        }
    }

    companion object {
        fun createDefault(): SettingsAddonPersistence = SettingsAddonPersistence(Settings())
    }
}
