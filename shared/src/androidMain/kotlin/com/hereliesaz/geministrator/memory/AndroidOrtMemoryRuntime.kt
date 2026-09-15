package com.hereliesaz.geministrator.memory

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtEpDevice
import ai.onnxruntime.OrtHardwareDevice
import ai.onnxruntime.OrtProvider
import ai.onnxruntime.OrtSession
import ai.onnxruntime.providers.NNAPIFlags
import android.os.Build
import java.io.File
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Android ORT discovery. NNAPI is considered only on API levels where it exists. */
class AndroidOrtHardwareCapabilityDetector(
    internal val environment: OrtEnvironment = OrtEnvironment.getEnvironment(),
) : HardwareCapabilityDetector {
    override suspend fun discover(): List<MemoryComputeDevice> = discoverNow()

    internal fun discoverNow(): List<MemoryComputeDevice> {
        val epDevices = runCatching { environment.getEpDevices() }.getOrDefault(emptyList())
        val discovered = epDevices.map(::toMemoryDevice).toMutableList()
        OrtEnvironment.getAvailableProviders().forEach { provider ->
            val backend = provider.getName()
            if ("nnapi" in backend.lowercase() && Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return@forEach
            if (discovered.none { it.backend.equals(backend, ignoreCase = true) }) discovered += syntheticDevice(provider)
        }
        if (discovered.none { it.deviceType == MemoryComputeDeviceType.CPU }) {
            discovered += MemoryComputeDevice("CPUExecutionProvider", "CPU", MemoryComputeDeviceType.CPU)
        }
        return discovered.distinctBy { Triple(it.backend, it.deviceId, it.deviceType) }
    }

    internal fun epDevices(): List<OrtEpDevice> = runCatching { environment.getEpDevices() }.getOrDefault(emptyList())

    private fun toMemoryDevice(epDevice: OrtEpDevice): MemoryComputeDevice {
        val device = epDevice.getDevice()
        val metadata = buildMap {
            device.getMetadata().forEach { (key, value) -> put(key, value) }
            epDevice.getEpMetadata().forEach { (key, value) -> put("ep.$key", value) }
            epDevice.getEpOptions().forEach { (key, value) -> put("option.$key", value) }
            put("vendor", device.getVendor())
            put("epVendor", epDevice.getEpVendor())
            put("vendorId", device.getVendorId().toString())
            put("androidApi", Build.VERSION.SDK_INT.toString())
            put("hardwareIdentityVerified", "true")
        }
        val type = device.getType().toMemoryDeviceType()
        return MemoryComputeDevice(
            backend = epDevice.getEpName(),
            deviceName = metadata["name"] ?: metadata["device_name"]
                ?: "${device.getVendor()} ${type.name} ${device.getDeviceId()}".trim(),
            deviceType = type,
            deviceId = device.getDeviceId(),
            dedicatedMemory = metadata.findMemoryBytes("dedicated"),
            sharedMemory = metadata.findMemoryBytes("shared"),
            metadata = metadata,
        )
    }

    private fun syntheticDevice(provider: OrtProvider): MemoryComputeDevice {
        val backend = provider.getName()
        val type = providerType(backend)
        return MemoryComputeDevice(
            backend = backend,
            deviceName = backend.removeSuffix("ExecutionProvider"),
            deviceType = type,
            deviceId = if (type == MemoryComputeDeviceType.CPU) null else 0,
            metadata = mapOf(
                "syntheticDiscovery" to "true",
                "hardwareIdentityVerified" to (type == MemoryComputeDeviceType.CPU).toString(),
                "androidApi" to Build.VERSION.SDK_INT.toString(),
            ),
        )
    }
}

class AndroidOrtPreparedSession internal constructor(
    val session: OrtSession,
    private val options: OrtSession.SessionOptions,
    val selection: MemoryComputeSelection,
    private val discoveredDevices: List<MemoryComputeDevice>,
    private val profilingEnabled: Boolean,
) : AutoCloseable {
    @Volatile
    private var report = MemoryExecutionReport(
        selection = selection,
        verification = MemoryExecutionVerification.SessionConfigured,
    )

    fun executionReport(): MemoryExecutionReport = report

    fun captureExecutionProfile(): MemoryExecutionReport {
        if (!profilingEnabled || report.verification == MemoryExecutionVerification.ProfiledRun) return report
        val profilePath = runCatching { session.endProfiling() }.getOrNull() ?: return report
        val profileFile = File(profilePath)
        val nodeProviders = runCatching { profileProviderEvents(profileFile) }.getOrDefault(emptyList())
        runCatching { profileFile.delete() }
        if (nodeProviders.isEmpty()) return report

        val actualDevices = nodeProviders.distinct().mapNotNull { providerName ->
            val discovered = discoveredDevices.firstOrNull { it.backend.equals(providerName, ignoreCase = true) }
            when {
                discovered?.deviceType == MemoryComputeDeviceType.CPU -> discovered
                discovered?.metadata?.get("hardwareIdentityVerified") == "true" -> discovered
                selection.device.backend.equals(providerName, ignoreCase = true) &&
                    selection.device.metadata["hardwareIdentityVerified"] == "true" -> selection.device
                else -> null
            }
        }.distinctBy { Triple(it.backend, it.deviceId, it.deviceType) }
        val acceleratedEvents = nodeProviders.count { providerName ->
            discoveredDevices.any {
                it.backend.equals(providerName, ignoreCase = true) &&
                    it.deviceType != MemoryComputeDeviceType.CPU &&
                    it.metadata["hardwareIdentityVerified"] == "true"
            }
        }
        report = MemoryExecutionReport(
            selection = selection,
            actualDevices = actualDevices,
            cpuFallbackObserved = selection.device.deviceType != MemoryComputeDeviceType.CPU &&
                actualDevices.any { it.deviceType == MemoryComputeDeviceType.CPU },
            acceleratedNodeFraction = acceleratedEvents.toFloat() / nodeProviders.size,
            verification = MemoryExecutionVerification.ProfiledRun,
        )
        return report
    }

    override fun close() {
        session.close()
        options.close()
    }
}

/** Hardware-aware Android session cache. Pass app cacheDir to enable post-run provider profiling. */
class AndroidOrtMemorySessionManager(
    val computePreference: MemoryComputePreference = MemoryComputePreference.AUTO,
    val capabilityDetector: AndroidOrtHardwareCapabilityDetector = AndroidOrtHardwareCapabilityDetector(),
    private val profileDirectory: File? = null,
    private val providerLibraries: Map<String, String> = emptyMap(),
) : AutoCloseable {
    val platform = MemoryMicroAgentPlatform.Android
    private val environment: OrtEnvironment = capabilityDetector.environment
    private val sessions = ConcurrentHashMap<String, AndroidOrtPreparedSession>()

    init {
        profileDirectory?.mkdirs()
        providerLibraries.forEach { (registrationName, libraryPath) ->
            environment.registerExecutionProviderLibrary(registrationName, libraryPath)
        }
    }

    suspend fun sessionFor(
        modelPath: String,
        model: MemoryMicroAgentModelSpec,
        providerOptions: Map<String, String> = emptyMap(),
    ): AndroidOrtPreparedSession {
        val key = listOf(
            model.modelId,
            modelPath,
            computePreference.name,
            model.requirements.workload.name,
            providerOptions.toSortedMap().entries.joinToString(),
        ).joinToString("|")
        sessions[key]?.let { return it }

        val discovered = capabilityDetector.discover()
        val rankedRequirements = model.requirements.copy(
            preferredBackends = (model.requirements.preferredBackends + platform.preferredExecutionProviders()).distinct(),
        )
        val attempts = MemoryComputeSelector.rank(
            devices = discovered,
            requirements = rankedRequirements,
            preference = computePreference,
            modelId = model.modelId,
        )
        require(attempts.isNotEmpty()) { "No compatible local compute device is available for ${model.modelId}" }

        val failures = mutableListOf<Pair<MemoryComputeSelection, Throwable>>()
        var created: AndroidOrtPreparedSession? = null
        for (attempt in attempts) {
            val selected = attempt.copy(
                device = attempt.device.copy(
                    supportedModels = attempt.device.supportedModels + model.modelId,
                    metadata = if (attempt.device.deviceType == MemoryComputeDeviceType.CPU && failures.isNotEmpty()) {
                        attempt.device.metadata + mapOf(
                            "fallbackFrom" to failures.joinToString(",") { it.first.device.backend },
                            "fallbackReason" to (failures.last().second.message
                                ?: failures.last().second::class.simpleName.orEmpty()).take(240),
                        )
                    } else attempt.device.metadata,
                ),
            )
            val options = if (selected.device.deviceType == MemoryComputeDeviceType.CPU) emptyMap() else providerOptions
            try {
                created = createSessionAttempt(modelPath, model, selected, discovered, options)
                break
            } catch (failure: Throwable) {
                failures += selected to failure
            }
        }
        val usable = created ?: throw IllegalStateException(
            "No ONNX Runtime execution provider accepted ${model.modelId}; tried " +
                failures.joinToString { it.first.device.backend },
            failures.lastOrNull()?.second,
        )
        val previous = sessions.putIfAbsent(key, usable)
        if (previous != null) {
            usable.close()
            return previous
        }
        return usable
    }

    private fun createSessionAttempt(
        modelPath: String,
        model: MemoryMicroAgentModelSpec,
        selection: MemoryComputeSelection,
        discovered: List<MemoryComputeDevice>,
        providerOptions: Map<String, String>,
    ): AndroidOrtPreparedSession {
        val options = OrtSession.SessionOptions()
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        if (!model.requirements.allowCpuFallback && selection.device.deviceType != MemoryComputeDeviceType.CPU) {
            options.addConfigEntry("session.disable_cpu_ep_fallback", "1")
        }
        val profilingEnabled = profileDirectory != null
        if (profilingEnabled) {
            val prefix = File(
                profileDirectory,
                "haive-ort-${model.modelId.hashCode().toUInt().toString(16)}-${System.nanoTime()}",
            ).absolutePath
            options.enableProfiling(prefix)
        }

        if (selection.device.deviceType != MemoryComputeDeviceType.CPU) {
            val epDevice = capabilityDetector.epDevices().firstOrNull { candidate ->
                candidate.getEpName().equals(selection.device.backend, ignoreCase = true) &&
                    candidate.getDevice().getDeviceId() == (selection.device.deviceId ?: candidate.getDevice().getDeviceId())
            }
            if (epDevice != null) options.addExecutionProvider(listOf(epDevice), providerOptions)
            else addLegacyProvider(options, selection.device, providerOptions)
        }

        return try {
            AndroidOrtPreparedSession(
                session = environment.createSession(modelPath, options),
                options = options,
                selection = selection,
                discoveredDevices = discovered,
                profilingEnabled = profilingEnabled,
            )
        } catch (failure: Throwable) {
            options.close()
            throw failure
        }
    }

    private fun addLegacyProvider(
        options: OrtSession.SessionOptions,
        device: MemoryComputeDevice,
        providerOptions: Map<String, String>,
    ) {
        val backend = device.backend.lowercase()
        when {
            "nnapi" in backend -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) options.addNnapi(EnumSet.of(NNAPIFlags.CPU_DISABLED))
                else options.addNnapi()
            }
            "qnn" in backend -> options.addQnn(providerOptions)
            "webgpu" in backend -> options.addWebGPU(providerOptions)
            else -> error("ORT provider ${device.backend} cannot be configured by the Android runtime")
        }
    }

    override fun close() {
        sessions.values.forEach { runCatching { it.close() } }
        sessions.clear()
        providerLibraries.keys.forEach { registrationName ->
            runCatching { environment.unregisterExecutionProviderLibrary(registrationName) }
        }
    }
}

private fun OrtHardwareDevice.OrtHardwareDeviceType.toMemoryDeviceType(): MemoryComputeDeviceType = when (name) {
    "GPU" -> MemoryComputeDeviceType.GPU
    "NPU" -> MemoryComputeDeviceType.NPU
    else -> MemoryComputeDeviceType.CPU
}

private fun providerType(backend: String): MemoryComputeDeviceType {
    val name = backend.lowercase()
    return when {
        "qnn" in name || "nnapi" in name || "npu" in name -> MemoryComputeDeviceType.NPU
        "webgpu" in name || "vulkan" in name || "gpu" in name -> MemoryComputeDeviceType.GPU
        else -> MemoryComputeDeviceType.CPU
    }
}

private fun Map<String, String>.findMemoryBytes(hint: String): Long? = entries
    .firstOrNull { (key, _) -> hint in key.lowercase() && "memory" in key.lowercase() }
    ?.value
    ?.filter(Char::isDigit)
    ?.toLongOrNull()

private fun profileProviderEvents(file: File): List<String> {
    if (!file.exists()) return emptyList()
    val root = Json.parseToJsonElement(file.readText()) as? JsonArray ?: return emptyList()
    return root.mapNotNull { element ->
        val event = element as? JsonObject ?: return@mapNotNull null
        val args = event["args"] as? JsonObject ?: return@mapNotNull null
        args["provider"]?.jsonPrimitive?.content
    }
}
