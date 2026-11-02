package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.ProviderRunId
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.providers.AgentEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What one line of a creature's live activity is: its reasoning, or a tool/progress step. */
enum class SessionActivityKind { Thinking, Progress }

data class SessionActivityLine(
    /** Increasing within a session's feed; newer lines have larger ids. */
    val id: Long,
    val kind: SessionActivityKind,
    val text: String,
)

/** One provider session of one task attempt: a retry is a new provider run, so a new feed. */
data class SessionActivityKey(
    val taskRunId: TaskRunId,
    val providerRunId: ProviderRunId,
)

fun ManagedSessionHandle.activityKey(): SessionActivityKey = SessionActivityKey(taskRunId, providerRunId)

/**
 * The live activity of each running provider session -- its `AgentEvent.Thinking` and
 * `AgentEvent.Progress` lines, newest last -- for the creature's panel (docs/Keepers-Room.md).
 *
 * In memory only and bounded: at most [maxLinesPerSession] lines per session (the oldest drop
 * first), at most [maxSessions] sessions (the least recently active drops first), each line at most
 * [maxLineChars]. Nothing here is persisted or exported: workflow state never holds thinking (the
 * memory layer banks it on its own), and the duplex peer never receives it (docs/architecture/DUPLEX_MCP.md).
 * Messages to and from the agent are not activity; they never enter a feed.
 */
class SessionActivityFeeds(
    val maxLinesPerSession: Int = DEFAULT_MAX_LINES,
    val maxSessions: Int = DEFAULT_MAX_SESSIONS,
    val maxLineChars: Int = DEFAULT_MAX_LINE_CHARS,
) {
    init {
        require(maxLinesPerSession > 0) { "maxLinesPerSession must be positive" }
        require(maxSessions > 0) { "maxSessions must be positive" }
        require(maxLineChars > 1) { "maxLineChars must be greater than one" }
    }

    private data class Feed(val nextId: Long, val lines: List<SessionActivityLine>)

    // Events arrive from each session's observer coroutine; every change is one compare-and-set
    // over an immutable map whose iteration order is least to most recently active.
    private val state = MutableStateFlow<Map<SessionActivityKey, Feed>>(emptyMap())
    private val _feeds = MutableStateFlow<Map<SessionActivityKey, List<SessionActivityLine>>>(emptyMap())

    /** Every retained session's lines, oldest first within each. */
    val feeds: StateFlow<Map<SessionActivityKey, List<SessionActivityLine>>> = _feeds.asStateFlow()

    /** Records [event] when it is activity (thinking or a non-blank progress message); ignores the rest. */
    fun record(handle: ManagedSessionHandle, event: AgentEvent) {
        val (kind, text) = when (event) {
            is AgentEvent.Thinking -> SessionActivityKind.Thinking to event.content
            is AgentEvent.Progress -> SessionActivityKind.Progress to event.message
            // Messages are conversation, not activity; the rest are state, shown elsewhere.
            is AgentEvent.Message, is AgentEvent.PlanGenerated, is AgentEvent.PlanApproved,
            is AgentEvent.ArtifactProduced, is AgentEvent.Completed, is AgentEvent.Failed,
            is AgentEvent.UsageReported -> return
        }
        record(handle.activityKey(), kind, text)
    }

    /** Appends [text] as the newest line of [key]'s feed. Blank lines and a repeat of the newest are ignored. */
    fun record(key: SessionActivityKey, kind: SessionActivityKind, text: String) {
        val clean = text.trim().let { if (it.length > maxLineChars) it.take(maxLineChars - 1).trimEnd() + "…" else it }
        if (clean.isEmpty()) return
        state.update { current ->
            val feed = current[key] ?: Feed(nextId = 0L, lines = emptyList())
            val newest = feed.lines.lastOrNull()
            if (newest != null && newest.kind == kind && newest.text == clean) return@update current
            val lines = (feed.lines + SessionActivityLine(feed.nextId, kind, clean)).takeLast(maxLinesPerSession)
            val next = LinkedHashMap<SessionActivityKey, Feed>()
            current.forEach { (k, v) -> if (k != key) next[k] = v }
            next[key] = Feed(feed.nextId + 1, lines)
            while (next.size > maxSessions) next.remove(next.keys.first())
            next
        }
        publishLatest()
    }

    /** [key]'s lines, oldest first; empty when it has none. */
    fun lines(key: SessionActivityKey): List<SessionActivityLine> = _feeds.value[key].orEmpty()

    /** [key]'s newest line: what it is doing now. */
    fun latest(key: SessionActivityKey): SessionActivityLine? = lines(key).lastOrNull()

    fun clear() {
        state.value = emptyMap()
        publishLatest()
    }

    // The same last-writer-wins publication as LiveStepsFeedModel: a caller that read an older
    // snapshot never leaves it in [feeds] after a newer one was published.
    private fun publishLatest() {
        while (true) {
            val shown = _feeds.value
            val snapshot = state.value
            if (_feeds.compareAndSet(shown, snapshot.mapValues { it.value.lines }) && state.value === snapshot) return
        }
    }

    companion object {
        const val DEFAULT_MAX_LINES = 60
        const val DEFAULT_MAX_SESSIONS = 32
        const val DEFAULT_MAX_LINE_CHARS = 600

        /** The app's feeds: the provider-backed gateway records here and the creature's panel reads here. */
        val Shared: SessionActivityFeeds = SessionActivityFeeds()
    }
}

/**
 * How a panel shows a feed: the newest [visible] lines, with the older ones collapsed into a
 * count unless [expanded].
 */
data class SessionActivityWindow(
    val shown: List<SessionActivityLine>,
    val collapsed: Int,
)

fun List<SessionActivityLine>.activityWindow(visible: Int, expanded: Boolean): SessionActivityWindow {
    require(visible > 0) { "visible must be positive" }
    if (expanded || size <= visible) return SessionActivityWindow(this, collapsed = 0)
    return SessionActivityWindow(takeLast(visible), collapsed = size - visible)
}

/** One short line for the hive's "doing now": whitespace flattened, at most [maxChars]. */
fun SessionActivityLine.doingNowText(maxChars: Int = 120): String {
    require(maxChars > 1) { "maxChars must be greater than one" }
    val flat = text.split(Regex("\\s+")).filter(String::isNotEmpty).joinToString(" ")
    return if (flat.length > maxChars) flat.take(maxChars - 1).trimEnd() + "…" else flat
}
