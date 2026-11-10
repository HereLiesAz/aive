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

    private val registryResponse = """
        {
          "total": 3, "limit": 20, "offset": 0,
          "agents": [
            {
              "name": "Economic Indicators",
              "description": "Find GDP and employment rates for countries",
              "wellKnownURI": "https://data.example.org/.well-known/agent-card.json",
              "skills": [{"id":"gdp", "name":"Economic research", "tags":["economics"]}],
              "is_healthy": true,
              "task_conformance": {"passed": true}
            },
            {
              "name": "Private Probe",
              "wellKnownURI": "https://127.0.0.1/admin",
              "is_healthy": true
            },
            {
              "name": "Credential Trap",
              "wellKnownURI": "https://user:secret@agent.example.com/card",
              "is_healthy": true
            }
          ]
        }
    """.trimIndent()

    @Test
    fun publicRegistryUsesOnlyItsSearchEndpointAndTreatsHealthAsAnUntrustedReport() = runTest {
        val requested = mutableListOf<String>()
        val client = HttpClient(MockEngine { request ->
            requested += request.url.toString()
            respond(registryResponse, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val result = PublicA2aRegistrySource(client).search("GDP and jobs")
            assertEquals(1, requested.size)
            val requestUrl = Url(requested.single())
            assertEquals("a2aregistry.org", requestUrl.host)
            assertEquals("/api/agents", requestUrl.encodedPath)
            assertEquals("GDP and jobs", requestUrl.parameters["search"])
            assertEquals("20", requestUrl.parameters["limit"])
            assertEquals("0", requestUrl.parameters["offset"])
            assertEquals(1, result.size)
            val lead = result.single()
            assertEquals("https://data.example.org/.well-known/agent-card.json", lead.cardUrl)
            assertEquals("Economic research", lead.skills.single())
            assertTrue(lead.registryReportsHealthy)
            assertTrue(lead.registryReportsTaskTested)
            assertNull(lead.connectedProviderId, "a registry listing never connects or approves an agent")
            assertTrue(A2aAgentConnections.all().isEmpty())
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
