package com.hereliesaz.geministrator.memory

/**
 * Princeton WordNet 3.1, as tools/memory_lexicon/build_wordnet_lexicon.py packs it: noun and verb
 * synsets (lemmas, hypernyms, cross-part-of-speech derivations, topic domains) in WordNet's sense
 * order, part-of-speech membership for adjectives and adverbs, and the irregular-form exceptions.
 *
 * Synsets are addressed by their line index in the resource. Lookups are by lowercase lemma.
 */
internal class WordNetLexicon private constructor(
    private val synsetPos: CharArray,
    private val synsetLexname: ShortArray,
    private val synsetLemmas: StringBlob,
    private val synsetHypernyms: IntSlices,
    private val synsetDerivations: IntSlices,
    private val synsetDomains: IntSlices,
    private val lexnames: List<String>,
    /** fnv64("n\t$lemma" / "v\t$lemma") -> slice of [senseLists]. */
    private val index: LongIntTable,
    private val senseLists: IntSlices,
    /** fnv64("p\t$lemma") -> bit 1 adjective, bit 2 adverb. */
    private val otherPos: LongIntTable,
    private val exceptions: Map<String, List<String>>,
) {
    enum class Pos(val code: Char) { Noun('n'), Verb('v'), Adjective('a'), Adverb('r') }

    private val partsOfSpeechCache = HashMap<String, String>()

    /** Synsets of [lemma] as [pos], most frequent sense first; nouns and verbs only. */
    fun senses(lemma: String, pos: Pos): IntArray {
        if (pos != Pos.Noun && pos != Pos.Verb) return EMPTY
        val slice = index.get(fnv64("${pos.code}\t$lemma"))
        return if (slice < 0) EMPTY else senseLists.get(slice)
    }

    fun contains(lemma: String, pos: Pos): Boolean = when (pos) {
        Pos.Noun, Pos.Verb -> index.get(fnv64("${pos.code}\t$lemma")) >= 0
        Pos.Adjective -> otherPos.get(fnv64("p\t$lemma")).let { it >= 0 && it and 1 != 0 }
        Pos.Adverb -> otherPos.get(fnv64("p\t$lemma")).let { it >= 0 && it and 2 != 0 }
    }

    /**
     * WordNet's morphy: the base forms of [word] as [pos] that WordNet lists, irregular forms first,
     * then the word itself, then the detachment rules. Empty when WordNet does not know the word.
     */
    fun baseForms(word: String, pos: Pos): List<String> {
        val lower = word.lowercase()
        val out = LinkedHashSet<String>()
        exceptions["${pos.code}\t$lower"]?.forEach { if (contains(it, pos)) out += it }
        if (contains(lower, pos)) out += lower
        RULES.getValue(pos).forEach { (suffix, ending) ->
            if (lower.length > suffix.length && lower.endsWith(suffix)) {
                val base = lower.dropLast(suffix.length) + ending
                if (contains(base, pos)) out += base
            }
        }
        return out.toList()
    }

    /**
     * The parts of speech WordNet allows for any base form of [word], as sorted codes ("nv" for
     * "builds"). This is the tagger's WordNet feature; it mirrors `WordNetPos.tags` in
     * train_pos_tagger.py exactly.
     */
    fun partsOfSpeech(word: String): String {
        val lower = word.lowercase()
        partsOfSpeechCache[lower]?.let { return it }
        if (partsOfSpeechCache.size > 50_000) partsOfSpeechCache.clear()
        val found = mutableSetOf<Char>()
        for (pos in Pos.entries) {
            if (contains(lower, pos)) {
                found += pos.code
                continue
            }
            val bases = exceptions["${pos.code}\t$lower"].orEmpty() +
                RULES.getValue(pos).mapNotNull { (suffix, ending) ->
                    if (lower.endsWith(suffix) && lower.length > suffix.length) lower.dropLast(suffix.length) + ending else null
                }
            if (bases.any { contains(it, pos) }) found += pos.code
        }
        return found.sorted().joinToString("").also { partsOfSpeechCache[lower] = it }
    }

    /** Number of noun and verb synsets; synset ids run 0 until this. */
    val synsetCount: Int get() = synsetPos.size

    fun posOf(synset: Int): Pos = if (synsetPos[synset] == 'v') Pos.Verb else Pos.Noun

    fun lemmas(synset: Int): List<String> = synsetLemmas.get(synset).split('|')

    fun hypernyms(synset: Int): IntArray = synsetHypernyms.get(synset)

    /** Synsets of the other part of speech that a lemma of [synset] is derivationally related to. */
    fun derivations(synset: Int): IntArray = synsetDerivations.get(synset)

    fun domains(synset: Int): IntArray = synsetDomains.get(synset)

    /** Lexicographer file, e.g. `noun.artifact`, `verb.change`. */
    fun lexname(synset: Int): String = lexnames.getOrElse(synsetLexname[synset].toInt()) { "" }

    /**
     * True when [synset], or a hypernym within [depth] levels, carries a computing topic domain
     * (computer science, computing, programming, software). Used to prefer technical senses.
     */
    fun isComputing(synset: Int, depth: Int = 3): Boolean {
        var frontier = intArrayOf(synset)
        repeat(depth + 1) {
            if (frontier.any { s -> domains(s).any { it in computingDomains } || s in computingDomains }) return true
            frontier = frontier.flatMap { hypernyms(it).asIterable() }.distinct().toIntArray()
            if (frontier.isEmpty()) return false
        }
        return false
    }

    private val computingDomains: Set<Int> by lazy {
        COMPUTING_DOMAIN_LEMMAS.flatMapTo(HashSet()) { senses(it, Pos.Noun).take(1) }
    }

    companion object {
        private val EMPTY = IntArray(0)

        private val COMPUTING_DOMAIN_LEMMAS = listOf(
            "computer science", "computing", "programming", "software", "computer", "internet",
        )

        private val RULES: Map<Pos, List<Pair<String, String>>> = mapOf(
            Pos.Noun to listOf("s" to "", "ses" to "s", "xes" to "x", "zes" to "z", "ches" to "ch", "shes" to "sh", "men" to "man", "ies" to "y"),
            Pos.Verb to listOf("s" to "", "ies" to "y", "es" to "e", "es" to "", "ed" to "e", "ed" to "", "ing" to "e", "ing" to ""),
            Pos.Adjective to listOf("er" to "", "est" to "", "er" to "e", "est" to "e"),
            Pos.Adverb to emptyList(),
        )

        fun parse(text: String): WordNetLexicon {
            val lexnames = mutableListOf<String>()
            val pos = StringBuilder()
            val lexname = ArrayList<Short>(100_000)
            val lemmas = StringBlob()
            val hypernyms = IntSlices()
            val derivations = IntSlices()
            val domains = IntSlices()
            val index = LongIntTable(140_000)
            val senseLists = IntSlices()
            val otherPos = LongIntTable(30_000)
            val exceptions = HashMap<String, List<String>>(8_000)

            fun ints(value: String): IntArray =
                if (value.isEmpty()) EMPTY else value.split(',').map(String::toInt).toIntArray()

            var section = ""
            text.lineSequence().forEach { line ->
                if (line.isEmpty()) return@forEach
                if (line.startsWith("#")) {
                    if (!line.startsWith("# ")) section = line
                    return@forEach
                }
                val cols = line.split('\t')
                when (section) {
                    "#lexnames" -> lexnames += line
                    "#synsets" -> {
                        pos.append(cols[0][0])
                        lexname += cols[1].toShort()
                        lemmas.add(cols[2])
                        hypernyms.add(ints(cols[3]))
                        derivations.add(ints(cols[4]))
                        domains.add(ints(cols[5]))
                    }
                    "#index" -> index.put(fnv64("${cols[1]}\t${cols[0]}"), senseLists.add(ints(cols[2])))
                    "#pos" -> otherPos.put(
                        fnv64("p\t${cols[0]}"),
                        (if ('a' in cols[1]) 1 else 0) or (if ('r' in cols[1]) 2 else 0),
                    )
                    "#exceptions" -> exceptions["${cols[0]}\t${cols[1]}"] = cols[2].split('|')
                }
            }
            require(pos.isNotEmpty()) { "WordNet resource has no synsets" }
            return WordNetLexicon(
                synsetPos = CharArray(pos.length) { pos[it] },
                synsetLexname = lexname.toShortArray(),
                synsetLemmas = lemmas.trim(),
                synsetHypernyms = hypernyms.trim(),
                synsetDerivations = derivations.trim(),
                synsetDomains = domains.trim(),
                lexnames = lexnames,
                index = index,
                senseLists = senseLists.trim(),
                otherPos = otherPos,
                exceptions = exceptions,
            )
        }
    }
}
