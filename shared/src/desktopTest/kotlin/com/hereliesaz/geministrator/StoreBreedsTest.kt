package com.hereliesaz.geministrator

import kotlin.test.Test
import kotlin.test.assertEquals

class StoreBreedsTest {
    private fun breed(id: String, source: String, price: Double) =
        Breed(id = id, name = id, genus = "g", rarity = "Common", source = source, price = price)

    @Test
    fun onlySoldBreedsShowDearestFirst() {
        val catalog = BreedCatalog(
            listOf(
                breed("cheap", "both", 0.99),
                breed("hatched-only", "breed", 0.0),
                breed("rare", "store", 9.99),
                breed("mid", "store", 2.99),
            ),
        )
        assertEquals(listOf("rare", "mid", "cheap"), breedsForSale(catalog).map(Breed::id))
    }

    @Test
    fun listPriceHasTwoDecimals() {
        assertEquals("$9.99", breedListPrice(9.99))
        assertEquals("$2.00", breedListPrice(2.0))
        assertEquals("$0.50", breedListPrice(0.5))
        assertEquals("$0.00", breedListPrice(0.0))
    }

}
