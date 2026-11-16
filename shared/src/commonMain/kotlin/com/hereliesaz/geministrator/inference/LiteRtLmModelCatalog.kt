package com.hereliesaz.geministrator.inference

/**
 * A model file the LiteRT-LM runtime runs, hosted by its publisher and pinned by revision, size and
 * SHA-256. The installers refuse a download whose digest differs; nothing here is trust-on-first-use.
 */
data class LiteRtLmModelArtifact(
    val id: String,
    val displayName: String,
    val foundationModelId: String,
    val fileName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val sha256: String,
    /** Container format: `task` (MediaPipe bundle LiteRT-LM still reads) or `litertlm`. */
    val format: String,
    val precision: String,
    val license: String,
) {
    init {
        require(id.isNotBlank() && displayName.isNotBlank() && fileName.isNotBlank()) { "model identity must not be blank" }
        require(downloadUrl.startsWith("https://")) { "model downloads must use https" }
        require(sizeBytes > 0L) { "sizeBytes must be positive" }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "sha256 must be a lowercase SHA-256 digest" }
    }
}

object LiteRtLmModelCatalog {
    /**
     * Qwen2.5-0.5B-Instruct, q8, 1280-token KV cache, from Google's `litert-community` repository at the
     * commit below (Apache-2.0). The digest was computed on the full download, and equals the Hub's LFS id.
     * The repository has no `.litertlm` file for this model; the `.task` bundle is the published form.
     */
    val qwen25Instruct05b = LiteRtLmModelArtifact(
        id = "litertlm:qwen2.5-0.5b-instruct:q8",
        displayName = "Qwen2.5-0.5B-Instruct (LiteRT-LM)",
        foundationModelId = "Qwen/Qwen2.5-0.5B-Instruct",
        fileName = "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
        downloadUrl = "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/" +
            "49bfd323c25c9dae2118906e1f3ffd16e0729b13/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
        sizeBytes = 546_660_344L,
        sha256 = "e608953f169aeb1bd7b9155fec2559825e08453fc209b84eda3a781ed0452fd2",
        format = "task",
        precision = "int8",
        license = "Apache-2.0",
    )

    val all: List<LiteRtLmModelArtifact> = listOf(qwen25Instruct05b)

    fun byId(id: String): LiteRtLmModelArtifact? = all.firstOrNull { it.id == id }
}

/** Planner capability ids of the LiteRT-LM runtime, beside `android-onnxruntime` and `desktop-onnxruntime`. */
object LiteRtLmRuntime {
    const val ANDROID_RUNTIME_ID: String = "android-litert-lm"
    const val DESKTOP_RUNTIME_ID: String = "desktop-litert-lm"

    /** Standalone, non-adapter models only: LiteRT-LM has no adapter-as-input path. */
    fun capabilities(runtimeId: String): LocalModelRuntimeCapabilities = LocalModelRuntimeCapabilities(
        runtimeId = runtimeId,
        supportedFormats = setOf("litertlm", "task"),
        supportedPrecisions = setOf("int8"),
        supportsSharedBaseAdapters = false,
    )
}
