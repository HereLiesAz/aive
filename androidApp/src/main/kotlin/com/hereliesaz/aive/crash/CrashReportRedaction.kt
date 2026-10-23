package com.hereliesaz.aive.crash

/**
 * Scrubs a stack trace before it is persisted for the crash-report gateway, which turns it into a
 * public GitHub issue. Exception messages can echo a request URL, a header, a token or an address,
 * so credential-shaped text, URL credentials and query strings, and e-mail addresses are removed.
 */
internal fun redactCrashTrace(trace: String): String =
    REDACTIONS.fold(trace) { text, (pattern, replacement) -> pattern.replace(text, replacement) }

private const val REDACTED = "[redacted]"

private val REDACTIONS: List<Pair<Regex, String>> = listOf(
    Regex("""(?i)\b(bearer|basic)\s+[A-Za-z0-9._~+/=-]{8,}""") to "$1 $REDACTED",
    Regex("""(?i)(?<![A-Za-z0-9_-])("?(?:x-)?(?:api[_-]?key|access[_-]?token|refresh[_-]?token|token|password|passwd|secret|client[_-]?secret)"?\s*[:=]\s*"?)[^"\s,&}]{4,}""") to "$1$REDACTED",
    Regex("""\b(?:sk-[A-Za-z0-9_-]{16,}|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|glpat-[A-Za-z0-9_-]{16,}|AIza[A-Za-z0-9_-]{30,}|xai-[A-Za-z0-9]{20,}|hf_[A-Za-z0-9]{20,})""") to REDACTED,
    // user:password@host in a URL
    Regex("""(?i)(https?://)[^/\s:@]+:[^/\s@]+@""") to "$1$REDACTED@",
    // query strings can carry tokens or pairing material
    Regex("""(?i)(https?://[^\s?#"']+)\?[^\s"']+""") to "$1?$REDACTED",
    Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""") to "$REDACTED",
    // The Aive pairing code and relay tokens (aive1:<base64url>)
    Regex("""aive1:[A-Za-z0-9_-]{16,}""") to REDACTED,
)
