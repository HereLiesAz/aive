package com.hereliesaz.geministrator

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The read-only lists behind `aive sources` and `aive agents` (and their MCP tools), shared by the desktop app and `aive serve`
 * so both answer the same way. Nothing here carries a key, and nothing here changes anything: switching a Source or a CLI on
 * or off is a local-only command of the `aive` process itself, never a control route.
 */
object ControlViews {
    /** Every Source the app lists: its id, name, whether it needs a key, and whether the keeper has it switched on. */
    fun sources(): JsonObject = buildJsonObject {
        put(
            "sources",
            JsonArray(
                ProviderCatalog.entries.map { entry ->
                    buildJsonObject {
                        put("id", JsonPrimitive(entry.id))
                        put("name", JsonPrimitive(entry.displayName))
                        put("needsKey", JsonPrimitive(!entry.keyOptional))
                        put("enabled", JsonPrimitive(ProviderEnablement.isEnabled(entry.id)))
                    }
                },
            ),
        )
    }

    /** Every vendor CLI the app can drive: whether it is installed on this computer, whether it is switched on, and its version. */
    suspend fun agents(): JsonObject = buildJsonObject {
        put(
            "agents",
            JsonArray(
                DesktopCliAgents().statuses().map { status ->
                    buildJsonObject {
                        put("id", JsonPrimitive(status.spec.id))
                        put("name", JsonPrimitive(status.spec.displayName))
                        put("installed", JsonPrimitive(status.path != null))
                        put("enabled", JsonPrimitive(status.enabled))
                        status.version?.let { put("version", JsonPrimitive(it)) }
                    }
                },
            ),
        )
    }
}
