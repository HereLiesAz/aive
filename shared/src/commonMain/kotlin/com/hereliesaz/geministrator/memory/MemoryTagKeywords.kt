package com.hereliesaz.geministrator.memory

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * WordNet trigger keywords for noun and verb tags.
 *
 * When the engine stores a NounTag or VerbTag it attaches, once, the related words of every keyword
 * in the tag ([TAG_KEYWORDS], a compact `term:weight` list). Recall then maps an agent's words to tags
 * (and through their Indexes edges to memories) by an inverted-index lookup instead of reasoning
 * about meaning at query time. Noise is deliberate: weak relations carry low weights, and the
 * attention dial's similarity threshold decides how far down the weights a cue may reach.
 *
 * The relations are ordinary WordNet ones (synonymy, derivation, hypernymy, hyponymy, co-hyponymy)
 * read from the most frequent senses; nothing here is new, only the weighting table is ours.
 */
internal object MemoryTagKeywords {
    /** Relation weights; one table so they can be tuned together. */
    data class Weights(
        val synonym: Float = 1.0f,
        val derivation: Float = 0.9f,
        val hypernym: Float = 0.6f,
        val hypernym2: Float = 0.4f,
        val hyponym: Float = 0.5f,
        val sibling: Float = 0.3f,
        val alias: Float = 1.0f,
    )

    val WEIGHTS = Weights()

    /** Senses read per lemma and part of speech, most frequent first. */
    const val SENSES = 3

    /** Narrower synsets read per synset (index order), and siblings per parent. */
    const val MAX_CHILDREN = 24

    /** Terms kept per tag keyword list, highest weight first. */
    const val MAX_TERMS = 64

    /**
     * Relevance a keyword match contributes to recall for a keyword weight: `0.9 * sqrt(weight)`. A
     * synonym (1.0) scores 0.9, a little under an exact match; a sibling (0.3) scores about 0.49 and
     * so clears only a near-intrusive dial (the threshold runs from 0.92 focused to 0.45 intrusive).
     */
    fun relevance(weight: Float): Float = (KEYWORD_RELEVANCE * sqrt(weight.coerceIn(0f, 1f).toDouble())).toFloat()

    private const val KEYWORD_RELEVANCE = 0.9

    /**
     * The keywords of a tag's [text]: every keyword (and the whole phrase, when WordNet knows it) with
     * its related words, max weight per term, capped at [MAX_TERMS]. Without [wordNet], or for words
     * WordNet does not know, just the word and its [MemoryAliases] group. Deterministic: ties keep
     * discovery order (keyword order, then sense order).
     */
    fun keywordsFor(text: String, kind: MemoryNodeKind, wordNet: WordNetLexicon?, weights: Weights = WEIGHTS): List<Pair<String, Float>> {
        val words = text.lowercase().split(WORD_SPLIT).filter { it.length > 1 }
        val phrase = words.joinToString(" ")
        val keywords = (listOf(phrase) + words).filter(String::isNotEmpty).distinct()
        val primary = if (kind == MemoryNodeKind.VerbTag) WordNetLexicon.Pos.Verb else WordNetLexicon.Pos.Noun
        val other = if (primary == WordNetLexicon.Pos.Verb) WordNetLexicon.Pos.Noun else WordNetLexicon.Pos.Verb
        val out = LinkedHashMap<String, Float>()
        fun add(term: String, weight: Float) {
            val clean = term.lowercase().replace(':', ' ').replace('|', ' ').trim()
            if (clean.length < 2) return
            if (weight > (out[clean] ?: 0f)) out[clean] = weight
        }
        keywords.forEach { word ->
            val isPhrase = ' ' in word
            if (!isPhrase) {
                add(word, weights.alias)
                MemoryAliases.group(word).forEach { add(it, weights.alias) }
            }
            if (wordNet == null) return@forEach
            listOf(primary, other).forEach { pos -> wordNet.baseForms(word, pos).forEach { lemma -> relate(wordNet, lemma, pos, weights, ::add) } }
        }
        return out.entries.withIndex()
            .sortedWith(compareByDescending<IndexedValue<Map.Entry<String, Float>>> { it.value.value }.thenBy { it.index })
            .take(MAX_TERMS)
            .map { it.value.key to it.value.value }
    }

    private fun relate(wordNet: WordNetLexicon, lemma: String, pos: WordNetLexicon.Pos, weights: Weights, add: (String, Float) -> Unit) {
        val senses = wordNet.senses(lemma, pos).take(SENSES)
        senses.forEach { synset -> wordNet.lemmas(synset).forEach { add(it, weights.synonym) } }
        senses.forEach { synset ->
            wordNet.derivations(synset).forEach { related -> wordNet.lemmas(related).forEach { add(it, weights.derivation) } }
        }
        senses.forEach { synset ->
            wordNet.hypernyms(synset).forEach { parent ->
                wordNet.lemmas(parent).forEach { add(it, weights.hypernym) }
                wordNet.hypernyms(parent).forEach { grand -> wordNet.lemmas(grand).forEach { add(it, weights.hypernym2) } }
            }
        }
        senses.forEach { synset ->
            hyponyms(wordNet, synset).take(MAX_CHILDREN).forEach { child -> wordNet.lemmas(child).forEach { add(it, weights.hyponym) } }
        }
        senses.forEach { synset ->
            val lexname = wordNet.lexname(synset)
            wordNet.hypernyms(synset).forEach { parent ->
                hyponyms(wordNet, parent)
                    .filter { it != synset && wordNet.lexname(it) == lexname }
                    .take(MAX_CHILDREN)
                    .forEach { sibling -> wordNet.lemmas(sibling).forEach { add(it, weights.sibling) } }
            }
        }
    }

    /** Direct hyponyms of [synset], in synset index order, from a reverse hypernym index built once. */
    fun hyponyms(wordNet: WordNetLexicon, synset: Int): List<Int> {
        val index = reverseIndex(wordNet)
        return (index.offsets[synset] until index.offsets[synset + 1]).map { index.children[it] }
    }

    private class ReverseIndex(val lexicon: WordNetLexicon, val offsets: IntArray, val children: IntArray)

    private var reverse: ReverseIndex? = null

    private fun reverseIndex(wordNet: WordNetLexicon): ReverseIndex {
        reverse?.takeIf { it.lexicon === wordNet }?.let { return it }
        val count = wordNet.synsetCount
        val offsets = IntArray(count + 1)
        for (s in 0 until count) wordNet.hypernyms(s).forEach { offsets[it + 1]++ }
        for (i in 0 until count) offsets[i + 1] += offsets[i]
        val fill = offsets.copyOf()
        val children = IntArray(offsets[count])
        for (s in 0 until count) wordNet.hypernyms(s).forEach { children[fill[it]++] = s }
        return ReverseIndex(wordNet, offsets, children).also { reverse = it }
    }

    /** `term:weight|term:weight`, weights to two decimals. */
    fun encode(keywords: List<Pair<String, Float>>): String =
        keywords.joinToString(SEPARATOR) { (term, weight) -> "$term:${formatWeight(weight)}" }

    fun decode(value: String?): List<Pair<String, Float>> =
        value.orEmpty().split(SEPARATOR).mapNotNull { entry ->
            val at = entry.lastIndexOf(':')
            if (at <= 0) null else entry.substring(at + 1).toFloatOrNull()?.let { entry.substring(0, at) to it }
        }

    private fun formatWeight(weight: Float): String {
        val hundredths = (weight.coerceIn(0f, 1f) * 100).roundToInt()
        return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}"
    }

    /**
     * Adds keywords to every NounTag/VerbTag in [nodes] that has none yet. Engine-side, so tags from
     * any clerk engine get one; a tag carrying keywords already is left as written.
     */
    fun withKeywords(nodes: List<MemoryNode>, wordNet: WordNetLexicon?): List<MemoryNode> = nodes.map { node ->
        if ((node.kind != MemoryNodeKind.NounTag && node.kind != MemoryNodeKind.VerbTag) || TAG_KEYWORDS in node.metadata) {
            node
        } else {
            val keywords = keywordsFor(node.text, node.kind, wordNet)
            if (keywords.isEmpty()) node else node.copy(metadata = node.metadata + (TAG_KEYWORDS to encode(keywords)))
        }
    }

    private const val SEPARATOR = "|"
    private val WORD_SPLIT = Regex("[^\\p{L}\\p{N}_.+#-]+")
}

/** Tag node metadata: the tag's trigger keywords, `term:weight|…` ([MemoryTagKeywords]). */
internal const val TAG_KEYWORDS = "keywords"

/**
 * Inverted index over stored tag keywords: term -> tags (with keyword weight), plus each tag's indexed
 * memories. A memory's keywords are the union of its tags' keywords, max weight per term.
 */
internal class MemoryKeywordIndex(snapshot: MemorySnapshot, activeIds: Set<MemoryNodeId>) {
    private val postings = HashMap<String, MutableMap<MemoryNodeId, Float>>()
    private val indexed = HashMap<MemoryNodeId, MutableList<MemoryNodeId>>()

    init {
        snapshot.nodes.forEach { node ->
            if (node.id !in activeIds) return@forEach
            if (node.kind != MemoryNodeKind.NounTag && node.kind != MemoryNodeKind.VerbTag) return@forEach
            MemoryTagKeywords.decode(node.metadata[TAG_KEYWORDS]).forEach { (term, weight) ->
                val tags = postings.getOrPut(term) { HashMap() }
                if (weight > (tags[node.id] ?: 0f)) tags[node.id] = weight
            }
        }
        if (postings.isNotEmpty()) {
            snapshot.edges.forEach { edge ->
                if (edge.relation == MemoryRelationKind.Indexes && edge.to in activeIds) indexed.getOrPut(edge.from) { mutableListOf() } += edge.to
            }
        }
    }

    val isEmpty: Boolean get() = postings.isEmpty()

    /** Memories (tags and what they index) whose keywords hold [term]; the frequency filter reads it. */
    fun memoryCount(term: String): Int {
        val tags = postings[term] ?: return 0
        return tags.keys.flatMapTo(HashSet()) { listOf(it) + indexed[it].orEmpty() }.size
    }

    /**
     * Best keyword weight per tag and per indexed memory for [terms] (each also tried in its WordNet
     * base forms when WordNet is loaded).
     */
    fun match(terms: Collection<String>, wordNet: WordNetLexicon?): Map<MemoryNodeId, Float> {
        if (postings.isEmpty()) return emptyMap()
        val out = HashMap<MemoryNodeId, Float>()
        terms.flatMap { term -> listOf(term) + forms(term, wordNet) }.distinct().forEach { term ->
            postings[term]?.forEach { (tag, weight) ->
                if (weight > (out[tag] ?: 0f)) out[tag] = weight
                indexed[tag]?.forEach { memory -> if (weight > (out[memory] ?: 0f)) out[memory] = weight }
            }
        }
        return out
    }

    private fun forms(term: String, wordNet: WordNetLexicon?): List<String> =
        if (wordNet == null || ' ' in term) emptyList()
        else wordNet.baseForms(term, WordNetLexicon.Pos.Noun) + wordNet.baseForms(term, WordNetLexicon.Pos.Verb)
}

/**
 * The cue trigger table of one memory layer, held ready: keyword (a word or a 2–3 word phrase,
 * lowercased) -> the tags it fires, with the keyword's weight. Each tag's own text is a keyword at
 * weight 1.0. Built from the stored keyword lists on first use, then updated the instant a tag is
 * committed through [KeywordTriggeringStore]; a store revision it did not see (a write that bypassed
 * the layer, a replace) rebuilds it. Keywords are never memories: the table only maps chat words to
 * the real tag, and what is delivered is always the tag.
 */
internal class MemoryKeywordTriggers {
    data class Trigger(val tag: MemoryNode, val weight: Float)

    private val mutex = Mutex()
    private val table = HashMap<String, MutableMap<MemoryNodeId, Trigger>>()
    /** Store revision the table reflects; null until built or after a replace. */
    private var revision: Long? = null

    /** Builds the table from [snapshot] unless it already reflects that revision. */
    suspend fun ensureCurrent(snapshot: MemorySnapshot) = mutex.withLock {
        if (revision == snapshot.revision) return@withLock
        table.clear()
        val superseded = snapshot.edges.filter { it.relation == MemoryRelationKind.Supersedes }.mapTo(HashSet()) { it.to }
        snapshot.nodes.forEach { if (it.id !in superseded) addLocked(it) }
        revision = snapshot.revision
    }

    /** A successful commit at [expectedRevision]: its new tags join the table at once. */
    suspend fun committed(expectedRevision: Long, nodes: List<MemoryNode>) = mutex.withLock {
        if (revision != expectedRevision) {
            revision = null
            return@withLock
        }
        nodes.forEach(::addLocked)
        revision = expectedRevision + 1
    }

    suspend fun invalidate() = mutex.withLock { revision = null }

    /** Best trigger per tag over [keys]; O(1) per key. */
    suspend fun match(keys: Collection<String>): Map<MemoryNodeId, Trigger> = mutex.withLock {
        val out = LinkedHashMap<MemoryNodeId, Trigger>()
        keys.forEach { key ->
            table[key]?.forEach { (id, trigger) -> if (trigger.weight > (out[id]?.weight ?: 0f)) out[id] = trigger }
        }
        out
    }

    suspend fun keywords(): Set<String> = mutex.withLock { table.keys.toSet() }

    private fun addLocked(node: MemoryNode) {
        if (node.kind != MemoryNodeKind.NounTag && node.kind != MemoryNodeKind.VerbTag) return
        val own = node.text.trim().lowercase()
        (listOf(own to 1.0f) + MemoryTagKeywords.decode(node.metadata[TAG_KEYWORDS])).forEach { (keyword, weight) ->
            if (keyword.isEmpty()) return@forEach
            val tags = table.getOrPut(keyword) { HashMap() }
            if (weight > (tags[node.id]?.weight ?: 0f)) tags[node.id] = Trigger(node, weight)
        }
    }

    companion object {
        /**
         * Lookup keys for [text]: its words (minus [dropWord], the stopword/common-word filter) and
         * every adjacent 2- and 3-word phrase, as-is.
         */
        inline fun keys(text: String, dropWord: (String) -> Boolean): List<String> {
            val tokens = text.lowercase().split(Regex("[^\\p{L}\\p{N}_-]+")).filter(String::isNotEmpty)
            val phrases = (2..3).flatMap { n -> tokens.windowed(n).map { it.joinToString(" ") } }
            return (tokens.filterNot(dropWord) + phrases).distinct()
        }
    }
}

/** The layer's store, telling [triggers] of every tag it commits. */
internal class KeywordTriggeringStore(private val inner: MemoryStore, private val triggers: MemoryKeywordTriggers) : MemoryStore {
    override suspend fun read(): MemorySnapshot = inner.read()

    override suspend fun commit(expectedRevision: Long, mutation: MemoryStoreMutation): Boolean =
        inner.commit(expectedRevision, mutation).also { ok -> if (ok) triggers.committed(expectedRevision, mutation.nodesToAdd) }

    override suspend fun replace(snapshot: MemorySnapshot) {
        inner.replace(snapshot)
        triggers.invalidate()
    }
}
