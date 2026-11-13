package com.hereliesaz.geministrator.tour

import com.hereliesaz.geministrator.control.ControlRedaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One broken rule: [rule] is the id (L1..L11), [message] says what to fix and is handed back to the writer. */
data class Violation(val rule: String, val message: String)

/** What the validator knows about the step being checked and the app. Pure data; nothing is read at check time. */
data class LessonContext(
    /** The target the plan gave this step. */
    val plannedTarget: String,
    /** The registry, as rows with their visible labels. */
    val targets: List<TargetRow>,
    /** Every exact visible string of the app (the UI word index). */
    val uiWords: Set<String>,
    /** Targets introduced by earlier steps of the plan. */
    val introduced: Set<String>,
    /** True for the first lesson, which must carry the consent facts. */
    val firstLesson: Boolean = false,
    /** The number of hives this device has, when known; a claim about it must match. Null: no such claim is allowed. */
    val knownHiveCount: Int? = null,
)

sealed interface LessonValidation {
    data class Valid(val payload: LessonPayload) : LessonValidation
    data class Invalid(val violations: List<Violation>) : LessonValidation
}

/**
 * Checks one generated lesson against the real registry and the real strings, with no model and no network.
 * Rules (the tutorial design, 6.3): L1 parse, L2 target, L3 one checkpoint, L4 real words, L5 introduced controls only,
 * L6 sizes, L7 vocabulary, L8 safety, L9 consent facts on the first lesson, L10 honest facts, L11 a check-in is not a quiz.
 */
object LessonValidator {
    private val json = Json { isLenient = false }
    private val allowedKeys = setOf("target", "sentence", "expect", "explain", "ifMissed", "checkIn", "anotherWay", "smaller", "bridge")

    fun validate(text: String, ctx: LessonContext): LessonValidation {
        val parsed = parse(text)
        val payload = parsed.first ?: return LessonValidation.Invalid(parsed.second)
        val violations = buildList {
            addAll(checkTarget(payload, ctx))
            addAll(checkExpect(payload, ctx))
            addAll(checkSizes(payload, text.length))
            addAll(checkWords(payload, ctx))
            addAll(checkVocabulary(payload))
            addAll(checkSafety(payload))
            if (ctx.firstLesson) addAll(checkConsent(payload))
            addAll(checkFacts(payload, ctx))
            addAll(checkCheckIn(payload, ctx))
        }
        return if (violations.isEmpty()) LessonValidation.Valid(payload) else LessonValidation.Invalid(violations)
    }

    // L1 -----------------------------------------------------------------------------------------------------------

    private fun parse(text: String): Pair<LessonPayload?, List<Violation>> {
        fun fail(message: String): Pair<LessonPayload?, List<Violation>> = null to listOf(Violation("L1", message))
        if (text.length > TeachingLimits.PAYLOAD_MAX_CHARS) {
            return fail("The reply is longer than ${TeachingLimits.PAYLOAD_MAX_CHARS} characters.")
        }
        val body = unfence(text) ?: return fail("Return exactly one JSON object and nothing else.")
        val element: JsonElement = try {
            json.parseToJsonElement(body)
        } catch (_: Exception) {
            return fail("The reply is not valid JSON.")
        }
        val obj = element as? JsonObject ?: return fail("The reply must be one JSON object.")
        val unknown = obj.keys - allowedKeys
        if (unknown.isNotEmpty()) return fail("Unknown keys: ${unknown.sorted().joinToString(", ")}.")
        fun str(key: String): String? = (obj[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val missing = listOf("target", "sentence", "explain", "ifMissed", "checkIn", "anotherWay").filter { str(it) == null }
        if (missing.isNotEmpty()) return fail("Missing or not text: ${missing.joinToString(", ")}.")
        val expectObj = obj["expect"] as? JsonObject ?: return fail("expect must be exactly one object.")
        val kindText = (expectObj["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val kind = kindText?.let { ExpectKind.fromWire(it) } ?: return fail("expect.type is not one of the known kinds.")
        val expectTarget = (expectObj["target"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val extraExpect = expectObj.keys - setOf("type", "target")
        if (extraExpect.isNotEmpty()) return fail("expect has unknown keys: ${extraExpect.sorted().joinToString(", ")}.")
        val smallerEl = obj["smaller"] as? JsonArray ?: return fail("smaller must be a list of two short texts.")
        val smaller = mutableListOf<String>()
        for (item in smallerEl) {
            val textItem = (item as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return fail("smaller holds only texts.")
            smaller.add(textItem)
        }
        val bridgeEl = obj["bridge"]
        val bridge: String? = when {
            bridgeEl == null || bridgeEl is JsonNull -> null
            bridgeEl is JsonPrimitive && bridgeEl.isString -> bridgeEl.content
            else -> return fail("bridge must be text.")
        }
        val payload = LessonPayload(
            target = str("target")!!,
            sentence = str("sentence")!!,
            expect = TourExpect(kind, expectTarget),
            explain = str("explain")!!,
            ifMissed = str("ifMissed")!!,
            checkIn = str("checkIn")!!,
            anotherWay = str("anotherWay")!!,
            smaller = smaller,
            bridge = bridge,
        )
        return payload to emptyList()
    }

    /** The JSON text, from the reply as it is or from one fenced block; null when anything else surrounds it. */
    private fun unfence(text: String): String? {
        val t = text.trim()
        if (!t.startsWith("```")) return t
        val firstBreak = t.indexOf('\n')
        if (firstBreak < 0 || !t.endsWith("```")) return null
        val inner = t.substring(firstBreak + 1, t.length - 3).trim()
        return if (inner.contains("```")) null else inner
    }

    // L2, L3 -------------------------------------------------------------------------------------------------------

    private fun checkTarget(p: LessonPayload, ctx: LessonContext): List<Violation> = buildList {
        if (p.target != ctx.plannedTarget) add(Violation("L2", "target must be ${ctx.plannedTarget}, the step's own target."))
        if (ctx.targets.none { it.id == p.target }) add(Violation("L2", "target ${p.target} is not a registered control."))
    }

    private fun checkExpect(p: LessonPayload, ctx: LessonContext): List<Violation> = buildList {
        val row = ctx.targets.firstOrNull { it.id == p.target }
        if (row != null && p.expect.kind !in row.observable) {
            add(Violation("L3", "${p.expect.kind.wire} cannot be observed for ${p.target}."))
        }
        val onTarget = p.expect.kind == ExpectKind.Tapped || p.expect.kind == ExpectKind.Opened
        if (onTarget && p.expect.target != p.target) add(Violation("L3", "expect.target must equal target for ${p.expect.kind.wire}."))
        if (!onTarget && p.expect.target != null) add(Violation("L3", "expect.target is only for tapped and opened."))
    }

    // L6 -----------------------------------------------------------------------------------------------------------

    private fun words(text: String): Int = text.split(Regex("\\s+")).count { it.isNotEmpty() }

    private fun checkSizes(p: LessonPayload, total: Int): List<Violation> = buildList {
        if (p.sentence.isBlank() || p.sentence.length > TeachingLimits.SENTENCE_MAX_CHARS) {
            add(Violation("L6", "sentence must be 1 to ${TeachingLimits.SENTENCE_MAX_CHARS} characters."))
        }
        if (words(p.explain) > TeachingLimits.EXPLAIN_MAX_WORDS) add(Violation("L6", "explain must be at most ${TeachingLimits.EXPLAIN_MAX_WORDS} words."))
        if (words(p.ifMissed) > TeachingLimits.IF_MISSED_MAX_WORDS) add(Violation("L6", "ifMissed must be at most ${TeachingLimits.IF_MISSED_MAX_WORDS} words."))
        if (words(p.anotherWay) > TeachingLimits.ANOTHER_WAY_MAX_WORDS) add(Violation("L6", "anotherWay must be at most ${TeachingLimits.ANOTHER_WAY_MAX_WORDS} words."))
        if (p.smaller.size != TeachingLimits.SMALLER_STEPS) add(Violation("L6", "smaller must hold exactly ${TeachingLimits.SMALLER_STEPS} items."))
        if (p.smaller.any { words(it) > TeachingLimits.SMALLER_STEP_MAX_WORDS }) {
            add(Violation("L6", "each smaller item must be at most ${TeachingLimits.SMALLER_STEP_MAX_WORDS} words."))
        }
        p.bridge?.let { if (words(it) > TeachingLimits.BRIDGE_MAX_WORDS) add(Violation("L6", "bridge must be at most ${TeachingLimits.BRIDGE_MAX_WORDS} words.")) }
        if (total > TeachingLimits.PAYLOAD_MAX_CHARS) add(Violation("L6", "the payload is longer than ${TeachingLimits.PAYLOAD_MAX_CHARS} characters."))
    }

    // L4, L5 -------------------------------------------------------------------------------------------------------

    /** Every text the keeper may read from the payload, except the check-in line (L11 has its own rules). */
    private fun teaching(p: LessonPayload): List<String> =
        listOf(p.sentence, p.explain, p.ifMissed, p.anotherWay) + p.smaller + listOfNotNull(p.bridge)

    private val quoted = Regex("""\*\*([^*]+)\*\*|`([^`]+)`|"([^"]+)"|“([^”]+)”""")

    private fun checkWords(p: LessonPayload, ctx: LessonContext): List<Violation> = buildList {
        val labels = ctx.targets.associate { it.id to it.label }
        val known = ctx.uiWords + labels.values
        val texts = teaching(p) + p.checkIn
        for (text in texts) {
            for (m in quoted.findAll(text)) {
                val span = m.groupValues.drop(1).first { it.isNotEmpty() }.trim()
                if (span !in known) add(Violation("L4", "\"$span\" is not a word the app shows. Name controls exactly as the UI words list spells them."))
            }
            for (label in labels.values.distinct()) {
                val tail = Regex(Regex.escape(label) + """\s+(tab|button|screen|page|pill|menu|icon)\b""", RegexOption.IGNORE_CASE).find(text)
                if (tail != null) add(Violation("L4", "Write $label, not \"${tail.value}\"."))
            }
        }
        // L5: only this step's control, controls introduced earlier, and controls always on screen may be named.
        val allowed = ctx.introduced + p.target + ctx.targets.filter { it.alwaysVisible }.map { it.id }
        for (row in ctx.targets) {
            if (row.id in allowed) continue
            if (texts.any { containsWord(it, row.label) }) {
                add(Violation("L5", "${row.label} has not been introduced yet; do not name it."))
            }
        }
    }

    /** True when [word] occurs in [text] bounded by non-letters (case-sensitive; no regex lookbehind, for JS and Wasm). */
    internal fun containsWord(text: String, word: String): Boolean {
        if (word.isEmpty()) return false
        var from = 0
        while (true) {
            val at = text.indexOf(word, from)
            if (at < 0) return false
            val before = if (at == 0) ' ' else text[at - 1]
            val after = if (at + word.length >= text.length) ' ' else text[at + word.length]
            if (!before.isLetter() && !after.isLetter()) return true
            from = at + 1
        }
    }

    // L7 -----------------------------------------------------------------------------------------------------------

    /** The words the owner retired from everything the keeper reads (the same set as VocabularyInStringsTest). */
    internal val retired = Regex("""\b(timelines?|providers?|inbox(es)?|hall[ -]monitors?|drifters?|build)\b""", RegexOption.IGNORE_CASE)

    private fun checkVocabulary(p: LessonPayload): List<Violation> = buildList {
        for (text in teaching(p) + p.checkIn) {
            retired.find(text)?.let { add(Violation("L7", "\"${it.value}\" is a retired word; use the current vocabulary.")) }
        }
    }

    // L8 -----------------------------------------------------------------------------------------------------------

    private val url = Regex("""(?i)\b(?:https?://|www\.)\S+""")
    private val html = Regex("""<\s*/?\s*[A-Za-z!][^>]*>""")
    private val mdLink = Regex("""\[[^\]]*\]\([^)]*\)""")
    private val credentialAsk = Regex("""(?i)\b(?:type|paste|enter|share|send|give)\b[^.?!]{0,40}\b(?:api key|key|passphrase|token|secret|pass[a-z]{3,5})\b""")

    private fun checkSafety(p: LessonPayload): List<Violation> = buildList {
        for (text in teaching(p) + p.checkIn + p.target) {
            if (url.containsMatchIn(text)) add(Violation("L8", "No web addresses."))
            if (html.containsMatchIn(text)) add(Violation("L8", "No HTML."))
            if (mdLink.containsMatchIn(text)) add(Violation("L8", "No links."))
            if (text.contains("```")) add(Violation("L8", "No code fences."))
            if (credentialAsk.containsMatchIn(text)) add(Violation("L8", "Never ask the keeper to type or paste a key or a login."))
            if (ControlRedaction.scrub(text) != text) add(Violation("L8", "The text contains something shaped like a key."))
        }
    }.distinct()

    // L9 -----------------------------------------------------------------------------------------------------------

    private val consentPhrases = listOf("summary", "goals", "keys", "free sources may log")

    private fun checkConsent(p: LessonPayload): List<Violation> {
        val all = teaching(p).joinToString(" ").lowercase()
        val missing = consentPhrases.filter { it !in all }
        return if (missing.isEmpty()) emptyList()
        else listOf(Violation("L9", "The first lesson must say what is read: include ${missing.joinToString(", ") { "\"$it\"" }}."))
    }

    // L10 ----------------------------------------------------------------------------------------------------------

    private val hiveClaim = Regex("""(?i)\byou (?:have|own|made|created|started) (\d+) hives?\b""")

    private fun checkFacts(p: LessonPayload, ctx: LessonContext): List<Violation> = buildList {
        for (text in teaching(p) + p.checkIn) {
            for (m in hiveClaim.findAll(text)) {
                val n = m.groupValues[1].toIntOrNull()
                if (ctx.knownHiveCount == null || n != ctx.knownHiveCount) {
                    add(Violation("L10", "Do not claim how many hives the keeper has; it is not what the facts say."))
                }
            }
        }
    }.distinct()

    // L11 ----------------------------------------------------------------------------------------------------------

    private val comfortCues = listOf("with me", "follow", "make sense", "clear", "okay", "slow", "lost", "ready", "comfortable", "so far")
    private val knowledgeWords = Regex("""(?i)\b(what|which|how many|why|who|name|list|explain|define)\b""")

    private fun checkCheckIn(p: LessonPayload, ctx: LessonContext): List<Violation> = buildList {
        val c = p.checkIn.trim()
        if (c.isEmpty() || c.length > TeachingLimits.CHECKIN_LINE_MAX_CHARS) {
            add(Violation("L11", "checkIn must be 1 to ${TeachingLimits.CHECKIN_LINE_MAX_CHARS} characters."))
        }
        if (!c.endsWith("?") || c.count { it == '?' || it == '.' || it == '!' } != 1) {
            add(Violation("L11", "checkIn must be one short question ending in \"?\"."))
        }
        if (comfortCues.none { c.lowercase().contains(it) }) {
            add(Violation("L11", "checkIn must only ask how the keeper feels (for example whether they are with you so far)."))
        }
        if (knowledgeWords.containsMatchIn(c)) add(Violation("L11", "checkIn must not ask a knowledge question."))
        if (ctx.targets.any { containsWord(c, it.label) || c.contains(it.id) } || ctx.uiWords.any { it.length > 3 && containsWord(c, it) }) {
            add(Violation("L11", "checkIn must not name a control."))
        }
        // The alternatives may name no control but this step's own (L5 already stops controls not yet introduced).
        val own = ctx.targets.firstOrNull { it.id == p.target }?.label
        for (text in listOf(p.anotherWay) + p.smaller) {
            val other = ctx.targets.firstOrNull { it.label != own && containsWord(text, it.label) }
            if (other != null) add(Violation("L11", "anotherWay and smaller may name only this step's control, not ${other.label}."))
        }
    }.distinct()
}

/** Why a bounded write-and-check loop ended. */
sealed interface LessonWriteOutcome {
    data class Accepted(val payload: LessonPayload, val attempts: Int) : LessonWriteOutcome

    /** Every attempt failed; [violations] are the last attempt's, for the Message the keeper may see. */
    data class Exhausted(val attempts: Int, val violations: List<Violation>) : LessonWriteOutcome
}

/** The model side of the loop: given what was wrong with the last attempt, write the lesson text again. */
fun interface LessonWriter {
    suspend fun write(previousViolations: List<Violation>): String
}

/**
 * The bounded repair loop: ask the writer, check with [LessonValidator], and on failure ask again with the violations,
 * at most [maxAttempts] times. A writer that throws (a Source failing) is not a repair case and propagates. Nothing is
 * produced when every attempt fails: there is no fallback lesson text.
 */
suspend fun writeValidatedLesson(
    ctx: LessonContext,
    writer: LessonWriter,
    maxAttempts: Int = TeachingLimits.LESSON_REPAIRS + 1,
): LessonWriteOutcome {
    var violations: List<Violation> = emptyList()
    for (attempt in 1..maxAttempts) {
        val text = writer.write(violations)
        when (val result = LessonValidator.validate(text, ctx)) {
            is LessonValidation.Valid -> return LessonWriteOutcome.Accepted(result.payload, attempt)
            is LessonValidation.Invalid -> violations = result.violations
        }
    }
    return LessonWriteOutcome.Exhausted(maxAttempts, violations)
}
