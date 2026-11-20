package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.artifact_folder_auto
import com.hereliesaz.geministrator.resources.artifact_folder_change
import com.hereliesaz.geministrator.resources.artifact_folder_choose
import com.hereliesaz.geministrator.resources.artifact_folder_current
import com.hereliesaz.geministrator.resources.artifact_folder_none
import com.hereliesaz.geministrator.resources.artifact_folder_stop
import com.hereliesaz.geministrator.resources.artifact_folder_title
import com.hereliesaz.geministrator.resources.artifact_folder_save_all
import com.hereliesaz.geministrator.resources.artifact_save_failed
import com.hereliesaz.geministrator.resources.artifact_save_nothing
import com.hereliesaz.geministrator.resources.artifact_saved_count
import com.hereliesaz.geministrator.resources.artifact_saved_partial
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/** The sentence for how a save went. */
internal suspend fun saveMessage(result: ArtifactSaveResult): String = when {
    result.noFolder -> getString(Res.string.artifact_save_failed)
    result.written == 0 && result.failed == 0 -> getString(Res.string.artifact_save_nothing)
    result.failed == 0 -> getString(Res.string.artifact_saved_count, result.written)
    result.written == 0 -> getString(Res.string.artifact_save_failed)
    else -> getString(Res.string.artifact_saved_partial, result.written, result.failed)
}

/**
 * The project's artifact folder, on the hive beside what it made (docs/Artifact-Folders.md): choose or change the folder, switch on
 * saving as artifacts are made, save everything of this run now, or stop saving. Off until a folder is chosen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ArtifactFolderControls(
    projectId: String,
    definition: WorkflowDefinition,
    run: WorkflowRun,
    runNumber: Int?,
    entries: List<ArtifactEntry>,
) {
    // Read through the counter so a change anywhere redraws this.
    @Suppress("UNUSED_VARIABLE") val changes = ArtifactFolderSettings.changes
    val service = ArtifactFolderHost.service ?: return
    val scope = rememberCoroutineScope()
    var message by remember(run.id.value) { mutableStateOf<String?>(null) }
    val folder = ArtifactFolderSettings.folder(projectId)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.artifact_folder_title).uppercase(), style = AzphaltType.eyebrow, color = azphaltOnSurface())
        Text(
            if (folder == null) stringResource(Res.string.artifact_folder_none) else stringResource(Res.string.artifact_folder_current, folder.displayName),
            style = AzphaltType.body,
            color = azphaltOnSurface(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AzphaltPill(
                label = stringResource(if (folder == null) Res.string.artifact_folder_choose else Res.string.artifact_folder_change),
                seed = "made-folder-choose",
                onClick = {
                    scope.launch {
                        service.chooseFolder()?.let { ArtifactFolderSettings.setFolder(projectId, it) }
                    }
                },
            )
            if (folder != null) {
                AzphaltPill(
                    label = stringResource(Res.string.artifact_folder_save_all),
                    seed = "made-folder-save-all",
                    onClick = { scope.launch { message = saveMessage(ArtifactSaver.save(projectId, definition, run, runNumber, entries)) } },
                )
                AzphaltPill(
                    label = stringResource(Res.string.artifact_folder_stop),
                    seed = "made-folder-stop",
                    onClick = { ArtifactFolderSettings.setFolder(projectId, null) },
                )
            }
        }
        if (folder != null) {
            AzphaltToggle(
                label = stringResource(Res.string.artifact_folder_auto),
                seed = "made-folder-auto",
                selected = ArtifactFolderSettings.automatic(projectId),
                onClick = { ArtifactFolderSettings.setAutomatic(projectId, !ArtifactFolderSettings.automatic(projectId)) },
            )
        }
        message?.let { Text(it, style = AzphaltType.body, color = azphaltOnSurface(), modifier = Modifier.azphaltLiveRegion()) }
    }
}
