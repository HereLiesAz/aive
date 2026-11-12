package com.hereliesaz.geministrator.mcp

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpRegistryDirectoryTest {
    @Test
    fun searchesOnlyTheStoreCatalogAndPreservesPublisherDefinedVersions() = runTest {
        val requests = mutableListOf<String>()
        val document = """
            {
              "schema":1,"source":"mcp-registry-v0.1","count":2,
              "servers":[
                {"kind":"mcp-server","name":"default/weather","title":"Weather",
                 "description":"Local weather forecasts","version":"release.2026-Q4",
                 "verification":"registry_metadata_only","installable":false,
                 "remotes":[
                   {"type":"sse","url":"https://old.example.org/sse"},
                   {"type":"streamable-http","url":"https://weather.example.org/mcp"}
                 ]},
                {"kind":"mcp-server","name":"private/tools","title":"Unsafe",
                 "version":"1.2.0","verification":"registry_metadata_only","installable":false,
                 "remotes":[{"type":"streamable-http","url":"https://127.0.0.1/secrets"}]},
                {"kind":"mcp-server","name":"fake/package","title":"Install me",
                 "version":"3","verification":"registry_metadata_only","installable":true,
                 "remotes":[{"type":"streamable-http","url":"https://package.example.org/mcp"}]}
              ]
            }
        """.trimIndent()
        val client = HttpClient(MockEngine { request ->
            requests += request.url.toString()
            respond(document, HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            val results = McpRegistryDirectory(client).search("weather")
            assertEquals(1, requests.size)
            val url = Url(requests.single())
            assertEquals("azphalt.store", url.host)
            assertEquals("/api/mcp/servers", url.encodedPath)
            assertEquals("weather", url.parameters["search"])
            assertEquals(listOf(McpRegistryLead("Weather", "Local weather forecasts",
                "release.2026-Q4", "https://weather.example.org/mcp")), results)
        } finally { client.close() }
    }

    @Test
    fun cannotPromoteCatalogMetadataIntoAnMcpConnection() = runTest {
        val document = """
            {"schema":1,"source":"mcp-registry-v0.1","servers":[
              {"kind":"mcp-server","name":"weather","version":"snapshot",
               "verification":"verified","installable":false,
               "remotes":[{"type":"streamable-http","url":"https://weather.example.org/mcp"}]}
            ]}
        """.trimIndent()
        val client = HttpClient(MockEngine {
            respond(document, HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType, "application/json"))
        })
        try {
            assertTrue(McpRegistryDirectory(client).search("weather").isEmpty())
        } finally { client.close() }
    }

    @Test
    fun rejectsUnsafeCatalogEndpointsWithoutNormalizingThemIntoTrustedUrls() {
        for (url in listOf(
            "http://weather.example.org/mcp",
            "https://user:secret@weather.example.org/mcp",
            "https://weather.example.org/mcp?token=secret",
            "https://weather.example.org/mcp#secret",
            "https://localhost/mcp",
            "https://127.0.0.1/mcp",
            "https://internal.local/mcp",
            "https://weather.example.org:8443/mcp",
        )) {
            assertNull(publicRegistryMcpEndpoint(url), url)
        }
        assertEquals("https://weather.example.org/mcp",
            publicRegistryMcpEndpoint("https://weather.example.org/mcp"))
    }

    @Test
    fun cannotSearchSilentlyOrWithTooShortAQuery() = runTest {
        val client = HttpClient(MockEngine {
            error("No request should be made")
        })
        try {
            assertFailsWith<IllegalArgumentException> { McpRegistryDirectory(client).search("ab") }
            assertFailsWith<IllegalArgumentException> { McpRegistryDirectory(client).search(" ") }
        } finally { client.close() }
    }
}
