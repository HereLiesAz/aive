package com.hereliesaz.aive

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import com.hereliesaz.geministrator.memory.AgentMemoryLayer
import com.hereliesaz.geministrator.memory.AndroidOrtEmbeddingInferenceRuntime
import com.hereliesaz.geministrator.memory.AndroidOrtEmbeddingModelAdapter
import com.hereliesaz.geministrator.memory.AndroidOrtGenerativeInferenceRuntime
import com.hereliesaz.geministrator.memory.AndroidOrtGenerativeModelAdapter
import com.hereliesaz.geministrator.memory.AndroidOrtMemorySessionManager
import com.hereliesaz.geministrator.memory.EmbeddingAssociationLinkerMicroAgent
import com.hereliesaz.geministrator.memory.MemoryComputePreference
import com.hereliesaz.geministrator.memory.MemoryConsolidationResult
import com.hereliesaz.geministrator.memory.MemoryEmbeddingInferenceRequest
import com.hereliesaz.geministrator.memory.MemoryEpoch8ModelCatalog
import com.hereliesaz.geministrator.memory.MemoryGenerativeInferenceRequest
import com.hereliesaz.geministrator.memory.MemoryMicroAgent
import com.hereliesaz.geministrator.memory.MemoryMicroAgentArtifact
import com.hereliesaz.geministrator.memory.MemoryMicroAgentRole
import com.hereliesaz.geministrator.memory.MemoryModelReleaseBundle
import com.hereliesaz.geministrator.memory.MemoryPromptContextProvider
import com.hereliesaz.geministrator.memory.MemoryQuery
import com.hereliesaz.geministrator.memory.MemoryResolution
import com.hereliesaz.geministrator.memory.MemoryRuntimeBridge
import com.hereliesaz.geministrator.memory.MemorySessionObserver
import com.hereliesaz.geministrator.memory.StructuredMemoryMicroAgent
import com.hereliesaz.geministrator.providers.AgentEvent
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.providers.PromptContextBlock
import com.hereliesaz.geministrator.workflow.ManagedSessionHandle
import com.hereliesaz.geministrator.workflow.ManagedSessionStatus
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.nio.ShortBuffer
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import kotlin.math.sqrt
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

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
    private val mutex = Mutex()

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
        staging.deleteRecursively()
        staging.mkdirs()
        try {
            val archive = File(staging, bundle.releaseAssetName)
            downloadVerified(bundle.downloadUrl, archive, bundle.releaseAssetSha256)
            val extracted = File(staging, "extracted")
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
            staging.deleteRecursively()
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

    private suspend fun downloadVerified(url: String, output: File, expectedSha: String) {
        val normalizedExpected = expectedSha.lowercase()
        check(normalizedExpected.matches(Regex("[0-9a-f]{64}"))) { "Invalid SHA-256 for ${output.name}" }
        if (output.isFile && sha256(output) == normalizedExpected) return
        val temporary = File(output.parentFile, "${output.name}.download")
        temporary.delete()
        val response = httpClient.get(url)
        check(response.status.isSuccess()) { "Download failed for ${output.name}: ${response.status}" }
        val digest = MessageDigest.getInstance("SHA-256")
        val channel = response.bodyAsChannel()
        FileOutputStream(temporary).buffered().use { stream ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (!channel.isClosedForRead) {
                val count = channel.readAvailable(buffer, 0, buffer.size)
                if (count > 0) {
                    stream.write(buffer, 0, count)
                    digest.update(buffer, 0, count)
                }
            }
        }
        val actual = digest.digest().toHex()
        check(actual == normalizedExpected) {
            temporary.delete()
            "SHA-256 mismatch for ${output.name}: expected $normalizedExpected, got $actual"
        }
        output.delete()
        check(temporary.renameTo(output)) { "Could not finalize ${output.name}" }
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

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        BufferedInputStream(FileInputStream(file)).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte) }
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
    private val agents: List<MemoryMicroAgent> = MemoryMicroAgentRole.entries.map { role ->
        val model = MemoryEpoch8ModelCatalog.modelSpec(role)
        if (role == MemoryMicroAgentRole.AssociationLinker) {
            EmbeddingAssociationLinkerMicroAgent(model, embeddingRuntime)
        } else {
            StructuredMemoryMicroAgent(role, model, generativeRuntime)
        }
    }
    private val layer = AgentMemoryLayer.createDefaultWithMicroAgents(agents)
    private val drainMutex = Mutex()

    val observer: MemorySessionObserver = object : MemorySessionObserver {
        override suspend fun onSessionStarted(handle: ManagedSessionHandle, request: AgentTaskRequest) {
            layer.sessionObserver.onSessionStarted(handle, request)
        }

        override suspend fun onSessionEvent(handle: ManagedSessionHandle, event: AgentEvent) {
            layer.sessionObserver.onSessionEvent(handle, event)
        }

        override suspend fun onSessionFinished(handle: ManagedSessionHandle, status: ManagedSessionStatus) {
            layer.sessionObserver.onSessionFinished(handle, status)
            scope.launch { drainConsolidationQueue() }
        }
    }

    private val promptContextProvider = MemoryPromptContextProvider { request ->
        val context = request.orchestrationContext
        val recall = layer.tool.grip(
            MemoryQuery(
                text = request.objective,
                resolution = MemoryResolution.Summary,
                maxResults = MAX_RECALL_RESULTS,
                projectId = context.projectId?.value,
                roleId = context.roleId?.value,
            ),
        )
        if (recall.hits.isEmpty()) {
            emptyList()
        } else {
            val content = recall.hits.joinToString("\n\n") { hit ->
                "[${"%.2f".format(hit.score)}] ${hit.node.kind.name}: ${hit.node.text}"
            }.take(MAX_RECALL_CHARS)
            listOf(PromptContextBlock("Relevant memory", content))
        }
    }

    init {
        MemoryRuntimeBridge.observer = observer
        MemoryRuntimeBridge.promptContextProvider = promptContextProvider
        scope.launch { drainConsolidationQueue() }
    }

    @OptIn(ExperimentalTime::class)
    private suspend fun drainConsolidationQueue() = drainMutex.withLock {
        while (true) {
            when (layer.consolidateOne(Clock.System.now().toEpochMilliseconds())) {
                MemoryConsolidationResult.Idle -> return@withLock
                else -> Unit
            }
        }
    }

    override fun close() {
        if (MemoryRuntimeBridge.observer === observer) {
            MemoryRuntimeBridge.reset()
        }
        scope.cancel()
        sessionManager.close()
    }

    private companion object {
        const val MAX_RECALL_RESULTS = 6
        const val MAX_RECALL_CHARS = 6_000
    }
}
