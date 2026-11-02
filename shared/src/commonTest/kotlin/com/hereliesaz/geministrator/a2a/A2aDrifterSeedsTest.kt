package com.hereliesaz.geministrator.a2a

import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class A2aDrifterSeedsTest {
    @BeforeTest
    fun isolate() {
        A2aAgentConnections.useSettings(MapSettings())
        A2aDrifterSeeds.useSettings(MapSettings())
        A2aDrifters.clear()
    }

    @AfterTest
    fun reset() = isolate()

    @Test
    fun everySeedIsANormalizedHttpsCardWithItsVerificationWrittenDown() {
        assertEquals(A2aDrifterSeeds.SEEDS.size, A2aDrifterSeeds.SEEDS.map { it.cardUrl }.toSet().size, "no seed twice")
        A2aDrifterSeeds.SEEDS.forEach { seed ->
            assertTrue(seed.cardUrl.startsWith("https://"), seed.cardUrl)
            assertEquals(seed.cardUrl, A2aAgentConnections.normalizeCardUrl(seed.cardUrl))
            assertTrue(seed.name.isNotBlank() && seed.about.isNotBlank() && seed.runBy.isNotBlank())
            assertTrue(seed.verified.isNotBlank(), "say how ${seed.name} was checked")
        }
    }

    @Test
    fun seedsWaitUnreadAtTheEdgeWithoutFetchingAnything() {
        val seeds = listOf(A2aDrifterSeed("https://seed.example/.well-known/agent-card.json", "Seed", "Does seed things.", "Seeders", "test"))
        A2aDrifters.offerSeeds(seeds)
        val drifter = A2aDrifters.waiting.value.single()
        assertTrue(drifter.seeded)
        assertTrue(drifter.isUnreadSeed)
        assertFalse(drifter.canBeLetIn, "its card is read only when the keeper lets it in")
        assertNull(drifter.card)
        assertEquals("Seed", drifter.name)
        assertEquals("Does seed things.", drifter.blurb)

        // Offering again does not double it.
        A2aDrifters.offerSeeds(seeds)
        assertEquals(1, A2aDrifters.waiting.value.size)
    }

    @Test
    fun aDismissedSeedStaysAwayOnThisDevice() {
        val settings = MapSettings()
        A2aDrifterSeeds.useSettings(settings)
        val seed = A2aDrifterSeeds.SEEDS.first()
        assertTrue(seed in A2aDrifterSeeds.pending())

        A2aDrifterSeeds.dismiss(seed.cardUrl)
        assertFalse(seed in A2aDrifterSeeds.pending())
        A2aDrifterSeeds.useSettings(settings)
        assertFalse(seed in A2aDrifterSeeds.pending(), "remembered after a restart")

        A2aDrifterSeeds.dismiss("https://not-a-seed.example/.well-known/agent-card.json")
        assertEquals(setOf(seed.cardUrl), A2aDrifterSeeds.dismissed.value)
    }

    @Test
    fun aSeedAlreadyLetInIsNotOfferedAgain() {
        val seed = A2aDrifterSeeds.SEEDS.first()
        A2aAgentConnections.add(seed.cardUrl)
        assertFalse(seed in A2aDrifterSeeds.pending())
    }

    @Test
    fun lettingASeedInReadsItsCardAndKeepsItsSeedName() = runTest {
        val seed = A2aDrifterSeeds.SEEDS.first()
        A2aDrifters.offerSeeds(listOf(seed))
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.ServiceUnavailable) })
        val read = A2aDrifters.bringIn(seed.cardUrl, client, seeded = true)
        // The failure lands on the seed, which keeps its name.
        assertEquals(DrifterTrouble.Unreachable, read.trouble)
        assertEquals(seed.name, A2aDrifters.waiting.value.single().name)
        assertFalse(A2aDrifters.waiting.value.single().isUnreadSeed)
    }
}
