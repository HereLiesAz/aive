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
    val format: String,
    val definitions: List<JsonObject> = emptyList(),
    val fragments: List<JsonObject> = emptyList(),
    val agents: List<JsonObject> = emptyList(),
    val dependencies: List<JsonObject> = emptyList(),
    val hostPermissions: List<String> = emptyList(),
    val screens: List<AddonScreen> = emptyList()
)

class AzphaltPackageParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(payload: String): AzphaltPackageManifest {
        val manifest = json.decodeFromString<AzphaltPackageManifest>(payload)
        return manifest
    }

    fun parseRequestedPermissions(requested: List<String>): Set<HostPermission> {
        val mapped = mutableSetOf<HostPermission>()
        for (req in requested) {
            try {
                mapped.add(HostPermission.valueOf(req))
            } catch (e: IllegalArgumentException) {
                // Deny unknown permissions
            }
        }
        return mapped
    }

    fun selectGrantedPermissions(requested: Set<HostPermission>, approved: Set<HostPermission>): Set<HostPermission> {
        return requested.intersect(approved)
    }
}
