package com.hereliesaz.geministrator.domain

import kotlinx.serialization.Serializable

/**
 * Where a task is allowed to execute.
 *
 * Placement is deliberately separate from [TaskExecutor]: executor describes what performs the
 * work, while placement describes which trusted Haive device may host that execution.
 */
@Serializable
sealed interface ComputePlacementPolicy {
    /** Never export this task from the originating device. */
    @Serializable
    data object LocalOnly : ComputePlacementPolicy

    /**
     * Prefer the local device, but allow a trusted paired device when the mesh scheduler decides it
     * is materially better or the local device cannot satisfy [requirements].
     */
    @Serializable
    data class RemoteAllowed(
        val requirements: ComputeRequirements = ComputeRequirements(),
        val allowLocalFallback: Boolean = true,
    ) : ComputePlacementPolicy

    /** Prefer one paired device, optionally falling back to any other eligible peer or local work. */
    @Serializable
    data class PreferredDevice(
        val deviceId: String,
        val requirements: ComputeRequirements = ComputeRequirements(),
        val allowOtherPeers: Boolean = true,
        val allowLocalFallback: Boolean = true,
    ) : ComputePlacementPolicy {
        init {
            require(deviceId.isNotBlank()) { "Preferred compute device ID must not be blank" }
        }
    }

    /**
     * Partition a shardable workload across multiple paired devices. The workflow task remains one
     * authoritative task run; the mesh coordinator owns slice fan-out/fan-in.
     */
    @Serializable
    data class Distributed(
        val requirements: ComputeRequirements = ComputeRequirements(),
        val maxDevices: Int = 4,
        val minimumDevices: Int = 2,
        val allowLocalParticipant: Boolean = true,
        val allowSingleDeviceFallback: Boolean = true,
    ) : ComputePlacementPolicy {
        init {
            require(maxDevices >= 2) { "Distributed placement requires at least two possible devices" }
            require(minimumDevices in 2..maxDevices) {
                "minimumDevices must be between 2 and maxDevices"
            }
        }
    }
}

@Serializable
data class ComputeRequirements(
    val minimumLogicalCores: Int = 1,
    val minimumMemoryBytes: Long = 0,
    val requiredAccelerators: Set<ComputeAccelerator> = emptySet(),
    val requiredCapabilities: Set<String> = emptySet(),
    val requiredModelIds: Set<String> = emptySet(),
    val requiredWeightEncodings: Set<String> = emptySet(),
    val requiresRepositoryRead: Boolean = false,
    val requiresRepositoryWrite: Boolean = false,
    val requiresNetworkAccess: Boolean = false,
) {
    init {
        require(minimumLogicalCores >= 1) { "minimumLogicalCores must be at least one" }
        require(minimumMemoryBytes >= 0) { "minimumMemoryBytes must not be negative" }
        require(requiredCapabilities.none(String::isBlank)) { "requiredCapabilities must not contain blanks" }
        require(requiredModelIds.none(String::isBlank)) { "requiredModelIds must not contain blanks" }
        require(requiredWeightEncodings.none(String::isBlank)) {
            "requiredWeightEncodings must not contain blanks"
        }
    }
}

@Serializable
enum class ComputeAccelerator {
    Cpu,
    Gpu,
    Npu,
}
