package com.hereliesaz.geministrator

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Buying breeds. Each purchase is one creature, so products are consumables: granted to the
 * stable, then consumed so they can be bought again. Google Play on Android for now; billing
 * through the Azphalt Store is to come for every platform as another implementation of this.
 * Platforms without one pass none, and the Store only shows prices.
 */
interface BreedPurchases {
    /** Breed id to its price in the keeper's currency; a breed missing here can't be bought. */
    suspend fun prices(breedIds: List<String>): Map<String, String>

    /** Opens the purchase for [breedId]; true once it was paid for and consumed. */
    suspend fun buy(breedId: String): Boolean

    /** Breeds paid for but not yet granted (the app closed mid-purchase); each is consumed as returned. */
    suspend fun unclaimed(): List<String>

    companion object {
        /** The Play product id of a breed: `breed_` and its id with dashes as underscores. */
        fun productId(breedId: String): String = "breed_" + breedId.replace('-', '_')

        fun breedId(productId: String): String? = productId.removePrefix("breed_").takeIf { it != productId }?.replace('_', '-')
    }
}

internal val LocalBreedPurchases = staticCompositionLocalOf<BreedPurchases?> { null }
