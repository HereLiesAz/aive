package com.hereliesaz.geministrator

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hereliesaz.geministrator.memory.MemoryLayerController
import com.hereliesaz.geministrator.memory.MemoryWeb
import com.hereliesaz.geministrator.memory.MemoryWebLayout
import com.hereliesaz.geministrator.memory.WebGraph
import com.hereliesaz.geministrator.memory.WebGroup
import com.hereliesaz.geministrator.memory.WebLayout
import com.hereliesaz.geministrator.memory.WebNode
import com.hereliesaz.geministrator.memory.WebNote
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.memory_web_backlinks
import com.hereliesaz.geministrator.resources.memory_web_close
import com.hereliesaz.geministrator.resources.memory_web_count
import com.hereliesaz.geministrator.resources.memory_web_empty
import com.hereliesaz.geministrator.resources.memory_web_export_failed
import com.hereliesaz.geministrator.resources.memory_web_exported_partial
import com.hereliesaz.geministrator.resources.memory_web_exported
import com.hereliesaz.geministrator.resources.memory_web_export_go
import com.hereliesaz.geministrator.resources.memory_web_export_change
import com.hereliesaz.geministrator.resources.memory_web_export_choose
import com.hereliesaz.geministrator.resources.memory_web_export_current
import com.hereliesaz.geministrator.resources.memory_web_export_none
import com.hereliesaz.geministrator.resources.memory_web_export_hint
import com.hereliesaz.geministrator.resources.memory_web_export_title
import com.hereliesaz.geministrator.resources.memory_web_filter
import com.hereliesaz.geministrator.resources.memory_web_fit
import com.hereliesaz.geministrator.resources.memory_web_group_category
import com.hereliesaz.geministrator.resources.memory_web_group_context
import com.hereliesaz.geministrator.resources.memory_web_group_contrast
import com.hereliesaz.geministrator.resources.memory_web_group_generalization
import com.hereliesaz.geministrator.resources.memory_web_group_habit
import com.hereliesaz.geministrator.resources.memory_web_group_keeper
import com.hereliesaz.geministrator.resources.memory_web_group_phrase
import com.hereliesaz.geministrator.resources.memory_web_group_procedure
import com.hereliesaz.geministrator.resources.memory_web_group_source
import com.hereliesaz.geministrator.resources.memory_web_group_summary
import com.hereliesaz.geministrator.resources.memory_web_group_tag
import com.hereliesaz.geministrator.resources.memory_web_laying_out
import com.hereliesaz.geministrator.resources.memory_web_lead
import com.hereliesaz.geministrator.resources.memory_web_links
import com.hereliesaz.geministrator.resources.memory_web_map
import com.hereliesaz.geministrator.resources.memory_web_map_description
import com.hereliesaz.geministrator.resources.memory_web_more
import com.hereliesaz.geministrator.resources.memory_web_notes
import com.hereliesaz.geministrator.resources.memory_web_write_failed
import com.hereliesaz.geministrator.resources.memory_web_write_hint
import com.hereliesaz.geministrator.resources.memory_web_write_label
import com.hereliesaz.geministrator.resources.memory_web_write_save
import com.hereliesaz.geministrator.resources.memory_web_write_title
import com.hereliesaz.geministrator.resources.memory_web_written
import com.hereliesaz.geministrator.resources.memory_web_zoom_in
import com.hereliesaz.geministrator.resources.memory_web_zoom_out
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * The Memory Web (docs/Memory-Web.md): the whole memory layer as a mind map and as linked notes. Colour says what a memory is, the
 * size of a node how much it weighs (salience, recalls, uses), the width of a line how strong the link is. Read-only except for one
 * thing: the keeper may write a memory of their own, which is banked like every other.
 */

/** The colour of each family of the layer; the legend, the map and the note chips all use these. */
internal fun webGroupColor(group: WebGroup): Color = when (group) {
    WebGroup.Source -> Azphalt.hues[10]
    WebGroup.Context -> Azphalt.hues[9]
    WebGroup.Tag -> Azphalt.hues[1]
    WebGroup.Phrase -> Azphalt.hues[2]
    WebGroup.Summary -> Azphalt.hues[3]
    WebGroup.Category -> Azphalt.hues[4]
    WebGroup.Generalization -> Azphalt.hues[5]
    WebGroup.Contrast -> Azphalt.hues[6]
    WebGroup.Keeper -> Azphalt.hues[7]
    WebGroup.Habit -> Azphalt.hues[0]
    WebGroup.Procedure -> Azphalt.hues[8]
}

@Composable
private fun webGroupLabel(group: WebGroup): String = stringResource(
    when (group) {
        WebGroup.Source -> Res.string.memory_web_group_source
        WebGroup.Context -> Res.string.memory_web_group_context
        WebGroup.Tag -> Res.string.memory_web_group_tag
        WebGroup.Phrase -> Res.string.memory_web_group_phrase
        WebGroup.Summary -> Res.string.memory_web_group_summary
        WebGroup.Category -> Res.string.memory_web_group_category
        WebGroup.Generalization -> Res.string.memory_web_group_generalization
        WebGroup.Contrast -> Res.string.memory_web_group_contrast
        WebGroup.Keeper -> Res.string.memory_web_group_keeper
        WebGroup.Habit -> Res.string.memory_web_group_habit
        WebGroup.Procedure -> Res.string.memory_web_group_procedure
    },
)

private enum class WebTab { Map, Notes }

private const val NOTES_PAGE = 60
private const val LINKS_SHOWN = 24

@Composable
internal fun MemoryWebView(controller: MemoryLayerController) {
    val snapshot by controller.snapshot.collectAsState()
    val selectedBank by controller.selectedBank.collectAsState()
    val source by produceState<com.hereliesaz.geministrator.memory.MemoryWebSource?>(null, snapshot, selectedBank) {
        value = controller.webSource()
    }
    MemoryWebContent(source, onBank = { controller.bankKeeperMemory(it) != null })
}

/** The Memory Web over a source (null while it loads); [onBank] banks the keeper's own memory and says whether it could. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MemoryWebContent(source: com.hereliesaz.geministrator.memory.MemoryWebSource?, onBank: suspend (String) -> Boolean) {
    val graph: WebGraph? = remember(source) { source?.let(MemoryWeb::graph) }
    val notes = remember(graph) { graph?.let(MemoryWeb::notes).orEmpty().associateBy { it.node.id } }
    val layout by produceState<WebLayout?>(null, graph) {
        value = null
        val g = graph ?: return@produceState
        // Off the main thread: a big bank takes a moment, and the notes below are readable meanwhile.
        value = withContext(Dispatchers.Default) { MemoryWebLayout.layout(g, iterations = if (g.nodes.size > 2_000) 90 else 240) }
    }
    var tab by remember { mutableStateOf(WebTab.Map) }
    var filter by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(Res.string.memory_web_lead), style = AzphaltType.body, color = azphaltOnSurface())
        if (graph == null || graph.nodes.isEmpty()) {
            Text(stringResource(Res.string.memory_web_empty), style = AzphaltType.body, color = azphaltOnSurface())
            WriteMemory(onBank)
            return@Column
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AzphaltPill(stringResource(Res.string.memory_web_map), "web-tab-map", selected = tab == WebTab.Map, onClick = { tab = WebTab.Map })
            AzphaltPill(stringResource(Res.string.memory_web_notes), "web-tab-notes", selected = tab == WebTab.Notes, onClick = { tab = WebTab.Notes })
        }
        Text(
            stringResource(Res.string.memory_web_count, graph.nodes.size, graph.edges.size),
            style = AzphaltType.eyebrow,
            color = azphaltOnSurface(),
        )
        Legend(graph)
        AzphaltWellField(
            value = filter,
            onValueChange = { filter = it },
            label = { Text(stringResource(Res.string.memory_web_filter)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val matching = remember(graph, filter) {
            val q = filter.trim()
            if (q.isEmpty()) null else graph.nodes.filter { it.title.contains(q, true) || it.text.contains(q, true) }.mapTo(mutableSetOf()) { it.id }
        }
        when (tab) {
            WebTab.Map -> WebMap(graph, layout, matching, selected, onSelect = { selected = it })
            WebTab.Notes -> NoteList(graph, matching, selected, onSelect = { selected = it })
        }
        selected?.let { id -> notes[id] }?.let { note -> NotePanel(note, onSelect = { selected = it }, onClose = { selected = null }) }
        WriteMemory(onBank)
        if (source != null && ArtifactFolderHost.service != null) ExportControls(source)
    }
}

/** The colour key: each family that is present, with how many things it holds. Tapping nothing; it is a legend. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend(graph: WebGraph) {
    val counts = remember(graph) { graph.nodes.groupingBy { it.group }.eachCount() }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        WebGroup.entries.filter { it in counts }.forEach { group ->
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(RoundedCornerShape(6.dp)).background(webGroupColor(group)))
                Text(
                    " ${webGroupLabel(group)} ${counts[group]}",
                    style = AzphaltType.body,
                    color = azphaltOnSurface(),
                )
            }
        }
    }
}

@Composable
private fun WriteMemory(onBank: suspend (String) -> Boolean) {
    var text by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.memory_web_write_title).uppercase(), style = AzphaltType.eyebrow, color = azphaltOnSurface())
        Text(stringResource(Res.string.memory_web_write_hint), style = AzphaltType.body, color = azphaltOnSurface())
        AzphaltWellField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(Res.string.memory_web_write_label)) },
            modifier = Modifier.fillMaxWidth(),
        )
        AzphaltPill(
            label = stringResource(Res.string.memory_web_write_save),
            seed = "web-write",
            enabled = text.isNotBlank(),
            onClick = {
                val body = text
                scope.launch {
                    val banked = onBank(body)
                    if (banked) text = ""
                    message = getString(if (banked) Res.string.memory_web_written else Res.string.memory_web_write_failed)
                }
            },
        )
        message?.let { Text(it, style = AzphaltType.body, color = azphaltOnSurface(), modifier = Modifier.azphaltLiveRegion()) }
    }
}

/**
 * The mind map: a node per thing, sized by weight and coloured by family, a line per link as wide as it is strong. Pan, zoom
 * (pinch, wheel or the buttons) and tap. The level of detail follows the zoom: far out, only the weighty nodes are named and only
 * the strong links drawn; closer in, everything.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WebMap(graph: WebGraph, layout: WebLayout?, matching: Set<String>?, selected: String?, onSelect: (String?) -> Unit) {
    if (layout == null) {
        Text(stringResource(Res.string.memory_web_laying_out), style = AzphaltType.body, color = azphaltOnSurface(), modifier = Modifier.azphaltLiveRegion())
        return
    }
    var scale by remember(layout) { mutableStateOf(1f) }
    var pan by remember(layout) { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(Offset.Zero) }
    val measurer = rememberTextMeasurer()
    val nodes = graph.nodes
    val byId = remember(graph) { nodes.associateBy { it.id } }
    val mapDescription = stringResource(Res.string.memory_web_map_description)
    val labelStyle = TextStyle(color = Color.White, fontSize = 11.sp)

    // Fit the whole web into the canvas, then let the keeper move from there.
    fun baseScale(): Float = if (layout.fitRadius <= 0f || canvasSize.x <= 0f) 1f else min(canvasSize.x, canvasSize.y) / 2f / layout.fitRadius * 0.95f
    fun toScreen(p: com.hereliesaz.geministrator.memory.WebPoint): Offset {
        val s = baseScale() * scale
        return Offset(canvasSize.x / 2f + pan.x + p.x * s, canvasSize.y / 2f + pan.y + p.y * s)
    }
    fun radiusOf(node: WebNode): Float = MemoryWebLayout.nodeRadius(node.size) * baseScale() * scale

    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AzphaltPill(stringResource(Res.string.memory_web_zoom_in), "web-zoom-in", onClick = { scale = min(40f, scale * 1.4f) })
        AzphaltPill(stringResource(Res.string.memory_web_zoom_out), "web-zoom-out", onClick = { scale = max(0.2f, scale / 1.4f) })
        AzphaltPill(stringResource(Res.string.memory_web_fit), "web-fit", onClick = { scale = 1f; pan = Offset.Zero })
    }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(560.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(Azphalt.Ink)
            .semantics { contentDescription = mapDescription }
            .pointerInput(layout) {
                detectTransformGestures { centroid, panChange, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(0.2f, 40f)
                    // Zoom about the fingers, not the corner.
                    val about = centroid - Offset(canvasSize.x / 2f, canvasSize.y / 2f)
                    pan = (pan - about) * (newScale / scale) + about + panChange
                    scale = newScale
                }
            }
            .pointerInput(layout) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Scroll) {
                            val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                            if (delta != 0f) scale = (scale * (if (delta < 0f) 1.15f else 1f / 1.15f)).coerceIn(0.2f, 40f)
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            }
            .pointerInput(layout, graph) {
                detectTapGestures { tap ->
                    var best: WebNode? = null
                    var bestDistance = Float.MAX_VALUE
                    nodes.forEach { node ->
                        val at = toScreen(layout.positions.getValue(node.id))
                        val d = (at - tap).getDistance()
                        if (d <= max(radiusOf(node), 10f) && d < bestDistance) {
                            best = node
                            bestDistance = d
                        }
                    }
                    onSelect(best?.id)
                }
            },
    ) {
        canvasSize = Offset(size.width, size.height)
        val zoom = baseScale() * scale
        // Far out, a big web draws only its strong links; closer in, all of them.
        val threshold = if (graph.edges.size < 1_500) 0f else (0.85f - 0.8f * min(1f, scale / 3f)).coerceAtLeast(0f)
        graph.edges.forEach { edge ->
            if (edge.weight < threshold) return@forEach
            val a = layout.positions[edge.from] ?: return@forEach
            val b = layout.positions[edge.to] ?: return@forEach
            val pa = toScreen(a)
            val pb = toScreen(b)
            if ((pa.x < 0f && pb.x < 0f) || (pa.y < 0f && pb.y < 0f) || (pa.x > size.width && pb.x > size.width) || (pa.y > size.height && pb.y > size.height)) return@forEach
            val dim = matching != null && (edge.from !in matching || edge.to !in matching)
            val colour = webGroupColor(byId.getValue(edge.from).group).copy(alpha = if (dim) 0.07f else 0.2f + 0.45f * edge.weight)
            // Width is strength: a faint link is a hairline, a strong one is a rope.
            drawLine(colour, pa, pb, strokeWidth = (0.6f + 6f * edge.weight) * min(1.6f, max(0.6f, zoom)), cap = StrokeCap.Round)
        }
        val named = nodes.sortedByDescending { it.size }
        nodes.forEach { node ->
            val at = toScreen(layout.positions.getValue(node.id))
            val r = max(2f, radiusOf(node))
            if (at.x + r < 0f || at.y + r < 0f || at.x - r > size.width || at.y - r > size.height) return@forEach
            val dim = matching != null && node.id !in matching
            drawCircle(webGroupColor(node.group).copy(alpha = if (dim) 0.15f else 0.95f), r, at)
            if (node.id == selected) drawCircle(Color.White, r + 3f, at, style = Stroke(width = 3f))
        }
        // Names: the weighty ones when far out, more as the view closes in; never more than fit to read.
        var drawn = 0
        val taken = mutableListOf<androidx.compose.ui.geometry.Rect>()
        val budget = (20 + 30 * scale).toInt().coerceAtMost(140)
        for (node in named) {
            if (drawn >= budget) break
            val at = toScreen(layout.positions.getValue(node.id))
            val r = radiusOf(node)
            if (at.x < 0f || at.y < 0f || at.x > size.width || at.y > size.height) continue
            if (r < 6f && node.id != selected) continue
            if (matching != null && node.id !in matching) continue
            val text = measurer.measure(node.title, labelStyle, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, constraints = androidx.compose.ui.unit.Constraints(maxWidth = 190))
            val topLeft = Offset(at.x - text.size.width / 2f, at.y + r + 2f)
            val box = androidx.compose.ui.geometry.Rect(topLeft, androidx.compose.ui.geometry.Size(text.size.width.toFloat(), text.size.height.toFloat()))
            // A name that would sit on another name is left out; zoom in and it has room.
            if (taken.any { it.overlaps(box) }) continue
            taken += box
            drawText(text, topLeft = topLeft)
            drawn += 1
        }
    }
}

@Composable
private fun NoteList(graph: WebGraph, matching: Set<String>?, selected: String?, onSelect: (String?) -> Unit) {
    var shown by remember(graph, matching) { mutableStateOf(NOTES_PAGE) }
    val rows = remember(graph, matching) {
        graph.nodes.filter { matching == null || it.id in matching }.sortedByDescending { it.size }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.take(shown).forEach { node ->
            AzphaltRecord(
                seed = "web-note-${node.id}",
                eyebrow = webGroupLabel(node.group).uppercase(),
                title = node.title,
                body = node.text.take(220),
                selected = node.id == selected,
                onClick = { onSelect(node.id) },
            )
        }
        if (rows.size > shown) {
            AzphaltPill(
                label = stringResource(Res.string.memory_web_more, min(NOTES_PAGE, rows.size - shown)),
                seed = "web-notes-more",
                onClick = { shown += NOTES_PAGE },
            )
        }
    }
}

/** One note, read: what it is, its words, where it came from, then what it links to and what links to it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotePanel(note: WebNote, onSelect: (String) -> Unit, onClose: () -> Unit) {
    val node = note.node
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Azphalt.Ink).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        androidx.compose.foundation.layout.Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.size(12.dp).clip(RoundedCornerShape(6.dp)).background(webGroupColor(node.group)))
            Text("${webGroupLabel(node.group)} · ${node.kind}".uppercase(), style = AzphaltType.eyebrow, color = Azphalt.White)
        }
        Text(node.title, style = AzphaltType.lead, color = Azphalt.White)
        SelectionContainer { Text(node.text, style = AzphaltType.body, color = Azphalt.White) }
        node.sources.take(6).forEach { Text(it, style = AzphaltType.eyebrow, color = Azphalt.White.copy(alpha = 0.6f)) }
        LinkSection(stringResource(Res.string.memory_web_links), note.outgoing, onSelect)
        LinkSection(stringResource(Res.string.memory_web_backlinks), note.backlinks, onSelect)
        AzphaltPill(stringResource(Res.string.memory_web_close), "web-note-close", onClick = onClose)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LinkSection(title: String, links: List<com.hereliesaz.geministrator.memory.WebLink>, onSelect: (String) -> Unit) {
    if (links.isEmpty()) return
    Text("$title (${links.size})".uppercase(), style = AzphaltType.eyebrow, color = Azphalt.White)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        links.take(LINKS_SHOWN).forEachIndexed { index, link ->
            AzphaltPill(label = link.title.take(40), seed = "web-link-$title-$index", endCap = link.relation, onClick = { onSelect(link.target) })
        }
    }
}

/** Open in Obsidian: choose a folder once, then export the web into a new subfolder of it each time. */
@OptIn(ExperimentalLayoutApi::class, kotlin.time.ExperimentalTime::class)
@Composable
private fun ExportControls(source: com.hereliesaz.geministrator.memory.MemoryWebSource) {
    // Read through the counter so a change anywhere redraws this.
    @Suppress("UNUSED_VARIABLE") val changes = ArtifactFolderSettings.changes
    val service = ArtifactFolderHost.service ?: return
    val folder = ArtifactFolderSettings.folder(MemoryWebExport.FOLDER_KEY)
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.memory_web_export_title).uppercase(), style = AzphaltType.eyebrow, color = azphaltOnSurface())
        Text(stringResource(Res.string.memory_web_export_hint), style = AzphaltType.body, color = azphaltOnSurface())
        Text(
            if (folder == null) stringResource(Res.string.memory_web_export_none) else stringResource(Res.string.memory_web_export_current, folder.displayName),
            style = AzphaltType.body,
            color = azphaltOnSurface(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            AzphaltPill(
                label = stringResource(if (folder == null) Res.string.memory_web_export_choose else Res.string.memory_web_export_change),
                seed = "web-export-folder",
                onClick = { scope.launch { service.chooseFolder()?.let { ArtifactFolderSettings.setFolder(MemoryWebExport.FOLDER_KEY, it) } } },
            )
            if (folder != null) {
                AzphaltPill(
                    label = stringResource(Res.string.memory_web_export_go),
                    seed = "web-export-go",
                    onClick = {
                        scope.launch {
                            val subfolder = MemoryWebExport.subfolderFor(MemoryWebExport.stampOf(kotlin.time.Clock.System.now().toEpochMilliseconds()))
                            val result = MemoryWebExport.export(source, subfolder)
                            message = when {
                                result.noFolder || result.written == 0 -> getString(Res.string.memory_web_export_failed)
                                result.failed == 0 -> getString(Res.string.memory_web_exported, result.written, subfolder)
                                else -> getString(Res.string.memory_web_exported_partial, result.written, result.failed)
                            }
                        }
                    },
                )
            }
        }
        message?.let { Text(it, style = AzphaltType.body, color = azphaltOnSurface(), modifier = Modifier.azphaltLiveRegion()) }
    }
}
