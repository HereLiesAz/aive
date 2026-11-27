package com.hereliesaz.geministrator

import com.hereliesaz.conveyance.h2g2.H2g2SwarmIdentityKind
import com.hereliesaz.conveyance.h2g2.H2g2TerrariumRelationship
import com.hereliesaz.conveyance.h2g2.H2g2TerrariumRelationshipKind
import com.hereliesaz.conveyance.h2g2.H2g2WorkflowState
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.memory.MemoryWebLayout
import com.hereliesaz.geministrator.memory.WebGraph
import com.hereliesaz.geministrator.memory.WebGroup

/** Render forms are not agent identities, roles, or new runtime states. */
internal enum class SpatialForm { Nucleus, Creature, ProgrammaticNeuron, Egg, WitheredEgg, Site }

internal data class SpatialCreature(
    val id: String,
    val label: String,
    val taskLabel: String?,
    val position: SpatialPoint,
    val morphologySeed: Int,
    val state: H2g2WorkflowState,
    val form: SpatialForm,
    val growth: Float = 0f,
)

internal data class SpatialSpore(val id: String, val group: WebGroup, val kind: String, val weight: Float, val position: SpatialPoint)
internal data class SpatialAssociation(val id: String, val from: String, val to: String, val relation: String, val weight: Float, val points: List<SpatialPoint>)
internal data class SpatialMemoryProjection(val spores: List<SpatialSpore>, val associations: List<SpatialAssociation>)
internal data class SpatialConnection(val from: String, val to: String, val kind: H2g2TerrariumRelationshipKind, val activeTransfer: Boolean)

/**
 * One read-only world, derived from the authoritative workflow and memory projections.
 * It contains no provider credentials, memory bodies, task objectives, execution commands or fake
 * backend. Run identity is separate from the workflow's layout. This is not yet a growth journal.
 */
internal data class SpatialHiveScene(
    val runId: String,
    val workflowId: String,
    val nucleus: SpatialCreature,
    val creatures: List<SpatialCreature>,
    val connections: List<SpatialConnection>,
    val memory: SpatialMemoryProjection,
)

/** Project only actual recorded memory nodes and associations, with no proximity-created links. */
internal fun projectSpatialMemory(graph: WebGraph): SpatialMemoryProjection {
    val nodes = graph.nodes.distinctBy { it.id }.map { node ->
        node.copy(size = node.size.takeIf { it.isFinite() && it >= 0f } ?: 0f)
    }
    val ids = nodes.mapTo(mutableSetOf()) { it.id }
    val edges = graph.edges.filter { it.from in ids && it.to in ids && it.from != it.to }.map { edge ->
        edge.copy(weight = edge.weight.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f)
    }
    val layout = MemoryWebLayout.layout(WebGraph(nodes, edges), iterations = if (nodes.size > 2_000) 90 else 240)
    val points = nodes.associate { node ->
        val p = layout.positions.getValue(node.id)
        node.id to spatialSporePosition(node.id, p.x, p.y, layout.radius)
    }
    val spores = nodes.map { node ->
        SpatialSpore(node.id, node.group, node.kind, node.size, points.getValue(node.id))
    }
    val occurrences = mutableMapOf<String, Int>()
    val associations = edges.map { edge ->
        val key = listOf(edge.from, edge.to, edge.relation).joinToString("") { "${it.length}:$it" }
        val occurrence = occurrences.getOrPut(key) { 0 }
        occurrences[key] = occurrence + 1
        val id = "$key:$occurrence"
        SpatialAssociation(id, edge.from, edge.to, edge.relation, edge.weight,
            spatialHyphaPoints(id, points.getValue(edge.from), points.getValue(edge.to)))
    }
    return SpatialMemoryProjection(spores, associations)
}

/**
 * Translate the live terrarium into the new spatial world, respecting its existing birth and
 * roaming projections. The orchestrator becomes the one central nucleus, never a second creature.
 * Only recorded active transfers can pulse. No scene event can mutate workflow or memory state.
 */
internal fun projectSpatialHive(
    projection: WorkflowTerrariumProjection,
    relationships: List<H2g2TerrariumRelationship>,
    tissue: TissueScene,
    run: WorkflowRun,
    layout: SpatialHiveLayoutStore,
    memory: SpatialMemoryProjection,
): SpatialHiveScene {
    val queen = projection.subjects.single { it.identityKind == H2g2SwarmIdentityKind.Orchestrator }
    val positions = layout.resolve(run.workflowDefinitionId, projection.subjects.map { it.node.id }, queen.node.id)
    val nucleus = SpatialCreature(queen.node.id, queen.node.label, queen.node.subtitle, SpatialPoint.Origin,
        spatialSeed(queen.identitySeed), queen.node.state, SpatialForm.Nucleus)
    val creatures = projection.subjects.mapNotNull { subject ->
        val node = subject.node
        if (node.id == nucleus.id) return@mapNotNull null
        val birth = tissue.births[node.id]
        if (birth is CreatureBirth.Hidden) return@mapNotNull null
        val form = when {
            birth is CreatureBirth.Egg -> SpatialForm.Egg
            birth is CreatureBirth.Withered -> SpatialForm.WitheredEgg
            tissue.roaming.roam[node.id] is CreatureRoam.Site -> SpatialForm.Site
            node.id in projection.programmaticNodeIds -> SpatialForm.ProgrammaticNeuron
            else -> SpatialForm.Creature
        }
        SpatialCreature(node.id, node.label, node.subtitle, positions.getValue(node.id), spatialSeed(subject.identitySeed),
            node.state, form, (birth as? CreatureBirth.Egg)?.growth ?: 0f)
    }
    val visible = creatures.mapTo(mutableSetOf()) { it.id }.apply { add(nucleus.id) }
    val connections = relationships.filter { it.from in visible && it.to in visible }.map { link ->
        SpatialConnection(link.from, link.to, link.kind, link.kind == H2g2TerrariumRelationshipKind.Transfer && link.active)
    }
    return SpatialHiveScene(run.id.value, run.workflowDefinitionId.value, nucleus, creatures, connections, memory)
}
