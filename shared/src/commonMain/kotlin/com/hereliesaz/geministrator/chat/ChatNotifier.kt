package com.hereliesaz.geministrator.chat

import com.hereliesaz.geministrator.ApplicationRuntime
import com.hereliesaz.geministrator.inboxItemKeys
import com.hereliesaz.geministrator.queen.QueenLevel
import com.hereliesaz.geministrator.workflow.isTerminal

/** One run as the notifier sees it: no text from the run, only its id, its status and the keys of what waits on the keeper. */
data class ChatRunSnapshot(val runId: String, val status: String, val needsYouKeys: List<String>) {
    val finished: Boolean get() = status == "Completed" || status == "Failed" || status == "Cancelled"
}

/** Where the notifier looks. Null means the app is not ready; nothing is concluded from it. */
fun interface ChatAttentionSource {
    suspend fun snapshot(): List<ChatRunSnapshot>?
}

/** The runs of an [ApplicationRuntime], with the Inbox's own keys for what needs the keeper (the same ones the badge counts). */
internal class RuntimeAttentionSource(private val runtime: () -> ApplicationRuntime?) : ChatAttentionSource {
    override suspend fun snapshot(): List<ChatRunSnapshot>? {
        val r = runtime() ?: return null
        val runs = r.persistence.projects.all().flatMap { r.persistence.runs.byProject(it.id) }
        return runs.map { run ->
            ChatRunSnapshot(
                runId = run.id.value,
                status = run.status.name,
                needsYouKeys = if (run.status.isTerminal()) emptyList() else inboxItemKeys(run),
            )
        }
    }
}

/**
 * What to tell the keeper's chat, and when (docs/Chat-Bots.md). Pure and deterministic. It reuses the queen's level: Quiet
 * says only what needs the keeper and how a run ended; Helpful also says a run started. Every sentence is built from a
 * closed set of phrases plus a short run id, never from text a run produced, and none holds a link.
 * The first look only records what is already there. Messages are paced: a minimum gap and an hourly cap that depend on
 * the level; what waits is combined into one message and bounded.
 */
class ChatNotifier(
    private val level: () -> QueenLevel,
    private val clock: () -> Long,
) {
    companion object {
        const val POINTER = "Open The Aive to approve."
        private const val MAX_QUEUE = 12
        private const val MAX_LINES_PER_MESSAGE = 6
        private const val HOUR = 3_600_000L
    }

    private var baselined = false
    private val statuses = HashMap<String, String>()
    private var seenKeys: Set<String> = emptySet()
    private val queue = ArrayDeque<String>()
    private val sentAt = ArrayDeque<Long>()
    private var lastSent = Long.MIN_VALUE / 2

    private fun short(id: String): String = id.take(8)

    /** Looks at [runs]; returns one message to send now, or null (nothing new, or paced). */
    fun poll(runs: List<ChatRunSnapshot>): String? {
        val keysNow = runs.flatMap { it.needsYouKeys }.toSet()
        if (!baselined) {
            baselined = true
            runs.forEach { statuses[it.runId] = it.status }
            seenKeys = keysNow
            return null
        }
        val helpful = level() == QueenLevel.Helpful
        val fresh = keysNow - seenKeys
        if (fresh.isNotEmpty()) {
            queue.addLast(if (fresh.size == 1) "1 thing needs you. $POINTER" else "${fresh.size} things need you. $POINTER")
        }
        seenKeys = keysNow
        runs.forEach { run ->
            val before = statuses[run.runId]
            if (before != run.status) {
                statuses[run.runId] = run.status
                when {
                    run.status == "Completed" && before != null -> queue.addLast("Run ${short(run.runId)} finished.")
                    run.status == "Failed" && before != null -> queue.addLast("Run ${short(run.runId)} failed. Open The Aive to see why.")
                    run.status == "Cancelled" && before != null -> queue.addLast("Run ${short(run.runId)} was stopped.")
                    before == null && !run.finished && helpful -> queue.addLast("Run ${short(run.runId)} started.")
                }
            }
        }
        while (queue.size > MAX_QUEUE) queue.removeFirst()
        if (queue.isEmpty()) return null
        return releaseIfPaced()
    }

    /** Sends what is waiting if the pacing allows; call it again later to flush. */
    fun flush(): String? = if (queue.isEmpty()) null else releaseIfPaced()

    private fun releaseIfPaced(): String? {
        val now = clock()
        val helpful = level() == QueenLevel.Helpful
        val minGap = if (helpful) 20_000L else 60_000L
        val perHour = if (helpful) 20 else 6
        while (sentAt.isNotEmpty() && now - sentAt.first() >= HOUR) sentAt.removeFirst()
        if (now - lastSent < minGap || sentAt.size >= perHour) return null
        val lines = ArrayList<String>()
        while (queue.isNotEmpty() && lines.size < MAX_LINES_PER_MESSAGE) lines += queue.removeFirst()
        if (queue.isNotEmpty()) lines += "and ${queue.size} more. $POINTER"
        queue.clear()
        lastSent = now
        sentAt.addLast(now)
        return lines.joinToString("\n")
    }
}
