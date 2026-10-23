package com.hereliesaz.geministrator

data class RepositoryServiceCatalogEntry(
    val id: String,
    val displayName: String,
    val credentialUrl: String,
    val credentialLabel: String,
    val description: String,
)

object RepositoryServiceCatalog {
    const val GITHUB_ID = "github"
    const val GITLAB_ID = "gitlab"

    val entries: List<RepositoryServiceCatalogEntry> = listOf(
        RepositoryServiceCatalogEntry(
            id = GITHUB_ID,
            displayName = "GitHub",
            credentialUrl = "https://github.com/settings/tokens/new?scopes=repo,workflow&description=The%20Aive",
            credentialLabel = "GitHub personal access token",
            description = "Repository operations and GitHub Actions for linked GitHub projects. Create a classic token with the repo and workflow scopes.",
        ),
        RepositoryServiceCatalogEntry(
            id = GITLAB_ID,
            displayName = "GitLab",
            credentialUrl = "https://gitlab.com/-/user_settings/personal_access_tokens?name=The+Aive&scopes=api",
            credentialLabel = "GitLab personal access token",
            description = "Repository branches and merge requests for linked GitLab projects. Create a token with the api scope.",
        ),
    )

    fun entry(id: String): RepositoryServiceCatalogEntry? = entries.firstOrNull { it.id == id }
}
