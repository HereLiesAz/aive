package com.hereliesaz.geministrator

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Paid breeds are granted before they are consumed, and never consumed when they can't be granted. */
class StableHostClaimTest {
    private class Shop(private val failOn: Set<String> = emptySet()) : BreedPurchases {
        val consumed = mutableListOf<String>()
        override suspend fun prices(breedIds: List<String>) = emptyMap<String, String>()
        override suspend fun buy(breedId: String) = emptyList<BreedPurchase>()
        override suspend fun unclaimed() = emptyList<BreedPurchase>()
        override suspend fun consume(purchase: BreedPurchase): Boolean {
            if (purchase.token in failOn) error("billing unavailable")
            consumed += purchase.token
            return true
        }
    }

    private fun host() = StableHost(DurableUiStateStore(rootKey = "aive.stable.claim.${System.nanoTime()}"))

    @Test
    fun nothingIsConsumedBeforeTheCatalogLoads() = runBlocking {
        val shop = Shop()
        val host = host()
        assertEquals(emptyList(), host.claim(shop, listOf(BreedPurchase("t1", listOf("architect")))))
        assertEquals(emptyList(), shop.consumed)
        assertEquals(null, host.stable.owned["architect"])
    }

    @Test
    fun aFailedConsumeKeepsEarlierGrantsAndIsNotGrantedTwice() = runBlocking {
        val shop = Shop(failOn = setOf("t2"))
        val host = host().also { assertTrue(it.load()) }
        val paid = listOf(
            BreedPurchase("t1", listOf("architect")),
            BreedPurchase("t2", listOf("monitoring-sentinel")),
            BreedPurchase("t3", listOf("no-such-breed")),
        )
        assertEquals(listOf("architect", "monitoring-sentinel"), host.claim(shop, paid))
        assertEquals(1, host.stable.owned["architect"])
        assertEquals(1, host.stable.owned["monitoring-sentinel"])
        // t2 was granted but not consumed; t3 names an unknown breed, so it is neither.
        assertEquals(listOf("t1"), shop.consumed)
        assertEquals(listOf("t2"), host.stable.granted)

        // Next launch: Play still reports t2 (and t3). t2 is consumed without a second creature.
        val retry = Shop()
        assertEquals(emptyList(), host.claim(retry, paid.drop(1)))
        assertEquals(1, host.stable.owned["monitoring-sentinel"])
        assertEquals(listOf("t2"), retry.consumed)
        assertEquals(emptyList(), host.stable.granted)
    }
}
