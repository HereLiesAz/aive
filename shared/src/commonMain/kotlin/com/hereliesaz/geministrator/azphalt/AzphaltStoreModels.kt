package com.hereliesaz.geministrator.azphalt

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

const val AZPHALT_STORE_URL: String = "https://azphalt.store"
const val HAIVE_AZPHALT_HOST_ID: String = "com.hereliesaz.haive"
const val HAIVE_WORKFLOW_FORMAT: String = "haive.workflow.v1"
const val HAIVE_ROLE_FORMAT: String = "haive.role.v1"
const val AZPHALT_PACKAGE_MEDIA_TYPE: String = "application/vnd.azphalt.package"
const val AZPHALT_PACKAGE_MEDIA_TYPE_DEPRECATED: String = "application/x-azphalt"

@Serializable
data class AzphaltRepositoryIndex(
    val name: String,
    val version: String,
    val description: String? = null,
    val auth: JsonElement? = null,
    val supportedTypes: List<String> = emptyList(),
    val profiles: List<String> = emptyList(),
    val signingKeys: List<AzphaltSigningKey> = emptyList(),
)

@Serializable
data class AzphaltSigningKey(
    val publicKey: String,
    val keyId: String? = null,
    val label: String? = null,
)

@Serializable
data class AzphaltPackageSearchResponse(
    val packages: List<AzphaltPackageSummary> = emptyList(),
    val total: Int = packages.size,
    val page: Int = 1,
    val pages: Int = 1,
)

@Serializable
data class AzphaltPackageSummary(
    val id: String,
    val name: String,
    val description: String? = null,
    val author: String? = null,
    val version: String,
    val latest: String = version,
    val kind: String,
    val types: List<String> = emptyList(),
    val priceStatus: String = "free",
    val targetApps: List<String> = emptyList(),
    val downloads: Long? = null,
    val installs: Long? = null,
    val uninstalls: Long? = null,
    val rating: Double? = null,
    val ratingCount: Long? = null,
    val updatedAt: String? = null,
    val byteSize: Long? = null,
    val mediaDomains: List<String> = emptyList(),
    val maturity: String? = null,
    val preview: AzphaltPreview? = null,
    val nameLocalized: Map<String, String> = emptyMap(),
    val descriptionLocalized: Map<String, String> = emptyMap(),
)

@Serializable
data class AzphaltPreview(
    val image: String? = null,
    val clip: String? = null,
)

@Serializable
data class AzphaltPackageDetail(
    val id: String,
    val name: String,
    val author: String? = null,
    val description: String? = null,
    val version: String,
    val latest: String? = null,
    val kind: String,
    val types: List<String> = emptyList(),
    val targetApps: List<String> = emptyList(),
    val priceStatus: String = "free",
    val manifest: AzphaltManifest? = null,
    val versions: List<AzphaltPackageVersion> = emptyList(),
    val nameLocalized: Map<String, String> = emptyMap(),
    val descriptionLocalized: Map<String, String> = emptyMap(),
)

@Serializable
data class AzphaltPackageVersion(
    val version: String,
    val publishedAt: String? = null,
    val size: Long? = null,
    val digest: String? = null,
    val yanked: Boolean = false,
)

/**
 * Manifest surface required by Haive's Azphalt host. Mutually-exclusive editor/executable blocks are
 * retained as JsonElement values solely so declarative workflow/role packages can be rejected when
 * any are present; Haive never interprets or executes those blocks.
 */
@Serializable
data class AzphaltManifest(
    val azphalt: String,
    val id: String,
    val name: String,
    val version: String,
    val kind: String,
    val license: String,
    val compat: String,
    val description: String? = null,
    val author: String? = null,
    val homepage: String? = null,
    val entry: JsonElement? = null,
    val runtime: JsonElement? = null,
    val capabilities: JsonElement? = null,
    val assets: JsonElement? = null,
    val contributes: JsonElement? = null,
    val app: JsonElement? = null,
    val mcp: JsonElement? = null,
    val pack: JsonElement? = null,
    val skill: JsonElement? = null,
    val script: JsonElement? = null,
    val composable: JsonElement? = null,
    val targetApps: List<String> = emptyList(),
    val visibility: String? = null,
    val maturity: String? = null,
    val files: Map<String, String> = emptyMap(),
    val workflow: AzphaltWorkflowManifest? = null,
    val role: AzphaltRoleManifest? = null,
)

@Serializable
data class AzphaltWorkflowManifest(
    val format: String,
    val definitions: List<AzphaltWorkflowPayloadEntry>,
    val fragments: List<AzphaltWorkflowPayloadEntry> = emptyList(),
    val agents: List<AzphaltWorkflowAgentEntry> = emptyList(),
    val dependencies: List<AzphaltWorkflowDependency> = emptyList(),
    val screens: List<AzphaltWorkflowScreenEntry> = emptyList(),
    val hostPermissions: List<String> = emptyList(),
)

@Serializable
data class AzphaltWorkflowPayloadEntry(
    val id: String,
    val name: String? = null,
    val description: String? = null,
    val path: String,
)

@Serializable
data class AzphaltWorkflowAgentEntry(
    val id: String,
    val name: String? = null,
    val description: String? = null,
    val path: String,
)

@Serializable
data class AzphaltRoleManifest(
    val format: String,
    val roles: List<AzphaltRolePayloadEntry>,
)

@Serializable
data class AzphaltRolePayloadEntry(
    val id: String,
    val name: String? = null,
    val description: String? = null,
    val path: String,
)

@Serializable
data class AzphaltWorkflowDependency(
    val id: String,
    val version: String? = null,
    val required: Boolean = false,
    val purpose: String? = null,
    val note: String? = null,
)

@Serializable
data class AzphaltWorkflowScreenEntry(
    val id: String,
    val name: String? = null,
    val path: String,
    val placements: List<String> = emptyList(),
)

@Serializable
data class AzphaltInstalledPackageRef(
    val id: String,
    val version: String,
)

@Serializable
data class AzphaltUpdateCheckResponse(
    val updates: List<AzphaltPackageUpdate> = emptyList(),
)

@Serializable
data class AzphaltPackageUpdate(
    val id: String,
    val current: String? = null,
    val latest: String? = null,
    val updateAvailable: Boolean? = null,
    val yanked: Boolean? = null,
)

@Serializable
data class AzphaltRevocationsResponse(
    val revocations: List<AzphaltRevocation> = emptyList(),
)

@Serializable
data class AzphaltRevocation(
    val id: String,
    val version: String,
    val reason: String? = null,
    val revokedAt: String? = null,
)

/** Host-agnostic web handoff defined by spec/web-handoff.md. */
data class AzphaltInstallLink(
    val packageId: String,
    val version: String? = null,
    val repositoryUrl: String = AZPHALT_STORE_URL,
) {
    companion object {
        private val packageIdPattern = Regex("^[A-Za-z0-9](?:[A-Za-z0-9._-]{0,253}[A-Za-z0-9])?$")
        private val versionPattern = Regex("^[A-Za-z0-9][A-Za-z0-9.+_-]{0,127}$")

        fun parse(raw: String): AzphaltInstallLink? {
            val prefix = "azphalt://install"
            if (!raw.startsWith(prefix, ignoreCase = true)) return null
            val suffix = raw.substring(prefix.length)
            if (suffix.isNotEmpty() && !suffix.startsWith("?")) return null
            val params = parseQuery(suffix.removePrefix("?"))
            val id = params["id"]?.trim()?.takeIf(String::isNotEmpty) ?: return null
            if (!packageIdPattern.matches(id)) return null
            val version = params["version"]?.trim()?.takeIf(String::isNotEmpty)
            if (version != null && !versionPattern.matches(version)) return null
            val repo = params["repo"]?.trim()?.takeIf(String::isNotEmpty) ?: AZPHALT_STORE_URL
            if (!repo.startsWith("https://", ignoreCase = true)) return null
            return AzphaltInstallLink(id, version, repo.trimEnd('/'))
        }

        private fun parseQuery(raw: String): Map<String, String> = buildMap {
            if (raw.isBlank()) return@buildMap
            raw.split('&').forEach { pair ->
                if (pair.isBlank()) return@forEach
                val separator = pair.indexOf('=')
                val key = if (separator >= 0) pair.substring(0, separator) else pair
                val value = if (separator >= 0) pair.substring(separator + 1) else ""
                put(percentDecode(key), percentDecode(value))
            }
        }

        private fun percentDecode(value: String): String {
            val out = StringBuilder(value.length)
            var index = 0
            while (index < value.length) {
                val char = value[index]
                when {
                    char == '%' && index + 2 < value.length -> {
                        val byte = value.substring(index + 1, index + 3).toIntOrNull(16)
                        if (byte == null) return value
                        out.append(byte.toChar())
                        index += 3
                    }
                    char == '+' -> {
                        out.append(' ')
                        index += 1
                    }
                    else -> {
                        out.append(char)
                        index += 1
                    }
                }
            }
            return out.toString()
        }
    }
}

sealed class AzphaltRepositoryException(message: String) : RuntimeException(message) {
    class Http(val statusCode: Int, message: String) : AzphaltRepositoryException(message)
    class AuthenticationRequired(message: String) : AzphaltRepositoryException(message)
    class PaymentRequired(message: String) : AzphaltRepositoryException(message)
}
