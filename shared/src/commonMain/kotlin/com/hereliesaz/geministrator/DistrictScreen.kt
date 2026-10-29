package com.hereliesaz.geministrator

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import com.hereliesaz.conveyance.h2g2.H2g2WorkflowState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.domain.RoleDefinition
import com.hereliesaz.geministrator.domain.WorkflowDefinition
import com.hereliesaz.geministrator.domain.WorkflowRun
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.district_loading
import com.hereliesaz.geministrator.resources.district_load_failed
import com.hereliesaz.geministrator.resources.district_creatures
import com.hereliesaz.geministrator.resources.district_empty
import com.hereliesaz.geministrator.resources.district_import_agent
import com.hereliesaz.geministrator.resources.hive_new
import com.hereliesaz.geministrator.resources.hive_not_yet_run
import com.hereliesaz.geministrator.resources.lineage_adopt
import com.hereliesaz.geministrator.resources.lineage_adopted
import com.hereliesaz.geministrator.resources.lineage_lineage
import com.hereliesaz.geministrator.resources.district_needs_you
import com.hereliesaz.geministrator.resources.district_scars
import com.hereliesaz.geministrator.resources.district_state_gestating
import com.hereliesaz.geministrator.resources.district_state_hatched
import com.hereliesaz.geministrator.resources.district_state_idle
import com.hereliesaz.geministrator.resources.district_state_withered
import com.hereliesaz.geministrator.resources.district_your_swarm
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private val DistrictCore = Color(0xFF2A231E)
private val DistrictEdge = Color(0xFF141110)
private val Withered = Color(0xFF4A4139)
// The palette only (docs/BRANDING.md): a hatched hive's ring is Yellow; a withered one dims.
private val Hatched = Azphalt.Yellow

/**
 * The district (docs/Swarm-OS.md): every workflow you keep, as a neighbourhood of one dark tissue.
 * A neighbourhood's ring says how its latest run went, its egg how far that run has grown, and a
 * yellow halo marks a creature at the surface waiting on you. Tapping one dives into it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DistrictScreen(
    onLoadDefinitions: suspend () -> List<WorkflowDefinition>,
    /** Runs grouped by the project they ran in; the whole hive is laid out by project. */
    onLoadRuns: suspend () -> List<Pair<String, List<WorkflowRun>>>,
    onEnterNeighbourhood: (Neighbourhood) -> Unit,
    onImportAgent: () -> Unit,
    onNewHive: () -> Unit = {},
    roles: Collection<RoleDefinition> = emptyList(),
    onAdopt: (RoleDefinition) -> Unit = {},
    /** The hive's edge: what the Store offers (HiveEdge). */
    edge: @Composable () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var district by remember { mutableStateOf<List<Neighbourhood>?>(null) }
    var projectOfRun by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var loadFailure by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        // A store that cannot be read says so here rather than taking the whole screen down.
        runCatching {
            val byProject = onLoadRuns()
            projectOfRun = byProject.flatMap { (project, runs) -> runs.map { it.id.value to project } }.toMap()
            district = projectDistrict(onLoadDefinitions(), byProject.flatMap { it.second })
        }.onFailure { failure ->
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            loadFailure = failure.message ?: failure::class.simpleName
        }
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(32.dp))
            .background(Brush.radialGradient(listOf(DistrictCore, DistrictEdge)))
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AzphaltThemedSurface(Azphalt.Ink) {
            Text(stringResource(Res.string.district_your_swarm), style = AzphaltType.section, color = azphaltOnSurface())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AzphaltPill(label = stringResource(Res.string.hive_new), seed = "hive-new", onClick = onNewHive)
                // Drifters come from the Store: the cure for the clone trap.
                AzphaltPill(
                    label = stringResource(Res.string.district_import_agent),
                    seed = "district-import",
                    onClick = onImportAgent,
                )
            }
            loadFailure?.let { message ->
                Text(stringResource(Res.string.district_load_failed, message), style = AzphaltType.body, color = azphaltOnSurface())
            }
            val neighbourhoods = district ?: run {
                if (loadFailure == null) {
                    Text(stringResource(Res.string.district_loading), style = AzphaltType.body, color = azphaltOnSurface())
                }
                return@AzphaltThemedSurface
            }
            if (neighbourhoods.isEmpty()) {
                Text(stringResource(Res.string.district_empty), style = AzphaltType.body, color = azphaltOnSurface())
            }
            // Laid out by project: the project of each hive's latest run; hives that never ran come last.
            val notYetRun = stringResource(Res.string.hive_not_yet_run)
            neighbourhoods
                .groupBy { hood -> hood.latestRunId?.let(projectOfRun::get) ?: notYetRun }
                .entries
                .sortedBy { (project, _) -> project == notYetRun }
                .forEach { (project, hives) ->
                    Text(project.uppercase(), style = AzphaltType.eyebrow, color = azphaltOnSurface())
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        hives.forEach { neighbourhood ->
                            NeighbourhoodCell(neighbourhood, onClick = { onEnterNeighbourhood(neighbourhood) })
                        }
                    }
                }
            StableSection()
            edge()
            Lineage(roles, onAdopt)
        }
    }
}

@Composable
private fun NeighbourhoodCell(neighbourhood: Neighbourhood, onClick: () -> Unit) {
    val ring = when (neighbourhood.state) {
        NeighbourhoodState.Idle -> Withered
        NeighbourhoodState.Gestating -> Azphalt.Yellow
        NeighbourhoodState.Hatched -> Hatched
        NeighbourhoodState.Withered -> Color(0xFF7A6E5F)
    }
    val stateLabel = stringResource(
        when (neighbourhood.state) {
            NeighbourhoodState.Idle -> Res.string.district_state_idle
            NeighbourhoodState.Gestating -> Res.string.district_state_gestating
            NeighbourhoodState.Hatched -> Res.string.district_state_hatched
            NeighbourhoodState.Withered -> Res.string.district_state_withered
        },
    )
    val creatures = pluralStringResource(Res.plurals.district_creatures, neighbourhood.creatures, neighbourhood.creatures)
    val needsYou = if (neighbourhood.needsYou > 0) stringResource(Res.string.district_needs_you) else null
    val scars = if (neighbourhood.scars > 0) pluralStringResource(Res.plurals.district_scars, neighbourhood.scars, neighbourhood.scars) else null
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .size(width = 150.dp, height = 214.dp)
            .clip(RoundedCornerShape(24.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {
                contentDescription = listOfNotNull(neighbourhood.name, stateLabel, creatures, needsYou, scars).joinToString(", ")
            }
            .padding(8.dp),
    ) {
        Box(Modifier.size(120.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val c = Offset(size.width / 2f, size.height / 2f)
                val r = size.minDimension / 2f
                if (neighbourhood.needsYou > 0) {
                    drawCircle(Azphalt.Yellow.copy(alpha = 0.35f), radius = r, center = c)
                }
                drawCircle(ring.copy(alpha = 0.12f), radius = r * 0.86f, center = c)
                drawCircle(ring.copy(alpha = 0.7f), radius = r * 0.86f, center = c, style = Stroke(width = 3.dp.toPx()))
                if (neighbourhood.state != NeighbourhoodState.Idle) {
                    val t = neighbourhood.growth.coerceIn(0f, 1f)
                    val rx = (7f + 12f * t).dp.toPx()
                    val ry = (9f + 15f * t).dp.toPx()
                    val shell = if (neighbourhood.state == NeighbourhoodState.Withered) Withered else Azphalt.Yellow
                    drawOval(shell, topLeft = Offset(c.x - rx, c.y + r * 0.55f - ry), size = Size(rx * 2f, ry * 2f))
                }
            }
            // A few of its creatures, at rest, so a hive reads as its swarm and not an empty ring.
            val spots = listOf(-24 to -20, 24 to -24, 0 to 22)
            neighbourhood.creatureRoles.forEachIndexed { i, role ->
                val (dx, dy) = spots[i]
                NodeMascotSurface(
                    roleLabel = role,
                    hueSeed = neighbourhood.definitionId + i,
                    state = H2g2WorkflowState.Pending,
                    motionPhase = { 0f },
                    modifier = Modifier.size(50.dp).offset(dx.dp, dy.dp),
                )
            }
        }
        Text(
            neighbourhood.name.uppercase(),
            style = AzphaltType.endCap,
            color = azphaltOnSurface(),
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
        Text(
            listOfNotNull(stateLabel, needsYou ?: scars).joinToString(" · "),
            style = AzphaltType.endCap,
            color = azphaltOnSurface(),
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

/**
 * The lineage (docs/Node-Creature-Genome.md): every config hatched from a run, with its parents.
 * A child is born unadopted; adopting it puts it in the active swarm.
 */
@Composable
private fun Lineage(roles: Collection<RoleDefinition>, onAdopt: (RoleDefinition) -> Unit) {
    val hatched = roles.filter { it.parents.isNotEmpty() }
    if (hatched.isEmpty()) return
    val names = roles.associate { it.id to it.name }
    Text(stringResource(Res.string.lineage_lineage), style = AzphaltType.section, color = azphaltOnSurface())
    hatched.forEach { child ->
        AzphaltPill(
            label = child.name,
            seed = "lineage-${child.id.value}",
            endCap = if (child.enabled) stringResource(Res.string.lineage_adopted) else stringResource(Res.string.lineage_adopt),
            onClick = { if (!child.enabled) onAdopt(child) },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            child.parents.joinToString(" × ") { names[it] ?: it.value },
            style = AzphaltType.endCap,
            color = azphaltOnSurface(),
        )
    }
}
