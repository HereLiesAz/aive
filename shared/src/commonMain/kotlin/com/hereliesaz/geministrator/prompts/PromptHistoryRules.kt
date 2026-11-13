package com.hereliesaz.geministrator.prompts

import com.hereliesaz.geministrator.control.ControlRedaction
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The kinds of text field that remember what was typed into them. Only these kinds exist, so a field that
 * holds a secret (an API key, a token, a pairing code, a header, the setup passphrase, memory export or
 * import text, script source, a justification) cannot be given a history by accident: there is no kind to pass.
 */
internal enum class PromptKind(val key: String) {
    /** What a hive is fed: the first launch, the feed bar, the launch bar, a continuation, a rerun. */
    Objective("objective"),

    /** Where a swarm works: a repository URL, shorthand or local folder. */
    Repository("repository"),

    /** What a keeper asks the planner to draft a swarm from. */
    SwarmDraft("swarm-draft"),

    /** A hive's name. */
    HiveName("hive-name"),

    /** A message to a step's agent. */
    AgentMessage("agent-message"),

    /** A role's standing instructions. */
    RoleInstructions("role-instr"),
}

/** One remembered text: when it was last used and how often. */
@Serializable
internal data class PromptEntry(val text: String, val usedAtEpochMillis: Long, val count: Int = 1)

/**
 * The pure rules of remembered text: what may be kept, how a list is updated and bounded, and which entries
 * a half-typed value suggests. No storage and no UI, so every rule is a plain test.
 */
internal object PromptHistoryRules {
    const val MAX_ENTRIES = 12
    const val MIN_ENTRY_CHARS = 3

    /** Longer text is a pasted document, not something typed again. */
    const val MAX_ENTRY_CHARS = 600

    /** A serialized list stays under one Preferences value (8,192 characters on the JVM). */
    const val MAX_SERIALIZED_CHARS = 7_000

    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(PromptEntry.serializer())
    private val whitespace = Regex("\\s+")
    private val urlUserInfo = Regex("""[A-Za-z][A-Za-z0-9+.\-]*://[^/\s?#]*@""")

    /** [text] trimmed, with every run of white space made one space. */
    fun normalize(text: String): String = text.trim().replace(whitespace, " ")

    /**
     * Whether [text] may be kept. Text that looks like a secret (a key shape the redactor knows, or a URL
     * carrying a user and password) is refused outright: a masked copy would be useless and the original must
     * not be kept.
     */
    fun isRecordable(text: String, scrub: (String) -> String = ControlRedaction::scrub): Boolean {
        val clean = normalize(text)
        return clean.length in MIN_ENTRY_CHARS..MAX_ENTRY_CHARS &&
            scrub(clean) == clean &&
            !urlUserInfo.containsMatchIn(clean)
    }

    /** [history] with [text] used at [now]: moved to the front when already there, bounded, newest first. */
    fun record(
        history: List<PromptEntry>,
        text: String,
        now: Long,
        scrub: (String) -> String = ControlRedaction::scrub,
    ): List<PromptEntry> {
        if (!isRecordable(text, scrub)) return history
        val clean = normalize(text)
        val folded = clean.lowercase()
        val previous = history.firstOrNull { it.text.lowercase() == folded }
        val entry = PromptEntry(clean, now, (previous?.count ?: 0) + 1)
        var next = (listOf(entry) + history.filter { it.text.lowercase() != folded })
            .sortedWith(compareByDescending<PromptEntry> { it.usedAtEpochMillis }.thenByDescending { it.count })
            .take(MAX_ENTRIES)
        while (next.size > 1 && encode(next).length > MAX_SERIALIZED_CHARS) next = next.dropLast(1)
        return next
    }

    /**
     * The entries [typed] suggests: all of them when it is blank, otherwise those that start with it or have a
     * word that does. The exact current value is left out. Most recent first, then most used; at most [limit].
     */
    fun suggestions(history: List<PromptEntry>, typed: String, limit: Int = 5): List<String> {
        val prefix = normalize(typed).lowercase()
        return history
            .asSequence()
            .filter { entry ->
                val folded = entry.text.lowercase()
                prefix.isEmpty() ||
                    (folded != prefix && (folded.startsWith(prefix) || folded.split(' ').any { it.startsWith(prefix) }))
            }
            .sortedWith(compareByDescending<PromptEntry> { it.usedAtEpochMillis }.thenByDescending { it.count })
            .map { it.text }
            .take(limit)
            .toList()
    }

    /** Whether a field offers, seeds and records at all: never a secret field, never with remembering off. */
    fun shouldOffer(secret: Boolean, enabled: Boolean): Boolean = enabled && !secret

    fun encode(history: List<PromptEntry>): String = json.encodeToString(serializer, history)

    /** The list [encoded] holds, or empty when it is missing or damaged. */
    fun decode(encoded: String?): List<PromptEntry> =
        if (encoded.isNullOrBlank()) emptyList() else runCatching { json.decodeFromString(serializer, encoded) }.getOrDefault(emptyList())
}
