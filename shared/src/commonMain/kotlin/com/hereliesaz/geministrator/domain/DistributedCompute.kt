package com.hereliesaz.geministrator.domain

import kotlinx.serialization.Serializable

@Serializable
enum class ComputeAccelerator {
    Cpu,
    Gpu,
    Npu,
}

@Serializable
enum class ComputePlatform {
    Android,
    Desktop,
    Web,
    Other,
}

/**
 * Placement requirements for work that may execute on another Haive node.
 *
 * These are eligibility constraints, not scheduling promises. A worker may advertise additional
 * dynamic limits (battery/network/thermal policy) and simply decline work it does not want.
 */
@Serializable
data class DistributedComputeRequirements(
    val requiredCapabilities: Set<String> = emptySet(),
    val preferredCapabilities: Set<String> = emptySet(),
    val requiredAccelerators: Set<ComputeAccelerator> = emptySet(),
    val minLogicalProcessors: Int = 1,
    val minMemoryMiB: Long = 0,
    val requiredModelIds: Set<String> = emptySet(),
    val preferredNodeIds: Set<String> = emptySet(),
) {
    init {
        require(minLogicalProcessors >= 1) { "minLogicalProcessors must be at least 1" }
        require(minMemoryMiB >= 0) { "minMemoryMiB must not be negative" }
        require(requiredCapabilities.none(String::isBlank)) { "requiredCapabilities must not contain blanks" }
        require(preferredCapabilities.none(String::isBlank)) { "preferredCapabilities must not contain blanks" }
        require(requiredModelIds.none(String::isBlank)) { "requiredModelIds must not contain blanks" }
        require(preferredNodeIds.none(String::isBlank)) { "preferredNodeIds must not contain blanks" }
    }
}
