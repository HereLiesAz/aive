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
// Sectioning: structural split (code fences whole, headings attached, paragraphs packed).
// ---------------------------------------------------------------------------------------------

private class ProgrammaticSectioner(now: () -> Long) : ProgrammaticClerk(MemoryMicroAgentRole.Sectioner, now) {
    override suspend fun process(packet: MemoryWorkPacket): MemoryMutationBatch {
        val pieces = packet.items.flatMap { item ->
            item.text.structuralBlocks().map { block -> item to block }
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

/** Paragraphs and fenced code blocks; a heading line joins the block after it; long prose is split by sentence. */
internal fun String.structuralBlocks(maxChars: Int = 1_200): List<String> {
    val blocks = mutableListOf<String>()
    val current = StringBuilder()
    var inFence = false
    var pendingHeading: String? = null

    fun flush() {
        val text = current.toString().trim()
        current.clear()
        if (text.isEmpty()) return
        val withHeading = pendingHeading?.let { "$it\n$text" } ?: text
        pendingHeading = null
        blocks += withHeading
    }

    lines().forEach { line ->
        val trimmed = line.trim()
        when {
            trimmed.startsWith("```") || trimmed.startsWith("~~~") -> {
                if (!inFence) flush()
                current.appendLine(line)
                inFence = !inFence
                if (!inFence) flush()
            }
            inFence -> current.appendLine(line)
            trimmed.isEmpty() -> flush()
            trimmed.startsWith("#") && trimmed.trimStart('#').startsWith(" ") -> {
                flush()
                pendingHeading = pendingHeading?.let { "$it\n$trimmed" } ?: trimmed
            }
            else -> current.appendLine(line)
        }
    }
    flush()
    pendingHeading?.let { blocks += it }

    return blocks.flatMap { block ->
        if (block.length <= maxChars || block.startsWith("```") || block.startsWith("~~~")) {
            listOf(block)
        } else {
            block.splitBySentence(maxChars)
        }
    }
}

private fun String.splitBySentence(maxChars: Int): List<String> {
    val sentences = SENTENCE_END.split(this).map(String::trim).filter(String::isNotEmpty)
    val out = mutableListOf<String>()
    val current = StringBuilder()
    sentences.forEach { sentence ->
        if (current.isNotEmpty() && current.length + sentence.length + 1 > maxChars) {
            out += current.toString()
            current.clear()
        }
        if (sentence.length > maxChars) {
            sentence.chunked(maxChars).forEach { out += it }
        } else {
            if (current.isNotEmpty()) current.append(' ')
            current.append(sentence)
        }
    }
    if (current.isNotEmpty()) out += current.toString()
    return out
}

// ---------------------------------------------------------------------------------------------
// Salience: keep everything except bookkeeping noise and duplicates; score what is kept.
// ---------------------------------------------------------------------------------------------

private class ProgrammaticSalienceFilter(now: () -> Long) :
    ProgrammaticClerk(MemoryMicroAgentRole.SalienceFilter, now) {
    override suspend fun process(packet: MemoryWorkPacket): MemoryMutationBatch {
        val seen = hashSetOf<String>()
        val createdAt = nowEpochMillis()
        val namespace = packet.namespace()
        val nodes = packet.items.mapIndexedNotNull { index, section ->
            val text = section.text.trim()
            if (text.isNoise() || !seen.add(text.memorySemanticKey())) return@mapIndexedNotNull null
            MemoryNode(
                id = MemoryNodeId("$namespace:node:$index"),
                kind = MemoryNodeKind.Context,
                text = text,
                sourceEpisodeIds = setOf(packet.episodeId),
                sourceSectionIds = setOf(MemorySectionId(section.id)),
                salience = salience(text, section.metadata["sourceKind"]),
                confidence = 1f,
                createdAtEpochMillis = createdAt,
                metadata = metadata(),
            )
        }.take(model.maxMutations)
        return MemoryMutationBatch(nodesToAdd = nodes)
    }

    private fun salience(text: String, sourceKind: String?): Float {
        var score = when (sourceKind) {
            MemorySourceKind.AgentNote.name -> 0.8f
            MemorySourceKind.UserPrompt.name, MemorySourceKind.Objective.name -> 0.75f
            MemorySourceKind.Failure.name -> 0.7f
            MemorySourceKind.Plan.name -> 0.6f
            else -> 0.5f
        }
        if (text.looksStronglyTechnical()) score += 0.1f
        if (DECISION.containsMatchIn(text)) score += 0.15f
        if (ERROR.containsMatchIn(text)) score += 0.1f
        return score.coerceIn(0f, 1f)
    }
}

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

    override suspend fun process(packet: MemoryWorkPacket): MemoryMutationBatch {
        // tag -> items in which it occurs, in first-seen order
        val occurrences = linkedMapOf<String, LinkedHashSet<MemoryWorkItem>>()
        val display = hashMapOf<String, String>()
        packet.items.forEach { item ->
            val tags = if (nodeKind == MemoryNodeKind.NounTag) item.text.nounTags() else item.text.verbTags()
            tags.forEach { tag ->
                val key = tag.memorySemanticKey()
                display.getOrPut(key) { tag }
                occurrences.getOrPut(key) { linkedSetOf() } += item
            }
        }
        // The Tags stage runs noun and verb clerks together; each gets half the shared budget.
        var budget = model.maxMutations / 2
        val createdAt = nowEpochMillis()
        val namespace = packet.namespace()
        val nodes = mutableListOf<MemoryNode>()
        val edges = mutableListOf<MemoryEdge>()
        occurrences.entries
            .sortedByDescending { it.value.size }
            .forEachIndexed { index, (key, items) ->
                val cost = 1 + items.size
                if (cost > budget) return@forEachIndexed
                budget -= cost
                val nodeId = MemoryNodeId("$namespace:node:$index:${key.idPart()}")
                nodes += MemoryNode(
                    id = nodeId,
                    kind = nodeKind,
                    text = display.getValue(key),
                    sourceEpisodeIds = items.flatMapTo(linkedSetOf(packet.episodeId)) { it.sourceEpisodeIds() },
                    sourceSectionIds = items.flatMapTo(linkedSetOf()) { it.sourceSectionIds() },
                    salience = items.maxOf { it.metadata["salience"]?.toFloatOrNull() ?: 0.5f },
                    confidence = 0.8f,
                    createdAtEpochMillis = createdAt,
                    metadata = metadata(),
                )
                items.forEachIndexed { itemIndex, item ->
                    edges += MemoryEdge(
                        id = MemoryEdgeId("$namespace:edge:$index:$itemIndex"),
                        from = nodeId,
                        to = MemoryNodeId(item.id),
                        relation = MemoryRelationKind.Indexes,
                        createdAtEpochMillis = createdAt,
                        metadata = metadata(),
                    )
                }
            }
        return MemoryMutationBatch(nodesToAdd = nodes, edgesToAdd = edges)
    }
}

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
            verbs.forEach verb@{ verb ->
                nouns.take(MAX_NOUNS_PER_VERB).forEach noun@{ noun ->
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
                        metadata = metadata(),
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

    private companion object {
        const val MAX_NOUNS_PER_VERB = 2
    }
}

// ---------------------------------------------------------------------------------------------
// Summaries: the phrases of one section, listed. Nothing added.
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
            var text = ""
            val used = mutableListOf<MemoryWorkItem>()
            distinct.forEach { phrase ->
                val next = if (text.isEmpty()) phrase.text.trim() else "$text; ${phrase.text.trim()}"
                if (next.length <= MAX_SUMMARY_CHARS || used.isEmpty()) {
                    text = next
                    used += phrase
                }
            }
            if (used.isEmpty() || budget < 1 + used.size) return@forEach
            budget -= 1 + used.size
            val index = nodes.size
            val id = MemoryNodeId("$namespace:node:$index")
            nodes += MemoryNode(
                id = id,
                kind = MemoryNodeKind.Summary,
                text = text.replaceFirstChar(Char::uppercaseChar) + ".",
                sourceEpisodeIds = used.flatMapTo(linkedSetOf(packet.episodeId)) { it.sourceEpisodeIds() },
                sourceSectionIds = used.flatMapTo(linkedSetOf()) { it.sourceSectionIds() },
                salience = used.maxOf { it.salience() },
                confidence = 0.7f,
                createdAtEpochMillis = createdAt,
                metadata = metadata(),
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

    private companion object {
        const val MAX_SUMMARY_CHARS = 280
    }
}

// ---------------------------------------------------------------------------------------------
// Categories: a fixed, inspectable taxonomy.
// ---------------------------------------------------------------------------------------------

private class ProgrammaticCategoryClassifier(now: () -> Long) :
    ProgrammaticClerk(MemoryMicroAgentRole.CategoryClassifier, now) {
    override suspend fun process(packet: MemoryWorkPacket): MemoryMutationBatch {
        val members = linkedMapOf<String, MutableList<MemoryWorkItem>>()
        packet.items.forEach { summary ->
            memoryCategoriesOf(summary.text).forEach { category ->
                members.getOrPut(category) { mutableListOf() } += summary
            }
        }
        var budget = model.maxMutations
        val createdAt = nowEpochMillis()
        val namespace = packet.namespace()
        val nodes = mutableListOf<MemoryNode>()
        val edges = mutableListOf<MemoryEdge>()
        members.forEach { (category, summaries) ->
            if (budget < 1 + summaries.size) return@forEach
            budget -= 1 + summaries.size
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
            summaries.forEachIndexed { part, summary ->
                edges += MemoryEdge(
                    id = MemoryEdgeId("$namespace:edge:$category:$part"),
                    from = id,
                    to = MemoryNodeId(summary.id),
                    relation = MemoryRelationKind.Categorizes,
                    createdAtEpochMillis = createdAt,
                    metadata = metadata(),
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
// Associations: near-duplicate detection with hashed character trigrams (same kind only).
// ---------------------------------------------------------------------------------------------

private class ProgrammaticAssociationLinker(
    now: () -> Long,
    private val minimumSimilarity: Float = 0.82f,
    private val maxLinksPerItem: Int = 6,
) : ProgrammaticClerk(MemoryMicroAgentRole.AssociationLinker, now) {
    override suspend fun process(packet: MemoryWorkPacket): MemoryMutationBatch {
        val visible = (packet.items + packet.neighborhood).distinctBy(MemoryWorkItem::id)
        if (visible.size < 2) return MemoryMutationBatch()
        val vectors = visible.associate { it.id to it.text.trigramVector() }
        val emitted = hashSetOf<String>()
        val createdAt = nowEpochMillis()
        val edges = mutableListOf<MemoryEdge>()
        packet.items.forEach { source ->
            visible.asSequence()
                .filter { it.id != source.id && it.kind == source.kind }
                .map { target -> target to cosine(vectors.getValue(source.id), vectors.getValue(target.id)) }
                .filter { (_, similarity) -> similarity >= minimumSimilarity }
                .sortedByDescending { it.second }
                .take(maxLinksPerItem)
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
                        metadata = metadata(mapOf("similarity" to "char-trigram-cosine")),
                    )
                }
        }
        return MemoryMutationBatch(edgesToAdd = edges)
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
// Condensation: keep the most representative member; decline when members differ in values.
// ---------------------------------------------------------------------------------------------

private class ProgrammaticCondensationRewriter(now: () -> Long) :
    ProgrammaticClerk(MemoryMicroAgentRole.CondensationRewriter, now) {
    override suspend fun process(packet: MemoryWorkPacket): MemoryMutationBatch {
        val items = packet.items
        if (items.size < 2 || condensationWouldAdjudicate(items.map { it.text })) return MemoryMutationBatch()
        val kind = items.first().kind.removePrefix("node:").let { runCatching { MemoryNodeKind.valueOf(it) }.getOrNull() }
            ?: return MemoryMutationBatch()
        val vectors = items.map { it.text.trigramVector() }
        val medoid = items.indices.maxBy { i -> items.indices.sumOf { j -> cosine(vectors[i], vectors[j]).toDouble() } }
        val createdAt = nowEpochMillis()
        val namespace = packet.namespace()
        val id = MemoryNodeId("$namespace:node:condensed")
        val node = MemoryNode(
            id = id,
            kind = kind,
            text = items[medoid].text,
            sourceEpisodeIds = items.flatMapTo(linkedSetOf()) { it.sourceEpisodeIds() },
            sourceSectionIds = items.flatMapTo(linkedSetOf()) { it.sourceSectionIds() },
            salience = items.maxOf { it.salience() },
            confidence = items.minOf { it.metadata["confidence"]?.toFloatOrNull() ?: 1f },
            createdAtEpochMillis = createdAt,
            metadata = metadata(mapOf("representative" to items[medoid].id)),
        )
        val edges = items.flatMapIndexed { index, item ->
            listOf(MemoryRelationKind.CondensedFrom, MemoryRelationKind.Supersedes).map { relation ->
                MemoryEdge(
                    id = MemoryEdgeId("$namespace:edge:$index:${relation.name}"),
                    from = id,
                    to = MemoryNodeId(item.id),
                    relation = relation,
                    createdAtEpochMillis = createdAt,
                    metadata = metadata(),
                )
            }
        }
        return MemoryMutationBatch(nodesToAdd = listOf(node), edgesToAdd = edges)
    }
}

/**
 * True when the members disagree on a value: a number, a quoted string, or negation present in some
 * but not all. Choosing a representative would then choose a claim, which clerks never do.
 */
internal fun condensationWouldAdjudicate(texts: List<String>): Boolean =
    texts.map(::memoryClaimSignature).distinct().size > 1

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

private val SENTENCE_END = Regex("(?<=[.!?])\\s+")
private val WORD = Regex("[A-Za-z][A-Za-z0-9_'-]*")
private val PHRASE_BOUNDARY = Regex("[.,;:!?()\\[\\]{}\"`\\n]+")
private val NUMBER = Regex("\\b\\d+(?:[.,]\\d+)?\\b")
private val QUOTED = Regex("\"[^\"]{1,80}\"|'[^'\\s][^']{0,79}'")
private val NEGATION = Regex("\\b(not|never|no|none|cannot|can't|don't|doesn't|isn't|won't|without)\\b", RegexOption.IGNORE_CASE)
private val DECISION = Regex(
    "\\b(decid\\w*|chose|choose|prefer\\w*|must|never|always|should|instead|because|agreed|requirement)\\b",
    RegexOption.IGNORE_CASE,
)
private val ERROR = Regex("\\b(error|exception|fail\\w*|crash\\w*|bug)\\b", RegexOption.IGNORE_CASE)
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
