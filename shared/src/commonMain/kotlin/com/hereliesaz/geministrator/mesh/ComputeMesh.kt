package com.hereliesaz.geministrator.mesh

import com.hereliesaz.geministrator.domain.ComputeAccelerator
import com.hereliesaz.geministrator.domain.ComputePlacementPolicy
import com.hereliesaz.geministrator.domain.ComputeRequirements
import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class ComputeDeviceId(val value: String) {
    init {
        require(value.isNotBlank()) { "Compute device ID must not be blank" }
    }
}

@Serializable
enum class ComputeDevicePlatform {
    Android,
    Linux,
    Windows,
    MacOS,
}

@Serializable
enum class ComputePermission {
    ModelInference,
    ProcessExecution,
    RepositoryRead,
    RepositoryWrite,
    NetworkAccess,
    ArtifactStorage,
}

@Serializable
data class ComputeDeviceLoad(
    val activeLeases: Int = 0,
    val maxConcurrentLeases: Int = 1,
    val cpuUtilization: Float? = null,
    val memoryAvailableBytes: Long? = null,
    val batteryFraction: Float? = null,
    val charging: Boolean? = null,
) {
    init {
        require(activeLeases >= 0)
        require(maxConcurrentLeases >= 1)
        require(cpuUtilization == null || cpuUtilization in 0f..1f)
        require(memoryAvailableBytes == null || memoryAvailableBytes >= 0)
        require(batteryFraction == null || batteryFraction in 0f..1f)
    }

    val saturation: Float
        get() = (activeLeases.toFloat() / maxConcurrentLeases.toFloat()).coerceIn(0f, 1f)
}

@Serializable
data class ComputeDeviceAdvertisement(
    val id: ComputeDeviceId,
    val displayName: String,
    val platform: ComputeDevicePlatform,
    val architecture: String,
    val logicalCores: Int,
    val memoryBytes: Long,
    val accelerators: Set<ComputeAccelerator> = setOf(ComputeAccelerator.Cpu),
    val capabilities: Set<String> = emptySet(),
    val modelIds: Set<String> = emptySet(),
    val weightEncodings: Set<String> = emptySet(),
    val grantedPermissions: Set<ComputePermission> = emptySet(),
    val load: ComputeDeviceLoad = ComputeDeviceLoad(),
    val online: Boolean = true,
    val lastSeenEpochMillis: Long,
) {
    init {
        require(displayName.isNotBlank())
        require(architecture.isNotBlank())
        require(logicalCores >= 1)
        require(memoryBytes >= 0)
    }

    fun satisfies(requirements: ComputeRequirements): Boolean {
        if (!online) return false
        if (load.activeLeases >= load.maxConcurrentLeases) return false
        if (logicalCores < requirements.minimumLogicalCores) return false
        val availableMemory = load.memoryAvailableBytes ?: memoryBytes
        if (availableMemory < requirements.minimumMemoryBytes) return false
        if (!accelerators.containsAll(requirements.requiredAccelerators)) return false
        if (!capabilities.containsAll(requirements.requiredCapabilities)) return false
        if (!modelIds.containsAll(requirements.requiredModelIds)) return false
        if (!weightEncodings.containsAll(requirements.requiredWeightEncodings)) return false
        if (requirements.requiresRepositoryRead && ComputePermission.RepositoryRead !in grantedPermissions) return false
        if (requirements.requiresRepositoryWrite && ComputePermission.RepositoryWrite !in grantedPermissions) return false
        if (requirements.requiresNetworkAccess && ComputePermission.NetworkAccess !in grantedPermissions) return false
        return true
    }
}

@Serializable
data class ComputeMeshGrant(
    val deviceId: ComputeDeviceId,
    val permissions: Set<ComputePermission>,
    val maxConcurrentLeases: Int = 1,
    val allowWhileOnBattery: Boolean = true,
    val minimumBatteryFraction: Float = 0.15f,
) {
    init {
        require(maxConcurrentLeases >= 1)
        require(minimumBatteryFraction in 0f..1f)
    }

    fun permits(device: ComputeDeviceAdvertisement): Boolean {
        val battery = device.load.batteryFraction
        if (!allowWhileOnBattery && device.load.charging == false) return false
        if (battery != null && device.load.charging != true && battery < minimumBatteryFraction) return false
        return true
    }
}

sealed interface ComputePlacementPlan {
    data object Local : ComputePlacementPlan

    data class Remote(
        val device: ComputeDeviceAdvertisement,
    ) : ComputePlacementPlan

    data class Distributed(
        val participants: List<ComputeParticipant>,
    ) : ComputePlacementPlan {
        init {
            require(participants.size >= 2)
            require(participants.map { it.sliceIndex }.distinct().size == participants.size)
            require(participants.all { it.sliceCount == participants.size })
        }
    }

    data class Blocked(val reason: String) : ComputePlacementPlan
}

data class ComputeParticipant(
    val device: ComputeDeviceAdvertisement?,
    val local: Boolean,
    val sliceIndex: Int,
    val sliceCount: Int,
) {
    init {
        require(local.xor(device != null)) { "Participant must be either local or remote" }
        require(sliceIndex in 0 until sliceCount)
    }
}

class ComputeMeshScheduler(
    private val localDevice: ComputeDeviceAdvertisement,
) {
    fun plan(
        policy: ComputePlacementPolicy,
        pairedDevices: Collection<ComputeDeviceAdvertisement>,
    ): ComputePlacementPlan = when (policy) {
        ComputePlacementPolicy.LocalOnly -> ComputePlacementPlan.Local
        is ComputePlacementPolicy.RemoteAllowed -> planRemoteAllowed(policy, pairedDevices)
        is ComputePlacementPolicy.PreferredDevice -> planPreferred(policy, pairedDevices)
        is ComputePlacementPolicy.Distributed -> planDistributed(policy, pairedDevices)
    }

    private fun planRemoteAllowed(
        policy: ComputePlacementPolicy.RemoteAllowed,
        peers: Collection<ComputeDeviceAdvertisement>,
    ): ComputePlacementPlan {
        val remote = eligible(peers, policy.requirements).firstOrNull()
        if (remote != null && shouldOffload(remote)) {
            return ComputePlacementPlan.Remote(remote)
        }
        if (localDevice.satisfies(policy.requirements) && policy.allowLocalFallback) {
            return ComputePlacementPlan.Local
        }
        if (remote != null) {
            return ComputePlacementPlan.Remote(remote)
        }
        return ComputePlacementPlan.Blocked("No paired device satisfies the task compute requirements")
    }

    private fun planPreferred(
        policy: ComputePlacementPolicy.PreferredDevice,
        peers: Collection<ComputeDeviceAdvertisement>,
    ): ComputePlacementPlan {
        val preferred = peers.firstOrNull {
            it.id.value == policy.deviceId && it.satisfies(policy.requirements)
        }
        if (preferred != null) return ComputePlacementPlan.Remote(preferred)

        if (policy.allowOtherPeers) {
            eligible(peers, policy.requirements).firstOrNull()?.let {
                return ComputePlacementPlan.Remote(it)
            }
        }
        if (policy.allowLocalFallback && localDevice.satisfies(policy.requirements)) {
            return ComputePlacementPlan.Local
        }
        return ComputePlacementPlan.Blocked("Preferred compute device is unavailable")
    }

    private fun planDistributed(
        policy: ComputePlacementPolicy.Distributed,
        peers: Collection<ComputeDeviceAdvertisement>,
    ): ComputePlacementPlan {
        val candidates = eligible(peers, policy.requirements)
            .take(policy.maxDevices)
            .toMutableList()
        val includeLocal = policy.allowLocalParticipant &&
            localDevice.satisfies(policy.requirements) &&
            candidates.size < policy.maxDevices

        val count = candidates.size + if (includeLocal) 1 else 0
        if (count >= policy.minimumDevices) {
            val participants = buildList {
                candidates.forEachIndexed { index, device ->
                    add(
                        ComputeParticipant(
                            device = device,
                            local = false,
                            sliceIndex = index,
                            sliceCount = count,
                        ),
                    )
                }
                if (includeLocal) {
                    add(
                        ComputeParticipant(
                            device = null,
                            local = true,
                            sliceIndex = size,
                            sliceCount = count,
                        ),
                    )
                }
            }
            return ComputePlacementPlan.Distributed(participants)
        }

        if (policy.allowSingleDeviceFallback) {
            candidates.firstOrNull()?.let { return ComputePlacementPlan.Remote(it) }
            if (localDevice.satisfies(policy.requirements)) return ComputePlacementPlan.Local
        }
        return ComputePlacementPlan.Blocked(
            "Distributed task requires ${policy.minimumDevices} eligible devices; only $count are available",
        )
    }

    private fun eligible(
        peers: Collection<ComputeDeviceAdvertisement>,
        requirements: ComputeRequirements,
    ): List<ComputeDeviceAdvertisement> =
        peers.filter { it.satisfies(requirements) }
            .sortedWith(
                compareBy<ComputeDeviceAdvertisement> { it.load.saturation }
                    .thenByDescending { it.load.memoryAvailableBytes ?: it.memoryBytes }
                    .thenByDescending { it.logicalCores }
                    .thenBy { it.id.value },
            )

    private fun shouldOffload(remote: ComputeDeviceAdvertisement): Boolean {
        val localSaturation = localDevice.load.saturation
        val remoteSaturation = remote.load.saturation
        if (localDevice.load.activeLeases >= localDevice.load.maxConcurrentLeases) return true
        if (remoteSaturation + 0.20f < localSaturation) return true
        val localMemory = localDevice.load.memoryAvailableBytes ?: localDevice.memoryBytes
        val remoteMemory = remote.load.memoryAvailableBytes ?: remote.memoryBytes
        return remoteMemory > localMemory * 2
    }
}
