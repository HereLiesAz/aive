package com.hereliesaz.geministrator.addons

import com.russhwolf.settings.Settings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SettingsAddonPersistence(private val settings: Settings) : AddonPersistence {
    private val json = Json { ignoreUnknownKeys = true }
    private val INSTALLATIONS_KEY = "addon_installations"
    private val HISTORY_KEY = "addon_tombstones"

    override fun getInstallations(): List<AddonInstallation> {
        val raw = settings.getStringOrNull(INSTALLATIONS_KEY) ?: return emptyList()
        return try {
            json.decodeFromString<List<AddonInstallation>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun saveInstallation(installation: AddonInstallation) {
        val current = getInstallations().filterNot { it.id == installation.id }.toMutableList()
        current.add(installation)
        settings.putString(INSTALLATIONS_KEY, json.encodeToString(current))
    }

    override fun removeInstallation(id: String) {
        val current = getInstallations()
        val toRemove = current.find { it.id == id }
        if (toRemove != null) {
            val remaining = current.filterNot { it.id == id }
            settings.putString(INSTALLATIONS_KEY, json.encodeToString(remaining))

            // Tombstone for old runs
            val historyRaw = settings.getStringOrNull(HISTORY_KEY) ?: "[]"
            val history = json.decodeFromString<List<AddonInstallation>>(historyRaw).toMutableList()
            history.add(toRemove.copy(enabled = false))
            settings.putString(HISTORY_KEY, json.encodeToString(history))
        }
    }
}
