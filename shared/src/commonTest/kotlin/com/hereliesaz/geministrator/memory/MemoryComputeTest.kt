package com.hereliesaz.geministrator.memory

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryComputeTest {
    private val cpu = MemoryComputeDevice("CPUExecutionProvider", "CPU", MemoryComputeDeviceType.CPU)
    private val gpu = MemoryComputeDevice("CUDAExecutionProvider", "GPU 0", MemoryComputeDeviceType.GPU, deviceId = 0)
    private val npu = MemoryComputeDevice("QNNExecutionProvider", "NPU 0", MemoryComputeDeviceType.NPU, deviceId = 0)

    @Test
    fun autoPrefersNpuForEmbeddings() {
        val selection = MemoryComputeSelector.select(
            devices = listOf(cpu, gpu, npu),
            requirements = MemoryModelRequirements.embeddings(),
        )

        assertEquals(MemoryComputeDeviceType.NPU, selection.device.deviceType)
        assertTrue(selection.cpuFallbackEnabled)
    }

    @Test
    fun autoPrefersGpuForAutoregressiveGeneration() {
        val selection = MemoryComputeSelector.select(
            devices = listOf(cpu, npu, gpu),
            requirements = MemoryModelRequirements.generation(),
        )

        assertEquals(MemoryComputeDeviceType.GPU, selection.device.deviceType)
    }

    @Test
    fun cpuOnlyNeverSelectsAccelerator() {
        val selection = MemoryComputeSelector.select(
            devices = listOf(gpu, npu, cpu),
            requirements = MemoryModelRequirements.generation(),
            preference = MemoryComputePreference.CPU_ONLY,
        )

        assertEquals(cpu, selection.device)
        assertFalse(selection.cpuFallbackEnabled)
    }

    @Test
    fun platformBackendHintsBreakDeviceTypeTies() {
        val webGpu = gpu.copy(backend = "WebGPUExecutionProvider", deviceName = "WebGPU")
        val selection = MemoryComputeSelector.select(
            devices = listOf(cpu, gpu, webGpu),
            requirements = MemoryModelRequirements.generation().copy(
                preferredBackends = listOf("WebGPU", "CUDA"),
            ),
        )

        assertEquals("WebGPUExecutionProvider", selection.device.backend)
    }
}
