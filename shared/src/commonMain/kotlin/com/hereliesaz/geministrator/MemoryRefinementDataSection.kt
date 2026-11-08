package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.memory_refinement
import com.hereliesaz.geministrator.resources.memory_refinement_clear
import com.hereliesaz.geministrator.resources.memory_refinement_clear_sure
import com.hereliesaz.geministrator.resources.memory_refinement_cleared
import com.hereliesaz.geministrator.resources.memory_refinement_copied
import com.hereliesaz.geministrator.resources.memory_refinement_copy
import com.hereliesaz.geministrator.resources.memory_refinement_copy_failed
import com.hereliesaz.geministrator.resources.memory_refinement_counts
import com.hereliesaz.geministrator.resources.memory_refinement_empty
import com.hereliesaz.geministrator.resources.memory_refinement_hint
import com.hereliesaz.geministrator.resources.memory_refinement_learning
import com.hereliesaz.geministrator.resources.memory_refinement_level
import com.hereliesaz.geministrator.resources.memory_refinement_off
import com.hereliesaz.geministrator.resources.memory_refinement_recent
import com.hereliesaz.geministrator.workflow.refinement.LadderDatasetBridge
import com.hereliesaz.geministrator.workflow.refinement.LadderDatasetRecord
import com.hereliesaz.geministrator.workflow.refinement.LadderDatasetSummary
import com.hereliesaz.geministrator.workflow.refinement.line
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * The keeper's view of the refinement-ladder dataset (docs/PRIVACY.md): the switch that lets finished
 * ladder levels be kept on this device, what has been kept and how near it is to enough to train on,
 * the newest records as plain lines, and copy or clear. Nothing leaves the device unless it is copied.
 */
@Suppress("DEPRECATION")
@Composable
internal fun MemoryRefinementDataSection() {
    val capture = LadderDatasetBridge.capture ?: return
    val scope = rememberCoroutineScope()
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    var enabled by remember { mutableStateOf(capture.enabled) }
    var refresh by remember { mutableIntStateOf(0) }
    var summary by remember { mutableStateOf<LadderDatasetSummary?>(null) }
    var recent by remember { mutableStateOf<List<LadderDatasetRecord>>(emptyList()) }
    var confirmClear by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(refresh, enabled) {
        summary = runCatching { capture.summary() }.getOrNull()
        recent = runCatching { capture.recent() }.getOrDefault(emptyList())
    }

    Text(stringResource(Res.string.memory_refinement).uppercase(), style = AzphaltType.eyebrow, color = azphaltOnSurface())
    Text(stringResource(Res.string.memory_refinement_hint), style = AzphaltType.body, color = azphaltOnSurface())
    AzphaltPill(
        if (enabled) stringResource(Res.string.memory_refinement_learning) else stringResource(Res.string.memory_refinement_off),
        "memory-refinement-capture",
        selected = enabled,
        onClick = { capture.enabled = !capture.enabled; enabled = capture.enabled },
    )
    val shown = summary
    if (shown == null || shown.records == 0) {
        Text(stringResource(Res.string.memory_refinement_empty), style = AzphaltType.body, color = azphaltOnSurface())
    } else {
        Text(
            stringResource(
                Res.string.memory_refinement_counts,
                shown.records.toString(), shown.ladders.toString(), shown.structured.toString(),
                shown.turnedBack.toString(), shown.outcomes.toString(),
            ),
            style = AzphaltType.body,
            color = azphaltOnSurface(),
        )
        shown.usablePerLevel.forEach { (level, rows) ->
            Text(
                stringResource(
                    Res.string.memory_refinement_level,
                    level.toString(), rows.toString(), LadderDatasetSummary.ROWS_NEEDED.toString(),
                ),
                style = AzphaltType.body,
                color = azphaltOnSurface(),
            )
        }
        Text(stringResource(Res.string.memory_refinement_recent), style = AzphaltType.eyebrow, color = azphaltOnSurface())
        SelectionContainer {
            Text(recent.joinToString("\n") { it.line() }, style = AzphaltType.body, color = azphaltOnSurface())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AzphaltPill(
                stringResource(Res.string.memory_refinement_copy),
                "memory-refinement-copy",
                onClick = {
                    scope.launch {
                        message = runCatching { clipboard.setText(AnnotatedString(capture.exportJsonl())) }.fold(
                            onSuccess = { getString(Res.string.memory_refinement_copied) },
                            onFailure = { getString(Res.string.memory_refinement_copy_failed) },
                        )
                    }
                },
            )
            AzphaltPill(
                stringResource(if (confirmClear) Res.string.memory_refinement_clear_sure else Res.string.memory_refinement_clear),
                "memory-refinement-clear",
                onClick = {
                    if (!confirmClear) {
                        confirmClear = true
                    } else {
                        scope.launch {
                            capture.clear()
                            confirmClear = false
                            refresh++
                            message = getString(Res.string.memory_refinement_cleared)
                        }
                    }
                },
            )
        }
    }
    message?.let { Text(it, style = AzphaltType.body, color = azphaltOnSurface()) }
}
