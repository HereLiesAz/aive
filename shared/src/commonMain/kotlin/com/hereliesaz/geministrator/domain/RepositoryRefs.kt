package com.hereliesaz.geministrator.domain

fun RepositorySource.displayName(): String = when (this) {
    RepositorySource.GitHub -> "GitHub"
    RepositorySource.GitLab -> "GitLab"
    RepositorySource.Local -> "Local Git"
}

fun RepositoryRef.displayName(): String = when (source) {
    RepositorySource.Local -> name
    RepositorySource.GitHub,
    RepositorySource.GitLab,
    -> "$owner/$name"
}

fun RepositoryRef.locationLabel(): String = when (source) {
    RepositorySource.Local -> localPath.orEmpty()
    RepositorySource.GitHub,
    RepositorySource.GitLab,
    -> remoteUrl ?: displayName()
}

fun RepositoryRef.locatorInput(): String = when (source) {
    RepositorySource.Local -> localPath.orEmpty()
    RepositorySource.GitHub,
    RepositorySource.GitLab,
    -> remoteUrl ?: displayName()
}

fun RepositoryRef.remoteBrowserUrl(): String? = when (source) {
    RepositorySource.Local -> null
    RepositorySource.GitHub -> remoteUrl
        ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        ?: "https://github.com/$owner/$name"
    RepositorySource.GitLab -> remoteUrl
        ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        ?: "https://gitlab.com/$owner/$name"
}

fun RepositoryRef.normalized(): RepositoryRef {
    val branch = defaultBranch?.trim()?.takeIf(String::isNotEmpty)
    return when (source) {
        RepositorySource.Local -> {
            val path = localPath?.trim()?.trimSurroundingQuotes().orEmpty()
            require(path.isNotEmpty()) { "Local Git folder is required" }
            val cleanName = name.trim().ifEmpty { localRepositoryName(path) }
            require(cleanName.isNotEmpty()) { "Local Git folder must end in a repository directory name" }
            copy(
                owner = "",
                name = cleanName,
                defaultBranch = branch,
                remoteUrl = null,
                localPath = path,
            )
        }

        RepositorySource.GitHub,
        RepositorySource.GitLab,
        -> {
            val cleanOwner = owner.trim().trim('/')
            val cleanName = name.trim().removeSuffix(".git").trim('/')
            require(cleanOwner.isNotEmpty()) { "Repository owner/group is required" }
            require(cleanName.isNotEmpty()) { "Repository name is required" }
            copy(
                owner = cleanOwner,
                name = cleanName,
                defaultBranch = branch,
                remoteUrl = remoteUrl?.trim()?.takeIf(String::isNotEmpty),
                localPath = null,
            )
        }
    }
}

fun parseRepositoryRef(
    source: RepositorySource,
    locator: String,
    defaultBranch: String? = null,
): RepositoryRef {
    val cleanLocator = locator.trim().trimSurroundingQuotes()
    require(cleanLocator.isNotEmpty()) { "Repository location is required" }

    if (source == RepositorySource.Local) {
        return RepositoryRef(
            owner = "",
            name = localRepositoryName(cleanLocator),
            defaultBranch = defaultBranch,
            source = RepositorySource.Local,
            localPath = cleanLocator,
        ).normalized()
    }

    val repositoryPath = remoteRepositoryPath(cleanLocator)
    val parts = repositoryPath
        .trim('/')
        .removeSuffix(".git")
        .split('/')
        .filter(String::isNotBlank)

    require(parts.size >= 2) {
        "Use owner/repository, group/repository, or a full ${source.displayName()} repository URL"
    }
    if (source == RepositorySource.GitHub) {
        require(parts.size == 2) {
            "GitHub repositories must use owner/repository"
        }
    }

    val name = parts.last()
    val owner = parts.dropLast(1).joinToString("/")
    val remoteUrl = when {
        cleanLocator.startsWith("http://") || cleanLocator.startsWith("https://") || cleanLocator.startsWith("git@") || cleanLocator.startsWith("ssh://") -> cleanLocator
        source == RepositorySource.GitHub -> "https://github.com/$owner/$name"
        else -> "https://gitlab.com/$owner/$name"
    }

    return RepositoryRef(
        owner = owner,
        name = name,
        defaultBranch = defaultBranch,
        source = source,
        remoteUrl = remoteUrl,
    ).normalized()
}

private fun remoteRepositoryPath(locator: String): String {
    val withoutQuery = locator.substringBefore('?').substringBefore('#').trimEnd('/')
    if (withoutQuery.startsWith("git@") && ':' in withoutQuery) {
        return withoutQuery.substringAfter(':')
    }
    if ("://" in withoutQuery) {
        val withoutScheme = withoutQuery.substringAfter("://")
        return withoutScheme.substringAfter('/', missingDelimiterValue = "")
    }
    return withoutQuery
}

private fun localRepositoryName(path: String): String {
    val clean = path.trim().trimSurroundingQuotes().trimEnd('/', '\\')
    return clean.substringAfterLast('/').substringAfterLast('\\')
}

private fun String.trimSurroundingQuotes(): String =
    removePrefix("\"").removeSuffix("\"").removePrefix("'").removeSuffix("'")
