package com.hereliesaz.geministrator

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.azphalt.AzphaltPackageSummary
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.hive_edge
import com.hereliesaz.geministrator.resources.hive_edge_bring_in
import com.hereliesaz.geministrator.resources.hive_edge_drifters
import com.hereliesaz.geministrator.resources.hive_edge_hives
import org.jetbrains.compose.resources.stringResource

/**
 * The hive's edge (mockup H5): what the Store offers, without leaving the hive. Drifters are
 * agents ("role" packages), hives are workflows. Bring in opens that package's install, where its
 * permissions are approved.
 */
@Composable
internal fun HiveEdge(
    onLoadPackages: suspend () -> List<AzphaltPackageSummary>,
    onBringIn: (AzphaltPackageSummary) -> Unit,
    modifier: Modifier = Modifier,
    limit: Int = 3,
) {
    var packages by remember { mutableStateOf<List<AzphaltPackageSummary>>(emptyList()) }
    var drifters by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { packages = runCatching { onLoadPackages() }.getOrDefault(emptyList()) }
    if (packages.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.hive_edge), style = AzphaltType.section, color = azphaltOnSurface())
        // One pill: what's showing, with the other side on its cap. (A selected pill vanishes on Ink.)
        val drifterLabel = stringResource(Res.string.hive_edge_drifters)
        val hiveLabel = stringResource(Res.string.hive_edge_hives)
        AzphaltPill(
            label = if (drifters) drifterLabel else hiveLabel,
            seed = "edge-toggle",
            endCap = if (drifters) hiveLabel else drifterLabel,
            role = Role.Switch,
            onClick = { drifters = !drifters },
        )
        packages.filter { it.kind == if (drifters) "role" else "workflow" }.take(limit).forEach { item ->
            AzphaltRecord(
                seed = "edge-${item.id}",
                eyebrow = item.author ?: item.kind,
                title = item.name,
                body = item.description,
                endCap = stringResource(Res.string.hive_edge_bring_in),
                onClick = { onBringIn(item) },
            )
        }
    }
}
