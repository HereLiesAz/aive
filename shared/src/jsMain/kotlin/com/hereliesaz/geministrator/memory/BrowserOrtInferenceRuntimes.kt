package com.hereliesaz.geministrator.memory

import kotlin.js.Promise
import kotlinx.coroutines.await

@JsModule("onnxruntime-web/webgpu")
@JsNonModule
private external val ortWeb: dynamic

class BrowserOrtHardwareCapabilityDetector(
    private val preference: MemoryComputePreference = MemoryComputePreference.AUTO,
) : HardwareCapabilityDetector {
    override suspend fun discover(): List<MemoryComputeDevice> {
        val devices = mutableListOf(
            MemoryComputeDevice(
                backend = "wasm",
                deviceName = "WebAssembly CPU",
                deviceType = MemoryComputeDeviceType.CPU,
            ),
        )
        val navigator = js("globalThis.navigator")
        val gpu = navigator?.gpu
        if (gpu != null) {
            val options = js("({})")
            when (preference) {
                MemoryComputePreference.HIGH_PERFORMANCE -> options.powerPreference = "high-performance"
                MemoryComputePreference.LOW_POWER -> options.powerPreference = "low-power"
                else -> Unit
            }
            val adapter = runCatching {
                gpu.requestAdapter(options).unsafeCast<Promise<dynamic>>().await()
            }.getOrNull()
            if (adapter != null) {
                val info = adapter.info
                val description = info?.description?.toString()
                val vendor = info?.vendor?.toString()
                val architecture = info?.architecture?.toString()
                devices.add(
                    0,
                    MemoryComputeDevice(
                        backend = "webgpu",
                        deviceName = description?.takeIf { it.isNotBlank() } ?: "WebGPU adapter",
                        deviceType = MemoryComputeDeviceType.GPU,
                        metadata = buildMap {
                            vendor?.takeIf { it.isNotBlank() }?.let { put("vendor", it) }
                            architecture?.takeIf { it.isNotBlank() }?.let { put("architecture", it) }
                        },
                    ),
                )
            }
        }
        return devices
    }
}

interface BrowserOrtArtifactResolver {
    suspend fun isAvailable(artifact: MemoryMicroAgentArtifact): Boolean
    suspend fun resolveUri(artifact: MemoryMicroAgentArtifact): String
}

class BrowserMapOrtArtifactResolver(
    private val uris: Map<String, String>,
) : BrowserOrtArtifactResolver {
    override suspend fun isAvailable(artifact: MemoryMicroAgentArtifact): Boolean =
        !uris[artifact.artifactId].isNullOrBlank()

    override suspend fun resolveUri(artifact: MemoryMicroAgentArtifact): String =
        uris[artifact.artifactId]?.takeIf(String::isNotBlank)
            ?: error("No browser ONNX artifact for ${artifact.artifactId}")
}

class BrowserOrtPreparedSession internal constructor(
    val session: dynamic,
    val selection: MemoryComputeSelection,
) {
    private var report: MemoryExecutionReport = MemoryExecutionReport(
        selection = selection,
        verification = MemoryExecutionVerification.SessionConfigured,
    )

    internal fun recordSuccessfulRun(gpuObserved: Boolean) {
        report = when {
            selection.device.deviceType == MemoryComputeDeviceType.CPU -> MemoryExecutionReport(
                selection = selection,
                actualDevices = listOf(selection.device),
                cpuFallbackObserved = false,
                acceleratedNodeFraction = 0f,
                verification = MemoryExecutionVerification.ProfiledRun,
            )
            gpuObserved -> MemoryExecutionReport(
                selection = selection,
                actualDevices = listOf(selection.device),
                cpuFallbackObserved = null,
                acceleratedNodeFraction = null,
                verification = MemoryExecutionVerification.ProfiledRun,
            )
            else -> report
        }
    }

    fun executionReport(): MemoryExecutionReport = report
}

class BrowserOrtMemorySessionManager(
    val computePreference: MemoryComputePreference = MemoryComputePreference.AUTO,
    val capabilityDetector: BrowserOrtHardwareCapabilityDetector =
        BrowserOrtHardwareCapabilityDetector(computePreference),
) {
    val platform: MemoryMicroAgentPlatform = MemoryMicroAgentPlatform.Web
    private val sessions = mutableMapOf<String, BrowserOrtPreparedSession>()
    private var activeProfiledSession: BrowserOrtPreparedSession? = null
    private var activeRunObservedGpu = false

    init {
        val profiling = js("({mode: 'default'})")
        profiling.ondata = { _: dynamic ->
            activeRunObservedGpu = true
        }
        ortWeb.env.webgpu.profiling = profiling
        when (computePreference) {
            MemoryComputePreference.HIGH_PERFORMANCE -> ortWeb.env.webgpu.powerPreference = "high-performance"
            MemoryComputePreference.LOW_POWER -> ortWeb.env.webgpu.powerPreference = "low-power"
            else -> Unit
        }
    }

    suspend fun sessionFor(
        modelUri: String,
        model: MemoryMicroAgentModelSpec,
    ): BrowserOrtPreparedSession {
        val key = listOf(model.modelId, modelUri, computePreference.name, model.requirements.workload.name).joinToString("|")
        sessions[key]?.let { return it }

        val discovered = capabilityDetector.discover()
        val ranked = model.requirements.copy(
            preferredBackends = (model.requirements.preferredBackends + platform.preferredExecutionProviders()).distinct(),
        )
        val initial = MemoryComputeSelector.select(discovered, ranked, computePreference, model.modelId)
        val selected = initial.copy(
            device = initial.device.copy(supportedModels = initial.device.supportedModels + model.modelId),
        )
        val prepared = try {
            createSession(modelUri, selected)
        } catch (acceleratorFailure: Throwable) {
            if (selected.device.deviceType == MemoryComputeDeviceType.CPU || !model.requirements.allowCpuFallback) {
                throw acceleratorFailure
            }
            val cpu = discovered.first { it.deviceType == MemoryComputeDeviceType.CPU }
            createSession(
                modelUri,
                MemoryComputeSelection(
                    preference = computePreference,
                    device = cpu.copy(
                        supportedModels = cpu.supportedModels + model.modelId,
                        metadata = cpu.metadata + mapOf(
                            "fallbackFrom" to selected.device.backend,
                            "fallbackReason" to (acceleratorFailure.message ?: acceleratorFailure::class.simpleName.orEmpty()).take(240),
                        ),
                    ),
                    cpuFallbackEnabled = false,
                ),
            )
        }
        sessions[key] = prepared
        return prepared
    }

    suspend fun <T> observeRun(session: BrowserOrtPreparedSession, block: suspend () -> T): T {
        check(activeProfiledSession == null) { "Browser ORT memory inference is already running" }
        activeProfiledSession = session
        activeRunObservedGpu = false
        var succeeded = false
        return try {
            block().also { succeeded = true }
        } finally {
            if (succeeded) session.recordSuccessfulRun(activeRunObservedGpu)
            activeProfiledSession = null
            activeRunObservedGpu = false
        }
    }

    private suspend fun createSession(
        modelUri: String,
        selection: MemoryComputeSelection,
    ): BrowserOrtPreparedSession {
        val options = js("({})")
        options.graphOptimizationLevel = "all"
        options.executionProviders = if (selection.device.deviceType == MemoryComputeDeviceType.GPU) {
            arrayOf("webgpu", "wasm")
        } else {
            arrayOf("wasm")
        }
        val session = ortWeb.InferenceSession.create(modelUri, options)
            .unsafeCast<Promise<dynamic>>()
            .await()
        return BrowserOrtPreparedSession(session, selection)
    }

    suspend fun close() {
        sessions.values.forEach { prepared ->
            runCatching {
                prepared.session.release().unsafeCast<Promise<Unit>>().await()
            }
        }
        sessions.clear()
    }
}

fun interface BrowserOrtGenerativeModelAdapter {
    suspend fun generate(session: dynamic, request: MemoryGenerativeInferenceRequest): String
}

fun interface BrowserOrtEmbeddingModelAdapter {
    suspend fun embed(session: dynamic, request: MemoryEmbeddingInferenceRequest): List<List<Float>>
}

class BrowserOrtGenerativeInferenceRuntime(
    private val sessionManager: BrowserOrtMemorySessionManager,
    private val artifactResolver: BrowserOrtArtifactResolver,
    private val modelAdapter: BrowserOrtGenerativeModelAdapter,
) : MemoryGenerativeInferenceRuntime {
    override val platform = MemoryMicroAgentPlatform.Web
    override val capabilityDetector: HardwareCapabilityDetector get() = sessionManager.capabilityDetector
    override val computePreference: MemoryComputePreference get() = sessionManager.computePreference
    private val reports = mutableMapOf<String, MemoryExecutionReport>()

    override suspend fun isAvailable(
        model: MemoryMicroAgentModelSpec,
        artifact: MemoryMicroAgentArtifact,
    ): Boolean = artifact.platform == platform &&
        artifact.backend == MemoryInferenceBackend.OnnxRuntimeWeb &&
        artifactResolver.isAvailable(artifact)

    override suspend fun generate(request: MemoryGenerativeInferenceRequest): MemoryGenerativeInferenceResult {
        val uri = artifactResolver.resolveUri(request.artifact)
        val prepared = sessionManager.sessionFor(uri, request.model)
        val text = sessionManager.observeRun(prepared) {
            modelAdapter.generate(prepared.session, request)
        }
        val report = prepared.executionReport()
        reports[request.model.modelId] = report
        return MemoryGenerativeInferenceResult(text, report)
    }

    override suspend fun executionReport(modelId: String): MemoryExecutionReport? = reports[modelId]
}

class BrowserOrtEmbeddingInferenceRuntime(
    private val sessionManager: BrowserOrtMemorySessionManager,
    private val artifactResolver: BrowserOrtArtifactResolver,
    private val modelAdapter: BrowserOrtEmbeddingModelAdapter,
) : MemoryEmbeddingInferenceRuntime {
    override val platform = MemoryMicroAgentPlatform.Web
    override val capabilityDetector: HardwareCapabilityDetector get() = sessionManager.capabilityDetector
    override val computePreference: MemoryComputePreference get() = sessionManager.computePreference
    private val reports = mutableMapOf<String, MemoryExecutionReport>()

    override suspend fun isAvailable(
        model: MemoryMicroAgentModelSpec,
        artifact: MemoryMicroAgentArtifact,
    ): Boolean = artifact.platform == platform &&
        artifact.backend == MemoryInferenceBackend.OnnxRuntimeWeb &&
        artifactResolver.isAvailable(artifact)

    override suspend fun embed(request: MemoryEmbeddingInferenceRequest): MemoryEmbeddingInferenceResult {
        val uri = artifactResolver.resolveUri(request.artifact)
        val prepared = sessionManager.sessionFor(uri, request.model)
        val vectors = sessionManager.observeRun(prepared) {
            modelAdapter.embed(prepared.session, request)
        }
        val report = prepared.executionReport()
        reports[request.model.modelId] = report
        return MemoryEmbeddingInferenceResult(vectors, report)
    }

    override suspend fun executionReport(modelId: String): MemoryExecutionReport? = reports[modelId]
}
