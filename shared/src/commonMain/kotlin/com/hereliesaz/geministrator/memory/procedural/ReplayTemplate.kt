package com.hereliesaz.geministrator.memory.procedural

import com.hereliesaz.geministrator.Sha256
import com.hereliesaz.geministrator.control.ControlRedaction
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * Text replay (docs/architecture/PROCEDURAL_MEMORY.md, section 11). A replayable habit is a text template
 * generalised, with no model, from several accepted runs of one task class. Everything here is pure.
 */

/** One text input to a task, by the label it arrives under (an upstream artifact). */
@Serializable
data class ReplayContextItem(val label: String, val text: String)

/** What a task is given: the three things a slot may be bound from. */
@Serializable
data class ReplayInputs(
    val objective: String,
    val criteria: List<String> = emptyList(),
    val context: List<ReplayContextItem> = emptyList(),
)

/** One earlier, accepted, text-only run of a task class: what it was given and what it produced. */
@Serializable
data class ReplaySourceRun(
    val workflowRunId: String,
    val taskRunId: String,
    val attempt: Int,
    val roleId: String,
    val inputs: ReplayInputs,
    val artifactKind: String,
    val output: String,
    val promptTokens: Long,
    val outputTokens: Long,
    val atEpochMillis: Long,
) {
    val shape: String get() = ReplayShape.parse(inputs.objective).shape
    val criteriaShape: String get() = ReplayShape.criteriaShape(inputs.criteria)

    /** Runs of one class share a role, an objective shape and a set of criteria shapes. */
    val classId: String get() = ReplayShape.classId(roleId, inputs)
}

/** Reads a task objective as a shape (its fixed words) and spans (the parts that vary). */
object ReplayShape {
    data class Parsed(val shape: String, val spans: List<String>)

    private val white = Regex("\\s+")

    /**
     * A word is a varying span when it has a digit, an underscore, a dot, a slash or an at sign, is
     * quoted, or is capitalised away from the start of a sentence. Neighbouring such words are one
     * span. The shape is the other words in lower case with `_` where a span was.
     */
    fun parse(objective: String): Parsed {
        val tokens = objective.trim().split(white).filter { it.isNotEmpty() }
        val shape = ArrayList<String>()
        val spans = ArrayList<String>()
        var run = ArrayList<String>()
        fun flush() {
            if (run.isNotEmpty()) {
                spans += run.joinToString(" ")
                shape += "_"
                run = ArrayList()
            }
        }
        var sentenceStart = true
        for (raw in tokens) {
            val core = raw.trim('"', '\'', '(', ')', '[', ']').trimEnd('.', ',', ';', ':', '!', '?')
            val variable = core.isNotEmpty() && (
                core.any { it.isDigit() } || core.contains('_') || core.contains('@') ||
                    core.contains('/') || core.contains('.') || raw.startsWith("\"") ||
                    (core[0].isUpperCase() && !sentenceStart)
                )
            if (variable) {
                run += core
            } else {
                flush()
                shape += raw.lowercase().trimEnd('.', ',', ';', ':', '!', '?')
            }
            sentenceStart = raw.endsWith(".") || raw.endsWith("!") || raw.endsWith("?")
        }
        flush()
        return Parsed(shape.joinToString(" "), spans)
    }

    /** The criteria with numbers masked, sorted: two tasks with the same checks have the same shape. */
    fun criteriaShape(criteria: List<String>): String =
        criteria.map { it.lowercase().replace(Regex("[0-9]+"), "#").trim() }.sorted().joinToString("|")

    fun classId(roleId: String, inputs: ReplayInputs): String =
        "replay-" + roleId + "-" +
            Sha256.hex((parse(inputs.objective).shape + "|" + criteriaShape(inputs.criteria)).encodeToByteArray()).take(12)
}

@Serializable
enum class ReplaySlotType {
    Number,
    Date,
    Identifier,
    Phrase,
    ;

    fun accepts(value: String): Boolean = when (this) {
        Number -> NUMBER.matches(value)
        Date -> DATE.matches(value)
        Identifier -> IDENTIFIER.matches(value)
        Phrase -> value.isNotBlank() && !value.contains('\n') && value.trim().split(Regex("\\s+")).size <= MAX_PHRASE_WORDS
    }

    companion object {
        const val MAX_PHRASE_WORDS = 12
        private val NUMBER = Regex("-?\\d+(?:[.,]\\d+)*%?")
        private val DATE = Regex("\\d{4}-\\d{2}-\\d{2}|\\d{1,2}/\\d{1,2}/\\d{2,4}")
        private val IDENTIFIER = Regex("[A-Za-z0-9_.:#@/-]+")

        /** The most specific type every value fits. */
        fun inferred(values: List<String>): ReplaySlotType =
            entries.first { type -> values.all { type.accepts(it) } }
    }
}

/** Where a slot's value comes from in a new task. A binder is kept only if it reproduces every source run. */
@Serializable
sealed interface ReplayBinder {
    @Serializable
    @SerialName("objective")
    data class ObjectiveSpan(val index: Int) : ReplayBinder

    @Serializable
    @SerialName("criterion")
    data class CriterionToken(val criterion: Int, val token: Int) : ReplayBinder

    @Serializable
    @SerialName("context")
    data class ContextField(val label: String, val key: String) : ReplayBinder

    @Serializable
    @SerialName("literal")
    data class Literal(val value: String) : ReplayBinder
}

internal fun criterionTokens(text: String): List<String> =
    text.split(Regex("\\s+")).map { it.trim('"', '\'', '(', ')').trimEnd('.', ',', ';', ':', '!', '?') }.filter { it.isNotEmpty() }

internal fun contextFieldKeys(text: String): List<String> =
    Regex("(?m)^\\s*([A-Za-z][A-Za-z0-9 _-]{0,30}?)\\s*:\\s*\\S").findAll(text).map { it.groupValues[1].trim() }.distinct().toList()

/** The value [this] binder names in [inputs], or null when the new task has none. */
fun ReplayBinder.resolve(inputs: ReplayInputs): String? = when (this) {
    is ReplayBinder.ObjectiveSpan -> ReplayShape.parse(inputs.objective).spans.getOrNull(index)
    is ReplayBinder.CriterionToken -> inputs.criteria.getOrNull(criterion)?.let(::criterionTokens)?.getOrNull(token)
    is ReplayBinder.ContextField -> inputs.context.firstOrNull { it.label == label }?.text?.let { text ->
        Regex("(?im)^\\s*" + Regex.escape(key) + "\\s*:\\s*(.+)$").find(text)?.groupValues?.get(1)?.trim()
    }
    is ReplayBinder.Literal -> value
}

@Serializable
data class ReplaySlot(val index: Int, val type: ReplaySlotType, val binder: ReplayBinder)

/**
 * The text skeleton of a task class. [parts] has one more element than [slots]: the text before the first
 * slot, between slots, and after the last.
 */
@Serializable
data class ReplayTemplate(
    val id: String,
    val roleId: String,
    val shape: String,
    val criteriaShape: String,
    val artifactKind: String,
    val parts: List<String>,
    val slots: List<ReplaySlot>,
    val sourceRuns: Int,
    val avgPromptTokens: Long,
    val avgOutputTokens: Long,
    val digest: String,
) {
    /** The estimate of what one replay saves: an average run's prompt plus its output. */
    val tokensAvoided: Long get() = avgPromptTokens + avgOutputTokens

    fun digestNow(): String = digestOf(id, roleId, shape, criteriaShape, artifactKind, parts, slots)

    /** The text for [inputs], or null when a slot has no value or one of the wrong type. */
    fun render(inputs: ReplayInputs): String? {
        val out = StringBuilder(parts.first())
        for (slot in slots) {
            val value = slot.binder.resolve(inputs)?.trim() ?: return null
            if (!slot.type.accepts(value)) return null
            out.append(value).append(parts[slot.index + 1])
        }
        return out.toString()
    }

    companion object {
        fun digestOf(
            id: String,
            roleId: String,
            shape: String,
            criteriaShape: String,
            artifactKind: String,
            parts: List<String>,
            slots: List<ReplaySlot>,
        ): String = Sha256.hex(
            listOf(id, roleId, shape, criteriaShape, artifactKind, parts.joinToString("\u0002"), slots.joinToString("\u0002") { "${it.index}:${it.type}:${it.binder}" })
                .joinToString("\u0001").encodeToByteArray(),
        )
    }
}

enum class ReplayRejectionReason {
    TooFewRuns,
    MixedRuns,
    NotTextOnly,
    TooLong,
    OptionalSpan,
    FreeFormSpan,
    SkeletonTooSmall,
    UnboundSlot,
    CodeOrCommand,
    UrlNotNamed,
    SecretLooking,
    SlashCommand,
}

sealed interface ReplayGeneralization {
    data class Made(val template: ReplayTemplate) : ReplayGeneralization
    data class Rejected(val reason: ReplayRejectionReason, val detail: String) : ReplayGeneralization
}

/** What a text must not contain to be replayed (shared by formation and by every replay). */
object ReplayGuards {
    private const val HEADS =
        "sudo|rm|curl|wget|git|npm|npx|pip3?|apt-get|apt|brew|chmod|chown|ssh|scp|docker|kubectl|gradle|bash|sh|zsh|powershell|python3?|node|make|mv|cp|dd|kill"
    private val url = Regex("(?i)\\b(?:https?|ftp)://[^\\s)>\\]]+|\\bwww\\.[^\\s)>\\]]+")
    private val longToken = Regex("[A-Za-z0-9+/_=-]{40,}")
    private val promptedCommand = Regex("(?m)^\\s*(?:[-*>]\\s+)?(?:[$]\\s+|`)(?:$HEADS)\\b")
    private val bareCommand = Regex("(?m)^\\s*(?:[-*>]\\s+)?(?:$HEADS)\\s+(?:-{1,2}[A-Za-z]\\S*|\\./\\S+|/\\S+|\\S*://\\S+)")
    private val chained = Regex("&&|\\|\\|\\s*\\S|\\|\\s*(?:sh|bash|zsh)\\b|[$]\\(")
    private val slash = Regex("(?:^|\\s)/([A-Za-z][A-Za-z0-9_-]*)(?=[\\s.,;:!?]|$)")

    /** The first reason [text] must not be replayed, or null. [task] is the text the task itself names (its URLs are allowed). */
    fun violation(text: String, task: String = ""): Pair<ReplayRejectionReason, String>? {
        if (text.contains("```") || text.contains("~~~")) return ReplayRejectionReason.CodeOrCommand to "a code fence"
        if (promptedCommand.containsMatchIn(text) || bareCommand.containsMatchIn(text) || chained.containsMatchIn(text)) {
            return ReplayRejectionReason.CodeOrCommand to "command-like text"
        }
        if (ControlRedaction.scrub(text) != text || longToken.containsMatchIn(text)) {
            return ReplayRejectionReason.SecretLooking to "secret-looking text"
        }
        for (match in url.findAll(text)) {
            val named = match.value.trimEnd('.', ',', ';', ':', '!', '?')
            if (!task.contains(named)) return ReplayRejectionReason.UrlNotNamed to named
        }
        for (match in slash.findAll(text)) {
            if (match.groupValues[1] != "remindme") return ReplayRejectionReason.SlashCommand to "/" + match.groupValues[1]
        }
        return null
    }
}

/**
 * Builds a [ReplayTemplate] from at least [MIN_RUNS] accepted runs of one task class, with no model.
 *
 * 1. Each output becomes atoms: its words, and one newline atom per line break (spacing is normalised).
 * 2. The skeleton is the longest common ordered subsequence of all the outputs, folded pairwise in order
 *    (a heuristic, not the optimum; ties go to the earlier atom, so the result is deterministic).
 * 3. Each run is aligned to the skeleton by its leftmost embedding; what lies between two skeleton atoms in a
 *    run is that run's span. A span the same in every run is fixed text. A span empty in some runs and not
 *    others, one with a line break, or one longer than [ReplaySlotType.MAX_PHRASE_WORDS] words rejects the
 *    template. Every other span is a slot, typed by the most specific type all its values fit.
 * 4. Each slot gets the first binder that reproduces its value in EVERY source run, tried in order: a span of
 *    the objective, a criterion word, a `key: value` field of an upstream text, and last a literal that more
 *    than half the runs (and at least two) share.
 * 5. The template is rejected when the skeleton is under [MIN_SKELETON_SHARE] of the average output, or when
 *    its fixed text has a code fence, a command, a URL, a secret, or a slash command other than `/remindme`.
 */
object ReplayGeneralizer {
    const val MIN_RUNS = 3
    const val MIN_SKELETON_SHARE = 0.40
    const val MAX_ATOMS = 1500
    private const val NL = "\n"
    private const val MARK = "\u0001"

    internal fun atoms(text: String): List<String> {
        val out = ArrayList<String>()
        text.trim().replace("\r\n", "\n").lines().forEach { line ->
            line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach { out += it }
            out += NL
        }
        while (out.isNotEmpty() && out.last() == NL) out.removeAt(out.lastIndex)
        return out
    }

    internal fun join(units: List<String>): String = buildString {
        var atLineStart = true
        for (unit in units) {
            if (unit == NL) {
                append('\n')
                atLineStart = true
            } else {
                if (!atLineStart) append(' ')
                append(unit)
                atLineStart = false
            }
        }
    }

    private fun lcs(a: List<String>, b: List<String>): List<String> {
        val n = a.size
        val m = b.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
        val out = ArrayList<String>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> { out += a[i]; i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> i++
                else -> j++
            }
        }
        return out
    }

    private fun embed(skeleton: List<String>, run: List<String>): List<Int> {
        val at = ArrayList<Int>()
        var j = 0
        for (atom in skeleton) {
            while (run[j] != atom) j++
            at += j
            j++
        }
        return at
    }

    fun generalize(runs: List<ReplaySourceRun>, minRuns: Int = MIN_RUNS): ReplayGeneralization {
        fun no(reason: ReplayRejectionReason, detail: String) = ReplayGeneralization.Rejected(reason, detail)
        if (runs.size < minRuns) return no(ReplayRejectionReason.TooFewRuns, "${runs.size} of $minRuns runs")
        val first = runs.first()
        if (runs.any { it.classId != first.classId || it.artifactKind != first.artifactKind }) {
            return no(ReplayRejectionReason.MixedRuns, "runs differ in role, objective shape, criteria or artifact kind")
        }
        val texts = runs.map { atoms(it.output) }
        if (texts.any { it.isEmpty() }) return no(ReplayRejectionReason.SkeletonTooSmall, "an output was empty")
        if (texts.any { it.size > MAX_ATOMS }) return no(ReplayRejectionReason.TooLong, "an output has over $MAX_ATOMS words")

        var skeleton = texts.first()
        for (next in texts.drop(1)) skeleton = lcs(skeleton, next)
        if (skeleton.none { it != NL }) return no(ReplayRejectionReason.SkeletonTooSmall, "nothing is common to every output")
        val embeddings = texts.map { embed(skeleton, it) }

        // Gap g lies before skeleton atom g (g == skeleton.size is the tail).
        val units = ArrayList<String>()
        val slotValues = ArrayList<List<String>>()
        for (g in 0..skeleton.size) {
            val gaps = texts.indices.map { r ->
                val from = if (g == 0) 0 else embeddings[r][g - 1] + 1
                val to = if (g == skeleton.size) texts[r].size else embeddings[r][g]
                texts[r].subList(from, to)
            }
            if (gaps.any { it.isNotEmpty() }) {
                if (gaps.all { it == gaps.first() }) {
                    units += gaps.first()
                } else {
                    if (gaps.any { it.isEmpty() }) return no(ReplayRejectionReason.OptionalSpan, "a span is missing from some outputs")
                    if (gaps.any { NL in it || it.size > ReplaySlotType.MAX_PHRASE_WORDS }) {
                        return no(ReplayRejectionReason.FreeFormSpan, "a varying span is free-form")
                    }
                    units += MARK
                    slotValues += gaps.map { it.joinToString(" ") }
                }
            }
            if (g < skeleton.size) units += skeleton[g]
        }

        val fixedLength = units.filter { it != MARK && it != NL }.sumOf { it.length + 1 }
        val averageLength = texts.map { join(it).length }.average()
        if (fixedLength < MIN_SKELETON_SHARE * averageLength) {
            return no(ReplayRejectionReason.SkeletonTooSmall, "the common text is under 40% of an average output")
        }

        val parts = join(units).split(MARK)
        if (parts.size != slotValues.size + 1) return no(ReplayRejectionReason.FreeFormSpan, "a slot marker was lost")
        val fixedText = parts.joinToString("\n")
        ReplayGuards.violation(fixedText)?.let { (reason, detail) -> return no(reason, detail) }

        val slots = slotValues.mapIndexed { index, values ->
            val type = ReplaySlotType.inferred(values)
            val binder = bind(values, runs.map { it.inputs }, type)
                ?: return no(ReplayRejectionReason.UnboundSlot, "slot ${index + 1} cannot be bound from a new task")
            if (binder is ReplayBinder.Literal) {
                ReplayGuards.violation(binder.value)?.let { (reason, detail) -> return no(reason, detail) }
            }
            ReplaySlot(index, type, binder)
        }

        val id = first.classId
        return ReplayGeneralization.Made(
            ReplayTemplate(
                id = id,
                roleId = first.roleId,
                shape = first.shape,
                criteriaShape = first.criteriaShape,
                artifactKind = first.artifactKind,
                parts = parts,
                slots = slots,
                sourceRuns = runs.size,
                avgPromptTokens = runs.sumOf { it.promptTokens } / runs.size,
                avgOutputTokens = runs.sumOf { it.outputTokens } / runs.size,
                digest = ReplayTemplate.digestOf(id, first.roleId, first.shape, first.criteriaShape, first.artifactKind, parts, slots),
            ),
        )
    }

    private fun bind(values: List<String>, inputs: List<ReplayInputs>, type: ReplaySlotType): ReplayBinder? {
        val head = inputs.first()
        val candidates = buildList<ReplayBinder> {
            ReplayShape.parse(head.objective).spans.indices.forEach { add(ReplayBinder.ObjectiveSpan(it)) }
            head.criteria.forEachIndexed { c, text -> criterionTokens(text).indices.forEach { add(ReplayBinder.CriterionToken(c, it)) } }
            head.context.forEach { item -> contextFieldKeys(item.text).forEach { add(ReplayBinder.ContextField(item.label, it)) } }
        }
        for (candidate in candidates) {
            if (inputs.indices.all { candidate.resolve(inputs[it])?.trim() == values[it].trim() }) return candidate
        }
        val counts = values.groupingBy { it }.eachCount()
        val best = counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).first()
        return if (best.value >= 2 && best.value * 2 > values.size && type.accepts(best.key)) ReplayBinder.Literal(best.key) else null
    }
}

/** A minimal checker for the acceptance criteria a machine can read: a word limit, a phrase to include, one to leave out. */
object ReplayCriteria {
    data class Result(val passed: Boolean, val failures: List<String>)

    private val maxWords = Regex("(?i)\\b(no more than|at most|maximum of|maximum|max|under|fewer than|less than|up to|within)\\s+(\\d+)\\s+words?\\b")
    private val wordsOrFewer = Regex("(?i)\\b(\\d+)\\s+words?\\s+or\\s+(?:fewer|less)\\b")
    private val mustNot = Regex("(?i)\\b(?:must not|should not|do not|don't|never|without)\\s+(?:to\\s+)?(?:include|mention|contain|use|say)\\s+[\"“]([^\"”]+)[\"”]")
    private val mustInclude = Regex("(?i)\\b(?:include|mention|contain)s?\\s+[\"“]([^\"”]+)[\"”]")

    fun check(criteria: List<String>, text: String): Result {
        val failures = ArrayList<String>()
        val words = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
        val lower = text.lowercase()
        for (criterion in criteria) {
            maxWords.findAll(criterion).forEach { match ->
                val limit = match.groupValues[2].toInt()
                val strict = match.groupValues[1].lowercase() in setOf("under", "fewer than", "less than")
                if (if (strict) words >= limit else words > limit) failures += "more than ${if (strict) limit - 1 else limit} words"
            }
            wordsOrFewer.findAll(criterion).forEach { match ->
                if (words > match.groupValues[1].toInt()) failures += "more than ${match.groupValues[1]} words"
            }
            mustNot.findAll(criterion).forEach { match ->
                if (lower.contains(match.groupValues[1].lowercase())) failures += "includes \"${match.groupValues[1]}\""
            }
            mustInclude.findAll(mustNot.replace(criterion, " ")).forEach { match ->
                if (!lower.contains(match.groupValues[1].lowercase())) failures += "lacks \"${match.groupValues[1]}\""
            }
        }
        return Result(failures.isEmpty(), failures)
    }
}
