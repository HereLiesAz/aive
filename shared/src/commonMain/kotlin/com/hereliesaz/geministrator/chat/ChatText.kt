package com.hereliesaz.geministrator.chat

import com.hereliesaz.geministrator.control.ControlRedaction

/** Everything that leaves for a chat goes through here: scrubbed, link-free, plain, bounded. */
object ChatText {
    private val link = Regex("""(?i)\b(?:https?://|www\.)\S+""")
    const val LINK_MASK = "[link removed]"

    /**
     * [text] without control and format characters (bidi marks, zero-width characters). Line breaks become spaces unless
     * [keepNewlines]; any other control character is dropped.
     */
    fun stripControls(text: String, keepNewlines: Boolean): String {
        val out = StringBuilder(text.length)
        for (c in text) {
            when {
                c == '\n' || c == '\r' -> out.append(if (keepNewlines && c == '\n') '\n' else ' ')
                c == '\t' -> out.append(' ')
                c.category == CharCategory.CONTROL || c.category == CharCategory.FORMAT -> Unit
                else -> out.append(c)
            }
        }
        return out.toString()
    }

    /** [text] cut to [max] characters (with an ellipsis), never inside a surrogate pair. */
    fun clip(text: String, max: Int): String {
        if (text.length <= max) return text
        var end = (max - 1).coerceAtLeast(0)
        if (end > 0 && text[end - 1].isHighSurrogate()) end -= 1
        return text.substring(0, end) + "…"
    }

    /**
     * Plain text safe to send: key shapes and each of [secrets] removed ([ControlRedaction]), links removed (the keeper opens
     * The Aive, not a link), control characters dropped, at most [max] characters.
     */
    fun safe(text: String, max: Int, secrets: List<String> = emptyList(), keepNewlines: Boolean = true): String {
        // Controls go first, so a key cannot be split by an invisible character to get past the scrubber.
        val scrubbed = ControlRedaction.scrub(stripControls(text, keepNewlines), secrets)
        return clip(link.replace(scrubbed, LINK_MASK).trim(), max)
    }
}
