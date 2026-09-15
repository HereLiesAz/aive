package com.hereliesaz.geministrator.memory

/**
 * Deterministic lexical features used before model inference.
 *
 * This deliberately separates two ideas:
 * - bookkeeping facts (handled by [MemoryProgrammaticAssociator]), and
 * - reproducible lexical/structural heuristics (handled here).
 *
 * The default lexicon is intentionally conservative and dependency-free. It provides technical
 * verb normalization and light morphology today, while [MemoryLexicon] is the seam for a compact
 * WordNet/VerbNet-derived backend inspired by Convey's noun/verb classifiers. A heuristic lexical
 * result is never promoted to a statement of truth merely because it is deterministic.
 */
interface MemoryLexicon {
    fun resolveNoun(word: String, context: String): MemoryLexicalSense?
    fun resolveVerb(word: String, context: String): MemoryLexicalSense?
}

data class MemoryLexicalSense(
    val lemma: String,
    /** Stable external sense id when a richer lexicon supplies one (for example a WordNet synset). */
    val senseId: String? = null,
    /** Optional coarse domain/classification supplied by the lexicon. */
    val semanticClass: String? = null,
    val confidence: Float = 1f,
) {
    init {
        require(lemma.isNotBlank())
        require(confidence in 0f..1f)
    }
}

enum class MemoryLexicalFeatureKind {
    NounLemma,
    NounSense,
    VerbLemma,
    VerbSense,
    VerbClass,
    CodeEntity,
    CodeAction,
    SubjectVerb,
    VerbObject,
    SubjectVerbObject,
}

data class MemoryLexicalFeature(
    val kind: MemoryLexicalFeatureKind,
    val value: String,
    val confidence: Float,
) {
    init {
        require(value.isNotBlank())
        require(confidence in 0f..1f)
    }
}

data class MemoryLexicalAnalysis(
    val features: Set<MemoryLexicalFeature>,
    val stronglyTechnical: Boolean,
) {
    fun values(kind: MemoryLexicalFeatureKind): Set<String> =
        features.asSequence().filter { it.kind == kind }.mapTo(linkedSetOf(), MemoryLexicalFeature::value)
}

/**
 * Small built-in resolver used until the generated lexical resource is shipped with Haive.
 *
 * Unlike a blind stemmer it only declares a verb when the normalized form belongs to the bounded
 * action vocabulary Haive already treats as meaningful. Noun normalization is deliberately light;
 * proper names and unknown concepts remain untouched so a model/WordNet backend can handle them.
 */
object RuleBasedMemoryLexicon : MemoryLexicon {
    override fun resolveNoun(word: String, context: String): MemoryLexicalSense? {
        val normalized = word.memoryLexicalToken() ?: return null
        if (normalized in MEMORY_STOPWORDS || normalized.length < 3 || normalized.all(Char::isDigit)) return null
        return MemoryLexicalSense(
            lemma = conservativeNounLemma(normalized),
            confidence = 0.72f,
        )
    }

    override fun resolveVerb(word: String, context: String): MemoryLexicalSense? {
        val normalized = word.memoryLexicalToken() ?: return null
        val lemma = normalizeKnownAction(normalized) ?: return null
        return MemoryLexicalSense(
            lemma = lemma,
            semanticClass = actionFamily(lemma),
            confidence = 0.94f,
        )
    }
}

class MemoryLexicalAnalyzer(
    private val lexicon: MemoryLexicon = RuleBasedMemoryLexicon,
) {
    fun analyze(text: String): MemoryLexicalAnalysis {
        if (text.isBlank()) return MemoryLexicalAnalysis(emptySet(), stronglyTechnical = false)

        val features = linkedSetOf<MemoryLexicalFeature>()
        val code = extractCodeSemanticHints(text)
        code.nounCandidates.forEach { candidate ->
            candidate.trim().takeIf(String::isNotEmpty)?.let {
                features += MemoryLexicalFeature(
                    MemoryLexicalFeatureKind.CodeEntity,
                    it.memorySemanticKey(),
                    confidence = 0.99f,
                )
            }
        }
        code.verbCandidates.forEach { candidate ->
            val normalized = normalizeKnownAction(candidate) ?: candidate.memorySemanticKey()
            features += MemoryLexicalFeature(
                MemoryLexicalFeatureKind.CodeAction,
                normalized,
                confidence = 0.99f,
            )
        }

        val words = MEMORY_WORD.findAll(text).map { it.value }.toList()
        words.forEach { word ->
            lexicon.resolveNoun(word, text)?.let { sense ->
                features += MemoryLexicalFeature(MemoryLexicalFeatureKind.NounLemma, sense.lemma, sense.confidence)
                sense.senseId?.let {
                    features += MemoryLexicalFeature(MemoryLexicalFeatureKind.NounSense, it, sense.confidence)
                }
            }
            lexicon.resolveVerb(word, text)?.let { sense ->
                features += MemoryLexicalFeature(MemoryLexicalFeatureKind.VerbLemma, sense.lemma, sense.confidence)
                sense.senseId?.let {
                    features += MemoryLexicalFeature(MemoryLexicalFeatureKind.VerbSense, it, sense.confidence)
                }
                sense.semanticClass?.let {
                    features += MemoryLexicalFeature(MemoryLexicalFeatureKind.VerbClass, it, sense.confidence)
                }
            }
        }

        parseMemorySvo(words, text, lexicon)?.let { svo ->
            val subject = svo.subject.memorySemanticKey()
            val verb = svo.verb.lemma.memorySemanticKey()
            val obj = svo.obj.memorySemanticKey()
            features += MemoryLexicalFeature(MemoryLexicalFeatureKind.SubjectVerb, "$subject|$verb", 0.72f)
            features += MemoryLexicalFeature(MemoryLexicalFeatureKind.VerbObject, "$verb|$obj", 0.78f)
            features += MemoryLexicalFeature(MemoryLexicalFeatureKind.SubjectVerbObject, "$subject|$verb|$obj", 0.84f)
        }

        return MemoryLexicalAnalysis(
            features = features,
            stronglyTechnical = text.looksStronglyTechnical() && (code.nounCandidates.isNotEmpty() || code.verbCandidates.isNotEmpty()),
        )
    }
}

internal fun memoryLexicalFeatures(
    text: String,
    lexicon: MemoryLexicon = RuleBasedMemoryLexicon,
): MemoryLexicalAnalysis = MemoryLexicalAnalyzer(lexicon).analyze(text)

/** Query/index normalization shared by lexical association and recall candidate generation. */
internal fun String.memoryLexicalTerms(
    lexicon: MemoryLexicon = RuleBasedMemoryLexicon,
): Set<String> {
    val analysis = memoryLexicalFeatures(this, lexicon)
    return buildSet {
        analysis.features.forEach { feature ->
            when (feature.kind) {
                MemoryLexicalFeatureKind.NounLemma,
                MemoryLexicalFeatureKind.VerbLemma,
                MemoryLexicalFeatureKind.CodeEntity,
                MemoryLexicalFeatureKind.CodeAction,
                -> add(feature.value)
                else -> Unit
            }
        }
    }
}

internal fun String.looksStronglyTechnical(): Boolean =
    '`' in this ||
        '(' in this ||
        '{' in this ||
        "::" in this ||
        "http://" in lowercase() ||
        "https://" in lowercase() ||
        TECHNICAL_PATH.containsMatchIn(this) ||
        TECHNICAL_IDENTIFIER.containsMatchIn(this) ||
        TECHNICAL_COMMAND.containsMatchIn(this)

private data class MemorySvoParts(
    val subject: String,
    val verb: MemoryLexicalSense,
    val obj: String,
)

/**
 * Convey-inspired weak S/V/O signature extraction. It is intentionally not advertised as syntax:
 * first confidently-known verb, nearest lexical word on each side. The resulting relation is
 * weighted heuristic evidence only.
 */
private fun parseMemorySvo(
    words: List<String>,
    context: String,
    lexicon: MemoryLexicon,
): MemorySvoParts? {
    if (words.size < 3) return null
    for (index in 1 until words.lastIndex) {
        val verb = lexicon.resolveVerb(words[index], context) ?: continue
        val subject = words.subList(0, index)
            .asReversed()
            .firstOrNull { it.memoryLexicalToken()?.let { token -> token !in MEMORY_STOPWORDS } == true }
            ?: continue
        val obj = words.subList(index + 1, words.size)
            .asReversed()
            .firstOrNull { it.memoryLexicalToken()?.let { token -> token !in MEMORY_STOPWORDS } == true }
            ?: continue
        return MemorySvoParts(subject, verb, obj)
    }
    return null
}

private fun conservativeNounLemma(value: String): String = when {
    value.length > 5 && value.endsWith("ies") -> value.dropLast(3) + "y"
    value.length > 5 && value.endsWith("ches") -> value.dropLast(2)
    value.length > 5 && value.endsWith("shes") -> value.dropLast(2)
    value.length > 4 && value.endsWith("xes") -> value.dropLast(2)
    value.length > 4 && value.endsWith("zes") -> value.dropLast(2)
    value.length > 4 && value.endsWith("s") && !value.endsWith("ss") && !value.endsWith("us") -> value.dropLast(1)
    else -> value
}

private fun normalizeKnownAction(value: String): String? {
    val lower = value.memorySemanticKey()
    ACTION_NORMALIZATION[lower]?.let { return it }
    if (lower in CANONICAL_ACTIONS) return lower

    // Convey/WordNet-style detachment, but only accept a candidate from our known action lexicon.
    VERB_DETACHMENT_RULES.forEach { (suffix, replacement) ->
        if (lower.length > suffix.length + 1 && lower.endsWith(suffix)) {
            val candidate = lower.removeSuffix(suffix) + replacement
            if (candidate in CANONICAL_ACTIONS) return candidate
        }
    }
    return null
}

private fun actionFamily(lemma: String): String = when (lemma) {
    "create", "build", "compile", "assemble", "generate" -> "creation"
    "read", "fetch", "load", "receive", "download" -> "acquisition"
    "write", "update", "save", "persist", "serialize", "upload", "send" -> "persistence"
    "delete", "remove" -> "deletion"
    "parse", "deserialize", "decode", "map", "filter", "sort", "transform" -> "transformation"
    "validate", "test", "verify", "lint", "check" -> "validation"
    "merge", "commit", "checkout", "rebase", "push", "pull" -> "source-control"
    "deploy", "release" -> "deployment"
    else -> "action"
}

private fun String.memoryLexicalToken(): String? =
    trim().lowercase().trim { !it.isLetterOrDigit() && it != '_' && it != '-' }.takeIf(String::isNotBlank)

internal fun String.memorySemanticKey(): String =
    trim().lowercase().replace(Regex("\\s+"), " ")

private val VERB_DETACHMENT_RULES = listOf(
    "ies" to "y",
    "es" to "e",
    "es" to "",
    "ed" to "e",
    "ed" to "",
    "ing" to "e",
    "ing" to "",
    "s" to "",
)

private val ACTION_NORMALIZATION = mapOf(
    "wrote" to "write",
    "written" to "write",
    "sent" to "send",
    "built" to "build",
    "ran" to "run",
    "read" to "read",
    "saved" to "persist",
    "saving" to "persist",
    "saves" to "persist",
    "persisted" to "persist",
    "persists" to "persist",
    "removed" to "delete",
    "removes" to "delete",
    "deleted" to "delete",
    "deletes" to "delete",
    "fetched" to "fetch",
    "fetches" to "fetch",
    "got" to "fetch",
    "gets" to "fetch",
    "verified" to "verify",
    "verifies" to "verify",
)

private val CANONICAL_ACTIONS = setOf(
    "create", "build", "compile", "assemble", "generate",
    "read", "fetch", "load", "receive", "download",
    "write", "update", "save", "persist", "serialize", "upload", "send",
    "delete", "remove",
    "parse", "deserialize", "decode", "encode", "map", "filter", "sort", "transform",
    "validate", "test", "verify", "lint", "check",
    "merge", "commit", "checkout", "rebase", "push", "pull",
    "deploy", "release", "run",
)

private val MEMORY_STOPWORDS = setOf(
    "a", "an", "the", "of", "to", "in", "on", "at", "for", "with", "and", "or", "is", "are",
    "was", "were", "be", "been", "being", "by", "as", "it", "its", "that", "this", "from", "into",
    "than", "then", "so", "not", "no", "do", "does", "did", "has", "have", "had", "will", "would",
    "can", "could", "should", "may", "might", "we", "you", "i", "they", "he", "she", "them", "our",
)

private val MEMORY_WORD = Regex("[A-Za-z][A-Za-z0-9_-]*")
private val TECHNICAL_PATH = Regex("(?:^|\\s)([A-Za-z0-9_.-]+/)+(?:[A-Za-z0-9_.-]+)")
private val TECHNICAL_IDENTIFIER = Regex("\\b(?:[a-z]+[A-Z][A-Za-z0-9]*|[A-Za-z][A-Za-z0-9]*_[A-Za-z0-9_]+)\\b")
private val TECHNICAL_COMMAND = Regex(
    "\\b(?:git|gradle|gradlew|mvn|npm|pnpm|yarn|cargo|pytest|GET|POST|PUT|PATCH|DELETE)\\b",
    RegexOption.IGNORE_CASE,
)
