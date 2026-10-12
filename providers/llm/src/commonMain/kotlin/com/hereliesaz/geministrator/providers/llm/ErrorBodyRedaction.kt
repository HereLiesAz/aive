package com.hereliesaz.geministrator.providers.llm

/**
 * Scrubs credential-shaped text from provider error bodies before they become exception messages.
 *
 * Failure messages are persisted as `TaskFailed.reason` and leave the device in run and project
 * exports, so an endpoint that echoes request headers or form fields must not carry a key with them.
 */
internal fun String.redactSecrets(): String =
    SECRET_PATTERNS.fold(this) { text, (pattern, replacement) -> pattern.replace(text, replacement) }

private const val REDACTED = "[redacted]"

private val SECRET_PATTERNS: List<Pair<Regex, String>> = listOf(
    // Authorization: Bearer <token> / Basic <credentials>
    Regex("""(?i)\b(bearer|basic)\s+[A-Za-z0-9._~+/=-]{8,}""") to "$1 $REDACTED",
    // "api_key": "...", x-api-key=..., password: ..., access_token=..., secret ...
    Regex("""(?i)(?<![A-Za-z0-9_-])("?(?:x-)?(?:api[_-]?key|access[_-]?token|refresh[_-]?token|token|password|passwd|secret|client[_-]?secret)"?\s*[:=]\s*"?)[^"\s,&}]{4,}""") to "$1$REDACTED",
    // Well-known key prefixes: OpenAI/Anthropic sk-, GitHub gh*_/github_pat_, GitLab glpat-, Google AIza, xAI xai-, Hugging Face hf_
    Regex("""\b(?:sk-[A-Za-z0-9_-]{16,}|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|glpat-[A-Za-z0-9_-]{16,}|AIza[A-Za-z0-9_-]{30,}|xai-[A-Za-z0-9]{20,}|hf_[A-Za-z0-9]{20,})""") to REDACTED,
)
