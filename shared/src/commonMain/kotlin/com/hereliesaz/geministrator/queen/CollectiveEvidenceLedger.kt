package com.hereliesaz.geministrator.queen

import com.hereliesaz.geministrator.control.ControlRedaction
import com.hereliesaz.geministrator.persistence.ChunkedStringSettings
import com.russhwolf.settings.Settings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.random.Random

/** Nature of a contribution, not memory eligibility or a trust classification. */
@Serializable
internal enum class EvidenceKind { Observation, CitedClaim, Hypothesis, Opinion, KeeperDecision }

@Serializable
internal data class EvidenceEntry(
    val id: String, val issueId: String, val question: String, val kind: EvidenceKind,
    val text: String, val source: String, val recordedAt: Long,
    val conversationId: String? = null, val roleId: String? = null,
    val specialistId: String? = null, val modelId: String? = null,
    /** Host-recorded author category, distinct from the source supplied by the author. */
    val recordedByKeeper: Boolean = false,
)

/** A separate Keeper-recorded assessment; neither support nor verification rewrites the original. */
@Serializable
internal enum class EvidenceRelation { Supports, Contradicts, VerifiedByKeeper, RetractedByKeeper }

@Serializable
internal data class EvidenceReview(
    val id: String, val targetId: String, val relatedId: String?, val relation: EvidenceRelation,
    val reason: String, val recordedAt: Long,
)

@Serializable
internal data class EvidenceBook(
    val entries: List<EvidenceEntry> = emptyList(), val reviews: List<EvidenceReview> = emptyList(),
)

/**
 * Durable deliberation record, not an associative memory store. Records actual contributions
 * and explicit Keeper assessments. Neither agreement nor recording grants execution permission.
 * Original entries and contradictory assessments remain independently inspectable.
 */
internal class CollectiveEvidenceLedger(settings: Settings, private val now: () -> Long) {
    private val storage = ChunkedStringSettings.unbounded(settings)
    private val mutex = Mutex()
    private fun read(): EvidenceBook = storage.getStringOrNull(KEY)?.let {
        Json.decodeFromString(EvidenceBook.serializer(), it)
    } ?: EvidenceBook()
    private fun write(book: EvidenceBook) = storage.putString(KEY, Json.encodeToString(EvidenceBook.serializer(), book))
    private fun clean(text: String, max: Int): String = ControlRedaction.scrub(text).trim().take(max)
    private fun identity(): String = "evidence:" + (1..2).joinToString("") {
        Random.nextLong().toULong().toString(16).padStart(16, '0')
    }

    /** One issue per host turn, not per specialist. */
    fun issueId(): String = identity()
    suspend fun snapshot(): EvidenceBook = mutex.withLock { read() }

    /** Actual advisory text stays an opinion regardless of its author or the number agreeing. */
    suspend fun recordAdvisory(issue: String, conversation: String, question: String, voice: CollectiveVoice, reply: String) {
        val source = voice.model.uses.singleOrNull()
        append(issue, question, EvidenceKind.Opinion, reply, source?.sourceId ?: "Configured Source not recorded",
            conversation, voice.role, voice.specialistId, source?.modelId)
    }

    /** Explicit local Keeper contribution with a stated source. */
    suspend fun recordKeeper(issue: String?, question: String, kind: EvidenceKind, text: String, source: String): EvidenceEntry =
        append(issue ?: issueId(), question, kind, text, source, keeper = true)

    private suspend fun append(issue: String, question: String, kind: EvidenceKind, text: String, source: String,
        conversation: String? = null, role: String? = null, specialist: String? = null, model: String? = null,
        keeper: Boolean = false): EvidenceEntry =
        mutex.withLock {
            val entry = EvidenceEntry(identity(), issue, clean(question, 1500), kind, clean(text, 12_000),
                clean(source, 512), now(), conversation, role, specialist, model?.let { clean(it, 128) }, keeper)
            require(entry.question.isNotBlank() && entry.text.isNotBlank() && entry.source.isNotBlank())
            val book = read()
            write(book.copy(entries = book.entries + entry))
            entry
        }

    /**
     * Record a Keeper assessment and rationale. Verification references a separate observation
     * or cited claim. It records the Keeper's assessment, not an automatic proof of truth.
     * Retraction leaves the original and earlier assessments visible.
     */
    suspend fun review(target: String, related: String?, relation: EvidenceRelation, reason: String): EvidenceReview =
        mutex.withLock {
            val book = read()
            require(book.entries.any { it.id == target })
            val evidence = related?.let { id -> book.entries.singleOrNull { it.id == id } ?: error("Evidence not found") }
            require(related != target)
            when (relation) {
                EvidenceRelation.Supports, EvidenceRelation.Contradicts -> require(evidence != null)
                EvidenceRelation.VerifiedByKeeper -> require(evidence?.kind in setOf(EvidenceKind.Observation, EvidenceKind.CitedClaim))
                EvidenceRelation.RetractedByKeeper -> require(related == null)
            }
            val note = clean(reason, 2000)
            require(note.isNotBlank())
            val review = EvidenceReview(identity(), target, related, relation, note, now())
            write(book.copy(reviews = book.reviews + review))
            review
        }

    /** Explicit evidence erasure removes dangling assessments without changing memory recall. */
    suspend fun forget(id: String) = mutex.withLock {
        val book = read()
        write(book.copy(entries = book.entries.filterNot { it.id == id },
            reviews = book.reviews.filterNot { it.targetId == id || it.relatedId == id }))
    }
    suspend fun clear() = mutex.withLock { storage.remove(KEY) }

    private companion object { const val KEY = "aive.collective.evidence.v1" }
}
