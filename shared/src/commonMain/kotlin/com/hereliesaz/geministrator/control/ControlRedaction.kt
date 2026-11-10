package com.hereliesaz.geministrator.control

/**
 * The one redactor. Whatever a run produced (command output, a CLI's text, a patch, a provider's error body)
 * is scrubbed of the common shapes of keys before it travels further. The control API never reads a key store,
 * so a key can only appear here by being written into a run's own text; this keeps it from going on. It also
 * removes the API's own bearer token.
 */
object ControlRedaction {
    const val MASK = "[redacted]"

    private val patterns = listOf(
        // Provider and platform keys with a recognisable prefix.
        Regex("""\b(?:sk|pk|rk)-[A-Za-z0-9_\-]{16,}"""),
        Regex("""\bgh[pousr]_[A-Za-z0-9]{20,}"""),
        Regex("""\bgithub_pat_[A-Za-z0-9_]{20,}"""),
        Regex("""\bglpat-[A-Za-z0-9_\-]{16,}"""),
        Regex("""\bxai-[A-Za-z0-9]{20,}"""),
        Regex("""\bhf_[A-Za-z0-9]{20,}"""),
        Regex("""\bxox[abprs]-[A-Za-z0-9\-]{10,}"""),
        Regex("""\bAIza[0-9A-Za-z_\-]{30,}"""),
        Regex("""\b(?:AKIA|ASIA)[0-9A-Z]{16}\b"""),
        // An Authorization header value (Bearer or Basic): the scheme word stays, the credential goes.
        Regex("""(?i)\b(bearer|basic)(\s+)[A-Za-z0-9._~+/=\-]{8,}"""),
        // A key=value assignment that names what it holds; the name stays, the value goes.
        Regex("""(?i)(?<![A-Za-z0-9_\-])("?(?:x-)?(?:api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|secret|token|password|passwd|authorization)"?\s*[:=]\s*"?)(?!(?:bearer|basic)\s)[^\s",}&]{4,}"""),
        // JSON Web Tokens.
        Regex("""\beyJ[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}"""),
        // A PEM private key block. One with no END line ends at the next quote or bracket or the end of the text,
        // not at the end of the whole text: that blanked the rest of a JSON document after it.
        Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----(?:[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----|[^"'<>\]}]*)"""),
    )

    private val schemeWords = setOf("bearer", "basic")

    /** [text] with every known key shape replaced, and each of [alsoMask] (for example the control token) removed. */
    fun scrub(text: String, alsoMask: List<String> = emptyList()): String {
        var out = text
        for (value in alsoMask) if (value.length >= 8) out = out.replace(value, MASK)
        for (pattern in patterns) {
            out = pattern.replace(out) { match ->
                val groups = match.groupValues
                val first = groups.getOrNull(1).orEmpty()
                when {
                    first.lowercase() in schemeWords -> first + groups[2] + MASK
                    first.isNotEmpty() -> first + MASK
                    else -> MASK
                }
            }
        }
        return out
    }
}
