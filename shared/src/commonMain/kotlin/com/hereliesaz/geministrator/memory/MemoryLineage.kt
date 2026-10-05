package com.hereliesaz.geministrator.memory

import com.russhwolf.settings.Settings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/**
 * Memory is organised like git history.
 *
 * - Every workflow run has its own **memory bank**: the records it appended.
 * - A **continuation** (a run with one parent) inherits its parent's bank by reference and adds to
 *   it; a **merge** (a run with several parents) marries those lineages. A workflow's **lineage
 *   bank** is its own records plus, read-through, every ancestor's records ([LineageMemoryStore]);
 *   nothing is copied and nothing is ever written into an ancestor's bank.
 * - A **project** is the set of workflow runs regarding it. A project can expand at any time to
 *   incorporate another project and so all its workflows (an append-only record).
 * - Within a project, the banks of workflows outside a workflow's lineage are readable at recall,
 *   read-only, ranked after the lineage and labelled. Nothing outside the project is accessible.
 *
 * This graph is add-only: workflows, parent links and project expansions are appended and never
 * removed. Which memory is right is never decided here.
 */
@Serializable
data class MemoryWorkflowRecord(
    val workflowId: String,
    val projectId: String? = null,
    val recordedAtEpochMillis: Long,
)

@Serializable
data class MemoryParentRecord(
    val workflowId: String,
    val parentWorkflowId: String,
    val recordedAtEpochMillis: Long,
)

@Serializable
data class MemoryProjectExpansion(
    val projectId: String,
    /** The project whose workflows [projectId] now includes. */
    val incorporatesProjectId: String,
    val by: String,
    val reason: String,
    val recordedAtEpochMillis: Long,
)

@Serializable
data class MemoryLineageLog(
    val workflows: List<MemoryWorkflowRecord> = emptyList(),
    val parents: List<MemoryParentRecord> = emptyList(),
    val expansions: List<MemoryProjectExpansion> = emptyList(),
)

/** What [MemoryLineage.registerWorkflow] changed. */
data class MemoryLineageChange(val newWorkflow: Boolean, val newParents: List<String>) {
    val changed: Boolean get() = newWorkflow || newParents.isNotEmpty()
}

/**
 * The workflow lineage DAG and project membership, add-only. [settings] persists it; null keeps it
 * in memory (tests, previews).
 */
class MemoryLineage(
    private val settings: Settings? = null,
    private val key: String = "${SettingsMemoryStore.DEFAULT_STORAGE_KEY}.lineage",
) {
    private val mutex = Mutex()
    private var memoryOnly = MemoryLineageLog()

    suspend fun log(): MemoryLineageLog = mutex.withLock { load() }

    /**
     * Records [workflowId] (in [projectId], if any) and any of [parents] not yet recorded. Parents
     * are only ever added. A parent that would make the graph cyclic is refused.
     */
    suspend fun registerWorkflow(
        workflowId: String,
        projectId: String?,
        parents: List<String> = emptyList(),
        atEpochMillis: Long,
    ): MemoryLineageChange = mutex.withLock {
        require(workflowId.isNotBlank()) { "A workflow needs an id" }
        var log = load()
        val project = memoryBankKey(projectId)
        val known = log.workflows.associateBy { it.workflowId }
        val newWorkflow = workflowId !in known
        if (newWorkflow) {
            log = log.copy(workflows = log.workflows + MemoryWorkflowRecord(workflowId, project, atEpochMillis))
        } else if (known.getValue(workflowId).projectId == null && project != null) {
            // A workflow first seen without a project joins its project once (still add-only: a new record).
            log = log.copy(workflows = log.workflows + MemoryWorkflowRecord(workflowId, project, atEpochMillis))
        }
        val added = mutableListOf<String>()
        parents.distinct().filter(String::isNotBlank).forEach { parent ->
            val existing = log.parents.any { it.workflowId == workflowId && it.parentWorkflowId == parent }
            if (existing) return@forEach
            require(parent != workflowId && workflowId !in ancestorsIn(log, parent) ) {
                "Workflow $workflowId cannot continue $parent: that would make the lineage cyclic"
            }
            if (log.workflows.none { it.workflowId == parent }) {
                log = log.copy(workflows = log.workflows + MemoryWorkflowRecord(parent, null, atEpochMillis))
            }
            log = log.copy(parents = log.parents + MemoryParentRecord(workflowId, parent, atEpochMillis))
            added += parent
        }
        save(log)
        MemoryLineageChange(newWorkflow, added)
    }

    /** Appends: [projectId] now also includes every workflow of [incorporatesProjectId]. */
    suspend fun expandProject(projectId: String, incorporatesProjectId: String, by: String, reason: String, atEpochMillis: Long): MemoryProjectExpansion {
        require(projectId.isNotBlank() && incorporatesProjectId.isNotBlank() && projectId != incorporatesProjectId) {
            "A project expansion names two different projects"
        }
        require(by.isNotBlank() && reason.isNotBlank()) { "A project expansion records who and why" }
        return mutex.withLock {
            val log = load()
            val expansion = MemoryProjectExpansion(projectId, incorporatesProjectId, by, reason, atEpochMillis)
            save(log.copy(expansions = log.expansions + expansion))
            expansion
        }
    }

    suspend fun parentsOf(workflowId: String): List<String> = mutex.withLock { parentsIn(load(), workflowId) }

    /** Every ancestor of [workflowId] (not itself), nearest first. */
    suspend fun ancestorsOf(workflowId: String): List<String> = mutex.withLock { ancestorsIn(load(), workflowId) }

    /** The project a workflow belongs to (its latest membership record), or null. */
    suspend fun projectOf(workflowId: String): String? = mutex.withLock { projectIn(load(), workflowId) }

    /** Every workflow of [projectId], including those of the projects it has incorporated (transitively). */
    suspend fun projectWorkflows(projectId: String): List<String> = mutex.withLock {
        val log = load()
        val projects = linkedSetOf(projectId)
        var frontier = listOf(projectId)
        while (frontier.isNotEmpty()) {
            frontier = frontier.flatMap { p -> log.expansions.filter { it.projectId == p }.map { it.incorporatesProjectId } }
                .filter { projects.add(it) }
        }
        log.workflows.map { it.workflowId }.distinct().filter { projectIn(log, it) in projects }
    }

    /**
     * Workflows whose banks [workflowId] may read, read-only, at recall: the other workflows of its
     * project (with expansions) that are neither it nor its ancestors. Empty without a project.
     */
    suspend fun readOnlyNeighboursOf(workflowId: String): List<String> {
        val project = projectOf(workflowId) ?: return emptyList()
        val lineage = (ancestorsOf(workflowId) + workflowId).toSet()
        return projectWorkflows(project).filter { it !in lineage }
    }

    /**
     * The lineage path from [producer] to [reader] through parent links (shortest; merge points are
     * the workflows on it with several parents), or null when [producer] is not [reader] or one of
     * its ancestors.
     */
    suspend fun lineagePath(producer: String, reader: String): List<String>? = mutex.withLock {
        val log = load()
        if (producer == reader) return@withLock listOf(reader)
        val cameFrom = HashMap<String, String>()
        val queue = ArrayDeque(listOf(reader))
        val seen = hashSetOf(reader)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            parentsIn(log, current).forEach { parent ->
                if (seen.add(parent)) {
                    cameFrom[parent] = current
                    if (parent == producer) {
                        val path = mutableListOf(producer)
                        var step = producer
                        while (step != reader) {
                            step = cameFrom.getValue(step)
                            path += step
                        }
                        return@withLock path
                    }
                    queue.addLast(parent)
                }
            }
        }
        null
    }

    suspend fun isMerge(workflowId: String): Boolean = parentsOf(workflowId).size > 1

    companion object {
        /** The lineage persisted in the platform's default settings. */
        fun createDefault(): MemoryLineage = MemoryLineage(Settings())
    }

    private fun parentsIn(log: MemoryLineageLog, workflowId: String) =
        log.parents.filter { it.workflowId == workflowId }.map { it.parentWorkflowId }

    private fun ancestorsIn(log: MemoryLineageLog, workflowId: String): List<String> {
        val seen = linkedSetOf<String>()
        var frontier = listOf(workflowId)
        while (frontier.isNotEmpty()) {
            frontier = frontier.flatMap { parentsIn(log, it) }.filter { it != workflowId && seen.add(it) }
        }
        return seen.toList()
    }

    private fun projectIn(log: MemoryLineageLog, workflowId: String): String? =
        log.workflows.lastOrNull { it.workflowId == workflowId && it.projectId != null }?.projectId

    private fun load(): MemoryLineageLog {
        val settings = settings ?: return memoryOnly
        return settings.getStringOrNull(key)
            ?.let { SettingsMemoryStore.defaultJson.decodeFromString(MemoryLineageLog.serializer(), it) }
            ?: MemoryLineageLog()
    }

    private fun save(log: MemoryLineageLog) {
        val settings = settings ?: run { memoryOnly = log; return }
        settings.putString(key, SettingsMemoryStore.defaultJson.encodeToString(MemoryLineageLog.serializer(), log))
    }
}

/** Metadata tag: the workflow whose bank a memory was written to (for a derived memory, the workflow that derived it). */
const val PRODUCED_BY_WORKFLOW: String = "producedByWorkflow"

/** Metadata tag: the session a memory came from, when it came from exactly one episode. */
const val PRODUCED_BY_SESSION: String = "producedBySession"

/**
 * One workflow's lineage bank: its own store, plus every ancestor's store read through by reference.
 *
 * - [read] is the union of the workflow's own records and its ancestors' (own first; the queue is the
 *   workflow's own only), at the workflow's own revision.
 * - [commit] validates the mutation against that whole view (so own records may cite ancestors'),
 *   stamps every new memory with [PRODUCED_BY_WORKFLOW] (and [PRODUCED_BY_SESSION] when it came from
 *   one episode), and writes only into the workflow's own store. An episode of another workflow, a
 *   memory tagged with another workflow, or a queue entry for an ancestor's episode is refused.
 */
class LineageMemoryStore(
    val workflowId: String,
    private val own: MemoryStore,
    private val ancestors: suspend () -> List<MemoryStore>,
) : MemoryStore {
    private val mutex = Mutex()
    private var cacheKey: List<MemorySnapshot>? = null
    private var cached: MemorySnapshot? = null

    override suspend fun read(): MemorySnapshot {
        val parts = listOf(own.read()) + ancestors().map { it.read() }
        return mutex.withLock {
            val key = cacheKey
            if (key != null && key.size == parts.size && key.indices.all { key[it] === parts[it] }) return@withLock cached!!
            val ownPart = parts.first()
            val merged = MemorySnapshot(
                revision = ownPart.revision,
                episodes = parts.flatMap { it.episodes }.distinctBy { it.id },
                sections = parts.flatMap { it.sections }.distinctBy { it.id },
                nodes = parts.flatMap { it.nodes }.distinctBy { it.id },
                edges = parts.flatMap { it.edges }.distinctBy { it.id },
                queue = ownPart.queue,
                declinedCondensations = parts.flatMap { it.declinedCondensations }.distinct(),
            )
            cacheKey = parts
            cached = merged
            merged
        }
    }

    override suspend fun commit(expectedRevision: Long, mutation: MemoryStoreMutation): Boolean {
        val view = read()
        if (view.revision != expectedRevision) return false
        val stamped = stamp(mutation, view)
        // Validates every reference against the whole lineage (throws when one is missing or a duplicate).
        view.applyMutation(stamped, MemoryIdIndex(view))
        return own.commit(expectedRevision, stamped)
    }

    override suspend fun replace(snapshot: MemorySnapshot) {
        requireOwnEpisodes(snapshot.episodes)
        own.replace(snapshot)
    }

    private fun stamp(mutation: MemoryStoreMutation, view: MemorySnapshot): MemoryStoreMutation {
        val episodes = mutation.episodesToAdd.map { if (it.workflowRunId == null) it.copy(workflowRunId = workflowId) else it }
        requireOwnEpisodes(episodes)
        val ownEpisodeIds = (view.episodes.filter { it.workflowRunId == workflowId }.map { it.id } + episodes.map { it.id }).toSet()
        require(mutation.queueUpserts.all { it.episodeId in ownEpisodeIds }) {
            "Workflow $workflowId may queue only its own episodes"
        }
        val sessions = (view.episodes + episodes).associate { it.id to it.sourceSessionId }
        val nodes = mutation.nodesToAdd.map { node ->
            val tagged = node.metadata[PRODUCED_BY_WORKFLOW]
            require(tagged == null || tagged == workflowId) {
                "Memory ${node.id.value} is tagged with workflow $tagged but is written by $workflowId"
            }
            val session = node.sourceEpisodeIds.singleOrNull()?.let(sessions::get)
            node.copy(metadata = node.metadata + (PRODUCED_BY_WORKFLOW to workflowId) + listOfNotNull(session?.let { PRODUCED_BY_SESSION to it }))
        }
        return mutation.copy(episodesToAdd = episodes, nodesToAdd = nodes)
    }

    private fun requireOwnEpisodes(episodes: List<MemoryEpisode>) {
        val foreign = episodes.filter { it.workflowRunId != workflowId }
        if (foreign.isNotEmpty()) {
            throw MemoryCrossBankException(
                "Workflow $workflowId's bank cannot hold episodes of another workflow: " +
                    foreign.joinToString { "${it.id.value} (${it.workflowRunId ?: "no workflow"})" },
            )
        }
    }
}
