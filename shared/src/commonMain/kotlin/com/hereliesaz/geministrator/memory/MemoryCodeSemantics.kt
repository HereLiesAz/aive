package com.hereliesaz.geministrator.memory

/**
 * Cheap deterministic candidates for the noun/verb micro-agents.
 *
 * These hints are not authoritative classifications. They reduce the amount of syntax discovery
 * a small on-device model has to do so it can spend its bounded context on semantic decisions.
 * The same source token may legitimately appear as both an entity and an action signal.
 */
data class MemoryCodeSemanticHints(
    val nounCandidates: List<String> = emptyList(),
    val verbCandidates: List<String> = emptyList(),
)

internal fun MemoryWorkPacket.withCodeSemanticHints(role: MemoryMicroAgentRole): MemoryWorkPacket {
    if (role != MemoryMicroAgentRole.NounTagger && role != MemoryMicroAgentRole.VerbTagger) return this
    return copy(
        items = items.map(MemoryWorkItem::withCodeSemanticHints),
        neighborhood = neighborhood.map(MemoryWorkItem::withCodeSemanticHints),
    )
}

private fun MemoryWorkItem.withCodeSemanticHints(): MemoryWorkItem {
    val hints = extractCodeSemanticHints(text)
    if (hints.nounCandidates.isEmpty() && hints.verbCandidates.isEmpty()) return this
    return copy(
        metadata = metadata + buildMap {
            if (hints.nounCandidates.isNotEmpty()) {
                put(CODE_NOUN_HINTS, hints.nounCandidates.joinToString(HINT_SEPARATOR))
            }
            if (hints.verbCandidates.isNotEmpty()) {
                put(CODE_VERB_HINTS, hints.verbCandidates.joinToString(HINT_SEPARATOR))
            }
        },
    )
}

internal fun extractCodeSemanticHints(text: String): MemoryCodeSemanticHints {
    if (text.isBlank()) return MemoryCodeSemanticHints()

    val nouns = linkedSetOf<String>()
    val verbs = linkedSetOf<String>()

    BACKTICK.findAll(text).forEach { match ->
        match.groupValues[1].trim().takeIf(String::isNotEmpty)?.let(nouns::add)
    }

    DECLARATION.findAll(text).forEach { match ->
        match.groupValues[2].trim().takeIf(String::isNotEmpty)?.let(nouns::add)
    }

    CALL.findAll(text).forEach { match ->
        val callable = match.groupValues[1].trim()
        if (callable.isEmpty() || callable in CONTROL_WORDS) return@forEach
        nouns += callable
        identifierTerms(callable).forEach { term ->
            if (term in ACTION_TERMS) verbs += normalizeAction(term)
        }
    }

    PATH.findAll(text).forEach { match ->
        match.value.trim().takeIf(String::isNotEmpty)?.let(nouns::add)
    }

    URL.findAll(text).forEach { match -> nouns += match.value }

    HTTP.findAll(text).forEach { match ->
        verbs += match.groupValues[1].lowercase()
        match.groupValues.getOrNull(2)?.trim()?.takeIf(String::isNotEmpty)?.let(nouns::add)
    }

    SHELL_ACTION.findAll(text).forEach { match ->
        normalizeAction(match.groupValues[1]).takeIf(String::isNotEmpty)?.let(verbs::add)
    }

    IDENTIFIER.findAll(text).forEach { match ->
        val identifier = match.value
        if (!looksCodeLike(identifier)) return@forEach
        nouns += identifier
        identifierTerms(identifier).forEach { term ->
            if (term in ACTION_TERMS) verbs += normalizeAction(term)
        }
    }

    text.memoryActionWords().forEach(verbs::add)

    return MemoryCodeSemanticHints(
        nounCandidates = nouns.filter(String::isNotBlank).take(MAX_HINTS),
        verbCandidates = verbs.filter(String::isNotBlank).take(MAX_HINTS),
    )
}

private fun String.memoryActionWords(): List<String> {
    val found = linkedSetOf<String>()
    ACTION_WORD.findAll(lowercase()).forEach { match ->
        normalizeAction(match.value).takeIf(String::isNotEmpty)?.let(found::add)
    }
    return found.toList()
}

private fun identifierTerms(identifier: String): List<String> {
    val expanded = identifier
        .replace('.', ' ')
        .replace('/', ' ')
        .replace('-', ' ')
        .replace('_', ' ')
        .replace(Regex("([a-z0-9])([A-Z])"), "$1 $2")
        .lowercase()
    return expanded.split(Regex("\\s+"))
        .map(String::trim)
        .filter { it.length > 1 }
}

private fun looksCodeLike(value: String): Boolean =
    value.any(Char::isUpperCase) || '_' in value || '.' in value || value.endsWith("Id") || value.endsWith("DTO")

private fun normalizeAction(value: String): String = when (value.lowercase()) {
    "creates", "created", "creating" -> "create"
    "reads", "reading" -> "read"
    "writes", "wrote", "writing" -> "write"
    "updates", "updated", "updating" -> "update"
    "deletes", "deleted", "deleting", "remove", "removes", "removed" -> "delete"
    "fetches", "fetched", "fetching", "gets", "getting" -> "fetch"
    "parses", "parsed", "parsing" -> "parse"
    "validates", "validated", "validating" -> "validate"
    "serializes", "serialized", "serializing", "encode", "encodes", "encoded" -> "serialize"
    "deserializes", "deserialized", "deserializing", "decode", "decodes", "decoded" -> "deserialize"
    "merges", "merged", "merging" -> "merge"
    "compiles", "compiled", "compiling", "build", "builds", "building", "assemble", "assembles" -> "build"
    "tests", "tested", "testing", "verify", "verifies", "verified", "verifying" -> "test"
    "deploys", "deployed", "deploying", "release", "releases", "released" -> "deploy"
    "commits", "committed", "committing" -> "commit"
    "maps", "mapped", "mapping" -> "map"
    "filters", "filtered", "filtering" -> "filter"
    "sorts", "sorted", "sorting" -> "sort"
    "loads", "loaded", "loading" -> "load"
    "saves", "saved", "saving", "persist", "persists", "persisted" -> "persist"
    "send", "sends", "sent" -> "send"
    "receive", "receives", "received" -> "receive"
    else -> value.lowercase()
}

internal const val CODE_NOUN_HINTS: String = "codeNounHints"
internal const val CODE_VERB_HINTS: String = "codeVerbHints"
internal const val HINT_SEPARATOR: String = "\u001f"

private const val MAX_HINTS = 64

private val BACKTICK = Regex("`([^`\\n]{1,160})`")
private val DECLARATION = Regex(
    """\b(class|interface|object|enum|data\s+class|fun|val|var|def|function|struct|type|const|let)\s+([A-Za-z_$][A-Za-z0-9_$]*)""",
    RegexOption.IGNORE_CASE,
)
private val CALL = Regex("""\b([A-Za-z_$][A-Za-z0-9_$.]*)\s*\(""")
private val PATH = Regex("""(?:^|\s)([A-Za-z0-9_.-]+/)+(?:[A-Za-z0-9_.-]+)""")
private val URL = Regex("""https?://[^\s)'\"]+""")
private val HTTP = Regex("""\b(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS)\s+([^\s]+)?""", RegexOption.IGNORE_CASE)
private val SHELL_ACTION = Regex(
    """\b(git\s+(?:add|commit|merge|rebase|checkout|switch|push|pull|fetch)|gradle|gradlew|mvn|npm|pnpm|yarn|cargo|pytest|test|build|deploy|release|compile|assemble)\b""",
    RegexOption.IGNORE_CASE,
)
private val IDENTIFIER = Regex("""\b[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)*\b""")
private val ACTION_WORD = Regex(
    """\b(create|creates|created|creating|read|reads|reading|write|writes|wrote|writing|update|updates|updated|updating|delete|deletes|deleted|deleting|remove|removes|removed|fetch|fetches|fetched|fetching|get|gets|getting|parse|parses|parsed|parsing|validate|validates|validated|validating|serialize|serializes|serialized|serializing|deserialize|deserializes|deserialized|deserializing|encode|encodes|encoded|decode|decodes|decoded|merge|merges|merged|merging|compile|compiles|compiled|compiling|build|builds|building|assemble|assembles|test|tests|tested|testing|verify|verifies|verified|verifying|deploy|deploys|deployed|deploying|release|releases|released|commit|commits|committed|committing|map|maps|mapped|mapping|filter|filters|filtered|filtering|sort|sorts|sorted|sorting|load|loads|loaded|loading|save|saves|saved|saving|persist|persists|persisted|send|sends|sent|receive|receives|received)\b""",
    RegexOption.IGNORE_CASE,
)
private val ACTION_TERMS = ACTION_WORD.pattern
    .removePrefix("\\b(")
    .removeSuffix(")\\b")
    .split('|')
    .map(String::lowercase)
    .toSet()
private val CONTROL_WORDS = setOf("if", "for", "while", "when", "switch", "catch", "return", "super", "this")
