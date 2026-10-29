package com.hereliesaz.geministrator

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Buying breeds. Each purchase is one creature, so products are consumables: granted to the
 * stable first (saved at once), then consumed so they can be bought again. A purchase is never
 * consumed before it is granted, so a crash or a failed grant leaves it to [unclaimed]. Google Play on Android for now; billing
 * through the Azphalt Store is to come for every platform as another implementation of this.
 * Platforms without one pass none, and the Store only shows prices.
 */
interface BreedPurchases {
    /** Breed id to its price in the keeper's currency; a breed missing here can't be bought. */
    suspend fun prices(breedIds: List<String>): Map<String, String>

    /** Opens the purchase for [breedId]; the purchases it paid for, not yet consumed (empty if cancelled). */
    suspend fun buy(breedId: String): List<BreedPurchase>

    /** Purchases paid for but not yet consumed (the app closed mid-purchase, or a grant failed). */
    suspend fun unclaimed(): List<BreedPurchase>

    /** Consumes [purchase] once it is granted, so its breed can be bought again; true once confirmed. */
    suspend fun consume(purchase: BreedPurchase): Boolean

    companion object {
        /** The Play product id of a breed: `breed_` and its id with dashes as underscores. */
        fun productId(breedId: String): String = "breed_" + breedId.replace('-', '_')

        fun breedId(productId: String): String? = productId.removePrefix("breed_").takeIf { it != productId }?.replace('_', '-')
    }
}

/** One paid purchase: the store's [token] for it, and the breeds it buys. */
data class BreedPurchase(val token: String, val breedIds: List<String>)

internal val LocalBreedPurchases = staticCompositionLocalOf<BreedPurchases?> { null }
