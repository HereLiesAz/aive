package com.hereliesaz.geministrator.addons

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

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

/**
 * Parses the manifest metadata from an Azphalt workflow package.
 *
 * This class deliberately does not claim to verify package authenticity. Cryptographic package
 * verification belongs at the package acquisition boundary; only metadata from a package that has
 * already passed that boundary may be handed to [parseManifest]. Keeping parsing and verification
 * separate prevents a non-empty JSON string from masquerading as a verified package.
 */
class AzphaltPackageParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseManifest(payload: String): AzphaltPackageManifest {
        require(payload.isNotBlank()) { "Manifest payload must not be blank." }
        return json.decodeFromString<AzphaltPackageManifest>(payload)
    }

    fun parseRequestedPermissions(requested: List<String>): Set<HostPermission> {
        val mapped = mutableSetOf<HostPermission>()
        for (req in requested) {
            try {
                mapped.add(HostPermission.valueOf(req))
            } catch (_: IllegalArgumentException) {
                // Unknown permissions are denied by omission.
            }
        }
        return mapped
    }

    fun selectGrantedPermissions(
        requested: Set<HostPermission>,
        approved: Set<HostPermission>,
    ): Set<HostPermission> = requested.intersect(approved)
}
