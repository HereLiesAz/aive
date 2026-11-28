package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.TaskRunStatus
import com.hereliesaz.geministrator.domain.TaskDefinitionId
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
import androidx.compose.runtime.rememberUpdatedState
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
        // The run's own records, drawn as light (SpatialSignals.kt, SpatialLights.kt): none of it is evidence of anything.
        val reports = LocalModelUsageReports.current
        val sporeUses = remember(run, reports) { memorySporeUses(run, reports) }
        val pulses = remember(run, reports) { transferPulses(run, reports) }
        val reducedMotion = AzphaltMotion.reduceMotion
        val beatRates = remember(run, frame, reducedMotion) {
            if (reducedMotion) emptyMap() else frame.bodies.mapNotNull { body ->
                pulseRateHz(run.taskRuns[TaskDefinitionId(body.id)])?.let { body.id to it }
            }.toMap()
        }
        // A sender that has finished still sends at the top rate it reached when its pulses were delivered.
        val pulseRates = remember(run, frame, reducedMotion) {
            if (reducedMotion) emptyMap() else frame.bodies.mapNotNull { body ->
                val task = run.taskRuns[TaskDefinitionId(body.id)]
                (pulseRateHz(task) ?: PULSE_MAX_HZ.takeIf { task?.status == TaskRunStatus.Completed })?.let { body.id to it }
            }.toMap()
        }
        val beatPhases = remember { mutableMapOf<String, Float>() }
        var clock by remember { mutableStateOf(currentEpochMillis()) }
        val currentBeatRates by rememberUpdatedState(beatRates)
        var camera by remember { mutableStateOf(SpatialCameraState()) }
        var memoryId by remember { mutableStateOf<String?>(null) }
        var colonyFocusId by remember { mutableStateOf<String?>(null) }
        var focusPosition by remember { mutableStateOf(SpatialPoint.Origin) }
        val focusGoal by rememberUpdatedState(
            if (camera.mode == SpatialCameraMode.Memory) {
                frame.spores.firstOrNull { it.id == memoryId }?.position ?: camera.target.gaze
            } else {
                frame.spores.firstOrNull { it.id == memoryId }?.position
                    ?: frame.bodies.firstOrNull { it.id == colonyFocusId }?.position ?: camera.target.gaze
            },
        )
        val reduced = AzphaltMotion.reduceMotion
        var motionPhase by remember { mutableStateOf(0f) }
        LaunchedEffect(selected) {
            colonyFocusId = selected
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
                    for ((id, rate) in currentBeatRates) beatPhases[id] = advanceBeat(beatPhases[id] ?: 0f, rate, elapsed, reduced).phase
                    clock = currentEpochMillis()
                    focusPosition = advanceSpatialFocus(focusPosition, focusGoal, elapsed, reduced)
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
            val lights = spatialLights(clock, scene, frame, sporeUses, pulses, beatRates, beatPhases.toMap(), pulseRates = pulseRates)
            SpatialHiveSurface(frame, camera.renderCamera(if (reduced) 0f else motionPhase, if (reduced) focusGoal else focusPosition), lights = lights, onInput = { input ->
                if (frame.accepts(input)) when (input.type) {
                    "agent" -> {
                        memoryId = null
                        colonyFocusId = input.id
                        frame.bodies.firstOrNull { it.id == input.id }?.let { camera = camera.focus(it.position, explicit = true) }
                        onAgent(input.id)
                    }
                    "memory" -> {
                        memoryId = input.id
                        frame.spores.firstOrNull { it.id == input.id }?.let { camera = camera.focus(it.position, explicit = true) }
                    }
                    "empty" -> { memoryId = null; colonyFocusId = null; camera = camera.clearFocus(); onEmpty() }
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
