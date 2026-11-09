package com.hereliesaz.geministrator.workflow.improvement

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
    /** A cycle examined the loop when it proposed a change to it or said, with evidence, that none was needed. */
    val examinedLoop: Boolean get() = metaProposals > 0 || !metaNoChange.isNullOrBlank()
}

@Serializable
data class ImprovementState(
    val proposals: List<ImprovementProposal> = emptyList(),
    val cycles: List<CycleRecord> = emptyList(),
)

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
                line.startsWith("META-NO-CHANGE") -> {
                    val reason = line.substringAfter('|', "").trim()
                    if (reason.isNotEmpty()) noChange = clean(reason, TEXT) else malformed += 1
                }
                line.startsWith("IMPROVEMENT") -> {
                    val parts = line.split(" | ").map(String::trim)
                    if (parts.size < 7 || parts[3].isBlank() || parts[5].isBlank()) {
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

/** Where the keeper's proposals and cycle records are kept, on this device. */
interface ImprovementStore {
    suspend fun state(): ImprovementState

    /** Adds a finished cycle's proposals once; a cycle already recorded is left as it is. True when it was added. */
    suspend fun ingest(cycle: CycleRecord, proposals: List<ImprovementProposal>): Boolean

    suspend fun decide(id: String, update: (ImprovementProposal) -> ImprovementProposal)

    /** Replaces the state with [update] of it, under the store's lock. For mirroring the other mechanisms. */
    suspend fun merge(update: (ImprovementState) -> ImprovementState)
}

class SettingsImprovementStore(
    private val settings: Settings = Settings(),
    private val key: String = DEFAULT_KEY,
) : ImprovementStore {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun state(): ImprovementState = mutex.withLock { read() }

    override suspend fun ingest(cycle: CycleRecord, proposals: List<ImprovementProposal>): Boolean = mutex.withLock {
        val now = read()
        if (now.cycles.any { it.runId == cycle.runId }) return@withLock false
        write(ImprovementState((now.proposals + proposals).takeLast(500), (now.cycles + cycle).takeLast(200)))
        true
    }

    override suspend fun decide(id: String, update: (ImprovementProposal) -> ImprovementProposal) = mutex.withLock {
        val now = read()
        write(now.copy(proposals = now.proposals.map { if (it.id == id) update(it) else it }))
    }

    override suspend fun merge(update: (ImprovementState) -> ImprovementState) = mutex.withLock {
        val now = read()
        val next = update(now)
        if (next != now) write(next.copy(proposals = next.proposals.takeLast(500)))
    }

    private fun read(): ImprovementState = settings.getStringOrNull(key)
        ?.let { runCatching { json.decodeFromString(ImprovementState.serializer(), it) }.getOrNull() }
        ?: ImprovementState()

    private fun write(state: ImprovementState) {
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
        if (state.cycles.any { it.runId == cycle.runId }) return false
        state = ImprovementState(state.proposals + proposals, state.cycles + cycle)
        return true
    }
    override suspend fun decide(id: String, update: (ImprovementProposal) -> ImprovementProposal) {
        state = state.copy(proposals = state.proposals.map { if (it.id == id) update(it) else it })
    }
    override suspend fun merge(update: (ImprovementState) -> ImprovementState) { state = update(state) }
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
