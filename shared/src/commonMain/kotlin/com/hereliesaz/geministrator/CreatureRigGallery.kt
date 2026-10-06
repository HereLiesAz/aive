package com.hereliesaz.geministrator

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hereliesaz.conveyance.h2g2.H2g2TerrariumPosition
import com.hereliesaz.conveyance.h2g2.H2g2TerrariumRelationship
import com.hereliesaz.conveyance.h2g2.H2g2TerrariumRelationshipKind
import com.hereliesaz.conveyance.h2g2.H2g2TerrariumSubject
import com.hereliesaz.conveyance.h2g2.H2g2WorkflowNode
import com.hereliesaz.conveyance.h2g2.H2g2WorkflowState

/**
 * Creature-rig gallery (web: `?creatureRigs=1`). Shows a small live terrarium with rigged,
 * generic-fallback and pre-rig roles connected by arms, then every rigged role in every state.
 * The switch forces the pre-rig rendering for comparison ([CreatureRigSettings.enabled]).
 */
@Composable
fun CreatureRigGalleryScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Azphalt.currentGround.page)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Puppet-rig creatures", style = AzphaltType.endCap, color = Azphalt.currentGround.onPage)
            Switch(checked = CreatureRigSettings.enabled, onCheckedChange = { CreatureRigSettings.enabled = it })
        }
        CreatureRigTerrariumDemo(Modifier.fillMaxWidth().height(620.dp))
        CreatureRigGrid(columns = 6)
    }
}

/** Fixture terrarium: a few rigged roles, two custom (generic rig) roles and one pre-rig role. */
@Composable
internal fun CreatureRigTerrariumDemo(modifier: Modifier = Modifier) {
    PlatformNodeTerrarium(
        subjects = creatureRigDemoSubjects,
        relationships = creatureRigDemoRelationships,
        adornments = emptyMap(),
        serviceVisits = emptyList(),
        selectedId = null,
        onNodeSelected = {},
        onNodeMoved = { _, _ -> },
        onNodeDroppedOn = { _, _ -> },
        modifier = modifier,
        editable = false,
    )
}

private fun demoSubject(id: String, label: String, state: H2g2WorkflowState, x: Float, y: Float, task: String? = null) =
    H2g2TerrariumSubject(
        node = H2g2WorkflowNode(id = id, label = label, state = state, subtitle = task),
        position = H2g2TerrariumPosition(x, y),
    )

internal val creatureRigDemoSubjects = listOf(
    demoSubject("arch", "Architect", H2g2WorkflowState.Complete, 0.14f, 0.22f, "Shape the system"),
    demoSubject("sim", "Simulator", H2g2WorkflowState.Active, 0.50f, 0.20f, "Run the model"),
    demoSubject("sec", "Security Analyst", H2g2WorkflowState.Gate, 0.86f, 0.22f),
    demoSubject("custom", "Weather Oracle", H2g2WorkflowState.Ready, 0.14f, 0.76f),
    demoSubject("legal", "Legal Counsel", H2g2WorkflowState.Blocked, 0.50f, 0.78f, "Review terms"),
    demoSubject("custom2", "Snack Coordinator", H2g2WorkflowState.Failed, 0.86f, 0.76f),
    demoSubject("research", "Researcher", H2g2WorkflowState.Pending, 0.50f, 0.50f),
)

internal val creatureRigDemoRelationships = listOf(
    H2g2TerrariumRelationship("arch", "sim", H2g2TerrariumRelationshipKind.Dependency, active = true),
    H2g2TerrariumRelationship("sim", "sec", H2g2TerrariumRelationshipKind.Dependency),
    H2g2TerrariumRelationship("research", "sim", H2g2TerrariumRelationshipKind.Transfer, active = true),
    H2g2TerrariumRelationship("custom", "legal", H2g2TerrariumRelationshipKind.Dependency),
    H2g2TerrariumRelationship("legal", "custom2", H2g2TerrariumRelationshipKind.Dependency),
    H2g2TerrariumRelationship("arch", "custom", H2g2TerrariumRelationshipKind.Dependency),
)

/** Every rigged role kind plus the six generic bodies, each in a different workflow state. */
@Composable
internal fun CreatureRigGrid(columns: Int, cell: Dp = 132.dp, modifier: Modifier = Modifier) {
    val clock = rememberInfiniteTransition(label = "creature-rig-gallery")
    val phase by clock.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2_400, easing = LinearEasing), RepeatMode.Restart),
        label = "creature-rig-gallery-phase",
    )
    val entries = creatureRigGalleryEntries
    val states = H2g2WorkflowState.entries
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        entries.chunked(columns).forEachIndexed { row, chunk ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                chunk.forEachIndexed { col, label ->
                    val state = states[(row * columns + col) % states.size]
                    Column(Modifier.width(cell), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(cell)) {
                            NodeMascotSurface(
                                roleLabel = label,
                                hueSeed = label,
                                state = state,
                                motionPhase = phase,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Text(
                            text = "$label · ${state.name}",
                            style = AzphaltType.endCap,
                            color = Azphalt.currentGround.onPage,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

/** Labels that resolve to every rig slug in [CreatureRigMapping] (one custom label per generic body). */
internal val creatureRigGalleryEntries: List<String> by lazy {
    val kinds = CreatureRigMapping.bySlugKind.keys.map { kind ->
        CreatureRigMapping.displayName(kind).split(' ').joinToString(" ") { w ->
            if (w == "ux" || w == "ml") w.uppercase() else w.replaceFirstChar(Char::uppercaseChar)
        }
    }
    val generics = CreatureRigMapping.GENERIC_SLUGS.map { slug ->
        (0 until 10_000).asSequence().map { "Custom Role $it" }
            .first { CreatureRigMapping.slugFor(it) == slug }
    }
    kinds + generics
}
