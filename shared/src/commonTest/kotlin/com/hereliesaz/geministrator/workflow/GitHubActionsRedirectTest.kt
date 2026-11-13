package com.hereliesaz.geministrator.workflow

import com.hereliesaz.geministrator.domain.RepositoryRef
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GitHubActionsRedirectTest {
    private val repository = RepositoryRef("o", "r", defaultBranch = "main")

    private fun client(location: String, seen: MutableList<HttpRequestData>): GitHubRestActionsClient {
        val http = HttpClient(MockEngine { request ->
            seen += request
            if (request.url.host == "api.github.com") {
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, location))
            } else {
                respond(byteArrayOf(1, 2, 3), HttpStatusCode.OK)
            }
        })
        return GitHubRestActionsClient(tokenProvider = { "tok" }, httpClient = http)
    }

    @Test
    fun theSingleRedirectIsFollowedWithoutTheAuthorizationHeader() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        val bytes = client("https://pipelines.actions.githubusercontent.com/abc/zip", seen)
            .downloadArtifact(repository, "7")
        assertContentEquals(byteArrayOf(1, 2, 3), bytes)
        assertEquals(2, seen.size)
        assertNotNull(seen[0].headers[HttpHeaders.Authorization])
        assertNull(seen[1].headers[HttpHeaders.Authorization])
    }

    @Test
    fun aLocationOnAnotherHostIsRefused() = runTest {
        val seen = mutableListOf<HttpRequestData>()
        assertFailsWith<IllegalStateException> {
            client("https://other.example.com/zip", seen).downloadArtifact(repository, "7")
        }
        assertEquals(1, seen.size)
    }

    @Test
    fun targetAllowlistRequiresHttpsAndGitHubHosts() {
        assertNull(githubArtifactDownloadTarget("http://pipelines.actions.githubusercontent.com/x"))
        assertNull(githubArtifactDownloadTarget("https://user@pipelines.actions.githubusercontent.com/x"))
        assertNull(githubArtifactDownloadTarget("https://actions.githubusercontent.com.example.org/x"))
        assertNull(githubArtifactDownloadTarget(null))
        assertNotNull(githubArtifactDownloadTarget("https://productionresultssa1.blob.core.windows.net/x?sig=1"))
    }
}
