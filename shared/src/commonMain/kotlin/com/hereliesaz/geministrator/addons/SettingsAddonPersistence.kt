package com.hereliesaz.geministrator.addons

import com.russhwolf.settings.Settings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class AddonPersistenceCorruptionException(message: String, cause: Throwable) : IllegalStateException(message, cause)

class SettingsAddonPersistence(private val settings: Settings) : AddonPersistence {
    private val json = Json { ignoreUnknownKeys = true }
    private val installationsKey = "addon_installations"
    private val historyKey = "addon_tombstones"

    override fun getInstallations(): List<AddonInstallation> {
        val raw = settings.getStringOrNull(installationsKey) ?: return emptyList()
        return try {
            json.decodeFromString<List<AddonInstallation>>(raw)
        } catch (failure: Exception) {
            throw AddonPersistenceCorruptionException("Stored add-on installation data is corrupted.", failure)
        }
    }

    override fun saveInstallation(installation: AddonInstallation) {
        val current = getInstallations().filterNot { it.id == installation.id }.toMutableList()
        current.add(installation)
        settings.putString(installationsKey, json.encodeToString(current))
    }

    override fun removeInstallation(id: String) {
        val current = getInstallations()
        val toRemove = current.find { it.id == id } ?: return
        val remaining = current.filterNot { it.id == id }
        settings.putString(installationsKey, json.encodeToString(remaining))

        val historyRaw = settings.getStringOrNull(historyKey) ?: "[]"
        val history = try {
            json.decodeFromString<List<AddonInstallation>>(historyRaw).toMutableList()
        } catch (failure: Exception) {
            throw AddonPersistenceCorruptionException("Stored add-on tombstone data is corrupted.", failure)
        }
        history.removeAll { it.id == id && it.version == toRemove.version }
        history.add(toRemove.copy(enabled = false))
        settings.putString(historyKey, json.encodeToString(history))
    }

    companion object {
        fun createDefault(): SettingsAddonPersistence = SettingsAddonPersistence(Settings())
    }
}
