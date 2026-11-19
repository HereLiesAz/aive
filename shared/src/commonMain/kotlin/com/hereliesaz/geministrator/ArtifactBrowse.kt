package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.domain.ArtifactKind
import com.hereliesaz.geministrator.domain.ArtifactRef
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.artifact_copied
import com.hereliesaz.geministrator.resources.artifact_copy
import com.hereliesaz.geministrator.resources.artifact_copy_failed
import com.hereliesaz.geministrator.resources.artifact_copy_link
import com.hereliesaz.geministrator.resources.artifact_media_type
import com.hereliesaz.geministrator.resources.artifact_open
import com.hereliesaz.geministrator.resources.artifact_open_change
import com.hereliesaz.geministrator.resources.artifact_open_failed
import com.hereliesaz.geministrator.resources.artifact_open_media
import com.hereliesaz.geministrator.resources.artifact_open_pull_request
import com.hereliesaz.geministrator.resources.artifact_open_release
import com.hereliesaz.geministrator.resources.hive_made_count
import com.hereliesaz.geministrator.resources.hive_made_filter
import com.hereliesaz.geministrator.resources.hive_made_none_match
import com.hereliesaz.geministrator.resources.hive_made_show_all
import com.hereliesaz.geministrator.resources.hive_made_show_less
import com.hereliesaz.geministrator.resources.hive_what_it_made
import com.hereliesaz.geministrator.tour.TourTargetId
import com.hereliesaz.geministrator.tour.tourTarget
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * One artifact with the creature that made it, for the hive's "What it made" (docs/Keepers-Room.md): the
 * retired Artifacts screen's capabilities (browse a whole run, filter it) folded into the hive, not a place.
 * [isFinal] is true for a creature nothing else feeds on, whose work is the hive's result.
 */
internal data class ArtifactEntry(
    val taskId: String,
    val creature: String,
    val isFinal: Boolean,
    val artifact: ArtifactRef,
)

/** One creature's artifacts, in the order they were made. */
internal data class ArtifactGroup(
    val taskId: String,
    val creature: String,
    val isFinal: Boolean,
    val entries: List<ArtifactEntry>,
)

/** Every artifact of [run], in the definition's task order, each with its creature. Pure; any run status. */
internal fun artifactEntries(definition: WorkflowDefinition, run: WorkflowRun): List<ArtifactEntry> {
    val tasks = definition.tasks
    return tasks.flatMap { task ->
        val isFinal = tasks.none { other -> task.id in other.dependsOn }
        run.taskRuns[task.id]?.artifacts.orEmpty().map { artifact ->
            ArtifactEntry(task.id.value, task.name, isFinal, artifact)
        }
    }
}

/**
 * The entries a keeper's filter text matches: creature name or id, label, kind, uri or media type, ignoring
 * case (the search the retired Artifacts screen had). Blank text keeps everything.
 */
internal fun List<ArtifactEntry>.matching(query: String): List<ArtifactEntry> {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return this
    return filter { entry ->
        listOf(
            entry.creature,
            entry.taskId,
            entry.artifact.label,
            entry.artifact.kind.name,
            entry.artifact.uri.orEmpty(),
            entry.artifact.mediaType.orEmpty(),
        ).any { it.lowercase().contains(needle) }
    }
}

/** The entries grouped by creature: the creatures that hold the result first, then the rest, each in task order. */
internal fun List<ArtifactEntry>.groupedByCreature(): List<ArtifactGroup> {
    val groups = map { it.taskId }.distinct().map { id ->
        val own = filter { it.taskId == id }
        ArtifactGroup(id, own.first().creature, own.first().isFinal, own)
    }
    return groups.filter { it.isFinal } + groups.filterNot { it.isFinal }
}

/** The artifact's link when it is a web address the platform can open; anything else is only copied. */
internal fun artifactWebUri(artifact: ArtifactRef): String? =
    artifact.uri?.trim()?.takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }

/** An artifact's text as shown: [text] is the whole of it when [expanded], else cut at [limit]; [hasMore] says a cut exists. */
internal data class ArtifactPreview(val text: String, val hasMore: Boolean)

internal fun artifactPreview(text: String, expanded: Boolean, limit: Int = ARTIFACT_PREVIEW_CHARS): ArtifactPreview {
    val trimmed = text.trim()
    val hasMore = trimmed.length > limit
    return if (hasMore && !expanded) ArtifactPreview(trimmed.take(limit).trimEnd() + "…", true) else ArtifactPreview(trimmed, hasMore)
}

internal const val ARTIFACT_PREVIEW_CHARS = 2_400

/**
 * What the keeper can do with one artifact, wherever it is shown: its media type, Copy (the text), Copy link
 * (its uri), and Open for a web address. Only the kinds that name a place to go (a pull request, a release,
 * media, a change) say so in the button; the rest say Open.
 */
@OptIn(ExperimentalLayoutApi::class)
@Suppress("DEPRECATION")
@Composable
internal fun ArtifactActions(artifact: ArtifactRef, seed: String) {
    val clipboard = LocalClipboardManager.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var message by remember(artifact.id.value) { mutableStateOf<String?>(null) }
    val webUri = artifactWebUri(artifact)
    val text = artifact.textContent?.takeIf(String::isNotBlank)
    val uri = artifact.uri?.takeIf(String::isNotBlank)

    fun copy(value: String) {
        scope.launch {
            message = runCatching { clipboard.setText(AnnotatedString(value)) }.fold(
                onSuccess = { getString(Res.string.artifact_copied) },
                onFailure = { getString(Res.string.artifact_copy_failed) },
            )
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        artifact.mediaType?.takeIf(String::isNotBlank)?.let {
            Text(stringResource(Res.string.artifact_media_type, it), style = AzphaltType.eyebrow, color = azphaltOnSurface())
        }
        if (text != null || uri != null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (text != null) {
                    AzphaltPill(
                        label = stringResource(Res.string.artifact_copy),
                        seed = "$seed-copy",
                        onClick = { copy(text) },
                    )
                }
                if (uri != null) {
                    AzphaltPill(
                        label = stringResource(Res.string.artifact_copy_link),
                        seed = "$seed-copy-link",
                        onClick = { copy(uri) },
                    )
                }
                if (webUri != null) {
                    AzphaltPill(
                        label = stringResource(
                            when (artifact.kind) {
                                ArtifactKind.PullRequest -> Res.string.artifact_open_pull_request
                                ArtifactKind.Release -> Res.string.artifact_open_release
                                ArtifactKind.Media -> Res.string.artifact_open_media
                                ArtifactKind.CodeChange -> Res.string.artifact_open_change
                                else -> Res.string.artifact_open
                            },
                        ),
                        seed = "$seed-open",
                        onClick = {
                            scope.launch {
                                runCatching { uriHandler.openUri(webUri) }
                                    .onFailure { message = getString(Res.string.artifact_open_failed) }
                            }
                        },
                    )
                }
            }
        }
        message?.let { Text(it, style = AzphaltType.body, color = azphaltOnSurface(), modifier = Modifier.azphaltLiveRegion()) }
    }
}

/**
 * "What it made" on the hive: every artifact of a finished run (completed, failed or cancelled), grouped by
 * the creature that made it, filtered by one field. A row opens that creature's Work ([onOpenWork]); a long
 * text is cut at [ARTIFACT_PREVIEW_CHARS] with Show all beside it, never silently. The scars switch which run
 * the hive shows, so this reads an earlier run too.
 */
@Composable
internal fun HiveMadeSection(
    definition: WorkflowDefinition,
    run: WorkflowRun,
    onOpenWork: (String) -> Unit,
) {
    val entries = remember(definition, run) { artifactEntries(definition, run) }
    if (entries.isEmpty()) return
    var query by rememberDurableStringState("artifacts.${run.id.value}.query")
    var expanded by remember(run.id.value) { mutableStateOf<Set<String>>(emptySet()) }
    val shown = entries.matching(query)

    Text(
        stringResource(Res.string.hive_what_it_made).uppercase(),
        style = AzphaltType.eyebrow,
        color = azphaltOnSurface(),
        modifier = Modifier.tourTarget(TourTargetId.HIVE_MADE),
    )
    Text(
        pluralStringResource(Res.plurals.hive_made_count, entries.size, entries.size),
        style = AzphaltType.body,
        color = azphaltOnSurface(),
    )
    if (entries.size > 1) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text(stringResource(Res.string.hive_made_filter)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (shown.isEmpty()) {
        Text(stringResource(Res.string.hive_made_none_match, query.trim()), style = AzphaltType.body, color = azphaltOnSurface())
        return
    }
    shown.groupedByCreature().forEach { group ->
        Text(group.creature.uppercase(), style = AzphaltType.eyebrow, color = azphaltOnSurface())
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                group.entries.forEach { entry ->
                    val artifact = entry.artifact
                    val id = artifact.id.value
                    val isExpanded = id in expanded
                    val preview = artifact.textContent?.takeIf(String::isNotBlank)?.let { artifactPreview(it, isExpanded) }
                    AzphaltRecord(
                        seed = "made-$id",
                        eyebrow = artifact.kind.name,
                        title = artifact.label,
                        body = if (preview == null) artifact.uri ?: artifact.mediaType else null,
                        richBody = preview?.let { markdownLite(it.text) },
                        onClick = { onOpenWork(entry.taskId) },
                        well = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (preview != null && preview.hasMore) {
                                    AzphaltPill(
                                        label = stringResource(
                                            if (isExpanded) Res.string.hive_made_show_less else Res.string.hive_made_show_all,
                                        ),
                                        seed = "made-$id-expand",
                                        onClick = { expanded = if (isExpanded) expanded - id else expanded + id },
                                    )
                                }
                                ArtifactActions(artifact, "made-$id")
                            }
                        },
                    )
                }
            }
        }
    }
}
