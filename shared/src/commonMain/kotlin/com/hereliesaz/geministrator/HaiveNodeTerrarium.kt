package com.hereliesaz.geministrator

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hereliesaz.conveyance.h2g2.H2g2SwarmAdornment
import com.hereliesaz.conveyance.h2g2.H2g2TerrariumPosition
import com.hereliesaz.conveyance.h2g2.H2g2TerrariumRelationship
import com.hereliesaz.conveyance.h2g2.H2g2TerrariumServiceVisit
import com.hereliesaz.conveyance.h2g2.H2g2TerrariumSubject
import com.hereliesaz.conveyance.h2g2.H2g2WorkflowNode

/**
 * Explicit failure surface for a missing or failed Rust node-creature renderer.
 *
 * Creature geometry is intentionally not recreated in Compose. Android, desktop, JS, and Wasm
 * must provide [platformNodeCreatureRenderEngine]; if that contract is unavailable, the terrarium
 * reports the packaging/runtime failure instead of silently switching to a second renderer.
 */
@Composable
internal fun HaiveNodeTerrarium(
    subjects: List<H2g2TerrariumSubject>,
    relationships: List<H2g2TerrariumRelationship>,
    adornments: Map<String, List<H2g2SwarmAdornment>>,
    serviceVisits: List<H2g2TerrariumServiceVisit>,
    selectedId: String?,
    onNodeSelected: (H2g2WorkflowNode) -> Unit,
    onNodeMoved: (String, H2g2TerrariumPosition) -> Unit,
    onNodeDroppedOn: (String, String) -> Unit,
    modifier: Modifier = Modifier,
    editable: Boolean = true,
    compact: Boolean = false,
) {
    val detail = if (subjects.isEmpty()) {
        "Node creature renderer unavailable"
    } else {
        "Node creature renderer unavailable · ${subjects.size} workflow node${if (subjects.size == 1) "" else "s"} not rendered"
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Azphalt.currentGround.onPage)
            .semantics { contentDescription = detail },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = detail.uppercase(),
            style = AzphaltType.endCap,
            color = GeministratorColors.error,
            modifier = Modifier.padding(24.dp),
        )
    }
}
