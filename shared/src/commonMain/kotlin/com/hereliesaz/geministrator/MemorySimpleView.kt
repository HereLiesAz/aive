package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.memory.MemoryActivity
import com.hereliesaz.geministrator.memory.MemoryConsolidationStage
import com.hereliesaz.geministrator.memory.MemoryNode
import com.hereliesaz.geministrator.memory.MemoryNodeKind
import com.hereliesaz.geministrator.memory.MemoryRelationKind
import com.hereliesaz.geministrator.memory.MemorySnapshot
import com.hereliesaz.geministrator.memory.waitingToConsolidate

/** The Memory swarm's two views: the memory field (Simple, the default) and every control (Technical). */
internal enum class MemoryView {
    Simple,
    Technical,
    ;

    companion object {
        /** Durable UI-state key: the view is remembered on this device. */
        const val STORAGE_KEY = "memory.view"

        /** The stored view, or Simple when nothing (or something unknown) is stored. */
        fun fromStored(stored: String?): MemoryView = entries.firstOrNull { it.name == stored } ?: Simple
    }
}

/**
 * The four stages the Simple view shows, mapped onto the real pipeline (docs/Memory-layer.md, Simple view):
 * - [Observe]: the intake. A finished session is banked as an episode and queued; no clerk runs.
 * - [Section]: the Sectioning stage.
 * - [Consolidate]: the other seven stages, Salience through Condensation.
 * - [Recall]: not a consolidation stage: agents reading memory back at task start, counted by the
 *   add-only `Recalled` links each delivered memory leaves.
 */
internal enum class MemorySimpleStage(val stages: Set<MemoryConsolidationStage>) {
    Observe(emptySet()),
    Section(setOf(MemoryConsolidationStage.Sectioning)),
    Consolidate(
        MemoryConsolidationStage.entries.toSet() - MemoryConsolidationStage.Sectioning - MemoryConsolidationStage.Complete,
    ),
    Recall(emptySet()),
    ;

    companion object {
        fun of(stage: MemoryConsolidationStage?): MemorySimpleStage? = stage?.let { s -> entries.firstOrNull { s in it.stages } }
    }
}

/** Real figures for the memory field, all from the selected bank's snapshot and this start's activity. */
internal data class MemorySimpleField(
    /** Episodes (finished sessions) banked in this bank's lineage. */
    val sessionsBanked: Int,
    val sections: Int,
    /** Memory nodes of every kind, as the Technical view counts them. */
    val memories: Int,
    /** Links between memories, leaving out `Recalled` access links (those are Recall's figure). */
    val links: Int,
    /** `Recalled` links: one per memory delivered to an agent. */
    val recalls: Int,
    /** Queue entries pending or being processed. */
    val waiting: Int,
    /** The simple stage a packet is being consolidated in now; null when idle. */
    val working: MemorySimpleStage?,
    /** The newest summary, category or phrase that nothing has superseded; null when there is none. */
    val latestMemory: MemoryNode?,
)

private val LATEST_MEMORY_KINDS = setOf(MemoryNodeKind.Summary, MemoryNodeKind.Category, MemoryNodeKind.Phrase)

internal fun memorySimpleField(snapshot: MemorySnapshot, activity: MemoryActivity): MemorySimpleField {
    val recalls = snapshot.edges.count { it.relation == MemoryRelationKind.Recalled }
    val superseded = snapshot.edges.filter { it.relation == MemoryRelationKind.Supersedes }.mapTo(hashSetOf()) { it.to }
    return MemorySimpleField(
        sessionsBanked = snapshot.episodes.size,
        sections = snapshot.sections.size,
        memories = snapshot.nodes.size,
        links = snapshot.edges.size - recalls,
        recalls = recalls,
        waiting = snapshot.waitingToConsolidate(),
        working = MemorySimpleStage.of(activity.working),
        latestMemory = snapshot.nodes
            .filter { it.kind in LATEST_MEMORY_KINDS && it.id !in superseded && it.text.isNotBlank() }
            .maxByOrNull { it.createdAtEpochMillis },
    )
}
