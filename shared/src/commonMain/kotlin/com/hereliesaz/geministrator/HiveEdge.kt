package com.hereliesaz.geministrator

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import com.hereliesaz.geministrator.a2a.A2aDrifters
import com.hereliesaz.geministrator.azphalt.AzphaltPackageSummary
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.hive_edge
import com.hereliesaz.geministrator.resources.hive_edge_bring_in
import com.hereliesaz.geministrator.resources.hive_edge_close
import com.hereliesaz.geministrator.resources.hive_edge_drifters
import com.hereliesaz.geministrator.resources.hive_edge_empty
import com.hereliesaz.geministrator.resources.hive_edge_hives
import org.jetbrains.compose.resources.stringResource

/**
 * The hive's edge (mockup H5): what the Store offers, without leaving the hive. Two tabs: drifters
 * are agents (remote A2A agents read from their Agent Card, [a2aDrifters], then the Store's "role"
 * packages); hives are the Store's workflows. Bring in opens that package's install, where its
 * permissions are approved. Breeds are sold in the Store itself (mockup B5).
 */
@Composable
internal fun HiveEdge(
    onLoadPackages: suspend () -> List<AzphaltPackageSummary>,
    onBringIn: (AzphaltPackageSummary) -> Unit,
    modifier: Modifier = Modifier,
    limit: Int = 3,
    shelfAtStart: EdgeShelf = EdgeShelf.Drifters,
    /** The keeper is bringing a drifter in: the Drifters shelf shows. */
    bringingIn: Boolean = false,
    /** Remote A2A agents at the edge (A2aDrifterShelf), shown on the Drifters shelf before the Store's. */
    a2aDrifters: (@Composable () -> Unit)? = null,
) {
    var packages by remember { mutableStateOf<List<AzphaltPackageSummary>>(emptyList()) }
    var shelf by remember { mutableStateOf(shelfAtStart) }
    LaunchedEffect(Unit) { packages = runCatching { onLoadPackages() }.getOrDefault(emptyList()) }
    LaunchedEffect(bringingIn) { if (bringingIn) shelf = EdgeShelf.Drifters }
    val labels = mapOf(
        EdgeShelf.Drifters to stringResource(Res.string.hive_edge_drifters),
        EdgeShelf.Hives to stringResource(Res.string.hive_edge_hives),
    )
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(Res.string.hive_edge), style = EdgeHeading, color = azphaltOnSurface())
        AzphaltTabs(
            tabs = EdgeShelf.entries.map { AzphaltTabItem(it.name, labels.getValue(it)) },
            selectedKey = shelf.name,
            onSelected = { key -> shelf = EdgeShelf.valueOf(key) },
            seedPrefix = "edge-shelf",
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (shelf == EdgeShelf.Drifters) a2aDrifters?.invoke()
            val offered = packages.filter { it.kind == shelf.packageKind }.take(limit)
            offered.forEach { item ->
                CreatureOfferRow(
                    rig = edgeCreatureFor("store:${item.id}"),
                    title = item.name,
                    // What it does, in its own words; the edge computes nothing more about it.
                    details = {
                        (item.description?.takeIf(String::isNotBlank) ?: item.author ?: item.kind).let { line ->
                            Text(line, style = EdgeLine, color = azphaltMutedOnSurface(), maxLines = 2)
                        }
                    },
                    action = {
                        AzphaltPill(
                            label = stringResource(Res.string.hive_edge_bring_in),
                            seed = "edge-${item.id}",
                            tone = PillTone.Primary,
                            onClick = { onBringIn(item) },
                        )
                    },
                )
            }
            if (offered.isEmpty() && (shelf != EdgeShelf.Drifters || a2aDrifters == null)) {
                Text(stringResource(Res.string.hive_edge_empty), style = AzphaltType.body, color = azphaltMutedOnSurface())
            }
        }
    }
}

internal enum class EdgeShelf(val packageKind: String) { Drifters("role"), Hives("workflow") }

/** The creature a thing at the edge is drawn as: the same one every time for the same [key]. */
internal fun edgeCreatureFor(key: String): String = A2aDrifters.creatureFor(key)

/** The edge's heading: bold, a step under a section title (mockup H5). */
internal val EdgeHeading = AzphaltType.lead.copy(fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)

/** A row's name, bold. */
internal val EdgeTitle = AzphaltType.body.copy(fontWeight = FontWeight.Bold)

/** A row's line under its name. */
internal val EdgeLine = AzphaltType.body.copy(fontSize = 13.sp, lineHeight = 17.sp, fontWeight = FontWeight.Normal)

/**
 * One thing offered at the edge or in the Store (mockups H5, B5): its creature, its name, a line
 * or two under it, and its one action on the right. [below] holds any further actions, under the
 * text. The row is lifted Ink, so its pills fill Yellow.
 */
@Composable
internal fun CreatureOfferRow(
    rig: String,
    title: String,
    details: @Composable () -> Unit = {},
    action: (@Composable () -> Unit)? = null,
    below: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    creatureSize: Int = 56,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(AzphaltLiftedInk)
            .padding(8.dp),
    ) {
        AzphaltSurface(AzphaltLiftedInk) {
            StableCreature(rig, creatureSize)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = EdgeTitle, color = azphaltOnSurface())
                details()
                below?.let {
                    Box(Modifier.padding(top = 6.dp)) { it() }
                }
            }
            action?.invoke()
        }
    }
}

/**
 * The edge as a sheet from the bottom (mockup H5), over the hive dimmed behind it. Tapping the dim
 * closes it; screen readers find that as a Close button.
 */
@Composable
internal fun HiveEdgeSheet(onClose: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val closeLabel = stringResource(Res.string.hive_edge_close)
    val title = stringResource(Res.string.hive_edge)
    BoxWithConstraints(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Azphalt.Ink.copy(alpha = 0.55f))
                .semantics { contentDescription = closeLabel }
                .clickable(role = Role.Button, onClickLabel = closeLabel, onClick = onClose),
        )
        val height = (maxHeight * 0.56f).coerceAtLeast(min(maxHeight, 380.dp))
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(height)
                .clip(RoundedCornerShape(topStart = 34.dp, topEnd = 34.dp))
                .background(Azphalt.Ink)
                .semantics {
                    paneTitle = title
                    isTraversalGroup = true
                },
        ) {
            AzphaltThemedSurface(Azphalt.Ink) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 18.dp),
                ) { content() }
            }
        }
    }
}
