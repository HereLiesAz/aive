package com.hereliesaz.geministrator

import com.hereliesaz.geministrator.domain.RepositorySource
import com.hereliesaz.geministrator.workflow.RepositoryServiceTokenProvider
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class RepositorySuggestion(
    val source: RepositorySource,
    val owner: String,
    val name: String,
    val defaultBranch: String? = null,
    val webUrl: String,
    val ownedByCurrentUser: Boolean,
    val description: String? = null,
) {
    val fullName: String get() = "$owner/$name"
}

class RemoteRepositoryDiscoveryClient(
    private val httpClient: HttpClient,
    private val githubTokenProvider: RepositoryServiceTokenProvider? = null,
    private val gitlabTokenProvider: RepositoryServiceTokenProvider? = null,
    private val githubBaseUrl: String = "https://api.github.com",
    private val gitlabBaseUrl: String = "https://gitlab.com/api/v4",
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    private var cachedGithubLogin: String? = null
    private var cachedGithubOwned: List<GitHubRepository>? = null
    private var cachedGitLabOwned: List<GitLabProject>? = null

    suspend fun search(
        source: RepositorySource,
        query: String,
        limit: Int = 12,
    ): List<RepositorySuggestion> {
        require(limit > 0) { "limit must be positive" }
        return when (source) {
            RepositorySource.GitHub -> searchGitHub(query, limit)
            RepositorySource.GitLab -> searchGitLab(query, limit)
            RepositorySource.Local -> emptyList()
        }
    }

    private suspend fun searchGitHub(query: String, limit: Int): List<RepositorySuggestion> {
        val tokenProvider = githubTokenProvider ?: return emptyList()
        val token = tokenProvider.getToken().trim().takeIf(String::isNotEmpty) ?: return emptyList()
        val login = cachedGithubLogin ?: run {
            val response = httpClient.get("$githubBaseUrl/user") { githubHeaders(token) }
            response.requireSuccess("read GitHub account")
            json.decodeFromString<GitHubUser>(response.bodyAsText()).login.also { cachedGithubLogin = it }
        }
        val owned = cachedGithubOwned ?: run {
            val response = httpClient.get("$githubBaseUrl/user/repos") {
                githubHeaders(token)
                url {
                    parameters.append("affiliation", "owner")
                    parameters.append("visibility", "all")
                    parameters.append("sort", "updated")
                    parameters.append("direction", "desc")
                    parameters.append("per_page", "50")
                }
            }
            response.requireSuccess("list GitHub repositories")
            json.decodeFromString<List<GitHubRepository>>(response.bodyAsText()).also { cachedGithubOwned = it }
        }

        val trimmed = query.trim()
        val searched = if (trimmed.isEmpty()) {
            emptyList()
        } else {
            val response = httpClient.get("$githubBaseUrl/search/repositories") {
                githubHeaders(token)
                url {
                    parameters.append("q", "$trimmed in:name")
                    parameters.append("sort", "updated")
                    parameters.append("order", "desc")
                    parameters.append("per_page", "30")
                }
            }
            response.requireSuccess("search GitHub repositories")
            json.decodeFromString<GitHubRepositorySearchResponse>(response.bodyAsText()).items
        }

        val ownIds = owned.mapTo(mutableSetOf()) { it.id }
        val normalizedQuery = trimmed.lowercase()
        return (owned + searched)
            .distinctBy(GitHubRepository::id)
            .map { repository ->
                RepositorySuggestion(
                    source = RepositorySource.GitHub,
                    owner = repository.owner.login,
                    name = repository.name,
                    defaultBranch = repository.defaultBranch,
                    webUrl = repository.htmlUrl,
                    ownedByCurrentUser = repository.id in ownIds || repository.owner.login.equals(login, ignoreCase = true),
                    description = repository.description,
                )
            }
            .filter { suggestion ->
                normalizedQuery.isEmpty() ||
                    suggestion.fullName.lowercase().contains(normalizedQuery) ||
                    suggestion.description.orEmpty().lowercase().contains(normalizedQuery)
            }
            .sortedForQuery(normalizedQuery)
            .take(limit)
    }

    private suspend fun searchGitLab(query: String, limit: Int): List<RepositorySuggestion> {
        val tokenProvider = gitlabTokenProvider ?: return emptyList()
        val token = tokenProvider.getToken().trim().takeIf(String::isNotEmpty) ?: return emptyList()
        val owned = cachedGitLabOwned ?: run {
            val response = httpClient.get("$gitlabBaseUrl/projects") {
                gitLabHeaders(token)
                url {
                    parameters.append("owned", "true")
                    parameters.append("simple", "true")
                    parameters.append("order_by", "last_activity_at")
                    parameters.append("sort", "desc")
                    parameters.append("per_page", "50")
                }
            }
            response.requireSuccess("list GitLab repositories")
            json.decodeFromString<List<GitLabProject>>(response.bodyAsText()).also { cachedGitLabOwned = it }
        }

        val trimmed = query.trim()
        val searched = if (trimmed.isEmpty()) {
            emptyList()
        } else {
            val response = httpClient.get("$gitlabBaseUrl/projects") {
                gitLabHeaders(token)
                url {
                    parameters.append("search", trimmed)
                    parameters.append("simple", "true")
                    parameters.append("order_by", "last_activity_at")
                    parameters.append("sort", "desc")
                    parameters.append("per_page", "30")
                }
            }
            response.requireSuccess("search GitLab repositories")
            json.decodeFromString<List<GitLabProject>>(response.bodyAsText())
        }

        val ownIds = owned.mapTo(mutableSetOf()) { it.id }
        val normalizedQuery = trimmed.lowercase()
        return (owned + searched)
            .distinctBy(GitLabProject::id)
            .map { project ->
                val path = project.pathWithNamespace.trim('/').split('/').filter(String::isNotBlank)
                RepositorySuggestion(
                    source = RepositorySource.GitLab,
                    owner = path.dropLast(1).joinToString("/"),
                    name = path.lastOrNull() ?: project.name,
                    defaultBranch = project.defaultBranch,
                    webUrl = project.webUrl,
                    ownedByCurrentUser = project.id in ownIds,
                    description = project.description,
                )
            }
            .filter { suggestion ->
                normalizedQuery.isEmpty() ||
                    suggestion.fullName.lowercase().contains(normalizedQuery) ||
                    suggestion.description.orEmpty().lowercase().contains(normalizedQuery)
            }
            .sortedForQuery(normalizedQuery)
            .take(limit)
    }

    private fun io.ktor.client.request.HttpRequestBuilder.githubHeaders(token: String) {
        header(HttpHeaders.Authorization, "Bearer $token")
        header(HttpHeaders.Accept, "application/vnd.github+json")
        header("X-GitHub-Api-Version", "2022-11-28")
    }

    private fun io.ktor.client.request.HttpRequestBuilder.gitLabHeaders(token: String) {
        header("PRIVATE-TOKEN", token)
    }

    private suspend fun HttpResponse.requireSuccess(operation: String) {
        if (status.value in 200..299) return
        val detail = bodyAsText().take(300)
        error("Unable to $operation: HTTP ${status.value}${detail.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}")
    }
}

private fun List<RepositorySuggestion>.sortedForQuery(query: String): List<RepositorySuggestion> =
    sortedWith(
        compareByDescending<RepositorySuggestion> { it.ownedByCurrentUser }
            .thenByDescending { query.isNotEmpty() && it.fullName.lowercase().startsWith(query) }
            .thenByDescending { query.isNotEmpty() && it.name.lowercase().startsWith(query) }
            .thenBy { it.fullName.lowercase() },
    )

@Serializable
private data class GitHubUser(val login: String)

@Serializable
private data class GitHubOwner(val login: String)

@Serializable
private data class GitHubRepository(
    val id: Long,
    val name: String,
    val owner: GitHubOwner,
    @SerialName("default_branch") val defaultBranch: String? = null,
    @SerialName("html_url") val htmlUrl: String,
    val description: String? = null,
)

@Serializable
private data class GitHubRepositorySearchResponse(
    val items: List<GitHubRepository> = emptyList(),
)

@Serializable
private data class GitLabProject(
    val id: Long,
    val name: String,
    @SerialName("path_with_namespace") val pathWithNamespace: String,
    @SerialName("default_branch") val defaultBranch: String? = null,
    @SerialName("web_url") val webUrl: String,
    val description: String? = null,
)
