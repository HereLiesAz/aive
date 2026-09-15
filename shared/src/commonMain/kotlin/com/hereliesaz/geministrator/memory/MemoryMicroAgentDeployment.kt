package com.hereliesaz.geministrator.memory

/** Every production memory model must have a local deployment path on every Haive client. */
enum class MemoryMicroAgentPlatform {
    Android,
    Windows,
    MacOS,
    Linux,
    Web,
}

enum class MemoryModelArtifactFormat {
    Onnx,
    Ort,
}

/** Runtime family. Hardware execution providers are selected separately at session creation. */
enum class MemoryInferenceBackend {
    OnnxRuntimeAndroid,
    OnnxRuntimeJvm,
    OnnxRuntimeWeb,
}

data class MemoryMicroAgentArtifact(
    val platform: MemoryMicroAgentPlatform,
    val format: MemoryModelArtifactFormat,
    val backend: MemoryInferenceBackend,
    val artifactId: String,
    val sha256: String? = null,
    val quantization: String? = null,
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
            "Memory model deployment entries must be unique per platform/runtime"
        }
    }

    val platforms: Set<MemoryMicroAgentPlatform>
        get() = artifacts.mapTo(linkedSetOf()) { it.platform }

    fun supportsAllHaivePlatforms(): Boolean = REQUIRED_PLATFORMS.all { it in platforms }

    fun artifactsFor(platform: MemoryMicroAgentPlatform): List<MemoryMicroAgentArtifact> =
        artifacts.filter { it.platform == platform }

    companion object {
        val REQUIRED_PLATFORMS: Set<MemoryMicroAgentPlatform> = MemoryMicroAgentPlatform.entries.toSet()

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
                    backend = MemoryInferenceBackend.OnnxRuntimeJvm,
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
                    backend = MemoryInferenceBackend.OnnxRuntimeWeb,
                    artifactId = artifactId,
                    sha256 = sha256,
                    quantization = quantization,
                ),
            ),
        )
    }
}

fun MemoryMicroAgentPlatform.preferredExecutionProviders(): List<String> = when (this) {
    MemoryMicroAgentPlatform.Android -> listOf("NNAPI", "QNN", "WebGPU")
    MemoryMicroAgentPlatform.Windows -> listOf("WebGPU", "DML", "CUDA")
    MemoryMicroAgentPlatform.MacOS -> listOf("CoreML", "WebGPU")
    MemoryMicroAgentPlatform.Linux -> listOf("CUDA", "WebGPU", "ROCM", "MIGraphX")
    MemoryMicroAgentPlatform.Web -> listOf("WebGPU", "WASM")
}
