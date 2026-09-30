package com.hereliesaz.geministrator

import com.hereliesaz.conveyance.h2g2.H2g2TerrariumPosition
import com.hereliesaz.conveyance.h2g2.H2g2TerrariumRelationship
import com.hereliesaz.conveyance.h2g2.H2g2TerrariumRelationshipKind
import com.hereliesaz.conveyance.h2g2.H2g2TerrariumSubject
import com.hereliesaz.conveyance.h2g2.H2g2WorkflowNode
import com.hereliesaz.conveyance.h2g2.H2g2WorkflowState
import com.hereliesaz.geministrator.memory.MemoryActivity
import com.hereliesaz.geministrator.memory.MemoryConsolidationResult
import com.hereliesaz.geministrator.memory.MemoryConsolidationStage
import com.hereliesaz.geministrator.memory.MemoryEngineAssembly
import com.hereliesaz.geministrator.memory.MemoryEngineKind
import com.hereliesaz.geministrator.memory.MemoryLayerSettings
import com.hereliesaz.geministrator.memory.MemoryMicroAgentRole
import com.hereliesaz.geministrator.memory.MemoryNodeKind
import com.hereliesaz.geministrator.memory.MemoryQueueStatus
import com.hereliesaz.geministrator.memory.MemoryRelationKind
import com.hereliesaz.geministrator.memory.MemorySnapshot
import com.hereliesaz.geministrator.memory.parked

/** The memory pipeline as terrarium subjects: an intake, then one creature per stage, in order. */
internal data class MemoryTerrariumProjection(
    val subjects: List<H2g2TerrariumSubject>,
    val relationships: List<H2g2TerrariumRelationship>,
)

/**
 * Each stage's creature. Labels carry the classifier keyword that gives the stage a fitting mascot
 * (see [classifyNodeCreatureRole]); the stage name stays readable on the tag.
 */
internal enum class MemoryTerrariumStage(
    val stage: MemoryConsolidationStage,
    val label: String,
    val roles: List<MemoryMicroAgentRole>,
) {
    Sectioning(MemoryConsolidationStage.Sectioning, "Sectioner Data Pipeline", listOf(MemoryMicroAgentRole.Sectioner)),
    Salience(MemoryConsolidationStage.Salience, "Salience Pattern Seeker", listOf(MemoryMicroAgentRole.SalienceFilter)),
    Tags(
        MemoryConsolidationStage.Tags,
        "Tagging Knowledge Keeper",
        listOf(MemoryMicroAgentRole.NounTagger, MemoryMicroAgentRole.VerbTagger),
    ),
    Phrases(MemoryConsolidationStage.Phrases, "Phrase Documenter", listOf(MemoryMicroAgentRole.PhraseSynthesizer)),
    Summaries(MemoryConsolidationStage.Summaries, "Summary Data Miner", listOf(MemoryMicroAgentRole.SummarySynthesizer)),
    Categories(MemoryConsolidationStage.Categories, "Category Integrator", listOf(MemoryMicroAgentRole.CategoryClassifier)),
    Associations(MemoryConsolidationStage.Associations, "Association Connector", listOf(MemoryMicroAgentRole.AssociationLinker)),
    Condensation(MemoryConsolidationStage.Condensation, "Condensation Optimizer", listOf(MemoryMicroAgentRole.CondensationRewriter)),
    ;

    val id: String get() = "memory-stage-${name.lowercase()}"

    companion object {
        fun of(stage: MemoryConsolidationStage): MemoryTerrariumStage? = entries.firstOrNull { it.stage == stage }
    }
}

internal const val MEMORY_INTAKE_ID = "memory-intake"

internal fun projectMemoryTerrarium(
    snapshot: MemorySnapshot,
    settings: MemoryLayerSettings,
    engines: MemoryEngineAssembly,
    activity: MemoryActivity,
): MemoryTerrariumProjection {
    val parked = snapshot.parked(settings.policy).groupingBy { it.stage }.eachCount()
    val queued = snapshot.queue
        .filter { it.status == MemoryQueueStatus.Pending || it.status == MemoryQueueStatus.Processing }
        .groupingBy { it.stage }
        .eachCount()
    val nodeCounts = snapshot.nodes.groupingBy { it.kind }.eachCount()
    val edgeCounts = snapshot.edges.groupingBy { it.relation }.eachCount()

    fun produced(stage: MemoryTerrariumStage): Int = when (stage) {
        MemoryTerrariumStage.Sectioning -> snapshot.sections.size
        MemoryTerrariumStage.Salience -> nodeCounts[MemoryNodeKind.Context] ?: 0
        MemoryTerrariumStage.Tags -> (nodeCounts[MemoryNodeKind.NounTag] ?: 0) + (nodeCounts[MemoryNodeKind.VerbTag] ?: 0)
        MemoryTerrariumStage.Phrases -> nodeCounts[MemoryNodeKind.Phrase] ?: 0
        MemoryTerrariumStage.Summaries -> nodeCounts[MemoryNodeKind.Summary] ?: 0
        MemoryTerrariumStage.Categories -> nodeCounts[MemoryNodeKind.Category] ?: 0
        MemoryTerrariumStage.Associations ->
            (edgeCounts[MemoryRelationKind.SimilarTo] ?: 0) + (edgeCounts[MemoryRelationKind.AssociatedWith] ?: 0)
        MemoryTerrariumStage.Condensation -> edgeCounts[MemoryRelationKind.CondensedFrom] ?: 0
    }

    val lastFailedStage = activity.lastStage.takeIf { activity.last is MemoryConsolidationResult.Failed }
    val stages = MemoryTerrariumStage.entries
    val subjects = buildList {
        val waiting = snapshot.queue.count { it.status != MemoryQueueStatus.Complete }
        add(
            H2g2TerrariumSubject(
                node = H2g2WorkflowNode(
                    id = MEMORY_INTAKE_ID,
                    label = "Memory Intake",
                    subtitle = if (settings.enabled) "${activity.banked} banked · $waiting waiting" else "Off",
                    state = when {
                        !settings.enabled -> H2g2WorkflowState.Blocked
                        settings.consolidationPaused -> H2g2WorkflowState.Gate
                        waiting > 0 -> H2g2WorkflowState.Ready
                        else -> H2g2WorkflowState.Complete
                    },
                    detail = "${snapshot.episodes.size} episodes remembered",
                ),
                position = H2g2TerrariumPosition(.5f, .1f),
            ),
        )
        stages.forEachIndexed { index, stage ->
            val engineKinds = stage.roles.map { engines.resolved[it] ?: MemoryEngineKind.Programmatic }.distinct()
            val parkedHere = parked[stage.stage] ?: 0
            val queuedHere = queued[stage.stage] ?: 0
            val state = when {
                !settings.enabled -> H2g2WorkflowState.Pending
                activity.working == stage.stage -> H2g2WorkflowState.Active
                parkedHere > 0 -> H2g2WorkflowState.Blocked
                lastFailedStage == stage.stage -> H2g2WorkflowState.Failed
                queuedHere > 0 -> H2g2WorkflowState.Ready
                produced(stage) > 0 -> H2g2WorkflowState.Complete
                else -> H2g2WorkflowState.Pending
            }
            add(
                H2g2TerrariumSubject(
                    node = H2g2WorkflowNode(
                        id = stage.id,
                        label = stage.label,
                        subtitle = buildString {
                            append(engineKinds.joinToString("+") { it.shortLabel })
                            if (queuedHere > 0) append(" · $queuedHere queued")
                            if (parkedHere > 0) append(" · $parkedHere parked")
                        },
                        state = state,
                        detail = "${produced(stage)} made",
                    ),
                    position = stagePosition(index),
                    birthParentId = if (index == 0) MEMORY_INTAKE_ID else stages[index - 1].id,
                ),
            )
        }
    }

    val relationships = buildList {
        val intakeFeeding = settings.enabled && (queued[MemoryConsolidationStage.Sectioning] ?: 0) > 0
        add(
            H2g2TerrariumRelationship(
                from = MEMORY_INTAKE_ID,
                to = stages.first().id,
                kind = if (intakeFeeding) H2g2TerrariumRelationshipKind.Spawn else H2g2TerrariumRelationshipKind.Dependency,
                active = intakeFeeding,
            ),
        )
        stages.zipWithNext().forEach { (from, to) ->
            val flowing = activity.working == to.stage
            add(
                H2g2TerrariumRelationship(
                    from = from.id,
                    to = to.id,
                    kind = if (flowing) H2g2TerrariumRelationshipKind.Transfer else H2g2TerrariumRelationshipKind.Dependency,
                    active = flowing,
                ),
            )
        }
    }
    return MemoryTerrariumProjection(subjects, relationships)
}

/** Two rows, left to right then right to left, so the pipeline reads as one loop under the intake. */
private fun stagePosition(index: Int): H2g2TerrariumPosition {
    val column = if (index < 4) index else 7 - index
    return H2g2TerrariumPosition(
        x = .14f + column * .24f,
        y = if (index < 4) .38f else .78f,
    )
}

internal val MemoryEngineKind.shortLabel: String
    get() = when (this) {
        MemoryEngineKind.Programmatic -> "Programmatic"
        MemoryEngineKind.LocalModel -> "Local model"
        MemoryEngineKind.HostedModel -> "Hosted"
    }
