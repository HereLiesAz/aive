package com.hereliesaz.geministrator.memory

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * WordNet related-word clouds for noun and verb tags.
 *
 * When the engine stores a NounTag or VerbTag it attaches, once, the related words of every keyword
 * in the tag ([TAG_CLOUD], a compact `term:weight` list). Recall then maps an agent's words to tags
 * (and through their Indexes edges to memories) by an inverted-index lookup instead of reasoning
 * about meaning at query time. Noise is deliberate: weak relations carry low weights, and the
 * attention dial's similarity threshold decides how far down the weights a cue may reach.
 *
 * The relations are ordinary WordNet ones (synonymy, derivation, hypernymy, hyponymy, co-hyponymy)
 * read from the most frequent senses; nothing here is new, only the weighting table is ours.
 */
internal object MemoryTagCloud {
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

    /** Terms kept per tag cloud, highest weight first. */
    const val MAX_TERMS = 64

    /**
     * Relevance a cloud match contributes to recall for a cloud weight: `0.9 * sqrt(weight)`. A
     * synonym (1.0) scores 0.9, a little under an exact match; a sibling (0.3) scores about 0.49 and
     * so clears only a near-intrusive dial (the threshold runs from 0.92 focused to 0.45 intrusive).
     */
    fun relevance(weight: Float): Float = (CLOUD_RELEVANCE * sqrt(weight.coerceIn(0f, 1f).toDouble())).toFloat()

    private const val CLOUD_RELEVANCE = 0.9

    /**
     * The cloud of a tag's [text]: every keyword (and the whole phrase, when WordNet knows it) with
     * its related words, max weight per term, capped at [MAX_TERMS]. Without [wordNet], or for words
     * WordNet does not know, just the word and its [MemoryAliases] group. Deterministic: ties keep
     * discovery order (keyword order, then sense order).
     */
    fun cloudFor(text: String, kind: MemoryNodeKind, wordNet: WordNetLexicon?, weights: Weights = WEIGHTS): List<Pair<String, Float>> {
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
    fun encode(cloud: List<Pair<String, Float>>): String =
        cloud.joinToString(SEPARATOR) { (term, weight) -> "$term:${formatWeight(weight)}" }

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
     * Adds a cloud to every NounTag/VerbTag in [nodes] that has none yet. Engine-side, so tags from
     * any clerk engine get one; a tag carrying a cloud already is left as written.
     */
    fun withClouds(nodes: List<MemoryNode>, wordNet: WordNetLexicon?): List<MemoryNode> = nodes.map { node ->
        if ((node.kind != MemoryNodeKind.NounTag && node.kind != MemoryNodeKind.VerbTag) || TAG_CLOUD in node.metadata) {
            node
        } else {
            val cloud = cloudFor(node.text, node.kind, wordNet)
            if (cloud.isEmpty()) node else node.copy(metadata = node.metadata + (TAG_CLOUD to encode(cloud)))
        }
    }

    private const val SEPARATOR = "|"
    private val WORD_SPLIT = Regex("[^\\p{L}\\p{N}_.+#-]+")
}

/** Tag node metadata: the tag's related-word cloud, `term:weight|…` ([MemoryTagCloud]). */
internal const val TAG_CLOUD = "cloud"

/**
 * Inverted index over stored tag clouds: term -> tags (with cloud weight), plus each tag's indexed
 * memories. A memory's cloud is the union of its tags' clouds, max weight per term.
 */
internal class MemoryTagCloudIndex(snapshot: MemorySnapshot, activeIds: Set<MemoryNodeId>) {
    private val postings = HashMap<String, MutableMap<MemoryNodeId, Float>>()
    private val indexed = HashMap<MemoryNodeId, MutableList<MemoryNodeId>>()

    init {
        snapshot.nodes.forEach { node ->
            if (node.id !in activeIds) return@forEach
            if (node.kind != MemoryNodeKind.NounTag && node.kind != MemoryNodeKind.VerbTag) return@forEach
            MemoryTagCloud.decode(node.metadata[TAG_CLOUD]).forEach { (term, weight) ->
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

    /** Memories (tags and what they index) whose cloud holds [term]; the frequency filter reads it. */
    fun memoryCount(term: String): Int {
        val tags = postings[term] ?: return 0
        return tags.keys.flatMapTo(HashSet()) { listOf(it) + indexed[it].orEmpty() }.size
    }

    /**
     * Best cloud weight per tag and per indexed memory for [terms] (each also tried in its WordNet
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
