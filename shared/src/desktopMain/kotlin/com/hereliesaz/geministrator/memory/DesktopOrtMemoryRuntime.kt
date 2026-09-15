package com.hereliesaz.geministrator.memory

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtEpDevice
import ai.onnxruntime.OrtHardwareDevice
import ai.onnxruntime.OrtProvider
import ai.onnxruntime.OrtSession
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Enumerates the execution-provider/device tuples exposed by the installed ORT build. */
class DesktopOrtHardwareCapabilityDetector(
    internal val environment: OrtEnvironment = OrtEnvironment.getEnvironment(),
) : HardwareCapabilityDetector {
    override suspend fun discover(): List<MemoryComputeDevice> = discoverNow()

    internal fun discoverNow(): List<MemoryComputeDevice> {
        val epDevices = runCatching { environment.getEpDevices() }.getOrDefault(emptyList())
        val discovered = epDevices.map(::toMemoryDevice).toMutableList()

        OrtEnvironment.getAvailableProviders().forEach { provider ->
            val backend = provider.getName()
            if (discovered.none { it.backend.equals(backend, ignoreCase = true) }) {
                discovered += syntheticDevice(provider)
            }
        }
        if (discovered.none { it.deviceType == MemoryComputeDeviceType.CPU }) {
            discovered += MemoryComputeDevice(
                backend = "CPUExecutionProvider",
                deviceName = "CPU",
                deviceType = MemoryComputeDeviceType.CPU,
            )
        }
        return discovered.distinctBy { Triple(it.backend, it.deviceId, it.deviceType) }
    }

    internal fun epDevices(): List<OrtEpDevice> =
        runCatching { environment.getEpDevices() }.getOrDefault(emptyList())

    internal fun toMemoryDevice(epDevice: OrtEpDevice): MemoryComputeDevice {
        val device = epDevice.getDevice()
        val metadata = buildMap {
            device.getMetadata().forEach { (key, value) -> put(key, value) }
            epDevice.getEpMetadata().forEach { (key, value) -> put("ep.$key", value) }
            epDevice.getEpOptions().forEach { (key, value) -> put("option.$key", value) }
            put("vendor", device.getVendor())
            put("epVendor", epDevice.getEpVendor())
            put("vendorId", device.getVendorId().toString())
            put("hardwareIdentityVerified", "true")
        }
        val type = device.getType().toMemoryDeviceType()
        return MemoryComputeDevice(
            backend = epDevice.getEpName(),
            deviceName = metadata["name"]
                ?: metadata["device_name"]
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
            ),
        )
    }
}

/** Cached ORT session plus truthful post-run provider telemetry. */
class DesktopOrtPreparedSession internal constructor(
    val session: OrtSession,
    private val options: OrtSession.SessionOptions,
    val selection: MemoryComputeSelection,
    private val discoveredDevices: List<MemoryComputeDevice>,
) : AutoCloseable {
    @Volatile
    private var report: MemoryExecutionReport = MemoryExecutionReport(
        selection = selection,
        verification = MemoryExecutionVerification.SessionConfigured,
    )

    fun executionReport(): MemoryExecutionReport = report

    /** Call after the first successful inference. Subsequent calls return the cached observation. */
    fun captureExecutionProfile(): MemoryExecutionReport {
        if (report.verification == MemoryExecutionVerification.ProfiledRun) return report
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
        val observedAcceleratedProviders = nodeProviders.count { providerName ->
            discoveredDevices.any {
                it.backend.equals(providerName, ignoreCase = true) &&
                    it.deviceType != MemoryComputeDeviceType.CPU &&
                    it.metadata["hardwareIdentityVerified"] == "true"
            }
        }
        val fraction = observedAcceleratedProviders.toFloat() / nodeProviders.size
        val cpuFallback = selection.device.deviceType != MemoryComputeDeviceType.CPU &&
            actualDevices.any { it.deviceType == MemoryComputeDeviceType.CPU }

        report = MemoryExecutionReport(
            selection = selection,
            actualDevices = actualDevices,
            cpuFallbackObserved = cpuFallback,
            acceleratedNodeFraction = fraction,
            verification = MemoryExecutionVerification.ProfiledRun,
        )
        return report
    }

    override fun close() {
        session.close()
        options.close()
    }
}

/**
 * Chooses the best available provider for each model, tries compatible accelerators in rank order,
 * keeps CPU as the explicit final fallback, and caches the first session that accepts the model.
 */
class DesktopOrtMemorySessionManager(
    val computePreference: MemoryComputePreference = MemoryComputePreference.AUTO,
    val capabilityDetector: DesktopOrtHardwareCapabilityDetector = DesktopOrtHardwareCapabilityDetector(),
    private val providerLibraries: Map<String, String> = emptyMap(),
) : AutoCloseable {
    val platform: MemoryMicroAgentPlatform = currentDesktopPlatform()
    private val environment: OrtEnvironment = capabilityDetector.environment
    private val sessions = ConcurrentHashMap<String, DesktopOrtPreparedSession>()

    init {
        providerLibraries.forEach { (registrationName, libraryPath) ->
            environment.registerExecutionProviderLibrary(registrationName, libraryPath)
        }
    }

    suspend fun sessionFor(
        modelPath: String,
        model: MemoryMicroAgentModelSpec,
        providerOptions: Map<String, String> = emptyMap(),
    ): DesktopOrtPreparedSession {
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
            preferredBackends = (
                model.requirements.preferredBackends + platform.preferredExecutionProviders()
            ).distinct(),
        )
        val attempts = MemoryComputeSelector.rank(
            devices = discovered,
            requirements = rankedRequirements,
            preference = computePreference,
            modelId = model.modelId,
        )
        require(attempts.isNotEmpty()) { "No compatible local compute device is available for ${model.modelId}" }

        val failures = mutableListOf<Pair<MemoryComputeSelection, Throwable>>()
        var created: DesktopOrtPreparedSession? = null
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
                    } else {
                        attempt.device.metadata
                    },
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
    ): DesktopOrtPreparedSession {
        val options = OrtSession.SessionOptions()
        options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        if (!model.requirements.allowCpuFallback && selection.device.deviceType != MemoryComputeDeviceType.CPU) {
            options.addConfigEntry("session.disable_cpu_ep_fallback", "1")
        }
        val profilePrefix = File(
            System.getProperty("java.io.tmpdir"),
            "haive-ort-${model.modelId.hashCode().toUInt().toString(16)}-${System.nanoTime()}",
        ).absolutePath
        options.enableProfiling(profilePrefix)

        if (selection.device.deviceType != MemoryComputeDeviceType.CPU) {
            val epDevice = capabilityDetector.epDevices().firstOrNull { candidate ->
                candidate.getEpName().equals(selection.device.backend, ignoreCase = true) &&
                    candidate.getDevice().getDeviceId() == (selection.device.deviceId ?: candidate.getDevice().getDeviceId())
            }
            if (epDevice != null) {
                options.addExecutionProvider(listOf(epDevice), providerOptions)
            } else {
                addLegacyProvider(options, selection.device, providerOptions)
            }
        }

        return try {
            DesktopOrtPreparedSession(
                session = environment.createSession(modelPath, options),
                options = options,
                selection = selection,
                discoveredDevices = discovered,
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
        val id = device.deviceId ?: 0
        when {
            "cuda" in backend -> options.addCUDA(id)
            "tensorrt" in backend -> options.addTensorrt(id)
            "directml" in backend || "dml" in backend -> options.addDirectML(id)
            "coreml" in backend -> options.addCoreML(providerOptions)
            "webgpu" in backend -> options.addWebGPU(providerOptions)
            "qnn" in backend -> options.addQnn(providerOptions)
            "rocm" in backend -> options.addROCM(id)
            else -> error("ORT provider ${device.backend} cannot be configured by this runtime")
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

private fun currentDesktopPlatform(): MemoryMicroAgentPlatform {
    val os = System.getProperty("os.name").lowercase()
    return when {
        "win" in os -> MemoryMicroAgentPlatform.Windows
        "mac" in os || "darwin" in os -> MemoryMicroAgentPlatform.MacOS
        "linux" in os -> MemoryMicroAgentPlatform.Linux
        else -> error("Unsupported desktop platform: $os")
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
        "qnn" in name || "nnapi" in name || "npu" in name || "vitis" in name -> MemoryComputeDeviceType.NPU
        "cuda" in name || "tensorrt" in name || "rocm" in name || "migrafx" in name ||
            "directml" in name || "dml" in name || "webgpu" in name || "coreml" in name -> MemoryComputeDeviceType.GPU
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
