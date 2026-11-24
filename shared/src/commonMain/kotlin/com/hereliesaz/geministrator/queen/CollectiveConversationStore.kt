package com.hereliesaz.geministrator.queen

import com.hereliesaz.geministrator.control.ControlRedaction
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
internal data class CollectiveConversation(val id: String, val turns: List<CollectiveTurn> = emptyList()) {
    fun context(): String = turns.joinToString("\n") {
        "Keeper: ${it.question}\nAive: ${it.result.answer.orEmpty()}"
    }.takeLast(1800)
}

/**
 * Local-only, bounded transcript ledger. Callers never supply history or mint a resumed identity.
 * Saves only returned turns, redacted before writing. No bank passages or approval state is stored.
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
    private fun write(book: Book) = settings.putString(KEY, Json.encodeToString(Book.serializer(), book))

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
            assessments = result.assessments.take(2).map {
                it.copy(role = clean(it.role, 128), text = clean(it.text, 1200),
                    sourceId = it.sourceId?.let { source -> clean(source, 128) },
                    modelId = it.modelId?.let { model -> clean(model, 128) })
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
        const val MAX_TURNS = 16
    }
}
