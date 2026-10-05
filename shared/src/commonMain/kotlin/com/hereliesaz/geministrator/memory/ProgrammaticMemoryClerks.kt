package com.hereliesaz.geministrator.memory

import kotlin.math.sqrt
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Deterministic clerks for every memory stage: no download, no inference, same contract.
 *
 * Each clerk only reorganizes what its packet supplies. Text is copied or extracted, never generated,
 * so nothing can be invented; condensation declines any cluster whose members differ in values
 * (numbers, quoted strings, negation), because merging those would mean picking one.
 *
 * These are the default engine. A model-backed clerk can replace any single stage.
 */
object ProgrammaticMemoryClerks {
    const val MODEL_PREFIX: String = "programmatic/"
    private const val PROGRAMMATIC_MAX_OUTPUT_CHARS = 256_000

    fun all(nowEpochMillis: () -> Long = ::programmaticNowEpochMillis): List<MemoryMicroAgent> =
        MemoryMicroAgentRouter.REQUIRED_ROLES.map { forRole(it, nowEpochMillis) }

    fun forRole(
        role: MemoryMicroAgentRole,
        nowEpochMillis: () -> Long = ::programmaticNowEpochMillis,
    ): MemoryMicroAgent = when (role) {
        MemoryMicroAgentRole.Sectioner -> ProgrammaticSectioner(nowEpochMillis)
        MemoryMicroAgentRole.SalienceFilter -> ProgrammaticSalienceFilter(nowEpochMillis)
        MemoryMicroAgentRole.NounTagger -> ProgrammaticTagger(role, nowEpochMillis)
        MemoryMicroAgentRole.VerbTagger -> ProgrammaticTagger(role, nowEpochMillis)
        MemoryMicroAgentRole.PhraseSynthesizer -> ProgrammaticPhraseSynthesizer(nowEpochMillis)
        MemoryMicroAgentRole.SummarySynthesizer -> ProgrammaticSummarySynthesizer(nowEpochMillis)
        MemoryMicroAgentRole.CategoryClassifier -> ProgrammaticCategoryClassifier(nowEpochMillis)
        MemoryMicroAgentRole.AssociationLinker -> ProgrammaticAssociationLinker(nowEpochMillis)
        MemoryMicroAgentRole.CondensationRewriter -> ProgrammaticCondensationRewriter(nowEpochMillis)
    }

    /** Spec for a programmatic clerk. It has no artifact; the deployment manifest is only a placeholder. */
    fun modelSpec(role: MemoryMicroAgentRole): MemoryMicroAgentModelSpec = MemoryMicroAgentModelSpec(
        modelId = MODEL_PREFIX + role.name,
        // Output is copied/extracted text plus provenance IDs, not generation; the model-sized
        // character cap would only truncate provenance.
        maxOutputChars = PROGRAMMATIC_MAX_OUTPUT_CHARS,
        requirements = if (role == MemoryMicroAgentRole.AssociationLinker) {
            MemoryModelRequirements.embeddings()
        } else {
            MemoryModelRequirements.generation()
        },
    )
}

/** Marker for clerks that run without a model; the technical tagging fast path does not wrap them. */
interface ProgrammaticMemoryMicroAgent : MemoryMicroAgent

private abstract class ProgrammaticClerk(
    final override val role: MemoryMicroAgentRole,
    protected val nowEpochMillis: () -> Long,
) : ProgrammaticMemoryMicroAgent {
    final override val model: MemoryMicroAgentModelSpec = ProgrammaticMemoryClerks.modelSpec(role)

    protected fun MemoryWorkPacket.namespace(): String =
        "${queueId.value}:${stage.name}:$packetKey:${role.name}:programmatic"

    protected fun metadata(extra: Map<String, String> = emptyMap()): Map<String, String> =
        mapOf("microAgentRole" to role.name, "semanticSource" to "programmatic") + extra
}

// ---------------------------------------------------------------------------------------------
// Sectioning: typed blocks (MemorySectioning.kt), typed blocks whole, prose split by topic and
// sentence, neighbours packed.
// ---------------------------------------------------------------------------------------------

private class ProgrammaticSectioner(now: () -> Long) : ProgrammaticClerk(MemoryMicroAgentRole.Sectioner, now) {
    override suspend fun process(packet: MemoryWorkPacket): MemoryMutationBatch {
        val pieces = packet.items.flatMap { item ->
            item.text.structuralBlocks(MAX_SECTION_CHARS).map { block -> item to block }
        }
        if (pieces.isEmpty()) return MemoryMutationBatch()
        val totalChars = pieces.sumOf { it.second.length }
        val target = maxOf(MAX_SECTION_CHARS, (totalChars + model.maxMutations - 1) / model.maxMutations)

        // Pack consecutive blocks of the same chunk up to the target size.
        val packed = mutableListOf<Pair<MemoryWorkItem, StringBuilder>>()
        pieces.forEach { (item, block) ->
            val last = packed.lastOrNull()
            if (last != null && last.first.id == item.id &&
                (last.second.length < MIN_SECTION_CHARS || last.second.length + block.length + 2 <= target)
            ) {
                last.second.append("\n\n").append(block)
            } else {
                packed += item to StringBuilder(block)
            }
        }
        val namespace = packet.namespace()
        return MemoryMutationBatch(
            sectionsToAdd = packed.mapIndexed { index, (item, text) ->
                val chunkOrdinal = item.metadata["ordinal"]?.toIntOrNull() ?: 0
                MemorySection(
                    id = MemorySectionId("$namespace:section:$index"),
                    episodeId = packet.episodeId,
                    sourceChunkIds = setOf(MemoryChunkId(item.id)),
                    ordinal = chunkOrdinal * SECTION_ORDINAL_STRIDE + index,
                    text = text.toString().trim(),
                    metadata = metadata(
                        buildMap {
                            item.metadata["label"]?.let { put("label", it) }
                            put("sourceKind", item.kind.removePrefix("source:"))
                        },
                    ),
                )
            }.filter { it.text.isNotBlank() },
        )
    }

    private companion object {
        const val MAX_SECTION_CHARS = 1_200
        const val MIN_SECTION_CHARS = 160
        const val SECTION_ORDINAL_STRIDE = 1_000
    }
}

// ---------------------------------------------------------------------------------------------
// Salience: keep everything except bookkeeping noise and duplicates; score what is kept.
// ---------------------------------------------------------------------------------------------

private class ProgrammaticSalienceFilter(now: () -> Long) :
    ProgrammaticClerk(MemoryMicroAgentRole.SalienceFilter, now) {
    override suspend fun process(packet: MemoryWorkPacket): MemoryMutationBatch {
        val createdAt = nowEpochMillis()
        val namespace = packet.namespace()
        val prompts = packet.items.filter { it.metadata["sourceKind"] in PROMPT_KINDS }.map { it.text }
        val idf = MemorySalienceFeatures.idf(packet.items.map { it.text })
        val promptTerms = prompts.flatMap { MemorySalienceFeatures.terms(it) }.toSet()
        val wordNet = runCatching { MemoryLanguageResources.get().wordNet }.getOrNull()

        data class Kept(val index: Int, val section: MemoryWorkItem, val text: String, val collapsed: Int, val shingles: Set<String>, var duplicates: Int = 0)
        val seen = hashSetOf<String>()
        val kept = mutableListOf<Kept>()
        packet.items.forEachIndexed { index, section ->
            val sourceKind = section.metadata["sourceKind"]
            val prompt = sourceKind in PROMPT_KINDS
            val (text, collapsed) = MemorySalienceFeatures.collapseRepeatedLines(section.text.trim())
            // User prompts are never dropped; everything else loses noise and repeats.
            if (!prompt && (text.isNoise() || MemorySalienceFeatures.isToolNoise(text) || text.isFewWords(wordNet))) return@forEachIndexed
            if (text.isBlank()) return@forEachIndexed
            val shingles = MemorySalienceFeatures.shingles(text)
            if (!seen.add(text.memorySemanticKey())) {
                kept.firstOrNull { it.text.memorySemanticKey() == text.memorySemanticKey() }?.let { it.duplicates++ }
                return@forEachIndexed
            }
            val original = kept.firstOrNull { MemorySalienceFeatures.jaccard(it.shingles, shingles) >= NEAR_DUPLICATE }
            if (original != null && !prompt) {
                original.duplicates++
                return@forEachIndexed
            }
            kept += Kept(index, section, text, collapsed, shingles)
        }
        val nodes = kept.map { item ->
            val features = MemorySalienceFeatures.score(
                text = item.text,
                sourceKind = item.section.metadata["sourceKind"],
                promptTerms = if (item.section.metadata["sourceKind"] in PROMPT_KINDS) emptySet() else promptTerms,
                idf = idf,
                duplicates = item.duplicates,
                position = if (packet.items.size > 1) item.index.toFloat() / (packet.items.size - 1) else 0f,
            )
            MemoryNode(
                id = MemoryNodeId("$namespace:node:${item.index}"),
                kind = MemoryNodeKind.Context,
                text = item.text,
                sourceEpisodeIds = setOf(packet.episodeId),
                sourceSectionIds = setOf(MemorySectionId(item.section.id)),
                salience = features.score,
                confidence = 1f,
                createdAtEpochMillis = createdAt,
                metadata = metadata(
                    buildMap {
                        put("salienceFeatures", features.explain())
                        if (item.collapsed > 0) put("collapsedRepeatedLines", item.collapsed.toString())
                        if (item.duplicates > 0) put("nearDuplicatesDropped", item.duplicates.toString())
                    },
                ),
            )
        }.take(model.maxMutations)
        return MemoryMutationBatch(nodesToAdd = nodes)
    }

    private companion object {
        val PROMPT_KINDS = setOf(MemorySourceKind.UserPrompt.name, MemorySourceKind.Objective.name)
        const val NEAR_DUPLICATE = 0.8
    }
}

/**
 * Prose-shaped text in which under [MIN_KNOWN_WORD_SHARE] of the words are English or known
 * technical words (WordNet, the overlay, common function words): encoded blobs, keyboard mash,
 * hashes run together. Code, paths, logs and stack traces are exempt, as is anything under
 * [MIN_WORDS_FOR_DICTIONARY_CHECK] words. Without WordNet the check is skipped.
 */
private fun String.isFewWords(wordNet: WordNetLexicon?): Boolean {
    if (wordNet == null || looksStronglyTechnical() || LOG_OR_TRACE.containsMatchIn(this)) return false
    val words = DICTIONARY_WORD.findAll(this).map { it.value.lowercase() }.toList()
    if (words.size < MIN_WORDS_FOR_DICTIONARY_CHECK) return false
    val known = words.count { word ->
        word in COMMON_WORDS || wordNet.partsOfSpeech(word).isNotEmpty() ||
            MemoryTechnicalLexicon.lookup(word, WordNetLexicon.Pos.Noun) != null || MemoryTechnicalLexicon.knowsVerb(word)
    }
    return known < words.size * MIN_KNOWN_WORD_SHARE
}

private val DICTIONARY_WORD = Regex("[A-Za-z]{2,}")
private val LOG_OR_TRACE = Regex("^\\s*(at |Caused by:|\\[?(INFO|WARN|ERROR|DEBUG|TRACE)\\b|\\d{4}-\\d\\d-\\d\\d)", RegexOption.MULTILINE)
private const val MIN_WORDS_FOR_DICTIONARY_CHECK = 6
private const val MIN_KNOWN_WORD_SHARE = 0.3
private val COMMON_WORDS = setOf(
    "the", "a", "an", "and", "or", "but", "if", "of", "to", "in", "on", "at", "by", "for", "with", "from", "as", "is", "are",
    "was", "were", "be", "been", "it", "its", "this", "that", "these", "those", "we", "you", "they", "he", "she", "i", "not", "no",
)

private fun String.isNoise(): Boolean {
    val text = trim()
    if (text.length < 12) return true
    if (ACKNOWLEDGEMENT.matches(text.lowercase())) return true
    val meaningful = text.count { it.isLetterOrDigit() }
    return meaningful < text.length * 0.3
}

// ---------------------------------------------------------------------------------------------
// Tags: code entities/actions, keyphrases (RAKE-style), and a bounded action vocabulary.
// ---------------------------------------------------------------------------------------------

private class ProgrammaticTagger(
    role: MemoryMicroAgentRole,
    now: () -> Long,
) : ProgrammaticClerk(role, now) {
    private val nodeKind = if (role == MemoryMicroAgentRole.NounTagger) MemoryNodeKind.NounTag else MemoryNodeKind.VerbTag
    private val conceptKind = if (role == MemoryMicroAgentRole.NounTagger) MemoryConceptKind.Entity else MemoryConceptKind.Action

    override suspend fun process(packet: MemoryWorkPacket): MemoryMutationBatch {
        val analyzer = runCatching { MemoryTextAnalyzer(MemoryLanguageResources.get()) }.getOrNull()
        // key -> (concept, items in which it occurs, in first-seen order)
        val occurrences = linkedMapOf<String, Pair<MemoryConcept, LinkedHashSet<MemoryWorkItem>>>()
        val itemIndex = packet.items.withIndex().associate { (i, item) -> item.id to i }
        val verbObjects = hashMapOf<String, LinkedHashSet<MemoryVerbObject>>()
        packet.items.forEach { item ->
            val concepts = if (analyzer != null) {
                val analysis = analyzer.analyze(item.text)
                analysis.verbObjects.forEach { pair -> verbObjects.getOrPut(pair.actionKey) { linkedSetOf() } += pair }
                analysis.concepts.filter { it.kind == conceptKind }
            } else {
                // Language resources unavailable: the earlier keyphrase and verb-list rules.
                val tags = if (nodeKind == MemoryNodeKind.NounTag) item.text.nounTags() else item.text.verbTags()
                tags.map { MemoryConcept("legacy:${it.memorySemanticKey()}", it, conceptKind, confidence = 0.8f) }
            }
            concepts.forEach { concept ->
                val entry = occurrences.getOrPut(concept.key) { concept to linkedSetOf() }
                entry.second += item
                if (entry.first.impliedBy != null && concept.impliedBy == null) occurrences[concept.key] = concept to entry.second
            }
        }
        // The Tags stage runs noun and verb clerks together; each gets half the shared budget.
        var budget = model.maxMutations / 2
        val createdAt = nowEpochMillis()
        val namespace = packet.namespace()
        val nodes = mutableListOf<MemoryNode>()
        val edges = mutableListOf<MemoryEdge>()
        // Explicit mentions first, ranked YAKE-style; implied concepts fill what budget remains.
        occurrences.entries
            .sortedWith(compareBy<Map.Entry<String, Pair<MemoryConcept, LinkedHashSet<MemoryWorkItem>>>> { it.value.first.impliedBy != null }
                .thenByDescending { (_, entry) ->
                    keyphraseImportance(entry.first.text, entry.second.mapNotNull { itemIndex[it.id] }, packet.items.size)
                })
            .forEachIndexed { index, (key, entry) ->
                val (concept, items) = entry
                val cost = 1 + items.size
                if (cost > budget) return@forEachIndexed
                budget -= cost
                val nodeId = MemoryNodeId("$namespace:node:$index:${key.idPart()}")
                nodes += MemoryNode(
                    id = nodeId,
                    kind = nodeKind,
                    text = concept.text,
                    sourceEpisodeIds = items.flatMapTo(linkedSetOf(packet.episodeId)) { it.sourceEpisodeIds() },
                    sourceSectionIds = items.flatMapTo(linkedSetOf()) { it.sourceSectionIds() },
                    salience = items.maxOf { it.metadata["salience"]?.toFloatOrNull() ?: 0.5f } *
                        (if (concept.impliedBy != null) IMPLIED_SALIENCE else 1f),
                    confidence = concept.confidence,
                    createdAtEpochMillis = createdAt,
                    metadata = metadata(conceptMetadata(concept, verbObjects[key].orEmpty())),
                )
                items.forEachIndexed { itemIndex, item ->
                    edges += MemoryEdge(
                        id = MemoryEdgeId("$namespace:edge:$index:$itemIndex"),
                        from = nodeId,
                        to = MemoryNodeId(item.id),
                        relation = MemoryRelationKind.Indexes,
                        weight = if (concept.impliedBy != null) concept.confidence else 1f,
                        createdAtEpochMillis = createdAt,
                        metadata = metadata(),
                    )
                }
            }
        return MemoryMutationBatch(nodesToAdd = nodes, edgesToAdd = edges)
    }

    private fun conceptMetadata(concept: MemoryConcept, pairs: Set<MemoryVerbObject>): Map<String, String> = buildMap {
        put(TAG_KEY, concept.key)
        if (concept.aliases.isNotEmpty()) put(TAG_ALIASES, concept.aliases.joinToString(TAG_LIST_SEPARATOR))
        concept.impliedBy?.let { put(TAG_IMPLIED_BY, it.name) }
        concept.sense?.let { put(TAG_SENSE, it) }
        if (concept.negated) put(TAG_NEGATED, "true")
        if (pairs.isNotEmpty()) {
            put(TAG_OBJECTS, pairs.joinToString(TAG_LIST_SEPARATOR) { it.entityKey })
            put(TAG_PHRASES, pairs.joinToString(TAG_LIST_SEPARATOR) { it.text })
            put(TAG_SPANS, pairs.joinToString(TAG_LIST_SEPARATOR) { it.span.replace(TAG_LIST_SEPARATOR, " ") })
            put(TAG_SENTENCES, pairs.joinToString(TAG_LIST_SEPARATOR) { it.sentence.replace(TAG_LIST_SEPARATOR, " ") })
        }
    }

    private companion object {
        /** Implied concepts rank below what the text states outright. */
        const val IMPLIED_SALIENCE = 0.6f
    }
}

/**
 * YAKE-style importance of a tag (Campos et al., 2020), higher first, from statistics of the packet
 * alone: spread (share of sections it occurs in), casing (capitalised, acronym or identifier text
 * earns 0.5), and position, damped by ln(ln(3 + first section index)) so early mentions lead.
 */
internal fun keyphraseImportance(text: String, sections: List<Int>, sectionCount: Int): Double {
    if (sections.isEmpty()) return 0.0
    val spread = sections.distinct().size.toDouble() / sectionCount.coerceAtLeast(1)
    val casing = if (text.any(Char::isUpperCase) || text.any { it == '_' || it == '.' || it == '/' }) 0.5 else 0.0
    val position = kotlin.math.ln(kotlin.math.ln(3.0 + sections.min()))
    return (spread + casing) / position
}

/** Tag node metadata written by the programmatic tagger. */
internal const val TAG_KEY = "conceptKey"
internal const val TAG_ALIASES = "aliases"
internal const val TAG_IMPLIED_BY = "impliedBy"
internal const val TAG_SENSE = "sense"
internal const val TAG_NEGATED = "negated"
internal const val TAG_OBJECTS = "objectKeys"
internal const val TAG_PHRASES = "objectPhrases"
internal const val TAG_SPANS = "objectSpans"
internal const val TAG_SENTENCES = "objectSentences"

/** Phrase node metadata: where in the source the pair was read. */
internal const val PHRASE_SPAN = "span"
internal const val PHRASE_SENTENCE = "sourceSentence"
internal const val TAG_LIST_SEPARATOR = " | "

internal fun String.nounTags(maxKeyphrases: Int = 6): List<String> {
    val code = extractCodeSemanticHints(this).nounCandidates.map { it.trim() }.filter { it.length in 2..80 }
    return (code + keyphrases(maxKeyphrases)).distinctBy { it.memorySemanticKey() }
}

internal fun String.verbTags(): List<String> {
    val code = extractCodeSemanticHints(this).verbCandidates
    val words = WORD.findAll(this).mapNotNull { match ->
        RuleBasedMemoryLexicon.resolveVerb(match.value, this)?.lemma ?: generalVerbLemma(match.value)
    }
    return (code + words).map(String::lowercase).distinct()
}

/** RAKE-style keyphrases: runs of content words between stopwords/verbs/punctuation, scored by degree. */
internal fun String.keyphrases(max: Int): List<String> {
    val phrases = mutableListOf<List<String>>()
    PHRASE_BOUNDARY.split(this).forEach { fragment ->
        var run = mutableListOf<String>()
        WORD.findAll(fragment).forEach { match ->
            val word = match.value
            val lower = word.lowercase()
            val boundary = lower in STOPWORDS || word.length < 3 || generalVerbLemma(word) != null ||
                RuleBasedMemoryLexicon.resolveVerb(word, this) != null
            if (boundary) {
                if (run.isNotEmpty()) phrases += run
                run = mutableListOf()
            } else {
                run += word
                if (run.size == 3) {
                    phrases += run
                    run = mutableListOf()
                }
            }
        }
        if (run.isNotEmpty()) phrases += run
    }
    if (phrases.isEmpty()) return emptyList()
    val frequency = hashMapOf<String, Int>()
    val degree = hashMapOf<String, Int>()
    phrases.forEach { phrase ->
        phrase.forEach { word ->
            val key = word.lowercase()
            frequency[key] = (frequency[key] ?: 0) + 1
            degree[key] = (degree[key] ?: 0) + phrase.size
        }
    }
    return phrases
        .map { phrase -> phrase.joinToString(" ") to phrase.sumOf { degree.getValue(it.lowercase()).toDouble() / frequency.getValue(it.lowercase()) } }
        .distinctBy { it.first.lowercase() }
        .sortedByDescending { it.second }
        .take(max)
        .map { it.first }
}

private fun generalVerbLemma(word: String): String? {
    val lower = word.lowercase()
    if (lower in GENERAL_VERBS) return lower
    GENERAL_VERB_FORMS[lower]?.let { return it }
    for ((suffix, replacement) in DETACHMENT) {
        if (lower.length > suffix.length + 2 && lower.endsWith(suffix)) {
            val candidate = lower.removeSuffix(suffix) + replacement
            if (candidate in GENERAL_VERBS) return candidate
        }
    }
    return null
}

// ---------------------------------------------------------------------------------------------
// Phrases: an action and an entity found in the same section.
// ---------------------------------------------------------------------------------------------

private class ProgrammaticPhraseSynthesizer(now: () -> Long) :
    ProgrammaticClerk(MemoryMicroAgentRole.PhraseSynthesizer, now) {
    override suspend fun process(packet: MemoryWorkPacket): MemoryMutationBatch {
        val bySection = linkedMapOf<String, MutableList<MemoryWorkItem>>()
        packet.items.forEach { item ->
            item.sourceSectionIds().ifEmpty { listOf(MemorySectionId("")) }.forEach { section ->
                bySection.getOrPut(section.value) { mutableListOf() } += item
            }
        }
        var budget = model.maxMutations
        val seen = hashSetOf<String>()
        val createdAt = nowEpochMillis()
        val namespace = packet.namespace()
        val nodes = mutableListOf<MemoryNode>()
        val edges = mutableListOf<MemoryEdge>()
        bySection.values.forEach { items ->
            val verbs = items.filter { it.kind == "node:${MemoryNodeKind.VerbTag.name}" }
            val nouns = items.filter { it.kind == "node:${MemoryNodeKind.NounTag.name}" }
            val nounsByKey = nouns.filter { it.metadata[TAG_KEY] != null }.associateBy { it.metadata.getValue(TAG_KEY) }
            verbs.forEach verb@{ verb ->
                // Programmatic tags carry the verb's objects as the text states them; pair only those.
                // Tags without that record (model-made) keep the earlier co-occurrence pairing.
                val objectKeys = verb.metadata[TAG_OBJECTS]?.split(TAG_LIST_SEPARATOR)
                val partners = objectKeys?.mapNotNull(nounsByKey::get) ?: nouns.take(MAX_NOUNS_PER_VERB)
                partners.forEach noun@{ noun ->
                    val text = "${verb.text.trim()} ${noun.text.trim()}"
                    if (budget < 3 || !seen.add(text.memorySemanticKey())) return@noun
                    budget -= 3
                    val index = nodes.size
                    val id = MemoryNodeId("$namespace:node:$index:${text.idPart()}")
                    nodes += MemoryNode(
                        id = id,
                        kind = MemoryNodeKind.Phrase,
                        text = text,
                        sourceEpisodeIds = (verb.sourceEpisodeIds() + noun.sourceEpisodeIds()).toSet() + packet.episodeId,
                        sourceSectionIds = verb.sourceSectionIds().toSet() intersect noun.sourceSectionIds().toSet(),
                        salience = (verb.salience() + noun.salience()) / 2f,
                        confidence = 0.7f,
                        createdAtEpochMillis = createdAt,
                        metadata = metadata(sourceOf(verb, noun)),
                    )
                    listOf(verb, noun).forEachIndexed { part, tag ->
                        edges += MemoryEdge(
                            id = MemoryEdgeId("$namespace:edge:$index:$part"),
                            from = id,
                            to = MemoryNodeId(tag.id),
                            relation = MemoryRelationKind.Composes,
                            createdAtEpochMillis = createdAt,
                            metadata = metadata(),
                        )
                    }
                }
            }
        }
        return MemoryMutationBatch(nodesToAdd = nodes, edgesToAdd = edges)
    }

    /** The span and sentence the tagger recorded for this verb-object pair, if it did. */
    private fun sourceOf(verb: MemoryWorkItem, noun: MemoryWorkItem): Map<String, String> {
        val keys = verb.metadata[TAG_OBJECTS]?.split(TAG_LIST_SEPARATOR) ?: return emptyMap()
        val at = keys.indexOf(noun.metadata[TAG_KEY])
        if (at < 0) return emptyMap()
        return buildMap {
            verb.metadata[TAG_SPANS]?.split(TAG_LIST_SEPARATOR)?.getOrNull(at)?.let { put(PHRASE_SPAN, it) }
            verb.metadata[TAG_SENTENCES]?.split(TAG_LIST_SEPARATOR)?.getOrNull(at)?.let { put(PHRASE_SENTENCE, it) }
        }
    }

    private companion object {
        const val MAX_NOUNS_PER_VERB = 2
    }
}

// ---------------------------------------------------------------------------------------------
// ---------------------------------------------------------------------------------------------
// Summaries: whole source sentences picked by SumBasic, trimmed by deletion only (MemorySynthesis.kt).
// Phrases from a model-backed tagger carry no source sentence and are listed as before.
// ---------------------------------------------------------------------------------------------

private class ProgrammaticSummarySynthesizer(now: () -> Long) :
    ProgrammaticClerk(MemoryMicroAgentRole.SummarySynthesizer, now) {
    override suspend fun process(packet: MemoryWorkPacket): MemoryMutationBatch {
        val groups = packet.items.groupBy { it.sourceSectionIds().minByOrNull(MemorySectionId::value)?.value.orEmpty() }
        var budget = model.maxMutations
        val createdAt = nowEpochMillis()
        val namespace = packet.namespace()
        val nodes = mutableListOf<MemoryNode>()
        val edges = mutableListOf<MemoryEdge>()
        groups.values.forEach { phrases ->
            val distinct = phrases.distinctBy { it.text.memorySemanticKey() }
            val (text, used, method) = extractive(distinct) ?: listed(distinct) ?: return@forEach
            if (used.isEmpty() || budget < 1 + used.size) return@forEach
            budget -= 1 + used.size
            val index = nodes.size
            val id = MemoryNodeId("$namespace:node:$index")
            nodes += MemoryNode(
                id = id,
                kind = MemoryNodeKind.Summary,
                text = text,
                sourceEpisodeIds = used.flatMapTo(linkedSetOf(packet.episodeId)) { it.sourceEpisodeIds() },
                sourceSectionIds = used.flatMapTo(linkedSetOf()) { it.sourceSectionIds() },
                salience = used.maxOf { it.salience() },
                confidence = 0.7f,
                createdAtEpochMillis = createdAt,
                metadata = metadata(mapOf("summaryMethod" to method)),
            )
            used.forEachIndexed { part, phrase ->
                edges += MemoryEdge(
                    id = MemoryEdgeId("$namespace:edge:$index:$part"),
                    from = id,
                    to = MemoryNodeId(phrase.id),
                    relation = MemoryRelationKind.Summarizes,
                    createdAtEpochMillis = createdAt,
                    metadata = metadata(),
                )
            }
        }
        return MemoryMutationBatch(nodesToAdd = nodes, edgesToAdd = edges)
    }

    /** Source sentences behind the phrases, chosen extractively; the phrases they cover are used. */
    private fun extractive(phrases: List<MemoryWorkItem>): Triple<String, List<MemoryWorkItem>, String>? {
        val bySentence = phrases.mapNotNull { phrase -> phrase.metadata[PHRASE_SENTENCE]?.takeIf(String::isNotBlank)?.let { it to phrase } }
        if (bySentence.isEmpty()) return null
        val support = bySentence.groupingBy { it.first }.eachCount()
        val extract = MemoryExtractiveSummary.summarize(bySentence.map { it.first }.distinct(), support) ?: return null
        val used = bySentence.filter { it.first in extract.sentences }.map { it.second }.distinctBy { it.id }
        return Triple(extract.text, used, "extractive")
    }

    /** The phrases of one section, listed: the fallback when no source sentence is known. */
    private fun listed(phrases: List<MemoryWorkItem>): Triple<String, List<MemoryWorkItem>, String>? {
        var text = ""
        val used = mutableListOf<MemoryWorkItem>()
        phrases.forEach { phrase ->
            val next = if (text.isEmpty()) phrase.text.trim() else "$text; ${phrase.text.trim()}"
            if (next.length <= MemoryExtractiveSummary.MAX_CHARS || used.isEmpty()) {
                text = next
                used += phrase
            }
        }
        if (used.isEmpty()) return null
        return Triple(text.replaceFirstChar(Char::uppercaseChar) + ".", used, "phrases")
    }
}


// ---------------------------------------------------------------------------------------------
// Categories: a fixed, inspectable taxonomy scored by a weighted lexicon (MemorySynthesis.kt); every
// label records the terms that earned it.
// ---------------------------------------------------------------------------------------------

private class ProgrammaticCategoryClassifier(now: () -> Long) :
    ProgrammaticClerk(MemoryMicroAgentRole.CategoryClassifier, now) {
    override suspend fun process(packet: MemoryWorkPacket): MemoryMutationBatch {
        val lexicon = MemoryCategoryLexicon(runCatching { MemoryLanguageResources.get().wordNet }.getOrNull())
        val members = linkedMapOf<String, MutableList<Pair<MemoryWorkItem, MemoryCategoryHit>>>()
        packet.items.forEach { summary ->
            lexicon.classify(summary.text).forEach { hit ->
                members.getOrPut(hit.category) { mutableListOf() } += summary to hit
            }
        }
        var budget = model.maxMutations
        val createdAt = nowEpochMillis()
        val namespace = packet.namespace()
        val nodes = mutableListOf<MemoryNode>()
        val edges = mutableListOf<MemoryEdge>()
        members.entries.sortedBy { MemoryCategoryLexicon.CATEGORY_ORDER.indexOf(it.key) }.forEach { (category, hits) ->
            if (budget < 1 + hits.size) return@forEach
            budget -= 1 + hits.size
            val summaries = hits.map { it.first }
            val id = MemoryNodeId("$namespace:node:$category")
            nodes += MemoryNode(
                id = id,
                kind = MemoryNodeKind.Category,
                text = category,
                sourceEpisodeIds = summaries.flatMapTo(linkedSetOf(packet.episodeId)) { it.sourceEpisodeIds() },
                sourceSectionIds = summaries.flatMapTo(linkedSetOf()) { it.sourceSectionIds() },
                salience = summaries.maxOf { it.salience() },
                confidence = 0.8f,
                createdAtEpochMillis = createdAt,
                metadata = metadata(mapOf("taxonomy" to "builtin")),
            )
            hits.forEachIndexed { part, (summary, hit) ->
                edges += MemoryEdge(
                    id = MemoryEdgeId("$namespace:edge:$category:$part"),
                    from = id,
                    to = MemoryNodeId(summary.id),
                    relation = MemoryRelationKind.Categorizes,
                    weight = minOf(1.0, hit.score / (MemoryCategoryLexicon.MIN_SCORE * 3)).toFloat(),
                    createdAtEpochMillis = createdAt,
                    metadata = metadata(mapOf("score" to hit.score.toString(), "evidence" to hit.evidence.joinToString(", "))),
                )
            }
        }
        return MemoryMutationBatch(nodesToAdd = nodes, edgesToAdd = edges)
    }
}


/** The built-in taxonomy, in display order. */
val MEMORY_CATEGORY_TAXONOMY: Map<String, Regex> = linkedMapOf(
    "build" to """\b(build|builds|built|gradle|compile\w*|assemble\w*|cargo|webpack|vite|maven)\b""",
    "testing" to """\b(tests?|tested|testing|assert\w*|junit|pytest|spec|verif\w+)\b""",
    "source control" to """\b(git|commits?|merge\w*|rebase\w*|branch\w*|pull request|pr|push\w*)\b""",
    "ci" to """\b(ci|workflows?|github actions|pipelines?|check runs?)\b""",
    "dependencies" to """\b(dependenc\w+|upgrade\w*|bump\w*|packages?|versions?)\b""",
    "ui" to """\b(ui|screens?|compose|layouts?|buttons?|views?|css|html|theme\w*)\b""",
    "api" to """\b(api|apis|endpoints?|http|requests?|responses?|rest|graphql)\b""",
    "data" to """\b(database|sql\w*|postgres\w*|schema\w*|migrat\w+|persist\w*|serializ\w+|json)\b""",
    "security" to """\b(security|auth\w*|tokens?|secrets?|keys?|permissions?|credentials?|encrypt\w*|sign\w*)\b""",
    "performance" to """\b(performance|latency|slow\w*|optimi[sz]\w*|cach\w+|speed)\b""",
    "configuration" to """\b(config\w*|settings?|env|environment|flags?|propert\w+)\b""",
    "deployment" to """\b(deploy\w*|releas\w+|publish\w*|ship\w*)\b""",
    "documentation" to """\b(docs?|documentation|readme|markdown)\b""",
    "models" to """\b(models?|onnx|lora|quantiz\w+|inference|embedding\w*|llm\w*)\b""",
    "errors" to """\b(errors?|exceptions?|crash\w*|fail\w*|bugs?|fix\w*)\b""",
).mapValues { (_, pattern) -> Regex(pattern, RegexOption.IGNORE_CASE) }

internal fun memoryCategoriesOf(text: String): List<String> =
    MEMORY_CATEGORY_TAXONOMY.filterValues { it.containsMatchIn(text) }.keys.toList()

/**
 * The taxonomy as the CategoryClassifier's instruction shows it, one category per line with its
 * keywords (`*` = any ending). A clerk then matches words it can see instead of recalling the list.
 */
internal fun memoryCategoryGuide(): String = MEMORY_CATEGORY_TAXONOMY.entries.joinToString("\n") { (category, regex) ->
    val keywords = regex.pattern.removePrefix("\\b(").removeSuffix(")\\b")
        .replace("\\w*", "*").replace("\\w+", "*").replace("s?", "(s)").replace("[sz]", "z")
        .split('|')
    "$category: ${keywords.joinToString(", ")}"
}

// ---------------------------------------------------------------------------------------------
// ---------------------------------------------------------------------------------------------
// Associations: tags link by shared sense, synonym or spelling; longer text by tf-idf and
// character trigrams (MemorySynthesis.kt). Same kind only.
// ---------------------------------------------------------------------------------------------

private class ProgrammaticAssociationLinker(
    now: () -> Long,
) : ProgrammaticClerk(MemoryMicroAgentRole.AssociationLinker, now) {
    override suspend fun process(packet: MemoryWorkPacket): MemoryMutationBatch {
        val visible = (packet.items + packet.neighborhood).distinctBy(MemoryWorkItem::id)
        if (visible.size < 2) return MemoryMutationBatch()
        val idf = MemorySalienceFeatures.idf(visible.map { it.text })
        // Large text neighbourhoods compare only MinHash/LSH candidate pairs; small ones compare all.
        val textItems = visible.filter { it.kind !in TAG_KINDS }
        val textCandidates = if (textItems.size > MemoryMinHash.ALL_PAIRS_UP_TO) MemoryMinHash.candidates(textItems.associate { it.id to it.text }) else null
        val emitted = hashSetOf<String>()
        val createdAt = nowEpochMillis()
        val edges = mutableListOf<MemoryEdge>()
        packet.items.forEach { source ->
            val tag = source.kind in TAG_KINDS
            val (threshold, limit) = if (tag) TAG_THRESHOLD to TAG_LINKS else TEXT_THRESHOLD to TEXT_LINKS
            visible.asSequence()
                .filter { it.id != source.id && it.kind == source.kind }
                .filter { tag || textCandidates == null || it.id in textCandidates[source.id].orEmpty() }
                .map { target -> target to if (tag) MemorySimilarity.tags(source, target) else MemorySimilarity.text(source.text, target.text, idf) }
                .filter { (_, similarity) -> similarity >= threshold }
                .sortedByDescending { it.second }
                .take(limit)
                .forEach { (target, similarity) ->
                    val (from, to) = if (source.id <= target.id) source.id to target.id else target.id to source.id
                    val pair = "$from\u0000$to"
                    if (!emitted.add(pair) || edges.size >= model.maxMutations) return@forEach
                    edges += MemoryEdge(
                        id = MemoryEdgeId("${packet.queueId.value}:${packet.packetKey}:association:programmatic:$from|$to"),
                        from = MemoryNodeId(from),
                        to = MemoryNodeId(to),
                        relation = MemoryRelationKind.SimilarTo,
                        weight = similarity,
                        createdAtEpochMillis = createdAt,
                        metadata = metadata(mapOf("similarity" to if (tag) "tag-sense-synonym-spelling" else "tfidf-trigram")),
                    )
                }
        }
        return MemoryMutationBatch(edgesToAdd = edges)
    }

    private companion object {
        val TAG_KINDS = setOf("node:${MemoryNodeKind.NounTag.name}", "node:${MemoryNodeKind.VerbTag.name}")
        const val TAG_THRESHOLD = 0.75f
        const val TAG_LINKS = 4
        const val TEXT_THRESHOLD = 0.8f
        const val TEXT_LINKS = 6
    }
}


internal fun String.trigramVector(): Map<Int, Float> {
    val text = " ${lowercase().replace(Regex("\\s+"), " ").trim()} "
    val counts = hashMapOf<Int, Float>()
    for (i in 0..text.length - 3) {
        val key = text.substring(i, i + 3).hashCode()
        counts[key] = (counts[key] ?: 0f) + 1f
    }
    return counts
}

internal fun cosine(left: Map<Int, Float>, right: Map<Int, Float>): Float {
    if (left.isEmpty() || right.isEmpty()) return 0f
    val (small, large) = if (left.size <= right.size) left to right else right to left
    var dot = 0.0
    small.forEach { (key, value) -> large[key]?.let { dot += value * it } }
    val norm = sqrt(left.values.sumOf { (it * it).toDouble() }) * sqrt(right.values.sumOf { (it * it).toDouble() })
    return if (norm == 0.0) 0f else (dot / norm).toFloat().coerceIn(0f, 1f)
}

// ---------------------------------------------------------------------------------------------
// ---------------------------------------------------------------------------------------------
// Condensation: the most representative member plus sentences only other members contain
// (MemorySynthesis.kt); decline when members differ in values.
// ---------------------------------------------------------------------------------------------

private class ProgrammaticCondensationRewriter(now: () -> Long) :
    ProgrammaticClerk(MemoryMicroAgentRole.CondensationRewriter, now) {
    override suspend fun process(packet: MemoryWorkPacket): MemoryMutationBatch {
        val items = packet.items
        if (items.size < 2 || condensationWouldAdjudicate(items.map { it.text })) return MemoryMutationBatch()
        val kind = items.first().kind.removePrefix("node:").let { runCatching { MemoryNodeKind.valueOf(it) }.getOrNull() }
            ?: return MemoryMutationBatch()
        val condensed = MemoryCondensation.condense(items)
        val createdAt = nowEpochMillis()
        val namespace = packet.namespace()
        val id = MemoryNodeId("$namespace:node:condensed")
        val node = MemoryNode(
            id = id,
            kind = kind,
            text = condensed.text,
            sourceEpisodeIds = items.flatMapTo(linkedSetOf()) { it.sourceEpisodeIds() },
            sourceSectionIds = items.flatMapTo(linkedSetOf()) { it.sourceSectionIds() },
            salience = items.maxOf { it.salience() },
            confidence = items.minOf { it.metadata["confidence"]?.toFloatOrNull() ?: 1f },
            createdAtEpochMillis = createdAt,
            metadata = metadata(
                buildMap {
                    put("representative", items[condensed.representative].id)
                    if (condensed.appendedFrom.isNotEmpty()) put("appendedFrom", condensed.appendedFrom.joinToString(",") { items[it].id })
                },
            ),
        )
        // CondensedFrom only: whether a source is covered (and so superseded) is the engine's call.
        val edges = items.mapIndexed { index, item ->
            MemoryEdge(
                id = MemoryEdgeId("$namespace:edge:$index:${MemoryRelationKind.CondensedFrom.name}"),
                from = id,
                to = MemoryNodeId(item.id),
                relation = MemoryRelationKind.CondensedFrom,
                createdAtEpochMillis = createdAt,
                metadata = metadata(),
            )
        }
        return MemoryMutationBatch(nodesToAdd = listOf(node), edgesToAdd = edges)
    }
}


/**
 * True when the members disagree on a value (a number, a quoted string, or negation present in some
 * but not all) or any two contrast: same frame, different filler ([MemoryContrast]). Choosing a
 * representative would then choose a claim, which clerks never do.
 */
internal fun condensationWouldAdjudicate(texts: List<String>): Boolean =
    texts.map(::memoryClaimSignature).distinct().size > 1 || MemoryContrast.anyContrast(texts)

/**
 * The values a memory's text asserts: its numbers and quoted strings, and whether it negates. Two
 * texts with different signatures make different claims, however similar they read.
 */
internal data class MemoryClaimSignature(val values: Set<String>, val negated: Boolean)

internal fun memoryClaimSignature(text: String): MemoryClaimSignature = MemoryClaimSignature(
    values = (NUMBER.findAll(text) + QUOTED.findAll(text)).map { it.value.lowercase() }.toSet(),
    negated = NEGATION.containsMatchIn(text),
)

// ---------------------------------------------------------------------------------------------

private fun MemoryWorkItem.sourceEpisodeIds(): List<MemoryEpisodeId> = metadata["sourceEpisodeIds"].orEmpty()
    .split(',').map(String::trim).filter(String::isNotEmpty).map(::MemoryEpisodeId)

private fun MemoryWorkItem.sourceSectionIds(): List<MemorySectionId> = metadata["sourceSectionIds"].orEmpty()
    .split(',').map(String::trim).filter(String::isNotEmpty).map(::MemorySectionId)

private fun MemoryWorkItem.salience(): Float = metadata["salience"]?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.5f

private fun String.idPart(): String = buildString {
    this@idPart.forEach { char ->
        when {
            char.isLetterOrDigit() -> append(char.lowercaseChar())
            char == '-' || char == '_' -> append(char)
            else -> append('-')
        }
    }
}.trim('-').take(48).ifBlank { "tag" }

@OptIn(ExperimentalTime::class)
private fun programmaticNowEpochMillis(): Long = Clock.System.now().toEpochMilliseconds()

private val WORD = Regex("[A-Za-z][A-Za-z0-9_'-]*")
private val PHRASE_BOUNDARY = Regex("[.,;:!?()\\[\\]{}\"`\\n]+")
private val NUMBER = Regex("\\b\\d+(?:[.,]\\d+)?\\b")
private val QUOTED = Regex("\"[^\"]{1,80}\"|'[^'\\s][^']{0,79}'")
private val NEGATION = Regex("\\b(not|never|no|none|cannot|can't|don't|doesn't|isn't|won't|without)\\b", RegexOption.IGNORE_CASE)
private val ACKNOWLEDGEMENT = Regex(
    "^(ok(ay)?|done|thanks?( you)?|sure|got it|yes|no|on it|working on it|let me (check|look|see)[^.]*|sounds good)[.!]*$",
)

private val STOPWORDS = setOf(
    "a", "an", "the", "of", "to", "in", "on", "at", "for", "with", "and", "or", "is", "are", "was", "were",
    "be", "been", "being", "by", "as", "it", "its", "that", "this", "these", "those", "from", "into", "than",
    "then", "so", "not", "no", "do", "does", "did", "has", "have", "had", "will", "would", "can", "could",
    "should", "may", "might", "must", "we", "you", "i", "they", "he", "she", "them", "our", "your", "their",
    "my", "me", "us", "if", "but", "also", "just", "only", "very", "any", "all", "each", "some", "more",
    "most", "other", "such", "about", "over", "under", "after", "before", "when", "where", "which", "who",
    "what", "why", "how", "there", "here", "now", "still", "yet", "via", "per", "out", "up", "down", "off",
)

private val GENERAL_VERBS = setOf(
    "add", "change", "move", "rename", "refactor", "configure", "install", "uninstall", "migrate", "implement",
    "replace", "investigate", "debug", "document", "review", "approve", "reject", "decide", "prefer", "choose",
    "fix", "fail", "break", "require", "use", "need", "enable", "disable", "open", "close", "start", "stop",
    "restart", "retry", "revert", "restore", "split", "combine", "extract", "inline", "export", "import",
    "measure", "benchmark", "profile", "train", "quantize", "download", "upload", "sign", "verify", "report",
)

private val GENERAL_VERB_FORMS = mapOf(
    "broke" to "break", "broken" to "break", "chose" to "choose", "chosen" to "choose",
    "made" to "make", "found" to "find", "kept" to "keep", "left" to "leave", "began" to "start",
)

private val DETACHMENT = listOf("ies" to "y", "ed" to "e", "ed" to "", "ing" to "e", "ing" to "", "es" to "", "s" to "")
