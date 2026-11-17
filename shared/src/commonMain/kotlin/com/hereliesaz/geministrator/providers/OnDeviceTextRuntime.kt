package com.hereliesaz.geministrator.providers

/** Tokens of context the Qwen2.5-0.5B LiteRT-LM model is built for (its `ekv1280` KV cache), prompt and answer together. */
const val LITERT_LM_CONTEXT_TOKENS: Int = 1_280

/** Room left in the context for the chat template and the system prompt, on top of the task text. */
private const val LITERT_LM_PROMPT_OVERHEAD_TOKENS: Int = 128

/**
 * The most task text routed to the LiteRT-LM runtime: the context minus the default answer length and
 * the template overhead. Tasks above it stay on the larger-context ONNX runtime or go to a source.
 */
const val LITERT_LM_MAX_INPUT_TOKENS: Int =
    LITERT_LM_CONTEXT_TOKENS - OnDeviceTextAgentProvider.DEFAULT_MAX_NEW_TOKENS - LITERT_LM_PROMPT_OVERHEAD_TOKENS

/** The inference engines that can sit behind [OnDeviceTextGenerator], in the order a platform prefers them. */
enum class OnDeviceTextRuntime(
    /** Shown in the Keeper's room, under This device. */
    val label: String,
    /** The most task text (in [OnDeviceTextAgentProvider.estimateTokens] units) this runtime is routed. */
    val maxInputTokens: Int,
) {
    LiteRtLm("LiteRT-LM", LITERT_LM_MAX_INPUT_TOKENS),
    OnnxRuntime("ONNX Runtime", OnDeviceTextAgentProvider.DEFAULT_MAX_INPUT_TOKENS),
}

/** A generator that knows which runtime it would use now; the provider and the status line read it. */
interface OnDeviceTextRuntimeSource {
    /** The runtime the next generation would run on, or null when none is ready. */
    fun activeRuntime(): OnDeviceTextRuntime?

    /** The prompt-prefix cache stats of that runtime's generator, or null when it keeps none. */
    fun activePrefixStats(): PromptPrefixStats? = null
}

/**
 * This runtime cannot serve the call (its native library or model did not load, or the task does not
 * fit its context), so the next runtime may try. Never thrown for a memory or thermal refusal
 * ([OnDeviceGenerationDeclined]) or a timeout: those must not move the work onto another local engine.
 */
class OnDeviceRuntimeUnavailableException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The device declined a new local generation (too hot, or too little free memory). Not retried elsewhere. */
class OnDeviceGenerationDeclined(message: String) : Exception(message)

/** A local generation ran past its time limit and was cancelled. */
class OnDeviceGenerationTimedOut(message: String) : Exception(message)

/** The Qwen chat template the local instruct models were trained on. */
object QwenChatPrompt {
    fun format(system: String, user: String): String = buildString {
        append("<|im_start|>system\n").append(system).append("<|im_end|>\n")
        append("<|im_start|>user\n").append(user).append("<|im_end|>\n")
        append("<|im_start|>assistant\n")
    }

    /** [format] cut in two: [head] ends where [stablePrefix] ends, [tail] is the rest; `head + tail` equals [format]. */
    data class Split(val head: String, val tail: String)

    /**
     * The same prompt as `format(system, stablePrefix + dynamic)`, split after [stablePrefix]. The cut must
     * sit at a blank-line boundary with a letter after it (see [OnDeviceTextAgentProvider.renderTaskParts]),
     * so the tokenizer sees the same tokens whether the halves are read together or one after the other.
     */
    fun formatSplit(system: String, stablePrefix: String, dynamic: String): Split = Split(
        head = "<|im_start|>system\n$system<|im_end|>\n<|im_start|>user\n$stablePrefix",
        tail = "$dynamic<|im_end|>\n<|im_start|>assistant\n",
    )

    internal val MARKERS = listOf("<|im_start|>", "<|im_end|>", "<|endoftext|>")
}

/**
 * Cleans a generation: cuts at the first end-of-turn marker, drops any other chat marker the engine
 * let through, and trims. The ONNX generators decode with special tokens skipped and then trim; this
 * gives a LiteRT-LM answer the same shape.
 */
fun sanitizeOnDeviceText(raw: String): String {
    var text = raw
    val end = text.indexOf("<|im_end|>")
    if (end >= 0) text = text.substring(0, end)
    QwenChatPrompt.MARKERS.forEach { marker -> text = text.replace(marker, "") }
    return text.trim()
}

/** What the device can spare right now, read by the platform. */
data class OnDeviceResourceSnapshot(
    /** Free memory the system would give a new allocation, in bytes; null when it cannot be read. */
    val availableMemoryBytes: Long?,
    /** The system says it is already short of memory. */
    val memoryLow: Boolean = false,
    /** The device is hot enough that new CPU-bound work is declined. */
    val thermalHot: Boolean = false,
)

fun interface OnDeviceResourceProbe {
    fun snapshot(): OnDeviceResourceSnapshot
}

/** The pure decisions behind "may a local generation start" and "may the model be loaded". */
object OnDeviceResourceGate {
    /**
     * Free memory required before the LiteRT-LM model is loaded: Google measures about 1.4 GB peak
     * resident memory for this model on CPU, so this keeps half as much again free.
     */
    const val LITERT_LM_MIN_AVAILABLE_MEMORY_BYTES: Long = 2L * 1024L * 1024L * 1024L

    private const val MEBIBYTE = 1024L * 1024L

    /** Why a new local generation should not start (the device is hot), or null when it may. */
    fun thermalHold(snapshot: OnDeviceResourceSnapshot): String? =
        if (snapshot.thermalHot) {
            "The device is running hot, so it is not starting new on-device generations until it cools down."
        } else {
            null
        }

    /** Why the model must not be loaded now (too little memory), or null when it may. */
    fun memoryRefusal(snapshot: OnDeviceResourceSnapshot, requiredBytes: Long): String? {
        val available = snapshot.availableMemoryBytes
        return when {
            snapshot.memoryLow -> "The system is low on memory, so the on-device model is not loaded."
            available == null -> "Free memory could not be read, so the on-device model is not loaded."
            available < requiredBytes ->
                "Only ${available / MEBIBYTE} MiB of memory is free and the on-device model needs about " +
                    "${requiredBytes / MEBIBYTE} MiB, so it is not loaded."
            else -> null
        }
    }
}
