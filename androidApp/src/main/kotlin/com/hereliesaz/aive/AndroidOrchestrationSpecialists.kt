package com.hereliesaz.aive

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import com.hereliesaz.geministrator.inference.LocalModelArtifactDescriptor
import com.hereliesaz.geministrator.inference.LocalModelLoadPlan
import com.hereliesaz.geministrator.inference.LocalModelRuntimeCapabilities
import com.hereliesaz.geministrator.memory.AndroidOrtMemorySessionManager
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
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.nio.ShortBuffer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

internal object AndroidOrchestrationSpecialists {
    val runtimeCapabilities = LocalModelRuntimeCapabilities(
        runtimeId = "android-onnxruntime",
        supportedFormats = setOf("onnx"),
        supportedPrecisions = setOf("int8"),
    )

    fun releasedRoleCount(): Int = OrchestrationSpecialistCatalog.released().allSpecialists().size

    fun utilities(installer: AndroidOrchestrationSpecialistInstaller): LocalOrchestrationUtilityFamily =
        if (!installer.hasAnyInstalledReleasedArtifact()) {
            DeterministicLocalOrchestrationUtilities
        } else {
            GuardedModelBackedOrchestrationUtilities(
                runtime = CatalogBackedLocalOrchestrationSpecialistRuntime(
                    library = OrchestrationSpecialistCatalog.released(),
                    runtimeCapabilities = runtimeCapabilities,
                    executor = AndroidLocalOrchestrationModelExecutor(installer),
                ),
            )
        }
}

internal class AndroidOrchestrationSpecialistInstaller(
    context: Context,
    httpClient: HttpClient,
) {
    private val installRoot = File(context.filesDir, "haive/orchestration-specialists")
    private val downloader = AndroidResumableFileDownloader(httpClient)
    private val mutex = Mutex()

    fun releasedArtifacts(): List<LocalModelArtifactDescriptor> {
        val library = OrchestrationSpecialistCatalog.released()
        return library.allSpecialists().mapNotNull { specialist ->
            runCatching {
                when (val plan = library.plan(specialist.specialistId, AndroidOrchestrationSpecialists.runtimeCapabilities)) {
                    is LocalModelLoadPlan.MergedModel -> plan.model
                    is LocalModelLoadPlan.Standalone -> plan.model
                    is LocalModelLoadPlan.SharedBaseAdapter -> null
                }
            }.getOrNull()
        }.distinctBy(LocalModelArtifactDescriptor::logicalArtifactId)
    }

    fun hasAnyInstalledReleasedArtifact(): Boolean = releasedArtifacts().any { installed(it) != null }

    fun allReleasedInstalled(): Boolean {
        val artifacts = releasedArtifacts()
        return artifacts.isNotEmpty() && artifacts.all { installed(it) != null }
    }

    fun installed(artifact: LocalModelArtifactDescriptor): File? {
        val dir = directory(artifact)
        val marker = File(dir, INSTALLED_MARKER)
        return dir.takeIf {
            marker.isFile &&
                marker.readText().trim() == artifact.sha256 &&
                File(dir, "model.onnx").isFile &&
                File(dir, "tokenizer.json").isFile &&
                File(dir, "config.json").isFile
        }
    }

    suspend fun installReleased(onProgress: (String) -> Unit = {}) = mutex.withLock {
        val artifacts = releasedArtifacts()
        require(artifacts.isNotEmpty()) { "No released orchestration specialists are compatible with Android" }
        artifacts.forEachIndexed { artifactIndex, artifact ->
            if (installed(artifact) != null) return@forEachIndexed
            onProgress("Downloading ${artifactIndex + 1}/${artifacts.size}: ${artifact.assetName}")
            val staging = File(installRoot, ".staging").apply { mkdirs() }
            val archive = File(staging, artifact.assetName)
            downloader.downloadVerified(
                url = artifact.downloadUrl,
                output = archive,
                expectedSha256 = artifact.sha256,
            )
            onProgress("Installing ${artifact.assetName}")
            val extracted = File(staging, "${artifact.logicalArtifactId.safeName()}.extracted").apply {
                deleteRecursively()
                mkdirs()
            }
            extractTarGzSafely(archive, extracted)
            require(File(extracted, "model.onnx").isFile) { "Archive has no model.onnx" }
            require(File(extracted, "tokenizer.json").isFile) { "Archive has no tokenizer.json" }
            require(File(extracted, "config.json").isFile) { "Archive has no config.json" }
            File(extracted, INSTALLED_MARKER).writeText(artifact.sha256 + "\n")
            val destination = directory(artifact)
            destination.deleteRecursively()
            destination.parentFile?.mkdirs()
            if (!extracted.renameTo(destination)) {
                extracted.copyRecursively(destination, overwrite = true)
                extracted.deleteRecursively()
            }
            archive.delete()
            check(installed(artifact) != null) { "Orchestration specialist did not install correctly" }
        }
        onProgress("Installed ${artifacts.size} released orchestration model artifact(s)")
    }

    suspend fun removeReleased() = mutex.withLock {
        releasedArtifacts().forEach { directory(it).deleteRecursively() }
        File(installRoot, ".staging").deleteRecursively()
    }

    private fun directory(artifact: LocalModelArtifactDescriptor) =
        File(installRoot, artifact.logicalArtifactId.safeName())

    private fun String.safeName() = replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun extractTarGzSafely(archive: File, destination: File) {
        val root = destination.canonicalFile
        TarArchiveInputStream(
            GzipCompressorInputStream(BufferedInputStream(FileInputStream(archive))),
        ).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val output = File(root, entry.name).canonicalFile
                check(output.path == root.path || output.path.startsWith(root.path + File.separator)) {
                    "Unsafe archive entry"
                }
                when {
                    entry.isDirectory -> output.mkdirs()
                    entry.isFile -> {
                        output.parentFile?.mkdirs()
                        BufferedOutputStream(FileOutputStream(output)).use { stream -> tar.copyTo(stream) }
                    }
                }
            }
        }
    }

    private companion object {
        const val INSTALLED_MARKER = ".installed-sha256"
    }
}

internal class AndroidLocalOrchestrationModelExecutor(
    private val installer: AndroidOrchestrationSpecialistInstaller,
    private val sessions: AndroidOrtMemorySessionManager = AndroidOrtMemorySessionManager(),
) : LocalOrchestrationModelExecutor {
    private val generator = AndroidOrchestrationCausalGenerator()

    override fun generate(
        role: OrchestrationUtilityRole,
        plan: LocalModelLoadPlan,
        inputJson: String,
    ): String? {
        val artifact = when (plan) {
            is LocalModelLoadPlan.MergedModel -> plan.model
            is LocalModelLoadPlan.Standalone -> plan.model
            is LocalModelLoadPlan.SharedBaseAdapter -> return null
        }
        val root = installer.installed(artifact) ?: return null
        val prepared = runBlocking {
            sessions.sessionFor(
                modelPath = File(root, "model.onnx").absolutePath,
                model = MemoryMicroAgentModelSpec(
                    modelId = artifact.logicalArtifactId,
                    quantization = artifact.precision,
                ),
            )
        }
        return HuggingFaceTokenizer.newInstance(root.toPath()).use { tokenizer ->
            generator.generate(
                session = prepared.session,
                tokenizer = tokenizer,
                modelRoot = root,
                prompt = buildString {
                    append("<|im_start|>system\n")
                    append(OrchestrationSpecialistPrompts.system(role))
                    append("<|im_end|>\n<|im_start|>user\n")
                    append(inputJson)
                    append("<|im_end|>\n<|im_start|>assistant\n")
                },
                maxNewTokens = 512,
            )
        }
    }
}

private class AndroidOrchestrationCausalGenerator(
    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun generate(
        session: OrtSession,
        tokenizer: HuggingFaceTokenizer,
        modelRoot: File,
        prompt: String,
        maxNewTokens: Int,
    ): String {
        val config = loadModelConfig(modelRoot)
        val promptIds = tokenizer.encode(prompt).ids
        require(promptIds.isNotEmpty()) { "Tokenizer returned no prompt tokens" }
        val stopIds = listOf("<|im_end|>", "<|endoftext|>")
            .mapNotNull { token -> tokenizer.encode(token).ids.singleOrNull() }
            .toSet()
        val generated = mutableListOf<Long>()
        var totalLength = promptIds.size
        var activeResult: OrtSession.Result? = null
        var stopped = false
        try {
            val owned = mutableListOf<OnnxTensor>()
            activeResult = try {
                session.run(buildInputs(session, promptIds, totalLength, null, config, owned))
            } finally {
                owned.forEach(OnnxTensor::close)
            }
            repeat(maxNewTokens) {
                if (stopped) return@repeat
                val current = requireNotNull(activeResult)
                val nextToken = argmaxLastLogit(current)
                if (nextToken in stopIds) {
                    stopped = true
                    return@repeat
                }
                generated += nextToken
                totalLength += 1
                val nextOwned = mutableListOf<OnnxTensor>()
                val nextResult = try {
                    session.run(buildInputs(session, longArrayOf(nextToken), totalLength, current, config, nextOwned))
                } finally {
                    nextOwned.forEach(OnnxTensor::close)
                }
                current.close()
                activeResult = nextResult
            }
        } finally {
            activeResult?.close()
        }
        return tokenizer.decode(generated.toLongArray(), true)
    }

    private fun buildInputs(
        session: OrtSession,
        tokenIds: LongArray,
        totalLength: Int,
        previousResult: OrtSession.Result?,
        config: ModelConfig,
        owned: MutableList<OnnxTensor>,
    ): Map<String, OnnxTensor> {
        val inputs = linkedMapOf<String, OnnxTensor>()
        session.inputInfo.forEach { (name, nodeInfo) ->
            val tensorInfo = nodeInfo.info as? TensorInfo ?: error("Unsupported non-tensor model input")
            val borrowed = name.startsWith("past_key_values.") && previousResult != null
            val tensor = when {
                name == "input_ids" -> longTensor(tokenIds, longArrayOf(1, tokenIds.size.toLong()))
                name == "attention_mask" -> longTensor(LongArray(totalLength) { 1L }, longArrayOf(1, totalLength.toLong()))
                name == "position_ids" -> {
                    val start = totalLength - tokenIds.size
                    longTensor(LongArray(tokenIds.size) { (start + it).toLong() }, longArrayOf(1, tokenIds.size.toLong()))
                }
                name == "cache_position" -> {
                    val start = totalLength - tokenIds.size
                    longTensor(LongArray(tokenIds.size) { (start + it).toLong() }, longArrayOf(tokenIds.size.toLong()))
                }
                name == "use_cache_branch" -> OnnxTensor.createTensor(environment, booleanArrayOf(previousResult != null))
                name.startsWith("past_key_values.") -> if (previousResult == null) {
                    zeroPastTensor(tensorInfo, config)
                } else {
                    val outputName = name.replaceFirst("past_key_values.", "present.")
                    previousResult.get(outputName).orElseThrow {
                        IllegalStateException("Required model cache output is missing")
                    } as? OnnxTensor ?: error("Model cache output is not a tensor")
                }
                else -> error("Unsupported orchestration model input: $name")
            }
            inputs[name] = tensor
            if (!borrowed) owned += tensor
        }
        return inputs
    }

    private fun longTensor(values: LongArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(environment, LongBuffer.wrap(values), shape)

    private fun zeroPastTensor(info: TensorInfo, config: ModelConfig): OnnxTensor {
        val shape = longArrayOf(1L, config.numKeyValueHeads.toLong(), 0L, config.headDim.toLong())
        return when (info.type) {
            OnnxJavaType.FLOAT16 -> OnnxTensor.createTensor(
                environment,
                ShortBuffer.wrap(ShortArray(0)),
                shape,
                OnnxJavaType.FLOAT16,
            )
            OnnxJavaType.FLOAT -> OnnxTensor.createTensor(environment, FloatBuffer.wrap(FloatArray(0)), shape)
            else -> error("Unsupported past-key-value tensor type")
        }
    }

    private fun argmaxLastLogit(result: OrtSession.Result): Long {
        val logits = result.get("logits").orElseThrow {
            IllegalStateException("Model output logits is missing")
        } as? OnnxTensor ?: error("Logits output is not a tensor")
        val shape = logits.info.shape
        require(shape.size == 3 && shape.last() > 0) { "Unexpected logits shape" }
        val vocab = shape.last().toInt()
        val values = logits.floatBuffer ?: error("Logits cannot be represented as floats")
        val start = values.limit() - vocab
        var bestIndex = 0
        var bestValue = Float.NEGATIVE_INFINITY
        repeat(vocab) { tokenIndex ->
            val value = values.get(start + tokenIndex)
            if (value > bestValue) {
                bestValue = value
                bestIndex = tokenIndex
            }
        }
        return bestIndex.toLong()
    }

    private fun loadModelConfig(root: File): ModelConfig {
        val file = root.walkTopDown().firstOrNull { it.isFile && it.name == "config.json" }
            ?: error("Installed orchestration model has no config.json")
        val obj = json.parseToJsonElement(file.readText()) as? JsonObject ?: error("Invalid model config.json")
        val hidden = obj.requiredInt("hidden_size")
        val heads = obj.requiredInt("num_attention_heads")
        require(hidden % heads == 0) { "Invalid attention geometry" }
        return ModelConfig(obj.requiredInt("num_key_value_heads"), hidden / heads)
    }

    private fun JsonObject.requiredInt(name: String): Int =
        this[name]?.jsonPrimitive?.int ?: error("Model config is missing field")

    private data class ModelConfig(val numKeyValueHeads: Int, val headDim: Int)
}
