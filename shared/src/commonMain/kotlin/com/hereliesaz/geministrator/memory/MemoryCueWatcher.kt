package com.hereliesaz.geministrator.memory

/*
 * Recall triggers read from an agent's own thinking and output.
 *
 * Memory offers cues as #tags. An agent follows one to its summaries by:
 *  - Explicit: writing the #tag (`#gradle-cache`).
 *  - Phrase: "Let me see what I remember about …" — for agents whose thinking is not visible.
 *  - Echo: using a just-offered tag's word within [MemoryAttentionPolicy.echoWindowTokens].
 *  - Doubling: using a word twice within [MemoryAttentionPolicy.doublingWindowTokens].
 *
 * Echo and doubling are implicit, so a word found in too many memories ("build", "test") cannot
 * fire them: the frequency filter ([MemoryAttentionPolicy.commonWordShare]) decides what is common.
 * Explicit tags and the phrase are deliberate and never filtered.
 */

internal sealed interface MemoryRecallTrigger {
    /** The tag or topic to recall. */
    val subject: String

    /** Asked for on purpose: not filtered for frequency, not held back by the dial. */
    val deliberate: Boolean

    data class Explicit(override val subject: String) : MemoryRecallTrigger {
        override val deliberate = true
    }

    data class Phrase(override val subject: String) : MemoryRecallTrigger {
        override val deliberate = true
    }

    /** Following a cue memory itself just offered; the dial already let the cue through. */
    data class Echo(override val subject: String) : MemoryRecallTrigger {
        override val deliberate = true
    }

    data class Doubled(override val subject: String) : MemoryRecallTrigger {
        override val deliberate = false
    }
}

/** One agent's trigger state: where in its token stream cues were offered and words were used. */
internal class MemoryCueWatcher(private val policy: MemoryAttentionPolicy = MemoryAttentionPolicy()) {
    private var position = 0L
    private val offered = LinkedHashMap<String, Long>()
    private val recent = ArrayDeque<Pair<String, Long>>()
    private val lastDoubled = HashMap<String, Long>()

    /** Records cues just offered to the agent, at the current position. */
    fun offered(tags: Collection<String>) {
        tags.forEach { offered[normalizeTag(it)] = position }
        while (offered.size > MAX_OFFERED) offered.remove(offered.keys.first())
    }

    /**
     * Reads one chunk of thinking or output and returns its triggers, deliberate ones first, one per
     * subject. [isCommon] answers the frequency filter for a single word.
     */
    suspend fun read(text: String, isCommon: suspend (String) -> Boolean): List<MemoryRecallTrigger> {
        val triggers = LinkedHashMap<String, MemoryRecallTrigger>()
        HASHTAG.findAll(text).forEach { match ->
            val tag = normalizeTag(match.groupValues[1])
            if (tag.isNotEmpty()) triggers.getOrPut(tag) { MemoryRecallTrigger.Explicit(tag) }
        }
        REMEMBER_PHRASE.findAll(text).forEach { match ->
            val topic = match.groupValues[1].trim().trimEnd(',', ';', ':').lowercase()
            if (topic.length >= 2) triggers.getOrPut(topic) { MemoryRecallTrigger.Phrase(topic) }
        }

        val words = WORD.findAll(text).map { it.value.lowercase() }.toList()
        val lowered = words.joinToString(" ")
        // Echo: an offered tag's words, used soon after the tag was offered.
        offered.entries.toList().forEach { (tag, at) ->
            if (position - at > policy.echoWindowTokens) return@forEach
            val reach = (policy.echoWindowTokens - (position - at)).toInt().coerceAtLeast(0)
            val window = words.take(reach)
            val single = ' ' !in tag
            val hit = if (single) window.any { stem(it) == stem(tag) } else window.joinToString(" ").contains(tag)
            if (hit && tag !in triggers && (!single || !isCommon(tag))) triggers[tag] = MemoryRecallTrigger.Echo(tag)
        }
        // Doubling: a content word used twice within the window (this chunk or the recent ones).
        words.forEachIndexed { i, word ->
            val at = position + i
            if (word.length < MIN_DOUBLED_LENGTH || word in STOPWORDS) return@forEachIndexed
            val key = stem(word)
            val earlier = recent.lastOrNull { (w, p) -> w == key && at - p <= policy.doublingWindowTokens }
            recent.addLast(key to at)
            if (earlier == null || key in triggers) return@forEachIndexed
            val fired = lastDoubled[key]
            if (fired != null && at - fired < policy.doublingWindowTokens * REFIRE_WINDOWS) return@forEachIndexed
            if (isCommon(key)) return@forEachIndexed
            lastDoubled[key] = at
            triggers[key] = MemoryRecallTrigger.Doubled(key)
        }
        while (recent.isNotEmpty() && position + words.size - recent.first().second > policy.doublingWindowTokens) recent.removeFirst()
        position += words.size.coerceAtLeast(1)
        if (lowered.isEmpty()) return emptyList()
        return triggers.values.sortedBy { if (it.deliberate) 0 else 1 }
    }

    companion object {
        private const val MAX_OFFERED = 64
        private const val MIN_DOUBLED_LENGTH = 4
        private const val REFIRE_WINDOWS = 4

        private val HASHTAG = Regex("(?<![\\w#&/])#([A-Za-z][A-Za-z0-9_-]{1,48})")
        private val REMEMBER_PHRASE = Regex(
            "\\b(?:let me see what i remember about|let me recall|what do i remember about|what i remember about)\\s+([^.?!\\n]{2,80})",
            RegexOption.IGNORE_CASE,
        )
        private val WORD = Regex("[A-Za-z][A-Za-z0-9_-]*")
        private val STOPWORDS = setOf(
            "that", "this", "with", "from", "have", "will", "would", "should", "could", "there", "their", "they", "them",
            "then", "than", "what", "when", "where", "which", "while", "about", "into", "also", "just", "only", "very",
            "some", "more", "most", "other", "such", "need", "needs", "make", "made", "like", "want", "know", "think",
            "look", "here", "were", "been", "being", "does", "done", "each", "your", "ours", "mine", "it's", "let's",
        )

        /** "#gradle-cache" and "Gradle Cache" are one tag: lowercase, words separated by spaces. */
        fun normalizeTag(tag: String): String = tag.trim().removePrefix("#").lowercase().replace(Regex("[-_\\s]+"), " ").trim()

        /** A tag as an agent writes it: "#gradle-cache". */
        fun hashtag(tag: String): String = "#" + normalizeTag(tag).replace(' ', '-')

        private fun stem(word: String): String = if (word.length > 4 && word.endsWith("s") && !word.endsWith("ss")) word.dropLast(1) else word
    }
}
