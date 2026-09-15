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
        modelId: String? = null,
    ): MemoryComputeSelection = rank(devices, requirements, preference, modelId).firstOrNull()
        ?: error("No compatible local compute device is available")

    /**
     * Returns the complete attempt order for a model. Accelerators are ordered by workload and
     * preference; CPU is appended only as the explicit final fallback (or is the sole CPU_ONLY
     * choice). Runtimes should attempt these selections in order and cache the first session that
     * actually accepts the model.
     */
    fun rank(
        devices: List<MemoryComputeDevice>,
        requirements: MemoryModelRequirements,
        preference: MemoryComputePreference = MemoryComputePreference.AUTO,
        modelId: String? = null,
    ): List<MemoryComputeSelection> {
        val available = devices
            .asSequence()
            .filter(MemoryComputeDevice::available)
            .filter { device ->
                modelId == null || device.supportedModels.isEmpty() || modelId in device.supportedModels
            }
            .toList()

        val cpu = available
            .filter { it.deviceType == MemoryComputeDeviceType.CPU }
            .sortedByDescending { score(it, requirements, preference) }

        if (preference == MemoryComputePreference.CPU_ONLY) {
            return cpu.map { device ->
                MemoryComputeSelection(preference, device, cpuFallbackEnabled = false)
            }
        }

        val accelerators = available
            .asSequence()
            .filter { it.deviceType != MemoryComputeDeviceType.CPU }
            .filter { it.deviceType in requirements.allowedDeviceTypes }
            .filter { device ->
                val minimum = requirements.minimumDedicatedMemoryBytes
                minimum == null || (device.dedicatedMemory != null && device.dedicatedMemory >= minimum)
            }
            .sortedByDescending { score(it, requirements, preference) }
            .map { device ->
                MemoryComputeSelection(
                    preference = preference,
                    device = device,
                    cpuFallbackEnabled = requirements.allowCpuFallback && cpu.isNotEmpty(),
                )
            }
            .toList()

        val cpuFallback = if (requirements.allowCpuFallback) {
            cpu.map { device -> MemoryComputeSelection(preference, device, cpuFallbackEnabled = false) }
        } else {
            emptyList()
        }
        return accelerators + cpuFallback
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
        val backendIndex = requirements.preferredBackends.indexOfFirst { preferenceHint ->
            device.backend.contains(preferenceHint, ignoreCase = true)
        }
        val backendBonus = if (backendIndex >= 0) 100 - backendIndex.coerceAtMost(99) else 0
        return deviceScore + backendBonus
    }
}
