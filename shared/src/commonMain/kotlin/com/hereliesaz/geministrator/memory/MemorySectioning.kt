package com.hereliesaz.geministrator.memory

import kotlin.math.sqrt

/*
 * Structure-first sectioning for the programmatic Sectioner.
 *
 *  1. Typed blocks, CommonMark-style: headings, fenced code (an unclosed fence closes at the end),
 *     stack traces, unified-diff hunks, log runs (3+ timestamped or levelled lines), lists, tables,
 *     block quotes, paragraphs. A heading joins the block after it.
 *  2. Typed blocks stay whole. Oversized logs, traces and diffs split only between lines (diffs
 *     between hunks); fences never split.
 *  3. Prose over the limit: TextTiling (Hearst 1997) proposes topic boundaries when the prose is long
 *     enough to measure, then a recursive split (paragraphs, lines, sentences, clauses, words)
 *     guarantees the limit, and neighbours merge back up to the minimum.
 *  4. Sentences split pySBD-style: never inside URLs, paths, versions, decimals, `foo.bar()` or
 *     after common abbreviations.
 */

internal enum class MemoryBlockType { Heading, Fence, StackTrace, Diff, Log, ListItems, Table, Quote, Paragraph }

internal data class MemoryBlock(val type: MemoryBlockType, val text: String)

/** Paragraph-level typed blocks; headings are attached to the block that follows them. */
internal fun String.typedBlocks(): List<MemoryBlock> {
    val lines = replace("\r\n", "\n").replace('\r', '\n').lines()
    val raw = mutableListOf<MemoryBlock>()
    var i = 0
    fun take(type: MemoryBlockType, from: Int, until: Int) {
        val text = lines.subList(from, until).joinToString("\n").trimEnd()
        if (text.isNotBlank()) raw += MemoryBlock(type, text.trimStart('\n'))
    }
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trim()
        when {
            trimmed.isEmpty() -> i++
            FENCE_OPEN.matches(trimmed) -> {
                val marker = trimmed.takeWhile { it == '`' || it == '~' }
                var j = i + 1
                while (j < lines.size && !lines[j].trim().startsWith(marker)) j++
                val end = if (j < lines.size) j + 1 else lines.size
                take(MemoryBlockType.Fence, i, end)
                i = end
            }
            HEADING.containsMatchIn(line) -> {
                take(MemoryBlockType.Heading, i, i + 1)
                i++
            }
            TRACE_HEADER.containsMatchIn(line) && i + 1 < lines.size && TRACE_LINE.containsMatchIn(lines[i + 1]) ||
                TRACE_LINE.containsMatchIn(line) -> {
                var j = i + 1
                while (j < lines.size && (TRACE_LINE.containsMatchIn(lines[j]) || TRACE_HEADER.containsMatchIn(lines[j]))) j++
                take(MemoryBlockType.StackTrace, i, j)
                i = j
            }
            DIFF_START.containsMatchIn(line) -> {
                var j = i + 1
                while (j < lines.size && (DIFF_LINE.containsMatchIn(lines[j]) || DIFF_START.containsMatchIn(lines[j]))) j++
                take(MemoryBlockType.Diff, i, j)
                i = j
            }
            LOG_LINE.containsMatchIn(line) && run(lines, i) { LOG_LINE.containsMatchIn(it) } >= 3 -> {
                val j = i + run(lines, i) { LOG_LINE.containsMatchIn(it) || it.startsWith(" ") && it.isNotBlank() }
                take(MemoryBlockType.Log, i, j)
                i = j
            }
            TABLE_LINE.containsMatchIn(line) -> {
                val j = i + run(lines, i) { TABLE_LINE.containsMatchIn(it) }
                take(MemoryBlockType.Table, i, j)
                i = j
            }
            QUOTE_LINE.containsMatchIn(line) -> {
                val j = i + run(lines, i) { QUOTE_LINE.containsMatchIn(it) }
                take(MemoryBlockType.Quote, i, j)
                i = j
            }
            LIST_LINE.containsMatchIn(line) -> {
                // Items plus their indented continuation lines.
                val j = i + run(lines, i) { LIST_LINE.containsMatchIn(it) || it.startsWith("  ") && it.isNotBlank() }
                take(MemoryBlockType.ListItems, i, j)
                i = j
            }
            else -> {
                var j = i + 1
                while (j < lines.size && lines[j].isNotBlank() && !startsBlock(lines[j])) j++
                take(MemoryBlockType.Paragraph, i, j)
                i = j
            }
        }
    }
    // Headings join the block after them (consecutive headings stack).
    val out = mutableListOf<MemoryBlock>()
    var pending: String? = null
    raw.forEach { block ->
        if (block.type == MemoryBlockType.Heading) {
            pending = pending?.let { "$it\n${block.text}" } ?: block.text
        } else {
            out += pending?.let { block.copy(text = "$it\n${block.text}") } ?: block
            pending = null
        }
    }
    pending?.let { out += MemoryBlock(MemoryBlockType.Heading, it) }
    return out
}

private fun run(lines: List<String>, from: Int, matches: (String) -> Boolean): Int {
    var n = 0
    while (from + n < lines.size && matches(lines[from + n])) n++
    return n
}

private fun startsBlock(line: String): Boolean {
    val trimmed = line.trim()
    return FENCE_OPEN.matches(trimmed) || HEADING.containsMatchIn(line) || DIFF_START.containsMatchIn(line) ||
        TABLE_LINE.containsMatchIn(line) || QUOTE_LINE.containsMatchIn(line) || LIST_LINE.containsMatchIn(line) ||
        TRACE_LINE.containsMatchIn(line)
}

/** Blocks of at most [maxChars] (fences excepted), in source order. */
internal fun String.sectionBlocks(maxChars: Int = 1_200, minChars: Int = 160): List<MemoryBlock> =
    typedBlocks().flatMap { block ->
        when {
            block.text.length <= maxChars || block.type == MemoryBlockType.Fence -> listOf(block)
            block.type == MemoryBlockType.Diff -> splitLines(block.text, maxChars) { DIFF_HUNK.containsMatchIn(it) }.map { block.copy(text = it) }
            block.type in LINE_BLOCKS -> splitLines(block.text, maxChars) { true }.map { block.copy(text = it) }
            else -> splitProse(block.text, maxChars, minChars).map { block.copy(text = it) }
        }
    }

/** The block texts; kept for callers that only need strings. */
internal fun String.structuralBlocks(maxChars: Int = 1_200): List<String> = sectionBlocks(maxChars).map(MemoryBlock::text)

private val LINE_BLOCKS = setOf(MemoryBlockType.Log, MemoryBlockType.StackTrace, MemoryBlockType.Table, MemoryBlockType.ListItems, MemoryBlockType.Quote)

/** Splits between lines only, preferring lines where [boundary] holds, packing up to [maxChars]. */
private fun splitLines(text: String, maxChars: Int, boundary: (String) -> Boolean): List<String> {
    val out = mutableListOf<String>()
    val current = StringBuilder()
    text.lines().forEach { line ->
        if (current.isNotEmpty() && current.length + line.length + 1 > maxChars && (boundary(line) || current.length > maxChars * 2)) {
            out += current.toString()
            current.clear()
        }
        if (current.isNotEmpty()) current.append('\n')
        current.append(line)
    }
    if (current.isNotEmpty()) out += current.toString()
    return out
}

/** Topic boundaries first (when measurable), then a recursive split that guarantees [maxChars]. */
internal fun splitProse(text: String, maxChars: Int, minChars: Int): List<String> {
    val sentences = memorySentences(text)
    val topics = if (text.length >= TEXTTILING_MIN_CHARS) textTiling(sentences) else listOf(sentences)
    val pieces = topics.flatMap { group -> recursiveSplit(group.joinToString(" "), maxChars) }
    return mergeSmall(pieces, maxChars, minChars)
}

private val SEPARATORS = listOf(Regex("\\n\\s*\\n"), Regex("\\n"), null /* sentences */, Regex("(?<=;)\\s+"), Regex("(?<=,)\\s+"), Regex("\\s+"))

private fun recursiveSplit(text: String, maxChars: Int, level: Int = 0): List<String> {
    if (text.length <= maxChars) return listOf(text.trim()).filter(String::isNotEmpty)
    if (level >= SEPARATORS.size) return text.chunked(maxChars)
    val separator = SEPARATORS[level]
    val parts = (if (separator == null) memorySentences(text) else text.split(separator)).map(String::trim).filter(String::isNotEmpty)
    if (parts.size <= 1) return recursiveSplit(text, maxChars, level + 1)
    val joiner = if (level == 0) "\n\n" else if (level == 1) "\n" else " "
    val out = mutableListOf<String>()
    val current = StringBuilder()
    parts.forEach { part ->
        if (part.length > maxChars) {
            if (current.isNotEmpty()) out += current.toString().also { current.clear() }
            out += recursiveSplit(part, maxChars, level + 1)
            return@forEach
        }
        if (current.isNotEmpty() && current.length + joiner.length + part.length > maxChars) out += current.toString().also { current.clear() }
        if (current.isNotEmpty()) current.append(joiner)
        current.append(part)
    }
    if (current.isNotEmpty()) out += current.toString()
    return out
}

private fun mergeSmall(pieces: List<String>, maxChars: Int, minChars: Int): List<String> {
    val out = mutableListOf<String>()
    pieces.forEach { piece ->
        val last = out.lastOrNull()
        if (last != null && (last.length < minChars || piece.length < minChars) && last.length + piece.length + 1 <= maxChars) {
            out[out.lastIndex] = "$last $piece"
        } else {
            out += piece
        }
    }
    return out
}

/**
 * TextTiling over sentences: lexical cohesion between adjacent windows of [WINDOW_WORDS]-word
 * pseudo-sentences, cut where the depth score exceeds mean - sd/2, snapped to sentence ends.
 */
private fun textTiling(sentences: List<String>): List<List<String>> {
    if (sentences.size < 6) return listOf(sentences)
    // Pseudo-sentence index at the end of each real sentence.
    val words = mutableListOf<String>()
    val sentenceEnds = mutableListOf<Int>()
    sentences.forEach { sentence ->
        TILING_WORD.findAll(sentence.lowercase()).map { it.value }.filter { it !in TILING_STOPWORDS && it.length > 2 }
            .forEach { words += it.removeSuffix("s") }
        sentenceEnds += words.size
    }
    val gaps = words.size / WINDOW_WORDS
    if (gaps < 4) return listOf(sentences)
    val pseudo = (0 until gaps).map { g -> words.subList(g * WINDOW_WORDS, minOf(words.size, (g + 1) * WINDOW_WORDS)) }
    val block = minOf(6, maxOf(2, gaps / 3))
    val scores = (1 until pseudo.size).map { gap ->
        val left = pseudo.subList(maxOf(0, gap - block), gap).flatten().groupingBy { it }.eachCount()
        val right = pseudo.subList(gap, minOf(pseudo.size, gap + block)).flatten().groupingBy { it }.eachCount()
        val dot = left.entries.sumOf { (word, count) -> count.toDouble() * (right[word] ?: 0) }
        val norm = sqrt(left.values.sumOf { it.toDouble() * it }) * sqrt(right.values.sumOf { it.toDouble() * it })
        if (norm == 0.0) 0.0 else dot / norm
    }
    val depths = scores.indices.map { k ->
        var leftPeak = scores[k]
        var l = k
        while (l > 0 && scores[l - 1] >= leftPeak) leftPeak = scores[--l]
        var rightPeak = scores[k]
        var r = k
        while (r < scores.lastIndex && scores[r + 1] >= rightPeak) rightPeak = scores[++r]
        (leftPeak - scores[k]) + (rightPeak - scores[k])
    }
    val mean = depths.average()
    val sd = sqrt(depths.sumOf { (it - mean) * (it - mean) } / depths.size)
    val cutWords = depths.indices.filter { depths[it] > mean - sd / 2 && depths[it] > 0 }.map { (it + 1) * WINDOW_WORDS }
    if (cutWords.isEmpty()) return listOf(sentences)
    // Snap each cut to the nearest sentence end.
    val cuts = cutWords.map { cut -> sentenceEnds.indices.minBy { kotlin.math.abs(sentenceEnds[it] - cut) } + 1 }
        .filter { it in 1 until sentences.size }.distinct().sorted()
    val out = mutableListOf<List<String>>()
    var from = 0
    cuts.forEach { cut ->
        if (cut > from) out += sentences.subList(from, cut)
        from = cut
    }
    if (from < sentences.size) out += sentences.subList(from, sentences.size)
    return out
}

/**
 * Sentence split that never breaks inside URLs, paths, versions, decimals, dotted identifiers or
 * after common abbreviations; breaks at `.`/`!`/`?` runs followed by whitespace, and at line ends
 * that close a sentence.
 */
internal fun memorySentences(text: String): List<String> {
    val protected = BooleanArray(text.length)
    PROTECTED_SPANS.findAll(text).forEach { match -> for (k in match.range) protected[k] = true }
    val out = mutableListOf<String>()
    var start = 0
    var k = 0
    while (k < text.length) {
        val c = text[k]
        if ((c == '.' || c == '!' || c == '?') && !protected[k]) {
            var end = k + 1
            while (end < text.length && (text[end] == '.' || text[end] == '!' || text[end] == '?' || text[end] == '"' || text[end] == '\'' || text[end] == ')')) end++
            val followedBySpace = end >= text.length || text[end].isWhitespace()
            val word = text.substring(start, k).takeLastWhile { it.isLetter() || it == '.' }.lowercase()
            if (followedBySpace && word.trimEnd('.') !in SENTENCE_ABBREVIATIONS && !(word.length == 1 && word[0].isLetter())) {
                text.substring(start, end).trim().takeIf(String::isNotEmpty)?.let(out::add)
                start = end
            }
            k = end
            continue
        }
        if (c == '\n' && k + 1 < text.length && text[k + 1] == '\n') {
            text.substring(start, k).trim().takeIf(String::isNotEmpty)?.let(out::add)
            start = k + 1
        }
        k++
    }
    text.substring(start).trim().takeIf(String::isNotEmpty)?.let(out::add)
    return out
}

private const val TEXTTILING_MIN_CHARS = 2_000
private const val WINDOW_WORDS = 20

private val FENCE_OPEN = Regex("^(```+|~~~+).*")
private val HEADING = Regex("^\\s{0,3}#{1,6}\\s+\\S")
private val TRACE_HEADER = Regex("^\\s*(?:Exception in thread .*|[\\w.$]*(?:Exception|Error)(?::.*)?|Traceback \\(most recent call last\\):)\\s*$")
private val TRACE_LINE = Regex("^\\s+at\\s+\\S+|^\\s*File \".*\", line \\d+|^\\s*Caused by:|^\\s*\\.\\.\\. \\d+ more")
private val DIFF_START = Regex("^diff --git |^@@ -\\d+(?:,\\d+)? \\+\\d+|^--- a/|^\\+\\+\\+ b/")
private val DIFF_HUNK = Regex("^@@ -\\d+")
private val DIFF_LINE = Regex("^[+\\- ]|^@@ |^index [0-9a-f]|^\\\\ No newline")
private val LOG_LINE = Regex("^\\s*\\[?\\d{4}-\\d\\d-\\d\\d[T ]\\d\\d:\\d\\d|^\\s*\\[?(?:TRACE|DEBUG|INFO|WARN|WARNING|ERROR|FATAL|E|W|I|D)\\]?[\\s/:]|^\\s*\\d\\d:\\d\\d:\\d\\d|^> Task :")
private val TABLE_LINE = Regex("^\\s*\\|.*\\|\\s*$")
private val QUOTE_LINE = Regex("^\\s*>")
private val LIST_LINE = Regex("^\\s*(?:[-*+]|\\d+[.)])\\s+\\S")
private val TILING_WORD = Regex("[a-z][a-z0-9_]+")
private val PROTECTED_SPANS = Regex(
    "https?://\\S*[\\w/=#&%-]|`[^`\\n]*`|(?:[\\w.@-]+/)+[\\w.@-]*[\\w@-]|\\bv?\\d+(?:\\.\\d+)+\\b|\\b\\d+\\.\\d+\\b|" +
        "[A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)+(?:\\(\\))?|\\b[\\w-]+\\.[a-z]{1,5}\\b(?=\\s|$|[,;:)])|\\b(?:e\\.g|i\\.e|etc|vs|cf)\\.",
)
private val SENTENCE_ABBREVIATIONS = setOf(
    "e.g", "i.e", "etc", "vs", "approx", "cf", "fig", "no", "mr", "mrs", "ms", "dr", "st", "jr", "sr", "inc", "ltd", "al", "eg", "ie", "esp", "incl", "min", "max", "sec", "ref",
)
private val TILING_STOPWORDS = setOf(
    "the", "and", "for", "with", "that", "this", "from", "was", "were", "are", "but", "not", "you", "have", "has", "had", "will",
    "would", "can", "could", "should", "its", "into", "than", "then", "there", "their", "they", "them", "which", "what", "when",
    "where", "who", "how", "all", "any", "also", "just", "only", "very", "about", "after", "before", "because", "been", "being",
)
