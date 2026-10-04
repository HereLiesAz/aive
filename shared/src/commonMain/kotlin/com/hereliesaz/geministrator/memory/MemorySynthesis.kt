package com.hereliesaz.geministrator.memory

import kotlin.math.ln
import kotlin.math.sqrt

/*
 * Extractive summaries, lexicon categories, similarity and condensation for the programmatic
 * clerks. Everything here copies, selects or deletes source text; nothing writes a new claim, and
 * nothing decides which of two texts is right.
 */

// ---------------------------------------------------------------------------------------------
// Summaries: SumBasic sentence selection with an MMR redundancy check, then deletion-only trimming.
// ---------------------------------------------------------------------------------------------

internal data class MemoryExtract(val text: String, val sentences: List<String>)

internal object MemoryExtractiveSummary {
    const val MAX_CHARS = 280

    /**
     * Picks whole source sentences (in source order) up to [maxChars]: SumBasic scoring (mean
     * content-word probability, squared after each pick; Nenkova & Vanderwende 2005), favouring the
     * first and last sentence, sentences behind several phrases, and outcome/error sentences;
     * rejecting candidates that overlap a chosen one by Jaccard > 0.5. A first sentence over the
     * limit is shortened by deletion only, and never loses a number, quoted value or negation.
     */
    fun summarize(sentences: List<String>, support: Map<String, Int> = emptyMap(), maxChars: Int = MAX_CHARS): MemoryExtract? {
        val candidates = sentences.map(String::trim).filter(String::isNotEmpty).distinct()
        if (candidates.isEmpty()) return null
        val words = candidates.map(::contentWords)
        val probability = HashMap<String, Double>()
        val total = words.sumOf { it.size }.coerceAtLeast(1)
        words.flatten().forEach { probability[it] = (probability[it] ?: 0.0) + 1.0 / total }

        val chosen = mutableListOf<Int>()
        var length = 0
        while (true) {
            val next = candidates.indices
                .filter { it !in chosen }
                .filter { i -> chosen.none { jaccard(words[i], words[it]) > 0.5 } }
                .maxByOrNull { i -> score(i, candidates, words, probability, support) } ?: break
            val addition = candidates[next].length + if (chosen.isEmpty()) 0 else 1
            if (chosen.isNotEmpty() && length + addition > maxChars) break
            chosen += next
            length += addition
            words[next].forEach { w -> probability[w] = (probability[w] ?: 0.0).let { it * it } }
            if (length >= maxChars) break
        }
        if (chosen.isEmpty()) return null
        val picked = chosen.sorted().map { candidates[it] }
        val text = picked.joinToString(" ").let { if (it.length > maxChars) trim(it, maxChars) else it }
        return MemoryExtract(text, picked)
    }

    private fun score(i: Int, sentences: List<String>, words: List<Set<String>>, p: Map<String, Double>, support: Map<String, Int>): Double {
        if (words[i].isEmpty()) return 0.0
        var s = words[i].sumOf { p[it] ?: 0.0 } / words[i].size
        if (i == 0) s *= 1.5
        if (i == sentences.lastIndex && i > 0) s *= 1.3
        val phrases = support[sentences[i]] ?: 0
        if (phrases > 1) s *= 1.0 + 0.25 * (phrases - 1)
        if (OUTCOME.containsMatchIn(sentences[i])) s *= 1.3
        return s
    }

    /**
     * Shortens by deleting, in order: parentheticals, a leading discourse marker, ", which/where"
     * clauses, then trailing words. Each deletion is kept only if the text still asserts the same
     * values (numbers, quoted strings, negation); otherwise the longer text stands.
     */
    fun trim(text: String, maxChars: Int): String {
        val signature = memoryClaimSignature(text)
        var current = text
        fun attempt(candidate: String) {
            val cleaned = candidate.replace(SPACES, " ").replace(" ,", ",").replace(" .", ".").trim()
            if (cleaned.isNotEmpty() && memoryClaimSignature(cleaned) == signature) current = cleaned
        }
        listOf(PARENTHETICAL, DISCOURSE_MARKER, RELATIVE_CLAUSE).forEach { pattern ->
            if (current.length > maxChars) attempt(current.replace(pattern, ""))
        }
        if (current.length > maxChars) {
            val cut = current.take(maxChars - 1).substringBeforeLast(' ')
            if (cut.length > maxChars / 2) attempt("$cut…")
        }
        return current
    }

    fun contentWords(sentence: String): Set<String> =
        WORD.findAll(sentence.lowercase()).map { it.value.removeSuffix("s") }.filter { it.length > 2 && it !in STOPWORDS }.toSet()

    private fun jaccard(left: Set<String>, right: Set<String>): Double {
        if (left.isEmpty() || right.isEmpty()) return 0.0
        val shared = left.count { it in right }
        return shared.toDouble() / (left.size + right.size - shared)
    }

    private val WORD = Regex("[a-z][a-z0-9_]+")
    private val SPACES = Regex("\\s{2,}")
    private val OUTCOME = Regex("\\b(fail\\w*|error|exception|crash\\w*|pass(?:ed|es)?|succeed\\w*|fixed|resolved|BUILD (?:FAILED|SUCCESSFUL)|decided|chose)\\b", RegexOption.IGNORE_CASE)
    private val PARENTHETICAL = Regex("\\s*\\([^()]{1,120}\\)|\\s*\\[[^\\[\\]]{1,120}]")
    private val DISCOURSE_MARKER = Regex("^(?:so|okay|ok|well|basically|actually|also|now|then|i think|it seems|note that)\\s*,?\\s+", RegexOption.IGNORE_CASE)
    private val RELATIVE_CLAUSE = Regex(",\\s+(?:which|where|who)\\b[^,.;]*[,]?")
    private val STOPWORDS = setOf(
        "the", "and", "for", "with", "that", "this", "from", "was", "were", "are", "but", "you", "have", "has", "had", "will",
        "would", "can", "could", "should", "its", "into", "than", "then", "there", "their", "they", "them", "which", "what",
        "when", "where", "who", "how", "all", "any", "also", "just", "only", "very", "about", "after", "before", "been", "being",
    )
}

// ---------------------------------------------------------------------------------------------
// Categories: weighted lexicon, threshold, evidence.
// ---------------------------------------------------------------------------------------------

internal data class MemoryCategoryHit(val category: String, val score: Double, val evidence: List<String>)

/**
 * Weighted-lexicon classification into the built-in taxonomy. Each category has strong (3),
 * medium (2) and weak (1) terms, matched on whole words and identifier parts (never inside other
 * words), plus terms that send a match elsewhere ("test data" is data). Score = Σ weight·(1+ln tf).
 * A category is assigned when its score is ≥ 3 and ≥ half the top score; at most three. WordNet
 * synonyms of strong terms (computing senses only) count at half weight when WordNet is loaded.
 */
internal class MemoryCategoryLexicon(private val wordNet: WordNetLexicon?) {
    private val terms: Map<String, List<Pair<String, Double>>> by lazy { build() }

    fun classify(text: String): List<MemoryCategoryHit> {
        val tokens = tokens(text)
        val grams = HashMap<String, Int>()
        for (n in 1..3) for (i in 0..tokens.size - n) {
            val gram = tokens.subList(i, i + n).joinToString(" ")
            grams[gram] = (grams[gram] ?: 0) + 1
        }
        // A redirected phrase does not count for the category its words would otherwise hit.
        REDIRECTS.forEach { (phrase, _) -> grams[phrase]?.let { count -> phrase.split(' ').forEach { w -> grams[w] = (grams[w] ?: 0) - count } } }
        val scores = HashMap<String, Double>()
        val evidence = HashMap<String, MutableList<String>>()
        grams.forEach { (gram, count) ->
            if (count <= 0) return@forEach
            terms[gram]?.forEach { (category, weight) ->
                scores[category] = (scores[category] ?: 0.0) + weight * (1 + ln(count.toDouble()))
                evidence.getOrPut(category) { mutableListOf() } += "$gram:${weight.format()}${if (count > 1) "x$count" else ""}"
            }
        }
        val top = scores.values.maxOrNull() ?: return emptyList()
        return scores.filter { (_, s) -> s >= MIN_SCORE && s >= top / 2 }
            .entries.sortedByDescending { it.value }.take(MAX_LABELS)
            .map { (category, s) -> MemoryCategoryHit(category, s, evidence[category].orEmpty()) }
            .sortedBy { CATEGORY_ORDER.indexOf(it.category) }
    }

    private fun tokens(text: String): List<String> {
        val out = mutableListOf<String>()
        TOKEN.findAll(text).forEach { match ->
            val raw = match.value
            val parts = raw.split(IDENTIFIER_BOUNDARY).filter(String::isNotEmpty)
            if (parts.size > 1) out += raw.lowercase()
            parts.forEach { out += lemma(it.lowercase()) }
        }
        return out
    }

    private fun lemma(word: String): String {
        wordNet?.let { wn ->
            (wn.baseForms(word, WordNetLexicon.Pos.Noun).firstOrNull() ?: wn.baseForms(word, WordNetLexicon.Pos.Verb).firstOrNull())?.let { return it }
        }
        return when {
            word.length > 4 && word.endsWith("ies") -> word.dropLast(3) + "y"
            word.length > 4 && word.endsWith("es") && (word.endsWith("ches") || word.endsWith("shes") || word.endsWith("xes")) -> word.dropLast(2)
            word.length > 3 && word.endsWith("s") && !word.endsWith("ss") -> word.dropLast(1)
            else -> word
        }
    }

    private fun build(): Map<String, List<Pair<String, Double>>> {
        val out = HashMap<String, MutableList<Pair<String, Double>>>()
        fun add(term: String, category: String, weight: Double) {
            val key = term.split(' ').joinToString(" ") { lemma(it) }
            val list = out.getOrPut(key) { mutableListOf() }
            if (list.none { it.first == category }) list += category to weight
        }
        LEXICON.forEach { (category, tiers) ->
            tiers.forEachIndexed { tier, words ->
                val weight = 3.0 - tier
                words.split(',').map(String::trim).filter(String::isNotEmpty).forEach { add(it, category, weight) }
            }
        }
        // WordNet synonyms of strong single-word terms, computing senses only, at half weight.
        wordNet?.let { wn ->
            LEXICON.forEach { (category, tiers) ->
                tiers.first().split(',').map(String::trim).filter { ' ' !in it }.forEach { seed ->
                    listOf(WordNetLexicon.Pos.Noun, WordNetLexicon.Pos.Verb).forEach { pos ->
                        wn.senses(seed, pos).take(6).firstOrNull { wn.isComputing(it) }?.let { synset ->
                            wn.lemmas(synset).filter { it != seed && ' ' !in it }.forEach { add(it, category, 1.5) }
                        }
                    }
                }
            }
        }
        REDIRECTS.forEach { (phrase, category) -> add(phrase, category, 3.0) }
        return out
    }

    private fun Double.format(): String = if (this == kotlin.math.floor(this)) toInt().toString() else toString()

    companion object {
        const val MIN_SCORE = 3.0
        const val MAX_LABELS = 3
        private val TOKEN = Regex("[A-Za-z][A-Za-z0-9_.-]*[A-Za-z0-9]|[A-Za-z]")
        private val IDENTIFIER_BOUNDARY = Regex("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|[_\\-.]+")

        /** Phrases whose words would hit one category but belong to another. */
        private val REDIRECTS = listOf("test data" to "data", "build number" to "deployment", "release build" to "build", "model class" to "data")

        /** category -> strong, medium, weak terms (comma-separated lemmas or phrases). */
        val LEXICON: Map<String, List<String>> = linkedMapOf(
            "build" to listOf("gradle, gradlew, compile, compiler, maven, webpack, vite, cargo, bazel, kapt, ksp, proguard, r8, build script, build.gradle.kts, toolchain", "build, assemble, artifact, bundle", "make"),
            "testing" to listOf("junit, pytest, jest, vitest, espresso, assertion, assert, unit test, test suite, coverage, mock, flaky, testcase", "test, spec, verify, fixture", "check"),
            "source control" to listOf("git, commit, rebase, cherry-pick, merge conflict, pull request, branch, stash, checkout, squash", "merge, push, pull, diff, repository, repo", "tag"),
            "ci" to listOf("ci, github actions, pipeline, check run, runner, jenkins, circleci, ci/cd", "workflow, job, action", "status"),
            "dependencies" to listOf("dependency, version catalog, libs.versions.toml, npm, pnpm, yarn, pip, dependabot, lockfile, bump", "upgrade, downgrade, package, library", "version"),
            "ui" to listOf("compose, composable, layout, screen, button, css, html, theme, dark mode, recomposition, ui, ux, widget, viewmodel", "view, page, dialog, menu, icon, animation", "color"),
            "api" to listOf("api, endpoint, http, rest, graphql, grpc, status code, ktor, retrofit, websocket", "request, response, client, server", "header"),
            "data" to listOf("database, sql, sqlite, sqldelight, postgres, schema, migration, serialize, deserialize, json, protobuf, persistence, persist", "query, snapshot, record, storage, store", "table, index, row"),
            "security" to listOf("security, authentication, authorization, oauth, secret, credential, encrypt, decrypt, keystore, vulnerability, cve, xss, csrf, tls, certificate, signing key", "auth, token, permission, sign, signature", "key"),
            "performance" to listOf("performance, latency, throughput, benchmark, optimize, slow, memory leak, oom, heap, allocation, jank, fps", "profile, cache, speed, cpu, faster", "fast"),
            "configuration" to listOf("config, configuration, setting, environment variable, gradle.properties, properties file", "flag, env, environment, option", "yaml, toml"),
            "deployment" to listOf("deploy, deployment, release, publish, rollout, play store, app store, staging, production, kubernetes", "ship, docker, cloudflare, store listing", "launch"),
            "documentation" to listOf("docs, documentation, readme, changelog, markdown, kdoc, javadoc, tutorial", "guide, doc, wiki", "comment"),
            "models" to listOf("onnx, lora, quantize, inference, embedding, llm, fine-tune, epoch, tokenizer, gguf, qwen, kaggle, colab", "model, adapter, training, train, prompt, notebook", "weight"),
            "errors" to listOf("error, exception, crash, stack trace, bug, failure, regression, nullpointerexception, broken", "fail, fix, timeout, issue", "problem"),
        )
        val CATEGORY_ORDER: List<String> = LEXICON.keys.toList()
    }
}

// ---------------------------------------------------------------------------------------------
// Similarity: tags by sense and spelling; longer text by tf-idf, trigrams and shared senses.
// ---------------------------------------------------------------------------------------------

internal object MemorySimilarity {
    /**
     * Tags are the same concept when the tagger gave them one key (1.0), share a synonym (0.9), or
     * differ only by spelling (Jaro-Winkler ≥ 0.92). Broader terms alone never link tags.
     */
    fun tags(left: MemoryWorkItem, right: MemoryWorkItem): Float {
        val leftKey = left.metadata[TAG_KEY]
        val rightKey = right.metadata[TAG_KEY]
        if (leftKey != null && leftKey == rightKey) return 1f
        val a = left.text.memorySemanticKey()
        val b = right.text.memorySemanticKey()
        if (a == b) return 1f
        val leftAliases = aliases(left) + a
        val rightAliases = aliases(right) + b
        if (leftAliases.any { it in rightAliases }) return 0.9f
        val jw = jaroWinkler(a, b)
        return if (jw >= 0.92) jw.toFloat() else 0f
    }

    private fun aliases(item: MemoryWorkItem): Set<String> =
        item.metadata[TAG_ALIASES]?.split(TAG_LIST_SEPARATOR)?.map { it.memorySemanticKey() }?.toSet().orEmpty()

    /** 0.6 · tf-idf cosine over content terms + 0.4 · character-trigram cosine. */
    fun text(left: String, right: String, idf: Map<String, Double>): Float {
        val lexical = tfidfCosine(MemorySalienceFeatures.terms(left), MemorySalienceFeatures.terms(right), idf)
        val characters = cosine(left.trigramVector(), right.trigramVector()).toDouble()
        return (0.6 * lexical + 0.4 * characters).toFloat().coerceIn(0f, 1f)
    }

    private fun tfidfCosine(left: List<String>, right: List<String>, idf: Map<String, Double>): Double {
        if (left.isEmpty() || right.isEmpty()) return 0.0
        fun vector(terms: List<String>) = terms.groupingBy { it }.eachCount().mapValues { (t, c) -> (1 + ln(c.toDouble())) * (idf[t] ?: 1.0) }
        val a = vector(left)
        val b = vector(right)
        val dot = a.entries.sumOf { (t, w) -> w * (b[t] ?: 0.0) }
        val norm = sqrt(a.values.sumOf { it * it }) * sqrt(b.values.sumOf { it * it })
        return if (norm == 0.0) 0.0 else dot / norm
    }

    fun jaroWinkler(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val window = maxOf(0, maxOf(a.length, b.length) / 2 - 1)
        val aMatched = BooleanArray(a.length)
        val bMatched = BooleanArray(b.length)
        var matches = 0
        for (i in a.indices) {
            for (j in maxOf(0, i - window)..minOf(b.lastIndex, i + window)) {
                if (!bMatched[j] && a[i] == b[j]) {
                    aMatched[i] = true
                    bMatched[j] = true
                    matches++
                    break
                }
            }
        }
        if (matches == 0) return 0.0
        var transpositions = 0
        var k = 0
        for (i in a.indices) {
            if (!aMatched[i]) continue
            while (!bMatched[k]) k++
            if (a[i] != b[k]) transpositions++
            k++
        }
        val m = matches.toDouble()
        val jaro = (m / a.length + m / b.length + (m - transpositions / 2.0) / m) / 3.0
        val prefix = a.zip(b).takeWhile { (x, y) -> x == y }.size.coerceAtMost(4)
        return jaro + prefix * 0.1 * (1 - jaro)
    }
}

// ---------------------------------------------------------------------------------------------
// Condensation: the medoid, plus sentences only other members contain.
// ---------------------------------------------------------------------------------------------

internal object MemoryCondensation {
    private val SENTENCE_KINDS = setOf("node:${MemoryNodeKind.Context.name}", "node:${MemoryNodeKind.Summary.name}")

    private const val MAX_CONDENSED_CHARS = 1_200

    data class Result(val text: String, val representative: Int, val appendedFrom: List<Int>)

    /**
     * The most representative member (highest summed similarity to the rest), verbatim. For
     * sections and summaries, each other member's sentences that the text does not already cover
     * (similarity < 0.85 to every kept sentence, not a word-subset of one) are appended in member
     * order, up to a section's limit. Near-duplicates share most sentences, so the union stays small. The result must assert the same values as the
     * members; if it would not, the representative stands alone.
     */
    fun condense(items: List<MemoryWorkItem>): Result {
        val idf = MemorySalienceFeatures.idf(items.map { it.text })
        val medoid = items.indices.maxBy { i -> items.indices.sumOf { j -> if (i == j) 0.0 else MemorySimilarity.text(items[i].text, items[j].text, idf).toDouble() } }
        val base = items[medoid].text
        if (items[medoid].kind !in SENTENCE_KINDS) return Result(base, medoid, emptyList())
        val kept = memorySentences(base).toMutableList()
        val appended = mutableListOf<Int>()
        var length = base.length
        val cap = maxOf(MAX_CONDENSED_CHARS, base.length)
        items.indices.filter { it != medoid }.forEach { i ->
            memorySentences(items[i].text).forEach { sentence ->
                val words = MemoryExtractiveSummary.contentWords(sentence)
                val covered = kept.any { existing ->
                    MemorySimilarity.text(existing, sentence, idf) >= 0.85 || MemoryExtractiveSummary.contentWords(existing).containsAll(words)
                }
                if (!covered && length + sentence.length + 1 <= cap) {
                    kept += sentence
                    length += sentence.length + 1
                    if (i !in appended) appended += i
                }
            }
        }
        val text = if (appended.isEmpty()) base else "$base ${kept.drop(memorySentences(base).size).joinToString(" ")}"
        return if (memoryClaimSignature(text) == memoryClaimSignature(base)) Result(text, medoid, appended) else Result(base, medoid, emptyList())
    }
}

/**
 * MinHash signatures with LSH banding (Broder 1997; Leskovec et al., ch. 3) to find text pairs worth
 * comparing without comparing every pair. Character 5-gram shingles, 60 hash functions as 20 bands
 * of 3 rows: pairs with shingle Jaccard 0.5 become candidates ~93% of the time, 0.3 ~42%, 0.1 ~2%.
 * Hashes are FNV-1a and SplitMix64 over Longs, so every platform picks the same pairs.
 */
internal object MemoryMinHash {
    /** Neighbourhoods up to this many texts compare every pair. */
    const val ALL_PAIRS_UP_TO = 48
    private const val BANDS = 20
    private const val ROWS = 3

    fun candidates(texts: Map<String, String>): Map<String, Set<String>> {
        val buckets = HashMap<Long, MutableList<String>>()
        texts.forEach { (id, text) ->
            val signature = signature(shingles(text))
            for (band in 0 until BANDS) {
                var key = band.toLong() * PRIME
                for (row in 0 until ROWS) key = mix(key xor signature[band * ROWS + row])
                buckets.getOrPut(key) { mutableListOf() } += id
            }
        }
        val out = HashMap<String, MutableSet<String>>()
        buckets.values.filter { it.size > 1 }.forEach { members ->
            members.forEach { id -> out.getOrPut(id) { linkedSetOf() } += members.filter { it != id } }
        }
        return out
    }

    private fun shingles(text: String): Set<Long> {
        val normalized = text.lowercase().replace(WHITESPACE, " ").trim()
        if (normalized.length < 5) return setOf(fnv(normalized))
        return (0..normalized.length - 5).mapTo(HashSet()) { fnv(normalized.substring(it, it + 5)) }
    }

    private fun signature(shingles: Set<Long>): LongArray = LongArray(BANDS * ROWS) { i ->
        val seed = mix(i.toLong() + 1)
        shingles.minOf { mix(it xor seed) }
    }

    private fun fnv(value: String): Long {
        var hash = -0x340d631b7bdddcdbL
        value.forEach { char ->
            hash = hash xor char.code.toLong()
            hash *= 0x100000001b3L
        }
        return hash
    }

    /** SplitMix64 finalizer. */
    private fun mix(value: Long): Long {
        var z = value + -0x61c8864680b583ebL
        z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
        z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
        return z xor (z ushr 31)
    }

    private const val PRIME = 1_000_003L
    private val WHITESPACE = Regex("\\s+")
}
