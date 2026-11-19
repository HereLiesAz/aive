package com.hereliesaz.geministrator.providers.llm

import kotlinx.coroutines.delay
import kotlin.concurrent.Volatile

/**
 * Gemini in Google Search ("AI Mode"), reached the way a person reaches it: the search page, in a browser view on the keeper's own
 * device and connection. There is no key and no API, and nothing is sent anywhere but Google. It answers like a search assistant:
 * one answer per question, no system prompt, no tools, no structured output. So it is a chat and research source, never a creature's model.
 */
object GoogleAiMode {
    const val ID = com.hereliesaz.geministrator.ProviderCatalog.GOOGLE_AI_MODE_ID

    /** The longest question the search page takes in its address; a longer one is refused rather than cut, so nothing is silently lost. */
    const val MAX_QUESTION_CHARS = 1_800

    /** The page that shows the answer to [question] (`udm=50` is AI Mode). The question is percent-encoded; nothing else is added. */
    fun url(question: String): String = "https://www.google.com/search?udm=50&hl=en&q=" + percentEncode(question.trim())

    private fun percentEncode(text: String): String = buildString {
        for (byte in text.encodeToByteArray()) {
            val c = byte.toInt() and 0xFF
            val plain = c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code || c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code
            if (plain) append(c.toChar()) else append('%').append(HEX[c shr 4]).append(HEX[c and 15])
        }
    }

    private const val HEX = "0123456789ABCDEF"

    /** What the visible text of the page says about Google refusing the request, or null when it does not. */
    fun refusal(pageText: String): String? {
        val t = pageText.lowercase()
        return when {
            "unusual traffic" in t || "not a robot" in t || "/sorry/" in t -> "Google asked for a human check (unusual traffic). Open Google in a browser on this connection, pass the check, then try again."
            "before you continue" in t && ("cookies" in t || "consent" in t) -> "Google is asking for cookie consent first."
            "ai mode is not available" in t || "ai mode isn't available" in t -> "AI Mode is not available in this region or account."
            else -> null
        }
    }

    /** The line Google ends every answer with; its presence means the answer has finished writing. */
    private val FINISHED = Regex("""(?i)ai responses may include mistakes""")

    /** Where the answer stops: the end-of-answer line, the ads block, or the follow-up box. */
    private val ENDS = listOf(FINISHED, Regex("""(?m)^\s*Sponsored\s*$"""), Regex("""(?m)^\s*Ask anything\s*$"""))

    /** True once the page text holds a finished answer to [question]. */
    fun finished(pageText: String, question: String): Boolean =
        FINISHED.containsMatchIn(pageText) && answerStart(pageText, question) != null

    private fun answerStart(pageText: String, question: String): Int? {
        val q = question.trim()
        if (q.isEmpty()) return null
        // The page echoes the question above the answer; the answer follows its last echo.
        val at = pageText.lastIndexOf(q)
        if (at >= 0) return at + q.length
        // Long questions are shown shortened: fall back to the first line of the question.
        val head = q.lineSequence().first().take(60)
        return pageText.lastIndexOf(head).takeIf { it >= 0 }?.let { pageText.indexOf('\n', it).takeIf { n -> n >= 0 } }
    }

    /** The answer text of [pageText] for [question], or null while there is none. Cuts the page's chrome, the disclaimer and the ads. */
    fun answer(pageText: String, question: String): String? {
        val start = answerStart(pageText, question) ?: return null
        val tail = pageText.substring(start)
        // Each end marker cuts from the start of its own line, so the disclaimer sentence that leads into it goes too.
        val end = ENDS.mapNotNull { rx -> rx.find(tail)?.range?.first?.let { tail.lastIndexOf('\n', it) + 1 } }.minOrNull() ?: tail.length
        val text = tail.substring(0, end).lines().joinToString("\n") { it.trimEnd() }.trim()
        return text.takeIf { it.length >= 2 }
    }
}

/** What a browser view reports back: the page's visible text so far. */
fun interface AiModePage {
    /** The visible text of the page now (`document.body.innerText`). */
    suspend fun text(): String
}

/** A browser view the platform provides: opens [url] on the keeper's connection and lets the caller read the page as it renders. */
interface AiModeBrowser {
    /** Runs [read] against the page at [url] and closes the view when it returns. [read] polls [AiModePage.text]. */
    suspend fun <T> withPage(url: String, read: suspend (AiModePage) -> T): T
}

/** AI Mode could not answer; [message] says why in words fit for the keeper. */
class GoogleAiModeUnavailable(message: String) : Exception(message)

/**
 * A [TextGenerationApi] over [GoogleAiMode]: asks the question in a browser view, waits for the finished answer and returns its text.
 * Only the prompt's own words go to Google. Free, so it never bills.
 */
class GoogleAiModeApi(
    private val browser: AiModeBrowser,
    private val timeoutMillis: Long = 75_000L,
    private val pollMillis: Long = 700L,
    private val settleMillis: Long = 1_800L,
    private val now: () -> Long,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) : TextGenerationApi {
    override val modelId: String = GoogleAiMode.ID
    override val billsPerToken: Boolean = false

    override suspend fun generate(prompt: String): TextGenerationResult {
        val question = prompt.trim()
        require(question.isNotEmpty()) { "Google AI Mode needs a question" }
        if (question.length > GoogleAiMode.MAX_QUESTION_CHARS) {
            throw GoogleAiModeUnavailable("The question is ${question.length} characters; Google AI Mode takes at most ${GoogleAiMode.MAX_QUESTION_CHARS}.")
        }
        val text = browser.withPage(GoogleAiMode.url(question)) { page ->
            val started = now()
            var last = ""
            var stableSince = started
            var done: String? = null
            while (done == null) {
                val seen = page.text()
                GoogleAiMode.refusal(seen)?.let { throw GoogleAiModeUnavailable(it) }
                val answer = GoogleAiMode.answer(seen, question)
                if (answer != null) {
                    if (answer != last) {
                        last = answer
                        stableSince = now()
                    }
                    // Done once Google marks it finished, or once it has stopped changing for a good while.
                    if (GoogleAiMode.finished(seen, question) || now() - stableSince >= settleMillis * 3) {
                        done = answer
                        continue
                    }
                }
                if (now() - started >= timeoutMillis) {
                    if (last.isEmpty()) throw GoogleAiModeUnavailable("Google did not answer in time.")
                    done = last
                    continue
                }
                pause(pollMillis)
            }
            checkNotNull(done)
        }
        return TextGenerationResult(text = text)
    }
}

/**
 * The browser view this platform provides for Google AI Mode, or null where there is none (the Source then does not appear at all).
 * Set once at start-up by the platform; read when the Sources list and the providers are built.
 */
object GoogleAiModeHost {
    @Volatile
    var browser: AiModeBrowser? = null
}
