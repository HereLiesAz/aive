package com.hereliesaz.geministrator.memory

/*
 * Deterministic entity/action analysis for the programmatic noun and verb clerks.
 *
 * Pipeline, per text:
 *  1. Code first. Fenced blocks and stack-trace lines are read for code entities only; in prose,
 *     backtick spans, identifiers, paths, URLs, `#123` references, commit hashes, versions, flags
 *     and Gradle task paths become single tokens, masked from the tagger as CODE_TOKEN.
 *  2. Sentences: split on sentence punctuation the tokenizer left standing (so `v1.2`, `foo.bar()`
 *     and paths never split), not after common abbreviations, and at line breaks that end a line
 *     or start a list item or heading.
 *  3. Part-of-speech tags from [PerceptronPosTagger].
 *  4. Entities: noun-phrase chunks (compound nouns plus code tokens; adjectives only when the whole
 *     phrase is a known term). Actions: verbs minus auxiliaries, with phrasal particles
 *     ("roll back") and NegEx-style negation ("didn't fix" -> "not fix").
 *  5. Sense: the technical overlay first, then WordNet, preferring computing-domain senses in
 *     technical text, else the most frequent sense, skipping senses that cannot be meant in
 *     technical text (animals, food...). Synonyms of the chosen sense become aliases and share a
 *     key, so "delete" and "erase" group; broader terms come only from a confident sense.
 *  6. Implied entities, each marked with what implied it: broader terms, identifier heads
 *     (`FooRepository` -> repository), file languages, URL hosts and GitHub references, commits,
 *     versions, acronym expansions (defined in the text, or unambiguous in the overlay).
 *  7. Verb-object pairs (ReVerb-style: a verb, optional particle/preposition, the next noun phrase
 *     in the same clause; passive voice takes the subject). `it`/`they`/`them` resolve to the most
 *     salient agreeing noun phrase in the last three sentences, or are left unresolved when the
 *     choice is close or the pronoun is pleonastic.
 *
 * Nothing here judges truth: it records what the text says, including its negations.
 */

internal enum class MemoryConceptKind { Entity, Action }

/** Why an implied concept was added; explicit concepts have none. */
internal enum class MemoryImpliedBy { Broader, CompoundHead, Identifier, Language, Host, Reference, Commit, Version, Option, Task, Exception, Acronym, Derivation }

internal data class MemoryConcept(
    /** Grouping key: concepts with one key are one tag. */
    val key: String,
    val text: String,
    val kind: MemoryConceptKind,
    val aliases: List<String> = emptyList(),
    val impliedBy: MemoryImpliedBy? = null,
    val negated: Boolean = false,
    val confidence: Float = 1f,
    /** `tech:<canonical>` or `wn:<synset>` when a sense was chosen. */
    val sense: String? = null,
)

internal data class MemoryVerbObject(
    val actionKey: String,
    val entityKey: String,
    /** "<action> <entity>", as the tags display them. */
    val text: String,
    /** The source text the pair was read from. */
    val span: String,
    val negated: Boolean,
    /** The whole sentence the pair came from, verbatim. */
    val sentence: String = span,
)

internal data class MemoryTextAnalysis(
    val concepts: List<MemoryConcept>,
    val verbObjects: List<MemoryVerbObject>,
) {
    fun entities(): List<MemoryConcept> = concepts.filter { it.kind == MemoryConceptKind.Entity }
    fun actions(): List<MemoryConcept> = concepts.filter { it.kind == MemoryConceptKind.Action }
}

internal class MemoryTextAnalyzer(private val resources: MemoryLanguageResources) {
    private val wordNet get() = resources.wordNet

    fun analyze(text: String): MemoryTextAnalysis {
        if (text.isBlank()) return MemoryTextAnalysis(emptyList(), emptyList())
        val out = ConceptCollector()
        val (prose, code) = separateCode(text)

        // Code blocks and stack traces: entities and identifier actions only.
        if (code.isNotBlank()) {
            val hints = extractCodeSemanticHints(code)
            hints.nounCandidates.map(String::trim).filter { it.length in 2..80 }.forEach { codeEntity(it, out) }
            hints.verbCandidates.forEach { action ->
                out.add(MemoryConcept("v:$action", action, MemoryConceptKind.Action, impliedBy = MemoryImpliedBy.Identifier, confidence = 0.6f))
            }
            EXCEPTION_NAME.findAll(code).forEach { codeEntity(it.value, out) }
        }

        val sentences = sentences(prose)
        val technical = code.isNotBlank() || prose.looksStronglyTechnical() ||
            sentences.any { s -> s.tokens.any { it.kind != TokKind.Word && it.kind != TokKind.Number && it.kind != TokKind.Punct } }
        val acronyms = definedAcronyms(prose)
        val history = ArrayDeque<List<Chunk>>()
        val mentions = HashMap<String, Int>()
        val pairs = LinkedHashMap<String, MemoryVerbObject>()

        sentences.forEach { sentence ->
            val tags = tag(sentence.tokens)
            val chunks = nounPhrases(sentence, tags, technical, acronyms, out)
            chunks.forEach { mentions[it.concept.key] = (mentions[it.concept.key] ?: 0) + 1 }
            val firstVerb = tags.indexOfFirst { it.startsWith("VB") }
            val subjects = chunks.filter { firstVerb < 0 || it.end <= firstVerb }

            verbs(sentence, tags, chunks, technical, out).forEach { verb ->
                val obj = objectOf(verb, sentence, tags, chunks)
                    ?: pronounObject(verb, sentence, tags, chunks, subjects, history, mentions)
                    ?: passiveSubject(verb, sentence, tags, chunks)
                if (obj != null) {
                    val text = "${verb.concept.text} ${obj.concept.text}"
                    val start = minOf(verb.start, obj.start)
                    val end = maxOf(verb.end, obj.end)
                    val span = sentence.source.substring(sentence.tokens[start].start, sentence.tokens[end - 1].end)
                    pairs.getOrPut("${verb.concept.key}|${obj.concept.key}") {
                        MemoryVerbObject(
                            verb.concept.key, obj.concept.key, text, span, verb.concept.negated,
                            sentence.source.substring(sentence.tokens.first().start, sentence.tokens.last().end),
                        )
                    }
                }
            }
            history.addLast(chunks.map { it.copy(subject = it in subjects) })
            if (history.size > 3) history.removeFirst()
        }
        return MemoryTextAnalysis(out.concepts(), pairs.values.toList())
    }

    // -----------------------------------------------------------------------------------------
    // Code separation, sentences, tokens
    // -----------------------------------------------------------------------------------------

    private fun separateCode(text: String): Pair<String, String> {
        val prose = StringBuilder()
        val code = StringBuilder()
        var fence: String? = null
        text.lines().forEach { line ->
            val trimmed = line.trim()
            when {
                fence != null -> {
                    if (trimmed.startsWith(fence!!)) fence = null else code.appendLine(line)
                }
                trimmed.startsWith("```") || trimmed.startsWith("~~~") -> {
                    fence = trimmed.take(3)
                    prose.appendLine()
                }
                STACK_FRAME.containsMatchIn(line) -> code.appendLine(line)
                else -> prose.appendLine(MARKDOWN_PREFIX.replace(line, ""))
            }
        }
        return prose.toString() to code.toString()
    }

    private enum class TokKind { Word, Code, Url, Ref, Path, Hash, Flag, Version, Task, Number, Punct }

    private data class Tok(val text: String, val kind: TokKind, val start: Int, val end: Int) {
        val masked: Boolean get() = kind != TokKind.Word && kind != TokKind.Number && kind != TokKind.Punct && kind != TokKind.Version
    }

    private class Sentence(val source: String, val tokens: List<Tok>)

    private fun sentences(prose: String): List<Sentence> {
        val out = mutableListOf<Sentence>()
        prose.split(PARAGRAPH).forEach { paragraph ->
            val lines = paragraph.lines().filter(String::isNotBlank)
            val units = mutableListOf<String>()
            lines.forEach { line ->
                val previous = units.lastOrNull()
                val joins = previous != null && !LINE_ENDS_UNIT.containsMatchIn(previous) && !LIST_ITEM.containsMatchIn(line)
                if (joins) units[units.lastIndex] = "$previous ${line.trim()}" else units += line.trim()
            }
            units.forEach { unit ->
                val tokens = tokenize(unit)
                var from = 0
                tokens.forEachIndexed { i, tok ->
                    val ends = tok.kind == TokKind.Punct && tok.text in SENTENCE_PUNCT &&
                        !(tok.text == "." && i > 0 && tokens[i - 1].text.lowercase() in ABBREVIATIONS)
                    if (ends) {
                        if (i + 1 > from) out += Sentence(unit, tokens.subList(from, i + 1))
                        from = i + 1
                    }
                }
                if (from < tokens.size) out += Sentence(unit, tokens.subList(from, tokens.size))
            }
        }
        return out.filter { s -> s.tokens.any { it.kind != TokKind.Punct } }
    }

    private fun tokenize(text: String): List<Tok> {
        val out = mutableListOf<Tok>()
        TOKEN.findAll(text).forEach { match ->
            val groups = match.groups
            val kind = TOKEN_KINDS.withIndex().firstOrNull { (i, _) -> groups[i + 1] != null }?.value ?: TokKind.Punct
            var value = match.value
            var end = match.range.last + 1
            when (kind) {
                TokKind.Code -> if (value.startsWith("`")) value = value.trim('`').trim()
                TokKind.Url, TokKind.Path -> {
                    val trimmed = value.trimEnd('.', ',', ';', ':', '!', '?', ')')
                    end -= value.length - trimmed.length
                    value = trimmed
                }
                TokKind.Hash -> if (value.none(Char::isDigit) || value.none(Char::isLetter)) {
                    out += Tok(value, TokKind.Word, match.range.first, end)
                    return@forEach
                }
                TokKind.Word -> {
                    // Penn Treebank contractions, as the tagger was trained on them.
                    val lower = value.lowercase()
                    val split = when {
                        lower.endsWith("n't") && lower.length > 3 -> when (lower) {
                            "can't" -> 2
                            "won't" -> 2
                            else -> value.length - 3
                        }
                        CLITIC.matches(lower) -> value.lastIndexOf('\'')
                        else -> -1
                    }
                    if (split > 0) {
                        val first = match.range.first
                        out += Tok(value.substring(0, split), TokKind.Word, first, first + split)
                        out += Tok(value.substring(split), TokKind.Word, first + split, end)
                        return@forEach
                    }
                }
                else -> Unit
            }
            if (value.isNotEmpty()) out += Tok(value, kind, match.range.first, end)
        }
        return out
    }

    private fun tag(tokens: List<Tok>): List<String> {
        val words = tokens.map { if (it.masked) PerceptronPosTagger.CODE_TOKEN else it.text }
        return resources.tagger.tag(words).mapIndexed { i, tag -> if (tokens[i].masked) "NNP" else tag }
    }

    // -----------------------------------------------------------------------------------------
    // Entities
    // -----------------------------------------------------------------------------------------

    private data class Chunk(val start: Int, val end: Int, val concept: MemoryConcept, val plural: Boolean, val code: Boolean, val subject: Boolean = false)

    private fun nounPhrases(
        sentence: Sentence,
        tags: List<String>,
        technical: Boolean,
        acronyms: Map<String, String>,
        out: ConceptCollector,
    ): List<Chunk> {
        val tokens = sentence.tokens
        val chunks = mutableListOf<Chunk>()
        var i = 0
        while (i < tokens.size) {
            if (!isNominal(tags[i], tokens[i]) && !isModifier(tags[i], tokens[i])) {
                i++
                continue
            }
            var j = i
            while (j < tokens.size && (isNominal(tags[j], tokens[j]) || isModifier(tags[j], tokens[j]))) j++
            // The phrase ends at its last nominal token.
            var head = j - 1
            while (head >= i && !isNominal(tags[head], tokens[head])) head--
            if (head < i) {
                i = j
                continue
            }
            chunkAt(i, head, tokens, tags, technical, acronyms, out)?.let(chunks::add)
            i = j
        }
        return chunks
    }

    private fun isNominal(tag: String, tok: Tok): Boolean =
        tok.masked || tag == "NN" || tag == "NNS" || tag == "NNP" || tag == "NNPS" || tag == "FW"

    private fun isModifier(tag: String, tok: Tok): Boolean =
        tok.kind == TokKind.Word && (tag == "JJ" || tag == "JJR" || tag == "JJS" || tag == "VBG" || tag == "VBN")

    private fun chunkAt(
        from: Int,
        head: Int,
        tokens: List<Tok>,
        tags: List<String>,
        technical: Boolean,
        acronyms: Map<String, String>,
        out: ConceptCollector,
    ): Chunk? {
        val headTok = tokens[head]
        if (headTok.masked) {
            // A code token heads the phrase: the token is the entity ("the `Foo` class" keeps `Foo`).
            val concept = codeToken(headTok, tokens, head, out) ?: return null
            return Chunk(head, head + 1, concept, plural = false, code = true)
        }
        // Compound: the head plus up to three nominal tokens before it.
        var compoundStart = head
        while (compoundStart > from && head - compoundStart < 3 && isNominal(tags[compoundStart - 1], tokens[compoundStart - 1])) compoundStart--
        val plural = tags[head] == "NNS" || tags[head] == "NNPS"
        val headLemma = nounLemma(headTok.text, plural)
        val modifiers = tokens.subList(compoundStart, head).map { display(it, tags[tokens.indexOf(it)]) }
        var words = modifiers + headLemma
        // An adjective is kept only when the whole phrase is a known term ("public key").
        if (compoundStart > from && tags[compoundStart - 1].startsWith("JJ")) {
            val withAdjective = listOf(tokens[compoundStart - 1].text.lowercase()) + words
            if (isKnownNoun(withAdjective.joinToString(" ").lowercase())) {
                words = withAdjective
                compoundStart--
            }
        }
        if (words.any { it in STOP_NOUNS } && words.size == 1) return null
        val phrase = words.joinToString(" ")
        val start = compoundStart
        val concept = when {
            words.size == 1 && headTok.isAcronymLike() -> acronym(acronymStem(headTok.text)!!, acronyms, technical, out)
            else -> nounConcept(phrase, technical, out)
        }
        // A compound implies its head ("gradle cache" -> cache) when the head means something alone.
        if (words.size > 1 && concept.sense == null) {
            val headConcept = resolveNoun(headLemma.lowercase(), technical)
            if (headConcept != null && headLemma.lowercase() !in STOP_NOUNS) {
                out.add(headConcept.copy(impliedBy = MemoryImpliedBy.CompoundHead, confidence = minOf(headConcept.confidence, 0.6f)))
            }
        }
        return Chunk(start, head + 1, concept, plural, code = false)
    }

    private fun Tok.isAcronymLike(): Boolean = acronymStem(text) != null

    /** "API", "APIs", "CI" -> the acronym; null for ordinary words. */
    private fun acronymStem(word: String): String? {
        val stem = if (PLURAL_ACRONYM.matches(word)) word.dropLast(1) else word
        return stem.takeIf {
            it.length in 2..6 && it.count(Char::isUpperCase) >= 2 && it.all(Char::isLetterOrDigit) ||
                it.length <= 6 && it.lowercase() in MemoryTechnicalLexicon.acronyms
        }
    }

    private fun display(tok: Tok, tag: String): String =
        if (tag == "NNP" || tag == "NNPS" || tok.masked) tok.text else tok.text.lowercase()

    private fun nounLemma(word: String, plural: Boolean): String {
        if (!plural) return if (word.any(Char::isUpperCase) && word.drop(1).any(Char::isUpperCase)) word else word.lowercase()
        return wordNet.baseForms(word, WordNetLexicon.Pos.Noun).firstOrNull()
            ?: word.lowercase().let { if (it.endsWith("s") && !it.endsWith("ss")) it.dropLast(1) else it }
    }

    private fun isKnownNoun(phrase: String): Boolean =
        MemoryTechnicalLexicon.lookup(phrase, WordNetLexicon.Pos.Noun) != null || wordNet.contains(phrase, WordNetLexicon.Pos.Noun)

    private fun nounConcept(phrase: String, technical: Boolean, out: ConceptCollector): MemoryConcept {
        val lower = phrase.lowercase()
        val resolved = resolveNoun(lower, technical)
        val concept = resolved?.copy(text = if (resolved.sense?.startsWith("tech:") == true) resolved.text else phrase)
            ?: MemoryConcept("n:$lower", phrase, MemoryConceptKind.Entity, confidence = 0.8f)
        out.add(concept)
        if (resolved != null) broaderOf(resolved, lower, technical, MemoryConceptKind.Entity).forEach(out::add)
        return concept
    }

    private fun acronym(surface: String, defined: Map<String, String>, technical: Boolean, out: ConceptCollector): MemoryConcept {
        val lower = surface.lowercase()
        val expansion = defined[lower] ?: MemoryTechnicalLexicon.acronyms[lower]
        if (expansion == null || expansion == lower) {
            val concept = resolveNoun(lower, technical)?.copy(text = surface)
                ?: MemoryConcept("n:$lower", surface, MemoryConceptKind.Entity, confidence = 0.8f)
            out.add(concept)
            return concept
        }
        val resolved = resolveNoun(expansion, technical)
        val concept = MemoryConcept(
            key = resolved?.key ?: "n:$expansion",
            text = surface,
            kind = MemoryConceptKind.Entity,
            aliases = (listOf(expansion) + resolved?.aliases.orEmpty()).distinct().filter { it != lower },
            confidence = 0.9f,
            sense = resolved?.sense,
        )
        out.add(concept)
        return concept
    }

    /** Overlay first, then WordNet; null when neither knows the noun. */
    private fun resolveNoun(lemma: String, technical: Boolean): MemoryConcept? =
        resolve(lemma, WordNetLexicon.Pos.Noun, technical, MemoryConceptKind.Entity)

    private fun resolve(lemma: String, pos: WordNetLexicon.Pos, technical: Boolean, kind: MemoryConceptKind): MemoryConcept? {
        val prefix = if (kind == MemoryConceptKind.Entity) "n" else "v"
        MemoryTechnicalLexicon.lookup(lemma, pos)?.let { term ->
            return MemoryConcept(
                key = "$prefix:${term.canonical}",
                text = term.canonical,
                kind = kind,
                aliases = (listOf(lemma) + term.synonyms).distinct().filter { it != term.canonical },
                confidence = 0.95f,
                sense = "tech:${term.canonical}",
            )
        }
        val sense = chooseSense(lemma, pos, technical) ?: return null
        val (synset, confidence) = sense
        return MemoryConcept(
            key = "$prefix#$synset",
            text = lemma,
            kind = kind,
            aliases = wordNet.lemmas(synset).filter { it != lemma && it.length <= 30 }.take(5),
            confidence = confidence,
            sense = "wn:$synset",
        )
    }

    /** The synset meant and how sure the choice is; null when no sense fits. */
    private fun chooseSense(lemma: String, pos: WordNetLexicon.Pos, technical: Boolean): Pair<Int, Float>? {
        val senses = wordNet.senses(lemma, pos)
        if (senses.isEmpty()) return null
        if (technical) {
            senses.take(6).firstOrNull { wordNet.isComputing(it) }?.let { return it to 0.85f }
            val plausible = senses.firstOrNull { wordNet.lexname(it) !in NON_TECHNICAL_LEXNAMES } ?: return null
            return plausible to if (senses.size == 1) 0.8f else 0.6f
        }
        return senses[0] to if (senses.size == 1) 0.85f else 0.6f
    }

    /** Broader terms from a confident sense: the overlay's, or one WordNet hypernym level. */
    private fun broaderOf(concept: MemoryConcept, lemma: String, technical: Boolean, kind: MemoryConceptKind): List<MemoryConcept> {
        val prefix = if (kind == MemoryConceptKind.Entity) "n" else "v"
        val sense = concept.sense ?: return emptyList()
        if (sense.startsWith("tech:")) {
            val pos = if (kind == MemoryConceptKind.Entity) WordNetLexicon.Pos.Noun else WordNetLexicon.Pos.Verb
            return MemoryTechnicalLexicon.lookup(lemma, pos)?.broader.orEmpty().map { broader ->
                (MemoryTechnicalLexicon.lookup(broader, pos)?.let { "$prefix:${it.canonical}" } ?: "$prefix:$broader")
                    .let { key -> MemoryConcept(key, broader, kind, impliedBy = MemoryImpliedBy.Broader, confidence = 0.6f) }
            }
        }
        if (concept.confidence < 0.75f) return emptyList()
        val synset = sense.removePrefix("wn:").toIntOrNull() ?: return emptyList()
        return wordNet.hypernyms(synset).take(2).mapNotNull { hypernym ->
            val name = wordNet.lemmas(hypernym).first()
            if (name in ABSTRACT_TOPS) return@mapNotNull null
            MemoryConcept("$prefix#$hypernym", name, kind, impliedBy = MemoryImpliedBy.Broader, confidence = 0.55f, sense = "wn:$hypernym")
        }.also { if (!technical && it.isEmpty()) return emptyList() }
    }

    // -----------------------------------------------------------------------------------------
    // Code tokens and what they imply
    // -----------------------------------------------------------------------------------------

    private fun codeToken(tok: Tok, tokens: List<Tok>, index: Int, out: ConceptCollector): MemoryConcept? {
        val previous = (index - 1 downTo maxOf(0, index - 2)).map { tokens[it].text.lowercase() }
        return when (tok.kind) {
            TokKind.Url -> url(tok.text, out)
            TokKind.Ref -> {
                val label = when {
                    previous.firstOrNull() in setOf("pr", "mr") || previous.take(2) == listOf("request", "pull") -> "pull request"
                    previous.firstOrNull() in setOf("issue", "ticket", "bug") -> "issue"
                    else -> null
                }
                val text = if (label != null) "$label ${tok.text}" else tok.text
                MemoryConcept("r:${text.lowercase()}", text, MemoryConceptKind.Entity, confidence = 1f).also {
                    out.add(it)
                    if (label != null) out.add(implied(label, MemoryImpliedBy.Reference))
                }
            }
            TokKind.Hash -> MemoryConcept("h:${tok.text.lowercase().take(7)}", tok.text.take(7), MemoryConceptKind.Entity).also {
                out.add(it)
                out.add(implied("commit", MemoryImpliedBy.Commit))
            }
            TokKind.Flag -> MemoryConcept("c:${tok.text}", tok.text, MemoryConceptKind.Entity).also {
                out.add(it)
                out.add(implied("command-line option", MemoryImpliedBy.Option))
            }
            TokKind.Task -> MemoryConcept("c:${tok.text}", tok.text, MemoryConceptKind.Entity).also {
                out.add(it)
                out.add(implied("gradle task", MemoryImpliedBy.Task))
            }
            TokKind.Path -> path(tok.text, out)
            TokKind.Code -> codeEntity(tok.text, out)
            else -> null
        }
    }

    private fun codeEntity(raw: String, out: ConceptCollector): MemoryConcept? {
        val text = raw.trim()
        if (text.length !in 2..80) return null
        if (PATH_LIKE.matches(text)) return path(text, out)
        val concept = MemoryConcept("c:${text.lowercase()}", text, MemoryConceptKind.Entity)
        out.add(concept)
        val terms = identifierWords(text.substringBefore('(').substringAfterLast('.'))
        val head = terms.lastOrNull()
        when {
            text.endsWith("Exception") || text.endsWith("Error") ->
                out.add(implied(if (text.endsWith("Exception")) "exception" else "error", MemoryImpliedBy.Exception))
            head != null && terms.size > 1 && head.length >= 3 && head !in STOP_NOUNS && isKnownNoun(head) ->
                out.add(implied(head, MemoryImpliedBy.Identifier))
        }
        return concept
    }

    private fun path(text: String, out: ConceptCollector): MemoryConcept {
        val concept = MemoryConcept("p:${text.lowercase()}", text, MemoryConceptKind.Entity)
        out.add(concept)
        val name = text.substringAfterLast('/')
        val extension = name.substringAfterLast('.', "").lowercase()
        MemoryTechnicalLexicon.extensions[extension]?.let { out.add(implied(it, MemoryImpliedBy.Language)) }
        if (name.equals("Dockerfile", ignoreCase = true)) out.add(implied("dockerfile", MemoryImpliedBy.Language))
        return concept
    }

    private fun url(text: String, out: ConceptCollector): MemoryConcept {
        val concept = MemoryConcept("u:$text", text, MemoryConceptKind.Entity)
        out.add(concept)
        val host = text.substringAfter("://").substringBefore('/').substringBefore(':').removePrefix("www.").lowercase()
        if (host.isNotEmpty()) out.add(MemoryConcept("u:$host", host, MemoryConceptKind.Entity, impliedBy = MemoryImpliedBy.Host, confidence = 0.8f))
        GITHUB.find(text)?.let { match ->
            val repository = "${match.groupValues[1]}/${match.groupValues[2]}"
            out.add(MemoryConcept("r:${repository.lowercase()}", repository, MemoryConceptKind.Entity, impliedBy = MemoryImpliedBy.Reference, confidence = 0.9f))
            val number = match.groupValues[4]
            if (number.isNotEmpty()) {
                val label = if (match.groupValues[3] == "pull") "pull request" else "issue"
                val ref = "$repository#$number"
                out.add(MemoryConcept("r:${ref.lowercase()}", "$label $ref", MemoryConceptKind.Entity, impliedBy = MemoryImpliedBy.Reference, confidence = 0.9f))
            }
        }
        return concept
    }

    private fun implied(text: String, by: MemoryImpliedBy): MemoryConcept {
        val term = MemoryTechnicalLexicon.lookup(text, WordNetLexicon.Pos.Noun)
        return MemoryConcept(
            key = "n:${term?.canonical ?: text}",
            text = term?.canonical ?: text,
            kind = MemoryConceptKind.Entity,
            impliedBy = by,
            confidence = 0.6f,
            sense = term?.let { "tech:${it.canonical}" },
        )
    }

    // -----------------------------------------------------------------------------------------
    // Actions
    // -----------------------------------------------------------------------------------------

    private data class VerbHit(val start: Int, val end: Int, val concept: MemoryConcept, val tag: String)

    private fun verbs(sentence: Sentence, tags: List<String>, chunks: List<Chunk>, technical: Boolean, out: ConceptCollector): List<VerbHit> {
        val tokens = sentence.tokens
        val inChunk = BooleanArray(tokens.size).also { flags -> chunks.forEach { c -> for (k in c.start until c.end) flags[k] = true } }
        val hits = mutableListOf<VerbHit>()
        tokens.forEachIndexed { i, tok ->
            if (!tags[i].startsWith("VB") || tok.kind != TokKind.Word || inChunk[i]) return@forEachIndexed
            // Participles directly before a noun inside a phrase are modifiers, not actions.
            val beforeNoun = i + 1 < tokens.size && (tags[i + 1].startsWith("NN") || tokens[i + 1].masked)
            val afterAuxiliary = i > 0 && tokens[i - 1].text.lowercase() in BE_FORMS + HAVE_FORMS
            if (tags[i] == "VBG" && beforeNoun && i > 0 && tags[i - 1] in DETERMINERS) return@forEachIndexed
            if (tags[i] == "VBN" && beforeNoun && !afterAuxiliary) return@forEachIndexed
            // "removed deprecated APIs": an -ed form between a verb (or determiner) and a noun modifies it.
            if (tags[i] == "VBD" && beforeNoun && tok.text.endsWith("ed") && i > 0 &&
                (tags[i - 1].startsWith("VB") || tags[i - 1] in DETERMINERS || tags[i - 1] == "IN" || tags[i - 1].startsWith("JJ"))
            ) return@forEachIndexed
            // A base form after a preposition or determiner is a noun the tagger misread ("on commit abc").
            if (tags[i] == "VB" && i > 0 && (tags[i - 1] in DETERMINERS || tags[i - 1] == "IN")) return@forEachIndexed
            val lemma = verbLemma(tok.text) ?: return@forEachIndexed
            if (lemma in AUXILIARY_VERBS) return@forEachIndexed
            if (lemma in LIGHT_VERBS && nextIsVerb(i, tags)) return@forEachIndexed
            var end = i + 1
            var phrasal = lemma
            // Particle right after, or after one pronoun ("roll it back").
            listOf(i + 1, i + 2).firstOrNull { p ->
                p < tokens.size && tokens[p].text.lowercase() in PARTICLES &&
                    (p == i + 1 || tags[i + 1] == "PRP") &&
                    "$lemma ${tokens[p].text.lowercase()}".let {
                        wordNet.contains(it, WordNetLexicon.Pos.Verb) || it in MemoryTechnicalLexicon.phrasalVerbs ||
                            MemoryTechnicalLexicon.lookup(it, WordNetLexicon.Pos.Verb) != null
                    }
            }?.let { p ->
                phrasal = "$lemma ${tokens[p].text.lowercase()}"
                if (p == i + 1) end = p + 1
            }
            val negated = negated(i, tokens, tags)
            val resolved = resolve(phrasal, WordNetLexicon.Pos.Verb, technical, MemoryConceptKind.Action)
            val base = resolved?.copy(text = if (resolved.sense?.startsWith("tech:") == true) resolved.text else phrasal)
                ?: MemoryConcept("v:$phrasal", phrasal, MemoryConceptKind.Action, confidence = 0.7f)
            val concept = if (negated) base.copy(key = "not:${base.key}", text = "not ${base.text}", negated = true) else base
            out.add(concept)
            if (!negated && resolved != null) broaderOf(resolved, phrasal, technical, MemoryConceptKind.Action).forEach(out::add)
            hits += VerbHit(i, end, concept, tags[i])
        }
        // Nominalizations imply their verb ("the deletion of the cache" -> delete).
        chunks.filter { !it.code && it.concept.sense?.startsWith("wn:") == true && NOMINAL_SUFFIX.containsMatchIn(it.concept.text) }.forEach { chunk ->
            val synset = chunk.concept.sense!!.removePrefix("wn:").toInt()
            val stem = chunk.concept.text.substringAfterLast(' ').take(4)
            wordNet.derivations(synset).asIterable().firstNotNullOfOrNull { target ->
                wordNet.lemmas(target).firstOrNull { it.startsWith(stem) }?.let { target to it }
            }?.let { (target, verb) ->
                out.add(MemoryConcept("v#$target", verb, MemoryConceptKind.Action, impliedBy = MemoryImpliedBy.Derivation, confidence = 0.55f, sense = "wn:$target"))
            }
        }
        return hits
    }

    private fun nextIsVerb(i: Int, tags: List<String>): Boolean =
        (i + 1 until minOf(tags.size, i + 4)).any { tags[it].startsWith("VB") }

    private fun verbLemma(word: String): String? {
        val lower = word.lowercase()
        if (lower.length < 2 || !lower.all { it.isLetter() || it == '-' }) return null
        wordNet.baseForms(lower, WordNetLexicon.Pos.Verb).firstOrNull()?.let { return it }
        // Jargon WordNet lacks: rebased, deduped, refactoring.
        JARGON_SUFFIXES.forEach { (suffix, ending) ->
            if (lower.endsWith(suffix) && lower.length > suffix.length + 2) {
                val base = lower.dropLast(suffix.length) + ending
                if (MemoryTechnicalLexicon.knowsVerb(base)) return base
                if (base.length > 2 && base[base.lastIndex] == base[base.lastIndex - 1]) {
                    val undoubled = base.dropLast(1)
                    if (MemoryTechnicalLexicon.knowsVerb(undoubled)) return undoubled
                }
            }
        }
        return if (MemoryTechnicalLexicon.knowsVerb(lower)) lower else null
    }

    /** NegEx-style: a trigger within five tokens before the verb, with no clause boundary between. */
    private fun negated(verb: Int, tokens: List<Tok>, tags: List<String>): Boolean {
        for (k in verb - 1 downTo maxOf(0, verb - 5)) {
            val word = tokens[k].text.lowercase()
            if (tokens[k].kind == TokKind.Punct && word in CLAUSE_PUNCT || word in CLAUSE_WORDS) return false
            if (word in NEGATION_TRIGGERS) {
                // "not only" is not a negation.
                if (word == "not" && k + 1 < tokens.size && tokens[k + 1].text.lowercase() == "only") return false
                return true
            }
            if ((word == "failed" || word == "fails" || word == "fail" || word == "unable") && k + 1 < verb && tokens[k + 1].text.lowercase() == "to") return true
            if (tags[k].startsWith("VB") && k < verb - 1 && verbLemma(word)?.let { it !in AUXILIARY_VERBS } == true) return false
        }
        return false
    }

    // -----------------------------------------------------------------------------------------
    // Verb-object pairs and pronouns
    // -----------------------------------------------------------------------------------------

    private fun objectOf(verb: VerbHit, sentence: Sentence, tags: List<String>, chunks: List<Chunk>): Chunk? {
        val tokens = sentence.tokens
        var preposition = false
        for (k in verb.end until minOf(tokens.size, verb.end + 5)) {
            chunks.firstOrNull { it.start == k }?.let { return it }
            val tag = tags[k]
            val word = tokens[k].text.lowercase()
            when {
                tag in DETERMINERS || tag == "RB" || tag == "RP" || tag == "CD" -> Unit
                (tag == "IN" || tag == "TO") && !preposition -> preposition = true
                else -> return null
            }
            if (word in CLAUSE_WORDS) return null
        }
        return null
    }

    private fun pronounObject(
        verb: VerbHit,
        sentence: Sentence,
        tags: List<String>,
        chunks: List<Chunk>,
        subjects: List<Chunk>,
        history: ArrayDeque<List<Chunk>>,
        mentions: Map<String, Int>,
    ): Chunk? {
        val tokens = sentence.tokens
        val k = verb.end
        if (k >= tokens.size) return null
        val pronoun = tokens[k].text.lowercase()
        if (pronoun !in RESOLVABLE_PRONOUNS || tags[k] != "PRP") return null
        val plural = pronoun != "it"
        val candidates = mutableListOf<Pair<Chunk, Int>>()
        history.forEachIndexed { age, past ->
            val distance = history.size - age
            past.forEach { candidates += it to (100 - 30 * distance + if (it.subject) 40 else 0) }
        }
        // Same sentence: nearer is more salient, and a clause subject (a phrase right before a verb) more so.
        val before = chunks.filter { it.end <= verb.start }
        before.forEachIndexed { rank, chunk ->
            val clauseSubject = chunk in subjects ||
                (chunk.end < tokens.size && (tags[chunk.end].startsWith("VB") || tags[chunk.end] == "MD"))
            val boundaries = (chunk.end until verb.start).count { k ->
                val word = tokens[k].text.lowercase()
                tokens[k].kind == TokKind.Punct && word in CLAUSE_PUNCT || word in CLAUSE_WORDS
            }
            candidates += chunk to (100 + 5 * rank + (if (clauseSubject) 40 else 0) - 15 * boundaries)
        }
        val scored = candidates
            .filter { (chunk, _) -> chunk.plural == plural || chunk.code }
            .map { (chunk, base) -> chunk to base + (if (chunk.code) 5 else 0) + 10 * ((mentions[chunk.concept.key] ?: 1) - 1) }
            .sortedByDescending { it.second }
        val best = scored.firstOrNull() ?: return null
        val runnerUp = scored.drop(1).firstOrNull { it.first.concept.key != best.first.concept.key }
        if (runnerUp != null && runnerUp.second >= best.second * 0.9) return null
        return best.first.copy(start = k, end = k + 1)
    }

    private fun passiveSubject(verb: VerbHit, sentence: Sentence, tags: List<String>, chunks: List<Chunk>): Chunk? {
        if (verb.tag != "VBN") return null
        val tokens = sentence.tokens
        val auxiliary = (verb.start - 1 downTo maxOf(0, verb.start - 3)).firstOrNull { tokens[it].text.lowercase() in BE_FORMS } ?: return null
        return chunks.lastOrNull { it.end <= auxiliary }
    }

    // -----------------------------------------------------------------------------------------
    // Acronyms defined in the text (Schwartz & Hearst 2003)
    // -----------------------------------------------------------------------------------------

    private fun definedAcronyms(text: String): Map<String, String> {
        val out = HashMap<String, String>()
        ACRONYM_DEFINITION.findAll(text).forEach { match ->
            val short = match.groupValues[2]
            if (short.length !in 2..10 || short.none(Char::isUpperCase)) return@forEach
            val words = match.groupValues[1].trim().split(Regex("\\s+"))
            val window = words.takeLast(minOf(short.length + 5, short.length * 2))
            longForm(short, window.joinToString(" "))?.let { out[short.lowercase()] = it.lowercase() }
        }
        return out
    }

    /** Matches the short form's characters right to left in [candidate]; the first must start a word. */
    private fun longForm(short: String, candidate: String): String? {
        var s = short.length - 1
        var l = candidate.length - 1
        while (s >= 0) {
            val c = short[s].lowercaseChar()
            if (!c.isLetterOrDigit()) {
                s--
                continue
            }
            while (l >= 0 && (candidate[l].lowercaseChar() != c || (s == 0 && l > 0 && candidate[l - 1].isLetterOrDigit()))) l--
            if (l < 0) return null
            l--
            s--
        }
        val start = candidate.lastIndexOf(' ', l) + 1
        return candidate.substring(start).trim().takeIf { it.isNotEmpty() && it.length > short.length }
    }

    // -----------------------------------------------------------------------------------------

    private class ConceptCollector {
        private val byKey = LinkedHashMap<String, MemoryConcept>()

        fun add(concept: MemoryConcept) {
            val existing = byKey[concept.key]
            byKey[concept.key] = when {
                existing == null -> concept
                // An explicit mention outranks an implied one; aliases accumulate.
                else -> {
                    val keep = if (existing.impliedBy != null && concept.impliedBy == null) concept else existing
                    keep.copy(
                        aliases = (existing.aliases + concept.aliases).distinct().filter { it != keep.text.lowercase() },
                        confidence = maxOf(existing.confidence, concept.confidence),
                    )
                }
            }
        }

        fun concepts(): List<MemoryConcept> = byKey.values.toList()
    }

    private companion object {
        private val TOKEN_KINDS = listOf(
            TokKind.Code, TokKind.Url, TokKind.Ref, TokKind.Task, TokKind.Path, TokKind.Version, TokKind.Hash,
            TokKind.Flag, TokKind.Code, TokKind.Code, TokKind.Word, TokKind.Number,
        )
        private val TOKEN = Regex(
            listOf(
                "(`[^`\\n]+`)",
                "(https?://[^\\s<>\"'`]+)",
                "((?:[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)?#\\d+\\b)",
                "((?<![\\w:]):[A-Za-z][\\w-]*(?::[A-Za-z][\\w-]*)+)",
                "((?:~|\\.{1,2})?/?(?:[\\w.@-]+/)+[\\w.@-]*[\\w@-]|\\b[\\w-]+\\.(?:" +
                    MemoryTechnicalLexicon.extensions.keys.sortedByDescending { it.length }.joinToString("|") + ")\\b)",
                "(\\bv?\\d+\\.\\d+(?:\\.\\d+){1,3}(?:-[\\w.]+)?\\b|\\bv\\d+(?:\\.\\d+)?\\b)",
                "(\\b[0-9a-f]{7,40}\\b)",
                "((?<!\\S)--?[A-Za-z][\\w-]*)",
                "([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)+(?:\\(\\))?|[A-Za-z_$][\\w$]*\\(\\))",
                "(\\b[a-z]+[A-Z][\\w]*\\b|\\b[A-Z][a-z0-9]+[A-Z][\\w]*\\b|\\b[A-Z]\\w*(?:Exception|Error)\\b|\\b[A-Za-z][A-Za-z0-9]*_[\\w]+\\b)",
                "([A-Za-z]+(?:-[A-Za-z]+)*(?:'[A-Za-z]+)?)",
                "(\\d+(?:[.,]\\d+)?%?)",
                "([^\\s\\w])",
            ).joinToString("|"),
        )
        private val CLITIC = Regex("[a-z]+'(s|re|ll|ve|m|d)")
        private val PARAGRAPH = Regex("\\n\\s*\\n")
        private val LINE_ENDS_UNIT = Regex("[.!?:]\\s*$|^\\s*(?:[-*+]|\\d+[.)])\\s|^#")
        private val LIST_ITEM = Regex("^\\s*(?:[-*+]|\\d+[.)])\\s|^\\s*#|^\\s*[A-Z]")
        private val MARKDOWN_PREFIX = Regex("^\\s*(?:#{1,6}\\s+|[-*+]\\s+|\\d+[.)]\\s+|>\\s*)")
        private val STACK_FRAME = Regex("^\\s+at\\s+[\\w$.<>]+\\(|^\\s*File \".*\", line \\d+|^\\s*Caused by:|^\\s*\\.\\.\\. \\d+ more")
        private val EXCEPTION_NAME = Regex("\\b[A-Z][\\w$]*(?:Exception|Error)\\b")
        private val PATH_LIKE = Regex("(?:[\\w.@-]+/)+[\\w.@-]+|[\\w-]+\\.[A-Za-z]{1,10}")
        private val GITHUB = Regex("github\\.com/([\\w.-]+)/([\\w.-]+?)(?:\\.git)?(?:/(pull|issues)/(\\d+))?(?:[/?#]|$)")
        private val ACRONYM_DEFINITION = Regex("([A-Za-z][\\w-]*(?:\\s+[\\w-]+){0,9})\\s*\\(([A-Za-z][A-Za-z0-9-]{1,9})\\)")
        private val NOMINAL_SUFFIX = Regex("(tion|sion|ment|ance|ence|ure|al)$")
        private val SENTENCE_PUNCT = setOf(".", "!", "?")
        private val CLAUSE_PUNCT = setOf(",", ";", ":", "(", ")")
        private val CLAUSE_WORDS = setOf("but", "however", "although", "though", "whereas", "while", "because", "so", "which", "that")
        private val ABBREVIATIONS = setOf("e.g", "i.e", "etc", "vs", "approx", "cf", "fig", "no", "mr", "mrs", "dr", "st", "jr", "sr", "inc", "ltd", "eg", "ie", "al")
        private val NEGATION_TRIGGERS = setOf("not", "n't", "never", "no", "cannot", "without", "neither", "nor")
        private val PARTICLES = setOf("up", "down", "out", "back", "off", "over", "in", "on", "away", "through", "around")
        private val DETERMINERS = setOf("DT", "PDT", "PRP$", "WP$", "POS")
        private val PLURAL_ACRONYM = Regex("[A-Z]{2,6}s")
        private val HAVE_FORMS = setOf("has", "have", "had", "having", "'ve")
        private val BE_FORMS = setOf("is", "was", "were", "are", "be", "been", "being", "got", "gets", "get", "'s", "'re")
        private val RESOLVABLE_PRONOUNS = setOf("it", "them", "they")
        private val AUXILIARY_VERBS = setOf("be", "have", "do", "will", "shall", "may", "might", "must", "can", "could", "would", "should", "let", "seem", "become")
        private val LIGHT_VERBS = setOf("get", "go", "come", "want", "try", "need", "keep", "start", "stop", "help")
        private val STOP_NOUNS = setOf("thing", "things", "way", "lot", "bit", "something", "anything", "nothing", "everything", "stuff", "one", "ones", "time", "kind", "sort", "part", "case", "point")
        private val ABSTRACT_TOPS = setOf(
            "entity", "physical entity", "abstraction", "abstract entity", "object", "whole", "thing", "act", "event",
            "psychological feature", "attribute", "measure", "group", "relation", "communication", "state", "matter",
            "artifact", "instrumentality", "human action", "change", "cognition", "content", "location",
        )
        private val NON_TECHNICAL_LEXNAMES = setOf(
            "noun.animal", "noun.plant", "noun.food", "noun.body", "noun.person", "noun.feeling", "noun.motive",
            "verb.body", "verb.consumption", "verb.emotion", "verb.weather",
        )
        private val JARGON_SUFFIXES = listOf("ing" to "", "ing" to "e", "ed" to "", "ed" to "e", "es" to "", "s" to "")

        private val IDENTIFIER_BOUNDARY = Regex("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])|[_\\-.$]+")

        fun identifierWords(identifier: String): List<String> =
            identifier.split(IDENTIFIER_BOUNDARY).map(String::lowercase).filter { it.length > 1 && it.any(Char::isLetter) }
    }
}
