package com.hereliesaz.geministrator.providers

import kotlinx.coroutines.CancellationException

/**
 * The on-device generator the small-task provider uses: the first ready runtime in preference order
 * (LiteRT-LM before ONNX Runtime), with the device's thermal hold applied to every runtime. A
 * runtime that cannot serve a call ([OnDeviceRuntimeUnavailableException]) hands it to the next one;
 * a refusal, timeout or cancellation does not.
 */
class SelectingOnDeviceTextGenerator(
    private val candidates: List<Candidate>,
    private val resources: OnDeviceResourceProbe,
) : OnDeviceTextGenerator, OnDeviceTextRuntimeSource {
    class Candidate(val runtime: OnDeviceTextRuntime, val generator: OnDeviceTextGenerator)

    private fun readyCandidates(): List<Candidate> =
        candidates.filter { runCatching { it.generator.isReady() }.getOrDefault(false) }

    override fun activeRuntime(): OnDeviceTextRuntime? = readyCandidates().firstOrNull()?.runtime

    /** Ready while a model is installed and the device is not hot, so routing skips a hot device. */
    override fun isReady(): Boolean =
        OnDeviceResourceGate.thermalHold(resources.snapshot()) == null && readyCandidates().isNotEmpty()

    override suspend fun generate(system: String, user: String, maxNewTokens: Int): OnDeviceTextGeneration {
        OnDeviceResourceGate.thermalHold(resources.snapshot())?.let { throw OnDeviceGenerationDeclined(it) }
        var unavailable: OnDeviceRuntimeUnavailableException? = null
        for (candidate in readyCandidates()) {
            try {
                return candidate.generator.generate(system, user, maxNewTokens)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (skipped: OnDeviceRuntimeUnavailableException) {
                unavailable = skipped
            }
        }
        throw unavailable ?: IllegalStateException("The on-device model is not installed")
    }

    companion object {
        /** The first runtime of [preference] that is in [ready], or null. */
        fun choose(preference: List<OnDeviceTextRuntime>, ready: Set<OnDeviceTextRuntime>): OnDeviceTextRuntime? =
            preference.firstOrNull { it in ready }
    }
}
