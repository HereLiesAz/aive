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
import com.hereliesaz.geministrator.memory.SettingsMemoryStore
import com.hereliesaz.geministrator.memory.androidSqlMemoryStore
import com.hereliesaz.geministrator.memory.AndroidOrtEmbeddingInferenceRuntime
import com.hereliesaz.geministrator.memory.AndroidOrtEmbeddingModelAdapter
import com.hereliesaz.geministrator.memory.AndroidOrtGenerativeInferenceRuntime
import com.hereliesaz.geministrator.memory.AndroidOrtGenerativeModelAdapter
import com.hereliesaz.geministrator.memory.AndroidOrtMemorySessionManager
import com.hereliesaz.geministrator.memory.EmbeddingAssociationLinkerMicroAgent
import com.hereliesaz.geministrator.memory.MemoryComputePreference
import com.hereliesaz.geministrator.memory.MemoryEmbeddingInferenceRequest
import com.hereliesaz.geministrator.memory.MemoryEpoch8ModelCatalog
import com.hereliesaz.geministrator.memory.MemoryGenerativeInferenceRequest
import com.hereliesaz.geministrator.memory.MemoryMicroAgent
import com.hereliesaz.geministrator.memory.MemoryMicroAgentArtifact
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
)

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
        val bundle = MemoryEpoch8ModelCatalog.bundleFor(role)
        return findInstalled(role, bundle, File(installRoot, "${bundle.releaseTag}/${role.name.lowercase()}")) != null
    }

    suspend fun remove(role: MemoryMicroAgentRole) = mutex.withLock {
        val bundle = MemoryEpoch8ModelCatalog.bundleFor(role)
        installedByArtifactId.remove(bundle.runtimeArtifactId)
        File(installRoot, "${bundle.releaseTag}/${role.name.lowercase()}").deleteRecursively()
        File(installRoot, ".staging/${bundle.releaseTag}/${role.name.lowercase()}").deleteRecursively()
    }

    suspend fun ensureInstalled(role: MemoryMicroAgentRole): InstalledMemoryModel = mutex.withLock {
        val bundle = MemoryEpoch8ModelCatalog.bundleFor(role)
        installedByArtifactId[bundle.runtimeArtifactId]?.let { installed ->
            if (installed.onnxModel.isFile && installed.tokenizerJson.isFile) return@withLock installed
        }

        val destination = File(installRoot, "${bundle.releaseTag}/${role.name.lowercase()}")
        findInstalled(role, bundle, destination)?.let { installed ->
            installedByArtifactId[bundle.runtimeArtifactId] = installed
            return@withLock installed
        }

        val staging = File(installRoot, ".staging/${bundle.releaseTag}/${role.name.lowercase()}")
        staging.mkdirs()
        try {
            val archive = File(staging, bundle.releaseAssetName)
            downloader.downloadVerified(bundle.downloadUrl, archive, bundle.releaseAssetSha256)
            val extracted = File(staging, "extracted")
            extracted.deleteRecursively()
            extracted.mkdirs()
            extractTarGzSafely(archive, extracted)
            locateInstalledModel(role, bundle, extracted)

            destination.parentFile?.mkdirs()
            destination.deleteRecursively()
            if (!extracted.renameTo(destination)) {
                extracted.copyRecursively(destination, overwrite = true)
                check(destination.isDirectory) { "Could not finalize memory model installation for $role" }
            }
            staging.deleteRecursively()
            val installed = findInstalled(role, bundle, destination)
                ?: error("Installed memory model could not be resolved for $role")
            installedByArtifactId[bundle.runtimeArtifactId] = installed
            installed
        } catch (failure: Throwable) {
            // Keep verified parts and .download partials so Retry Runtime resumes instead of
            // throwing away hundreds of megabytes and restarting from byte zero.
            throw failure
        }
    }

    suspend fun ensureInstalled(artifact: MemoryMicroAgentArtifact): InstalledMemoryModel {
        installedByArtifactId[artifact.artifactId]?.let { installed ->
            if (installed.onnxModel.isFile && installed.tokenizerJson.isFile) return installed
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

    private fun findInstalled(
        role: MemoryMicroAgentRole,
        bundle: MemoryModelReleaseBundle,
        root: File,
    ): InstalledMemoryModel? = if (root.isDirectory) {
        runCatching { locateInstalledModel(role, bundle, root) }.getOrNull()
    } else {
        null
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

}

internal class InstallingAndroidMemoryArtifactResolver(
    private val installer: AndroidMemoryModelInstaller,
) : com.hereliesaz.geministrator.memory.AndroidOrtArtifactResolver {
    override suspend fun isAvailable(artifact: MemoryMicroAgentArtifact): Boolean =
        runCatching { installer.ensureInstalled(artifact).onnxModel.isFile }.getOrDefault(false)

    override suspend fun resolvePath(artifact: MemoryMicroAgentArtifact): String =
        installer.ensureInstalled(artifact).onnxModel.absolutePath
}

internal class AndroidEpoch8GenerativeAdapter(
    private val installer: AndroidMemoryModelInstaller,
) : AndroidOrtGenerativeModelAdapter {
    private val environment = OrtEnvironment.getEnvironment()

    override suspend fun generate(session: OrtSession, request: MemoryGenerativeInferenceRequest): String {
        val installed = installer.ensureInstalled(request.artifact)
        HuggingFaceTokenizer.newInstance(installed.root.toPath()).use { tokenizer ->
            return generateCausalText(session, tokenizer, installed.root, request.prompt, MAX_NEW_TOKENS)
        }
    }

    private fun generateCausalText(
        session: OrtSession,
        tokenizer: HuggingFaceTokenizer,
        modelRoot: File,
        prompt: String,
        maxNewTokens: Int,
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
                session.run(buildCausalInputs(session, promptIds, totalLength, null, config, owned))
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
    ): Map<String, OnnxTensor> {
        val inputs = linkedMapOf<String, OnnxTensor>()
        session.inputInfo.forEach { (name, nodeInfo) ->
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
                name = bundle.releaseAssetName,
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
        override fun localAgent(role: MemoryMicroAgentRole): MemoryMicroAgent {
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

    /**
     * SQLite store. Built on the IO dispatcher (see MainActivity), so the one-time import of the
     * older Settings-backed graph runs before anything can bank into the new database.
     */
    private val store = androidSqlMemoryStore(context).also { store ->
        runBlocking { store.importLegacy(SettingsMemoryStore.createDefault()) }
    }

    /** The memory layer the Memory screen controls. */
    val controller = MemoryLayerController(
        store = store,
        settingsStore = MemoryLayerSettingsStore.createDefault(),
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
