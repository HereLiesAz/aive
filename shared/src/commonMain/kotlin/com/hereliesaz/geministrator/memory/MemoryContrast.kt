package com.hereliesaz.geministrator.memory

/**
 * Deterministic, non-judging contrast detection.
 *
 * A memory's **frame** is what it is about: its subject and the question or predicate it answers
 * ("I chose _ for the database"). Its **filler** is what it says in that slot ("Postgres"). Two
 * memories with the same frame and different fillers are a **contrast**: they are not similar
 * (condensing them would pick a filler), and neither is ranked as right. Detecting one is a text
 * diff, nothing more: align the two token sequences (longest common subsequence), and read a
 * contrast when the shared part carries content and an aligned slot holds different content words on
 * both sides, at least one of which looks like a filler (an entity, value, identifier or choice).
 *
 * The idea is borrowed from frame semantics (Fillmore; FrameNet's frames and frame elements), reduced
 * to surface alignment: no parser, no frame lexicon, no semantic roles beyond "the shared part" and
 * "the slot". Spelling variants of one name are folded first through [MemoryAliases] (an entity alias
 * table), so "Postgres" and "PostgreSQL" are the same filler.
 */
internal object MemoryContrast {
    /** A filler slot: the aligned gap where the two texts say different things. */
    data class Slot(val left: List<String>, val right: List<String>)

    data class Contrast(
        /** Normalized frame: the shared tokens with `_` where a slot differs. Stable across pairs. */
        val frameKey: String,
        /** The frame as readable text (original casing of the left text, `_` for slots). */
        val frameText: String,
        /** Leading shared tokens before the first slot (approximate subject). Empty if none. */
        val subject: String,
        val slots: List<Slot>,
        /** Left filler (normalized slot words joined), one per slot, joined by " | ". */
        val leftFiller: String,
        val rightFiller: String,
        /** Fillers as written in each text. */
        val leftFillerText: String,
        val rightFillerText: String,
    )

    /** The contrast between [left] and [right], or null when they share no frame or differ in no filler. */
    fun between(left: String, right: String): Contrast? {
        val a = tokens(left)
        val b = tokens(right)
        if (a.isEmpty() || b.isEmpty()) return null
        val lcs = align(a.map { it.norm }, b.map { it.norm })
        val sharedContent = lcs.count { (i, _) -> a[i].content }
        val contentA = a.count { it.content }
        val contentB = b.count { it.content }
        if (sharedContent == 0 || sharedContent * 2 < minOf(contentA, contentB)) return null

        // Gaps between consecutive aligned pairs (and before the first / after the last) are slots.
        val slots = mutableListOf<Pair<List<Token>, List<Token>>>()
        val frame = mutableListOf<String>()
        val frameText = mutableListOf<String>()
        var pi = 0
        var pj = 0
        fun gap(toI: Int, toJ: Int) {
            val ga = a.subList(pi, toI)
            val gb = b.subList(pj, toJ)
            if (ga.isNotEmpty() || gb.isNotEmpty()) {
                slots += ga to gb
                frame += "_"
                frameText += "_"
            }
        }
        lcs.forEach { (i, j) ->
            gap(i, j)
            frame += a[i].norm
            frameText += a[i].raw
            pi = i + 1
            pj = j + 1
        }
        gap(a.size, b.size)

        // A slot is a filler contrast when both sides say something with content and the words differ
        // and at least one side's words look like a filler. A polarity slot (negation on one side only)
        // is also a contrast: same frame, opposite filler.
        val contrasting = slots.filter { (ga, gb) ->
            val ca = ga.filter { it.content }
            val cb = gb.filter { it.content }
            val substitution = ca.isNotEmpty() && cb.isNotEmpty() &&
                ca.map { it.norm }.toSet() != cb.map { it.norm }.toSet() &&
                (ca.any { it.filler } || cb.any { it.filler })
            val polarity = ga.any { it.negation } != gb.any { it.negation }
            substitution || polarity
        }
        if (contrasting.isEmpty()) return null

        val firstSlot = frame.indexOf("_")
        val subject = frameText.take(if (firstSlot < 0) frameText.size else firstSlot).joinToString(" ")
        fun filler(side: List<List<Token>>, raw: Boolean) =
            side.joinToString(" | ") { slot -> slot.joinToString(" ") { if (raw) it.raw else it.norm } }
        return Contrast(
            frameKey = frame.joinToString(" "),
            frameText = frameText.joinToString(" "),
            subject = subject,
            slots = contrasting.map { (ga, gb) -> Slot(ga.map { it.norm }, gb.map { it.norm }) },
            leftFiller = filler(contrasting.map { it.first }, raw = false),
            rightFiller = filler(contrasting.map { it.second }, raw = false),
            leftFillerText = filler(contrasting.map { it.first }, raw = true),
            rightFillerText = filler(contrasting.map { it.second }, raw = true),
        )
    }

    fun contrasts(left: String, right: String): Boolean = between(left, right) != null

    /** True when any two of [texts] contrast. */
    fun anyContrast(texts: List<String>): Boolean {
        for (i in texts.indices) for (j in i + 1 until texts.size) if (contrasts(texts[i], texts[j])) return true
        return false
    }

    internal data class Token(
        val raw: String,
        val norm: String,
        val content: Boolean,
        val filler: Boolean,
        val negation: Boolean,
    )

    internal fun tokens(text: String): List<Token> {
        val result = mutableListOf<Token>()
        var sentenceStart = true
        TOKEN.findAll(text).forEach { match ->
            if (match.value.length == 1 && match.value[0] in ".!?;") {
                sentenceStart = true
                return@forEach
            }
            val raw = match.value.trim('\'', '"', '`')
            if (raw.isBlank()) return@forEach
            val lower = raw.lowercase()
            val norm = MemoryAliases.canonical(lower)
            val negation = lower in NEGATIONS
            val function = lower in FUNCTION_WORDS || negation
            val quoted = match.value.length > raw.length && match.value.first() in "'\"`"
            val identifier = raw.any(Char::isDigit) || raw.contains('_') || raw.contains('.') || raw.contains('/') ||
                (raw.drop(1).any(Char::isUpperCase) && raw.any(Char::isLowerCase))
            // A capitalized word is a name unless it only starts a sentence; "We", "The" are function words anyway.
            val name = raw.first().isUpperCase() && (!sentenceStart || raw.length > 1 && raw.drop(1).any(Char::isUpperCase) || lower !in COMMON_SENTENCE_STARTERS)
            val filler = !function && (quoted || identifier || name || lower in VALUE_WORDS || norm != lower)
            result += Token(raw = raw, norm = norm, content = !function, filler = filler, negation = negation)
            sentenceStart = false
        }
        return result
    }

    /** Longest common subsequence of [a] and [b] as index pairs, in order (classic dynamic programming). */
    private fun align(a: List<String>, b: List<String>): List<Pair<Int, Int>> {
        val n = a.size
        val m = b.size
        val table = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            table[i][j] = if (a[i] == b[j]) table[i + 1][j + 1] + 1 else maxOf(table[i + 1][j], table[i][j + 1])
        }
        val pairs = mutableListOf<Pair<Int, Int>>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> { pairs += i to j; i++; j++ }
                table[i + 1][j] >= table[i][j + 1] -> i++
                else -> j++
            }
        }
        return pairs
    }

    private val TOKEN = Regex("\"[^\"]{1,80}\"|`[^`]{1,80}`|[A-Za-z0-9_][A-Za-z0-9_./+#'-]*[A-Za-z0-9_+#]|[A-Za-z0-9]|[.!?;]")

    private val NEGATIONS = setOf("not", "never", "no", "none", "cannot", "can't", "don't", "doesn't", "isn't", "won't", "without", "nor")

    /** Words that carry structure, not content: they can be shared but never make a slot a contrast. */
    private val FUNCTION_WORDS = setOf(
        "a", "an", "the", "of", "to", "in", "on", "at", "for", "with", "and", "or", "is", "are", "was", "were",
        "be", "been", "being", "by", "as", "it", "its", "that", "this", "these", "those", "from", "into", "than",
        "then", "so", "do", "does", "did", "has", "have", "had", "will", "would", "can", "could", "should", "may",
        "might", "must", "we", "you", "i", "they", "he", "she", "them", "our", "your", "their", "my", "me", "us",
        "if", "but", "also", "just", "only", "very", "any", "all", "each", "some", "more", "most", "other", "such",
        "about", "over", "under", "after", "before", "when", "where", "which", "who", "what", "why", "how", "there",
        "here", "now", "still", "yet", "via", "per", "out", "up", "down", "off", "instead", "again", "too",
    )

    /** Sentence-initial capitals that are ordinary words, not names. Other sentence-initial capitals count as names. */
    private val COMMON_SENTENCE_STARTERS = setOf(
        "use", "used", "using", "chose", "choose", "set", "fixed", "fix", "added", "add", "removed", "remove",
        "switched", "moved", "changed", "decided", "build", "built", "run", "ran", "test", "tests", "keep", "kept",
        "please", "next", "then", "first", "finally", "yes", "ok", "okay", "memory", "after", "before",
    )

    /** Lowercase words that are values or choices rather than topic words. */
    private val VALUE_WORDS = setOf(
        "true", "false", "on", "off", "enabled", "disabled", "yes", "no", "always", "never", "required", "optional",
        "allowed", "forbidden", "public", "private", "sync", "async", "tabs", "spaces", "debug", "release",
        "dark", "light", "left", "right", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
        "january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december",
    )
}

/**
 * Entity alias table: spellings of one name fold to one canonical key before contrast detection, so
 * "Postgres" vs "PostgreSQL" is the same filler, not a contrast. Deliberately small and explicit;
 * extend it with names this codebase actually meets. Folding is case-insensitive and exact (no fuzzy
 * matching), so it can never merge two different names.
 */
internal object MemoryAliases {
    private val groups: List<Set<String>> = listOf(
        setOf("postgresql", "postgres", "psql", "pgsql"),
        setOf("mysql", "my-sql"),
        setOf("sqlite", "sqlite3"),
        setOf("mongodb", "mongo"),
        setOf("javascript", "js", "ecmascript"),
        setOf("typescript", "ts"),
        setOf("python", "py", "python3"),
        setOf("kubernetes", "k8s"),
        setOf("github", "gh"),
        setOf("macos", "osx", "os-x"),
        setOf("windows", "win32"),
        setOf("webassembly", "wasm"),
        setOf("nodejs", "node.js"),
        setOf("dotnet", ".net"),
        setOf("redis", "redis-server"),
        setOf("ok", "okay"),
    )

    private val canonicalOf: Map<String, String> = buildMap {
        groups.forEach { group ->
            val canonical = group.first()
            group.forEach { put(it, canonical) }
        }
    }

    fun canonical(lowercaseWord: String): String = canonicalOf[lowercaseWord] ?: lowercaseWord
}
