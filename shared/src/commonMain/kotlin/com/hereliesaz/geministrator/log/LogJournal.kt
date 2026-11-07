package com.hereliesaz.geministrator.log

import com.hereliesaz.geministrator.control.ControlRedaction
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** The four logs the Log tab can show (docs/Keepers-Room.md). */
enum class LogSource { Run, Agents, App, Memory }

/** One dated line. [scope] says whose it is: a task, a tag, a workflow. */
data class LogLine(
    val atEpochMillis: Long,
    val scope: String,
    val text: String,
)

/**
 * A bounded, dated journal: the newest [maxLines] lines, each at most [maxLineChars], scrubbed of
 * the common shapes of keys before they are kept ([ControlRedaction.scrub]), so a key a message
 * happened to carry is never stored or shown.
 *
 * With [settings] and a [storageKey] the journal survives a restart: it is read back when made and
 * written after each line. Lines are rare (user actions and failures), so a write per line is cheap;
 * a failed read or write leaves the in-memory journal as it is.
 */
@OptIn(ExperimentalTime::class)
class LogJournal(
    private val maxLines: Int = DEFAULT_MAX_LINES,
    private val maxLineChars: Int = DEFAULT_MAX_LINE_CHARS,
    private val settings: Settings? = null,
    private val storageKey: String = "",
    private val nowEpochMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    init {
        require(maxLines > 0) { "maxLines must be positive" }
        require(maxLineChars > 1) { "maxLineChars must be greater than one" }
    }

    private val state = MutableStateFlow(restore())

    /** Oldest first. */
    val lines: StateFlow<List<LogLine>> = state.asStateFlow()

    fun record(scope: String, text: String) {
        val clean = ControlRedaction.scrub(text).trim().let {
            if (it.length > maxLineChars) it.take(maxLineChars - 1).trimEnd() + "…" else it
        }
        if (clean.isEmpty()) return
        val line = LogLine(nowEpochMillis(), ControlRedaction.scrub(scope).trim().take(MAX_SCOPE_CHARS), clean)
        state.update { (it + line).takeLast(maxLines) }
        persist()
    }

    fun clear() {
        state.value = emptyList()
        persist()
    }

    private fun restore(): List<LogLine> {
        val store = settings ?: return emptyList()
        if (storageKey.isEmpty()) return emptyList()
        return runCatching { decodeLogLines(store.getStringOrNull(storageKey).orEmpty()) }
            .getOrDefault(emptyList())
            .takeLast(maxLines)
    }

    private fun persist() {
        val store = settings ?: return
        if (storageKey.isEmpty()) return
        runCatching { store.putString(storageKey, encodeLogLines(state.value)) }
    }

    companion object {
        const val DEFAULT_MAX_LINES = 500
        const val DEFAULT_MAX_LINE_CHARS = 2_000
        private const val MAX_SCOPE_CHARS = 80
    }
}

/** One line per entry: `epoch<TAB>scope<TAB>text`, with backslash, tab and newlines escaped. */
internal fun encodeLogLines(lines: List<LogLine>): String =
    lines.joinToString("\n") { "${it.atEpochMillis}\t${escapeLog(it.scope)}\t${escapeLog(it.text)}" }

/** The inverse of [encodeLogLines]; a malformed line is skipped. */
internal fun decodeLogLines(encoded: String): List<LogLine> =
    encoded.lineSequence().mapNotNull { row ->
        val parts = row.split('\t')
        if (parts.size != 3) return@mapNotNull null
        val at = parts[0].toLongOrNull() ?: return@mapNotNull null
        LogLine(at, unescapeLog(parts[1]), unescapeLog(parts[2]))
    }.toList()

private fun escapeLog(text: String): String = buildString(text.length) {
    for (c in text) when (c) {
        '\\' -> append("\\\\")
        '\t' -> append("\\t")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        else -> append(c)
    }
}

private fun unescapeLog(text: String): String = buildString(text.length) {
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '\\' && i + 1 < text.length) {
            when (text[i + 1]) {
                '\\' -> append('\\')
                't' -> append('\t')
                'n' -> append('\n')
                'r' -> append('\r')
                else -> append(c).append(text[i + 1])
            }
            i += 2
        } else {
            append(c)
            i++
        }
    }
}

/** The app's own diagnostic log: kept on the device, newest 500 lines, secrets scrubbed. */
object AppLog {
    const val SETTINGS_KEY = "aive.app-log.v1"

    val Shared: LogJournal by lazy { LogJournal(settings = Settings(), storageKey = SETTINGS_KEY) }

    fun record(tag: String, message: String) = Shared.record(tag, message)
}

/**
 * A moment as `2026-11-07 12:51:03.412Z`: UTC, because a log read after a move or a flight must
 * not shift under the reader, and the platforms here share no local-time source.
 */
fun formatLogTime(epochMillis: Long): String {
    val msPerDay = 86_400_000L
    val days = epochMillis.floorDiv(msPerDay)
    val inDay = epochMillis.mod(msPerDay)
    // Civil-from-days (Howard Hinnant), proleptic Gregorian.
    val z = days + 719_468
    val era = z.floorDiv(146_097L)
    val doe = z - era * 146_097
    val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val day = doy - (153 * mp + 2) / 5 + 1
    val month = if (mp < 10) mp + 3 else mp - 9
    val year = yoe + era * 400 + if (month <= 2) 1 else 0
    val h = inDay / 3_600_000
    val m = inDay / 60_000 % 60
    val s = inDay / 1_000 % 60
    val ms = inDay % 1_000
    return "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')} " +
        "${h.toString().padStart(2, '0')}:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}." +
        "${ms.toString().padStart(3, '0')}Z"
}
