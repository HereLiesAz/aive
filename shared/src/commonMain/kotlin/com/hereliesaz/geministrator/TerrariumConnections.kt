package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.TaskDefinitionId
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.build_add_connection
import com.hereliesaz.geministrator.resources.build_add_creature
import com.hereliesaz.geministrator.resources.build_add_from_steps
import com.hereliesaz.geministrator.resources.build_add_from_swarm
import com.hereliesaz.geministrator.resources.build_add_hint
import com.hereliesaz.geministrator.resources.build_step_repo_fetch
import com.hereliesaz.geministrator.resources.build_step_repo_status
import com.hereliesaz.geministrator.resources.build_step_tests
import com.hereliesaz.geministrator.resources.build_build
import com.hereliesaz.geministrator.resources.build_bud
import com.hereliesaz.geministrator.resources.build_cut
import com.hereliesaz.geministrator.resources.build_detach
import com.hereliesaz.geministrator.resources.build_hold_output
import com.hereliesaz.geministrator.resources.build_release_output
import com.hereliesaz.geministrator.resources.build_fed_by
import com.hereliesaz.geministrator.resources.build_fold
import com.hereliesaz.geministrator.resources.build_group
import com.hereliesaz.geministrator.resources.build_group_hint
import com.hereliesaz.geministrator.resources.build_knot_on_any_outcome
import com.hereliesaz.geministrator.resources.build_knot_on_failure
import com.hereliesaz.geministrator.resources.build_knot_on_success
import com.hereliesaz.geministrator.resources.build_live
import com.hereliesaz.geministrator.resources.build_pick_a_creature
import com.hereliesaz.geministrator.resources.build_redo
import com.hereliesaz.geministrator.resources.build_rehearse
import com.hereliesaz.geministrator.resources.build_undo
import org.jetbrains.compose.resources.stringResource

/** Live watches the run; Build opens the wiring to edits (docs/Swarm-OS.md, two modes). */
@Composable
internal fun TerrariumModeToggle(
    building: Boolean,
    onBuildingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** One Ink capsule holding both, the open one filled (mockup H6's hive head). */
    segmented: Boolean = false,
) {
    if (segmented) {
        TerrariumModeSegments(building, onBuildingChange, modifier)
        return
    }
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
    onRehearse: () -> Unit = {},
    grouping: Set<String>? = null,
    onGroupingChange: (Set<String>?) -> Unit = {},
    onFold: (Set<String>) -> Unit = {},
    /** The swarm's roles, offered by Add creature. */
    roles: Collection<RoleDefinition> = emptyList(),
    modifier: Modifier = Modifier,
) {
    // Add creature: open, it lists what can join; a pick joins unwired and closes the list.
    var adding by remember(definition.id) { mutableStateOf(false) }
    val addPill: @Composable () -> Unit = {
        AzphaltPill(
            label = stringResource(Res.string.build_add_creature),
            seed = "add-creature",
            selected = adding,
            onClick = { adding = !adding },
        )
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AzphaltPill(label = stringResource(Res.string.build_rehearse), seed = "rehearse", onClick = onRehearse)
            // Group: while on, taps collect creatures; Fold makes them one circuit.
            AzphaltPill(
                label = stringResource(Res.string.build_group),
                seed = "group",
                selected = grouping != null,
                onClick = { onGroupingChange(if (grouping == null) emptySet() else null) },
            )
            if (grouping != null && grouping.size >= 2) {
                AzphaltPill(
                    label = stringResource(Res.string.build_fold, grouping.size),
                    seed = "fold",
                    onClick = { onFold(grouping) },
                )
            }
            if (canUndo) AzphaltPill(label = stringResource(Res.string.build_undo), seed = "tide-undo", onClick = onUndo)
            if (canRedo) AzphaltPill(label = stringResource(Res.string.build_redo), seed = "tide-redo", onClick = onRedo)
        }
        if (grouping != null) {
            Text(stringResource(Res.string.build_group_hint), style = AzphaltType.body, color = azphaltOnSurface())
            return@Column
        }
        val selected = definition.tasks.firstOrNull { it.id.value == selectedTaskId }
        if (selected == null) {
            Text(stringResource(Res.string.build_pick_a_creature), style = AzphaltType.body, color = azphaltOnSurface())
            addPill()
            if (adding) TerrariumAddCreature(definition, roles, onEdit = { onEdit(it); adding = false })
            return@Column
        }
        val names = definition.tasks.associate { it.id to it.name }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AzphaltPill(
                label = stringResource(Res.string.build_detach),
                seed = "detach-${selected.id.value}",
                onClick = { onEdit(detachTerrariumCreature(definition, selected.id)) },
            )
            AzphaltPill(
                label = stringResource(if (selected.holdOutput) Res.string.build_release_output else Res.string.build_hold_output),
                seed = "hold-${selected.id.value}",
                selected = selected.holdOutput,
                onClick = { onEdit(setTerrariumHold(definition, selected.id, !selected.holdOutput)) },
            )
            AzphaltPill(
                label = stringResource(Res.string.build_bud),
                seed = "bud-${selected.id.value}",
                onClick = { onEdit(budTerrariumCreature(definition, selected.id)) },
            )
            addPill()
        }
        if (adding) TerrariumAddCreature(definition, roles, onEdit = { onEdit(it); adding = false })
        Text(stringResource(Res.string.build_fed_by, selected.name), style = AzphaltType.eyebrow, color = azphaltOnSurface())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            selected.dependsOn.sortedBy { it.value }.forEach { upstream ->
                AzphaltPill(
                    label = names[upstream] ?: upstream.value,
                    seed = "fed-by-${upstream.value}",
                    endCap = stringResource(Res.string.build_cut),
                    onClick = { onEdit(removeTerrariumDependency(definition, selected.id, upstream)) },
                )
                // The knot: tapping ties the next one in turn.
                val knot = terrariumKnot(definition, selected.id, upstream)
                AzphaltPill(
                    label = stringResource(
                        when (knot) {
                            TerrariumKnot.OnSuccess -> Res.string.build_knot_on_success
                            TerrariumKnot.OnAnyOutcome -> Res.string.build_knot_on_any_outcome
                            TerrariumKnot.OnFailure -> Res.string.build_knot_on_failure
                        },
                    ),
                    seed = "knot-${upstream.value}",
                    onClick = {
                        val next = TerrariumKnot.entries[(knot.ordinal + 1) % TerrariumKnot.entries.size]
                        onEdit(setTerrariumKnot(definition, selected.id, upstream, next))
                    },
                )
            }
        }
        HiveJobEditor(definition = definition, task = selected, onEdit = onEdit)
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

/** What Add creature can bring in: the swarm's roles, then the steps that wear the neuron. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TerrariumAddCreature(
    definition: WorkflowDefinition,
    roles: Collection<RoleDefinition>,
    onEdit: (TerrariumDependencyEditResult) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.build_add_hint), style = AzphaltType.body, color = azphaltOnSurface())
        val addable = terrariumAddableRoles(roles)
        if (addable.isNotEmpty()) {
            Text(stringResource(Res.string.build_add_from_swarm), style = AzphaltType.eyebrow, color = azphaltOnSurface())
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                addable.forEach { role ->
                    AzphaltPill(
                        label = role.name,
                        seed = "add-role-${role.id.value}",
                        onClick = { onEdit(addTerrariumCreature(definition, TerrariumNewcomer.FromRole(role))) },
                    )
                }
            }
        }
        Text(stringResource(Res.string.build_add_from_steps), style = AzphaltType.eyebrow, color = azphaltOnSurface())
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TerrariumStep.entries.forEach { step ->
                AzphaltPill(
                    label = stringResource(
                        when (step) {
                            TerrariumStep.Tests -> Res.string.build_step_tests
                            TerrariumStep.RepositoryStatus -> Res.string.build_step_repo_status
                            TerrariumStep.RepositoryFetch -> Res.string.build_step_repo_fetch
                        },
                    ),
                    seed = "add-step-${step.idStem}",
                    onClick = { onEdit(addTerrariumCreature(definition, TerrariumNewcomer.Step(step))) },
                )
            }
        }
    }
}
