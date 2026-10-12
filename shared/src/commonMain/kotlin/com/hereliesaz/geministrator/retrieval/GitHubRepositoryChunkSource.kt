package com.hereliesaz.geministrator.retrieval

import com.hereliesaz.geministrator.domain.RepositoryRef
import com.hereliesaz.geministrator.domain.RepositorySource
import com.hereliesaz.geministrator.providers.AgentTaskRequest
import com.hereliesaz.geministrator.vector.BuiltInTextEmbedder
import com.hereliesaz.geministrator.workflow.GitHubTokenProvider
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Chunks of a GitHub repository's files, read through the REST API for requests that name a
 * GitHub repository.
 *
 * Reading every file on every request would be slow and costly, so this reads the file tree (at
 * most every [treeRefresh]), picks the [maxFiles] text files whose paths share the most words with
 * the request (READMEs always qualify), and fetches only those, caching each file's chunks by blob
 * SHA. Generated, vendored and oversized files are skipped.
 */
class GitHubRepositoryChunkSource(
    private val tokenProvider: GitHubTokenProvider,
    private val httpClient: HttpClient,
    private val baseUrl: String = "https://api.github.com",
    private val maxFiles: Int = 24,
    private val maxFileBytes: Long = 48_000,
    private val treeRefresh: Duration = 5.minutes,
    private val maxCachedFiles: Int = 512,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) : ContextChunkSource {
    private val json = Json { ignoreUnknownKeys = true }
    private val trees = HashMap<String, Pair<TimeMark, List<TreeEntry>>>()
    private val files = LinkedHashMap<String, List<String>>()

    override suspend fun chunks(request: AgentTaskRequest): List<TextChunk> {
        val repository = request.repository?.takeIf { it.source == RepositorySource.GitHub } ?: return emptyList()
        val token = tokenProvider.getToken().trim()
        if (token.isEmpty()) return emptyList()
        val entries = tree(repository, token)
        val queryWords = BuiltInTextEmbedder.tokens(request.retrievalQuery()).toSet()
        val chosen = entries
            .map { entry -> entry to pathScore(entry.path, queryWords) }
            .filter { (_, score) -> score > 0 }
            .sortedWith(compareByDescending<Pair<TreeEntry, Int>> { it.second }.thenBy { it.first.path })
            .take(maxFiles)
            .map { it.first }
        return chosen.flatMap { entry ->
            fileChunks(repository, entry, token).mapIndexed { i, text ->
                TextChunk(
                    id = "repo:${repository.owner}/${repository.name}:${entry.path}#$i",
                    label = "Repository file ${entry.path}" + if (i > 0) " (part ${i + 1})" else "",
                    text = text,
                )
            }
        }
    }

    private suspend fun tree(repository: RepositoryRef, token: String): List<TreeEntry> {
        val ref = repository.defaultBranch?.takeIf(String::isNotBlank) ?: "HEAD"
        val key = "${repository.owner}/${repository.name}@$ref"
        trees[key]?.let { (fetchedAt, entries) -> if (fetchedAt.elapsedNow() < treeRefresh) return entries }
        val body = httpClient.get("${repositoryUrl(repository)}/git/trees/${ref.encodeURLPathPart()}") {
            parameter("recursive", "1")
            githubHeaders(token, "application/vnd.github+json")
        }.requireSuccessBody("read the file tree of $key")
        val entries = json.decodeFromString(TreeResponse.serializer(), body).tree
            .filter { it.type == "blob" && (it.size ?: Long.MAX_VALUE) <= maxFileBytes && isReadable(it.path) }
        trees[key] = timeSource.markNow() to entries
        return entries
    }

    private suspend fun fileChunks(repository: RepositoryRef, entry: TreeEntry, token: String): List<String> {
        files[entry.sha]?.let { return it }
        val text = httpClient.get("${repositoryUrl(repository)}/git/blobs/${entry.sha.encodeURLPathPart()}") {
            githubHeaders(token, "application/vnd.github.raw+json")
        }.requireSuccessBody("read ${entry.path}")
        val chunks = if ('\u0000' in text) emptyList() else chunkText(text)
        files[entry.sha] = chunks
        while (files.size > maxCachedFiles) files.remove(files.keys.first())
        return chunks
    }

    private fun repositoryUrl(repository: RepositoryRef) =
        "$baseUrl/repos/${repository.owner.encodeURLPathPart()}/${repository.name.encodeURLPathPart()}"

    private fun HttpRequestBuilder.githubHeaders(token: String, accept: String) {
        header(HttpHeaders.Accept, accept)
        header(HttpHeaders.Authorization, "Bearer $token")
        header("X-GitHub-Api-Version", "2026-03-10")
    }

    private suspend fun HttpResponse.requireSuccessBody(operation: String): String {
        val body = bodyAsText()
        if (status.value !in 200..299) error("Unable to $operation: HTTP ${status.value}")
        return body
    }

    @Serializable
    internal data class TreeResponse(val tree: List<TreeEntry> = emptyList())

    @Serializable
    internal data class TreeEntry(val path: String, val type: String, val sha: String, val size: Long? = null)

    internal companion object {
        private val TEXT_EXTENSIONS = setOf(
            "kt", "kts", "java", "md", "txt", "py", "js", "ts", "tsx", "jsx", "go", "rs", "swift", "c", "h",
            "cpp", "hpp", "cs", "rb", "php", "sh", "yml", "yaml", "toml", "json", "xml", "gradle", "sql",
            "sq", "html", "css", "scss", "proto", "properties",
        )
        private val SKIPPED_DIRECTORIES = setOf(
            "build", "dist", "out", "target", "node_modules", "vendor", ".git", ".gradle", ".idea", "generated",
        )
        private val SKIPPED_FILES = setOf("package-lock.json", "yarn.lock", "pnpm-lock.yaml", "gradle.lockfile")

        fun isReadable(path: String): Boolean {
            val parts = path.split('/')
            val name = parts.last()
            return parts.dropLast(1).none { it in SKIPPED_DIRECTORIES } &&
                name !in SKIPPED_FILES &&
                name.substringAfterLast('.', "").lowercase() in TEXT_EXTENSIONS
        }

        /** Words the path shares with the request; a README always scores. */
        fun pathScore(path: String, queryWords: Set<String>): Int {
            val words = BuiltInTextEmbedder.tokens(path.replace('/', ' ').replace('.', ' '))
            val shared = words.count { it in queryWords }
            return shared + if (path.substringAfterLast('/').startsWith("README", ignoreCase = true)) 1 else 0
        }
    }
}
