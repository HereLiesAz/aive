package com.hereliesaz.geministrator.memory

enum class MemoryComputePreference {
    AUTO,
    HIGH_PERFORMANCE,
    LOW_POWER,
    CPU_ONLY,
}

enum class MemoryComputeDeviceType {
    CPU,
    GPU,
    NPU,
}

enum class MemoryInferenceWorkload {
    Embedding,
    AutoregressiveGeneration,
}

data class MemoryModelRequirements(
    val workload: MemoryInferenceWorkload,
    val allowedDeviceTypes: Set<MemoryComputeDeviceType> = MemoryComputeDeviceType.entries.toSet(),
    val preferredBackends: List<String> = emptyList(),
    val minimumDedicatedMemoryBytes: Long? = null,
    val allowCpuFallback: Boolean = true,
) {
    init {
        require(allowedDeviceTypes.isNotEmpty())
        require(minimumDedicatedMemoryBytes == null || minimumDedicatedMemoryBytes >= 0)
    }

    companion object {
        fun embeddings(): MemoryModelRequirements = MemoryModelRequirements(
            workload = MemoryInferenceWorkload.Embedding,
        )

        fun generation(): MemoryModelRequirements = MemoryModelRequirements(
            workload = MemoryInferenceWorkload.AutoregressiveGeneration,
        )
    }
}

data class MemoryComputeDevice(
    val backend: String,
    val deviceName: String,
    val deviceType: MemoryComputeDeviceType,
    val deviceId: Int? = null,
    val dedicatedMemory: Long? = null,
    val sharedMemory: Long? = null,
    val available: Boolean = true,
    val supportedModels: Set<String> = emptySet(),
    val metadata: Map<String, String> = emptyMap(),
)

data class MemoryComputeSelection(
    val preference: MemoryComputePreference,
    val device: MemoryComputeDevice,
    val cpuFallbackEnabled: Boolean,
)

enum class MemoryExecutionVerification {
    Unverified,
    SessionConfigured,
    ProfiledRun,
}

data class MemoryExecutionReport(
    val selection: MemoryComputeSelection,
    val actualDevices: List<MemoryComputeDevice> = emptyList(),
    val cpuFallbackObserved: Boolean? = null,
    val acceleratedNodeFraction: Float? = null,
    val verification: MemoryExecutionVerification = MemoryExecutionVerification.Unverified,
) {
    val isHardwareAccelerated: Boolean
        get() = actualDevices.any { it.deviceType != MemoryComputeDeviceType.CPU }

    init {
        require(acceleratedNodeFraction == null || acceleratedNodeFraction in 0f..1f)
    }
}

interface HardwareCapabilityDetector {
    suspend fun discover(): List<MemoryComputeDevice>
}

object MemoryComputeSelector {
    fun select(
        devices: List<MemoryComputeDevice>,
        requirements: MemoryModelRequirements,
        preference: MemoryComputePreference = MemoryComputePreference.AUTO,
    ): MemoryComputeSelection {
        val compatible = devices
            .asSequence()
            .filter(MemoryComputeDevice::available)
            .filter { it.deviceType in requirements.allowedDeviceTypes }
            .filter { device ->
                val minimum = requirements.minimumDedicatedMemoryBytes
                minimum == null || device.deviceType == MemoryComputeDeviceType.CPU ||
                    (device.dedicatedMemory != null && device.dedicatedMemory >= minimum)
            }
            .toList()

        val candidates = if (preference == MemoryComputePreference.CPU_ONLY) {
            compatible.filter { it.deviceType == MemoryComputeDeviceType.CPU }
        } else {
            compatible
        }

        val selected = candidates.maxByOrNull { device ->
            score(device, requirements, preference)
        } ?: if (requirements.allowCpuFallback) {
            compatible.firstOrNull { it.deviceType == MemoryComputeDeviceType.CPU }
        } else {
            null
        } ?: error("No compatible local compute device is available")

        if (selected.deviceType == MemoryComputeDeviceType.CPU && !requirements.allowCpuFallback &&
            preference != MemoryComputePreference.CPU_ONLY
        ) {
            error("No compatible accelerator is available and CPU fallback is disabled")
        }

        return MemoryComputeSelection(
            preference = preference,
            device = selected,
            cpuFallbackEnabled = requirements.allowCpuFallback && selected.deviceType != MemoryComputeDeviceType.CPU,
        )
    }

    private fun score(
        device: MemoryComputeDevice,
        requirements: MemoryModelRequirements,
        preference: MemoryComputePreference,
    ): Int {
        val deviceScore = when (preference) {
            MemoryComputePreference.CPU_ONLY -> if (device.deviceType == MemoryComputeDeviceType.CPU) 10_000 else -10_000
            MemoryComputePreference.LOW_POWER -> when (device.deviceType) {
                MemoryComputeDeviceType.NPU -> 500
                MemoryComputeDeviceType.CPU -> 350
                MemoryComputeDeviceType.GPU -> if (device.metadata["integrated"] == "true") 400 else 250
            }
            MemoryComputePreference.HIGH_PERFORMANCE -> when (device.deviceType) {
                MemoryComputeDeviceType.GPU -> 600
                MemoryComputeDeviceType.NPU -> 550
                MemoryComputeDeviceType.CPU -> 100
            }
            MemoryComputePreference.AUTO -> when (requirements.workload) {
                MemoryInferenceWorkload.Embedding -> when (device.deviceType) {
                    MemoryComputeDeviceType.NPU -> 600
                    MemoryComputeDeviceType.GPU -> 500
                    MemoryComputeDeviceType.CPU -> 100
                }
                MemoryInferenceWorkload.AutoregressiveGeneration -> when (device.deviceType) {
                    MemoryComputeDeviceType.GPU -> 600
                    MemoryComputeDeviceType.NPU -> 500
                    MemoryComputeDeviceType.CPU -> 100
                }
            }
        }
        val backendIndex = requirements.preferredBackends.indexOfFirst {
            it.equals(device.backend, ignoreCase = true)
        }
        val backendBonus = if (backendIndex >= 0) 100 - backendIndex.coerceAtMost(99) else 0
        return deviceScore + backendBonus
    }
}
