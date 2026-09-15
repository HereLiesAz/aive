package com.hereliesaz.geministrator.addons

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

@Serializable
data class AzphaltPackageManifest(
    val id: String,
    val version: String,
    val name: String,
    val targetApps: List<String>,
    val workflow: AzphaltWorkflowBlock? = null
)

@Serializable
data class AzphaltWorkflowBlock(
    val format: String,
    val definitions: List<AzphaltFileEntry> = emptyList(),
    val fragments: List<AzphaltFileEntry> = emptyList(),
    val agents: List<AzphaltFileEntry> = emptyList(),
    val dependencies: List<AzphaltDependencyEntry> = emptyList(),
    val hostPermissions: List<String> = emptyList(),
    val screens: List<AzphaltFileEntry> = emptyList()
)

@Serializable
data class AzphaltFileEntry(
    val path: String,
    val id: String? = null,
    val name: String? = null
)

@Serializable
data class AzphaltDependencyEntry(
    val id: String,
    val version: String
)

class AzphaltPackageParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(payload: String): AzphaltPackageManifest {
        // Enforce verified payload by simulating verification (stub for architecture)
        require(payload.isNotBlank()) { "Payload must be a verified package payload." }
        return json.decodeFromString<AzphaltPackageManifest>(payload)
    }

    fun mapRequestedPermissions(requested: List<String>): Set<HostPermission> {
        val mapped = mutableSetOf<HostPermission>()
        for (req in requested) {
            try {
                mapped.add(HostPermission.valueOf(req))
            } catch (e: IllegalArgumentException) {
                // Deny unknown strings (inert)
            }
        }
        return mapped
    }
}
