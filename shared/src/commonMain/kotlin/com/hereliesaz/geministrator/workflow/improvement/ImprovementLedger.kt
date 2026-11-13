package com.hereliesaz.geministrator.workflow.improvement

import com.hereliesaz.geministrator.persistence.refuseIfUnreadable
import com.hereliesaz.geministrator.persistence.readQuarantining
import com.hereliesaz.geministrator.persistence.ChunkedStringSettings
import com.russhwolf.settings.Settings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class ImprovementKind(val label: String) {
    RoleInstruction("role-instruction"),
    HiveStep("hive-step"),
    Schedule("schedule"),
    LoopDefinition("loop-definition"),
    EvidenceRule("evidence-rule"),
    /** A habit the swarm formed, offered as a governed step in a hive (from the habits layer). */
    HabitTask("habit-task"),
    Other("other"),
    ;

    companion object {
        fun of(text: String): ImprovementKind = entries.firstOrNull { it.label == text.trim().lowercase() } ?: Other
    }
}

@Serializable
enum class ProposalStatus { Proposed, Adopted, Dismissed, Reverted, Superseded }

/** Which mechanism noticed it. All of them end up in the one ledger and are decided in the one place. */
@Serializable
enum class ImprovementSource {
    /** The self-improvement cycle (a model, challenged twice). */
    Loop,

    /** Deterministic heuristics over run history: repeated failures, fixes, the keeper's corrections. */
    RoleHistory,

    /** The habits layer: an operation the swarm did often enough to be a habit. */
    Habit,

    /** The queen's mid-run suggestions (per run, decided in Messages; see queen/QueenSuggestions.kt). */
    Queen,
}

/**
 * One thing the loop proposes. It is only a record: nothing in it changes a role, a hive or a schedule
 * until the keeper adopts it. [newText] is the exact text; [appliedBefore] and [appliedAfter] are kept on
 * adoption so it can be reverted.
 */
@Serializable
data class ImprovementProposal(
    val id: String,
    val cycleRunId: String,
    val kind: ImprovementKind,
    val target: String,
    val summary: String,
    val newText: String,
    val expectedEffect: String,
    val undo: String,
    /** Whether it is about the loop itself. Decided by the target and kind only, never by what it says. */
    val meta: Boolean,
    val status: ProposalStatus = ProposalStatus.Proposed,
    val createdAtEpochMillis: Long,
    val decidedAtEpochMillis: Long? = null,
    val appliedBefore: String? = null,
    val appliedAfter: String? = null,
    val note: String? = null,
    val source: ImprovementSource = ImprovementSource.Loop,
    /** The id in the mechanism that made it (a role-instruction proposal id, a memory bank), to decide it there too. */
    val sourceRef: String? = null,
    /** The evidence the mechanism gave, bounded and quoted. */
    val evidence: String = "",
    // The queen's own fields (source Queen only). All default, so a ledger written before they existed still reads.
    // For a queen row [cycleRunId] is the run it was made for and [sourceRef] the swarm's id.
    /** The queen's action, by name ([com.hereliesaz.geministrator.queen.QueenAction]). */
    val action: String? = null,
    /** The sentence she says, by name ([com.hereliesaz.geministrator.queen.QueenVoice]); [summary] is the English fallback. */
    val voice: String? = null,
    /** The slots of that sentence: counts and bounded names. */
    val args: List<String> = emptyList(),
    /** Whether it rests on content from outside the keeper's own; then it is labelled and asks before it applies. */
    val outside: Boolean = false,
)

/** What one finished cycle left: how many proposals, and whether it examined the loop (the clause). */
@Serializable
data class CycleRecord(
    val runId: String,
    val atEpochMillis: Long,
    val proposals: Int,
    val metaProposals: Int,
    /** The evidence for "the loop needs no change", when the cycle said that instead of proposing one. */
    val metaNoChange: String? = null,
    val malformedLines: Int = 0,
) {
    /**
     * A cycle examined the loop when it proposed a change to it or said, with evidence, that none was needed.
     * A reason that is only a few words, or cites no figure from the evidence, is not an examination
     * ([MetaNoChange.isExamination]).
     */
    val examinedLoop: Boolean get() = metaProposals > 0 || MetaNoChange.isExamination(metaNoChange)
}

/**
 * What a `META-NO-CHANGE` line has to carry to count as the loop having examined itself
 * (SELF_IMPROVEMENT.md §4, point 2). The reason is a model's word, so the check is only that it is more than
 * a gesture: a sentence's worth of text that cites at least one number from the evidence it was given (a
 * count, a rate, a cost). It cannot tell a good reason from a plausible one; the next cycle is fed it as a
 * finding to re-examine ([SwarmEvidence]) and the keeper reads it.
 */
object MetaNoChange {
    const val MIN_CHARS = 60

    fun isExamination(reason: String?): Boolean {
        val text = reason?.trim().orEmpty()
        return text.length >= MIN_CHARS && text.any { it.isDigit() }
    }
}

@Serializable
data class ImprovementState(
    val proposals: List<ImprovementProposal> = emptyList(),
    val cycles: List<CycleRecord> = emptyList(),
    /**
     * Run ids of every cycle ever read, kept apart from the bounded [cycles] so that a cycle older than the retained
     * records is still recognised as read (otherwise startup backfill re-imports its proposals as Proposed again,
     * resurrecting dismissed ones and duplicating adopted ones). Itself capped at [MAX_INGESTED_IDS], oldest first.
     */
    val ingestedRunIds: List<String> = emptyList(),
) {
    /** True when the cycle of [runId] has been read, whether or not its record is still retained. */
    fun hasIngested(runId: String): Boolean = ingestedRunIds.contains(runId) || cycles.any { it.runId == runId }

    companion object {
        const val MAX_CYCLES = 200
        const val MAX_INGESTED_IDS = 5_000
    }
}

/** The proposal lines of a cycle's last step, read into records. Deterministic; no model. */
object ImprovementParser {
    data class Parsed(val proposals: List<ImprovementProposal>, val metaNoChange: String?, val malformed: Int)

    private const val SUMMARY = 200
    private const val TEXT = 1_200
    private const val FIELD = 300

    fun parse(text: String, cycleRunId: String, nowEpochMillis: Long): Parsed {
        val proposals = mutableListOf<ImprovementProposal>()
        var noChange: String? = null
        var malformed = 0
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith(NO_CHANGE_PREFIX) -> {
                    val reason = line.substringAfter('|', "").trim()
                    if (reason.isNotEmpty()) noChange = clean(reason, TEXT) else malformed += 1
                }
                line.startsWith(LINE_PREFIX) -> {
                    val parts = fields(line)
                    if (parts.size != FIELDS || parts[3].isBlank() || parts[5].isBlank()) {
                        malformed += 1
                        continue
                    }
                    val kind = ImprovementKind.of(parts[1])
                    val target = clean(parts[2], FIELD)
                    proposals += ImprovementProposal(
                        id = "$cycleRunId-${proposals.size + 1}",
                        cycleRunId = cycleRunId,
                        kind = kind,
                        target = target,
                        summary = clean(parts[3], SUMMARY),
                        newText = clean(parts[4].takeIf { it != "-" }.orEmpty(), TEXT),
                        expectedEffect = clean(parts[5], FIELD),
                        undo = clean(parts[6], FIELD),
                        meta = isMeta(kind, target),
                        createdAtEpochMillis = nowEpochMillis,
                    )
                }
            }
        }
        return Parsed(proposals, noChange, malformed)
    }

    /** What a proposal line, and the line that says the loop needs no change, start with. */
    const val LINE_PREFIX = "IMPROVEMENT"
    const val NO_CHANGE_PREFIX = "META-NO-CHANGE"

    /** A proposal line has exactly this many fields: the word IMPROVEMENT, then the six of [SelfImprovementLoop.GRAMMAR]. */
    const val FIELDS = 7

    /**
     * The fields of a proposal line. They are separated by space, pipe, space; a pipe that belongs to a field's text
     * is written `\|` (as [SelfImprovementLoop.GRAMMAR] tells the writer), so it cannot shift the fields after it.
     * A line with more or fewer separators than the grammar has comes back with the wrong number of fields and is
     * counted as malformed by the caller, never read with its fields moved. Lines written without any escape, as
     * every line was before the escape existed, read exactly as they did.
     */
    internal fun fields(line: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var i = 0
        while (i < line.length) {
            if (line.startsWith(SEPARATOR, i)) {
                out += current.toString()
                current.clear()
                i += SEPARATOR.length
            } else {
                current.append(line[i])
                i += 1
            }
        }
        out += current.toString()
        return out.map { it.replace("\\|", "|").trim() }
    }

    private const val SEPARATOR = " | "

    /** About the loop: by kind or by target, and never by how the proposal is worded. */
    fun isMeta(kind: ImprovementKind, target: String): Boolean =
        kind == ImprovementKind.LoopDefinition || kind == ImprovementKind.EvidenceRule || SelfImprovementLoop.isOwn(target)

    /** Agent text is untrusted: bounded, with control characters dropped. */
    private fun clean(text: String, max: Int): String =
        text.filter { it == '\n' || it == '\t' || !it.isISOControl() }.trim().take(max)
}

/** The loop's obligation to examine itself (SELF_IMPROVEMENT.md §4, point 2). */
object LoopObligation {
    const val MAX_GAP_MILLIS = 21L * 24 * 3_600_000

    enum class Standing { Examined, NeverRan, Overdue, LastCycleSkippedItself }

    fun standing(state: ImprovementState, now: Long, maxGapMillis: Long = MAX_GAP_MILLIS): Standing {
        val last = state.cycles.maxByOrNull { it.atEpochMillis } ?: return Standing.NeverRan
        if (!last.examinedLoop) return Standing.LastCycleSkippedItself
        val lastExamined = state.cycles.filter { it.examinedLoop }.maxOf { it.atEpochMillis }
        return if (now - lastExamined > maxGapMillis) Standing.Overdue else Standing.Examined
    }
}

/**
 * What the ledger forgets when it is full. An adopted proposal is never forgotten: it holds the text it replaced
 * ([ImprovementProposal.appliedBefore]), which is all that lets the keeper revert it. Settled and dismissed ones go
 * first, oldest first, then ones still waiting; so the cap may be exceeded by adopted ones, never by anything else.
 */
object ProposalRetention {
    const val MAX = 500

    /** The queen's rows are per run and short-lived: at most this many are kept, the oldest dropped first. */
    const val MAX_QUEEN = 100

    private val dropOrder = listOf(ProposalStatus.Dismissed, ProposalStatus.Superseded, ProposalStatus.Reverted, ProposalStatus.Proposed)

    /** [proposals] with the queen's oldest rows dropped beyond [MAX_QUEEN]; every other source is left alone. */
    internal fun boundQueen(proposals: List<ImprovementProposal>): List<ImprovementProposal> {
        var surplus = proposals.count { it.source == ImprovementSource.Queen } - MAX_QUEEN
        if (surplus <= 0) return proposals
        val kept = mutableListOf<ImprovementProposal>()
        for (proposal in proposals) {
            if (proposal.source == ImprovementSource.Queen && surplus > 0) {
                surplus -= 1
            } else {
                kept += proposal
            }
        }
        return kept
    }

    fun bound(all: List<ImprovementProposal>, max: Int = MAX): List<ImprovementProposal> {
        val proposals = boundQueen(all)
        var excess = proposals.size - max
        if (excess <= 0) return proposals
        val drop = hashSetOf<Int>()
        for (status in dropOrder) {
            for ((index, proposal) in proposals.withIndex()) {
                if (excess <= 0) break
                if (proposal.status == status) {
                    drop += index
                    excess -= 1
                }
            }
        }
        return proposals.filterIndexed { index, _ -> index !in drop }
    }
}

/** Where the keeper's proposals and cycle records are kept, on this device. */
interface ImprovementStore {
    suspend fun state(): ImprovementState

    /** Adds a finished cycle's proposals once; a cycle already recorded is left as it is. True when it was added. */
    suspend fun ingest(cycle: CycleRecord, proposals: List<ImprovementProposal>): Boolean

    /**
     * Applies [update] to proposal [id], but only while it is still [expected] (a compare-and-set, under the store's
     * lock). True when it was updated; false when the proposal is not in the ledger or has already been decided,
     * which the caller must not treat as done.
     */
    suspend fun decide(id: String, expected: ProposalStatus = ProposalStatus.Proposed, update: (ImprovementProposal) -> ImprovementProposal): Boolean

    /** Replaces the state with [update] of it, under the store's lock. For mirroring the other mechanisms. */
    suspend fun merge(update: (ImprovementState) -> ImprovementState)
}

class SettingsImprovementStore(
    settings: Settings = Settings(),
    private val key: String = DEFAULT_KEY,
) : ImprovementStore {
    private val settings: Settings = ChunkedStringSettings.unbounded(settings)
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun state(): ImprovementState = mutex.withLock { read() }

    override suspend fun ingest(cycle: CycleRecord, proposals: List<ImprovementProposal>): Boolean = mutex.withLock {
        val now = read()
        if (now.hasIngested(cycle.runId)) return@withLock false
        write(
            ImprovementState(
                ProposalRetention.bound(now.proposals + proposals),
                (now.cycles + cycle).takeLast(ImprovementState.MAX_CYCLES),
                (now.ingestedRunIds + now.cycles.map { it.runId } + cycle.runId).distinct().takeLast(ImprovementState.MAX_INGESTED_IDS),
            ),
        )
        true
    }

    override suspend fun decide(id: String, expected: ProposalStatus, update: (ImprovementProposal) -> ImprovementProposal): Boolean = mutex.withLock {
        val now = read()
        val target = now.proposals.firstOrNull { it.id == id }
        if (target == null || target.status != expected) return@withLock false
        write(now.copy(proposals = now.proposals.map { if (it.id == id) update(it) else it }))
        true
    }

    override suspend fun merge(update: (ImprovementState) -> ImprovementState) = mutex.withLock {
        val now = read()
        val next = update(now)
        if (next != now) write(next.copy(proposals = ProposalRetention.bound(next.proposals)))
    }

    private fun read(): ImprovementState =
        settings.readQuarantining(key) { json.decodeFromString(ImprovementState.serializer(), it) } ?: ImprovementState()

    private fun write(state: ImprovementState) {
        settings.refuseIfUnreadable(key) { json.decodeFromString(ImprovementState.serializer(), it) }
        settings.putString(key, json.encodeToString(ImprovementState.serializer(), state))
    }

    companion object {
        const val DEFAULT_KEY = "aive.improvement-ledger.v1"
    }
}

class InMemoryImprovementStore : ImprovementStore {
    private var state = ImprovementState()
    override suspend fun state() = state
    override suspend fun ingest(cycle: CycleRecord, proposals: List<ImprovementProposal>): Boolean {
        if (state.hasIngested(cycle.runId)) return false
        state = ImprovementState(
            ProposalRetention.bound(state.proposals + proposals),
            (state.cycles + cycle).takeLast(ImprovementState.MAX_CYCLES),
            (state.ingestedRunIds + cycle.runId).distinct().takeLast(ImprovementState.MAX_INGESTED_IDS),
        )
        return true
    }
    override suspend fun decide(id: String, expected: ProposalStatus, update: (ImprovementProposal) -> ImprovementProposal): Boolean {
        val target = state.proposals.firstOrNull { it.id == id }
        if (target == null || target.status != expected) return false
        state = state.copy(proposals = state.proposals.map { if (it.id == id) update(it) else it })
        return true
    }
    override suspend fun merge(update: (ImprovementState) -> ImprovementState) { state = update(state).let { it.copy(proposals = ProposalRetention.bound(it.proposals)) } }
}

/** Reads a finished cycle into a record and proposals, ready for [ImprovementStore.ingest]. */
fun readCycle(runId: String, finalText: String, nowEpochMillis: Long): Pair<CycleRecord, List<ImprovementProposal>> {
    val parsed = ImprovementParser.parse(finalText, runId, nowEpochMillis)
    return CycleRecord(
        runId = runId,
        atEpochMillis = nowEpochMillis,
        proposals = parsed.proposals.size,
        metaProposals = parsed.proposals.count { it.meta },
        metaNoChange = parsed.metaNoChange,
        malformedLines = parsed.malformed,
    ) to parsed.proposals
}
