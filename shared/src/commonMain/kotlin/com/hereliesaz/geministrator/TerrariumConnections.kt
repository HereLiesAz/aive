package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.build_add_connection
import com.hereliesaz.geministrator.resources.build_build
import com.hereliesaz.geministrator.resources.build_cut
import com.hereliesaz.geministrator.resources.build_fed_by
import com.hereliesaz.geministrator.resources.build_live
import com.hereliesaz.geministrator.resources.build_pick_a_creature
import com.hereliesaz.geministrator.resources.build_redo
import com.hereliesaz.geministrator.resources.build_undo
import org.jetbrains.compose.resources.stringResource

/** Live watches the run; Build opens the wiring to edits (docs/Swarm-OS.md, two modes). */
@Composable
internal fun TerrariumModeToggle(
    building: Boolean,
    onBuildingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AzphaltPill(
            label = stringResource(Res.string.build_live),
            seed = "mode-live",
            selected = !building,
            role = Role.RadioButton,
            onClick = { onBuildingChange(false) },
        )
        AzphaltPill(
            label = stringResource(Res.string.build_build),
            seed = "mode-build",
            selected = building,
            role = Role.RadioButton,
            onClick = { onBuildingChange(true) },
        )
    }
}

/**
 * The plain equivalent of every tether gesture: what feeds the selected creature, each cuttable,
 * and the creatures it could be fed by. Undo and redo walk the tide.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TerrariumConnections(
    definition: WorkflowDefinition,
    selectedTaskId: String?,
    canUndo: Boolean,
    canRedo: Boolean,
    onEdit: (TerrariumDependencyEditResult) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (canUndo) AzphaltPill(label = stringResource(Res.string.build_undo), seed = "tide-undo", onClick = onUndo)
            if (canRedo) AzphaltPill(label = stringResource(Res.string.build_redo), seed = "tide-redo", onClick = onRedo)
        }
        val selected = definition.tasks.firstOrNull { it.id.value == selectedTaskId }
        if (selected == null) {
            Text(stringResource(Res.string.build_pick_a_creature), style = AzphaltType.body, color = azphaltOnSurface())
            return@Column
        }
        val names = definition.tasks.associate { it.id to it.name }
        Text(stringResource(Res.string.build_fed_by, selected.name), style = AzphaltType.eyebrow, color = azphaltOnSurface())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            selected.dependsOn.sortedBy { it.value }.forEach { upstream ->
                AzphaltPill(
                    label = names[upstream] ?: upstream.value,
                    seed = "fed-by-${upstream.value}",
                    endCap = stringResource(Res.string.build_cut),
                    onClick = { onEdit(removeTerrariumDependency(definition, selected.id, upstream)) },
                )
            }
        }
        val candidates = definition.tasks.filter { it.id != selected.id && it.id !in selected.dependsOn }
        if (candidates.isNotEmpty()) {
            Text(stringResource(Res.string.build_add_connection), style = AzphaltType.eyebrow, color = azphaltOnSurface())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                candidates.forEach { upstream ->
                    AzphaltPill(
                        label = upstream.name,
                        seed = "feed-from-${upstream.id.value}",
                        onClick = { onEdit(addTerrariumDependency(definition, selected.id, TaskDefinitionId(upstream.id.value))) },
                    )
                }
            }
        }
    }
}
