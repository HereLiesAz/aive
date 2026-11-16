package com.hereliesaz.geministrator.memory

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.JsString
import kotlin.js.Promise
import kotlinx.coroutines.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/*
 * ONNX Runtime Web for Kotlin/Wasm. Same shape as the Web Crypto bridge (mesh/MeshCrypto.wasm.kt):
 * webApp/src/webMain/resources/aive-ort.js defines `globalThis.AiveOrt`, loads onnxruntime-web/webgpu
 * lazily, and answers every call with a JSON string (`{"ok":true,...}` / `{"ok":false,"error":"..."}`),
 * so nothing but String, Int and Boolean crosses the boundary and no rejected Promise has to be mapped.
 * Tensor, provider and error logic is platform-free and lives in OrtWeb.kt.
 */
private external object AiveOrt : JsAny {
    val ready: Boolean

    fun webGpuAvailable(): Boolean

    fun adapterInfo(powerPreference: String): Promise<JsString>

    fun allocBuffer(size: Int): String

    fun writeBuffer(bufferId: String, offset: Int, chunkBase64: String): String

    fun createSession(modelUri: String, providersCsv: String, powerPreference: String): Promise<JsString>

    fun createSessionFromBuffer(bufferId: String, providersCsv: String, powerPreference: String): Promise<JsString>

    fun run(sessionId: String, requestJson: String): Promise<JsString>

    fun release(sessionId: String): Promise<JsString>
}

/** Entry points to the browser runtime. */
@OptIn(ExperimentalWasmJsInterop::class, ExperimentalEncodingApi::class)
object BrowserOrtRuntime {
    private const val CHUNK_BYTES = 3 * 256 * 1024

    /** True when the page loaded aive-ort.js (it does not load onnxruntime-web itself until first use). */
    fun isShimLoaded(): Boolean = runCatching { AiveOrt.ready }.getOrDefault(false)

    internal fun webGpuAvailable(): Boolean = runCatching { AiveOrt.webGpuAvailable() }.getOrDefault(false)

    internal suspend fun adapterInfo(preference: MemoryComputePreference): OrtWebAdapterInfo? =
        OrtWebWire.decodeAdapterInfo(
            AiveOrt.adapterInfo(OrtWebExecutionProviders.powerPreference(preference)).await().toString(),
        )

    /** Creates a session from a model URL (same origin, or https with CORS). */
    suspend fun createSession(
        modelUri: String,
        providers: List<String> = listOf(OrtWebExecutionProviders.WEBGPU, OrtWebExecutionProviders.WASM),
        preference: MemoryComputePreference = MemoryComputePreference.AUTO,
    ): BrowserOrtSession {
        require(providers.isNotEmpty()) { "At least one execution provider is required" }
        val info = OrtWebWire.decodeSessionInfo(
            AiveOrt.createSession(
                modelUri,
                providers.joinToString(","),
                OrtWebExecutionProviders.powerPreference(preference),
            ).await().toString(),
        )
        return BrowserOrtSession(info)
    }

    /** Creates a session from model bytes, copied across in base64 chunks. */
    suspend fun createSessionFromBytes(
        model: ByteArray,
        providers: List<String> = listOf(OrtWebExecutionProviders.WEBGPU, OrtWebExecutionProviders.WASM),
        preference: MemoryComputePreference = MemoryComputePreference.AUTO,
    ): BrowserOrtSession {
        require(model.isNotEmpty()) { "Model bytes must not be empty" }
        require(providers.isNotEmpty()) { "At least one execution provider is required" }
        val bufferId = OrtWebWire.decodeBufferId(AiveOrt.allocBuffer(model.size))
        var offset = 0
        while (offset < model.size) {
            val end = minOf(offset + CHUNK_BYTES, model.size)
            OrtWebWire.parseEnvelope(
                AiveOrt.writeBuffer(bufferId, offset, Base64.Default.encode(model, offset, end)),
            )
            offset = end
        }
        val info = OrtWebWire.decodeSessionInfo(
            AiveOrt.createSessionFromBuffer(
                bufferId,
                providers.joinToString(","),
                OrtWebExecutionProviders.powerPreference(preference),
            ).await().toString(),
        )
        return BrowserOrtSession(info)
    }

    internal suspend fun run(
        sessionId: String,
        inputs: Map<String, OrtTensor>,
        fetches: List<String>,
    ): Map<String, OrtTensor> =
        OrtWebWire.decodeRunResult(
            AiveOrt.run(sessionId, OrtWebWire.encodeRun(inputs, fetches)).await().toString(),
        )

    internal suspend fun release(sessionId: String) {
        OrtWebWire.parseEnvelope(AiveOrt.release(sessionId).await().toString())
    }
}

/** One loaded ONNX model. Runs on one session are queued by the shim (ORT Web forbids overlapping runs). */
class BrowserOrtSession internal constructor(private val info: OrtWebSessionInfo) {
    val inputNames: List<String> get() = info.inputNames
    val outputNames: List<String> get() = info.outputNames

    /** The provider order requested; ORT Web does not report which one executed. */
    val requestedProviders: List<String> get() = info.providers

    private var released = false

    /** Runs the model with named input tensors; [fetches] limits which outputs are copied back (default: all). */
    suspend fun run(
        inputs: Map<String, OrtTensor>,
        fetches: List<String> = emptyList(),
    ): Map<String, OrtTensor> {
        check(!released) { "ONNX session is released" }
        return BrowserOrtRuntime.run(info.sessionId, inputs, fetches)
    }

    suspend fun release() {
        if (released) return
        released = true
        BrowserOrtRuntime.release(info.sessionId)
    }
}

class BrowserOrtHardwareCapabilityDetector(
    private val preference: MemoryComputePreference = MemoryComputePreference.AUTO,
) : HardwareCapabilityDetector {
    override suspend fun discover(): List<MemoryComputeDevice> {
        val devices = mutableListOf(
            MemoryComputeDevice(
                backend = OrtWebExecutionProviders.WASM,
                deviceName = "WebAssembly CPU",
                deviceType = MemoryComputeDeviceType.CPU,
            ),
        )
        if (BrowserOrtRuntime.webGpuAvailable()) {
            val adapter = runCatching { BrowserOrtRuntime.adapterInfo(preference) }.getOrNull()
            if (adapter != null) {
                devices.add(
                    0,
                    MemoryComputeDevice(
                        backend = OrtWebExecutionProviders.WEBGPU,
                        deviceName = adapter.description ?: "WebGPU adapter",
                        deviceType = MemoryComputeDeviceType.GPU,
                        metadata = buildMap {
                            adapter.vendor?.let { put("vendor", it) }
                            adapter.architecture?.let { put("architecture", it) }
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
    val session: BrowserOrtSession,
    val selection: MemoryComputeSelection,
) {
    private var runSucceeded = false

    internal fun recordSuccessfulRun() {
        runSucceeded = true
    }

    fun executionReport(): MemoryExecutionReport = ortWebExecutionReport(selection, runSucceeded)
}

/** Ranks the discovered devices, tries WebGPU then WASM, caches the first session that accepts the model. */
class BrowserOrtMemorySessionManager(
    val computePreference: MemoryComputePreference = MemoryComputePreference.AUTO,
    val capabilityDetector: BrowserOrtHardwareCapabilityDetector =
        BrowserOrtHardwareCapabilityDetector(computePreference),
) {
    val platform: MemoryMicroAgentPlatform = MemoryMicroAgentPlatform.Web
    private val sessions = mutableMapOf<String, BrowserOrtPreparedSession>()
    private val lock = Mutex()

    suspend fun sessionFor(
        modelUri: String,
        model: MemoryMicroAgentModelSpec,
    ): BrowserOrtPreparedSession = lock.withLock {
        val key = listOf(model.modelId, modelUri, computePreference.name, model.requirements.workload.name)
            .joinToString("|")
        sessions[key]?.let { return@withLock it }

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
            val cpu = discovered.firstOrNull { it.deviceType == MemoryComputeDeviceType.CPU }
            if (selected.device.deviceType == MemoryComputeDeviceType.CPU || !model.requirements.allowCpuFallback || cpu == null) {
                throw acceleratorFailure
            }
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
        prepared
    }

    private suspend fun createSession(
        modelUri: String,
        selection: MemoryComputeSelection,
    ): BrowserOrtPreparedSession {
        val session = BrowserOrtRuntime.createSession(
            modelUri = modelUri,
            providers = OrtWebExecutionProviders.forSelection(selection),
            preference = computePreference,
        )
        return BrowserOrtPreparedSession(session, selection)
    }

    suspend fun close() {
        lock.withLock {
            sessions.values.forEach { prepared -> runCatching { prepared.session.release() } }
            sessions.clear()
        }
    }
}

fun interface BrowserOrtGenerativeModelAdapter {
    suspend fun generate(session: BrowserOrtSession, request: MemoryGenerativeInferenceRequest): String
}

fun interface BrowserOrtEmbeddingModelAdapter {
    suspend fun embed(session: BrowserOrtSession, request: MemoryEmbeddingInferenceRequest): List<List<Float>>
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
        BrowserOrtRuntime.isShimLoaded() &&
        artifactResolver.isAvailable(artifact)

    override suspend fun generate(request: MemoryGenerativeInferenceRequest): MemoryGenerativeInferenceResult {
        val uri = artifactResolver.resolveUri(request.artifact)
        val prepared = sessionManager.sessionFor(uri, request.model)
        val text = modelAdapter.generate(prepared.session, request)
        prepared.recordSuccessfulRun()
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
        BrowserOrtRuntime.isShimLoaded() &&
        artifactResolver.isAvailable(artifact)

    override suspend fun embed(request: MemoryEmbeddingInferenceRequest): MemoryEmbeddingInferenceResult {
        val uri = artifactResolver.resolveUri(request.artifact)
        val prepared = sessionManager.sessionFor(uri, request.model)
        val vectors = modelAdapter.embed(prepared.session, request)
        prepared.recordSuccessfulRun()
        val report = prepared.executionReport()
        reports[request.model.modelId] = report
        return MemoryEmbeddingInferenceResult(vectors, report)
    }

    override suspend fun executionReport(modelId: String): MemoryExecutionReport? = reports[modelId]
}
