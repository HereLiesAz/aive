package com.hereliesaz.geministrator.memory

/** Every production memory micro-agent must have a local deployment path on every Haive client. */
enum class MemoryMicroAgentPlatform {
    Android,
    Windows,
    MacOS,
    Linux,
    Web,
}

enum class MemoryModelArtifactFormat {
    /** Portable baseline for ONNX Runtime on Android, desktop, and browser. */
    Onnx,
    /** ONNX Runtime optimized format where a target benefits from a reduced operator/runtime set. */
    Ort,
    /** Optional native fallback/optimization for llama.cpp-compatible platform runtimes. */
    Gguf,
    /** Browser/native WebGPU-specific compiled artifact when an ONNX export is not optimal. */
    WebGpu,
}

enum class MemoryInferenceBackend {
    OnnxRuntimeAndroid,
    OnnxRuntimeJvm,
    OnnxRuntimeNative,
    OnnxRuntimeWebWasm,
    OnnxRuntimeWebGpu,
    LlamaCpp,
    BrowserWebGpu,
}

data class MemoryMicroAgentArtifact(
    val platform: MemoryMicroAgentPlatform,
    val format: MemoryModelArtifactFormat,
    val backend: MemoryInferenceBackend,
    /** Stable artifact/model identifier. It may resolve to a bundled resource or local cache key. */
    val artifactId: String,
    /** Optional integrity value produced by the training/export pipeline. */
    val sha256: String? = null,
    /** Quantization of this deployment artifact, e.g. int8, int4, q4_k_m. */
    val quantization: String? = null,
    /** True when the role-specific adapter has already been merged into the deployment model. */
    val adapterMerged: Boolean = true,
) {
    init {
        require(artifactId.isNotBlank())
        require(sha256 == null || sha256.isNotBlank())
    }
}

data class MemoryMicroAgentDeploymentManifest(
    val artifacts: List<MemoryMicroAgentArtifact>,
) {
    init {
        require(artifacts.isNotEmpty())
        require(artifacts.distinctBy { it.platform to it.backend }.size == artifacts.size) {
            "Memory micro-agent deployment entries must be unique per platform/backend"
        }
    }

    val platforms: Set<MemoryMicroAgentPlatform> get() = artifacts.mapTo(linkedSetOf()) { it.platform }

    fun supportsAllHaivePlatforms(): Boolean = REQUIRED_PLATFORMS.all { it in platforms }

    fun artifactsFor(platform: MemoryMicroAgentPlatform): List<MemoryMicroAgentArtifact> =
        artifacts.filter { it.platform == platform }

    companion object {
        val REQUIRED_PLATFORMS: Set<MemoryMicroAgentPlatform> = MemoryMicroAgentPlatform.entries.toSet()

        /**
         * Portable deployment baseline expected from the Kaggle export pipeline. A platform may
         * add optimized alternatives, but these entries keep every client locally functional.
         */
        fun portableOnnx(
            artifactId: String,
            quantization: String? = "int8",
            sha256: String? = null,
        ): MemoryMicroAgentDeploymentManifest = MemoryMicroAgentDeploymentManifest(
            artifacts = listOf(
                MemoryMicroAgentArtifact(
                    platform = MemoryMicroAgentPlatform.Android,
                    format = MemoryModelArtifactFormat.Onnx,
                    backend = MemoryInferenceBackend.OnnxRuntimeAndroid,
                    artifactId = artifactId,
                    sha256 = sha256,
                    quantization = quantization,
                ),
                MemoryMicroAgentArtifact(
                    platform = MemoryMicroAgentPlatform.Windows,
                    format = MemoryModelArtifactFormat.Onnx,
                    backend = MemoryInferenceBackend.OnnxRuntimeJvm,
                    artifactId = artifactId,
                    sha256 = sha256,
                    quantization = quantization,
                ),
                MemoryMicroAgentArtifact(
                    platform = MemoryMicroAgentPlatform.MacOS,
                    format = MemoryModelArtifactFormat.Onnx,
                    backend = MemoryInferenceBackend.OnnxRuntimeNative,
                    artifactId = artifactId,
                    sha256 = sha256,
                    quantization = quantization,
                ),
                MemoryMicroAgentArtifact(
                    platform = MemoryMicroAgentPlatform.Linux,
                    format = MemoryModelArtifactFormat.Onnx,
                    backend = MemoryInferenceBackend.OnnxRuntimeJvm,
                    artifactId = artifactId,
                    sha256 = sha256,
                    quantization = quantization,
                ),
                MemoryMicroAgentArtifact(
                    platform = MemoryMicroAgentPlatform.Web,
                    format = MemoryModelArtifactFormat.Onnx,
                    backend = MemoryInferenceBackend.OnnxRuntimeWebWasm,
                    artifactId = artifactId,
                    sha256 = sha256,
                    quantization = quantization,
                ),
            ),
        )
    }
}
