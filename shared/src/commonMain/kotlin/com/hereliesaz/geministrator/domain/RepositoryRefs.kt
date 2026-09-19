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
    RepositorySource.GitHub -> remoteUrl.toBrowserUrl() ?: "https://github.com/$owner/$name"
    RepositorySource.GitLab -> remoteUrl.toBrowserUrl() ?: "https://gitlab.com/$owner/$name"
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

    val explicitHost = remoteRepositoryHost(cleanLocator)
    if (explicitHost != null) {
        val expectedHost = when (source) {
            RepositorySource.GitHub -> "github.com"
            RepositorySource.GitLab -> "gitlab.com"
            RepositorySource.Local -> error("Local repositories have no remote host")
        }
        require(explicitHost.equals(expectedHost, ignoreCase = true)) {
            "${source.displayName()} repository URL must use $expectedHost"
        }
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
    // Persist only a canonical credential-free remote. User-info, tokens, query parameters, and
    // fragments from pasted clone URLs must never enter project state or model prompts.
    val remoteUrl = when (source) {
        RepositorySource.GitHub -> "https://github.com/$owner/$name"
        RepositorySource.GitLab -> "https://gitlab.com/$owner/$name"
        RepositorySource.Local -> error("Local repositories do not have remote URLs")
    }

    return RepositoryRef(
        owner = owner,
        name = name,
        defaultBranch = defaultBranch,
        source = source,
        remoteUrl = remoteUrl,
    ).normalized()
}

private fun remoteRepositoryHost(locator: String): String? {
    val clean = locator.substringBefore('?').substringBefore('#').trim()
    return when {
        clean.startsWith("git@", ignoreCase = true) ->
            clean.substringAfter('@').substringBefore(':').takeIf(String::isNotBlank)
        clean.startsWith("ssh://", ignoreCase = true) -> {
            val authority = clean.substringAfter("://").substringBefore('/')
            authority.substringAfter('@').substringBefore(':').takeIf(String::isNotBlank)
        }
        "://" in clean -> {
            val authority = clean.substringAfter("://").substringBefore('/')
            authority.substringAfter('@').substringBefore(':').takeIf(String::isNotBlank)
        }
        else -> null
    }
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

private fun String?.toBrowserUrl(): String? {
    val remote = this?.trim()?.takeIf(String::isNotEmpty) ?: return null
    return when {
        remote.startsWith("http://") || remote.startsWith("https://") ->
            remote.substringBefore('?').substringBefore('#').trimEnd('/').removeSuffix(".git")

        remote.startsWith("git@") && ':' in remote -> {
            val host = remote.substringAfter("git@").substringBefore(':')
            val path = remote.substringAfter(':').trim('/').removeSuffix(".git")
            if (host.isBlank() || path.isBlank()) null else "https://$host/$path"
        }

        remote.startsWith("ssh://") -> {
            val authorityAndPath = remote.substringAfter("ssh://")
            val authority = authorityAndPath.substringBefore('/')
            val host = authority.substringAfter('@').substringBefore(':')
            val path = authorityAndPath.substringAfter('/', missingDelimiterValue = "").trim('/').removeSuffix(".git")
            if (host.isBlank() || path.isBlank()) null else "https://$host/$path"
        }

        else -> null
    }
}

private fun localRepositoryName(path: String): String {
    val clean = path.trim().trimSurroundingQuotes().trimEnd('/', '\\')
    return clean.substringAfterLast('/').substringAfterLast('\\')
}

private fun String.trimSurroundingQuotes(): String =
    removePrefix("\"").removeSuffix("\"").removePrefix("'").removeSuffix("'")
