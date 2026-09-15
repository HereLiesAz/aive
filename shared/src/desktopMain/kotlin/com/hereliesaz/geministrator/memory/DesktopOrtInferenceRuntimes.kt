package com.hereliesaz.geministrator.memory

import ai.onnxruntime.OrtSession
import java.io.File
import java.util.concurrent.ConcurrentHashMap

interface DesktopOrtArtifactResolver {
    suspend fun isAvailable(artifact: MemoryMicroAgentArtifact): Boolean
    suspend fun resolvePath(artifact: MemoryMicroAgentArtifact): String
}

class DesktopFileOrtArtifactResolver(
    private val paths: Map<String, String>,
) : DesktopOrtArtifactResolver {
    override suspend fun isAvailable(artifact: MemoryMicroAgentArtifact): Boolean =
        paths[artifact.artifactId]?.let(::File)?.isFile == true

    override suspend fun resolvePath(artifact: MemoryMicroAgentArtifact): String =
        paths[artifact.artifactId]
            ?.takeIf { File(it).isFile }
            ?: error("No local ONNX artifact for ${artifact.artifactId}")
}

fun interface DesktopOrtGenerativeModelAdapter {
    suspend fun generate(session: OrtSession, request: MemoryGenerativeInferenceRequest): String
}

fun interface DesktopOrtEmbeddingModelAdapter {
    suspend fun embed(session: OrtSession, request: MemoryEmbeddingInferenceRequest): List<List<Float>>
}

class DesktopOrtGenerativeInferenceRuntime(
    private val sessionManager: DesktopOrtMemorySessionManager,
    private val artifactResolver: DesktopOrtArtifactResolver,
    private val modelAdapter: DesktopOrtGenerativeModelAdapter,
    private val providerOptions: (MemoryMicroAgentModelSpec) -> Map<String, String> = { emptyMap() },
) : MemoryGenerativeInferenceRuntime {
    override val platform: MemoryMicroAgentPlatform get() = sessionManager.platform
    override val capabilityDetector: HardwareCapabilityDetector get() = sessionManager.capabilityDetector
    override val computePreference: MemoryComputePreference get() = sessionManager.computePreference
    private val reports = ConcurrentHashMap<String, MemoryExecutionReport>()

    override suspend fun isAvailable(
        model: MemoryMicroAgentModelSpec,
        artifact: MemoryMicroAgentArtifact,
    ): Boolean = artifact.platform == platform &&
        artifact.backend == MemoryInferenceBackend.OnnxRuntimeJvm &&
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

class DesktopOrtEmbeddingInferenceRuntime(
    private val sessionManager: DesktopOrtMemorySessionManager,
    private val artifactResolver: DesktopOrtArtifactResolver,
    private val modelAdapter: DesktopOrtEmbeddingModelAdapter,
    private val providerOptions: (MemoryMicroAgentModelSpec) -> Map<String, String> = { emptyMap() },
) : MemoryEmbeddingInferenceRuntime {
    override val platform: MemoryMicroAgentPlatform get() = sessionManager.platform
    override val capabilityDetector: HardwareCapabilityDetector get() = sessionManager.capabilityDetector
    override val computePreference: MemoryComputePreference get() = sessionManager.computePreference
    private val reports = ConcurrentHashMap<String, MemoryExecutionReport>()

    override suspend fun isAvailable(
        model: MemoryMicroAgentModelSpec,
        artifact: MemoryMicroAgentArtifact,
    ): Boolean = artifact.platform == platform &&
        artifact.backend == MemoryInferenceBackend.OnnxRuntimeJvm &&
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
