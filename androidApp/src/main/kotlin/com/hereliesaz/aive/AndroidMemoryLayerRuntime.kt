package com.hereliesaz.aive

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import com.hereliesaz.geministrator.memory.HostedMemoryGenerativeRuntime
import com.hereliesaz.geministrator.memory.MemoryLayerController
import com.hereliesaz.geministrator.memory.MemoryLocalModelManager
import com.hereliesaz.geministrator.memory.MemoryLocalModelStatus
import com.hereliesaz.geministrator.memory.MemoryEngineProvider
import com.hereliesaz.geministrator.memory.MemoryLayerSettingsStore
import com.hereliesaz.geministrator.memory.MemoryMicroAgentPlatform
import com.hereliesaz.geministrator.memory.MemoryStageEngine
import com.hereliesaz.geministrator.memory.androidSqlMemoryBanks
import com.hereliesaz.geministrator.memory.AndroidOrtEmbeddingInferenceRuntime
import com.hereliesaz.geministrator.memory.AndroidOrtEmbeddingModelAdapter
import com.hereliesaz.geministrator.memory.AndroidOrtGenerativeInferenceRuntime
import com.hereliesaz.geministrator.memory.AndroidOrtGenerativeModelAdapter
import com.hereliesaz.geministrator.memory.AndroidOrtMemorySessionManager
import com.hereliesaz.geministrator.memory.EmbeddingAssociationLinkerMicroAgent
import com.hereliesaz.geministrator.memory.MemoryAdapterReleaseAsset
import com.hereliesaz.geministrator.memory.MemoryComputePreference
import com.hereliesaz.geministrator.memory.MemoryEmbeddingInferenceRequest
import com.hereliesaz.geministrator.memory.MemoryEpoch8ModelCatalog
import com.hereliesaz.geministrator.memory.MemoryGenerativeInferenceRequest
import com.hereliesaz.geministrator.memory.MemoryMicroAgent
import com.hereliesaz.geministrator.memory.MemoryMicroAgentArtifact
import com.hereliesaz.geministrator.memory.MemoryMicroAgentPrompts
import com.hereliesaz.geministrator.memory.MemoryMicroAgentRole
import com.hereliesaz.geministrator.memory.MemoryModelReleaseBundle
import com.hereliesaz.geministrator.memory.StructuredMemoryMicroAgent
import io.ktor.client.HttpClient
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.nio.ShortBuffer
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import kotlin.math.sqrt

internal data class InstalledMemoryModel(
    val role: MemoryMicroAgentRole,
    val bundle: MemoryModelReleaseBundle,
    val root: File,
    val onnxModel: File,
    val tokenizerJson: File,
    /** The role's LoRA weights when it is an adapter on a shared base; null for a merged model. */
    val adapterFile: File? = null,
)

/**
 * Memory models under files/haive/memory. An adapter clerk installs its family's shared base once
 * (under `shared/`, verified by SHA-256) plus its own verified LoRA file (under `adapters/`); the base
 * is removed with the last adapter that runs on it.
 */
internal class AndroidMemoryModelInstaller(
    context: Context,
    private val httpClient: HttpClient,
) {
    private val installRoot = File(context.filesDir, "haive/memory")
    private val installedByArtifactId = ConcurrentHashMap<String, InstalledMemoryModel>()
    private val downloader = AndroidResumableFileDownloader(httpClient)
    private val mutex = Mutex()

    /** Installed and complete, without downloading. */
    fun isInstalled(role: MemoryMicroAgentRole): Boolean {
        val bundle = MemoryEpoch8ModelCatalog.bundleFor(role) ?: return false
        return findInstalled(role, bundle) != null
    }

    suspend fun remove(role: MemoryMicroAgentRole) = mutex.withLock {
        val bundle = MemoryEpoch8ModelCatalog.bundleFor(role) ?: return@withLock
        installedByArtifactId.remove(bundle.runtimeArtifactId)
        val adapter = bundle.adapter
        if (adapter == null) {
            modelDirectory(bundle).deleteRecursively()
        } else {
            adapterPath(adapter).delete()
            File(adapterPath(adapter).path + ".sha256").delete()
            // The shared base goes with the last adapter that runs on it.
            val baseInUse = MemoryEpoch8ModelCatalog.all.any {
                it.role != role && it.modelArtifactId == bundle.modelArtifactId && verifiedAdapter(it) != null
            }
            if (!baseInUse) modelDirectory(bundle).deleteRecursively()
        }
        File(installRoot, ".staging/${bundle.releaseTag}/${role.name.lowercase()}").deleteRecursively()
    }

    suspend fun ensureInstalled(role: MemoryMicroAgentRole): InstalledMemoryModel = mutex.withLock {
        val bundle = MemoryEpoch8ModelCatalog.bundleFor(role) ?: error("No local model is released for $role")
        installedByArtifactId[bundle.runtimeArtifactId]?.let { installed ->
            if (installed.onnxModel.isFile && installed.tokenizerJson.isFile && installed.adapterFile?.isFile != false) {
                return@withLock installed
            }
        }
        findInstalled(role, bundle)?.let { installed ->
            installedByArtifactId[bundle.runtimeArtifactId] = installed
            return@withLock installed
        }

        val destination = modelDirectory(bundle)
        if (locateModel(role, bundle, destination) == null) {
            val staging = File(installRoot, ".staging/${bundle.releaseTag}/${destination.name}")
            staging.mkdirs()
            // Verified parts and .download partials stay in staging on failure, so Retry Runtime
            // resumes instead of throwing away hundreds of megabytes and restarting from byte zero.
            val archive = File(staging, bundle.releaseAssetName)
            downloader.downloadVerified(bundle.downloadUrl, archive, bundle.releaseAssetSha256)
            val extracted = File(staging, "extracted")
            extracted.deleteRecursively()
            extracted.mkdirs()
            extractTarGzSafely(archive, extracted)
            locateInstalledModel(role, bundle, extracted)
            if (bundle.adapter != null) File(extracted, INSTALLED_MARKER).writeText(bundle.releaseAssetSha256 + "\n")

            destination.parentFile?.mkdirs()
            destination.deleteRecursively()
            if (!extracted.renameTo(destination)) {
                extracted.copyRecursively(destination, overwrite = true)
                check(destination.isDirectory) { "Could not finalize memory model installation for $role" }
            }
            staging.deleteRecursively()
        }
        bundle.adapter?.let { adapter ->
            if (verifiedAdapter(bundle) == null) {
                val staging = File(installRoot, ".staging/adapters").apply { mkdirs() }
                val downloaded = File(staging, adapter.assetName)
                downloader.downloadVerified(adapter.downloadUrl, downloaded, adapter.sha256)
                val file = adapterPath(adapter)
                file.parentFile?.mkdirs()
                downloaded.copyTo(file, overwrite = true)
                downloaded.delete()
                File(file.path + ".sha256").writeText(adapter.sha256 + "\n")
            }
        }
        val installed = findInstalled(role, bundle)
            ?: error("Installed memory model could not be resolved for $role")
        installedByArtifactId[bundle.runtimeArtifactId] = installed
        installed
    }

    suspend fun ensureInstalled(artifact: MemoryMicroAgentArtifact): InstalledMemoryModel {
        installedByArtifactId[artifact.artifactId]?.let { installed ->
            if (installed.onnxModel.isFile && installed.tokenizerJson.isFile && installed.adapterFile?.isFile != false) {
                return installed
            }
        }
        val bundle = MemoryEpoch8ModelCatalog.all.singleOrNull { it.runtimeArtifactId == artifact.artifactId }
            ?: error("Unknown Epoch-8 memory artifact ${artifact.artifactId}")
        return ensureInstalled(bundle.role)
    }

    private fun extractTarGzSafely(archive: File, destination: File) {
        val root = destination.canonicalFile
        TarArchiveInputStream(
            GzipCompressorInputStream(BufferedInputStream(FileInputStream(archive))),
        ).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val output = File(root, entry.name).canonicalFile
                check(output.path == root.path || output.path.startsWith(root.path + File.separator)) {
                    "Unsafe archive entry: ${entry.name}"
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

    private fun findInstalled(role: MemoryMicroAgentRole, bundle: MemoryModelReleaseBundle): InstalledMemoryModel? {
        val model = locateModel(role, bundle, modelDirectory(bundle)) ?: return null
        if (bundle.adapter == null) return model
        return verifiedAdapter(bundle)?.let { model.copy(adapterFile = it) }
    }

    /** The installed model in [root]; a shared base must also carry its verified digest. */
    private fun locateModel(role: MemoryMicroAgentRole, bundle: MemoryModelReleaseBundle, root: File): InstalledMemoryModel? {
        if (!root.isDirectory) return null
        if (bundle.adapter != null) {
            val marker = File(root, INSTALLED_MARKER)
            if (!marker.isFile || marker.readText().trim() != bundle.releaseAssetSha256) return null
        }
        return runCatching { locateInstalledModel(role, bundle, root) }.getOrNull()
    }

    private fun locateInstalledModel(
        role: MemoryMicroAgentRole,
        bundle: MemoryModelReleaseBundle,
        root: File,
    ): InstalledMemoryModel {
        val files = root.walkTopDown().filter(File::isFile).toList()
        val onnx = files.firstOrNull { it.name == "model.onnx" }
            ?: files.firstOrNull { it.extension.equals("onnx", ignoreCase = true) }
            ?: error("No ONNX model found in installed $role bundle")
        val tokenizer = files.firstOrNull { it.name == "tokenizer.json" }
            ?: error("No tokenizer.json found in installed $role bundle")
        return InstalledMemoryModel(role, bundle, root, onnx, tokenizer)
    }

    private fun verifiedAdapter(bundle: MemoryModelReleaseBundle): File? {
        val adapter = bundle.adapter ?: return null
        val file = adapterPath(adapter)
        val marker = File(file.path + ".sha256")
        return file.takeIf { it.isFile && marker.isFile && marker.readText().trim() == adapter.sha256 }
    }

    private fun modelDirectory(bundle: MemoryModelReleaseBundle): File =
        if (bundle.adapter == null) {
            File(installRoot, "${bundle.releaseTag}/${bundle.role.name.lowercase()}")
        } else {
            File(installRoot, "shared/${bundle.modelArtifactId.safeName()}")
        }

    private fun adapterPath(adapter: MemoryAdapterReleaseAsset) =
        File(installRoot, "adapters/${adapter.logicalArtifactId.safeName()}.safetensors")

    private fun String.safeName() = replace(Regex("[^A-Za-z0-9._-]"), "_")

    private companion object {
        const val INSTALLED_MARKER = ".installed-sha256"
    }
}

internal class InstallingAndroidMemoryArtifactResolver(
    private val installer: AndroidMemoryModelInstaller,
) : com.hereliesaz.geministrator.memory.AndroidOrtArtifactResolver {
    override suspend fun isAvailable(artifact: MemoryMicroAgentArtifact): Boolean =
        runCatching { installer.ensureInstalled(artifact).onnxModel.isFile }.getOrDefault(false)

    override suspend fun resolvePath(artifact: MemoryMicroAgentArtifact): String =
        installer.ensureInstalled(artifact).onnxModel.absolutePath
}

/**
 * Runs a clerk on its installed model. An adapter clerk runs on the shared base with its LoRA weights
 * as graph inputs; the most recently used adapters stay loaded (about 35 MB each as fp32).
 */
internal class AndroidEpoch8GenerativeAdapter(
    private val installer: AndroidMemoryModelInstaller,
) : AndroidOrtGenerativeModelAdapter {
    private val environment = OrtEnvironment.getEnvironment()

    // Serialized: an adapter's tensors must not be evicted while a run still reads them.
    private val adapterLock = Mutex()
    private val adapters = object : LinkedHashMap<String, AndroidLoraAdapter>(ADAPTER_CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AndroidLoraAdapter>): Boolean =
            (size > ADAPTER_CACHE_SIZE).also { evict -> if (evict) eldest.value.close() }
    }

    override suspend fun generate(session: OrtSession, request: MemoryGenerativeInferenceRequest): String {
        val installed = installer.ensureInstalled(request.artifact)
        HuggingFaceTokenizer.newInstance(installed.root.toPath()).use { tokenizer ->
            // Local clerks are trained on the chat-templated prompt; the raw prompt is for hosted engines.
            val prompt = MemoryMicroAgentPrompts.chatPrompt(request.role, request.prompt)
            val adapterFile = installed.adapterFile
                ?: return generateCausalText(session, tokenizer, installed.root, prompt, MAX_NEW_TOKENS)
            val adapterSha = checkNotNull(installed.bundle.adapter).sha256
            return adapterLock.withLock {
                val adapter = adapters.getOrPut(adapterSha) { AndroidLoraAdapter.load(adapterFile) }
                val expectedBase = adapter.metadata["base"]
                require(expectedBase == null || expectedBase == installed.bundle.modelArtifactId) {
                    "${adapterFile.name} was trained for $expectedBase, not ${installed.bundle.modelArtifactId}"
                }
                generateCausalText(session, tokenizer, installed.root, prompt, MAX_NEW_TOKENS, adapter.tensors)
            }
        }
    }

    private fun generateCausalText(
        session: OrtSession,
        tokenizer: HuggingFaceTokenizer,
        modelRoot: File,
        prompt: String,
        maxNewTokens: Int,
        extraInputs: Map<String, OnnxTensor> = emptyMap(),
    ): String {
        val promptIds = tokenizer.encode(prompt).ids
        require(promptIds.isNotEmpty()) { "Tokenizer returned no prompt tokens" }
        val stopIds = listOf("<|im_end|>", "<|endoftext|>")
            .mapNotNull { token -> tokenizer.encode(token).ids.singleOrNull() }
            .toSet()
        val config = readAttentionGeometry(modelRoot)
        val generated = mutableListOf<Long>()
        var totalLength = promptIds.size
        var activeResult: OrtSession.Result? = null
        try {
            val owned = mutableListOf<OnnxTensor>()
            activeResult = try {
                session.run(buildCausalInputs(session, promptIds, totalLength, null, config, owned, extraInputs))
            } finally {
                owned.forEach(OnnxTensor::close)
            }
            for (ignored in 0 until maxNewTokens) {
                val current = requireNotNull(activeResult)
                val nextToken = argmaxLastLogit(current)
                if (nextToken in stopIds) break
                generated += nextToken
                totalLength += 1
                val nextOwned = mutableListOf<OnnxTensor>()
                val nextResult = try {
                    session.run(
                        buildCausalInputs(
                            session,
                            longArrayOf(nextToken),
                            totalLength,
                            current,
                            config,
                            nextOwned,
                            extraInputs,
                        ),
                    )
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

    private fun buildCausalInputs(
        session: OrtSession,
        tokenIds: LongArray,
        totalLength: Int,
        previousResult: OrtSession.Result?,
        config: AttentionGeometry,
        owned: MutableList<OnnxTensor>,
        extraInputs: Map<String, OnnxTensor>,
    ): Map<String, OnnxTensor> {
        val inputs = linkedMapOf<String, OnnxTensor>()
        session.inputInfo.forEach { (name, nodeInfo) ->
            // Supplied by the caller (a role's LoRA weights) and owned by it: never closed here.
            extraInputs[name]?.let { supplied ->
                inputs[name] = supplied
                return@forEach
            }
            val tensorInfo = nodeInfo.info as? TensorInfo ?: error("Unsupported non-tensor memory input $name")
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
                        IllegalStateException("Model output $outputName required by $name is missing")
                    } as? OnnxTensor ?: error("Model output $outputName is not a tensor")
                }
                else -> error("Unsupported memory generation model input $name")
            }
            inputs[name] = tensor
            if (!borrowed) owned += tensor
        }
        return inputs
    }

    private fun longTensor(values: LongArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(environment, LongBuffer.wrap(values), shape)

    private fun zeroPastTensor(info: TensorInfo, config: AttentionGeometry): OnnxTensor {
        val shape = longArrayOf(1L, config.numKeyValueHeads.toLong(), 0L, config.headDim.toLong())
        return when (info.type) {
            OnnxJavaType.FLOAT16 -> OnnxTensor.createTensor(
                environment,
                ShortBuffer.wrap(ShortArray(0)),
                shape,
                OnnxJavaType.FLOAT16,
            )
            OnnxJavaType.FLOAT -> OnnxTensor.createTensor(environment, FloatBuffer.wrap(FloatArray(0)), shape)
            else -> error("Unsupported past-key-value tensor type ${info.type}")
        }
    }

    private fun argmaxLastLogit(result: OrtSession.Result): Long {
        val logits = result.get("logits").orElseThrow {
            IllegalStateException("Memory model output logits is missing")
        } as? OnnxTensor ?: error("Memory logits output is not a tensor")
        val shape = logits.info.shape
        require(shape.size == 3 && shape.last() > 0) { "Unexpected logits shape ${shape.contentToString()}" }
        val vocab = shape.last().toInt()
        val values = logits.floatBuffer ?: error("Logits cannot be represented as floats")
        val start = values.limit() - vocab
        var bestIndex = 0
        var bestValue = Float.NEGATIVE_INFINITY
        for (index in 0 until vocab) {
            val value = values.get(start + index)
            if (value > bestValue) {
                bestValue = value
                bestIndex = index
            }
        }
        return bestIndex.toLong()
    }

    private fun readAttentionGeometry(root: File): AttentionGeometry {
        val configFile = root.walkTopDown().firstOrNull { it.isFile && it.name == "config.json" }
            ?: error("Installed memory model has no config.json")
        val text = configFile.readText()
        fun intField(name: String): Int = Regex("\\\"$name\\\"\\s*:\\s*(\\d+)")
            .find(text)?.groupValues?.get(1)?.toInt()
            ?: error("Model config is missing $name")
        val hidden = intField("hidden_size")
        val heads = intField("num_attention_heads")
        require(hidden % heads == 0) { "Invalid attention geometry" }
        return AttentionGeometry(intField("num_key_value_heads"), hidden / heads)
    }

    private data class AttentionGeometry(val numKeyValueHeads: Int, val headDim: Int)

    private companion object {
        const val MAX_NEW_TOKENS = 768
        const val ADAPTER_CACHE_SIZE = 3
    }
}

internal class AndroidEpoch8EmbeddingAdapter(
    private val installer: AndroidMemoryModelInstaller,
) : AndroidOrtEmbeddingModelAdapter {
    private val environment = OrtEnvironment.getEnvironment()

    override suspend fun embed(session: OrtSession, request: MemoryEmbeddingInferenceRequest): List<List<Float>> {
        val installed = installer.ensureInstalled(request.artifact)
        HuggingFaceTokenizer.newInstance(installed.root.toPath()).use { tokenizer ->
            val encodings = request.texts.map { text -> tokenizer.encode(text) }
            val maxLength = encodings.maxOf { it.ids.size }
            require(maxLength > 0) { "Embedding tokenizer returned no tokens" }
            val batch = encodings.size
            val inputIds = LongArray(batch * maxLength)
            val attentionMask = LongArray(batch * maxLength)
            encodings.forEachIndexed { row, encoding ->
                encoding.ids.forEachIndexed { column, token ->
                    inputIds[row * maxLength + column] = token
                    attentionMask[row * maxLength + column] = 1L
                }
            }

            val owned = mutableListOf<OnnxTensor>()
            val inputs = linkedMapOf<String, OnnxTensor>()
            session.inputInfo.forEach { (name, _) ->
                val tensor = when (name) {
                    "input_ids" -> longTensor(inputIds, longArrayOf(batch.toLong(), maxLength.toLong()))
                    "attention_mask" -> longTensor(attentionMask, longArrayOf(batch.toLong(), maxLength.toLong()))
                    "token_type_ids" -> longTensor(LongArray(batch * maxLength), longArrayOf(batch.toLong(), maxLength.toLong()))
                    else -> error("Unsupported memory embedding model input $name")
                }
                inputs[name] = tensor
                owned += tensor
            }

            val result = try {
                session.run(inputs)
            } finally {
                owned.forEach(OnnxTensor::close)
            }
            result.use { output ->
                val preferred = output.get("sentence_embedding").orElse(null)
                    ?: output.get("last_hidden_state").orElse(null)
                    ?: output.iterator().asSequence().map { it.value }.filterIsInstance<OnnxTensor>().firstOrNull()
                    ?: error("Embedding model produced no tensor output")
                val tensor = preferred as? OnnxTensor ?: error("Embedding output is not a tensor")
                return extractEmbeddings(tensor, attentionMask, batch, maxLength)
            }
        }
    }

    private fun extractEmbeddings(
        tensor: OnnxTensor,
        attentionMask: LongArray,
        batch: Int,
        sequenceLength: Int,
    ): List<List<Float>> {
        val shape = tensor.info.shape
        val values = tensor.floatBuffer ?: error("Embedding output cannot be represented as floats")
        return when (shape.size) {
            2 -> {
                require(shape[0].toInt() == batch)
                val dimension = shape[1].toInt()
                List(batch) { row ->
                    normalize(List(dimension) { column -> values.get(row * dimension + column) })
                }
            }
            3 -> {
                require(shape[0].toInt() == batch)
                val modelSequence = shape[1].toInt()
                val dimension = shape[2].toInt()
                require(modelSequence == sequenceLength)
                List(batch) { row ->
                    val pooled = FloatArray(dimension)
                    var tokenCount = 0
                    for (token in 0 until sequenceLength) {
                        if (attentionMask[row * sequenceLength + token] == 0L) continue
                        tokenCount++
                        val offset = (row * sequenceLength + token) * dimension
                        for (dimensionIndex in 0 until dimension) {
                            pooled[dimensionIndex] += values.get(offset + dimensionIndex)
                        }
                    }
                    require(tokenCount > 0)
                    normalize(pooled.map { it / tokenCount.toFloat() })
                }
            }
            else -> error("Unexpected embedding output shape ${shape.contentToString()}")
        }
    }

    private fun normalize(vector: List<Float>): List<Float> {
        val norm = sqrt(vector.sumOf { value -> (value * value).toDouble() }).toFloat()
        if (norm == 0f) return vector
        return vector.map { it / norm }
    }

    private fun longTensor(values: LongArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(environment, LongBuffer.wrap(values), shape)
}

/** Epoch-8 on-device clerks: what is installed, and downloads/removals on request. */
private class AndroidMemoryLocalModels(
    private val installer: AndroidMemoryModelInstaller,
    private val scope: CoroutineScope,
) : MemoryLocalModelManager {
    private val mutableModels = MutableStateFlow(emptyList<MemoryLocalModelStatus>())
    override val models: StateFlow<List<MemoryLocalModelStatus>> = mutableModels.asStateFlow()

    init {
        scope.launch { refresh() }
    }

    override suspend fun install(role: MemoryMicroAgentRole) {
        mark(role) { it.copy(installing = true, error = null) }
        val failure = runCatching { installer.ensureInstalled(role) }.exceptionOrNull()
        refresh()
        failure?.let { error -> mark(role) { it.copy(error = error.message ?: error::class.simpleName) } }
    }

    override suspend fun remove(role: MemoryMicroAgentRole) {
        installer.remove(role)
        refresh()
    }

    private fun refresh() {
        mutableModels.value = MemoryEpoch8ModelCatalog.all.map { bundle ->
            MemoryLocalModelStatus(
                role = bundle.role,
                name = bundle.adapter?.assetName ?: bundle.releaseAssetName,
                installed = installer.isInstalled(bundle.role),
            )
        }
    }

    private fun mark(role: MemoryMicroAgentRole, change: (MemoryLocalModelStatus) -> MemoryLocalModelStatus) {
        mutableModels.value = mutableModels.value.map { if (it.role == role) change(it) else it }
    }
}

/**
 * Android's memory: the shared [MemoryLayerController] over the SQLite store, with the epoch-8
 * clerks as the local engine and the app's providers as the hosted engine.
 */
internal class AndroidMemoryLayerRuntime(
    context: Context,
    httpClient: HttpClient,
    cacheDirectory: File,
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val installer = AndroidMemoryModelInstaller(context, httpClient)
    private val resolver = InstallingAndroidMemoryArtifactResolver(installer)
    private val sessionManager = AndroidOrtMemorySessionManager(
        computePreference = MemoryComputePreference.AUTO,
        profileDirectory = File(cacheDirectory, "memory-ort-profile"),
    )
    private val generativeRuntime = AndroidOrtGenerativeInferenceRuntime(
        sessionManager = sessionManager,
        artifactResolver = resolver,
        modelAdapter = AndroidEpoch8GenerativeAdapter(installer),
    )
    private val embeddingRuntime = AndroidOrtEmbeddingInferenceRuntime(
        sessionManager = sessionManager,
        artifactResolver = resolver,
        modelAdapter = AndroidEpoch8EmbeddingAdapter(installer),
    )

    /**
     * Answers hosted memory stages: (providerId or null for the default, model or null, prompt) to
     * text. Set by the activity once provider credentials are known; unset means hosted stages fail
     * visibly in the queue rather than silently.
     */
    @Volatile
    var hostedTextGenerator: (suspend (providerId: String?, model: String?, prompt: String) -> String)? = null

    private val engineProvider = object : MemoryEngineProvider {
        override fun localAgent(role: MemoryMicroAgentRole): MemoryMicroAgent? {
            if (MemoryEpoch8ModelCatalog.bundleFor(role) == null) return null
            val model = MemoryEpoch8ModelCatalog.modelSpec(role)
            return if (role == MemoryMicroAgentRole.AssociationLinker) {
                EmbeddingAssociationLinkerMicroAgent(model, embeddingRuntime)
            } else {
                StructuredMemoryMicroAgent(role, model, generativeRuntime)
            }
        }

        override fun hostedAgent(role: MemoryMicroAgentRole, engine: MemoryStageEngine): MemoryMicroAgent =
            HostedMemoryGenerativeRuntime.agent(role, engine, MemoryMicroAgentPlatform.Android) { prompt ->
                val generate = hostedTextGenerator ?: error("No hosted provider is configured for memory")
                generate(engine.providerId, engine.model, prompt)
            }
    }

    /** The workflow lineage DAG and project membership (add-only). */
    private val memoryLineage = com.hereliesaz.geministrator.memory.MemoryLineage(com.russhwolf.settings.Settings())

    /**
     * One SQLite database per workflow run's memory bank. Built on the IO dispatcher (see
     * MainActivity), so the one-time split of the older shared store runs before anything can bank.
     */
    private val banks = runBlocking { androidSqlMemoryBanks(context, memoryLineage) }

    /** The memory layer the Memory screen controls. */
    val controller = MemoryLayerController(
        banks = banks,
        settingsStore = MemoryLayerSettingsStore.createDefault(),
        lineage = memoryLineage,
        engineProvider = engineProvider,
        scope = scope,
        localModels = AndroidMemoryLocalModels(installer, scope),
    )

    fun attach() = controller.attach()

    fun detach() = controller.detach()

    override fun close() {
        detach()
        scope.cancel()
        sessionManager.close()
    }
}
