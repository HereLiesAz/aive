package com.hereliesaz.geministrator.memory

import kotlin.math.ln
import kotlin.math.roundToInt

/*
 * Salience features for the programmatic Salience clerk. Every part of the score is recorded on the
 * node (`salienceFeatures`), so a score can be audited term by term. None of it judges whether a
 * section is true; it measures kind, cue words, relevance to the user's own prompt, specificity,
 * repetition and recency (position within the episode).
 */
internal object MemorySalienceFeatures {
    data class Score(val score: Float, val parts: List<Pair<String, Float>>) {
        fun explain(): String = parts.joinToString(" ") { (name, value) -> "$name=${value.format()}" }
    }

    /** [position] is the section's place in its episode, 0 (first) to 1 (last): later sections carry outcomes. */
    fun score(text: String, sourceKind: String?, promptTerms: Set<String>, idf: Map<String, Double>, duplicates: Int, position: Float = 0f): Score {
        val parts = mutableListOf<Pair<String, Float>>()
        parts += "kind" to when (sourceKind) {
            MemorySourceKind.AgentNote.name -> 0.8f
            MemorySourceKind.UserPrompt.name, MemorySourceKind.Objective.name -> 0.75f
            MemorySourceKind.Failure.name -> 0.7f
            MemorySourceKind.Plan.name -> 0.6f
            else -> 0.5f
        }
        if (text.looksStronglyTechnical()) parts += "technical" to 0.1f
        if (DECISION.containsMatchIn(text)) parts += "decision" to 0.15f
        if (ERROR.containsMatchIn(text)) parts += "error" to 0.1f
        if (ACTION_ITEM.containsMatchIn(text)) parts += "actionItem" to 0.08f
        relevance(text, promptTerms, idf).takeIf { it > 0f }?.let { parts += "promptRelevance" to 0.15f * it }
        specificity(text).takeIf { it > 0f }?.let { parts += "specificity" to 0.05f * it }
        if (duplicates > 0) parts += "repeated" to 0.05f * minOf(1f, duplicates / 2f)
        if (position > 0f) parts += "recency" to 0.05f * position.coerceIn(0f, 1f)
        val total = parts.sumOf { it.second.toDouble() }.toFloat().coerceIn(0f, 1f)
        return Score((total * 100).roundToInt() / 100f, parts)
    }

    /** IDF-weighted share of the user's prompt terms that [text] contains, 0..1. */
    private fun relevance(text: String, promptTerms: Set<String>, idf: Map<String, Double>): Float {
        if (promptTerms.isEmpty()) return 0f
        val terms = terms(text).toSet()
        val total = promptTerms.sumOf { idf[it] ?: 1.0 }
        if (total == 0.0) return 0f
        return (promptTerms.filter { it in terms }.sumOf { idf[it] ?: 1.0 } / total).toFloat()
    }

    /** Identifiers, paths, URLs and numbers per 100 characters, saturating at 3. */
    private fun specificity(text: String): Float {
        if (text.isEmpty()) return 0f
        val count = SPECIFIC.findAll(text).count()
        return minOf(1f, count * 100f / text.length / 3f)
    }

    fun terms(text: String): List<String> =
        TERM.findAll(text.lowercase()).map { it.value }.filter { it.length > 2 && it !in STOPWORDS }.toList()

    /** Smoothed IDF over the packet's sections (BM25's form, floored at 0). */
    fun idf(texts: List<String>): Map<String, Double> {
        val df = HashMap<String, Int>()
        texts.forEach { text -> terms(text).toSet().forEach { df[it] = (df[it] ?: 0) + 1 } }
        val n = texts.size.toDouble()
        return df.mapValues { (_, count) -> maxOf(0.0, ln((n - count + 0.5) / (count + 0.5) + 1.0)) }
    }

    /** Word 3-shingles for prose; character 5-grams for code and logs, where words repeat. */
    fun shingles(text: String): Set<String> {
        val normalized = text.lowercase().replace(WHITESPACE, " ").trim()
        return if (text.looksStronglyTechnical() || normalized.count { it == ' ' } < 6) {
            (0..normalized.length - 5).mapTo(HashSet()) { normalized.substring(it, it + 5) }
        } else {
            val words = normalized.split(' ')
            (0..words.size - 3).mapTo(HashSet()) { words.subList(it, it + 3).joinToString(" ") }
        }
    }

    fun jaccard(left: Set<String>, right: Set<String>): Double {
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val (small, large) = if (left.size <= right.size) left to right else right to left
        val shared = small.count { it in large }
        return shared.toDouble() / (left.size + right.size - shared)
    }

    /**
     * Drain-style collapse of repeated tool output: lines whose template (numbers, hex, paths,
     * timestamps and quoted values masked) recurs keep their first and last instance; the rest are
     * dropped and counted. Kept lines are copied verbatim.
     */
    fun collapseRepeatedLines(text: String): Pair<String, Int> {
        val lines = text.lines()
        if (lines.size < 4) return text to 0
        val templates = lines.map(::lineTemplate)
        val counts = templates.groupingBy { it }.eachCount()
        val lastIndex = HashMap<String, Int>()
        templates.forEachIndexed { i, t -> lastIndex[t] = i }
        val seen = HashSet<String>()
        var dropped = 0
        val kept = lines.filterIndexed { i, line ->
            val t = templates[i]
            val repeats = (counts[t] ?: 0) >= REPEAT_THRESHOLD && t.isNotBlank()
            val keep = !repeats || seen.add(t) || lastIndex[t] == i
            if (!keep) dropped++
            keep || line.isBlank()
        }
        return if (dropped == 0) text to 0 else kept.joinToString("\n") to dropped
    }

    /** A line with its timestamps, quoted values, paths, hex and numbers masked. */
    fun lineTemplate(line: String): String = line.trim()
        .replace(TIMESTAMP, "<t>").replace(QUOTED, "<q>").replace(PATHISH, "<p>").replace(HEX, "<h>").replace(NUMBER, "<n>")

    /** Whole sections of build-tool bookkeeping: up-to-date tasks, download progress, bars. */
    fun isToolNoise(text: String): Boolean {
        val lines = text.lines().filter(String::isNotBlank)
        return lines.isNotEmpty() && lines.all { TOOL_NOISE.containsMatchIn(it) }
    }

    private fun Float.format(): String = ((this * 100).roundToInt() / 100f).toString()

    private const val REPEAT_THRESHOLD = 3
    private val WHITESPACE = Regex("\\s+")
    private val TERM = Regex("[a-z][a-z0-9_]+")
    private val SPECIFIC = Regex("https?://\\S+|(?:[\\w.-]+/)+[\\w.-]+|\\b[a-z]+[A-Z]\\w*\\b|\\b\\w+_\\w+\\b|`[^`]+`|\\b\\d+(?:\\.\\d+)*\\b")
    private val TIMESTAMP = Regex("\\d{4}-\\d\\d-\\d\\d[T ]\\d\\d:\\d\\d(?::\\d\\d(?:[.,]\\d+)?)?(?:Z|[+-]\\d\\d:?\\d\\d)?|\\b\\d\\d:\\d\\d:\\d\\d(?:[.,]\\d+)?")
    private val QUOTED = Regex("\"[^\"]*\"|'[^']*'")
    private val PATHISH = Regex("(?:[\\w.@-]+/)+[\\w.@-]+")
    private val HEX = Regex("\\b(?:0x)?[0-9a-fA-F]{6,}\\b|\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b")
    private val NUMBER = Regex("\\d+(?:\\.\\d+)?")
    private val TOOL_NOISE = Regex(
        "^\\s*> Task :\\S+ (?:UP-TO-DATE|NO-SOURCE|SKIPPED|FROM-CACHE)\\s*$|^\\s*(?:Downloading|Download|Downloaded|Resolving|Fetching|Progress)\\b.*\\d+\\s*%|" +
            "^\\s*\\[[=#>\\-. ]{5,}\\]|^\\s*\\d+%\\s*[|\\[]",
        RegexOption.IGNORE_CASE,
    )
    private val DECISION = Regex(
        "\\b(decid\\w*|chose|choose|prefer\\w*|must|never|always|should|instead|because|agreed|requirement|will use|going with)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val ERROR = Regex("\\b(error|exception|fail\\w*|crash\\w*|bug|FAIL)\\b|error:", RegexOption.IGNORE_CASE)
    private val ACTION_ITEM = Regex("\\b(TODO|FIXME|next step|follow[- ]up|need(?:s)? to|remaining|blocked on)\\b", RegexOption.IGNORE_CASE)
    private val STOPWORDS = setOf(
        "the", "and", "for", "with", "that", "this", "from", "was", "were", "are", "but", "not", "you", "have", "has", "had",
        "will", "would", "can", "could", "should", "its", "into", "than", "then", "there", "their", "they", "them", "which",
        "what", "when", "where", "who", "how", "all", "any", "also", "just", "only", "very", "about", "after", "before",
        "because", "been", "being", "our", "your", "please", "make", "let", "use",
    )
}
