package com.hereliesaz.geministrator.providers.llm

import com.hereliesaz.geministrator.memory.MEMORY_MESSAGE_MARKER
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.pluginOrNull
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.preparePost
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A piece of a streamed generation: the model's visible reasoning, or its answer. */
sealed interface TextGenerationChunk {
    val text: String

    data class Thinking(override val text: String) : TextGenerationChunk

    data class Text(override val text: String) : TextGenerationChunk
}

/** A streaming request refused before any output (HTTP status and body), so it can be retried. */
internal class StreamRejected(val status: Int, val body: String, operation: String) :
    IllegalStateException("Unable to $operation: HTTP $status${body.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}")

/**
 * POSTs the request [configure] sets up and reads the response as server-sent events, calling [onEvent] with each event's
 * name (null when unnamed) and data. A non-2xx response throws [StreamRejected] before any event.
 * Streams may run long, so the request timeout is raised to [STREAM_TIMEOUT_MILLIS].
 */
internal suspend fun HttpClient.postServerSentEvents(
    url: String,
    operation: String,
    configure: suspend HttpRequestBuilder.() -> Unit,
    onEvent: suspend (event: String?, data: String) -> Unit,
) {
    val timeouts = pluginOrNull(HttpTimeout) != null
    preparePost(url) {
        if (timeouts) timeout { requestTimeoutMillis = STREAM_TIMEOUT_MILLIS }
        configure()
    }.execute { response ->
        if (response.status.value !in 200..299) {
            throw StreamRejected(response.status.value, response.bodyAsText().take(800), operation)
        }
        val channel = response.bodyAsChannel()
        var event: String? = null
        val data = StringBuilder()
        suspend fun dispatch() {
            if (data.isNotEmpty()) onEvent(event, data.toString())
            event = null
            data.clear()
        }
        while (true) {
            val line = channel.readUTF8Line() ?: break
            when {
                line.isEmpty() -> dispatch()
                line.startsWith(":") -> Unit
                line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.removePrefix("data:").removePrefix(" "))
                }
            }
        }
        dispatch()
    }
}

/** True when a refusal reads as the reasoning option itself being unsupported or not allowed. */
internal fun StreamRejected.rejectsReasoning(): Boolean =
    status in 400..403 && REASONING_REJECTION.containsMatchIn(body)

private val REASONING_REJECTION = Regex(
    "thinking|reasoning|summary|thought|budget_tokens|adaptive|verif|stream_options|include_usage|not supported|unsupported|unknown (parameter|field)|extra inputs|unrecognized",
    RegexOption.IGNORE_CASE,
)

internal const val STREAM_TIMEOUT_MILLIS = 15L * 60 * 1000

/**
 * Splits `<think>…</think>` spans out of streamed answer text (DeepSeek-R1, Qwen and other open
 * reasoning models served over OpenAI-compatible endpoints put their reasoning inline). Tags split
 * across chunks are held back until they can be read.
 */
internal class ThinkTagSplitter {
    private var inThink = false
    private val pending = StringBuilder()

    fun accept(text: String): List<TextGenerationChunk> {
        pending.append(text)
        val out = mutableListOf<TextGenerationChunk>()
        while (true) {
            val tag = if (inThink) CLOSE else OPEN
            val at = pending.indexOf(tag)
            if (at >= 0) {
                emit(out, pending.substring(0, at))
                pending.deleteRange(0, at + tag.length)
                inThink = !inThink
                continue
            }
            // Keep a tail that might be the start of the tag.
            val keep = (1 until tag.length).lastOrNull { pending.endsWith(tag.substring(0, it)) } ?: 0
            emit(out, pending.substring(0, pending.length - keep))
            pending.deleteRange(0, pending.length - keep)
            return out
        }
    }

    fun finish(): List<TextGenerationChunk> {
        val out = mutableListOf<TextGenerationChunk>()
        emit(out, pending.toString())
        pending.clear()
        return out
    }

    private fun emit(out: MutableList<TextGenerationChunk>, text: String) {
        if (text.isEmpty()) return
        out += if (inThink) TextGenerationChunk.Thinking(text) else TextGenerationChunk.Text(text)
    }

    private companion object {
        const val OPEN = "<think>"
        const val CLOSE = "</think>"
    }
}

/**
 * One streamed generation that an agent's memory can interrupt.
 *
 * Reasoning is reported through `onThinking` in readable pieces (at a paragraph or sentence end, or
 * every [THINKING_CHUNK_CHARS]), which the provider emits as `AgentEvent.Thinking`. Memory answers
 * those through the session's `sendMessage` with a `⟦memory⟧` message; [offer] queues it, and at
 * the next streamed piece the generation stops and starts again with the original prompt, the
 * reasoning and answer so far, and what memory brought back, so the model continues with the
 * memory in view. At most [maxInterrupts] times per run; after that recalls are declined. Only
 * the final attempt's answer is returned. Usage covers that attempt only (an abandoned stream
 * reports none).
 */
class InterruptibleGeneration(
    private val api: TextGenerationApi,
    private val maxInterrupts: Int = MAX_MEMORY_INTERRUPTS,
) {
    private val mutex = Mutex()
    private val pending = mutableListOf<String>()
    private var active = false
    private var interrupts = 0

    /** Queues a memory message while a generation is streaming; false when nothing can take it. */
    suspend fun offer(message: String): Boolean = mutex.withLock {
        if (!active || !message.startsWith(MEMORY_MESSAGE_MARKER) || interrupts >= maxInterrupts) return@withLock false
        pending += message
        true
    }

    suspend fun run(prompt: String, onThinking: suspend (String) -> Unit): TextGenerationResult {
        mutex.withLock { active = true }
        try {
            var attemptPrompt = prompt
            while (true) {
                val reasoning = StringBuilder()
                val answer = StringBuilder()
                val buffer = StringBuilder()
                suspend fun flush() {
                    val text = buffer.toString().trim()
                    buffer.clear()
                    if (text.isNotEmpty()) onThinking(text)
                }
                try {
                    val result = api.stream(attemptPrompt) { chunk ->
                        when (chunk) {
                            is TextGenerationChunk.Thinking -> {
                                reasoning.append(chunk.text)
                                buffer.append(chunk.text)
                                if (buffer.length >= THINKING_CHUNK_CHARS || buffer.endsWithBreak()) flush()
                            }
                            is TextGenerationChunk.Text -> {
                                if (buffer.isNotEmpty()) flush()
                                answer.append(chunk.text)
                            }
                        }
                        takeRecalls()?.let { throw MemoryInterrupt(it) }
                    }
                    flush()
                    return result
                } catch (interrupt: MemoryInterrupt) {
                    attemptPrompt = continuation(prompt, reasoning.toString(), answer.toString(), interrupt.recalls)
                }
            }
        } finally {
            mutex.withLock {
                active = false
                pending.clear()
            }
        }
    }

    private suspend fun takeRecalls(): List<String>? = mutex.withLock {
        if (pending.isEmpty()) return@withLock null
        interrupts++
        pending.toList().also { pending.clear() }
    }

    private fun StringBuilder.endsWithBreak(): Boolean {
        val tail = trimEnd()
        return tail.length >= MIN_THINKING_CHUNK_CHARS && (endsWith("\n\n") || tail.last() in ".!?")
    }

    /** Thrown from inside the stream callback to abandon the stream; caught by [run] alone. */
    private class MemoryInterrupt(val recalls: List<String>) : RuntimeException("memory interrupt")

    private fun continuation(prompt: String, reasoning: String, answer: String, recalls: List<String>): String = buildString {
        append(prompt)
        append("\n\n")
        if (reasoning.isNotBlank()) {
            append("YOUR REASONING SO FAR\n")
            append(reasoning.trim())
            append("\n\n")
        }
        if (answer.isNotBlank()) {
            append("YOUR ANSWER SO FAR\n")
            append(answer.trim())
            append("\n\n")
        }
        append("MEMORY\n")
        append(recalls.joinToString("\n\n") { it.trim() })
        append("\n\nYou were interrupted by memory. Continue from where you stopped, with what memory brought back in view, ")
        append("and return the complete work product.")
    }

    companion object {
        const val MAX_MEMORY_INTERRUPTS = 3
        const val THINKING_CHUNK_CHARS = 400
        const val MIN_THINKING_CHUNK_CHARS = 80
    }
}
