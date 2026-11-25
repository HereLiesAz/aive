package com.hereliesaz.geministrator.queen

import com.hereliesaz.geministrator.Sha256
import com.hereliesaz.geministrator.control.ControlRedaction
import com.hereliesaz.geministrator.domain.ArtifactId
import com.hereliesaz.geministrator.persistence.ArtifactRepository
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.workflow.LocalTestReceipt
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.research.ResearchBudget
import com.hereliesaz.geministrator.research.ResearchClaim
import com.hereliesaz.geministrator.research.MAX_RESEARCH_FACTS
import com.hereliesaz.geministrator.research.ResearchOutcome
import com.hereliesaz.geministrator.research.toAvailableTools
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
    /** Host-computed correspondence to the exact bounded research source, not a truth verdict. */
    val researchCitation: EvidenceResearchCitation? = null,
    /** Only the actual local executor result can populate this observation. */
    val localTest: EvidenceLocalTest? = null,
)

/** Process result independent of report content, not an acceptance-criteria verdict. */
@Serializable
internal data class EvidenceLocalTest(
    val exitCode: Int, val timedOut: Boolean, val started: Boolean,
    val checkoutCommit: String? = null,
)

/** Local citation evidence with source position and the actual redacted excerpt shown to the model. */
@Serializable
internal data class EvidenceResearchCitation(
    val sourceNumber: Int, val sourceType: String, val sourceName: String,
    val sourceExcerpt: String, val matchesSourceText: Boolean,
)

/** A separate Keeper-recorded assessment; neither support nor verification rewrites the original. */
@Serializable
internal enum class EvidenceRelation { Supports, Contradicts, VerifiedByKeeper, RetractedByKeeper }

@Serializable
internal data class EvidenceReview(
    val id: String, val targetId: String, val relatedId: String?, val relation: EvidenceRelation,
    val reason: String, val recordedAt: Long,
)

/** A redacted projection awaiting confirmation of the exact durable artifact version. */
@Serializable
internal data class PendingArtifactEvidence(
    val artifactId: ArtifactId, val fingerprint: String, val entry: EvidenceEntry,
    val observation: EvidenceEntry? = null,
)

@Serializable
internal data class EvidenceBook(
    val entries: List<EvidenceEntry> = emptyList(), val reviews: List<EvidenceReview> = emptyList(),
    /** Journal entries are not visible evidence and never enter the Keeper's snapshot. */
    val pendingArtifacts: List<PendingArtifactEvidence> = emptyList(),
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
    suspend fun snapshot(): EvidenceBook = mutex.withLock { read().copy(pendingArtifacts = emptyList()) }

    /** Actual advisory text stays an opinion regardless of its author or the number agreeing. */
    suspend fun recordAdvisory(issue: String, conversation: String, question: String, voice: CollectiveVoice, reply: String) {
        val source = voice.model.uses.singleOrNull()
        append(issue, question, EvidenceKind.Opinion, reply, source?.sourceId ?: "Configured Source not recorded",
            conversation, voice.role, voice.specialistId, source?.modelId)
    }

    /**
     * Retain the redacted, bounded lead projection and bundled excerpts returned by the research step.
     * Source text remains a cited claim, not an observation proving its contents or a Keeper decision.
     * The lookup key identifies the originating operation; it grants no memory or execution permission.
     * Empty results (including declined or failed lookups with no returned content) create no entry.
     */
    suspend fun recordResearch(question: String, runKey: String, outcome: ResearchOutcome, budget: ResearchBudget) {
        val leads = outcome.leads.map {
            it.copy(name = ControlRedaction.scrub(it.name), trust = ControlRedaction.scrub(it.trust),
                why = ControlRedaction.scrub(it.why))
        }
        val lines = (leads.toAvailableTools(budget) + outcome.docs).distinct()
        if (lines.isEmpty()) return
        val issue = issueId()
        mutex.withLock {
            val entries = lines.mapNotNull { line ->
                val statement = clean(line, 12_000)
                if (statement.isBlank()) null else EvidenceEntry(
                    identity(), issue, clean(question, 1500).ifBlank { "Research" },
                    EvidenceKind.CitedClaim, statement, clean("Research operation: $runKey", 512),
                    now(),
                )
            }
            if (entries.isNotEmpty()) {
                val book = read()
                write(book.copy(entries = book.entries + entries))
            }
        }
    }

    /**
     * Store model claims and their deterministic citation checks. A mismatched claim is a hypothesis,
     * not a source claim, and never becomes a verification or approval. The exact source excerpt
     * remains inspectable even when the model quoted it incorrectly.
     */
    suspend fun recordResearchClaims(question: String, runKey: String, claims: List<ResearchClaim>) {
        if (claims.isEmpty()) return
        val issue = issueId()
        mutex.withLock {
            val book = read()
            val entries = claims.take(MAX_RESEARCH_FACTS).map { claim ->
                EvidenceEntry(
                    identity(), issue, clean(question, 1500).ifBlank { "Research" },
                    if (claim.matchesSourceText) EvidenceKind.CitedClaim else EvidenceKind.Hypothesis,
                    clean(claim.statement, 12_000), clean("Research operation: $runKey", 512), now(),
                    researchCitation = EvidenceResearchCitation(
                        claim.sourceNumber, claim.source.label, clean(claim.sourceName, 60),
                        clean(claim.sourceExcerpt, 160), claim.matchesSourceText,
                    ),
                )
            }
            write(book.copy(entries = book.entries + entries))
        }
    }

    /**
     * Journal a redacted projection before the coordinator writes a new artifact version. It is not
     * visible until [recoverArtifactEvidence] confirms that exact version in the artifact repository.
     * Even a test result can contain an unsupported assertion: receipt does not verify the payload.
     * URI-only artifacts are recorded by reference; nothing is fetched or executed. Pending versions
     * are replaced only by a newly staged version of the same artifact and are erased on clear.
     */
    suspend fun stageArtifactEvidence(run: WorkflowRun, artifact: ArtifactRef) = stageArtifact(run, artifact, null)

    /** Stage an executor observation alongside, never instead of, the artifact's untrusted claim. */
    suspend fun stageLocalTestEvidence(run: WorkflowRun, artifact: ArtifactRef, receipt: LocalTestReceipt) {
        require(receipt.artifactId == artifact.id)
        stageArtifact(run, artifact, receipt)
    }

    private suspend fun stageArtifact(run: WorkflowRun, artifact: ArtifactRef, receipt: LocalTestReceipt?) {
        val question = run.objective.ifBlank { "Workflow ${run.id.value}" }
        val text = buildString {
            append(artifact.kind.name).append(": ").append(clean(artifact.label, 512))
            artifact.textContent?.let { append("\n").append(clean(it, 12_000)) }
            if (artifact.textContent == null) append("\nArtifact content not read (reference only).")
        }
        // Raw artifact IDs are needed only to read the authoritative repository; reject IDs that
        // look like credentials rather than writing them unredacted into the recovery journal.
        require(ControlRedaction.scrub(artifact.id.value) == artifact.id.value)
        val fingerprint = artifactFingerprint(artifact)
        mutex.withLock {
            val book = read()
            val prior = book.pendingArtifacts.singleOrNull { it.artifactId == artifact.id }
            if (prior?.fingerprint == fingerprint && (receipt == null || prior.observation != null)) return@withLock
            require(prior != null || book.pendingArtifacts.size < MAX_PENDING_ARTIFACTS) {
                "Evidence recovery journal is full"
            }
            val entry = EvidenceEntry(
                identity(), "workflow:${clean(run.id.value, 256)}", clean(question, 1500),
                EvidenceKind.CitedClaim, clean(text, 12_000),
                clean("Run ${run.id.value} | task run ${artifact.taskRunId.value} | artifact ${artifact.id.value}", 512),
                now(),
            )
            require(entry.question.isNotBlank() && entry.text.isNotBlank())
            val observation = receipt?.let {
                val commit = it.checkoutCommit?.takeIf { value -> value.matches(Regex("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}")) }
                val outcome = when {
                    !it.started -> "Local test command could not start."
                    it.timedOut -> "Local test command timed out."
                    it.passed -> "Local test command exited successfully (exit 0)."
                    else -> "Local test command failed (exit ${it.exitCode})."
                }
                EvidenceEntry(
                    identity(), entry.issueId, entry.question, EvidenceKind.Observation,
                    outcome, entry.source, now(),
                    localTest = EvidenceLocalTest(it.exitCode, it.timedOut, it.started, commit),
                )
            }
            write(book.copy(pendingArtifacts = book.pendingArtifacts.filterNot { it.artifactId == artifact.id } +
                PendingArtifactEvidence(artifact.id, fingerprint, entry, observation)))
        }
    }

    /** Canonicalize metadata ordering; retain only the digest, not the raw artifact or its URI. */
    private fun artifactFingerprint(artifact: ArtifactRef): String = Sha256.hex(
        Json.encodeToString(ArtifactRef.serializer(),
            artifact.copy(metadata = artifact.metadata.entries.sortedBy { it.key }.associate { it.key to it.value }),
        ).encodeToByteArray(),
    )

    /**
     * Finalize matching durable receipts in a single ledger write. Called after coordinator writes
     * and during bootstrap; an interrupted commit is replayed only from the existing journal.
     * Missing or changed artifacts stay pending, never become evidence on their own, and an erased
     * journal cannot be reconstructed from historical artifacts. No provider or verifier is called.
     */
    suspend fun recoverArtifactEvidence(artifacts: ArtifactRepository) = mutex.withLock {
        val book = read()
        if (book.pendingArtifacts.isEmpty()) return@withLock
        val confirmed = mutableListOf<PendingArtifactEvidence>()
        for (pending in book.pendingArtifacts) {
            val artifact = artifacts.get(pending.artifactId) ?: continue
            if (artifactFingerprint(artifact) == pending.fingerprint) confirmed += pending
        }
        if (confirmed.isNotEmpty()) write(book.copy(
            entries = book.entries + confirmed.flatMap { listOfNotNull(it.entry, it.observation) },
            pendingArtifacts = book.pendingArtifacts.filterNot { it in confirmed },
        ))
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

    private companion object {
        const val KEY = "aive.collective.evidence.v1"
        const val MAX_PENDING_ARTIFACTS = 256
    }
}
