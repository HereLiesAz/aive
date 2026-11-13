package com.hereliesaz.geministrator.telemetry

import com.russhwolf.settings.Settings
import io.ktor.http.Url
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The Keeper's room settings for OTLP trace export. Off by default; content is a second,
 * separate opt-in. [enabledAtEpochMillis] is when export was last turned on: runs that ended
 * before it are never sent, so turning export on does not backfill history.
 */
@Serializable
data class TraceExportSettings(
    val enabled: Boolean = false,
    val endpoint: String = "",
    /** `name=value` pairs separated by commas or new lines (the OTEL_EXPORTER_OTLP_HEADERS form). */
    val headers: String = "",
    val includeContent: Boolean = false,
    val enabledAtEpochMillis: Long? = null,
) {
    /** The traces URL to post to, or null while export is off or the settings are not valid. */
    fun activeTracesUrl(): String? = if (enabled) otlpTracesUrl(endpoint) else null

    val contentPolicy: TraceContentPolicy
        get() = TraceContentPolicy(includeContent = enabled && includeContent)
}

/**
 * Settings-backed store. The key is outside the setup-file allowlist (SETUP_SETTINGS_KEYS), so
 * the endpoint and headers are never written into an exported `.aivesetup` file.
 */
class TraceExportSettingsStore(
    settings: Settings? = null,
    private val key: String = DEFAULT_KEY,
    private val exportedKey: String = DEFAULT_EXPORTED_KEY,
) {
    /** Resolved at first use, not at construction, so the private file is chosen whatever order the platform starts in. */
    private val backing: Settings by lazy { settings ?: com.hereliesaz.geministrator.persistence.PrivateSettings.forKeys(DEFAULT_KEY, DEFAULT_EXPORTED_KEY) }
    fun load(): TraceExportSettings = backing.getStringOrNull(key)
        ?.let { encoded -> runCatching { json.decodeFromString(TraceExportSettings.serializer(), encoded) }.getOrNull() }
        ?: TraceExportSettings()

    fun save(value: TraceExportSettings) {
        backing.putString(key, json.encodeToString(TraceExportSettings.serializer(), value))
    }

    /** Runs already handed to the exporter, newest last, bounded to [MAX_REMEMBERED_RUNS]. */
    fun isExported(runId: String): Boolean = exportedRuns().contains(runId)

    fun markExported(runId: String) {
        val runs = exportedRuns().filterNot { it == runId } + runId
        backing.putString(exportedKey, runs.takeLast(MAX_REMEMBERED_RUNS).joinToString("\n"))
    }

    private fun exportedRuns(): List<String> =
        backing.getStringOrNull(exportedKey)?.split('\n')?.filter(String::isNotBlank).orEmpty()

    companion object {
        const val DEFAULT_KEY: String = "aive.telemetry.otlp.v1"
        const val DEFAULT_EXPORTED_KEY: String = "aive.telemetry.otlp.exported-runs.v1"
        const val MAX_REMEMBERED_RUNS: Int = 500
        private val json = Json {
            encodeDefaults = true
            ignoreUnknownKeys = true
        }
    }
}

/**
 * The OTLP/HTTP traces URL for [endpoint]: `https://` (plain `http://` only for this device's
 * loopback address, where a local collector listens), with `/v1/traces` appended unless present.
 * Null when the endpoint is blank or not acceptable.
 */
fun otlpTracesUrl(endpoint: String): String? {
    val trimmed = endpoint.trim().trimEnd('/')
    if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() || it == '?' || it == '#' }) return null
    val url = runCatching { Url(trimmed) }.getOrNull() ?: return null
    val scheme = url.protocol.name.lowercase()
    val host = url.host.lowercase().removePrefix("[").removeSuffix("]")
    if (host.isEmpty()) return null
    if (!trimmed.lowercase().startsWith("$scheme://")) return null
    val acceptable = scheme == "https" || (scheme == "http" && host in LOOPBACK_HOSTS)
    if (!acceptable) return null
    // Credentials belong in headers, never in the URL.
    if ('@' in trimmed.substringAfter("://").substringBefore('/')) return null
    return if (trimmed.endsWith(TRACES_PATH)) trimmed else trimmed + TRACES_PATH
}

/**
 * Headers from `name=value` pairs separated by commas or new lines. Null when any pair is malformed
 * or names a header the exporter sets itself. Blank input is no headers.
 */
fun parseOtlpHeaders(raw: String): Map<String, String>? {
    val result = linkedMapOf<String, String>()
    raw.split(',', '\n').map(String::trim).filter(String::isNotEmpty).forEach { pair ->
        val separator = pair.indexOf('=')
        if (separator <= 0) return null
        val name = pair.substring(0, separator).trim()
        val value = pair.substring(separator + 1).trim()
        if (name.isEmpty() || !name.all(::isHeaderNameChar)) return null
        if (name.lowercase() in RESERVED_HEADERS) return null
        if (value.isEmpty() || value.any { it == '\r' || it.code < 0x20 && it != '\t' }) return null
        result[name] = value
    }
    return result
}

private fun isHeaderNameChar(char: Char): Boolean =
    char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char in "!#$%&'*+-.^_`|~"

private const val TRACES_PATH = "/v1/traces"
private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1")
private val RESERVED_HEADERS = setOf("content-type", "content-length", "host", "transfer-encoding", "connection")
