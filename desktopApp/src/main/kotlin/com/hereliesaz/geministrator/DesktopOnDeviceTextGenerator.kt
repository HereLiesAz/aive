package com.hereliesaz.geministrator

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import com.hereliesaz.geministrator.memory.DesktopOrtMemorySessionManager
import com.hereliesaz.geministrator.memory.MemoryEpoch8ModelCatalog
import com.hereliesaz.geministrator.memory.MemoryMicroAgentRole
import com.hereliesaz.geministrator.memory.MemoryModelReleaseBundle
import com.hereliesaz.geministrator.providers.OnDeviceTextGeneration
import com.hereliesaz.geministrator.providers.OnDeviceTextGenerator
import java.io.File
import java.nio.FloatBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Small text tasks on desktop, on the instruct model the memory clerks share: the shared base an
 * adapter clerk installed from the Memory swarm, run with zero LoRA inputs so it answers as the plain
 * base. Nothing is downloaded here; with no such base installed the generator is not ready.
 *
 * It uses the memory layer's session manager, so the base is loaded once for both.
 */
internal class DesktopOnDeviceTextGenerator(
    private val installer: DesktopMemoryModelInstaller,
    private val sessions: DesktopOrtMemorySessionManager,
    private val generator: DesktopOrtCausalGenerator = DesktopOrtCausalGenerator(),
    private val environment: OrtEnvironment = OrtEnvironment.getEnvironment(),
) : OnDeviceTextGenerator {
    private class InstalledBase(val bundle: MemoryModelReleaseBundle, val root: File)

    /** The first installed shared base (an adapter clerk's); merged clerks are task-specific and skipped. */
    private fun installedBase(): InstalledBase? = MemoryEpoch8ModelCatalog.all.firstNotNullOfOrNull { bundle ->
        if (bundle.adapter == null || bundle.role == MemoryMicroAgentRole.AssociationLinker) {
            null
        } else {
            installer.installed(bundle.role)?.let { InstalledBase(bundle, it) }
        }
    }

    override fun isReady(): Boolean = installedBase() != null

    override suspend fun generate(system: String, user: String, maxNewTokens: Int): OnDeviceTextGeneration =
        withContext(Dispatchers.Default) {
            val base = installedBase() ?: error("No local instruct model is installed")
            val modelFile = installer.modelFile(base.root) ?: error("The installed local model has no ONNX file")
            val tokenizerRoot = installer.tokenizerRoot(base.root) ?: error("The installed local model has no tokenizer.json")
            // The memory layer's own spec for this clerk, so its cached session is reused.
            val prepared = sessions.sessionFor(modelFile.absolutePath, MemoryEpoch8ModelCatalog.modelSpec(base.bundle.role))
            val prompt = onDeviceChatPrompt(system, user)
            HuggingFaceTokenizer.newInstance(tokenizerRoot.toPath()).use { tokenizer ->
                val zeroAdapter = zeroAdapterInputs(prepared.session)
                val text = try {
                    generator.generate(
                        session = prepared.session,
                        tokenizer = tokenizer,
                        modelRoot = tokenizerRoot,
                        prompt = prompt,
                        maxNewTokens = maxNewTokens,
                        writingLabel = "Writing",
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

/** The Qwen chat template the local instruct models were trained on. */
internal fun onDeviceChatPrompt(system: String, user: String): String = buildString {
    append("<|im_start|>system\n").append(system).append("<|im_end|>\n")
    append("<|im_start|>user\n").append(user).append("<|im_end|>\n")
    append("<|im_start|>assistant\n")
}
