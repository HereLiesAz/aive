package com.hereliesaz.geministrator

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.hereliesaz.geministrator.memory.DesktopOrtArtifactResolver
import com.hereliesaz.geministrator.memory.DesktopOrtEmbeddingInferenceRuntime
import com.hereliesaz.geministrator.memory.DesktopOrtEmbeddingModelAdapter
import com.hereliesaz.geministrator.memory.DesktopOrtGenerativeInferenceRuntime
import com.hereliesaz.geministrator.memory.DesktopOrtGenerativeModelAdapter
import com.hereliesaz.geministrator.memory.DesktopOrtMemorySessionManager
import com.hereliesaz.geministrator.memory.EmbeddingAssociationLinkerMicroAgent
import com.hereliesaz.geministrator.memory.HostedMemoryEngineProvider
import com.hereliesaz.geministrator.memory.MemoryEmbeddingInferenceRequest
import com.hereliesaz.geministrator.memory.MemoryEngineProvider
import com.hereliesaz.geministrator.memory.MemoryEpoch8ModelCatalog
import com.hereliesaz.geministrator.memory.MemoryGenerativeInferenceRequest
import com.hereliesaz.geministrator.memory.MemoryLocalModelManager
import com.hereliesaz.geministrator.memory.MemoryLocalModelStatus
import com.hereliesaz.geministrator.memory.MemoryMicroAgent
import com.hereliesaz.geministrator.memory.MemoryMicroAgentArtifact
import com.hereliesaz.geministrator.memory.MemoryMicroAgentPrompts
import com.hereliesaz.geministrator.memory.MemoryMicroAgentRole
import com.hereliesaz.geministrator.memory.StructuredMemoryMicroAgent
import io.ktor.client.HttpClient
import java.io.File
import java.nio.LongBuffer
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Epoch-8 memory clerks on desktop, installed per stage from the Memory screen under
 * ~/.aive/models/memory. Inference never downloads: a stage set to its local model whose model is
 * not installed fails visibly in the queue until it is installed.
 */
internal class DesktopMemoryModelInstaller(
    httpClient: HttpClient,
    private val installRoot: File = File(System.getProperty("user.home"), ".aive/models/memory"),
) {
    private val downloader = DesktopResumableFileDownloader(httpClient)
    private val mutex = Mutex()

    /** The installed bundle's root directory, or null. */
    fun installed(role: MemoryMicroAgentRole): File? {
        val bundle = MemoryEpoch8ModelCatalog.bundleFor(role)
        val dir = directory(role)
        val marker = File(dir, INSTALLED_MARKER)
        return dir.takeIf {
            marker.isFile && marker.readText().trim() == bundle.releaseAssetSha256 &&
                modelFile(dir) != null && tokenizerRoot(dir) != null
        }
    }

    fun installed(artifact: MemoryMicroAgentArtifact): File? =
        MemoryEpoch8ModelCatalog.all.singleOrNull { it.runtimeArtifactId == artifact.artifactId }
            ?.let { installed(it.role) }

    suspend fun install(role: MemoryMicroAgentRole): File = mutex.withLock {
        installed(role)?.let { return@withLock it }
        withContext(Dispatchers.IO) {
            val bundle = MemoryEpoch8ModelCatalog.bundleFor(role)
            val staging = File(installRoot, ".staging/${role.name.lowercase()}").apply { mkdirs() }
            // Verified parts and .download partials stay in staging on failure, so a retry resumes.
            val archive = downloader.downloadVerified(
                url = bundle.downloadUrl,
                output = File(staging, bundle.releaseAssetName),
                expectedSha256 = bundle.releaseAssetSha256,
            )
            val extracted = File(staging, "extracted").apply {
                deleteRecursively()
                mkdirs()
            }
            extractTarGzSafely(archive, extracted)
            checkNotNull(modelFile(extracted)) { "${bundle.releaseAssetName} has no ONNX model" }
            checkNotNull(tokenizerRoot(extracted)) { "${bundle.releaseAssetName} has no tokenizer.json" }
            File(extracted, INSTALLED_MARKER).writeText(bundle.releaseAssetSha256 + "\n")
            val destination = directory(role)
            destination.deleteRecursively()
            destination.parentFile?.mkdirs()
            if (!extracted.renameTo(destination)) extracted.copyRecursively(destination, overwrite = true)
            staging.deleteRecursively()
            installed(role) ?: error("${bundle.releaseAssetName} did not install")
        }
    }

    suspend fun remove(role: MemoryMicroAgentRole) = mutex.withLock {
        withContext(Dispatchers.IO) {
            directory(role).deleteRecursively()
            File(installRoot, ".staging/${role.name.lowercase()}").deleteRecursively()
        }
    }

    fun modelFile(root: File): File? {
        val files = root.walkTopDown().filter(File::isFile).toList()
        return files.firstOrNull { it.name == "model.onnx" }
            ?: files.firstOrNull { it.extension.equals("onnx", ignoreCase = true) }
    }

    /** The directory holding tokenizer.json (and config.json), which may be nested in the archive. */
    fun tokenizerRoot(root: File): File? =
        root.walkTopDown().firstOrNull { it.isFile && it.name == "tokenizer.json" }?.parentFile

    private fun directory(role: MemoryMicroAgentRole) =
        File(installRoot, "${MemoryEpoch8ModelCatalog.RELEASE_TAG}/${role.name.lowercase()}")

    private companion object {
        const val INSTALLED_MARKER = ".installed-sha256"
    }
}

private class InstalledDesktopMemoryArtifactResolver(
    private val installer: DesktopMemoryModelInstaller,
) : DesktopOrtArtifactResolver {
    override suspend fun isAvailable(artifact: MemoryMicroAgentArtifact): Boolean = installer.installed(artifact) != null

    override suspend fun resolvePath(artifact: MemoryMicroAgentArtifact): String =
        installer.installed(artifact)?.let(installer::modelFile)?.absolutePath
            ?: error("The local model for ${artifact.artifactId} is not installed")
}

private class DesktopEpoch8GenerativeAdapter(
    private val installer: DesktopMemoryModelInstaller,
    private val generator: DesktopOrtCausalGenerator = DesktopOrtCausalGenerator(),
) : DesktopOrtGenerativeModelAdapter {
    override suspend fun generate(session: OrtSession, request: MemoryGenerativeInferenceRequest): String {
        val root = installer.installed(request.artifact)?.let(installer::tokenizerRoot)
            ?: error("The local model for ${request.artifact.artifactId} is not installed")
        return withContext(Dispatchers.Default) {
            HuggingFaceTokenizer.newInstance(root.toPath()).use { tokenizer ->
                // Local clerks are trained on the chat-templated prompt; the raw prompt is for hosted engines.
                val prompt = MemoryMicroAgentPrompts.chatPrompt(request.role, request.prompt)
                generator.generate(session, tokenizer, root, prompt, MAX_NEW_TOKENS)
            }
        }
    }

    private companion object {
        const val MAX_NEW_TOKENS = 768
    }
}

/** Mean-pooled, L2-normalized sentence embeddings; ported from the Android adapter. */
private class DesktopEpoch8EmbeddingAdapter(
    private val installer: DesktopMemoryModelInstaller,
) : DesktopOrtEmbeddingModelAdapter {
    private val environment = OrtEnvironment.getEnvironment()

    override suspend fun embed(session: OrtSession, request: MemoryEmbeddingInferenceRequest): List<List<Float>> {
        val root = installer.installed(request.artifact)?.let(installer::tokenizerRoot)
            ?: error("The local model for ${request.artifact.artifactId} is not installed")
        return HuggingFaceTokenizer.newInstance(root.toPath()).use { tokenizer ->
            val encodings = request.texts.map(tokenizer::encode)
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
            val shape = longArrayOf(batch.toLong(), maxLength.toLong())
            val inputs = linkedMapOf<String, OnnxTensor>()
            try {
                session.inputInfo.keys.forEach { name ->
                    inputs[name] = when (name) {
                        "input_ids" -> longTensor(inputIds, shape)
                        "attention_mask" -> longTensor(attentionMask, shape)
                        "token_type_ids" -> longTensor(LongArray(batch * maxLength), shape)
                        else -> error("Unsupported memory embedding model input $name")
                    }
                }
                session.run(inputs).use { output ->
                    val tensor = (
                        output.get("sentence_embedding").orElse(null)
                            ?: output.get("last_hidden_state").orElse(null)
                            ?: output.iterator().asSequence().map { it.value }.filterIsInstance<OnnxTensor>().firstOrNull()
                        ) as? OnnxTensor ?: error("Embedding model produced no tensor output")
                    extractEmbeddings(tensor, attentionMask, batch, maxLength)
                }
            } finally {
                inputs.values.forEach(OnnxTensor::close)
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
        require(shape.isNotEmpty() && shape[0].toInt() == batch) { "Unexpected embedding batch ${shape.contentToString()}" }
        return when (shape.size) {
            2 -> {
                val dimension = shape[1].toInt()
                List(batch) { row -> normalize(List(dimension) { values.get(row * dimension + it) }) }
            }
            3 -> {
                require(shape[1].toInt() == sequenceLength) { "Unexpected embedding sequence ${shape.contentToString()}" }
                val dimension = shape[2].toInt()
                List(batch) { row ->
                    val pooled = FloatArray(dimension)
                    var tokens = 0
                    for (token in 0 until sequenceLength) {
                        if (attentionMask[row * sequenceLength + token] == 0L) continue
                        tokens++
                        val offset = (row * sequenceLength + token) * dimension
                        for (index in 0 until dimension) pooled[index] += values.get(offset + index)
                    }
                    require(tokens > 0)
                    normalize(pooled.map { it / tokens })
                }
            }
            else -> error("Unexpected embedding output shape ${shape.contentToString()}")
        }
    }

    private fun normalize(vector: List<Float>): List<Float> {
        val norm = sqrt(vector.sumOf { (it * it).toDouble() }).toFloat()
        return if (norm == 0f) vector else vector.map { it / norm }
    }

    private fun longTensor(values: LongArray, shape: LongArray): OnnxTensor =
        OnnxTensor.createTensor(environment, LongBuffer.wrap(values), shape)
}

/** What is installed, and installs/removals on request from the Memory screen. */
internal class DesktopMemoryLocalModels(
    private val installer: DesktopMemoryModelInstaller,
    scope: CoroutineScope,
) : MemoryLocalModelManager {
    private val mutableModels = MutableStateFlow(emptyList<MemoryLocalModelStatus>())
    override val models: StateFlow<List<MemoryLocalModelStatus>> = mutableModels.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) { refresh() }
    }

    override suspend fun install(role: MemoryMicroAgentRole) {
        mark(role) { it.copy(installing = true, error = null) }
        val failure = runCatching { installer.install(role) }.exceptionOrNull()
        if (failure is kotlinx.coroutines.CancellationException) throw failure
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
                installed = installer.installed(bundle.role) != null,
            )
        }
    }

    private fun mark(role: MemoryMicroAgentRole, change: (MemoryLocalModelStatus) -> MemoryLocalModelStatus) {
        mutableModels.value = mutableModels.value.map { if (it.role == role) change(it) else it }
    }
}

/**
 * Desktop memory engines: the epoch-8 clerks with ONNX Runtime as the local engine, and [hosted]
 * (the app's providers) as the hosted engine.
 */
internal class DesktopMemoryEngineProvider(
    private val hosted: HostedMemoryEngineProvider,
    installer: DesktopMemoryModelInstaller,
    sessions: DesktopOrtMemorySessionManager,
) : MemoryEngineProvider by hosted {
    private val resolver = InstalledDesktopMemoryArtifactResolver(installer)
    private val generativeRuntime = DesktopOrtGenerativeInferenceRuntime(sessions, resolver, DesktopEpoch8GenerativeAdapter(installer))
    private val embeddingRuntime = DesktopOrtEmbeddingInferenceRuntime(sessions, resolver, DesktopEpoch8EmbeddingAdapter(installer))

    override fun localAgent(role: MemoryMicroAgentRole): MemoryMicroAgent {
        val model = MemoryEpoch8ModelCatalog.modelSpec(role)
        return if (role == MemoryMicroAgentRole.AssociationLinker) {
            EmbeddingAssociationLinkerMicroAgent(model, embeddingRuntime)
        } else {
            StructuredMemoryMicroAgent(role, model, generativeRuntime)
        }
    }
}
