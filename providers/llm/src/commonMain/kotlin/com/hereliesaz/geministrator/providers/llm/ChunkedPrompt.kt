package com.hereliesaz.geministrator.providers.llm

/**
 * A conversation with a source that keeps it: each [send] adds one message and returns the source's reply to it. The source
 * remembers what came before (a chat session, a browser thread), so nothing earlier is resent. A source that is stateless and
 * replays the whole exchange on every call is not one of these: splitting a prompt for it saves nothing, since the replay
 * carries every part again.
 */
interface ConversationChannel {
    suspend fun send(message: String): String
}

/** The source did not take a split prompt the way it was asked to; [message] says where and in words fit for the keeper. */
class ChunkedPromptException(message: String) : Exception(message)

/** Splits text into pieces of at most a given length, at the kindest place each can be cut. */
object PromptChunker {
    /**
     * [text] cut into pieces of at most [maxChars], in order, so that joining them gives [text] back exactly. A cut falls, in
     * order of preference, after a blank line, after a line break, after a sentence's full stop, after a space, and only
     * then inside a word; never between the two halves of a surrogate pair.
     */
    fun split(text: String, maxChars: Int): List<String> {
        require(maxChars >= 2) { "A piece must be able to hold at least one character" }
        if (text.length <= maxChars) return listOf(text)
        val pieces = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val hardEnd = minOf(start + maxChars, text.length)
            if (hardEnd == text.length) {
                pieces += text.substring(start)
                break
            }
            var end = cutBefore(text, start, hardEnd)
            // Never end between a surrogate pair's halves.
            if (end > start && text[end - 1].isHighSurrogate()) end -= 1
            if (end <= start) end = hardEnd.coerceAtLeast(start + 1).let { if (text[it - 1].isHighSurrogate() && it - 1 > start) it - 1 else it }
            pieces += text.substring(start, end)
            start = end
        }
        return pieces
    }

    /** Where to cut [text] between [start] and [hardEnd]: just after the last good break in the second half, else at [hardEnd]. */
    private fun cutBefore(text: String, start: Int, hardEnd: Int): Int {
        val floor = start + (hardEnd - start) / 2
        for (marker in BREAKS) {
            val at = text.lastIndexOf(marker, hardEnd - marker.length)
            if (at >= floor) return at + marker.length
        }
        return hardEnd
    }

    private val BREAKS = listOf("\n\n", "\n", ". ", " ")
}

/**
 * Sends a prompt that is longer than a source takes in one message as several, the way a person would paste a long text into
 * a chat: say it comes in parts, send each, and let the source answer "continue" until the last. The source is asked to
 * answer the whole only after the last part. Where it answers early or says something else, the prompt is not guessed at: it
 * fails and says which part.
 */
object ChunkedPrompt {
    /** The most parts one prompt may become: past this the trip is too long to be worth it, and the keeper is told so. */
    const val DEFAULT_MAX_PARTS = 20

    private fun intro(parts: Int) =
        "This request is too long for one message, so it comes in $parts parts. After each part except the last, reply with only " +
            "the word CONTINUE and wait for the next part. After the last part, answer the whole request.\n\n"

    private fun header(part: Int, parts: Int) =
        if (part == parts) "Part $part of $parts (the last part, so answer now):\n" else "Part $part of $parts:\n"

    /** Words added to a part's own text, at most (the intro rides only on the first part; budgeted on all for one size). */
    private val OVERHEAD = intro(99).length + header(99, 99).length

    /**
     * Sends [prompt] on [channel] and returns the reply to its last part. A prompt that fits in [maxMessageChars] goes as it is,
     * with no wrapping. [maxParts] bounds the number of messages.
     */
    suspend fun send(channel: ConversationChannel, prompt: String, maxMessageChars: Int, maxParts: Int = DEFAULT_MAX_PARTS): String {
        if (prompt.length <= maxMessageChars) return channel.send(prompt)
        val room = maxMessageChars - OVERHEAD
        if (room < MIN_PART_CHARS) {
            throw ChunkedPromptException("The source takes only $maxMessageChars characters a message, too few to send a request in parts.")
        }
        val pieces = PromptChunker.split(prompt, room)
        if (pieces.size > maxParts) {
            throw ChunkedPromptException(
                "The request is ${prompt.length} characters, which would take ${pieces.size} parts of at most $room; the limit is $maxParts parts. Shorten it, or use a source that takes more at once.",
            )
        }
        val total = pieces.size
        var reply = ""
        pieces.forEachIndexed { index, piece ->
            val number = index + 1
            val message = (if (number == 1) intro(total) else "") + header(number, total) + piece
            reply = channel.send(message)
            if (number < total && !acknowledges(reply)) {
                // One nudge, in case it was chatty rather than confused; the part itself is not sent again.
                reply = channel.send("Part $number of $total was received. Reply with only the word CONTINUE.")
                if (!acknowledges(reply)) {
                    throw ChunkedPromptException("The source did not take the request in parts: after part $number of $total it answered instead of saying continue.")
                }
            }
        }
        return reply
    }

    /** Whether [reply] is the source saying to go on: the word, bare or with a little around it. */
    internal fun acknowledges(reply: String): Boolean {
        val text = reply.trim().lowercase().trim('.', '!', '"', '\'', '*', '_', '`', ' ', '\n')
        return text == "continue" || (text.length <= SHORT_ACK_CHARS && "continue" in text)
    }

    private const val MIN_PART_CHARS = 200
    private const val SHORT_ACK_CHARS = 40
}

/**
 * A [TextGenerationApi] over a source that takes at most [maxMessageChars] a message and keeps its conversation: each
 * [generate] opens a fresh conversation ([open]) and sends the prompt in as many parts as it needs ([ChunkedPrompt]).
 */
class ChunkingTextGenerationApi(
    private val open: suspend () -> ConversationChannel,
    private val maxMessageChars: Int,
    override val modelId: String? = null,
    override val billsPerToken: Boolean = true,
    private val maxParts: Int = ChunkedPrompt.DEFAULT_MAX_PARTS,
) : TextGenerationApi {
    override suspend fun generate(prompt: String): TextGenerationResult =
        TextGenerationResult(text = ChunkedPrompt.send(open(), prompt, maxMessageChars, maxParts))
}
