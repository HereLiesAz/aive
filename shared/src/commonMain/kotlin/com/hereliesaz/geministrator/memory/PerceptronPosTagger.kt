package com.hereliesaz.geministrator.memory

/**
 * Greedy averaged-perceptron part-of-speech tagger (Penn Treebank tags), trained by
 * tools/memory_lexicon/train_pos_tagger.py on Universal Dependencies English-EWT with WordNet
 * part-of-speech features. ~94% on the EWT test set.
 *
 * [features] must build exactly the strings `features()` builds in the training script;
 * tools/memory_lexicon/pos_tagger_golden.tsv pins the two together.
 *
 * Code tokens are masked to [CODE_TOKEN] by the caller before tagging; the model was trained to
 * read them as proper-noun-like entities.
 */
internal class PerceptronPosTagger private constructor(
    private val tags: List<String>,
    private val tagDictionary: Map<String, String>,
    /** fnv64(feature) -> slice of [cells]: (tag index, weight) pairs. */
    private val weights: LongIntTable,
    private val cells: IntSlices,
    private val wordNet: WordNetLexicon,
) {
    /** Tags [words] (one sentence) left to right. */
    fun tag(words: List<String>): List<String> {
        val context = START + words.map(::normalize) + END
        var prev = START[0]
        var prev2 = START[1]
        val scores = IntArray(tags.size)
        return words.mapIndexed { i, word ->
            val guess = tagDictionary[word] ?: run {
                scores.fill(0)
                features(i, word, context, prev, prev2).forEach { feature ->
                    val slice = weights.get(fnv64(feature))
                    if (slice < 0) return@forEach
                    val pairs = cells.get(slice)
                    var c = 0
                    while (c < pairs.size) {
                        scores[pairs[c]] += pairs[c + 1]
                        c += 2
                    }
                }
                // Highest score; ties go to the earliest tag, as in training.
                var best = 0
                for (t in 1 until scores.size) if (scores[t] > scores[best]) best = t
                tags[best]
            }
            prev2 = prev
            prev = guess
            guess
        }
    }

    private fun features(i: Int, word: String, context: List<String>, prev: String, prev2: String): List<String> {
        val at = i + START.size
        val wn = if (word == CODE_TOKEN) "code" else wordNet.partsOfSpeech(word)
        val next = context[at + 1]
        val nextWn = if (next in END || next == CODE_TOKEN) "-" else wordNet.partsOfSpeech(next)
        return listOf(
            "i wn $wn",
            "i wn+i-1 tag $wn $prev",
            "i+1 wn $nextWn",
            "bias",
            "i suffix ${word.takeLast(3)}",
            "i pref1 ${word.take(1)}",
            "i-1 tag $prev",
            "i-2 tag $prev2",
            "i tag+i-2 tag $prev $prev2",
            "i word ${context[at]}",
            "i-1 tag+i word $prev ${context[at]}",
            "i-1 word ${context[at - 1]}",
            "i-1 suffix ${context[at - 1].takeLast(3)}",
            "i-2 word ${context[at - 2]}",
            "i+1 word $next",
            "i+1 suffix ${next.takeLast(3)}",
            "i+2 word ${context[at + 2]}",
        )
    }

    companion object {
        const val CODE_TOKEN: String = "CODE_TOKEN"
        private val START = listOf("-START-", "-START2-")
        private val END = listOf("-END-", "-END2-")

        internal fun normalize(word: String): String = when {
            word == CODE_TOKEN -> word
            '-' in word && word[0] != '-' -> "!HYPHEN"
            word.length == 4 && word.all(Char::isDigit) -> "!YEAR"
            word.isNotEmpty() && word[0].isDigit() -> "!DIGITS"
            else -> word.lowercase()
        }

        fun parse(text: String, wordNet: WordNetLexicon): PerceptronPosTagger {
            val tags = mutableListOf<String>()
            val tagDictionary = HashMap<String, String>()
            val weights = LongIntTable(80_000)
            val cells = IntSlices()
            var section = ""
            text.lineSequence().forEach { line ->
                if (line.isEmpty()) return@forEach
                if (line.startsWith("#") && (line.startsWith("#aive") || line in SECTIONS)) {
                    section = line
                    return@forEach
                }
                if (section == "#license") return@forEach
                when (section) {
                    "#tags" -> tags += line
                    "#tagdict" -> {
                        val tab = line.lastIndexOf('\t')
                        tagDictionary[line.substring(0, tab)] = line.substring(tab + 1)
                    }
                    "#weights" -> {
                        val tab = line.lastIndexOf('\t')
                        val entries = line.substring(tab + 1).split(' ')
                        val packed = IntArray(entries.size * 2)
                        entries.forEachIndexed { c, cell ->
                            val colon = cell.indexOf(':')
                            packed[c * 2] = cell.substring(0, colon).toInt()
                            packed[c * 2 + 1] = cell.substring(colon + 1).toInt()
                        }
                        weights.put(fnv64(line.substring(0, tab)), cells.add(packed))
                    }
                }
            }
            require(tags.isNotEmpty() && weights.size > 0) { "Tagger resource is empty" }
            return PerceptronPosTagger(tags, tagDictionary, weights, cells.trim(), wordNet)
        }

        private val SECTIONS = setOf("#license", "#tags", "#tagdict", "#weights")
    }
}
