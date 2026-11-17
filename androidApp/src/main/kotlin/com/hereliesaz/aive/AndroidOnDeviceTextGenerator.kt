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
import com.hereliesaz.geministrator.providers.OrtPrefixBackend
import com.hereliesaz.geministrator.providers.OrtPrefixReuse
import com.hereliesaz.geministrator.providers.OrtPrimedHead
import com.hereliesaz.geministrator.providers.PromptPrefixStats
import com.hereliesaz.geministrator.providers.PromptPrefixSwitch
import com.hereliesaz.geministrator.providers.QwenChatPrompt
import com.hereliesaz.geministrator.providers.OnDeviceTextGenerator
import java.io.File
import java.nio.FloatBuffer
import kotlin.concurrent.Volatile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    /** What one generation works on; the held head outlives it, so the backend reads it only during a call. */
    private class Call(
        val session: OrtSession,
        val tokenizer: HuggingFaceTokenizer,
        val modelRoot: File,
        val extraInputs: Map<String, OnnxTensor>,
        val modelKey: String,
    )

    @Volatile
    private var call: Call? = null

    private fun current(): Call = checkNotNull(call) { "No generation is running" }

    private fun generation(call: Call, text: String, inputTokens: Long): OnDeviceTextGeneration =
        OnDeviceTextGeneration(
            text = text,
            inputTokens = inputTokens,
            outputTokens = call.tokenizer.encode(text).ids.size.toLong(),
        )

    private inner class HeldPrefix(private val held: AndroidOrtHeldHead) : OrtPrimedHead {
        override fun generate(tail: String, maxNewTokens: Int): OnDeviceTextGeneration {
            val c = current()
            val text = generator.generate(
                session = c.session,
                tokenizer = c.tokenizer,
                modelRoot = c.modelRoot,
                prompt = tail,
                maxNewTokens = maxNewTokens,
                extraInputs = c.extraInputs,
                heldHead = held,
            ).trim()
            return generation(c, text, held.length + c.tokenizer.encode(tail).ids.size.toLong())
        }

        override fun close() = held.close()
    }

    private val backend = object : OrtPrefixBackend {
        override fun encode(text: String): LongArray = current().tokenizer.encode(text).ids

        override fun prime(head: String): OrtPrimedHead {
            val c = current()
            return HeldPrefix(generator.prime(c.session, c.tokenizer, c.modelRoot, head, c.extraInputs))
        }

        override fun generateWhole(prompt: String, maxNewTokens: Int): OnDeviceTextGeneration {
            val c = current()
            val text = generator.generate(
                session = c.session,
                tokenizer = c.tokenizer,
                modelRoot = c.modelRoot,
                prompt = prompt,
                maxNewTokens = maxNewTokens,
                extraInputs = c.extraInputs,
            ).trim()
            return generation(c, text, c.tokenizer.encode(prompt).ids.size.toLong())
        }
    }

    private val callLock = Mutex()
    private val reuse = OrtPrefixReuse(backend, adapterId = { call?.modelKey }, switch = PromptPrefixSwitch())

    override fun prefixStats(): PromptPrefixStats = reuse.stats()

    override suspend fun generate(system: String, user: String, maxNewTokens: Int): OnDeviceTextGeneration =
        generate(system, "", user, maxNewTokens)

    override suspend fun generate(
        system: String,
        stablePrefix: String,
        dynamic: String,
        maxNewTokens: Int,
    ): OnDeviceTextGeneration = withContext(Dispatchers.Default) {
        val role = installedRole() ?: error("No local instruct model is installed")
        // Already installed (checked above), so this resolves the files without downloading.
        val installed = installer.ensureInstalled(role)
        // The memory layer's own spec for this clerk, so its cached session is reused.
        val prepared = sessions.sessionFor(installed.onnxModel.absolutePath, MemoryEpoch8ModelCatalog.modelSpec(role))
        val split = QwenChatPrompt.formatSplit(system, stablePrefix, dynamic)
        callLock.withLock {
            HuggingFaceTokenizer.newInstance(installed.root.toPath()).use { tokenizer ->
                val zeroAdapter = zeroAdapterInputs(prepared.session)
                try {
                    call = Call(prepared.session, tokenizer, installed.root, zeroAdapter, installed.onnxModel.absolutePath)
                    if (stablePrefix.isEmpty()) {
                        backend.generateWhole(split.head + split.tail, maxNewTokens)
                    } else {
                        reuse.generate(split.head, split.tail, maxNewTokens)
                    }
                } finally {
                    call = null
                    zeroAdapter.values.forEach(OnnxTensor::close)
                }
            }
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
