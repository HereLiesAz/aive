package com.hereliesaz.geministrator.workflow.schedule

import com.hereliesaz.geministrator.mcp.McpToolSource
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** One message as a mail source lists it: who sent it and its subject. The body is never read here. */
data class MailHeader(val id: String, val sender: String, val subject: String)

/** The mail source could not be asked (nothing connected, the tool failed, or it answered in a shape this cannot read). */
class MailSourceUnavailable(message: String) : Exception(message)

/** Lists recent mail. The app has no mail connector of its own: [McpMailArrivalSource] asks a connected MCP server. */
interface MailArrivalSource {
    /** Recent messages matching [filter] (the source's own search words; blank for any), newest first, at most [limit]. */
    suspend fun recent(filter: String, limit: Int): List<MailHeader>
}

/** The pure part of a look at the mailbox. */
object MailArrival {
    class Plan(val baseline: Boolean, val fresh: List<MailHeader>)

    /**
     * What a look found. The first look of a schedule ([TriggerLedger.lastMailCheckAtEpochMillis] null) is a baseline: what is
     * already there is noted and nothing starts. Later, a message whose id is not in the ledger is new; [Plan.fresh] is oldest first.
     */
    fun plan(ledger: TriggerLedger, newestFirst: List<MailHeader>): Plan {
        val baseline = ledger.lastMailCheckAtEpochMillis == null
        val seen = ledger.seenEventIds.toSet()
        val fresh = newestFirst.distinctBy { it.id }.filter { it.id !in seen }.reversed()
        return Plan(baseline, if (baseline) emptyList() else fresh)
    }

    fun due(schedule: WorkflowSchedule, now: Long): Boolean {
        if (!schedule.enabled || schedule.trigger.kind != ScheduleTrigger.Kind.EmailArrival) return false
        val last = schedule.triggerLedger.lastMailCheckAtEpochMillis ?: return true
        val gap = schedule.trigger.mailPollMinutes.coerceIn(ScheduleTrigger.MIN_MAIL_POLL_MINUTES, ScheduleTrigger.MAX_MINUTES) * 60_000L
        return now - last !in 0 until gap
    }

    /** The context a mail start gives the hive: sender and subject only, cleaned and capped. */
    fun context(header: MailHeader): String =
        "From: " + TriggerGuard.cleanText(header.sender, TriggerGuard.MAX_FIELD_CHARS).replace('\n', ' ') +
            "\nSubject: " + TriggerGuard.cleanText(header.subject, TriggerGuard.MAX_FIELD_CHARS).replace('\n', ' ')
}

/**
 * Looks at the mailbox of each email schedule at its own interval (off unless the schedule is on) and hands each new message to
 * [intake] as one event, which applies the dedupe, gap and daily cap. Messages the guard turned away stay unseen and are looked at again.
 */
class MailPoller(
    private val store: WorkflowScheduleStore,
    private val source: MailArrivalSource,
    private val intake: TriggerIntake,
    private val now: () -> Long,
    private val log: (String) -> Unit,
) {
    /** One pass over the schedules; returns how many events were taken. */
    suspend fun poll(): Int {
        val at = now()
        var taken = 0
        for (schedule in store.all().filter { MailArrival.due(it, at) }) {
            // Claim the look first, so a second pass (or another window) does not repeat it.
            val claimed = store.update(schedule.id) { current ->
                if (MailArrival.due(current, at)) current.copy(triggerLedger = current.triggerLedger.copy(lastMailCheckAtEpochMillis = at)) else null
            } ?: continue
            val headers = try {
                source.recent(claimed.trigger.mailFilter, LOOK_LIMIT)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val note = "Could not check mail: ${failure.message ?: failure::class.simpleName}"
                store.update(schedule.id) { if (it.lastNote != note) it.copy(lastNote = note) else null }
                log("Schedule '${schedule.name}': $note")
                continue
            }
            val plan = MailArrival.plan(schedule.triggerLedger, headers)
            if (plan.baseline) {
                store.update(schedule.id) { it.copy(triggerLedger = it.triggerLedger.copy(seenEventIds = (it.triggerLedger.seenEventIds + headers.map(MailHeader::id)).distinct().takeLast(TriggerGuard.MAX_SEEN))) }
                continue
            }
            for (header in plan.fresh) {
                val cause = "new mail from " + TriggerGuard.cleanText(header.sender, 80).replace('\n', ' ')
                val verdict = intake.submit(schedule.id, ScheduleTrigger.Kind.EmailArrival, header.id, cause, MailArrival.context(header))
                if (verdict is TriggerVerdict.Accept) taken++ else if (verdict !is TriggerVerdict.Duplicate) break
            }
        }
        return taken
    }

    companion object {
        const val LOOK_LIMIT = 20
    }
}

/**
 * [MailArrivalSource] over a connected MCP server (docs/Scheduled-Workflows.md). To work, a server must be connected in
 * Connections that offers a read-only tool named one of [toolNames] taking the search words in [queryArgument] and a count in
 * [limitArgument], and answering JSON: a list of messages (or an object holding one under `messages`, `threads`, `items`, `emails`
 * or `results`) each with an id and, where it has them, `from` and `subject`. The server should be marked always allowed, or the
 * keeper is asked at each look. Nothing here reads a message body.
 */
class McpMailArrivalSource(
    private val tools: McpToolSource,
    private val toolNames: List<String> = DEFAULT_TOOLS,
    private val queryArgument: String = "query",
    private val limitArgument: String = "maxResults",
) : MailArrivalSource {
    override suspend fun recent(filter: String, limit: Int): List<MailHeader> {
        val toolbox = tools.toolbox(RUN_KEY) ?: throw MailSourceUnavailable(NOT_CONNECTED)
        val offer = toolbox.offers.firstOrNull { !it.destructive && it.toolName in toolNames } ?: throw MailSourceUnavailable(NOT_CONNECTED)
        val outcome = toolbox.call(
            offer.name,
            buildJsonObject {
                put(queryArgument, filter)
                put(limitArgument, limit)
            },
        )
        if (outcome.isError) throw MailSourceUnavailable("the mail tool failed: " + outcome.rawText.take(200))
        return parse(outcome.rawText, limit)
    }

    companion object {
        const val RUN_KEY = "mail-trigger"
        const val NOT_CONNECTED = "no connected mail tool lists recent messages (connect one in Connections)"
        val DEFAULT_TOOLS = listOf("search_threads", "search_messages", "list_messages", "list_emails", "search_emails", "list_unread")
        private val LIST_KEYS = listOf("messages", "threads", "items", "emails", "results", "data")
        private val json = Json { ignoreUnknownKeys = true }

        /** The messages in a tool's JSON answer; throws [MailSourceUnavailable] when it is not a list this can read. */
        fun parse(text: String, limit: Int): List<MailHeader> {
            val root = try {
                json.parseToJsonElement(text)
            } catch (_: Exception) {
                throw MailSourceUnavailable("the mail tool did not answer in JSON")
            }
            val list: JsonArray = when (root) {
                is JsonArray -> root
                is JsonObject -> LIST_KEYS.firstNotNullOfOrNull { root[it] as? JsonArray }
                    ?: root.values.firstNotNullOfOrNull { it as? JsonArray }
                    ?: throw MailSourceUnavailable("the mail tool's answer holds no list of messages")
                else -> throw MailSourceUnavailable("the mail tool's answer holds no list of messages")
            }
            return list.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val id = text(obj, "id", "messageId", "message_id", "threadId", "thread_id")?.takeIf { it.length <= 256 } ?: return@mapNotNull null
                MailHeader(id, text(obj, "from", "sender", "fromAddress").orEmpty(), text(obj, "subject").orEmpty())
            }.take(limit)
        }

        private fun text(obj: JsonObject, vararg keys: String): String? {
            for (key in keys) {
                val value: JsonElement = obj[key] ?: continue
                val found = when (value) {
                    is JsonPrimitive -> value.contentOrNull
                    is JsonObject -> listOf("email", "address", "name").firstNotNullOfOrNull { (value[it] as? JsonPrimitive)?.contentOrNull }
                    else -> null
                }
                if (!found.isNullOrBlank()) return found
            }
            return null
        }
    }
}
