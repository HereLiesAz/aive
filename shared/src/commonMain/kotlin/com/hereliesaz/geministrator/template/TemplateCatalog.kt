package com.hereliesaz.geministrator.template

import com.hereliesaz.geministrator.persistence.ChunkedStringSettings
import com.hereliesaz.geministrator.persistence.readQuarantining
import com.hereliesaz.geministrator.persistence.refuseIfUnreadable
import com.russhwolf.settings.Settings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * What a version of a template is, from the built-in versions and the keeper's adopted revisions. Pure and cheap.
 * "Current" is the most recently adopted revision whose chain reaches a built-in and whose locked fields still equal
 * that built-in's; a retired or candidate revision is skipped, and the built-in is the fallback. A pinned version
 * (a saved swarm, a running step) resolves to exactly that version while it is valid.
 */
class TemplateCatalog(
    private val builtIns: List<TemplateBase>,
    private val revisions: List<TemplateRevision> = emptyList(),
) {
    private fun root(kind: TemplateKind, id: String): TemplateBase? =
        builtIns.filter { it.kind == kind && it.id == id }.maxByOrNull { it.version }

    private fun forTemplate(kind: TemplateKind, id: String) = revisions.filter { it.kind == kind && it.templateId == id }

    private fun chainValid(rev: TemplateRevision, root: TemplateBase, depth: Int = 0): Boolean {
        if (depth > MAX_DEPTH || rev.status == TemplateStatus.Reverted) return false
        if (rev.locked != root.locked) return false
        if (rev.parentVersion == root.version) return true
        val parent = forTemplate(rev.kind, rev.templateId).firstOrNull { it.version == rev.parentVersion } ?: return false
        return chainValid(parent, root, depth + 1)
    }

    private fun baseOf(root: TemplateBase, rev: TemplateRevision) =
        root.copy(version = rev.version, body = rev.body)

    /** The version new steps and new swarms are made from. Null when no built-in has this id. */
    fun current(kind: TemplateKind, id: String): TemplateBase? {
        val root = root(kind, id) ?: return null
        val best = forTemplate(kind, id)
            .filter { it.status == TemplateStatus.Approved && chainValid(it, root) }
            .maxWithOrNull(compareBy<TemplateRevision>({ it.adoptedAtEpochMillis }, { it.version }))
        return if (best == null) root else baseOf(root, best)
    }

    /** Exactly [version] when it is valid and not retired; otherwise the current version. */
    fun at(kind: TemplateKind, id: String, version: Int): TemplateBase? {
        val root = root(kind, id) ?: return null
        if (version == root.version) return root
        val rev = forTemplate(kind, id).firstOrNull {
            it.version == version && it.status != TemplateStatus.Candidate && chainValid(it, root)
        }
        return if (rev != null) baseOf(root, rev) else current(kind, id)
    }

    fun nextVersion(kind: TemplateKind, id: String): Int =
        maxOf(root(kind, id)?.version ?: 0, forTemplate(kind, id).maxOfOrNull { it.version } ?: 0) + 1

    /** Generation of [version]: 1 for a built-in, a revision's own number otherwise. */
    fun generationOf(kind: TemplateKind, id: String, version: Int): Int =
        forTemplate(kind, id).firstOrNull { it.version == version }?.generation ?: 1

    /** Every revision of one template, retired branches included: the lineage the keeper can read. */
    fun lineage(kind: TemplateKind, id: String): List<TemplateRevision> =
        forTemplate(kind, id).sortedBy { it.version }

    /** [version] and everything that descends from it. */
    fun branch(kind: TemplateKind, id: String, version: Int): Set<Int> {
        val all = forTemplate(kind, id)
        val found = linkedSetOf(version)
        var grew = true
        while (grew) {
            grew = false
            all.filter { it.parentVersion in found && found.add(it.version) }.forEach { grew = true }
        }
        return found
    }

    companion object {
        private const val MAX_DEPTH = 64

        /** [revisions] with the branch from [version] on retired (archived, never deleted). */
        fun retire(revisions: List<TemplateRevision>, kind: TemplateKind, id: String, version: Int): List<TemplateRevision> {
            val branch = TemplateCatalog(emptyList(), revisions).branch(kind, id, version)
            return revisions.map {
                if (it.kind == kind && it.templateId == id && it.version in branch) it.copy(status = TemplateStatus.Reverted) else it
            }
        }

        /** A candidate generation becomes selectable only with a new generation's worth of evidence behind it. */
        fun promote(revisions: List<TemplateRevision>, kind: TemplateKind, id: String, version: Int, evidenceSteps: Int): List<TemplateRevision>? {
            val target = revisions.firstOrNull { it.kind == kind && it.templateId == id && it.version == version } ?: return null
            if (target.status != TemplateStatus.Candidate || evidenceSteps < GENETIC_MIN_STEPS) return null
            return revisions.map { if (it === target) it.copy(status = TemplateStatus.Approved) else it }
        }
    }
}

/** The keeper's adopted revisions on this device. Not part of the setup file or an export (an import cannot forge one). */
interface TemplateStore {
    suspend fun revisions(): List<TemplateRevision>

    /** Adds [revision] unless its version is already taken for that template (a compare-and-set). True when added. */
    suspend fun adopt(revision: TemplateRevision): Boolean

    /** Replaces the list with [update] of it, under the store's lock. */
    suspend fun merge(update: (List<TemplateRevision>) -> List<TemplateRevision>)
}

class InMemoryTemplateStore : TemplateStore {
    private var all: List<TemplateRevision> = emptyList()
    override suspend fun revisions() = all
    override suspend fun adopt(revision: TemplateRevision): Boolean {
        if (all.any { it.kind == revision.kind && it.templateId == revision.templateId && it.version == revision.version }) return false
        all = (all + revision).takeLast(MAX_REVISIONS)
        return true
    }
    override suspend fun merge(update: (List<TemplateRevision>) -> List<TemplateRevision>) {
        all = update(all)
    }
}

const val MAX_REVISIONS: Int = 300

class SettingsTemplateStore(
    settings: Settings = Settings(),
    private val key: String = DEFAULT_KEY,
) : TemplateStore {
    private val settings: Settings = ChunkedStringSettings.unbounded(settings)
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(TemplateRevision.serializer())

    override suspend fun revisions(): List<TemplateRevision> = mutex.withLock { read() }

    override suspend fun adopt(revision: TemplateRevision): Boolean = mutex.withLock {
        val now = read()
        if (now.any { it.kind == revision.kind && it.templateId == revision.templateId && it.version == revision.version }) {
            return@withLock false
        }
        write((now + revision).takeLast(MAX_REVISIONS))
        true
    }

    override suspend fun merge(update: (List<TemplateRevision>) -> List<TemplateRevision>) = mutex.withLock {
        val now = read()
        val next = update(now)
        if (next != now) write(next.takeLast(MAX_REVISIONS))
    }

    private fun read(): List<TemplateRevision> =
        settings.readQuarantining(key) { json.decodeFromString(serializer, it) } ?: emptyList()

    private fun write(revisions: List<TemplateRevision>) {
        settings.refuseIfUnreadable(key) { json.decodeFromString(serializer, it) }
        settings.putString(key, json.encodeToString(serializer, revisions))
    }

    companion object {
        const val DEFAULT_KEY = "aive.template-store.v1"
    }
}
