package com.hereliesaz.geministrator

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.hereliesaz.geministrator.orchestration.OrchestrationDecisionModel
import com.hereliesaz.geministrator.orchestration.OrchestrationQuestion
import java.io.File
import java.nio.LongBuffer
import kotlin.math.exp
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The released orchestration decision model on ONNX Runtime: a tiny INT8 encoder whose single output
 * holds every question's logits side by side, temperature already applied (`tools/decision_training`).
 *
 * One forward pass per text answers every question asked of it. Returns null (the caller's heuristic
 * stands) when the model is not installed or fails.
 */
internal class DesktopOrchestrationDecisionModel(
    /** The installed model directory, or null when it is not released or not installed. */
    private val installedRoot: () -> File?,
) : OrchestrationDecisionModel, AutoCloseable {
    private val environment = OrtEnvironment.getEnvironment()
    private var loaded: Loaded? = null

    private class Loaded(
        val root: File,
        val session: OrtSession,
        val tokenizer: HuggingFaceTokenizer,
        val offsets: Map<String, Int>,
        val thresholds: Map<String, Double>,
    )

    @Serializable
    private data class DecisionConfig(val maxLength: Int, val questions: List<DecisionHead>)

    @Serializable
    private data class DecisionHead(val id: String, val options: List<String>, val offset: Int, val minConfidence: Double? = null)

    fun isInstalled(): Boolean = installedRoot() != null

    @Synchronized
    override fun minConfidence(question: OrchestrationQuestion): Double? =
        runCatching { load()?.thresholds?.get(question.id) }.getOrNull()

    @Synchronized
    override fun answer(text: String, questions: List<OrchestrationQuestion>): Map<OrchestrationQuestion, List<Double>>? =
        runCatching {
            val model = load() ?: return null
            val ids = model.tokenizer.encode(text).ids
            if (ids.isEmpty()) return null
            val shape = longArrayOf(1, ids.size.toLong())
            val inputIds = OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), shape)
            val mask = OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(ids.size) { 1L }), shape)
            val logits = try {
                model.session.run(mapOf("input_ids" to inputIds, "attention_mask" to mask)).use { result ->
                    val tensor = result.get("logits").orElse(null) as? OnnxTensor ?: return null
                    val buffer = tensor.floatBuffer
                    FloatArray(buffer.remaining()).also(buffer::get)
                }
            } finally {
                inputIds.close()
                mask.close()
            }
            questions.mapNotNull { question ->
                val offset = model.offsets[question.id] ?: return@mapNotNull null
                val size = question.options.size
                if (offset + size > logits.size) return@mapNotNull null
                question to softmax(logits, offset, size)
            }.toMap()
        }.getOrNull()

    private fun load(): Loaded? {
        val root = installedRoot() ?: return null.also { release() }
        loaded?.takeIf { it.root == root }?.let { return it }
        release()
        val config = json.decodeFromString<DecisionConfig>(File(root, "config.json").readText())
        // A head whose options differ from the app's question would be read wrongly: leave it out.
        val heads = config.questions.filter { head -> OrchestrationQuestion.byId(head.id)?.options == head.options }
        val offsets = heads.associate { it.id to it.offset }
        val thresholds = heads.mapNotNull { head -> head.minConfidence?.let { head.id to it } }.toMap()
        val tokenizer = HuggingFaceTokenizer.builder()
            .optTokenizerPath(root.toPath())
            .optMaxLength(config.maxLength)
            .optTruncation(true)
            .optPadding(false)
            .build()
        val session = environment.createSession(File(root, "model.onnx").absolutePath, OrtSession.SessionOptions())
        return Loaded(root, session, tokenizer, offsets, thresholds).also { loaded = it }
    }

    private fun release() {
        loaded?.let {
            it.session.close()
            it.tokenizer.close()
        }
        loaded = null
    }

    @Synchronized
    override fun close() = release()

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        fun softmax(logits: FloatArray, offset: Int, size: Int): List<Double> {
            val max = (offset until offset + size).maxOf { logits[it] }.toDouble()
            val exps = (offset until offset + size).map { exp(logits[it] - max) }
            val sum = exps.sum()
            return exps.map { it / sum }
        }
    }
}
