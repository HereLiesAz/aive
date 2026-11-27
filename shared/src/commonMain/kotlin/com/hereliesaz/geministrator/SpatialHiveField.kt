package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hereliesaz.conveyance.h2g2.H2g2TerrariumRelationship
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.memory.MemoryLayerController
import com.hereliesaz.geministrator.memory.MemoryWeb
import com.hereliesaz.geministrator.memory.WebNote
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.spatial_colony_view
import com.hereliesaz.geministrator.resources.spatial_memory_view
import com.russhwolf.settings.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

/** Reuses the live memory controller. Never changes its selected bank to follow the hive. */
internal val LocalSpatialHiveMemory = staticCompositionLocalOf<MemoryLayerController?> { null }

private data class SpatialMemoryRead(val geometry: SpatialMemoryProjection, val notes: Map<String, WebNote>)

/** Owns the scene session, camera, and validated presentation inputs. Runtime work stays in existing callers. */
@Composable
internal fun SpatialHiveField(
    projection: WorkflowTerrariumProjection,
    relationships: List<H2g2TerrariumRelationship>,
    tissue: TissueScene,
    run: WorkflowRun,
    selected: String?,
    onAgent: (String) -> Unit,
    onEmpty: () -> Unit,
    onFailure: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val controller = LocalSpatialHiveMemory.current
    key(run.id, controller) {
        val revision = controller?.webRevision?.collectAsState()?.value ?: 0L
        val memory by produceState<SpatialMemoryRead?>(null, controller, revision) {
            val source = controller ?: return@produceState
            try {
                val bank = source.webSourceForRun(run.id.value)
                value = withContext(Dispatchers.Default) {
                    val graph = MemoryWeb.graph(bank)
                    SpatialMemoryRead(projectSpatialMemory(graph), MemoryWeb.notes(graph).associateBy { it.node.id })
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                value = null
            }
        }
        val settings = remember { Settings() }
        val layout = remember(settings) { SpatialHiveLayoutStore(settings) }
        val store = remember(settings) { SpatialTissueStore(settings) }
        val emptyMemory = remember { SpatialMemoryProjection(emptyList(), emptyList()) }
        val scene = remember(projection, relationships, tissue, run, memory) {
            projectSpatialHive(projection, relationships, tissue, run, layout, memory?.geometry ?: emptyMemory)
        }
        var journal by remember { mutableStateOf(SpatialTissueJournal(run.id.value)) }
        LaunchedEffect(scene.runId, scene.creatures.map { it.id }) {
            try {
                // The persisted sequence distinguishes actual openings and newly visible sites.
                val previous = store.load(scene.runId)
                journal = store.advance(scene, "opening:" + previous.events.size)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                onFailure()
            }
        }
        val frame = remember(scene, journal) { scene.renderFrame(journal) }
        var camera by remember { mutableStateOf(SpatialCameraState()) }
        var memoryId by remember { mutableStateOf<String?>(null) }
        val reduced = AzphaltMotion.reduceMotion
        var motionPhase by remember { mutableStateOf(0f) }
        LaunchedEffect(selected) {
            val point = frame.bodies.firstOrNull { it.id == selected }?.position
            if (point != null) camera = camera.focus(point, explicit = true)
            else camera = camera.clearFocus()
        }
        LaunchedEffect(scene.connections.filter { it.activeTransfer }) {
            val target = scene.connections.firstOrNull { it.activeTransfer }?.to
            frame.bodies.firstOrNull { it.id == target }?.let { camera = camera.focus(it.position, explicit = false) }
        }
        LaunchedEffect(reduced) {
            var previous = 0L
            while (true) {
                withFrameNanos { now ->
                    val elapsed = if (previous == 0L) 0f else ((now - previous) / 1_000_000_000f).coerceIn(0f, .1f)
                    previous = now
                    val next = camera.advance(elapsed, reduced)
                    if (next != camera) camera = next
                    motionPhase = advanceSpatialMotion(motionPhase, elapsed, reduced)
                }
            }
        }
        Column(modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = { memoryId = null; camera = camera.colony() }) {
                    Text(stringResource(Res.string.spatial_colony_view))
                }
                TextButton(onClick = { camera = camera.memory(frame.spores.map { it.position }) }) {
                    Text(stringResource(Res.string.spatial_memory_view))
                }
            }
            SpatialHiveSurface(frame, camera.renderCamera(if (reduced) 0f else motionPhase), onInput = { input ->
                if (frame.accepts(input)) when (input.type) {
                    "agent" -> {
                        memoryId = null
                        frame.bodies.firstOrNull { it.id == input.id }?.let { camera = camera.focus(it.position, explicit = true) }
                        onAgent(input.id)
                    }
                    "memory" -> {
                        memoryId = input.id
                        frame.spores.firstOrNull { it.id == input.id }?.let { camera = camera.focus(it.position, explicit = true) }
                    }
                    "empty" -> { memoryId = null; camera = camera.clearFocus(); onEmpty() }
                    "orbit" -> camera = camera.orbit(input.x, input.y)
                    "pan" -> camera = camera.pan(SpatialPoint(input.x, input.y, input.z))
                    "zoom" -> camera = camera.zoom(input.x)
                    "error" -> onFailure()
                }
            }, modifier = Modifier.weight(1f).fillMaxWidth())
            memoryId?.let { id -> memory?.notes?.get(id) }?.let { note ->
                Column(Modifier.weight(.65f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(8.dp)) {
                    NotePanel(note, onSelect = { id -> if (memory?.notes?.containsKey(id) == true) memoryId = id },
                        onClose = { memoryId = null })
                }
            }
        }
    }
}
