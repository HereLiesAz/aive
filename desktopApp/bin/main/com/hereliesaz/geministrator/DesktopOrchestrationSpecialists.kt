package com.hereliesaz.geministrator

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import com.hereliesaz.geministrator.inference.LocalModelArtifactDescriptor
import com.hereliesaz.geministrator.inference.LocalModelArtifactKind
import com.hereliesaz.geministrator.inference.LocalModelLoadPlan
import com.hereliesaz.geministrator.inference.LocalModelRuntimeCapabilities
import com.hereliesaz.geministrator.memory.DesktopOrtMemorySessionManager
import com.hereliesaz.geministrator.memory.MemoryMicroAgentModelSpec
import com.hereliesaz.geministrator.orchestration.CatalogBackedLocalOrchestrationSpecialistRuntime
import com.hereliesaz.geministrator.orchestration.DeterministicLocalOrchestrationUtilities
import com.hereliesaz.geministrator.orchestration.GuardedModelBackedOrchestrationUtilities
import com.hereliesaz.geministrator.orchestration.LocalOrchestrationModelExecutor
import com.hereliesaz.geministrator.orchestration.LocalOrchestrationUtilityFamily
import com.hereliesaz.geministrator.orchestration.OrchestrationSpecialistCatalog
import com.hereliesaz.geministrator.orchestration.OrchestrationSpecialistPrompts
import com.hereliesaz.geministrator.orchestration.OrchestrationUtilityRole
import io.ktor.client.HttpClient
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Local orchestration specialists on desktop.
 *
 * Off unless `AIVE_LOCAL_ORCHESTRATION_SPECIALISTS=1` (or `-Daive.localOrchestrationSpecialists=true`).
 * Even when on, a role only uses a model that is released in [OrchestrationSpecialistCatalog] and
 * installed with [DesktopOrchestrationSpecialistInstaller]; inference never downloads. Every answer
 * still passes [GuardedModelBackedOrchestrationUtilities], so the deterministic baseline remains the
 * floor.
 *
 * `AIVE_ORCHESTRATION_SPECIALIST_MODE` (or `-Daive.orchestrationSpecialistMode`) picks the release shape
 * when a catalog offers both:
 * - `merged` (default): one multi-task model serves every role.
 * - `adapters`: one shared base plus a small LoRA file per role. A role with no adapter falls back to
 *   the merged model.
 */
internal object DesktopOrchestrationSpecialists {
    val enabled: Boolean
        get() = System.getenv("AIVE_LOCAL_ORCHESTRATION_SPECIALISTS") == "1" ||
            System.getProperty("aive.localOrchestrationSpecialists") == "true"

    val adaptersMode: Boolean
        get() = (System.getenv("AIVE_ORCHESTRATION_SPECIALIST_MODE")
            ?: System.getProperty("aive.orchestrationSpecialistMode")) == "adapters"

    val runtimeCapabilities: LocalModelRuntimeCapabilities
        get() = if (adaptersMode) {
            LocalModelRuntimeCapabilities(
                runtimeId = "desktop-onnxruntime",
                supportedFormats = setOf("onnx", "safetensors"),
                supportedPrecisions = setOf("int8", "fp16"),
                supportsSharedBaseAdapters = true,
            )
        } else {
            LocalModelRuntimeCapabilities(
                runtimeId = "desktop-onnxruntime",
                supportedFormats = setOf("onnx"),
                supportedPrecisions = setOf("int8"),
            )
        }

    fun utilities(installer: DesktopOrchestrationSpecialistInstaller): LocalOrchestrationUtilityFamily =
        if (!enabled) {
            DeterministicLocalOrchestrationUtilities
        } else {
            GuardedModelBackedOrchestrationUtilities(
                runtime = CatalogBackedLocalOrchestrationSpecialistRuntime(
                    library = OrchestrationSpecialistCatalog.released(),
                    runtimeCapabilities = runtimeCapabilities,
                    executor = DesktopLocalOrchestrationModelExecutor(installer),
                ),
            )
        }
}

/**
 * Installs released specialists under ~/.aive/models/orchestration-utilities on explicit request.
 * Model archives are verified against the catalog's SHA-256 before they are unpacked; adapters are
 * single verified `.safetensors` files under `adapters/`.
 */
internal class DesktopOrchestrationSpecialistInstaller(
    httpClient: HttpClient,
    private val installRoot: File = File(System.getProperty("user.home"), ".aive/models/orchestration-utilities"),
) {
    private val mutex = Mutex()
    private val downloader = DesktopResumableFileDownloader(httpClient)

    /** The unpacked model directory for [artifact], or null. Cheap: compares the recorded digest only. */
    fun installed(artifact: LocalModelArtifactDescriptor): File? {
        if (artifact.kind == LocalModelArtifactKind.Adapter) {
            val file = adapterFile(artifact)
            val marker = File(file.path + ".sha256")
            return file.takeIf { it.isFile && marker.isFile && marker.readText().trim() == artifact.sha256 }
        }
        val dir = directory(artifact)
        val marker = File(dir, INSTALLED_MARKER)
        return dir.takeIf {
            marker.isFile && marker.readText().trim() == artifact.sha256 &&
                File(dir, "model.onnx").isFile && File(dir, "tokenizer.json").isFile && File(dir, "config.json").isFile
        }
    }

    suspend fun install(artifact: LocalModelArtifactDescriptor, onProgress: (DownloadProgress) -> Unit = {}): File =
        mutex.withLock {
            installed(artifact)?.let { return@withLock it }
            val staging = File(installRoot, ".staging").apply { mkdirs() }
            if (artifact.kind == LocalModelArtifactKind.Adapter) {
                val downloaded = downloader.downloadVerified(
                    url = artifact.downloadUrl,
                    output = File(staging, artifact.assetName),
                    expectedSha256 = artifact.sha256,
                    onProgress = onProgress,
                )
                val destination = adapterFile(artifact)
                destination.parentFile?.mkdirs()
                downloaded.copyTo(destination, overwrite = true)
                downloaded.delete()
                File(destination.path + ".sha256").writeText(artifact.sha256 + "\n")
                return@withLock installed(artifact) ?: error("${artifact.assetName} did not install")
            }
            val archive = downloader.downloadVerified(
                url = artifact.downloadUrl,
                output = File(staging, artifact.assetName),
                expectedSha256 = artifact.sha256,
                onProgress = onProgress,
            )
            val extracted = File(staging, "${artifact.logicalArtifactId.safeName()}.extracted").apply {
                deleteRecursively()
                mkdirs()
            }
            extractTarGzSafely(archive, extracted)
            File(extracted, INSTALLED_MARKER).writeText(artifact.sha256 + "\n")
            val destination = directory(artifact)
            destination.deleteRecursively()
            destination.parentFile?.mkdirs()
            if (!extracted.renameTo(destination)) extracted.copyRecursively(destination, overwrite = true)
            archive.delete()
            installed(artifact) ?: error("${artifact.assetName} has no model.onnx, tokenizer.json and config.json")
        }

    private fun directory(artifact: LocalModelArtifactDescriptor) = File(installRoot, artifact.logicalArtifactId.safeName())

    private fun adapterFile(artifact: LocalModelArtifactDescriptor) =
        File(installRoot, "adapters/${artifact.logicalArtifactId.safeName()}.safetensors")

    private fun String.safeName() = replace(Regex("[^A-Za-z0-9._-]"), "_")

    private companion object {
        const val INSTALLED_MARKER = ".installed-sha256"
    }
}

/**
 * Runs an installed specialist with ONNX Runtime; returns null when it is not installed.
 *
 * A shared-base plan loads the base session once for every role and feeds the role's LoRA weights as
 * graph inputs. The most recently used adapters stay in memory (about 35 MB each as fp32).
 */
internal class DesktopLocalOrchestrationModelExecutor(
    private val installer: DesktopOrchestrationSpecialistInstaller,
    private val sessions: DesktopOrtMemorySessionManager = DesktopOrtMemorySessionManager(),
    private val generator: DesktopOrtCausalGenerator = DesktopOrtCausalGenerator(),
) : LocalOrchestrationModelExecutor {
    private val adapters = object : LinkedHashMap<String, DesktopLoraAdapter>(ADAPTER_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DesktopLoraAdapter>): Boolean =
            (size > ADAPTER_CACHE_SIZE).also { evict -> if (evict) eldest.value.close() }
    }

    override fun generate(role: OrchestrationUtilityRole, plan: LocalModelLoadPlan, inputJson: String): String? =
        when (plan) {
            is LocalModelLoadPlan.MergedModel -> run(role, plan.model, inputJson, emptyMap())
            is LocalModelLoadPlan.SharedBaseAdapter -> {
                val file = installer.installed(plan.adapter) ?: return null
                // Serialized: an adapter's tensors must not be evicted while a run still reads them.
                synchronized(adapters) {
                    val adapter = adapters.getOrPut(plan.adapter.sha256) { DesktopLoraAdapter.load(file) }
                    val expectedBase = adapter.metadata["base"]
                    require(expectedBase == null || expectedBase == plan.base.logicalArtifactId) {
                        "${plan.adapter.assetName} was trained for $expectedBase, not ${plan.base.logicalArtifactId}"
                    }
                    run(role, plan.base, inputJson, adapter.tensors)
                }
            }
            else -> null
        }

    private fun run(
        role: OrchestrationUtilityRole,
        model: LocalModelArtifactDescriptor,
        inputJson: String,
        extraInputs: Map<String, ai.onnxruntime.OnnxTensor>,
    ): String? {
        val root = installer.installed(model) ?: return null
        // The executor contract is synchronous; ONNX Runtime inference blocks regardless.
        val prepared = runBlocking {
            sessions.sessionFor(
                File(root, "model.onnx").absolutePath,
                MemoryMicroAgentModelSpec(modelId = model.logicalArtifactId, quantization = model.precision),
            )
        }
        return HuggingFaceTokenizer.newInstance(root.toPath()).use { tokenizer ->
            generator.generate(
                session = prepared.session,
                tokenizer = tokenizer,
                modelRoot = root,
                prompt = chatPrompt(role, inputJson),
                maxNewTokens = MAX_NEW_TOKENS,
                extraInputs = extraInputs,
            )
        }
    }

    private companion object {
        const val MAX_NEW_TOKENS = 512
        const val ADAPTER_CACHE_SIZE = 3

        /** Qwen2.5 chat template, identical to what the training notebook applies. */
        fun chatPrompt(role: OrchestrationUtilityRole, inputJson: String) = buildString {
            append("<|im_start|>system\n").append(OrchestrationSpecialistPrompts.system(role)).append("<|im_end|>\n")
            append("<|im_start|>user\n").append(inputJson).append("<|im_end|>\n")
            append("<|im_start|>assistant\n")
        }
    }
}
