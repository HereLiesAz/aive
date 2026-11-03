package com.hereliesaz.geministrator

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.hereliesaz.geministrator.resources.Res
import com.hereliesaz.geministrator.resources.store_breed_only_here
import com.hereliesaz.geministrator.resources.store_breeds_footnote
import com.hereliesaz.geministrator.resources.store_breeds_none
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** The breeds the Store sells, rarest (dearest) first. */
internal fun breedsForSale(catalog: BreedCatalog): List<Breed> =
    catalog.breeds.filter(Breed::sold).sortedByDescending(Breed::price)

/** A breed's list price as the catalog states it, in dollars with two decimals ("$2.00"). */
internal fun breedListPrice(price: Double): String {
    val cents = kotlin.math.round(price * 100).toLong()
    return "$" + (cents / 100).toString() + "." + (cents % 100).toString().padStart(2, '0')
}

/**
 * Breeds in the Store (mockup B5): one creature each, priced by rarity. Only the name, the rarity
 * (with "only here" for a breed that cannot be hatched) and the price show. Buying goes through
 * [LocalBreedPurchases] (Google Play on Android) and lands in the stable; a platform without one
 * shows the catalog price on a button that does nothing.
 */
@Composable
internal fun StoreBreeds(modifier: Modifier = Modifier) {
    val host = LocalStableHost.current
    val purchases = LocalBreedPurchases.current
    val breeds = host?.catalog?.let(::breedsForSale).orEmpty()
    val scope = rememberCoroutineScope()
    var prices by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(purchases, breeds.size) {
        prices = runCatching { purchases?.prices(breeds.map { it.id }) }.getOrNull().orEmpty()
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StorePanel {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (breeds.isEmpty()) {
                    Text(stringResource(Res.string.store_breeds_none), style = AzphaltType.body, color = azphaltOnSurface())
                }
                breeds.forEach { breed ->
                    val buy: (() -> Unit)? = if (purchases != null && breed.id in prices && host != null) {
                        { scope.launch { host.claim(purchases, runCatching { purchases.buy(breed.id) }.getOrDefault(emptyList())) } }
                    } else {
                        null
                    }
                    BreedRow(
                        rig = breed.id,
                        title = breed.name,
                        caption = if (breed.bred) breed.rarity else stringResource(Res.string.store_breed_only_here, breed.rarity),
                        price = prices[breed.id] ?: breedListPrice(breed.price),
                        onBuy = buy,
                    )
                }
            }
        }
        StorePanel {
            Text(stringResource(Res.string.store_breeds_footnote), style = EdgeLine, color = azphaltMutedOnSurface())
        }
    }
}

/** An Ink panel on the page ground (mockup B5). */
@Composable
private fun StorePanel(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(Azphalt.Ink)
            .padding(16.dp),
    ) {
        AzphaltThemedSurface(Azphalt.Ink) { content() }
    }
}
