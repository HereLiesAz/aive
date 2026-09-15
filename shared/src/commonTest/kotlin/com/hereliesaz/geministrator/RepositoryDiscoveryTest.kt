package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.RepositorySource
import com.hereliesaz.geministrator.workflow.RepositoryServiceTokenProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepositoryDiscoveryTest {
    @Test
    fun githubSearchRanksOwnedRepositoriesBeforeExternalSuggestions() = runBlocking {
        val engine = MockEngine { request ->
            val body = when (request.url.encodedPath) {
                "/user" -> """{"login":"az"}"""
                "/user/repos" -> """
                    [
                      {"id":1,"name":"haive","owner":{"login":"az"},"default_branch":"main","html_url":"https://github.com/az/haive","description":"Company OS"},
                      {"id":2,"name":"unrelated","owner":{"login":"az"},"default_branch":"main","html_url":"https://github.com/az/unrelated","description":null}
                    ]
                """.trimIndent()
                "/search/repositories" -> """
                    {"items":[
                      {"id":1,"name":"haive","owner":{"login":"az"},"default_branch":"main","html_url":"https://github.com/az/haive","description":"duplicate owned result"},
                      {"id":9,"name":"haive-tools","owner":{"login":"someone-else"},"default_branch":"trunk","html_url":"https://github.com/someone-else/haive-tools","description":"External helpers"}
                    ]}
                """.trimIndent()
                else -> error("Unexpected request ${request.url}")
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = HttpClient(engine)
        val discovery = RemoteRepositoryDiscoveryClient(
            httpClient = client,
            githubTokenProvider = RepositoryServiceTokenProvider { "token" },
        )

        val results = discovery.search(RepositorySource.GitHub, "haive")

        assertEquals(listOf("az/haive", "someone-else/haive-tools"), results.map { it.fullName })
        assertTrue(results.first().ownedByCurrentUser)
        assertFalse(results.last().ownedByCurrentUser)
        assertEquals("trunk", results.last().defaultBranch)
        client.close()
    }

    @Test
    fun githubBlankSearchShowsOwnedRepositoriesWithoutGlobalSearch() = runBlocking {
        var globalSearchCalled = false
        val engine = MockEngine { request ->
            val body = when (request.url.encodedPath) {
                "/user" -> """{"login":"az"}"""
                "/user/repos" -> """[{"id":1,"name":"mine","owner":{"login":"az"},"default_branch":"main","html_url":"https://github.com/az/mine","description":null}]"""
                "/search/repositories" -> {
                    globalSearchCalled = true
                    """{"items":[]}"""
                }
                else -> error("Unexpected request ${request.url}")
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = HttpClient(engine)
        val discovery = RemoteRepositoryDiscoveryClient(
            httpClient = client,
            githubTokenProvider = RepositoryServiceTokenProvider { "token" },
        )

        val results = discovery.search(RepositorySource.GitHub, "")

        assertEquals(listOf("az/mine"), results.map { it.fullName })
        assertFalse(globalSearchCalled)
        client.close()
    }

    @Test
    fun gitlabSearchRanksOwnedProjectsBeforeOtherVisibleProjects() = runBlocking {
        val engine = MockEngine { request ->
            val owned = request.url.parameters["owned"] == "true"
            val body = if (owned) {
                """[{"id":4,"name":"Haive","path_with_namespace":"az/haive","default_branch":"main","web_url":"https://gitlab.com/az/haive","description":"Mine"}]"""
            } else {
                """[
                    {"id":4,"name":"Haive","path_with_namespace":"az/haive","default_branch":"main","web_url":"https://gitlab.com/az/haive","description":"Mine"},
                    {"id":7,"name":"Haive Examples","path_with_namespace":"community/haive-examples","default_branch":"develop","web_url":"https://gitlab.com/community/haive-examples","description":"Examples"}
                ]"""
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val client = HttpClient(engine)
        val discovery = RemoteRepositoryDiscoveryClient(
            httpClient = client,
            gitlabTokenProvider = RepositoryServiceTokenProvider { "token" },
        )

        val results = discovery.search(RepositorySource.GitLab, "haive")

        assertEquals(listOf("az/haive", "community/haive-examples"), results.map { it.fullName })
        assertTrue(results.first().ownedByCurrentUser)
        assertFalse(results.last().ownedByCurrentUser)
        client.close()
    }
}
