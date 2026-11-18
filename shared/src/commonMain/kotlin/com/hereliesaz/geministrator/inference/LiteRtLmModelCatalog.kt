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

    /**
     * The merged full model the app-integration notebook's LiteRT-LM export produces (the adapter folded into
     * Qwen2.5-0.5B-Instruct), pinned from the `app-integration-v1` release (file name, URL, size and SHA-256 are the release asset's own; the
     * notebook's smoke test on 50 test rows matched Gate 1 exactly, but no device trial has been run). LiteRT-LM has no adapter-as-input path, so this is a second model
     * file, not an adapter. Nothing is guessed: change these values only from a release. When it is pinned and installed the runtime
     * runs it instead of [qwen25Instruct05b] ([LiteRtLmModelSelection]); otherwise the stock model runs.
     */
    val adapterModel: LiteRtLmModelArtifact? = LiteRtLmModelArtifact(
        id = "litertlm:qwen2.5-0.5b-instruct:app-integration:q8",
        displayName = "Qwen2.5-0.5B-Instruct, app-integration (LiteRT-LM)",
        foundationModelId = "Qwen/Qwen2.5-0.5B-Instruct",
        fileName = "Qwen2.5-0.5B-Instruct-aive-app-integration_multi-prefill-seq_q8_ekv1280-v1.litertlm",
        downloadUrl = "https://github.com/HereLiesAz/aive/releases/download/app-integration-v1/" +
            "Qwen2.5-0.5B-Instruct-aive-app-integration_multi-prefill-seq_q8_ekv1280-v1.litertlm",
        sizeBytes = 528_255_232L,
        sha256 = "6f2ccf749a8ebab4a08a0d17396fe0df0bce9936da37b9f36c88fba159e0dab3",
        format = "litertlm",
        precision = "int8",
        license = "Apache-2.0",
    )

    val all: List<LiteRtLmModelArtifact> = listOfNotNull(qwen25Instruct05b, adapterModel)

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

/** The LiteRT-LM model file a generation loads, and whether it is the merged app-integration model. */
data class LiteRtLmModelChoice(val path: String, val adapterModel: Boolean)

/** The pure choice between the merged app-integration model and the stock one. */
object LiteRtLmModelSelection {
    /**
     * The merged model's path when [adapterModel] is pinned and installed ([adapterModelPath] is non-null),
     * else the stock model's path, else null. A pinned but missing merged model never blocks the stock one.
     */
    fun choose(
        adapterModel: LiteRtLmModelArtifact?,
        adapterModelPath: () -> String?,
        stockModelPath: () -> String?,
    ): LiteRtLmModelChoice? {
        if (adapterModel != null) {
            adapterModelPath()?.let { return LiteRtLmModelChoice(it, adapterModel = true) }
        }
        return stockModelPath()?.let { LiteRtLmModelChoice(it, adapterModel = false) }
    }
}
