package com.hereliesaz.geministrator.retrieval

import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.TaskRunId
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.workflow.GitHubTokenProvider
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

class ContextRetrievalTest {
    private fun request(objective: String, repository: RepositoryRef? = null) = AgentTaskRequest(
        taskRunId = TaskRunId("t"),
        objective = objective,
        roleInstructions = "",
        acceptanceCriteria = emptyList(),
        repository = repository,
    )

    @Test
    fun chunksPackParagraphsAndSplitLongOnes() {
        val text = "alpha one\n\nbeta two\n\n" + (1..40).joinToString("\n") { "line $it of a long paragraph" }
        val chunks = chunkText(text, maxChars = 200)
        assertTrue(chunks.all { it.length <= 200 }, chunks.toString())
        assertTrue(chunks.first().startsWith("alpha one\n\nbeta two"), chunks.first())
        assertEquals(text.lines().filter { it.isNotBlank() }, chunks.flatMap { it.lines() }.filter { it.isNotBlank() })
    }

    @Test
    fun retrieverRanksChunksBySimilarityToTheTask() = runBlocking {
        val source = ContextChunkSource {
            listOf(
                TextChunk("db", "Schema", "The workflow database schema stores runs, events and artifacts in SQL tables."),
                TextChunk("ui", "Splash", "The splash screen animation fades the logo in over half a second."),
            )
        }
        val hits = SimilarityContextRetriever(listOf(source), maxChunks = 1).retrieve(request("Add a SQL table to the workflow database schema"))
        assertEquals(listOf("db"), hits.map { it.chunk.id })
    }

    @Test
    fun aFailingSourceContributesNothing() = runBlocking {
        val broken = ContextChunkSource { error("offline") }
        val working = ContextChunkSource { listOf(TextChunk("db", "Schema", "workflow database schema")) }
        val hits = SimilarityContextRetriever(listOf(broken, working)).retrieve(request("workflow database schema"))
        assertEquals(listOf("db"), hits.map { it.chunk.id })
    }

    @Test
    fun githubSourceReadsOnlyRelevantTextFilesAndCachesThem() = runBlocking {
        val requested = mutableListOf<String>()
        val engine = MockEngine { call ->
            requested += call.url.encodedPath
            val body = when (call.url.encodedPath) {
                "/repos/az/app/git/trees/main" -> """
                    {"sha":"t1","tree":[
                      {"path":"README.md","type":"blob","sha":"b-readme","size":40},
                      {"path":"src/WorkflowDatabase.kt","type":"blob","sha":"b-db","size":60},
                      {"path":"src/SplashScreen.kt","type":"blob","sha":"b-splash","size":60},
                      {"path":"build/generated/WorkflowDatabaseImpl.kt","type":"blob","sha":"b-gen","size":60},
                      {"path":"assets/database.png","type":"blob","sha":"b-png","size":60},
                      {"path":"src","type":"tree","sha":"t-src"}
                    ]}
                """.trimIndent()
                "/repos/az/app/git/blobs/b-readme" -> "An app."
                "/repos/az/app/git/blobs/b-db" -> "class WorkflowDatabase { fun migrate() {} }"
                else -> error("Unexpected request ${call.url}")
            }
            respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val source = GitHubRepositoryChunkSource(GitHubTokenProvider { "token" }, HttpClient(engine))
        val repository = RepositoryRef(owner = "az", name = "app", defaultBranch = "main")

        val chunks = source.chunks(request("Migrate the workflow database", repository))
        assertEquals(setOf("Repository file README.md", "Repository file src/WorkflowDatabase.kt"), chunks.map { it.label }.toSet())
        assertFalse(requested.any { "b-splash" in it || "b-gen" in it || "b-png" in it }, requested.toString())

        requested.clear()
        source.chunks(request("Migrate the workflow database", repository))
        assertTrue(requested.isEmpty(), "tree and files cached: $requested")
    }

    @Test
    fun githubSourceIgnoresOtherRepositoryKinds() = runBlocking {
        val source = GitHubRepositoryChunkSource(GitHubTokenProvider { "token" }, HttpClient(MockEngine { error("no request expected") }))
        assertTrue(source.chunks(request("anything")).isEmpty())
    }
}
