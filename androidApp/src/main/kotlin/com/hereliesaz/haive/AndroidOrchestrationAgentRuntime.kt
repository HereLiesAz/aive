package com.hereliesaz.haive

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import com.hereliesaz.geministrator.memory.AndroidOrtMemorySessionManager
import com.hereliesaz.geministrator.memory.MemoryComputePreference
import com.hereliesaz.geministrator.memory.MemoryMicroAgentModelSpec
import com.hereliesaz.geministrator.orchestration.OrchestrationAgentRole
import com.hereliesaz.geministrator.orchestration.OrchestrationAgentRuntime
import com.hereliesaz.geministrator.orchestration.OrchestrationEpoch8ModelCatalog
import com.hereliesaz.geministrator.orchestration.OrchestrationPacket
import com.hereliesaz.geministrator.orchestration.OrchestrationPlan
import com.hereliesaz.geministrator.orchestration.validateAgainst
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.nio.ShortBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

internal class AndroidOrchestrationAgentRuntime(
    private val installer: AndroidOrchestrationModelInstaller,
    cacheDirectory: java.io.File,
) : OrchestrationAgentRuntime, AutoCloseable {
    private val json = Json { ignoreUnknownKeys = true }
    private val environment = OrtEnvironment.getEnvironment()
    private val sessionManager = AndroidOrtMemorySessionManager(
        computePreference = MemoryComputePreference.AUTO,
        profileDirectory = java.io.File(cacheDirectory, "orchestration-ort-profile"),
    )

    override suspend fun plan(packet: OrchestrationPacket): OrchestrationPlan =
        invoke(OrchestrationAgentRole.Planner, packet).validateAgainst(packet)

    override suspend fun repair(packet: OrchestrationPacket): OrchestrationPlan =
        invoke(OrchestrationAgentRole.PlanRepair, packet).validateAgainst(packet)

    private suspend fun invoke(
        role: OrchestrationAgentRole,
        packet: OrchestrationPacket,
    ): OrchestrationPlan = withContext(Dispatchers.Default) {
        val installed = installer.ensureInstalled(role)
        val bundle = OrchestrationEpoch8ModelCatalog.bundleFor(role)
        val modelSpec = MemoryMicroAgentModelSpec(
            modelId = bundle.runtimeArtifactId,
            quantization = bundle.quantization,
        )
        val prepared = sessionManager.sessionFor(installed.onnxModel.absolutePath, modelSpec)
        val prompt = buildPrompt(role, packet)
        HuggingFaceTokenizer.newInstance(installed.root.toPath()).use { tokenizer ->
            val generated = generate(
                session = prepared.session,
                tokenizer = tokenizer,
                modelRoot = installed.root,
                prompt = prompt,
                maxNewTokens = MAX_NEW_TOKENS,
            )
            prepared.captureExecutionProfile()
            decodePlan(generated)
        }
    }

    private fun buildPrompt(role: OrchestrationAgentRole, packet: OrchestrationPacket): String {
        val roleInstruction = when (role) {
            OrchestrationAgentRole.Planner ->
                "Create an executable dependency-correct DAG for the objective. Use only availableAgents role ids."
            OrchestrationAgentRole.PlanRepair ->
                "Repair the current DAG after the reported failures. Preserve valid completed work and use only availableAgents role ids."
        }
        val schema = """{"steps":[{"id":"step-id","name":"short name","objective":"bounded worker objective","roleId":"available-role-id","dependsOn":["prior-step-id"],"requiresHumanApproval":false}]}"""
        return buildString {
            append("<|im_start|>system\n")
            append("You are Haive's bounded orchestration control model. ")
            append(roleInstruction)
            append(" Return exactly one JSON object and no markdown or prose. Output schema: ")
            append(schema)
            append("<|im_end|>\n<|im_start|>user\n")
            append(json.encodeToString(packet))
            append("<|im_end|>\n<|im_start|>assistant\n")
        }
    }

    private fun decodePlan(generated: String): OrchestrationPlan {
        val start = generated.indexOf('{')
        val end = generated.lastIndexOf('}')
        require(start >= 0 && end > start) { "Orchestration model did not return a JSON object" }
        return json.decodeFromString(generated.substring(start, end + 1))
    }

    private fun generate(
        session: OrtSession,
        tokenizer: HuggingFaceTokenizer,
        modelRoot: java.io.File,
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
        try {
            val initialOwned = mutableListOf<OnnxTensor>()
            val initialInputs = buildInputs(
                session = session,
                tokenIds = promptIds,
                totalLength = totalLength,
                previousResult = null,
                config = config,
                owned = initialOwned,
            )
            activeResult = try {
                session.run(initialInputs)
            } finally {
                initialOwned.forEach(OnnxTensor::close)
            }

            for (ignored in 0 until maxNewTokens) {
                val currentResult = requireNotNull(activeResult)
                val nextToken = argmaxLastLogit(currentResult)
                if (nextToken in stopIds) break
                generated += nextToken
                totalLength += 1

                val owned = mutableListOf<OnnxTensor>()
                val nextInputs = buildInputs(
                    session = session,
                    tokenIds = longArrayOf(nextToken),
                    totalLength = totalLength,
                    previousResult = currentResult,
                    config = config,
                    owned = owned,
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
    ): Map<String, OnnxTensor> {
        val inputs = linkedMapOf<String, OnnxTensor>()
        session.inputInfo.forEach { (name, nodeInfo) ->
            val tensorInfo = nodeInfo.info as? TensorInfo
                ?: error("Unsupported non-tensor orchestration input $name")
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
                else -> error("Unsupported orchestration model input $name")
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
        val logits = result.get("logits").orElseThrow {
            IllegalStateException("Orchestration model output logits is missing")
        } as? OnnxTensor ?: error("Orchestration logits output is not a tensor")
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
            ?: error("Installed orchestration model has no config.json")
        val objectValue = json.parseToJsonElement(configFile.readText()) as? JsonObject
            ?: error("Invalid orchestration model config.json")
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

    override fun close() {
        sessionManager.close()
    }

    private data class ModelConfig(
        val numKeyValueHeads: Int,
        val headDim: Int,
    )

    private companion object {
        const val MAX_NEW_TOKENS = 768
    }
}
