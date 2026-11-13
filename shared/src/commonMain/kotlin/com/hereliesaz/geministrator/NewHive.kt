package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.prompts.PromptKind
import com.hereliesaz.geministrator.resources.hive_new_name
import com.hereliesaz.geministrator.prompts.PromptSuggestBox
import com.hereliesaz.geministrator.prompts.PromptSuggestions
import com.hereliesaz.geministrator.prompts.rememberPromptHistory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.conveyance.h2g2.H2g2WorkflowState
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.build_build
import com.hereliesaz.geministrator.resources.build_live
import com.hereliesaz.geministrator.resources.hive_new_build_help
import com.hereliesaz.geministrator.resources.hive_new_build_title
import com.hereliesaz.geministrator.resources.hive_new_build_title_only
import com.hereliesaz.geministrator.resources.hive_new_draft_help
import com.hereliesaz.geministrator.resources.hive_new_draft_title
import com.hereliesaz.geministrator.resources.hive_new_name_field
import com.hereliesaz.geministrator.resources.hive_new_save
import com.hereliesaz.geministrator.resources.hive_new_saved
import com.hereliesaz.geministrator.resources.hive_new_surface
import com.hereliesaz.geministrator.resources.terrarium_could_not_stage_workflow_edit
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/*
 * A new hive (mockup H6): it opens empty in Build. Its head names it and leads back to the district,
 * beside Live and Edit as one control; under the field it is named, and creatures come up from the
 * tray the way Build's Add creature brings them in.
 */

/** Text-only hive name and a way back to the district. */
@Composable
internal fun NewHiveCrumb(name: String, onSurface: () -> Unit, modifier: Modifier = Modifier) {
    val description = stringResource(Res.string.hive_new_surface, name)
    Text(
        text = name.uppercase(),
        style = AzphaltType.lead.copy(fontWeight = FontWeight.ExtraBold),
        color = azphaltOnSurface(),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = description
            }
            .defaultMinSize(minHeight = AzphaltMinTouchTarget)
            .clickable(onClick = onSurface)
            .padding(horizontal = 8.dp),
    )
}

/** Live and Edit share an Ink capsule; only one view is selected. */
@Composable
internal fun TerrariumModeSegments(
    building: Boolean,
    onBuildingChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    AzphaltThemedSurface(Azphalt.Ink) {
        Row(
            modifier = modifier
                .selectableGroup()
                .clip(RoundedCornerShape(999.dp))
                .background(Azphalt.Ink)
                .padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            listOfNotNull(
                runSegmentString(LocalRunSegment.current)?.let { Triple("live", it, !building) },
                Triple("build", Res.string.build_build, building),
            ).forEach { (mode, label, open) ->
                val fill = if (open) azphaltStrong() else Azphalt.Ink
                val text = stringResource(label)
                Box(
                    modifier = Modifier
                        .semantics {
                            role = Role.RadioButton
                            selected = open
                            contentDescription = text
                        }
                        // With the capsule's own 4dp each side, the control stands 48dp tall.
                        .height(AzphaltMinTouchTarget - 8.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(fill)
                        .clickable {
                            onBuildingChange(mode == "build")
                        }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text.uppercase(), style = AzphaltType.capsule, color = fill.onOpposite())
                }
            }
        }
    }
}

/**
 * Under a new hive's field: its name, saved with Save through [onRename], and the tray of the
 * swarm's [roles], each brought into the hive by a tap ([addTerrariumCreature], as Add creature does).
 */
@Composable
internal fun NewHiveBench(
    definition: WorkflowDefinition,
    roles: Collection<RoleDefinition>,
    onEdit: (TerrariumDependencyEditResult) -> Unit,
    onRename: suspend (String) -> Unit,
    modifier: Modifier = Modifier,
    /** Drafts a swarm from a prompt (SwarmDraft.kt); null when no keeper is linked, and the field is not shown. */
    drafter: (suspend (String) -> SwarmDraftResult)? = null,
) {
    val canDraft = drafter != null
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // The obvious way in: describe the work and a keeper drafts the roles and their links.
        if (canDraft) {
            BenchHeading(stringResource(Res.string.hive_new_draft_title), stringResource(Res.string.hive_new_draft_help))
            SwarmDraftBox(definition, drafter, onEdit, redraft = false, onAdoptName = onRename)
        }
        // Once the planner has named the hive, the name is offered in the field to keep or change.
        val defaultName = stringResource(Res.string.hive_new_name)
        NewHiveNamer(
            key = definition.id.value,
            onRename = onRename,
            suggestion = PromptSuggestions.hiveName(
                drafted = definition.name.takeIf { it != defaultName },
                objective = null,
                nameFromObjective = { it },
            ),
        )
        BenchHeading(
            stringResource(if (canDraft) Res.string.hive_new_build_title else Res.string.hive_new_build_title_only),
            stringResource(Res.string.hive_new_build_help),
        )
        NewHiveTray(definition, roles, onEdit)
    }
}

@Composable
private fun BenchHeading(title: String, help: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = AzphaltType.section, color = azphaltOnSurface(), modifier = Modifier.semantics { heading() })
        Text(help, style = AzphaltType.body.copy(fontWeight = FontWeight.Normal), color = azphaltOnSurface())
    }
}

/** "Name this hive…" and Save, in one Ink capsule. A blank name keeps the one it has. */
@Composable
internal fun NewHiveNamer(
    key: String,
    onRename: suspend (String) -> Unit,
    modifier: Modifier = Modifier,
    /** A name worth starting from, such as the one the planner drafted: the field starts with it, selected. */
    suggestion: String? = null,
) {
    val scope = rememberCoroutineScope()
    var draft by remember(key) { mutableStateOf("") }
    val nameHistory = rememberPromptHistory(PromptKind.HiveName)
    var message by remember(key) { mutableStateOf<String?>(null) }
    val placeholder = stringResource(Res.string.hive_new_name_field)
    val save = {
        val name = draft.trim()
        if (name.isNotEmpty()) {
            scope.launch {
                val saved = runCatching { onRename(name) }
                message = saved.fold(
                    onSuccess = { getString(Res.string.hive_new_saved, name) },
                    onFailure = { it.message ?: getString(Res.string.terrarium_could_not_stage_workflow_edit) },
                )
                if (saved.isSuccess) {
                    nameHistory.record(name)
                    draft = ""
                }
            }
        }
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AzphaltThemedSurface(Azphalt.Ink) {
            val content = azphaltOnSurface()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Azphalt.Ink)
                    .padding(start = 18.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PromptSuggestBox(
                    kind = PromptKind.HiveName,
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    seed = suggestion,
                    onSeeded = { draft = it },
                    resetKey = key,
                ) { input ->
                    BasicTextField(
                        value = input.value,
                        onValueChange = input.onValueChange,
                        singleLine = true,
                        textStyle = AzphaltType.body.copy(color = content),
                        cursorBrush = SolidColor(content),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { save() }),
                        modifier = Modifier.fillMaxWidth().then(input.modifier).semantics { contentDescription = placeholder },
                        decorationBox = { field ->
                            Box(contentAlignment = Alignment.CenterStart) {
                                if (draft.isEmpty()) {
                                    Text(placeholder, style = AzphaltType.body.copy(fontWeight = FontWeight.Normal), color = content.copy(alpha = 0.78f))
                                }
                                field()
                            }
                        },
                    )
                }
                // Save is quiet until there is a name to save: lifted Ink, not the strong fill.
                val ready = draft.isNotBlank()
                val fill = if (ready) azphaltStrong() else AzphaltLiftedInk
                val label = stringResource(Res.string.hive_new_save)
                Box(
                    modifier = Modifier
                        .semantics {
                            role = Role.Button
                            contentDescription = label
                        }
                        .height(AzphaltMinTouchTarget)
                        .clip(RoundedCornerShape(999.dp))
                        .background(fill)
                        .clickable(onClick = { save() })
                        .padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label.uppercase(), style = AzphaltType.capsule.copy(fontSize = 14.sp), color = fill.onOpposite())
                }
            }
        }
        message?.let {
            Text(it, style = AzphaltType.body, color = azphaltOnSurface(), modifier = Modifier.azphaltLiveRegion())
        }
    }
}

/** A hive opened with no creatures is a new one: it opens in Build with the bench under it. */
internal fun opensAsNewHive(definition: WorkflowDefinition): Boolean = definition.tasks.isEmpty()

/** The hive renamed to [name] (trimmed); a blank name keeps the one it has. */
internal fun renamedHive(definition: WorkflowDefinition, name: String): WorkflowDefinition =
    name.trim().takeIf(String::isNotEmpty)?.let { definition.copy(name = it) } ?: definition
