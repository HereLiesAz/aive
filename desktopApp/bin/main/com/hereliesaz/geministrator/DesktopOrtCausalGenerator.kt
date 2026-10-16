package com.hereliesaz.geministrator

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.nio.ShortBuffer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/**
 * Greedy decoding for an Optimum `text-generation-with-past` ONNX export (Qwen2.5 layout: KV cache
 * inputs, float logits). Shared by the local planner and the orchestration specialists.
 *
 * `extraInputs` feeds graph inputs the loop does not produce itself (a role's LoRA weights on a shared
 * base). The caller owns those tensors; they are reused across every step and never closed here.
 */
internal class DesktopOrtCausalGenerator(
    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun generate(
        session: OrtSession,
        tokenizer: HuggingFaceTokenizer,
        modelRoot: java.io.File,
        prompt: String,
        maxNewTokens: Int,
        progress: (String) -> Unit = {},
        writingLabel: String = "Writing",
        extraInputs: Map<String, OnnxTensor> = emptyMap(),
    ): String {
        val config = loadModelConfig(modelRoot)
        val promptIds = tokenizer.encode(prompt).ids
        require(promptIds.isNotEmpty()) { "Tokenizer returned no prompt tokens" }
        progress("Reading prompt (${promptIds.size} tokens)…")
        val stopIds = listOf("<|im_end|>", "<|endoftext|>")
            .mapNotNull { token -> tokenizer.encode(token).ids.singleOrNull() }
            .toSet()
        val generated = mutableListOf<Long>()

        // Prefill in bounded chunks. The exported graph emits full-sequence logits
        // ([1, inputLength, vocab]), and reading them copies the whole tensor onto the Java heap.
        // Chunking bounds each logits tensor to PREFILL_CHUNK_TOKENS * vocab, and intermediate
        // chunks don't request `logits` at all.
        val cacheOnlyOutputs = session.outputNames.filterTo(linkedSetOf()) { it != LOGITS_OUTPUT }
        var totalLength = 0
        var activeResult: OrtSession.Result? = null
        try {
            val chunks = prefillChunks(promptIds.size, PREFILL_CHUNK_TOKENS)
            chunks.forEachIndexed { chunkIndex, range ->
                progress("Thinking (chunk ${chunkIndex + 1} of ${chunks.size})…")
                val chunkIds = promptIds.copyOfRange(range.first, range.last + 1)
                totalLength += chunkIds.size
                val previous = activeResult
                val owned = mutableListOf<OnnxTensor>()
                val inputs = buildInputs(
                    session = session,
                    tokenIds = chunkIds,
                    totalLength = totalLength,
                    previousResult = previous,
                    config = config,
                    owned = owned,
                    extraInputs = extraInputs,
                )
                val isFinalChunk = chunkIndex == chunks.lastIndex
                val chunkResult = try {
                    if (isFinalChunk) session.run(inputs) else session.run(inputs, cacheOnlyOutputs)
                } finally {
                    owned.forEach(OnnxTensor::close)
                }
                previous?.close()
                activeResult = chunkResult
            }

            for (ignored in 0 until maxNewTokens) {
                val currentResult = requireNotNull(activeResult)
                val nextToken = argmaxLastLogit(currentResult)
                if (nextToken in stopIds) break
                generated += nextToken
                if (generated.size == 1 || generated.size % GENERATION_PROGRESS_INTERVAL == 0) {
                    progress("$writingLabel (${generated.size} tokens)…")
                }
                totalLength += 1

                val owned = mutableListOf<OnnxTensor>()
                val nextInputs = buildInputs(
                    session = session,
                    tokenIds = longArrayOf(nextToken),
                    totalLength = totalLength,
                    previousResult = currentResult,
                    config = config,
                    owned = owned,
                    extraInputs = extraInputs,
                )
                val nextResult = try {
                    session.run(nextInputs)
                } finally {
                    owned.forEach(OnnxTensor::close)
                }
                currentResult.close()
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
        extraInputs: Map<String, OnnxTensor>,
    ): Map<String, OnnxTensor> {
        val inputs = linkedMapOf<String, OnnxTensor>()
        session.inputInfo.forEach { (name, nodeInfo) ->
            extraInputs[name]?.let { supplied ->
                inputs[name] = supplied
                return@forEach
            }
            val tensorInfo = nodeInfo.info as? TensorInfo
                ?: error("Unsupported non-tensor model input $name")
            val borrowsPreviousResult = name.startsWith("past_key_values.") && previousResult != null
            val tensor = when {
                name == "input_ids" -> longTensor(tokenIds, longArrayOf(1, tokenIds.size.toLong()))
                name == "attention_mask" -> longTensor(
                    LongArray(totalLength) { 1L },
                    longArrayOf(1, totalLength.toLong()),
                )
                name == "position_ids" -> {
                    val start = totalLength - tokenIds.size
                    longTensor(
                        LongArray(tokenIds.size) { index -> (start + index).toLong() },
                        longArrayOf(1, tokenIds.size.toLong()),
                    )
                }
                name == "cache_position" -> {
                    val start = totalLength - tokenIds.size
                    longTensor(
                        LongArray(tokenIds.size) { index -> (start + index).toLong() },
                        longArrayOf(tokenIds.size.toLong()),
                    )
                }
                name == "use_cache_branch" -> OnnxTensor.createTensor(
                    environment,
                    booleanArrayOf(previousResult != null),
                )
                name.startsWith("past_key_values.") -> {
                    if (previousResult == null) {
                        zeroPastTensor(tensorInfo, config)
                    } else {
                        val outputName = name.replaceFirst("past_key_values.", "present.")
                        previousResult.get(outputName).orElseThrow {
                            IllegalStateException("Model output $outputName required by $name is missing")
                        } as? OnnxTensor ?: error("Model output $outputName is not a tensor")
                    }
                }
                else -> error("Unsupported model input $name")
            }
            inputs[name] = tensor
            if (!borrowsPreviousResult) owned += tensor
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
            OnnxJavaType.FLOAT -> OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(FloatArray(0)),
                shape,
            )
            else -> error("Unsupported past-key-value tensor type ${info.type}")
        }
    }

    private fun argmaxLastLogit(result: OrtSession.Result): Long {
        val logits = result.get(LOGITS_OUTPUT).orElseThrow {
            IllegalStateException("Model output logits is missing")
        } as? OnnxTensor ?: error("Logits output is not a tensor")
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

    private fun loadModelConfig(root: java.io.File): ModelConfig {
        val configFile = root.walkTopDown().firstOrNull { it.isFile && it.name == "config.json" }
            ?: error("Installed model has no config.json")
        val objectValue = json.parseToJsonElement(configFile.readText()) as? JsonObject
            ?: error("Invalid model config.json")
        val hiddenSize = objectValue.requiredInt("hidden_size")
        val attentionHeads = objectValue.requiredInt("num_attention_heads")
        require(hiddenSize % attentionHeads == 0) { "Invalid attention geometry in model config" }
        return ModelConfig(
            numKeyValueHeads = objectValue.requiredInt("num_key_value_heads"),
            headDim = hiddenSize / attentionHeads,
        )
    }

    private fun JsonObject.requiredInt(name: String): Int =
        this[name]?.jsonPrimitive?.int ?: error("Model config is missing $name")

    private data class ModelConfig(
        val numKeyValueHeads: Int,
        val headDim: Int,
    )

    private companion object {
        const val PREFILL_CHUNK_TOKENS = 32
        const val LOGITS_OUTPUT = "logits"
        const val GENERATION_PROGRESS_INTERVAL = 32

        /** Splits [0, tokenCount) into consecutive ranges of at most [chunkSize] tokens. */
        fun prefillChunks(tokenCount: Int, chunkSize: Int): List<IntRange> {
            require(tokenCount > 0) { "Prompt must contain at least one token" }
            require(chunkSize > 0) { "Chunk size must be positive" }
            return (0 until tokenCount step chunkSize).map { start ->
                start until minOf(start + chunkSize, tokenCount)
            }
        }
    }
}
