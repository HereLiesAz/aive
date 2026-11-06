package com.hereliesaz.geministrator.control

/**
 * Last line of defence for what leaves the control API: whatever a run produced (command output,
 * a pasted log) is scrubbed of the common shapes of keys before an agent reads it. The control API
 * never reads a key store, so a key can only appear here by being written into a run's own text;
 * this keeps it from travelling further. It also removes the API's own bearer token.
 */
object ControlRedaction {
    const val MASK = "[redacted]"

    private val patterns = listOf(
        // Provider and platform keys with a recognisable prefix.
        Regex("""\b(?:sk|pk|rk)-[A-Za-z0-9_\-]{16,}"""),
        Regex("""\bgh[pousr]_[A-Za-z0-9]{20,}"""),
        Regex("""\bgithub_pat_[A-Za-z0-9_]{20,}"""),
        Regex("""\bglpat-[A-Za-z0-9_\-]{16,}"""),
        Regex("""\bxox[abprs]-[A-Za-z0-9\-]{10,}"""),
        Regex("""\bAIza[0-9A-Za-z_\-]{30,}"""),
        Regex("""\b(?:AKIA|ASIA)[0-9A-Z]{16}\b"""),
        // An Authorization header value.
        Regex("""(?i)\bbearer\s+[A-Za-z0-9._~+/=\-]{16,}"""),
        // A key=value assignment that names what it holds; the name stays, the value goes.
        Regex("""(?i)("?(?:api[_-]?key|secret|token|password|passwd|authorization)"?\s*[:=]\s*"?)[^\s",}]{8,}"""),
        // JSON Web Tokens.
        Regex("""\beyJ[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}"""),
        // A PEM private key block.
        Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?(?:-----END [A-Z ]*PRIVATE KEY-----|$)"""),
    )

    /** [text] with every known key shape replaced, and each of [alsoMask] (for example the control token) removed. */
    fun scrub(text: String, alsoMask: List<String> = emptyList()): String {
        var out = text
        for (value in alsoMask) if (value.length >= 8) out = out.replace(value, MASK)
        for (pattern in patterns) {
            out = pattern.replace(out) { match ->
                val prefix = match.groupValues.getOrNull(1)?.takeIf { it.isNotEmpty() }
                if (prefix != null) prefix + MASK else MASK
            }
        }
        return out
    }
}
