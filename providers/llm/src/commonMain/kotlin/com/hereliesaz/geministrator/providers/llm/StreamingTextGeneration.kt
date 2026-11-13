package com.hereliesaz.geministrator.providers.llm

import com.hereliesaz.geministrator.net.boundedTextOrTruncated
import com.hereliesaz.geministrator.net.BoundedLineReader
import com.hereliesaz.geministrator.net.parseRetryAfterMillis
import com.hereliesaz.geministrator.net.RetryAfterHint
import io.ktor.http.HttpHeaders
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A piece of a streamed generation: the model's visible reasoning, or its answer. */
sealed interface TextGenerationChunk {
    val text: String

    data class Thinking(override val text: String) : TextGenerationChunk

    data class Text(override val text: String) : TextGenerationChunk
}

/** A streaming request refused before any output (HTTP status and body), so it can be retried. */
internal class StreamRejected(val status: Int, val body: String, operation: String, override val retryAfterMillis: Long? = null) :
    IllegalStateException("Unable to $operation: HTTP $status${body.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}"), RetryAfterHint

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
            throw StreamRejected(
                response.status.value,
                response.boundedTextOrTruncated(ERROR_BODY_MAX_BYTES).redactSecrets().take(800),
                operation,
                parseRetryAfterMillis(response.headers[HttpHeaders.RetryAfter]),
            )
        }
        // Lines are read with a byte cap: a server (or a proxy gone wrong) that never ends a line, or never ends the
        // stream, costs at most the cap in memory instead of all of it.
        val lines = BoundedLineReader(response.bodyAsChannel(), STREAM_MAX_WIRE_BYTES)
        var event: String? = null
        val data = StringBuilder()
        suspend fun dispatch() {
            if (data.isNotEmpty()) onEvent(event, data.toString())
            event = null
            data.clear()
        }
        while (true) {
            val line = lines.readLine() ?: break
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

/**
 * True when a refusal reads as the reasoning option itself being unsupported or not allowed. Only
 * request-shape refusals (400, 422) that name one of the options this client adds count: an
 * authentication or account error (401, 403, "unrecognized API key", "verify your account") must not
 * permanently switch reasoning or usage off for the adapter.
 */
internal fun StreamRejected.rejectsReasoning(): Boolean =
    (status == 400 || status == 422) && REASONING_REJECTION.containsMatchIn(body)

private val REASONING_REJECTION = Regex(
    "thinking|reasoning|thought|budget_tokens|adaptive|stream_options|include_usage",
    RegexOption.IGNORE_CASE,
)

/**
 * Every streaming protocol ends with an explicit terminal event. A stream that reaches EOF without
 * one was cut off (connection reset, proxy timeout), and the partial text must not be returned as a
 * complete answer.
 */
internal fun requireStreamTerminated(terminated: Boolean, providerName: String) {
    if (!terminated) error("$providerName stream ended before the response was complete; output is incomplete")
}

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
 * the final attempt's answer is returned. Usage covers the final attempt plus an estimate for each
 * abandoned one (a cut-off stream reports none, but the provider billed it).
 */
class InterruptibleGeneration(
    private val api: TextGenerationApi,
    private val maxInterrupts: Int = MAX_MEMORY_INTERRUPTS,
) {
    private val mutex = Mutex()
    private val pending = mutableListOf<String>()
    private var active = false
    private var interrupts = 0
    private var cancelled = false

    /** Thrown inside the stream callback when [cancel] was called: the stream is abandoned and the run ends. */
    class GenerationCancelled : CancellationException("The generation was cancelled")

    /**
     * Stops the generation: the streaming attempt in progress is abandoned at its next piece (a provider keeps
     * billing for a stream that is read to its end), and a run not yet started never makes its request.
     */
    suspend fun cancel() {
        mutex.withLock {
            cancelled = true
            pending.clear()
        }
    }

    private suspend fun isCancelled(): Boolean = mutex.withLock { cancelled }

    /** Queues a memory message while a generation is streaming; false when nothing can take it. */
    suspend fun offer(message: String): Boolean = mutex.withLock {
        if (!active || !message.startsWith(MEMORY_MESSAGE_MARKER) || interrupts >= maxInterrupts) return@withLock false
        pending += message
        true
    }

    suspend fun run(prompt: String, onThinking: suspend (String) -> Unit): TextGenerationResult =
        run(CacheablePrompt(dynamicSuffix = prompt), onThinking)

    /**
     * Runs a prefix-structured prompt. A memory continuation extends only the dynamic part, so the
     * stable prefix (and any provider cache of it) is the same on every attempt.
     */
    suspend fun run(prompt: CacheablePrompt, onThinking: suspend (String) -> Unit): TextGenerationResult {
        mutex.withLock { active = true }
        try {
            if (isCancelled()) throw GenerationCancelled()
            var attemptPrompt = prompt
            var abandonedInput = 0L
            var abandonedOutput = 0L
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
                        if (isCancelled()) throw GenerationCancelled()
                        takeRecalls()?.let { throw MemoryInterrupt(it) }
                    }
                    flush()
                    return if (abandonedInput == 0L && abandonedOutput == 0L) {
                        result
                    } else {
                        // An abandoned stream is billed too, and reports no usage: its estimate is added so the interrupts count.
                        result.copy(
                            inputTokens = (result.inputTokens ?: 0L) + abandonedInput,
                            outputTokens = (result.outputTokens ?: 0L) + abandonedOutput,
                        )
                    }
                } catch (interrupt: MemoryInterrupt) {
                    abandonedInput += estimateTokens(attemptPrompt.text.length)
                    abandonedOutput += estimateTokens(reasoning.length + answer.length)
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

    /** A deliberately high estimate (three characters a token) for a stream that was cut off and so reported nothing. */
    private fun estimateTokens(chars: Int): Long = (chars + 2L) / 3L

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

    private fun continuation(prompt: CacheablePrompt, reasoning: String, answer: String, recalls: List<String>): CacheablePrompt =
        prompt.copy(dynamicSuffix = continuationText(prompt.dynamicSuffix, reasoning, answer, recalls))

    private fun continuationText(prompt: String, reasoning: String, answer: String, recalls: List<String>): String = buildString {
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

/** Most bytes one streamed response may send, event framing included: far past any real answer, short of a runaway. */
internal const val STREAM_MAX_WIRE_BYTES: Long = 64L * 1024 * 1024

/** Most of an error response's body that is read, since only the start of it is quoted. */
internal const val ERROR_BODY_MAX_BYTES: Int = 16 * 1024

/** Most characters of answer plus reasoning one stream may produce (about half a million tokens): a runaway generation stops here. */
internal const val STREAM_MAX_OUTPUT_CHARS: Int = 2_000_000

/**
 * [this] wrapped so the characters passed through it are counted: past [maxChars] the stream is abandoned with an
 * [OutputTruncatedException] (it says "truncated", so it classifies as output too long) instead of growing without end.
 * The provider has billed what was produced, so the caller still prices the attempt as a failure.
 */
internal fun (suspend (TextGenerationChunk) -> Unit).cappedOutput(
    providerName: String,
    maxChars: Int = STREAM_MAX_OUTPUT_CHARS,
): suspend (TextGenerationChunk) -> Unit {
    var produced = 0L
    return { chunk ->
        produced += chunk.text.length
        if (produced > maxChars) {
            throw OutputTruncatedException(
                "$providerName stream was truncated at the output limit of $maxChars characters; output is incomplete",
            )
        }
        this(chunk)
    }
}
