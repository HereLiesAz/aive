package com.hereliesaz.geministrator.distributed

import com.russhwolf.settings.Settings
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class DistributedComputeConfiguration(
    val relayUrl: String = "",
    val poolId: String = "",
    val nodeId: String = "",
    val displayName: String = "",
    val sharingEnabled: Boolean = false,
    val maxParallelLeases: Int = 1,
    val allowMeteredNetwork: Boolean = false,
    val requireExternalPower: Boolean = false,
) {
    init {
        require(maxParallelLeases >= 1) { "maxParallelLeases must be at least 1" }
    }

    val configured: Boolean
        get() = relayUrl.isNotBlank() &&
            poolId.isNotBlank() &&
            nodeId.isNotBlank() &&
            displayName.isNotBlank()
}

interface DistributedComputeConfigurationStore {
    fun read(): DistributedComputeConfiguration
    fun write(configuration: DistributedComputeConfiguration)
    fun clear()
}

class SettingsDistributedComputeConfigurationStore(
    private val settings: Settings = Settings(),
    private val storageKey: String = DEFAULT_STORAGE_KEY,
    private val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    },
) : DistributedComputeConfigurationStore {
    override fun read(): DistributedComputeConfiguration {
        val encoded = settings.getStringOrNull(storageKey) ?: return DistributedComputeConfiguration()
        return runCatching {
            json.decodeFromString<DistributedComputeConfiguration>(encoded)
        }.getOrDefault(DistributedComputeConfiguration())
    }

    override fun write(configuration: DistributedComputeConfiguration) {
        settings.putString(
            storageKey,
            json.encodeToString(DistributedComputeConfiguration.serializer(), configuration),
        )
    }

    override fun clear() {
        settings.remove(storageKey)
    }

    companion object {
        const val DEFAULT_STORAGE_KEY: String = "haive.distributed-compute.configuration.v1"
    }
}

data class DistributedComputeUiState(
    val configuration: DistributedComputeConfiguration = DistributedComputeConfiguration(),
    val tokenConfigured: Boolean = false,
    val connected: Boolean = false,
    val onlineNodes: List<ComputeNodeDescriptor> = emptyList(),
    val lastError: String? = null,
    /** Every node enrolled with the pool, as the relay reports them; the keeper can revoke any but this one. */
    val enrolledNodes: List<EnrolledComputeNode> = emptyList(),
    /** This device's own identity was revoked by another pool member. */
    val revoked: Boolean = false,
) {
    val ready: Boolean
        get() = configuration.configured && tokenConfigured
}
