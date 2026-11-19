package com.hereliesaz.geministrator.research

import com.hereliesaz.geministrator.control.ControlRedaction

/** What the queen already has to look at, so a research step is not started with nothing to consult. */
data class ResearchContext(
    val repositoryLinked: Boolean = false,
    /** Servers the keeper connected in Connections. */
    val connectedServers: Int = 0,
    /** The keeper turned on the app's own control interface. */
    val bridgeEnabled: Boolean = false,
    /** A directory lookup exists to ask (still only with the keeper's consent). */
    val lookupConfigured: Boolean = false,
)

private val TRIGGER = Regex(
    """\b(teach|taught|explain|learn|integrat\w*|how do i|how can i|how to|walk me through|show me how|ins and outs|connect (?:to|with))\b""",
    RegexOption.IGNORE_CASE,
)

private val NOT_A_PRODUCT = setOf(
    "i", "i'm", "i'll", "i've", "the", "a", "an", "my", "our", "and", "or", "but", "to", "in", "on", "of", "for", "with",
    "it", "this", "that", "these", "those", "please", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday",
    "sunday", "january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november",
    "december", "english", "spanish", "french", "german",
)

/** A word that names a product, tool or app: capitalised in the middle of a sentence, camelCase, or dotted like `node.js`. */
private fun namesAProduct(objective: String): Boolean {
    val words = objective.split(Regex("""\s+""")).map { it.trim('.', ',', ';', ':', '!', '?', '"', '\'', '(', ')') }.filter { it.isNotEmpty() }
    return words.drop(1).any { word ->
        val lower = word.lowercase()
        when {
            lower in NOT_A_PRODUCT || word.length < 3 -> false
            word.any(Char::isLowerCase) && word.drop(1).any(Char::isUpperCase) -> true // GitHub, iPhone
            word.first().isUpperCase() && word.drop(1).all { it.isUpperCase() || it.isDigit() } && word.length in 3..8 -> true // API names: AWS, SQL
            word.first().isUpperCase() -> true
            Regex("""[a-z]+\.(js|py|io|ai|net)""").matches(lower) -> true
            else -> false
        }
    }
}

/**
 * Whether the queen runs her one research step before drafting a plan. Deterministic and local: an objective that
 * asks to teach, explain, learn or integrate, asks "how do I", or names a product, tool or app; and only when there
 * is something to consult. Nothing about the objective leaves the device here.
 */
fun researchWanted(objective: String, context: ResearchContext = ResearchContext()): Boolean {
    val text = objective.trim()
    if (text.isEmpty()) return false
    if (!(context.repositoryLinked || context.connectedServers > 0 || context.bridgeEnabled || context.lookupConfigured)) return false
    return TRIGGER.containsMatchIn(text) || namesAProduct(text)
}

/** The few generic capability words a lookup may send: never the objective's own text. */
object ResearchQuery {
    const val MAX_WORDS = 4
    const val MAX_CHARS = 60

    private val stop = setOf(
        "teach", "taught", "explain", "learn", "learning", "integrate", "integration", "integrating", "how", "do", "does", "can",
        "walk", "through", "show", "ins", "outs", "connect", "the", "and", "for", "with", "from", "into", "about", "that",
        "this", "these", "those", "what", "which", "when", "where", "who", "why", "you", "your", "our", "mine", "its", "use",
        "using", "set", "make", "build", "need", "want", "please", "help", "get", "got", "are", "was", "were", "has", "have",
        "had", "not", "but", "all", "any", "some", "can't", "cannot", "would", "could", "should", "will", "just", "also",
        "then", "than", "key", "id", "secret", "token", "password", "redacted", "account", "https", "http", "them", "they", "their", "there", "here", "out", "off", "how's", "me", "myself", "app", "tool",
    )

    /**
     * Up to [MAX_WORDS] distinct lowercase words from [objective], or null when none qualify. Dropped first: links,
     * addresses, paths, quoted text, anything with digits, long identifiers and every known credential shape
     * ([ControlRedaction]); then filler and the trigger verbs. A subject that is itself private still leaks through
     * its own name, which is why the keeper sees these words before they are sent.
     */
    fun minimal(objective: String): String? {
        var text = ControlRedaction.scrub(objective)
        text = Regex("\"[^\"]*\"|'[^']*'|`[^`]*`").replace(text, " ")
        text = Regex("""(?i)\S*(://|www\.)\S*|\S+@\S+|\S*[/\\]\S*""").replace(text, " ")
        val words = text.split(Regex("""[\s,;:!?()\[\]{}]+""")).asSequence()
            .map { it.trim('.', '-', '_') }
            .filter { it.isNotEmpty() }
            .filterNot { '/' in it || '\\' in it || '@' in it || "://" in it || it.startsWith("www.") }
            .filterNot { word -> word.any(Char::isDigit) }
            .map(String::lowercase)
            .filter { Regex("[a-z][a-z.+\\-]{2,23}").matches(it) && it !in stop }
            .distinct()
            .take(MAX_WORDS)
            .toList()
        val joined = words.joinToString(" ").take(MAX_CHARS).trim()
        return joined.ifEmpty { null }
    }
}
