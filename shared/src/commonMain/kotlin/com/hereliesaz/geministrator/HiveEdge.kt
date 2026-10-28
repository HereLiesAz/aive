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
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
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
import com.hereliesaz.geministrator.resources.hive_edge_breeds
import com.hereliesaz.geministrator.resources.hive_edge_only_here
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
    shelfAtStart: EdgeShelf = EdgeShelf.Drifters,
) {
    var packages by remember { mutableStateOf<List<AzphaltPackageSummary>>(emptyList()) }
    var shelf by remember { mutableStateOf(shelfAtStart) }
    LaunchedEffect(Unit) { packages = runCatching { onLoadPackages() }.getOrDefault(emptyList()) }
    val host = LocalStableHost.current
    val purchases = LocalBreedPurchases.current
    val breeds = host?.catalog?.breeds.orEmpty().filter { it.sold }.sortedBy { it.price }
    if (packages.isEmpty() && breeds.isEmpty()) return
    val scope = rememberCoroutineScope()
    var prices by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(purchases, breeds.size) {
        prices = runCatching { purchases?.prices(breeds.map { it.id }) }.getOrNull().orEmpty()
    }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(Res.string.hive_edge), style = AzphaltType.section, color = azphaltOnSurface())
        // One pill: what's showing, with the next shelf on its cap. (A selected pill vanishes on Ink.)
        val labels = mapOf(
            EdgeShelf.Drifters to stringResource(Res.string.hive_edge_drifters),
            EdgeShelf.Hives to stringResource(Res.string.hive_edge_hives),
            EdgeShelf.Breeds to stringResource(Res.string.hive_edge_breeds),
        )
        val next = EdgeShelf.entries[(shelf.ordinal + 1) % EdgeShelf.entries.size]
        AzphaltPill(
            label = labels.getValue(shelf),
            seed = "edge-toggle",
            endCap = labels.getValue(next),
            role = Role.Switch,
            onClick = { shelf = next },
        )
        when (shelf) {
            EdgeShelf.Breeds -> breeds.take(limit * 2).forEach { breed ->
                // Only the name, rarity and price: what a breed is like is learned by keeping it.
                val price = prices[breed.id] ?: "$" + breed.price.toString().let { if (it.substringAfter('.').length < 2) it + "0" else it }
                val buy: (() -> Unit)? = if (purchases != null && breed.id in prices && host != null) {
                    { scope.launch { if (runCatching { purchases.buy(breed.id) }.getOrDefault(false)) host.bought(breed.id) } }
                } else {
                    null
                }
                BreedRow(
                    rig = breed.id,
                    title = breed.name,
                    caption = if (breed.bred) breed.rarity else stringResource(Res.string.hive_edge_only_here, breed.rarity),
                    price = price,
                    onBuy = buy,
                )
            }
            else -> packages.filter { it.kind == if (shelf == EdgeShelf.Drifters) "role" else "workflow" }.take(limit).forEach { item ->
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
}

internal enum class EdgeShelf { Drifters, Hives, Breeds }
