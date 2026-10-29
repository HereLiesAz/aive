package com.hereliesaz.geministrator.memory.procedural

import com.hereliesaz.geministrator.memory.MemoryLineage
import com.hereliesaz.geministrator.memory.memoryBankKey
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/**
 * A versioned add-only catalog. It intentionally does not store executable payloads or promotion
 * flags. The only supported mutation in this foundation is proposing a new definition or recording
 * attributed evidence; "Verified" in a received report is not a trusted promotion decision.
 */
@Serializable
data class ProcedureSnapshot(
    val revision: Long = 0L,
    val definitions: List<ProcedureDefinition> = emptyList(),
    val evidence: List<ProcedureEvidence> = emptyList(),
    /** Bounded, source-redacted execution receipts, independent of summarized contextual memory. */
    val traces: List<ProcedureTrace> = emptyList(),
)

@Serializable
data class ProcedureMutation(
    val definitionsToAdd: List<ProcedureDefinition> = emptyList(),
    val evidenceToAdd: List<ProcedureEvidence> = emptyList(),
    val tracesToAdd: List<ProcedureTrace> = emptyList(),
)

/** Revisions prevent one candidate writer from overwriting another writer's evidence. */
interface ProcedureStore {
    suspend fun read(): ProcedureSnapshot
    /** False on revision conflict; invalid or cross-bank writes throw. */
    suspend fun commit(expectedRevision: Long, mutation: ProcedureMutation): Boolean
    /** Explicit privacy erasure; its revision is advanced so old writers cannot restore data. */
    suspend fun clear(expectedRevision: Long): Boolean
}

/**
 * Per-workflow test/preview implementation, using exactly the same invariants expected of a
 * future SQLDelight-backed store. No persistence is claimed.
 */
class InMemoryProcedureStore(
    val workflowId: String,
    initial: ProcedureSnapshot = ProcedureSnapshot(),
) : ProcedureStore {
    private val mutex = Mutex()
    private var snapshot: ProcedureSnapshot = initial

    init {
        require(memoryBankKey(workflowId) == workflowId)
        validateInitial(initial)
    }

    override suspend fun read(): ProcedureSnapshot = mutex.withLock { snapshot }

    override suspend fun clear(expectedRevision: Long): Boolean = mutex.withLock {
        if (snapshot.revision != expectedRevision) return@withLock false
        snapshot = ProcedureSnapshot(revision = snapshot.revision + 1)
        true
    }

    override suspend fun commit(expectedRevision: Long, mutation: ProcedureMutation): Boolean = mutex.withLock {
        if (snapshot.revision != expectedRevision) return@withLock false
        require(mutation.definitionsToAdd.isNotEmpty() || mutation.evidenceToAdd.isNotEmpty() || mutation.tracesToAdd.isNotEmpty()) {
            "Empty procedural memory mutation"
        }
        val newDefinitions = snapshot.definitions + mutation.definitionsToAdd
        val newEvidence = snapshot.evidence + mutation.evidenceToAdd
        val newTraces = snapshot.traces + mutation.tracesToAdd
        require(newDefinitions.all { it.ref.workflowId == workflowId }) {
            "Cannot write a procedure belonging to another workflow bank"
        }
        require(newDefinitions.map { it.ref }.distinct().size == newDefinitions.size) {
            "A procedure version is immutable and may be inserted only once"
        }
        require(newEvidence.all { it.procedure.workflowId == workflowId }) {
            "Cannot bank evidence for another workflow"
        }
        require(newEvidence.map { it.id }.distinct().size == newEvidence.size) {
            "Duplicate procedural evidence id"
        }
        require(newEvidence.map { it.procedure to it.receiptId }.distinct().size == newEvidence.size) {
            "An execution receipt may be recorded only once per procedure"
        }
        val validRefs = newDefinitions.mapTo(hashSetOf()) { it.ref }
        require(newEvidence.all { it.procedure in validRefs }) {
            "Evidence must reference an existing procedure in this bank"
        }
        require(newTraces.all { it.workflowId == workflowId }) {
            "Cannot bank a trace for another workflow"
        }
        require(newTraces.map { it.receiptKey }.distinct().size == newTraces.size) {
            "An execution receipt can be stored only once per workflow and attempt"
        }
        snapshot = ProcedureSnapshot(snapshot.revision + 1, newDefinitions, newEvidence, newTraces)
        true
    }

    private fun validateInitial(initial: ProcedureSnapshot) {
        require(initial.revision >= 0)
        require(initial.definitions.all { it.ref.workflowId == workflowId })
        require(initial.definitions.map { it.ref }.distinct().size == initial.definitions.size)
        require(initial.evidence.all { it.procedure.workflowId == workflowId })
        require(initial.evidence.map { it.id }.distinct().size == initial.evidence.size)
        require(initial.evidence.map { it.procedure to it.receiptId }.distinct().size == initial.evidence.size)
        val refs = initial.definitions.mapTo(hashSetOf()) { it.ref }
        require(initial.evidence.all { it.procedure in refs })
        require(initial.traces.all { it.workflowId == workflowId })
        require(initial.traces.map { it.receiptKey }.distinct().size == initial.traces.size)
    }
}

/**
 * A workflow may inspect its own candidates and those of its ancestors, but never write through
 * the lineage view. Neighbouring project workflows and unrelated projects are not discoverable.
 * Shared names do not collapse distinct workflow-scoped versions.
 */
class ProcedureBankCatalog(
    private val lineage: MemoryLineage,
    private val openBank: suspend (workflowId: String) -> ProcedureStore = { InMemoryProcedureStore(it) },
) {
    private val mutex = Mutex()
    private val opened = mutableMapOf<String, ProcedureStore>()

    suspend fun own(workflowId: String): ProcedureStore {
        val key = requireNotNull(memoryBankKey(workflowId)) { "Missing procedure workflow" }
        return mutex.withLock { opened.getOrPut(key) { openBank(key) } }
    }

    suspend fun visible(workflowId: String): List<ProcedureCandidate> {
        val key = requireNotNull(memoryBankKey(workflowId))
        val ancestors = lineage.ancestorsOf(key)
        val ownCandidates = own(key).read().definitions.map { ProcedureCandidate(it, inherited = false) }
        val inherited = ancestors.flatMap { ancestor ->
            own(ancestor).read().definitions.map { ProcedureCandidate(it, inherited = true) }
        }
        return ownCandidates + inherited
    }

    suspend fun lookup(workflowId: String, ref: ProcedureReference): ProcedureCandidate? =
        visible(workflowId).firstOrNull { it.definition.ref == ref }
}
