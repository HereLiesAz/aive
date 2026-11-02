package com.hereliesaz.aive

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import com.hereliesaz.geministrator.memory.AndroidOrtMemorySessionManager
import com.hereliesaz.geministrator.memory.MemoryEpoch8ModelCatalog
import com.hereliesaz.geministrator.memory.MemoryMicroAgentRole
import com.hereliesaz.geministrator.providers.OnDeviceTextGeneration
import com.hereliesaz.geministrator.providers.OnDeviceTextGenerator
import java.nio.FloatBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Small text tasks on Android, on the instruct model the memory clerks share: the shared base an
 * adapter clerk installed from the Memory swarm, run with zero LoRA inputs so it answers as the plain
 * base. Nothing is downloaded here; with no such base installed the generator is not ready.
 *
 * It uses the memory layer's session manager, so the base is loaded once for both.
 */
internal class AndroidOnDeviceTextGenerator(
    private val installer: AndroidMemoryModelInstaller,
    private val sessions: AndroidOrtMemorySessionManager,
    private val generator: AndroidOrchestrationCausalGenerator = AndroidOrchestrationCausalGenerator(),
    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment(),
) : OnDeviceTextGenerator {
    /** The first installed shared base (an adapter clerk's); merged clerks are task-specific and skipped. */
    private fun installedRole(): MemoryMicroAgentRole? = MemoryEpoch8ModelCatalog.all.firstOrNull { bundle ->
        bundle.adapter != null &&
            bundle.role != MemoryMicroAgentRole.AssociationLinker &&
            installer.isInstalled(bundle.role)
    }?.role

    override fun isReady(): Boolean = installedRole() != null

    override suspend fun generate(system: String, user: String, maxNewTokens: Int): OnDeviceTextGeneration =
        withContext(Dispatchers.Default) {
            val role = installedRole() ?: error("No local instruct model is installed")
            // Already installed (checked above), so this resolves the files without downloading.
            val installed = installer.ensureInstalled(role)
            // The memory layer's own spec for this clerk, so its cached session is reused.
            val prepared = sessions.sessionFor(installed.onnxModel.absolutePath, MemoryEpoch8ModelCatalog.modelSpec(role))
            val prompt = buildString {
                append("<|im_start|>system\n").append(system).append("<|im_end|>\n")
                append("<|im_start|>user\n").append(user).append("<|im_end|>\n")
                append("<|im_start|>assistant\n")
            }
            HuggingFaceTokenizer.newInstance(installed.root.toPath()).use { tokenizer ->
                val zeroAdapter = zeroAdapterInputs(prepared.session)
                val text = try {
                    generator.generate(
                        session = prepared.session,
                        tokenizer = tokenizer,
                        modelRoot = installed.root,
                        prompt = prompt,
                        maxNewTokens = maxNewTokens,
                        extraInputs = zeroAdapter,
                    ).trim()
                } finally {
                    zeroAdapter.values.forEach(OnnxTensor::close)
                }
                OnDeviceTextGeneration(
                    text = text,
                    inputTokens = tokenizer.encode(prompt).ids.size.toLong(),
                    outputTokens = tokenizer.encode(text).ids.size.toLong(),
                )
            }
        }

    /**
     * Zero-valued tensors for the base graph's LoRA inputs (every float input the generation loop
     * does not feed itself). A zero low-rank update leaves the base weights as they are.
     */
    private fun zeroAdapterInputs(session: OrtSession): Map<String, OnnxTensor> {
        val tensors = linkedMapOf<String, OnnxTensor>()
        try {
            session.inputInfo.forEach { (name, node) ->
                if (name in GENERATOR_INPUTS || name.startsWith("past_key_values.")) return@forEach
                val info = node.info as? TensorInfo ?: return@forEach
                if (info.type != OnnxJavaType.FLOAT) return@forEach
                // A symbolic dimension (the adapter rank) becomes 1: zeros of any rank add nothing.
                val shape = info.shape.map { if (it < 1L) 1L else it }.toLongArray()
                val count = shape.fold(1L, Long::times)
                require(count <= Int.MAX_VALUE) { "Adapter input $name is too large" }
                tensors[name] = OnnxTensor.createTensor(environment, FloatBuffer.allocate(count.toInt()), shape)
            }
        } catch (failure: Throwable) {
            tensors.values.forEach(OnnxTensor::close)
            throw failure
        }
        return tensors
    }

    private companion object {
        val GENERATOR_INPUTS = setOf("input_ids", "attention_mask", "position_ids", "cache_position", "use_cache_branch")
    }
}
