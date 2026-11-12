package com.hereliesaz.geministrator.a2a

import com.russhwolf.settings.MapSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

class AgentDirectoryTest {
    @BeforeTest
    fun isolate() {
        A2aAgentConnections.useSettings(MapSettings())
        A2aDrifterSeeds.useSettings(MapSettings())
    }

    @AfterTest
    fun reset() = isolate()

    private val semanticResponse = """
        {
          "schema": 1, "source": "searcha2a", "match_mode": "semantic",
          "results": [
            {
              "name": "Meteorology Agent",
              "description": "Rain and precipitation forecasts",
              "source_url": "https://meteo.example.org/.well-known/agent-card.json",
              "skills": [{"name": "Atmospheric science"}],
              "kind": "a2a-agent", "installable": false, "verification": "card_read_only",
              "match_score": 0.92
            },
            {
              "name": "Private Probe",
              "source_url": "https://127.0.0.1/admin",
              "kind": "a2a-agent", "installable": false, "verification": "card_read_only",
              "match_score": 0.95
            },
            {
              "name": "Fake Package",
              "source_url": "https://fake.example.org/card",
              "kind": "a2a-agent", "installable": true, "verification": "card_read_only",
              "match_score": 0.99
            }
          ]
        }
    """.trimIndent()

    private val publishedCatalog = """
        {
          "schema": 1, "source": "searcha2a", "generated_at": "2026-11-12T11:12:20Z",
          "listings": [
            {
              "name": "Weather Agent",
              "description": "Weather forecasts",
              "source_url": "https://weather.example.org/.well-known/agent-card.json",
              "skills": [{"name": "Precipitation"}],
              "kind": "a2a-agent", "installable": false, "verification": "card_read_only"
            },
            {
              "name": "Finance Agent",
              "description": "Stock market",
              "source_url": "https://finance.example.org/.well-known/agent-card.json",
              "skills": [{"name": "Economics"}],
              "kind": "a2a-agent", "installable": false, "verification": "card_read_only"
            }
          ]
        }
    """.trimIndent()

    @Test
    fun searchA2aWorkerProvidesSemanticMatchesWithoutContactingAgentCards() = runTest {
        val requested = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            requested += request.url.toString()
            respond(semanticResponse, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val result = SearchA2aDirectorySource(client).search("sky outlook")
            assertEquals(1, requested.size)
            val requestUrl = Url(requested.single())
            assertEquals("searcha2a-api.hereliesaz.workers.dev", requestUrl.host)
            assertEquals("/api/v1/directory", requestUrl.encodedPath)
            assertEquals("sky outlook", requestUrl.parameters["q"])
            assertEquals(1, result.size)
            val lead = result.single()
            assertEquals("https://meteo.example.org/.well-known/agent-card.json", lead.cardUrl)
            assertEquals(listOf("Atmospheric science"), lead.skills)
            assertEquals(0.92, lead.matchScore)
            assertEquals("SearchA2A · semantic", lead.source)
            assertFalse(lead.registryReportsHealthy)
            assertFalse(lead.registryReportsTaskTested)
            assertNull(lead.connectedProviderId, "a directory result is never an approved provider")
            assertTrue(A2aAgentConnections.all().isEmpty())
        } finally {
            client.close()
        }
    }

    @Test
    fun inaccessibleSemanticGatewayFallsBackToPublishedSnapshotWithoutAnotherRegistry() = runTest {
        val requested = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            requested += request.url.toString()
            if (request.url.host == "searcha2a-api.hereliesaz.workers.dev") {
                respond("Unavailable", HttpStatusCode.ServiceUnavailable)
            } else {
                respond(publishedCatalog, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        })
        try {
            val result = SearchA2aDirectorySource(client).search("weather")
            assertEquals(listOf("Weather Agent"), result.map { it.name })
            assertEquals("SearchA2A · cached catalog", result.single().source)
            assertNull(result.single().matchScore, "cached metadata never pretends to be semantic ranking")
            assertEquals(2, requested.size)
            assertEquals("raw.githubusercontent.com", Url(requested[1]).host)
            assertEquals("/HereLiesAz/searcha2a/agent-feed/site/api/v1/azphalt-listings.json",
                Url(requested[1]).encodedPath)
            assertTrue(A2aAgentConnections.all().isEmpty())
        } finally {
            client.close()
        }
    }

    @Test
    fun catalogFallbackSearchesBeyondTheFirstSeventyUnfilteredEntries() = runTest {
        val unrelated = (1..70).joinToString(",") { index ->
            """{"name":"Unrelated $index","description":"No relevant skills",
                "source_url":"https://unrelated$index.example.org/card",
                "skills":[{"name":"General"}],
                "kind":"a2a-agent","installable":false,"verification":"card_read_only"}""".trimIndent()
        }
        val catalog = """{"schema":1,"source":"searcha2a","listings":[
            $unrelated,
            {"name":"Weather Agent","description":"Weather forecasts",
             "source_url":"https://weather.example.org/card","skills":[{"name":"Precipitation"}],
             "kind":"a2a-agent","installable":false,"verification":"card_read_only"}
        ]}""".trimIndent()
        val client = HttpClient(MockEngine { request ->
            if (request.url.host == "searcha2a-api.hereliesaz.workers.dev") {
                respond("API unavailable", HttpStatusCode.ServiceUnavailable)
            } else {
                respond(catalog, HttpStatusCode.OK)
            }
        })
        try {
            val matches = SearchA2aDirectorySource(client).search("weather")
            assertEquals(listOf("Weather Agent"), matches.map { it.name })
            assertEquals("SearchA2A · cached catalog", matches.single().source)
        } finally {
            client.close()
        }
    }

    @Test
    fun rankedSearchIsNotReducedToNameOrKeywordMatching() = runTest {
        val names = mutableListOf<String>()
        val client = HttpClient(MockEngine { call ->
            names += call.url.host
            respond(semanticResponse, HttpStatusCode.OK)
        })
        try {
            val result = AgentDirectory(listOf(SearchA2aDirectorySource(client))).search("sky outlook")
            assertEquals(listOf("Meteorology Agent"), result.entries.map { it.name })
            assertTrue(result.unavailableSources.isEmpty())
            assertEquals(listOf("searcha2a-api.hereliesaz.workers.dev"), names)
        } finally {
            client.close()
        }
    }

    @Test
    fun invalidPublicRegistryUrlsAreDroppedBeforeAnyCardCouldBeRead() {
        assertEquals("https://safe.example.org/.well-known/agent-card.json", directoryPublicCardUrl("https://safe.example.org"))
        val rejected = listOf(
            "http://public.example.org/.well-known/agent-card.json",
            "https://localhost/.well-known/agent-card.json",
            "https://127.0.0.1/card",
            "https://[::1]/card",
            "https://machine.local/card",
            "https://user:password@agent.example.org/card",
            "https://agent.example.org/?redirect=http://localhost",
            "https://agent.example.org/#fragment",
            "https://agent.example.org:abc/card",
            "https://agent.example.org:99999/card",
            "https://agent.example.org/white%20space",
        )
        rejected.forEach { assertNull(directoryPublicCardUrl(it), it) }
    }

    @Test
    fun federatedResultsDeduplicateAndKeepExplicitLocalApproval() = runTest {
        val url = "https://data.example.org/.well-known/agent-card.json"
        val connected = A2aAgentConnections.add(url)
        val local = object : AgentDirectorySource {
            override val name = "Local"
            override suspend fun search(query: String) = listOf(
                AgentDirectoryEntry(url, "Economic Indicators", "Connected", emptyList(), name, connectedProviderId = connected.providerId),
            )
        }
        val registry = object : AgentDirectorySource {
            override val name = "Registry"
            override suspend fun search(query: String) = listOf(
                AgentDirectoryEntry(url, "Economic Indicators", "Country GDP research", listOf("GDP"), name),
                AgentDirectoryEntry("https://news.example.net/card", "News Research", "Current events", listOf("news"), name),
            )
        }
        val result = AgentDirectory(listOf(local, registry)).search("economic")
        assertEquals(listOf("Economic Indicators", "News Research"), result.entries.map { it.name })
        assertEquals(connected.providerId, result.entries.first().connectedProviderId)
        assertEquals(listOf("GDP"), result.entries.first().skills)
        assertTrue(result.unavailableSources.isEmpty())
        assertEquals(1, A2aAgentConnections.all().size, "directory search is read-only")
    }

    @Test
    fun registrySkillMatchStillShowsAlreadyConnectedWhenLocalNameDoesNotMatch() = runTest {
        val url = "https://economic.example.org/.well-known/agent-card.json"
        val connected = A2aAgentConnections.add(url)
        val directory = AgentDirectory(
            listOf(
                LocalAgentDirectorySource,
                object : AgentDirectorySource {
                    override val name = "Registry"
                    override suspend fun search(query: String) = listOf(
                        AgentDirectoryEntry(
                            url, "Economic Indicators", "Economic history",
                            listOf("GDP forecasting"), name,
                        ),
                    )
                },
            ),
        )

        val results = directory.search("GDP")
        assertEquals(1, results.entries.size)
        assertEquals(connected.providerId, results.entries.single().connectedProviderId)
        assertEquals("Registry", results.entries.single().source)
        assertEquals(listOf("GDP forecasting"), results.entries.single().skills)
    }

    @Test
    fun failingRegistryDoesNotHideLocalMatchesOrLeakRemoteErrors() = runTest {
        val good = object : AgentDirectorySource {
            override val name = "Local"
            override suspend fun search(query: String) = listOf(
                AgentDirectoryEntry("https://news.example.net/card", "News", "News research", emptyList(), name),
            )
        }
        val broken = object : AgentDirectorySource {
            override val name = "Offline registry"
            override suspend fun search(query: String): List<AgentDirectoryEntry> = error("secret network error")
        }
        val result = AgentDirectory(listOf(good, broken)).search("news")
        assertEquals(listOf("News"), result.entries.map { it.name })
        assertEquals(listOf("Offline registry"), result.unavailableSources)
        assertTrue(AgentDirectory(listOf(good)).search("").entries.isEmpty())
    }

    @Test
    fun cancellationIsNotSwallowedAsARegistryFailure() = runTest {
        val cancelled = object : AgentDirectorySource {
            override val name = "Cancelled"
            override suspend fun search(query: String): List<AgentDirectoryEntry> = throw CancellationException("cancelled")
        }
        assertFailsWith<CancellationException> {
            AgentDirectory(listOf(cancelled)).search("news")
        }
    }

    @Test
    fun localDirectoryOffersOnlyUndismissedAndUnconnectedSuggestions() = runTest {
        val seed = A2aDrifterSeeds.SEEDS.first()
        val first = LocalAgentDirectorySource.search("world")
        assertTrue(first.any { it.cardUrl == seed.cardUrl })
        A2aDrifterSeeds.dismiss(seed.cardUrl)
        assertFalse(LocalAgentDirectorySource.search("world").any { it.cardUrl == seed.cardUrl })
        val connected = A2aAgentConnections.add(seed.cardUrl)
        A2aAgentConnections.rename(connected.providerId, seed.name)
        assertTrue(LocalAgentDirectorySource.search("world").any { it.connectedProviderId == connected.providerId })
    }
}
