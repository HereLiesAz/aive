package com.hereliesaz.geministrator.memory

import ai.onnxruntime.OrtSession
import java.io.File
import java.util.concurrent.ConcurrentHashMap

interface AndroidOrtArtifactResolver {
    suspend fun isAvailable(artifact: MemoryMicroAgentArtifact): Boolean
    suspend fun resolvePath(artifact: MemoryMicroAgentArtifact): String
}

class AndroidFileOrtArtifactResolver(
    private val paths: Map<String, String>,
) : AndroidOrtArtifactResolver {
    override suspend fun isAvailable(artifact: MemoryMicroAgentArtifact): Boolean =
        paths[artifact.artifactId]?.let(::File)?.isFile == true

    override suspend fun resolvePath(artifact: MemoryMicroAgentArtifact): String =
        paths[artifact.artifactId]
            ?.takeIf { File(it).isFile }
            ?: error("No local ONNX artifact for ${artifact.artifactId}")
}

fun interface AndroidOrtGenerativeModelAdapter {
    suspend fun generate(session: OrtSession, request: MemoryGenerativeInferenceRequest): String
}

fun interface AndroidOrtEmbeddingModelAdapter {
    suspend fun embed(session: OrtSession, request: MemoryEmbeddingInferenceRequest): List<List<Float>>
}

class AndroidOrtGenerativeInferenceRuntime(
    private val sessionManager: AndroidOrtMemorySessionManager,
    private val artifactResolver: AndroidOrtArtifactResolver,
    private val modelAdapter: AndroidOrtGenerativeModelAdapter,
    private val providerOptions: (MemoryMicroAgentModelSpec) -> Map<String, String> = { emptyMap() },
) : MemoryGenerativeInferenceRuntime {
    override val platform = MemoryMicroAgentPlatform.Android
    override val capabilityDetector: HardwareCapabilityDetector get() = sessionManager.capabilityDetector
    override val computePreference: MemoryComputePreference get() = sessionManager.computePreference
    private val reports = ConcurrentHashMap<String, MemoryExecutionReport>()

    override suspend fun isAvailable(
        model: MemoryMicroAgentModelSpec,
        artifact: MemoryMicroAgentArtifact,
    ): Boolean = artifact.platform == platform &&
        artifact.backend == MemoryInferenceBackend.OnnxRuntimeAndroid &&
        artifactResolver.isAvailable(artifact) &&
        capabilityDetector.discover().any(MemoryComputeDevice::available)

    override suspend fun generate(request: MemoryGenerativeInferenceRequest): MemoryGenerativeInferenceResult {
        require(request.artifact.platform == platform)
        val path = artifactResolver.resolvePath(request.artifact)
        val prepared = sessionManager.sessionFor(path, request.model, providerOptions(request.model))
        val text = modelAdapter.generate(prepared.session, request)
        val report = prepared.captureExecutionProfile()
        reports[request.model.modelId] = report
        return MemoryGenerativeInferenceResult(text = text, execution = report)
    }

    override suspend fun executionReport(modelId: String): MemoryExecutionReport? = reports[modelId]
}

class AndroidOrtEmbeddingInferenceRuntime(
    private val sessionManager: AndroidOrtMemorySessionManager,
    private val artifactResolver: AndroidOrtArtifactResolver,
    private val modelAdapter: AndroidOrtEmbeddingModelAdapter,
    private val providerOptions: (MemoryMicroAgentModelSpec) -> Map<String, String> = { emptyMap() },
) : MemoryEmbeddingInferenceRuntime {
    override val platform = MemoryMicroAgentPlatform.Android
    override val capabilityDetector: HardwareCapabilityDetector get() = sessionManager.capabilityDetector
    override val computePreference: MemoryComputePreference get() = sessionManager.computePreference
    private val reports = ConcurrentHashMap<String, MemoryExecutionReport>()

    override suspend fun isAvailable(
        model: MemoryMicroAgentModelSpec,
        artifact: MemoryMicroAgentArtifact,
    ): Boolean = artifact.platform == platform &&
        artifact.backend == MemoryInferenceBackend.OnnxRuntimeAndroid &&
        artifactResolver.isAvailable(artifact) &&
        capabilityDetector.discover().any(MemoryComputeDevice::available)

    override suspend fun embed(request: MemoryEmbeddingInferenceRequest): MemoryEmbeddingInferenceResult {
        require(request.artifact.platform == platform)
        val path = artifactResolver.resolvePath(request.artifact)
        val prepared = sessionManager.sessionFor(path, request.model, providerOptions(request.model))
        val vectors = modelAdapter.embed(prepared.session, request)
        val report = prepared.captureExecutionProfile()
        reports[request.model.modelId] = report
        return MemoryEmbeddingInferenceResult(vectors = vectors, execution = report)
    }

    override suspend fun executionReport(modelId: String): MemoryExecutionReport? = reports[modelId]
}
