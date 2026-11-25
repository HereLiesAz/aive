package com.hereliesaz.geministrator.queen

import com.hereliesaz.geministrator.control.ControlRedaction
import com.hereliesaz.geministrator.domain.BuiltInRoles
import com.hereliesaz.geministrator.memory.MemoryBankRequest
import com.hereliesaz.geministrator.memory.MemoryBankScope
import com.hereliesaz.geministrator.memory.MemorySourceKind
import com.hereliesaz.geministrator.persistence.ChunkedStringSettings
import com.russhwolf.settings.Settings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

/** A completed advisory turn, not a record of executed work. */
@Serializable
internal data class CollectiveTurn(val question: String, val result: CollectiveAnswer)

/** One local conversation; its identity also scopes participant attention. */
@Serializable
internal data class CollectiveConversation(
    val id: String,
    val turns: List<CollectiveTurn> = emptyList(),
    /** Legacy serialized field only; ignored by recall and cleared on the next write. */
    val recallSourceIds: List<String> = emptyList(),
) {
    /**
     * Keep recent dialogue and a separately bounded sample of each specialist's latest actual
     * assessment, not merely the facilitator's interpretation. These are untrusted excerpts,
     * not verified facts or a record of resolved disagreement. A failed turn cannot erase them.
     * Full redacted assessments remain in their original retained turns.
     */
    fun context(): String {
        val dialogue = turns.joinToString("\n") {
            "Keeper: ${it.question}\nAive: ${it.result.answer.orEmpty()}"
        }
        val assessments = turns.lastOrNull { it.result.assessments.isNotEmpty() }
            ?.result?.assessments.orEmpty()
        if (assessments.isEmpty()) return dialogue.takeLast(1800)
        val latest = assessments.asReversed().distinctBy { it.role }.asReversed()
        val sample = latest.takeLast(4)
        val notes = sample.joinToString("\n") {
            val persuasion = it.persuadedBy?.let { number ->
                val source = assessments.getOrNull(number - 1)
                source?.let { previous -> " (reports persuaded by ${previous.role.take(40)}: ${previous.text.take(100)})" }
            }.orEmpty()
            "${it.role.take(40)}$persuasion: ${it.text.take(225)}"
        } + if (latest.size > sample.size) "\nAdditional participants' positions omitted." else ""
        val reference = ("\nLatest available specialist assessment excerpts (unverified; may be from an earlier turn):\n$notes").take(1800)
        return dialogue.takeLast(1800 - reference.length) + reference
    }

    /**
     * Explicit import of retained transcript text only. Historical timestamps and the facilitator's
     * configured route were not recorded; never invent them or claim the excerpts are complete.
     * The controller must reject import into any previously registered conversation bank.
     */
    fun historicalMemoryRequests(importedAt: Long): List<MemoryBankRequest> = buildList {
        turns.takeLast(MAX_COLLECTIVE_HISTORY_TURNS).forEachIndexed { index, turn ->
            fun addUtterance(slot: String, text: String, role: String?, label: String, kind: MemorySourceKind) {
                val clean = ControlRedaction.scrub(text).trim()
                if (clean.isBlank()) return
                add(MemoryBankRequest(
                    sourceSessionId = "$id:turn:legacy:$index:$slot",
                    text = clean.take(if (kind == MemorySourceKind.UserPrompt) 1500 else 1200),
                    label = ControlRedaction.scrub(label).take(512),
                    sourceKind = kind,
                    scope = MemoryBankScope(workflowRunId = id, roleId = role),
                    bankedAtEpochMillis = importedAt,
                    occurredAtEpochMillis = null,
                ))
            }
            addUtterance("keeper", turn.question, null, "Keeper question (retained transcript)", MemorySourceKind.UserPrompt)
            turn.result.assessments.forEachIndexed { slot, assessment ->
                if (assessment.role.isNotBlank()) addUtterance("assessment:$slot", assessment.text,
                    assessment.role, "Historical agent advisory excerpt (unverified); role=${assessment.role}; " +
                        "instance=${assessment.specialistId ?: "unknown"}; configured Source=${assessment.sourceId ?: "unknown"}; " +
                        "model=${assessment.modelId ?: "unknown"}; phase=${if (assessment.crossExamination) "cross-examination" else "initial"}; " +
                        "utterance=${slot + 1}; respondsTo=${assessment.respondsTo ?: "not recorded"}; " +
                        "persuadedBy=${assessment.persuadedBy ?: "not reported"}; " +
                        "promptObservations=${assessment.observationIds.joinToString()}", MemorySourceKind.AgentNote)
            }
            turn.result.answer?.let { answer ->
                addUtterance("facilitator", answer, BuiltInRoles.Orchestrator.id.value,
                    "Historical facilitator advisory excerpt (unverified); instance, Source and model unknown",
                    MemorySourceKind.AgentNote)
            }
        }
    }
}

/**
 * Local-only, bounded transcript ledger. Callers never supply history or mint a resumed identity.
 * Saves returned turns, redacted before writing. Conversation identity is not memory eligibility.
 * No bank passages or workflow approval state is stored.
 * Uses the existing crash-safe chunked settings decorator; unknown/corrupt state is not overwritten.
 */
internal class CollectiveConversationStore(settings: Settings, private val now: () -> Long) {
    private val settings = ChunkedStringSettings.unbounded(settings)
    private val mutex = Mutex()

    @Serializable
    private data class Book(val conversations: List<CollectiveConversation> = emptyList())

    private fun read(): Book = settings.getStringOrNull(KEY)?.let {
        Json.decodeFromString(Book.serializer(), it)
    } ?: Book()
    private fun write(book: Book) {
        val cleaned = book.copy(conversations = book.conversations.map { it.copy(recallSourceIds = emptyList()) })
        settings.putString(KEY, Json.encodeToString(Book.serializer(), cleaned))
    }

    suspend fun list(): List<CollectiveConversation> = mutex.withLock { read().conversations }

    suspend fun create(): CollectiveConversation = mutex.withLock {
        val book = read()
        var id: String
        do { id = "collective:${now()}:${Random.nextLong()}" } while (book.conversations.any { it.id == id })
        val conversation = CollectiveConversation(id)
        write(Book((listOf(conversation) + book.conversations).take(MAX_CONVERSATIONS)))
        conversation
    }

    suspend fun get(id: String): CollectiveConversation? = mutex.withLock {
        read().conversations.singleOrNull { it.id == id }
    }

    /** Refuse a missing/deleted conversation rather than resurrect it with an in-flight answer. */
    suspend fun append(id: String, question: String, result: CollectiveAnswer): CollectiveConversation? = mutex.withLock {
        val book = read()
        val old = book.conversations.singleOrNull { it.id == id } ?: return@withLock null
        fun clean(text: String, max: Int) = ControlRedaction.scrub(text).take(max)
        val turn = CollectiveTurn(clean(question, 1500), result.copy(
            answer = result.answer?.let { clean(it, 1200) },
            assessments = result.assessments.map {
                it.copy(role = clean(it.role, 128), text = clean(it.text, 1200),
                    sourceId = it.sourceId?.let { source -> clean(source, 128) },
                    modelId = it.modelId?.let { model -> clean(model, 128) },
                    specialistId = it.specialistId?.takeIf { id -> id.matches(Regex("[a-f0-9]{64}")) })
            },
            unavailable = result.unavailable.take(16).map { clean(it, 128) },
        ))
        val updated = old.copy(turns = (old.turns + turn).takeLast(MAX_TURNS))
        write(Book(listOf(updated) + book.conversations.filterNot { it.id == id }))
        updated
    }

    suspend fun delete(id: String) = mutex.withLock {
        write(Book(read().conversations.filterNot { it.id == id }))
    }

    suspend fun clear() = mutex.withLock { settings.remove(KEY) }

    private companion object {
        const val KEY = "aive.collective.conversations.v1"
        const val MAX_CONVERSATIONS = 12
        const val MAX_TURNS = MAX_COLLECTIVE_HISTORY_TURNS
    }
}
